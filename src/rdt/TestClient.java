/**
 * @author mohamed
 *
 */

package rdt;

public class TestClient {

	/**
	 * 
	 */
	public TestClient() {
		
	}

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		if (args.length != 3) {
	 		System.out.println("Required arguments: dst_hostname dst_port local_port");
	 		return;
		}
		String hostname = args[0];
		int dst_port = Integer.parseInt(args[1]);
		int local_port = Integer.parseInt(args[2]);

		RDT rdt = new RDT(hostname, dst_port, local_port, 1, 3);
		RDT.setLossRate(0.4);

		byte[] buf = new byte[RDT.MSS];
		byte[] data = new byte[10];

		sendData(rdt, data, (byte) 0);
//		sendData(rdt, data, (byte) 1);
//		sendData(rdt, data, (byte) 2);
//		sendData(rdt, data, (byte) 3);
//		sendData(rdt, data, (byte) 4);

		System.out.println(System.currentTimeMillis() + ":Client has sent all data " );
		System.out.flush();

		rdt.receive(buf, RDT.MSS);
		rdt.close();
		System.out.println("Client is done " );
	}

	public static void sendData(RDT rdt, byte[] data, byte value) {
		System.out.println("Sending " + value + "...");
		for (int i=0; i<10; i++)
			data[i] = value;
		rdt.send(data, 10);
	}

}
