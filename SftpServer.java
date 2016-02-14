import java.io.*;
import java.net.*;
import java.util.*;


public class SftpServer
{

   public static void main(String[] args) throws Exception
   {
      
      if (args.length != 1) {
         System.out.println("Required argument: loss rate");
         return;
      }
    
      int lossrate = Integer.parseInt(args[0]); //loss rate as a percentage
      
      int port = 9093;
      
	  DatagramSocket socket = new DatagramSocket(port);  // Create a datagram socket
	  
	  int ack = 0; //the ack number used for the server
	  
	  //variables for packets
	  boolean synflag = false;
	  boolean ackflag = true; //will always be true for the server
	  boolean finflag = false;
	  
      //variables that store the values received from the last packet
      boolean rec_syn;
      boolean rec_ack;
      boolean rec_fin;
      int rec_dlength;
      int rec_seqack;
      
      byte[] filebuf = new byte[20000000]; //will store the file data as it comes in (and will grow in size)
      String filename = ""; //will receive the filename
      int lastbyte = 0; //used to keep track of where to truncate filebuf in order to produce the output file
      
	  boolean acktimeout = false; //for keeping the server open to grab packets for a while
	  socket.setSoTimeout(0); //this is manipulated to manipulate reception of a stream of packets
	  
	  //all the packet stuff declared here
	  InetAddress clientHost = InetAddress.getByName("0.0.0.0");
      int clientPort = 0;
      byte[] buf = new byte[0];
      byte[] data = new byte[0]; //dummy data file (server does not send data)
      short dlength = (short)0;  
      DatagramPacket reply = new DatagramPacket(buf, buf.length, clientHost, clientPort);

      // Processing loop.
      while (true) {
		 //Thread.sleep(1000);
         // Create a datagram packet to hold incoming UDP packet.
          DatagramPacket request = new DatagramPacket(new byte[408], 408);
         // Block until the host receives a UDP packet.
         try{
			 socket.receive(request);
			 socket.setSoTimeout(10);
			 }
         catch(SocketTimeoutException ex)
		 {
			 if (socket.getSoTimeout()==5000) {
			 System.out.println("Disconnecting.");
			 writeFile(filename, filebuf, lastbyte);
			 socket.close();
			 break;
			}
			if (socket.getSoTimeout()==10) {
			 acktimeout = true;
			}
	     }

         // Decode the header of the received datagram.
         byte[] recdata = request.getData();
         if((recdata[0] & 0x80)==0x80) 
         {
			 rec_syn=true;
		 } 
		 else 
		 {
			 rec_syn=false;
		 }
		 if((recdata[0] & 0x40)==0x40) 
		 {
			 rec_ack=true;
		 } 
		 else 
		 {
			 rec_ack=false;
		 }
		 if((recdata[0] & 0x20)==0x20) 
		 {
			 rec_fin=true;
	     } 
	     else 
	     {
			 rec_fin=false;
		 }

		 rec_dlength = -decodeVal(recdata, 2, 3);       
		 rec_seqack = decodeVal(recdata, 4, 7);
         
         byte[] recdata_noheader = new byte[recdata.length-8]; //pulls out the data below the header     
         for (int i=0; i<recdata.length-8; i++)
         {
			recdata_noheader[i]=recdata[i+8];
		 }
	
		 if (filename.length()>0) {
			 appendBuffer(filebuf, recdata_noheader, ack, rec_dlength, filename.length());
			 lastbyte=rec_seqack+rec_dlength-filename.length();
			 }
		 
		 // Internal counting related to received datagram.
		 
		 System.out.println("Current ACK:"+ack);	 
		 System.out.println("SEQ received:"+rec_seqack);	
		 
		 //triggers for disconnect timeout
		 finflag = rec_fin;
		 if (finflag==true)
		 {
			 socket.setSoTimeout(5000);
		 }
		 
		 //packet flag flowchart
		 
		 if (rec_seqack == ack) {
			if (rec_syn==true)
			{
				synflag=true;
				ack=1;
				System.out.println("SYN flag packet received.  Local ACK is now "+ack);	 
			}
			else if (rec_fin==true)
			{
				System.out.println("FIN flag received.  Sending shutdown ACK and starting timer (5s).");	
				System.out.println("Name of file received: "+filename);
				System.out.println("Length of filename received: "+filename.length());
				finflag = true;
				buf = formPacket(synflag,true,finflag,dlength,ack,data);
				reply = new DatagramPacket(buf, buf.length, clientHost, clientPort);
				socket.send(reply);
				System.out.println("   Reply sent.");
				
			}
			else if (rec_seqack==1)
			{
				filename = decodeDataString(recdata_noheader, rec_dlength);
				System.out.println("Name of file received: "+filename);	
				ack=ack+rec_dlength;
				System.out.println(rec_dlength+" bytes of data received.  ACK incremented to "+ack);
				//Thread.sleep(1000);
			}
			else
			{
				ack=ack+rec_dlength;
				System.out.println(rec_dlength+" bytes of data received.  ACK incremented to "+ack);	
				System.out.println("Data length:"+recdata_noheader.length);
				//printBytes(recdata_noheader, 10);
			}
		}
		else if (acktimeout ==false)
			{
					System.out.println("Received out-of-sequence ACK!");
			}

         //Send reply.
		 if (acktimeout ==false){
		 clientHost = request.getAddress();
         clientPort = request.getPort();
         buf = new byte[8];
         data = new byte[0]; //dummy data file (server does not send data)
         dlength = (short)data.length;
         buf = formPacket(synflag,true,finflag,dlength,ack,data);
         reply = new DatagramPacket(buf, buf.length, clientHost, clientPort);
		}
         if (ack<= 1 || acktimeout==true) {
		 socket.setSoTimeout(0);
		 acktimeout = false;
		 System.out.println("Sending time!");
         if (Math.random()*100<lossrate && ack>1)
			{
				System.out.println("Server packet dropped!");
			}
		 else
			{
				socket.send(reply);
				System.out.println("   Reply sent.");
			}
         
         //cleanup
         
         synflag=false; //disable syn unless another synflag arrives
		}
      }
   }

   private static void printData(byte[] buf) throws Exception
   {

      // Wrap the bytes in a byte array input stream,
      // so that you can read the data as a stream of bytes.
      ByteArrayInputStream bais = new ByteArrayInputStream(buf);

      // Wrap the byte array output stream in an input stream reader,
      // so you can read the data as a stream of characters.
      InputStreamReader isr = new InputStreamReader(bais);

      // Wrap the input stream reader in a bufferred reader,
      // so you can read the character data a line at a time.
      // (A line is a sequence of chars terminated by any combination of \r and \n.) 
      BufferedReader br = new BufferedReader(isr);

      // The message data is contained in a single line, so read this line.
      String line = br.readLine();

      // Print host address and data received from it.
      System.out.println(
         "Data contents: " + new String(line) );
   }
   
   	private static byte[] formPacket(boolean syn, boolean ack, boolean fin, short dlength, int seqack, byte[] message) //puts the appropriate header on the UDP packet
	{
		//headerlead is the first two bytes of the header, which are handled bitwise
		
		byte flagbit = 0x00;
		byte[] packetHeader = new byte[8+message.length];
		
		//set the flags	(first two bytes)
		if (syn==true) flagbit = (byte)(flagbit ^ 0x80);
		if (ack==true) flagbit = (byte)(flagbit ^ 0x40);
		if (fin==true) flagbit = (byte)(flagbit ^ 0x20);
		
		packetHeader[0] = flagbit;
		
		//read in the data length	
		packetHeader[2] = (byte)((dlength & 0xFF00)>>8);
		packetHeader[3] = (byte)(dlength & 0x00FF);
		
		//read in the seq/ack number
		packetHeader[4] = (byte)((seqack & 0xFF000000)>>24);
		packetHeader[5] = (byte)((seqack & 0x00FF0000)>>16);
		packetHeader[6] = (byte)((seqack & 0x0000FF00)>>8);
		packetHeader[7] = (byte)(seqack & 0x000000FF);
		
		//append data
		for(int i=8; i<8+message.length; i++)
		{
			packetHeader[i]=message[i-8];	
		}
		
		return packetHeader;
		
	}
	
		private static int decodeVal(byte[] data, int a, int b) //decodes the number represented by the value in between bytes a and b in data
	{
		int doutput = 0;
		int abe = (int)Math.pow(2,(8*(1+b-a)-1))+1;
		for (int i=a; i<b+1; i++)
		{
		int babe = 128;
			for (int j=0; j<8; j++)
			  {
				  int input = data[i] & babe;
				  int output;
				  if (input == 0) {output=0;} else {output=1;}
				  doutput = doutput + abe*(output);

				  abe=abe/2;
				  babe=babe/2;
			  }
		}
		int foutput = (int)(-doutput);
		return foutput;
	}
	
	  public static String decodeDataString(byte[] buf, int dlength) throws Exception
   {
	   
	  byte[] newbuf = new byte[dlength];
	  
	  for (int i=0; i<dlength; i++)
	  {
			newbuf[i] = buf[i];
	  }
	   
      ByteArrayInputStream bais = new ByteArrayInputStream(newbuf);
      InputStreamReader isr = new InputStreamReader(bais);
      BufferedReader br = new BufferedReader(isr);
      String line = br.readLine();
         
      return line;
   }
   
	  private static void printBytes(byte[] data, int length) //prints out the packet for diagnostic purposes
	{
		for (int i=0; i<length; i++)
		{
			int k=128;
			for (int j=0; j<8; j++)
			  {
				  String output;
				  int input = k & data[i];
				  if (input == 0) {output="0";} else {output="1";}
				  System.out.print(output);
				  k=k/2;
			  }
			  {System.out.println();}
		}
		System.out.println("END OF PACKET");
	}


  public static void writeFile(String strFilePath, byte[] data, int lastbyte) {
	  
	 byte[] writefilebuf = new byte[lastbyte-1];
	 System.out.println("Writing file.");
	 for (int i=0; i<lastbyte-1; i++)
	 {
		 writefilebuf[i] = data[i];
	 }
    
     try
     {

       
      FileOutputStream fos = new FileOutputStream(strFilePath, true);
  
       fos.write(writefilebuf);
    
       fos.close();
     
     }
     catch(FileNotFoundException ex)
     {
      System.out.println("FileNotFoundException : " + ex);
     }
     catch(IOException ioe)
     {
      System.out.println("IOException : " + ioe);
     }
   
  }
  
   public static void appendBuffer(byte[] filebuf, byte[] packet, int ack, int dlength, int filenamelength) {  //appends the file receive buffer with new data; returns new file buffer
	   	  
	  //System.out.println("Append ack:"+ack); 
	  for (int i=0; i<dlength; i++) {
		  //System.out.println("i="+i); 
			filebuf[i+ack-filenamelength-1] = packet[i];
	  }

   }
   
}

