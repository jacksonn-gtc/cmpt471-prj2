/**
 * 
 * @author mohamed
 *
 */

package rdt;

public class RDTSegment {
	public int seqNum;
	public int ackNum;
	public int flags;
	public int checksum; 
	public int rcvWin;
	public int length;  // number of data bytes (<= MSS)
	public byte[] data;

	public boolean ackReceived;
	
	public TimeoutHandler timeoutHandler;  // make it for every segment, 
	                                       // will be used in selective repeat
	
  // constants 
	public static final int SEQ_NUM_OFFSET = 0;
	public static final int ACK_NUM_OFFSET = 4;
	public static final int FLAGS_OFFSET = 8;
	public static final int CHECKSUM_OFFSET = 12;
	public static final int RCV_WIN_OFFSET = 16;
	public static final int LENGTH_OFFSET = 20;

	public static final int HDR_SIZE = 24;
	public static final int FLAGS_ACK = 1;

	RDTSegment() {
		data = new byte[RDT.MSS];
		flags = 0; 
		checksum = 0;
		seqNum = 0;
		ackNum = 0;
		length = 0;
		rcvWin = 0;
		ackReceived = false;
	}
	
	public boolean containsAck() {
		// complete
		return flags == FLAGS_ACK;
	}
	
	public boolean containsData() {
		// complete
		return length > 0;
	}

	// Algorithm is based on RFC 791, Section 3.1. Internet Header Format: Header Checksum
	// https://www.rfc-editor.org/rfc/rfc791
	public int computeChecksum() {
		// complete

		// Take one's complement (inverse) of all header values
		int seqNum1C = seqNum ^ 0xffffffff;
		int ackNum1C = ackNum ^ 0xffffffff;
		int flags1C = flags ^ 0xffffffff;
		int rcvWin1C = rcvWin ^ 0xffffffff;
		int length1C = length ^ 0xffffffff;
		int checksum1C = 0 ^ 0xffffffff;

		// Take the sum of all one's complement values
		int sum = seqNum1C + ackNum1C + flags1C + rcvWin1C + length1C + checksum1C;

		// Take the one's complement of the sum
		int sum1C = sum ^ 0xffffffff;

		return sum1C;
	}
	public boolean isValid() {
		// complete
		return true;
	}
	
	// converts this seg to a series of bytes
	public void makePayload(byte[] payload) {
		// add header 
		Utility.intToByte(seqNum, payload, SEQ_NUM_OFFSET);
		Utility.intToByte(ackNum, payload, ACK_NUM_OFFSET);
		Utility.intToByte(flags, payload, FLAGS_OFFSET);
		Utility.intToByte(checksum, payload, CHECKSUM_OFFSET);
		Utility.intToByte(rcvWin, payload, RCV_WIN_OFFSET);
		Utility.intToByte(length, payload, LENGTH_OFFSET);
		//add data
		for (int i=0; i<length; i++)
			payload[i+HDR_SIZE] = data[i];
	}
	
	public void printHeader() {
		System.out.println("SeqNum: " + seqNum);
		System.out.println("ackNum: " + ackNum);
		System.out.println("flags: " +  flags);
		System.out.println("checksum: " + checksum);
		System.out.println("rcvWin: " + rcvWin);
		System.out.println("length: " + length);
	}
	public void printData() {
		System.out.println("Data ... ");
		for (int i=0; i<length; i++) 
			System.out.print(data[i]);
		System.out.println(" ");
	}
	public void dump() {
		printHeader();
		printData();
	}
	
} // end RDTSegment class
