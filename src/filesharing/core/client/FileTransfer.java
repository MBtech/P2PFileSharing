package filesharing.core.client;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import filesharing.core.exception.RequestFailedException;

public class FileTransfer {
	
	/**
	 * Default block size in bytes
	 */
	public static final int DEFAULT_BLOCK_SIZE = 3;//(int) (1024*1024*1.4);
	
	/**
	 * The client with this file transfer
	 */
	private Client client;
	
	/**
	 * The filename for this file
	 */
	private String filename;
	
	/**
	 * Where the file is stored in local storage
	 */
	private File local_file;
	
	/**
	 * An object for random access to the local file
	 */
	private RandomAccessFile file_access;
	
	/**
	 * A seeder thread
	 */
	private FileSeeder seeder = new FileSeeder(this);
	
	/**
	 * A download thread
	 */
	private FileDownloader downloader = new FileDownloader(this);
	
	/**
	 * Checks if metadata for the file is loaded
	 */
	private boolean has_metadata = false;
	
	/**
	 * List of trackers to connect
	 */
	private Set<TrackerInformation> tracker_list = new HashSet<TrackerInformation>();
	
	/**
	 * List of seeders
	 */
	private Set<PeerInformation> seed_list = new HashSet<PeerInformation>();
	
	/**
	 * File metadata: file size (in bytes)
	 */
	private long file_size;
	
	/**
	 * File metadata: file block size (in bytes)
	 */
	private int block_size = DEFAULT_BLOCK_SIZE;
	
	/**
	 * A bitmap to check for blocks present
	 */
	private BitSet blocks_present;
	
	/**
	 * Creates a new file transfer
	 * @param filename the global name of the file
	 * @param local_file pointer to local file
	 * @throws IOException 
	 */
	public FileTransfer(Client client, String filename, File local_file) throws IOException {
		this.client = client;
		this.filename = filename;
		this.local_file = local_file;
		this.seeder = new FileSeeder(this);
		this.downloader = new FileDownloader(this);
	}
	
	/**
	 * Add a list of trackers for this file
	 * @param new_trackers a collection of tracker information objects
	 */
	public void addTrackers(Collection<TrackerInformation> new_trackers) {
		tracker_list.addAll(new_trackers);
	}
	
	/**
	 * Add a tracker for this file
	 * @param address tracker address
	 * @param port tracker port
	 */
	public void addTracker(String address, int port) {
		tracker_list.add(new TrackerInformation(address, port));
	}
	
	/**
	 * Sets file metadata
	 * @param file_size the size of the file
	 * @param block_size the size of the transfer blocks
	 * @throws IOException
	 */
	protected void setMetadata(long file_size, int block_size) throws IOException {
		// dont set metadata if already present
		if(hasMetadata()) return;
		
		// initialize metadata
		this.file_size = file_size;
		this.block_size = block_size;
		this.blocks_present = new BitSet(numBlocks());
		
		// open file for random access
		file_access = new RandomAccessFile(local_file, "rw");
		
		// finish
		has_metadata = true;
	}
	
	/**
	 * Generates file metadata from the local disk.
	 * Blocking method - blocks until metadata is loaded or error occurs.
	 * @throws IOException 
	 */
	public void loadMetadataFromDisk() throws IOException {
		// check if metadata already loaded
		if(hasMetadata()) return;
		
		// create metadata
		setMetadata(local_file.length(), block_size);
		
		// set all blocks as present
		blocks_present.set(0, numBlocks());
	}
	
	/**
	 * Fetches file metadata from remote peers
	 * This method blocks until metadata is loaded or an error occurs.
	 */
	public void loadMetadataFromPeers() throws IOException {
		// check if metadata already loaded
		if(hasMetadata()) return;

		// fetch metadata from peers
		downloader.fetchMetadata();
		
		// create file if doesn't exist and allocate disk space
		local_file.createNewFile();
		file_access.setLength(fileSize());
	}
	
	/**
	 * Starts the seeding of the file
	 */
	public void startSeeder() throws IOException {
		
		// FIXME: should throw a DontHaveMetadata exception
		if(!hasMetadata()) return;
		
		// start seeder thread
		seeder.start();
		
		// register thread on trackers
		int data_port = seeder.getDataPort();
		Iterator<TrackerInformation> it = tracker_list.iterator();
		while(it.hasNext()) {
			TrackerInformation tinfo = it.next();
			try {
				tinfo.registerPeer(filename(), data_port);
			} catch (RequestFailedException e) {
				log("failed to register with tracker " + tinfo);
			}
		}
		
	}
	
	/**
	 * Starts downloading the file
	 * @throws IOException 
	 */
	public void startDownload() throws IOException {
		// FIXME: should throw a DontHaveMetadata exception
		if(!hasMetadata()) return;
		
		// start downloader thread
		this.downloader.start();
	}
	
	/**
	 * Returns the filename
	 * @return filename
	 */
	public String filename() {
		return filename;
	}
	
	/**
	 * Check if metadata has been loaded for this file
	 * @return true if metadata has been loaded, false otherwise
	 */
	public boolean hasMetadata() {
		return has_metadata;
	}
	
	/**
	 * Returns the file size for the file
	 * @return file size
	 */
	public long fileSize() {
		return file_size;
	}
	
	/**
	 * Returns the block size for the file transfer
	 * @return transfer block size
	 */
	public int blockSize() {
		return block_size;
	}

	/**
	 * Gets the number of blocks for this file
	 * @return number of blocks
	 */
	public int numBlocks() {
		return (int) Math.ceil(fileSize()/blockSize());
	}
	
	/**
	 * Returns the list of seeders
	 * @return set of inet socket addresses of seeders
	 */
	protected Set<PeerInformation> seedList() {
		return seed_list;
	}
	
	/**
	 * Returns the list of trackers
	 * @return set of tracker information objects
	 */
	protected Set<TrackerInformation> getTrackers() {
		return tracker_list;
	}
	
	protected BitSet getBlocksPresent() {
		return blocks_present;
	}
	
	/**
	 * Return a textual representation of this object
	 */
	public String toString() {
		String metadata;
		int num_blocks_present = this.blocks_present.cardinality();
		if(hasMetadata()) {
			metadata = "filesize=" + fileSize() + "B, " +
			           "blocksize=" + blockSize() + ", " +
			           "numblocks=" + num_blocks_present + "/" + numBlocks() + ", " +
			           "numTrackers=" + tracker_list.size() + ", " +
			           "numSeeds=" + seed_list.size();
		}
		else {
			metadata = "no metadata";
		}
		return "[FILE] filename=" + filename() + ", " + metadata;
	}

	/**
	 * Log message into stdout
	 * @param msg
	 */
	protected void log(String msg) {
		client.log("[FILE filename=" + filename + "] " + msg);
	}
	
}