package filesharing.core.tracker;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import filesharing.core.TrackerRequestProcessor;
import filesharing.core.client.PeerInformation;
import filesharing.core.message.tracker.request.PeerListRequestMessage;
import filesharing.core.message.tracker.request.RegisterPeerRequestMessage;
import filesharing.core.message.tracker.request.TrackerRequestMessage;
import filesharing.core.message.tracker.response.PeerListResponseMessage;
import filesharing.core.message.tracker.response.SuccessResponseMessage;

public class TrackerRequestHandler implements Runnable, TrackerRequestProcessor {
	
	/**
	 * The tracker atached to this handler
	 */
	TrackerDaemon tracker;
	
	/**
	 * Client socket to wait for requests
	 */
	Socket sock;
	ObjectInputStream is; // socket object input stream, for convenience
	ObjectOutputStream os; // socket object output stream, for convenience
	
	/**
	 * Create a new tracker request handler listening in the specified socket
	 * @param sock socket for listening
	 * @throws IOException 
	 */
	public TrackerRequestHandler(TrackerDaemon tracker, Socket sock) throws IOException {
		this.tracker = tracker;
		this.sock = sock;
		this.is = new ObjectInputStream(sock.getInputStream());
		this.os = new ObjectOutputStream(sock.getOutputStream());
	}

	/**
	 * Tracker request handler main thread - processes requests from a single client
	 */
	@Override
	public void run() {
		try {
			// read requests, process them and return the response
			while(true) {
					// read request
					TrackerRequestMessage msg = (TrackerRequestMessage) is.readObject();
					// process request
					log(sock.getRemoteSocketAddress() + ": " + msg);
					msg.accept(this);
			}
		}
		catch (IOException | ClassNotFoundException e) {
			// just exit silently
			//log(sock.getRemoteSocketAddress() + ": disconnected");
		}
	}
	
	public void log(String msg) {
		tracker.log(msg);
	}

	/**
	 * Process requests for handing out the list of peers for a given file
	 */
	@Override
	public void processPeerListRequestMessage(PeerListRequestMessage msg) throws IOException {
		
		// initialize
		String filename = msg.filename();
		
		// check if file is registered
		if(tracker.peerRecord().containsKey(filename)) {
			// if it is, return peer list
			Collection<PeerInformation> peer_list = tracker.peerRecord().get(filename);
			os.writeObject(new PeerListResponseMessage(peer_list));
		}
		else {
			// if it is not, return an empty list
			os.writeObject(new PeerListResponseMessage());
		}
		
	}

	/**
	 * Process requests for registering a peer with a given file
	 */
	@Override
	public void processRegisterPeerRequestMessage(RegisterPeerRequestMessage msg) throws IOException {
		
		// initialize
		String filename = msg.filename();
		int data_port = msg.dataPort();
		
		// check if this file has not been registered yet
		if(!tracker.peerRecord().containsKey(filename)) {
			// if not, create a new set to store peers for that file
			tracker.peerRecord().put(filename, Collections.synchronizedSet(new HashSet<PeerInformation>()));
		}
		// add the peer to the list of peers for the given filename
		Set<PeerInformation> peer_list = tracker.peerRecord().get(filename);
		peer_list.add(new PeerInformation(sock.getInetAddress().getHostAddress(), data_port));
		
		// send response to client
		os.writeObject(new SuccessResponseMessage());
	}

}