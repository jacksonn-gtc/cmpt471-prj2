/**
 * @author mhefeeda
 *
 */

package rdt;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.TimerTask;

class TimeoutHandler extends TimerTask {
	RDTBuffer sndBuf;
	RDTSegment seg; 
	DatagramSocket socket;
	InetAddress ip;
	int port;
	int num_segments = 0;
	
	TimeoutHandler (RDTBuffer sndBuf_, RDTSegment s, DatagramSocket sock, 
			InetAddress ip_addr, int p) {
		sndBuf = sndBuf_;
		seg = s;
		socket = sock;
		ip = ip_addr;
		port = p;
	}

	TimeoutHandler (RDTBuffer sndBuf_, RDTSegment s, DatagramSocket sock,
					InetAddress ip_addr, int p, int num) {
		sndBuf = sndBuf_;
		seg = s;
		socket = sock;
		ip = ip_addr;
		port = p;
		num_segments = num;
	}

	public void setNumPackets(int num) {
		num_segments = num;
	}
	
	public void run() {
		
		System.out.println(System.currentTimeMillis()+ ":Timeout for seg: " + seg.seqNum);
		System.out.flush();

		TimeoutHandler timeoutHandler;

		// complete 
		switch(RDT.protocol){
			case RDT.GBN:
				// Obtain the first segment, and set a new timeoutHandler
				RDTSegment segCurr = sndBuf.getNext();
				timeoutHandler = new TimeoutHandler(sndBuf, segCurr, socket, ip, port);
				segCurr.timeoutHandler = timeoutHandler;

				// Schedule the timer
				RDT.timer.schedule(timeoutHandler, RDT.RTO);

				// Send the first segment
				Utility.udp_send(segCurr, socket, ip, port);

				// Send the rest
				for (int i=1; i<sndBuf.numNotAcked(); i++) {
					segCurr = sndBuf.getSegAt(i);
					Utility.udp_send(segCurr, socket, ip, port);
				}

				break;
			case RDT.SR:
				// Resend this segment, and schedule a new timer
				RDTSegment segResend = sndBuf.getSeqNum(seg);
				segResend.timeoutHandler = new TimeoutHandler(sndBuf, segResend, socket, ip, port);
				RDT.timer.schedule(segResend.timeoutHandler, RDT.RTO);
				Utility.udp_send(segResend, socket, ip, port);

				break;
			default:
				System.out.println("Error in TimeoutHandler:run(): unknown protocol");
		}
	}
} // end TimeoutHandler class

