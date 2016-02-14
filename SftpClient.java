import java.io.*;
import java.net.*;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.Path;

public class SftpClient
{

   public static void main(String[] args) throws Exception
   {
      // Get command line argument.
      
      if (args.length != 4) {
         System.out.println("Required arguments: address, port, filename, loss rate");
         return;
      }
    
      int port = Integer.parseInt(args[1]);
      InetAddress ipaddr = InetAddress.getByName(args[0]);
	  DatagramSocket socket = new DatagramSocket(port);
	  socket.setSoTimeout(2000); //two second timeout! (default)
	  
	  //file readin
	  Path path = Paths.get(args[2]);
	  String filename = args[2];
	  byte[] filebuf = Files.readAllBytes(path); //read in the file
	  int fileptr = 0; //pointer before which all data in the filebuf has been sent
	  System.out.println("File size: "+filebuf.length);
	  
	  //for simulating loss
	  int lossrate = Integer.parseInt(args[3]);

	  //for timing of packet RTT
	  long sendtime;
	  long rectime;
	  
	  //outgoing data variables
	  boolean synflag = true;
	  boolean ackflag = false; //will always be false for client
	  boolean finflag = false;
	  byte[] header = new byte[8]; //header byte array
	  byte[] datasent = new byte[0]; //datagram body byte array
	  short dlength;
	      
	  //incoming data variables
      boolean rec_syn;
      boolean rec_ack;
      boolean rec_fin;
      int rec_dlength;
      int rec_seqack;
      
      //time variables for keeping track of total transfer time
      long starttime = 0;
      long totaltime = 0;
      
      //internal state variables
	  boolean acked = false; //whether the client has gotten the initial ack from the server
	  boolean nameacked = false; //whether the client has had the filename acked by the server
	  int seq = 0; //the client's sequence number representing the back of the window
	  int ack = 0; //largest ack the client has received from the server
	  int sent = 0; //sequence number of the last packet sent out
	  
	  //variables for buffering the last N packets
	  int n = 1; //number of old packets to buffer
	  byte[][] packetholder;
	  packetholder = new byte[n][408];
	  
	  //go back N-related timers
	  double acktimer = 0; //stores the last time a packet was acked
	  double rttimer = System.currentTimeMillis(); //millisstores the moment the last volley of packets was sent out for retransmission purposes
	  
	  //initial filling up of buffer
	  int minifileptr = 0;
	  int packets = filebuf.length/400+1; //number of packets needed to send whole file
	  System.out.println("Number of packets:"+packets);
	  int filesize = 400;
	  for (int i=0; i<Math.min(n,packets); i++)
	  {
	     filesize = 400;
	     finflag = false;
	     if (filebuf.length-minifileptr<400) {filesize = (filebuf.length-minifileptr); finflag=true;}
	     datasent = prepData(filebuf, minifileptr, (short)filesize);
	     header = formPacket(false,false,finflag,(short)filesize,1+filename.length()+minifileptr,datasent);
	     packetholder[i]=header;
	     minifileptr = minifileptr + filesize;
	  }

      // Processing loop.
      while (true) {
		 //Thread.sleep(2000);
		 if (seq == 0)
	     {
			 synflag = true;
			 datasent = new byte[0];
			 dlength = (short)datasent.length;
		 }
		 else if (seq == 1)
		 {
			 synflag = false;
			 datasent = filename.getBytes();
			 dlength = (short)datasent.length;		
		 }
		 else 
		 {
			 synflag = false;
			 datasent = packetholder[0];		
			 dlength = (short)datasent.length;
	     }

		 sendtime = System.currentTimeMillis();
		 if (starttime ==0) {starttime = System.currentTimeMillis();}
					if (seq <= 1)
						 {
							 header = formPacket(synflag,false,false,dlength,seq,datasent);
							 DatagramPacket packet = new DatagramPacket(header,header.length,ipaddr,port);
							 socket.send(packet);
							 if (seq==0) {sent = 0;}
							 if (seq==1) {sent = 1;}
						 }
						 else 
						 {
							int sentsoon=sent; //this value will become the new "sent" after this packet volley		
							header = packetholder[Math.min(n,packets)-1];
							rec_seqack = decodeVal(header, 4, 7); //figure out the seq number of the last packet in the packetholder	
							if (packets>n) { //rolling system for updating the buffer with packets
							header = packetholder[0];
							int rec_oldseq = decodeVal(header, 4, 7); //look at the sequence number of the first packet
							//while (rec_seqack-seq<Math.min(400*(Math.min(n,packets)-1),filebuf.length-rec_seqack)) //the window is expanded ahead
							while (seq>rec_oldseq && filebuf.length-rec_seqack>=400)
							{
								header = packetholder[Math.min(n,packets)-1]; //look at the last packet
								int old_dlength = -decodeVal(header, 2, 3);  //the dlength of the old last packet
								short send_dlength = (short)Math.min(filebuf.length-rec_seqack-400+filename.length()+1,400); //the dlength of the new last packet
								if (send_dlength<0) {send_dlength=0;}
								datasent = prepData(filebuf,rec_seqack+old_dlength-filename.length()-1,send_dlength); //get the new packet's data ready				
								if (send_dlength<400) 
								{
									finflag=true;
									int finseq = rec_seqack+old_dlength;
									System.out.println("The FIN packet is in the buffer.  (seq= "+finseq+")");
								} 
								else {finflag = false;}
								header = formPacket(false,false,finflag,send_dlength,rec_seqack+400,datasent);
								updatePacketBuffer (packetholder, header, n-1);
								rec_seqack = decodeVal(header, 4, 7);
								header = packetholder[0];
								rec_oldseq = decodeVal(header, 4, 7);
							}
							}
							for (int i=0; i<n; i++)
							{
								header = packetholder[i];
								rec_seqack = decodeVal(header, 4, 7);
							}
							
							//set the timer for the oldest packet
							rttimer =  System.currentTimeMillis();
							
							for (int i=0; i<Math.min(n,packets); i++)  //in this loop, packets are actually sent (and "send" is updated)
							{
								header = packetholder[i];
								rec_seqack = decodeVal(header, 4, 7);
								if (rec_seqack>sent)
								{
									DatagramPacket packet = new DatagramPacket(header,header.length,ipaddr,port);

									rec_dlength = -decodeVal(header, 2, 3);      
									System.out.println("Packet sent. (seq = "+rec_seqack+")");		
									//printBytes(header,12);	
									if (Math.random()*100<lossrate)
									{
										System.out.println("Packet dropped by client! (seq = "+rec_seqack+")");
									}
									else
									{
										socket.send(packet);							
									}
									sentsoon = rec_seqack;
									//Thread.sleep(250);	 
									
								}
							}
							sent = sentsoon;
						 }
		 double progress = 100*seq/filebuf.length;
		 System.out.println("File transfer progress: "+progress+"%");
         DatagramPacket request = new DatagramPacket(new byte[408], 408);
         
         socket.setSoTimeout((int)(2000+rttimer-System.currentTimeMillis())); //set the socket timeout based on rttimer
         try{
			socket.receive(request);
			rectime = System.currentTimeMillis();
			acktimer = System.currentTimeMillis();
			System.out.println("Reply received. (seq = "+seq+")");
			
			//Decode the received ack.
         
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

		 rec_dlength = decodeVal(recdata, 2, 3);    
		 rec_seqack = decodeVal(recdata, 4, 7);
		 
		 // Internal counting related to received datagram.
		 
		 if (seq==0 && rec_seqack>1) {	 
			 System.out.println("Cannot connect.  Server is already connected.");	 
			 break;
		 }
			else
			{
		 if (rec_syn==true)
		 {
			System.out.println("Connection established. (seq is now "+seq+")");	 
			seq=1;
		 }
		 else if (rec_fin==true)
		 {
			System.out.println("FIN signal acknowledged. Shutting down.");	 
			totaltime = System.currentTimeMillis() - starttime;
			writeStatusReport(filename, filebuf.length, totaltime);
			break;
		 }
		 else
		 {
			if (rec_seqack>seq){
				seq=rec_seqack;
				System.out.println("ACK="+rec_seqack+" received. SEQ updated to "+seq);

					if (rec_seqack > filename.length()+1)
						{
						fileptr = Math.min((fileptr + datasent.length),filebuf.length);
						}					
				
			}
			else
			{
				System.out.println("Error: received an old ACK! (ack = "+rec_seqack+")");	 
			}
		 }
		}	
			
		 }
		 catch(SocketTimeoutException ex)
		 {
			 System.out.println("Retransmitting:");
				//set the timer for the oldest retransmission packet
			 	rttimer = System.currentTimeMillis(); 
				for (int i=0; i<Math.min(n,packets); i++)
				{
					header = packetholder[i];
					rec_seqack = decodeVal(header, 4, 7);
					if (rec_seqack<=sent && rec_seqack>=seq)				
						{
						DatagramPacket packet = new DatagramPacket(header,header.length,ipaddr,port);

						rec_dlength = -decodeVal(header, 2, 3);      
						System.out.println("Retransmitted packet sent. (seq = "+rec_seqack+")");		
						//printBytes(header,12);	
						if (Math.random()*100<lossrate)
						{
							System.out.println("Packet dropped by client! (seq = "+rec_seqack+")");
						}
						else
						{
							socket.send(packet);							
						}
						//Thread.sleep(250);	 	
					}
				}
				System.out.println("Sent: "+sent+")");	
	     }
      }
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
		//System.out.println();
		int foutput = (int)(-doutput);
	//	System.out.println(doutput);
		return foutput;
	}
	

	   public static String decodeDataString(byte[] buf) throws Exception
   {
      ByteArrayInputStream bais = new ByteArrayInputStream(buf);
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
	
	   
     private static byte[] prepData (byte[] data, int ptr, short dlength) //readies the 1016-byte data contents of next UDP packet
     {
		byte[] output = new byte[dlength];
			for (int i=0; i<dlength; i++)
				{
					output[i] = data[i+ptr];
				}
				
		return output;
	 }
	 
	   public static void writeStatusReport(String filename, int filesize, long time) {
   
		String strFilePath = "stfp_status_report.txt";
		time = time/1000; //convert to seconds
   
     try
     {
      FileOutputStream fos = new FileOutputStream(strFilePath);
      String strContent = "FILE TRANSFER STATUS REPORT \n Filename: "+filename+"\n Filesize: "+filesize+" bytes \n Transfer time: "+time+" s";
        
       byte[] data = strContent.getBytes();
     
       fos.write(data);
     
     
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
   
	   private static byte[][] updatePacketBuffer (byte[][] packetbuffer, byte[] packet, int n) //adds newest packet to buffer; discards the oldest
     {
			for (int i=0; i<n; i++)
			{
					packetbuffer[i] = packetbuffer[i+1];
			}

			packetbuffer[n] = packet;
			
			return packetbuffer;
	 }

}

