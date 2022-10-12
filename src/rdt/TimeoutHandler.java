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
	
	TimeoutHandler (RDTBuffer sndBuf_, RDTSegment s, DatagramSocket sock,
			InetAddress ip_addr, int p) {
		sndBuf = sndBuf_;
		seg = s;
		socket = sock;
		ip = ip_addr;
		port = p;
	}
	
	public void run() {
		
		System.out.println(System.currentTimeMillis()+ ":Timeout for seg: " + seg.seqNum);
		System.out.flush();
		
		// complete 
		switch(RDT.protocol){
			case RDT.GBN:
				TimeoutHandler timeout;
				RDTSegment seg;
				int i=0;

				// While we still have segments to send
				seg = sndBuf.getSegAt(i);
				while (seg != null) {
					// Send the segment
					Utility.udp_send(seg, socket, ip, port);

					// For the first segment, create a new timer
					if (i == 0) {
						timeout = new TimeoutHandler(sndBuf, seg, socket, ip, port);
						seg.timeoutHandler = timeout;

						// Schedule the timer
						RDT.timer.schedule(timeout, RDT.RTO);
					}

					// Next segment
					i++;
					System.out.println("Timeout: i=" + i);
					seg = sndBuf.getSegAt(i);
				}

				break;
			case RDT.SR:
				
				break;
			default:
				System.out.println("Error in TimeoutHandler:run(): unknown protocol");
		}
		
	}
} // end TimeoutHandler class

