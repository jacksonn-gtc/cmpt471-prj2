/**
 * @author mohamed
 *
 */

package rdt;

public class TestServer {

	public TestServer() {
		
	}

	/**
	 * @param args
	 */
	public static void main(String[] args) throws Exception {
		System.out.println("\n\n\n");

		 if (args.length != 3) {
	         System.out.println("Required arguments: dst_hostname dst_port local_port");
	         return;
	      }
		 String hostname = args[0];
	     int dst_port = Integer.parseInt(args[1]);
	     int local_port = Integer.parseInt(args[2]);
	     	      
	     RDT rdt = new RDT(hostname, dst_port, local_port, 3, 3);
	     RDT.setLossRate(0.0);
	     byte[] buf = new byte[500];  	     
	     System.out.println("Server is waiting to receive ... " );
	
	     
	     while (true) {
	    	 int size = rdt.receive(buf, RDT.MSS);

			 if (size > 0) {
				 System.out.println("DATA ACQUIRED");
				 for (int i=0; i<size; i++)
					 System.out.print(buf[i]);
				 System.out.println();
			 }

//	    	 System.out.println(" ");
//	    	 System.out.flush();
	     } 
	}
}

