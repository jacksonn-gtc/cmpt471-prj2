
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

		// * put each segment into sndBuf

		int data_length;
		int count = 0;
		//while (count < size) {
		for (int j=0; j<num_segments; j++) {
			PrintHandler.printOnLevel(3,"count: " + count);
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
			PrintHandler.printOnLevel(3,"Putting seqNum " + seg.seqNum + " in sndBuf");
			sndBuf.putNext(seg);
		}

		PrintHandler.printOnLevel(3,"RDTBuffer contains " + sndBuf.next + " segments");

		// send using udp_send()
		RDTSegment seg = sndBuf.getNext();

		int i=0;
		while(seg != null && i < num_segments) {
			// Send the segment
			Utility.udp_send(seg, socket, dst_ip, dst_port);

			// Next segment
			i++;
			seg = sndBuf.getSegAt(i);
			PrintHandler.printOnLevel(3, "----- i: " + i);
		}
		
		// schedule timeout for segment(s) 
			
		return count;
	}
	
	
	// called by app
	// receive one segment at a time
	// returns number of bytes copied in buf
	public int receive (byte[] buf, int size)
	{
		//*****  complete
		
		return 0;   // fix
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
	}
	
	// return the next in-order segment
	public RDTSegment getNext() {
		RDTSegment seg = null;

		if (base != next) {
			PrintHandler.printOnLevel(3,"base: " + base + ", next: " + next);
			// **** Complete
			try {
				semMutex.acquire();
				seg = buf[base%size];
				semMutex.release();
			} catch(InterruptedException e) {
				System.out.println("getNext: " + e);
			}
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

	// Slides the window according to the segment ACK
	public void slideWindowACK(RDTSegment seg) {
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
			byte[] buf = new byte[RDT.MSS];
			DatagramPacket packetReceived = new DatagramPacket(buf, RDT.MSS);

			// Receive data
			try {
				PrintHandler.printOnLevel(3,"- Calling receive()");
				socket.receive(packetReceived);
			} catch (SocketTimeoutException sto) {
				PrintHandler.printOnLevel(3,"-- RECEIVE LOOP Socket Timeout: " + sto);

				// Check for an interrupt
				if(Thread.currentThread().isInterrupted()) {
					PrintHandler.printOnLevel(3,"- Thread interrupted: return to top of loop");
					PrintHandler.printOnLevel(3,"- endLoop: " + endLoop);
					continue;
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

//			different behaviour between GBN and SR starts

			// Check for ACK in the segment
			if (segRcv.containsAck()) {
				PrintHandler.printOnLevel(2, "- ACK received: ackNum = " + segRcv.ackNum);

				// Slide window up
				sndBuf.slideWindowACK(segRcv);
			}
			else if (segRcv.containsData()) {
				// Segment is data, send to buffer
				PrintHandler.printOnLevel(1, "- Data received: seqNum = " + segRcv.seqNum);
				//rcvBuf.putNext(segRcv);

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

			PrintHandler.printOnLevel(2,"-- RECEIVE LOOP end");
		} // End rcvThread while loop

		PrintHandler.printOnLevel(2,"--- RECEIVE LOOP exit");

		// Initiate graceful shutdown here

		PrintHandler.printOnLevel(2, "---- RECEIVE THREAD exit");
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
	
} // end ReceiverThread class

