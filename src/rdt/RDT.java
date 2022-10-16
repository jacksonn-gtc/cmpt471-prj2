
/**
 * @author mohamed
 *
 */
package rdt;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class RDT {

	public static final int MSS = 100; 	// Max segment size in bytes
	public static final int RTO = 500; 	// Retransmission Timeout in msec
	public static final int STO = 1000;	// Socket Timeout in msec

	public static final int ERROR = -1;
	public static final int MAX_BUF_SIZE = 3;  
	public static final int GBN = 1;   // Go back N protocol
	public static final int SR = 2;    // Selective Repeat
	public static final int protocol = GBN;
	
	public static double lossRate = 0.0;
	public static Random random = new Random(); 
	public static Timer timer = new Timer();	
	
	private DatagramSocket socket; 
	private InetAddress dst_ip;
	private int dst_port;
	private int local_port; 
	
	private RDTBuffer sndBuf;
	private RDTBuffer rcvBuf;
	
	private ReceiverThread rcvThread;  

	private static int next_expected_seq_sender = 0;
	
	RDT (String dst_hostname_, int dst_port_, int local_port_) 
	{
		rdtInit(dst_hostname_, dst_port_, local_port_, MAX_BUF_SIZE, MAX_BUF_SIZE);
	}
	
	RDT (String dst_hostname_, int dst_port_, int local_port_, int sndBufSize, int rcvBufSize)
	{
		rdtInit(dst_hostname_, dst_port_, local_port_, sndBufSize, rcvBufSize);
	}

	private void rdtInit(String dst_hostname_, int dst_port_, int local_port_, int sndBufSize, int rcvBufSize) {
		local_port = local_port_;
		dst_port = dst_port_;
		try {
			socket = new DatagramSocket(local_port);
			socket.setSoTimeout(STO);
			dst_ip = InetAddress.getByName(dst_hostname_);
		} catch (IOException e) {
			System.out.println("RDT constructor: " + e);
		}
		sndBuf = new RDTBuffer(sndBufSize);
		if (protocol == GBN)
			rcvBuf = new RDTBuffer(1);
		else
			rcvBuf = new RDTBuffer(rcvBufSize);

		rcvThread = new ReceiverThread(rcvBuf, sndBuf, socket, dst_ip, dst_port);
		rcvThread.start();
	}
	
	public static void setLossRate(double rate) {lossRate = rate;}
	
	// called by app
	// returns total number of sent bytes  
	public int send(byte[] data, int size) {
		
		//****** complete

		// * divide data into segments
		int num_segments = (int) Math.ceil((double)size/(double)MSS);
		RDTSegment[] segArray = new RDTSegment[num_segments];

		// If we didn't put in segments in, exit
		if (num_segments == 0) {
			return 0;
		}

		// * put each segment into sndBuf

		int data_length;
		int byte_count = 0;
		for (int j=0; j<num_segments; j++) {
			PrintHandler.printOnLevel(3,"--- byte_count: " + byte_count);

			// if size < MSS, only copy that amount of data
			data_length = (size-byte_count) < MSS ? (size-byte_count) : MSS;
			byte_count += data_length;

			// Create segment, fill the header
			RDTSegment seg = new RDTSegment();
			seg.seqNum = next_expected_seq_sender;
			seg.ackNum = next_expected_seq_sender;
			seg.rcvWin = 0;
			seg.flags = 0;
			seg.length = data_length;

			// Calculate checksum
			seg.checksum = seg.computeChecksum();

			// Copy the data
			for (int i=0; i<data_length; i++) {
				seg.data[i] = data[i];
			}

			// Add the segment to the sndBuf
			PrintHandler.printOnLevel(3,"--- Putting seqNum " + seg.seqNum);
			segArray[j] = seg;

			// Next expected seg
			next_expected_seq_sender++;
		}

		PrintHandler.printOnLevel(3,"--- Divided data into " + num_segments + " segments");

		//* send using udp_send()
		//* schedule timeout for segment(s)

		if (protocol == GBN) {
			// Go-Back-N

			// Obtain the first segment, track it in the sndBuf
			RDTSegment seg = segArray[0];
			sndBuf.putNext(seg);

			// set a timeoutHandler
			TimeoutHandler timeoutHandler = new TimeoutHandler(sndBuf, seg, socket, dst_ip, dst_port);
			seg.timeoutHandler = timeoutHandler;

			// Send the first segment
			Utility.udp_send(seg, socket, dst_ip, dst_port);

			// Schedule the timer
			timer.schedule(timeoutHandler, RTO);

			// Send the rest
			for (int j=1; j<num_segments; j++) {
				PrintHandler.printOnLevel(3, "----- j: " + j);
				seg = segArray[j];
				seg.timeoutHandler = new TimeoutHandler(sndBuf, seg, socket, dst_ip, dst_port);
				sndBuf.putNext(seg);
				Utility.udp_send(seg, socket, dst_ip, dst_port);

				if (sndBuf.numNotAcked() == 1) {
					timer.schedule(seg.timeoutHandler, RTO);
				}
			}
		}
		else {
			// Selective Repeat
		}

			
		return byte_count;
	}
	
	
	// called by app
	// receive one segment at a time
	// returns number of bytes copied in buf
	public int receive (byte[] buf, int size)
	{
		//*****  complete
		RDTSegment seg = rcvBuf.getNextAndSlide();

		// No data, return
		if (seg == null) {
			return 0;
		}

		PrintHandler.printOnLevel(1, "- passing data to app from receive()");

		// Copy the data into the buf
		for (int i=0; i<seg.length; i++) {
			buf[i] = seg.data[i];
		}

		return seg.length;
	}
	
	// called by app
	public void close() {
		// OPTIONAL: close the connection gracefully
		// you can use TCP-style connection termination process
		rcvThread.stopReceiving();
		rcvThread.interrupt();
	}
	
}  // end RDT class 


class RDTBuffer {
	public RDTSegment[] buf;
	public int size;	
	public int base;
	public int next;
	public Semaphore semMutex; // for mutual exclusion
	public Semaphore semFull;  // #of full slots
	public Semaphore semEmpty; // #of Empty slots
	
	RDTBuffer (int bufSize) {
		buf = new RDTSegment[bufSize];
		for (int i=0; i<bufSize; i++)
			buf[i] = null;
		size = bufSize;
		base = next = 0;
		semMutex = new Semaphore(1, true);
		semFull =  new Semaphore(0, true);
		semEmpty = new Semaphore(bufSize, true);
	}

	
	
	// Put a segment in the next available slot in the buffer
	public void putNext(RDTSegment seg) {

//		PrintHandler.printOnLevel(1,"putNext - base: " + base + ", next: " + next);
//		PrintHandler.printOnLevel(1,"putNext - base: " + base + ", next: " + next);

		try {
			semEmpty.acquire(); // wait for an empty slot 
			semMutex.acquire(); // wait for mutex 
				buf[next%size] = seg;
				next++;  
			semMutex.release();
			semFull.release(); // increase #of full slots
		} catch(InterruptedException e) {
			System.out.println("putNext: " + e);
		}

//		PrintHandler.printOnLevel(1,"putNext - base: " + base + ", next: " + next);
	}
	
	// return the next in-order segment
	public RDTSegment getNext() {
		RDTSegment seg = null;

//		PrintHandler.printOnLevel(1,"base: " + base + ", next: " + next);
		// **** Complete
		try {
			semMutex.acquire();
				seg = buf[base%size];
			semMutex.release();
		} catch(InterruptedException e) {
			System.out.println("getNext: " + e);
		}

		return seg;
	}

	// return the next in-order segment and slide the window
	public RDTSegment getNextAndSlide() {
		RDTSegment seg = null;

		// **** Complete
		try {
			semFull.acquire();
			semMutex.acquire();
				seg = buf[base%size];
				base++;
			semMutex.release();
			semEmpty.release();
		} catch(InterruptedException e) {
			System.out.println("getNext: " + e);
		}

		return seg;
	}

	// Return the segment at the index, where 0 is the lowest segment number not ACKed.
	public RDTSegment getSegAt(int index) {
		RDTSegment seg = null;
		int q_idx = base + index;

		PrintHandler.printOnLevel(3, "q_idx: " + q_idx + ", next: " + next);

		if (q_idx != next) {
			try {
				semMutex.acquire();
					seg = buf[q_idx%size];
				semMutex.release();
			} catch (InterruptedException e) {
				System.out.println("getSegAt: " + e);
			}
		}

		return seg;
	}
	
	// Put a segment in the *right* slot based on seg.seqNum
	// used by receiver in Selective Repeat
	public void putSeqNum (RDTSegment seg) {
		// ***** compelte

	}

	// Slides the window by 1
	public void slideWindowNext() {
		try {
			semFull.acquire();
			semMutex.acquire();
				base++;
			semMutex.release();
			semEmpty.release();
		} catch (InterruptedException e) {
			System.out.println("slideWindowNext: " + e);
		}
	}

	// Slides the window according to the segment ACK
	public void slideWindowACK(RDTSegment seg) {
		// If we get ACK for already ACKed segment, ignore it
		if (seg.ackNum < base) {
			return;
		}

		try {
			semFull.acquire();
			semMutex.acquire();
				base = seg.ackNum+1;
			semMutex.release();
			semEmpty.release();
		} catch (InterruptedException e) {
			System.out.println("slideWindowACK: " + e);
		}
	}

	public boolean isEmptyWindow() {
		boolean emptyWindow = true;

		try {
			semMutex.acquire();
				emptyWindow = base == next;
			semMutex.release();
		} catch (InterruptedException e) {
			System.out.println("isEmptyWindow: " + e);
		}
		return emptyWindow;
	}

	public boolean isExpectedData(RDTSegment seg) {
		boolean unseenData = false;

		try {
			semMutex.acquire();
				unseenData = seg.seqNum == base;
			semMutex.release();
		} catch (InterruptedException e) {
			System.out.println("isUnseenData: " + e);
		}

		return unseenData;
	}

	public int numNotAcked() {
		int numNotAcked = 0;

		try {
			semMutex.acquire();
			numNotAcked = next - base;
			semMutex.release();
		} catch (InterruptedException e) {
			System.out.println("numNotAcked: " + e);
		}

		return numNotAcked;
	}

	public void stopAllTimers() {
		for (int i=0; i<(next-base); i++) {
			buf[i].timeoutHandler.cancel();
		}
	}
	
	// for debugging
	public void dump() {
		System.out.println("Dumping the receiver buffer ...");
		// Complete, if you want to 
		
	}
} // end RDTBuffer class



class ReceiverThread extends Thread {
	RDTBuffer rcvBuf, sndBuf;
	DatagramSocket socket;
	InetAddress dst_ip;
	int dst_port;
	boolean endLoop;

	int next_expected_seq = 0;
	RDTSegment segACK = null;
	
	ReceiverThread (RDTBuffer rcv_buf, RDTBuffer snd_buf, DatagramSocket s, 
			InetAddress dst_ip_, int dst_port_) {
		rcvBuf = rcv_buf;
		sndBuf = snd_buf;
		socket = s;
		dst_ip = dst_ip_;
		dst_port = dst_port_;
		endLoop = false;

		segACK = new RDTSegment();
		updateACK();
	}	
	public void run() {
		
		// *** complete 
		// Essentially:  while(cond==true){  // may loop for ever if you will not implement RDT::close()  
		//                socket.receive(pkt)
		//                seg = make a segment from the pkt
		//                verify checksum of seg
		//	              if seg contains ACK, process it potentailly removing segments from sndBuf
		//                if seg contains data, put the data in rcvBuf and do any necessary 
		//                             stuff (e.g, send ACK)
		//

		while(!endLoop) {

			// Make packet to receive data
			byte[] buf = new byte[RDT.MSS + RDTSegment.HDR_SIZE];
			DatagramPacket packetReceived = new DatagramPacket(buf, RDT.MSS + RDTSegment.HDR_SIZE);

			// Receive data
			try {
				PrintHandler.printOnLevel(3,"- Calling receive()");
				socket.receive(packetReceived);
			} catch (SocketTimeoutException sto) {
				PrintHandler.printOnLevel(3,"-- RECEIVE LOOP Socket Timeout: " + sto);

				// Check for an interrupt
				if(Thread.currentThread().isInterrupted()) {
					PrintHandler.printOnLevel(3,"- Thread interrupted");
					PrintHandler.printOnLevel(3,"- endLoop: " + endLoop);
				}
			} catch (IOException e) {
				System.out.println("-- RECEIVE LOOP IOException: " + e);
			}

			// Turn packet into a segment
			RDTSegment segRcv = new RDTSegment();
			makeSegment(segRcv, packetReceived.getData());

			// Verify checksum (see if corrupted)
			int checksumCalc = segRcv.computeChecksum();
			if (segRcv.checksum != checksumCalc) {
				PrintHandler.printOnLevel(2,"Packet corrupted");
				continue;
			}

			// Check for ACK in the segment
			if (segRcv.containsAck()) {
				PrintHandler.printOnLevel(1, "- ACK received: ackNum = " + segRcv.ackNum);

				// Clock behaviour: different between GBN and SR
				if (RDT.protocol == RDT.GBN) {
					// Go-Back-N

					// get the oldest segment no ACKed
					RDTSegment segOldest = sndBuf.getNext();
					if (segOldest == null)
						continue;

					// Cancel the timer (set on the oldest segment)
					segOldest.timeoutHandler.cancel();

					// Slide window up
					sndBuf.slideWindowACK(segRcv);

					// Place a new timer on the oldest segment not ACKed
					if (!sndBuf.isEmptyWindow()) {
						RDTSegment segNextToAck = sndBuf.getNext();
						TimeoutHandler timeoutHandler = new TimeoutHandler(sndBuf, segNextToAck, socket, dst_ip, dst_port, sndBuf.numNotAcked());
						segNextToAck.timeoutHandler = timeoutHandler;

						PrintHandler.printOnLevel(1, "- Resetting the timer on segment: " + segNextToAck.seqNum);

						// Schedule the timer
						RDT.timer.schedule(timeoutHandler, RDT.RTO);
					}
				}
				else {
					// Selective Repeat
				}
			}
			else if (segRcv.containsData()) {
//				segRcv.printHeader();
//				segRcv.printData();

				// Segment is data, send to buffer
				PrintHandler.printOnLevel(1, "- Data received: seqNum = " + segRcv.seqNum);

				if (segRcv.seqNum == next_expected_seq) {
					rcvBuf.putNext(segRcv);

					// update our ACK packet
					updateACK();

					// Expect next segment
					next_expected_seq++;
				}

				// Send the ACK
				PrintHandler.printOnLevel(1, "- Sending ACK");
				Utility.udp_send(segACK, socket, dst_ip, dst_port);
			}

			PrintHandler.printOnLevel(2,"-- RECEIVE LOOP end");
		} // End rcvThread while loop

		PrintHandler.printOnLevel(1,"--- RECEIVE LOOP exit");

		// Initiate graceful shutdown here

		// Stop any timers that are ongoing
		PrintHandler.printOnLevel(2,"-- Stopping all timers");
		sndBuf.stopAllTimers();

		PrintHandler.printOnLevel(0, "---- RECEIVE THREAD exit");
		return;
	}

	public void stopReceiving() {
		PrintHandler.printOnLevel(0,"--- stopReceiving()");
		endLoop = true;
	}

//	 create a segment from received bytes 
	void makeSegment(RDTSegment seg, byte[] payload) {
	
		seg.seqNum = Utility.byteToInt(payload, RDTSegment.SEQ_NUM_OFFSET);
		seg.ackNum = Utility.byteToInt(payload, RDTSegment.ACK_NUM_OFFSET);
		seg.flags  = Utility.byteToInt(payload, RDTSegment.FLAGS_OFFSET);
		seg.checksum = Utility.byteToInt(payload, RDTSegment.CHECKSUM_OFFSET);
		seg.rcvWin = Utility.byteToInt(payload, RDTSegment.RCV_WIN_OFFSET);
		seg.length = Utility.byteToInt(payload, RDTSegment.LENGTH_OFFSET);
		//Note: Unlike C/C++, Java does not support explicit use of pointers! 
		// we have to make another copy of the data
		// This is not efficient in protocol implementation
		for (int i=0; i< seg.length; i++)
			seg.data[i] = payload[i + RDTSegment.HDR_SIZE]; 
	}

	void updateACK() {
		segACK.seqNum = next_expected_seq;
		segACK.ackNum = next_expected_seq;
		segACK.flags = RDTSegment.FLAGS_ACK;
		segACK.length = 0;
		segACK.rcvWin = 1;
		segACK.checksum = segACK.computeChecksum();
	}
	
} // end ReceiverThread class