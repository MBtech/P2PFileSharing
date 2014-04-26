package filesharing.core.client;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.BitSet;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import filesharing.core.connection.PeerConnection;
import filesharing.core.connection.TrackerConnection;
import filesharing.core.processor.TrackerResponseProcessor;
import filesharing.exception.DownloadCompleteException;
import filesharing.exception.NoMetadataException;
import filesharing.exception.NoNewBlocksForDownloadException;
import filesharing.message.tracker.request.PeerListRequestMessage;
import filesharing.message.tracker.request.TrackerRequestMessage;
import filesharing.message.tracker.response.PeerListResponseMessage;
import filesharing.message.tracker.response.SuccessResponseMessage;
import filesharing.message.tracker.response.TrackerErrorResponseMessage;

/**
 * File metadata and content downloader - makes requests to peers and handles
 * responses
 */
public class FileDownloader implements Runnable, TrackerResponseProcessor {
	
	/**
	 * Delay between asking tracker for new peers
	 */
	public static final int TRACKER_QUERY_DELAY = 1000; // 30 seconds
	
	/**
	 * Downloader thread
	 */
	private Thread runnerThread = new Thread(this);
	
	/**
	 * Pool of downloading threads
	 */
	private ExecutorService executor = Executors.newCachedThreadPool();
	
	/**
	 * Pool of scheduled/periodic tasks
	 */
	private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
	
	/**
	 * File transfer associated with this downloader
	 */
	private FileTransfer fileTransfer;
	
	/**
	 * An object for random access to the local file
	 */
	private RandomAccessFile fileAccess;
	
	/**
	 * Set of blocks assigned for downloading
	 */
	private BitSet blocksForDownload;
	
	/**
	 * List of seeders
	 */
	private Set<PeerConnection> seedList = Collections.synchronizedSet(new TreeSet<PeerConnection>());
	
	/**
	 * Constructor
	 * @param file_transfer information about file to download
	 * @throws FileNotFoundException 
	 */
	public FileDownloader(FileTransfer file_transfer) throws FileNotFoundException {
		this.fileTransfer = file_transfer;
	}
	
	/**
	 * Returns the file transfer instance associated with this downloader
	 * @return file transfer
	 */
	protected FileTransfer getFileTransfer() {
		return fileTransfer;
	}
	
	/**
	 * Returns the bit set with the blocks that have been assigned for download
	 * @return blocks for download
	 */
	protected BitSet getBlocksForDownload() {
		return blocksForDownload;
	}
	
	/**
	 * Returns the list of seeders
	 * @return set of information of peers
	 */
	protected Set<PeerConnection> seedList() {
		return seedList;
	}
	
	/**
	 * Writes a file block to local storage
	 * @param index block number (zero-indexed)
	 * @param block the contents of the block
	 * @throws IOException on write operation failure
	 */
	protected synchronized void writeBlock(int index, byte[] block) throws IOException {
		// check if we have the block
		if(fileTransfer.getBlocksPresent().get(index)) {
			// ignore it - dont overwrite
			// should we throw an exception?
			return;
		}
		
		// process
		fileAccess.seek(fileTransfer.blockSize() * index);
		fileAccess.write(block);
		fileTransfer.getBlocksPresent().set(index);
	}
	
	/**
	 * Returns a block index for a thread to download
	 * @return block index
	 */
	protected synchronized int getBlockIndexForDownload(FileDownloaderThread peer) {
		String filename = fileTransfer.filename();
		BitSet local_blocks = fileTransfer.getBlocksPresent();
		BitSet peer_blocks = peer.getPeerBlocksPresent();
		
		// check if we already have all the blocks
		if(local_blocks.cardinality() == fileTransfer.numBlocks()) {
			throw new DownloadCompleteException("download of file " + filename + " is finished");
		}
		
		// check which blocks peer has that we dont have
		BitSet peer_new_blocks = new BitSet();
		peer_new_blocks.or(peer_blocks);
		peer_new_blocks.andNot(local_blocks);
		
		// check which blocks peer has that we dont have
		// and that we have not requested for download yet
		BitSet blocks_interest = new BitSet();
		blocks_interest.or(peer_new_blocks);
		blocks_interest.andNot(this.blocksForDownload);
		
		//check if peer does not have any new blocks at all
		if(peer_new_blocks.cardinality() == 0) {
			throw new NoNewBlocksForDownloadException("peer has no new blocks for file " + filename);
		}
		
		// check if peer does not have any blocks that are not present or assigned
		if(blocks_interest.cardinality() == 0) {
			// assign one that has already been assigned then ("endgame"?)
			return peer_new_blocks.nextSetBit(0);
		}
		else {
			// assign a block that has never been requested for download
			int block_index = blocks_interest.nextSetBit(0);
			this.blocksForDownload.set(block_index);
			return block_index;
		}
		
	}
	
	/**
	 * Updates peer list
	 */
	private synchronized void updatePeerList() {
		String filename = fileTransfer.filename();
		
		// connect to trackers and ask for peers for this file
		for(TrackerConnection tracker : fileTransfer.getTrackers()) {
			try {
				// send request
				TrackerRequestMessage msg = new PeerListRequestMessage(filename);
				tracker.sendMessage(msg, this);
			}
			catch (IOException e) {
				// it failed... that's life, ignore it - neeext!
				log(e.getMessage());
			}
		}
	}
	
	public void fetchMetadata() throws IOException {
		// setup
		String filename = fileTransfer.filename();
		
		// update the peer list of peers
		updatePeerList();
		
		// connect to peers and ask for metadata (one at a time)
		for(PeerConnection peer : seedList()) {
			try {
				FileDownloaderThread fdt = new FileDownloaderThread(this, peer);
				fdt.requestMetadata();
				// stop if we get the metadata
				if(fileTransfer.hasMetadata()) break;
			}
			catch(IOException | ClassNotFoundException e) {
				// communication failed or bad response from peer - ignore it
				// do nothing and move on to next peer
			}
		}
		
		// check if after all this we indeed have the metadata
		if(!fileTransfer.hasMetadata()) {
			// no?! die miserably then
			log("could not fetch metadata for file " + filename);
			throw new NoMetadataException("all the peers were mean to me: could not fetch metadata");
		}
	}

	/**
	 * Starts the downloading of the file
	 */
	@Override
	public void run() {
		try {
			// initialize
			blocksForDownload = new BitSet(fileTransfer.numBlocks());
			this.fileAccess = new RandomAccessFile(fileTransfer.getLocalFile(), "rws");
			
			final FileDownloader downloader = this;
			
			// setup periodic tasks
			// update peer lists regularly
			Runnable periodicPeerListUpdateTask = new Runnable() {
				@Override public void run() {
					downloader.log("Updating peer list");
					downloader.updatePeerList();
				}
			};
			this.scheduler.scheduleAtFixedRate(periodicPeerListUpdateTask, 0, TRACKER_QUERY_DELAY, TimeUnit.SECONDS);
			
			// start a downloader thread for every peer
			for(PeerConnection peer_info : seedList()) {
				executor.execute(new FileDownloaderThread(this, peer_info));
			}
		}
		catch(FileNotFoundException e) {
			// something weird happened
			log("Error starting downloader - local file not found");
		}
	}
	
	/**
	 * Starts execution of the file downloader in a new thread
	 */
	public void start() {
		if(!runnerThread.isAlive()) {
			runnerThread.start();
		}
	}
	
	/**
	 * Logs a message to console
	 * @param msg message to log
	 */
	protected void log(String msg) {
		fileTransfer.log("[DOWN] " + msg);
	}
	
	/**
	 * process success response from tracker
	 */
	@Override
	public void processSuccessResponseMessage(SuccessResponseMessage msg) {
		/* success! nothing to do... */
	}

	/**
	 * process error response from tracker
	 */
	@Override
	public void processTrackerErrorResponseMessage(TrackerErrorResponseMessage msg) {
		/* hmm... just ignore errors from tracker */
		/* or should we also throw tracker error exceptions? */
		log("tracker returned " + msg.reason());
	}

	/**
	 * Process peer list response from tracker
	 */
	@Override
	public void processPeerListResponseMessage(PeerListResponseMessage msg) {
		// check which are new peers (remove existing ones from retrieved list)
		msg.peerList().removeAll(seedList());
		
		// add all peers to our set
		seedList().addAll(msg.peerList());
		
		// if already downloading, start downloading also from the new peers
		if(fileTransfer.isDownloading()) {
			for(PeerConnection peer : msg.peerList()) {
				executor.execute(new FileDownloaderThread(this, peer));
			}
		}
	}
}
