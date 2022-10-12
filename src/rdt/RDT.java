
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

	public static final int MSS = 100;	// Max segment size in bytes
	public static final int RTO = 500;	// Retransmission Timeout in msec

	public static final int RCV_TO = 1000;	// Timeout for receiving data

	public static final int ERROR = -1;
	public static final int MAX_BUF_SIZE = 3;  
	public static final int GBN = 1;	// Go back N protocol
	public static final int SR  = 2;	// Selective Repeat
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
	
	RDT (String dst_hostname_, int dst_port_, int local_port_) 
	{
		local_port = local_port_;
		dst_port = dst_port_; 
		try {
			 socket = new DatagramSocket(local_port);
			 dst_ip = InetAddress.getByName(dst_hostname_);
		 } catch (IOException e) {
			 System.out.println("RDT constructor: " + e);
		 }
		sndBuf = new RDTBuffer(MAX_BUF_SIZE);
		if (protocol == GBN)
			rcvBuf = new RDTBuffer(1);
		else 
			rcvBuf = new RDTBuffer(MAX_BUF_SIZE);
		rcvThread = new ReceiverThread(rcvBuf, sndBuf, socket, dst_ip, dst_port);
		rcvThread.start();
	}
	
	RDT (String dst_hostname_, int dst_port_, int local_port_, int sndBufSize, int rcvBufSize)
	{
		local_port = local_port_;
		dst_port = dst_port_;
		 try {
			 socket = new DatagramSocket(local_port);
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
		int num_segs = (int) Math.ceil((double)size/(double)MSS);
		RDTSegment[] rdt_array = new RDTSegment[num_segs];

		// * put each segment into sndBuf

		int data_length;
		int count = 0;
		//while (count < size) {
		for (int j=0; j<num_segs; j++) {
			System.out.println("count: " + count);
			data_length = (size-count) < MSS ? (size-count) : MSS;	// if size < MSS, only copy that amount of data

			// Create segment
			RDTSegment seg = new RDTSegment();
			seg.seqNum = j;
			seg.ackNum = j;
			seg.rcvWin = 0;
			seg.flags = 0;
			seg.length = data_length;

			// Copy the data
			for (int i=0; i<data_length; i++) {
				seg.data[i] = data[i];
			}
			count += data_length;

			// Calculate checksum
			seg.checksum = seg.computeChecksum();

			// Add the segment to the sndBuf
			System.out.println("Putting seqNum " + seg.seqNum + " in sndBuf");
			sndBuf.putNext(seg);
		}

		System.out.println("Done splitting packets");
		System.out.println("num_segs: " + num_segs);
		
		// * send using udp_send()

		// Different behaviour between GBN and SR
		if (protocol == GBN) {
			TimeoutHandler timeout;
			RDTSegment segToSend;

			// Send if there are segments available
			for (int i = 0; i < num_segs; i++) {
				segToSend = sndBuf.getSegAt(i);

				// Send the segment
				Utility.udp_send(segToSend, socket, dst_ip, dst_port);

				// Give the first segment a timeout
				if (i == 0) {
					timeout = new TimeoutHandler(sndBuf, segToSend, socket, dst_ip, dst_port);
					segToSend.timeoutHandler = timeout;

					// Schedule the timeout
//					timer.schedule(timeout, RTO);
				}
			}
		} else /* (protocol == SR) */{

		}

		return count;
	}
	
	
	// called by app
	// receive one segment at a time
	// returns number of bytes copied in buf
	public int receive (byte[] buf, int size)
	{
		//*****  complete
		RDTSegment seg = rcvBuf.getNext();

		// No data, return
		if (seg == null) {
			return 0;
		}

		rcvBuf.slideWindow();

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

		rcvThread.close();
		rcvThread.interrupt();
	}
	
}  // end RDT class 


class RDTBuffer {
	public RDTSegment[] buf;
	public int size;	
	public int base;
	public int next;
	public Semaphore semMutex; // for mutual exclusion
	public Semaphore semFull;  // Count of Full slots
	public Semaphore semEmpty; // Count of Empty slots
	
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
		try {
			semEmpty.acquire();	// wait for an empty slot
			semMutex.acquire();	// wait for mutex
				buf[next%size] = seg;
				next++;  
			semMutex.release();
			semFull.release();	// increase count of full slots
		} catch(InterruptedException e) {
			System.out.println("Buffer put(): " + e);
		}
	}
	
	// return the next in-order segment
	// used when receiving packets from rcvBuf
	public RDTSegment getNext() {
		RDTSegment seg = null;

		if (base != next) {
			System.out.println("base: " + base + ", next: " + next);
			// **** Complete
			try {
				semMutex.acquire();
				seg = buf[base%size];
				semMutex.release();
			} catch(InterruptedException e) {
				System.out.println("Buffer put(): " + e);
			}
		}

		return seg;
	}
	
	// Put a segment in the *right* slot based on seg.seqNum
	// used by receiver in Selective Repeat
	public void putSeqNum (RDTSegment seg) {
		// ***** compelte

	}

	// Moves the window up by 1
	public void slideWindow() {
		try {
			semMutex.acquire();
			base++;
			semMutex.release();
		} catch (InterruptedException e) {
			System.out.println("Buffer put(): " + e);
		}
	}

	// Moves the window up by 'slide'
	public void slideWindowBy(int slide) {
		try {
			semMutex.acquire();
			base += slide;
			semMutex.release();
		} catch (InterruptedException e) {
			System.out.println("Buffer put(): " + e);
		}
	}

	// Slides the window according to the segment ACK
	// Call after isValidACK()
	public void slideWindowACK(RDTSegment seg) {
		try {
			semMutex.acquire();
			base = seg.ackNum+1;
			semMutex.release();
		} catch (InterruptedException e) {
			System.out.println("Buffer put(): " + e);
		}
	}

	// Return the segment at the index, where 0 is the lowest segment number not ACKed.
	public RDTSegment getSegAt(int index) {
		RDTSegment seg = null;
		int q_idx = base + index;

		if (q_idx != next) {
			try {
				semMutex.acquire();
				seg = buf[q_idx];
				semMutex.release();
			} catch (InterruptedException e) {
				System.out.println("Buffer put(): " + e);
			}
		}

		return seg;
	}

	// Checks if seg ACKs the next in-order segment in the buffer
	public boolean isAckNextSeg(RDTSegment seg) {
		return seg.ackNum == base;
	}

	// Checks if the segment is a valid ACK
	public boolean isValidACK(RDTSegment seg) {
		return (seg.ackNum >= base) && (seg.ackNum < next);
	}

	// Checks if the window is empty (all segments ACKed)
	public boolean isEmptyWindow() {
		return base == next;
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
	
	ReceiverThread (RDTBuffer rcv_buf, RDTBuffer snd_buf, DatagramSocket s, 
			InetAddress dst_ip_, int dst_port_) {
		rcvBuf = rcv_buf;
		sndBuf = snd_buf;
		socket = s;
		dst_ip = dst_ip_;
		dst_port = dst_port_;
		endLoop = false;
	}	
	public void run() {
		
		// *** complete 
		// Essentially:  while(cond==true){  // may loop forever if you will not implement RDT::close()
		//                socket.receive(pkt)
		//                seg = make a segment from the pkt
		//                verify checksum of seg
		//	              if seg contains ACK, process it potentially removing segments from sndBuf
		//                if seg contains data, put the data in rcvBuf and do any necessary 
		//                             stuff (e.g, send ACK)
		//

		while(!endLoop) {
			System.out.println("RECEIVE LOOP");
			// Make packet to receive data
			byte[] buf = new byte[RDT.MSS];
			DatagramPacket packetReceived = new DatagramPacket(buf, RDT.MSS);

			// Check for an interrupt
			if(this.isInterrupted()) {

			}

			// Receive data
			try {
				socket.receive(packetReceived);
			} catch (IOException e) {
				System.out.println("Buffer put(): " + e);
			}

			// Make segment from packet
			RDTSegment segRcv = new RDTSegment();
			makeSegment(segRcv, packetReceived.getData());

			// Verify checksum (see if corrupted)
			int checksumCalc = segRcv.computeChecksum();
			if (segRcv.checksum != checksumCalc) {
				System.out.println("Packet corrupted");
				continue;
			}

//			different behaviour between GBN and SR starts

			// Check for ACK in the segment
			if (segRcv.containsAck()) {
				System.out.println("ACK received: ackNum = " + segRcv.ackNum);
				RDTSegment segACKed = sndBuf.getNext();

				// Slide window up
				sndBuf.slideWindowACK(segRcv);

				// Cancel the timer of ACKed segment
				segACKed.timeoutHandler.cancel();

				// Reset the timer if we still have segments to be ACKed
				if (!sndBuf.isEmptyWindow()){
					RDTSegment nextSegToACK = sndBuf.getNext();
					nextSegToACK.timeoutHandler = new TimeoutHandler(sndBuf, nextSegToACK, socket, dst_ip, dst_port);

					// Schedule the timer
					RDT.timer.schedule(nextSegToACK.timeoutHandler, RDT.RTO);
				}
			} else {
				// No ACK, get data from the packet
				rcvBuf.putNext(segRcv);

				// Make an ACK packet
				RDTSegment segACK = new RDTSegment();
				segACK.seqNum = segRcv.seqNum;
				segACK.ackNum = segRcv.ackNum;
				segACK.flags = RDTSegment.FLAGS_ACK;
				segACK.length = 0;
				segACK.rcvWin = 1;
				segACK.checksum = segACK.computeChecksum();

				// Send the ACK
				Utility.udp_send(segACK, socket, dst_ip, dst_port);
			}
		} // End while loop
	}
	
	
	// create a segment from received bytes
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
			seg.data[i] = payload[i + RDTSegment.HEADER_SIZE];
	}

	// End the connection
	public void close() {
		endLoop = true;
		// do more things
	}
	
} // end ReceiverThread class

