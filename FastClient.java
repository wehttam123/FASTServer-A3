/**
 * FastClient Class
 *
 * FastClient implements a basic reliable FTP client application based on UDP data transmission and selective repeat protocol
 *
 */
 import java.io.*;
 import java.net.*;
 import java.util.*;

 class TimeOutHandler extends TimerTask {

   /**
    * Constructor to initialize the program
    *
    * @param segment	  segment
    * @param IPAddress	IP address
    * @param port		    port
    * @param UDPsocket  UDP socket
    * @param time       timeout
    */

     private Segment segment;
     private InetAddress IPAddress;
     private int port;
     private DatagramSocket UDPsocket;
     private int time;

     public TimeOutHandler(Segment segment, InetAddress IPAddress, int port, DatagramSocket UDPsocket, int time)
     {
       this.segment = segment;
       this.IPAddress = IPAddress;
       this.port = port;
       this.UDPsocket = UDPsocket;
       this.time = time;
     }

      @Override
      public void run() {
        try {

          //Do not resend if already Acknowledged
          if(segment.getSeqNum() >= (FastClient.listsize - FastClient.list)){

            // Resend segment
            DatagramPacket sendPacket =  new DatagramPacket(segment.getBytes(), segment.getLength(), IPAddress, port);
            if (!UDPsocket.isClosed())
            {
              UDPsocket.send(sendPacket);

              // Restart timer
              Timer timer = new Timer(true);
              timer.schedule(new TimeOutHandler(segment, IPAddress, port, UDPsocket, time), time);
            }
          }
        }
        catch (Exception e)
        {
          System.out.println("Error: " + e.getMessage());
        }
      }
 }

 /**
  * Constructor to initialize the program
  *
  * @param UDPsocket	    UDP socket
  * @param ack	          Acknowledgment
  * @param ackData		    Extracted data from ack
  * @param receivePacket  Recieved packet
  * @param queue          Transmission queue
  */

class Receive extends Thread {

  private DatagramSocket UDPsocket;
  private byte[] ack;
  private Segment ackData;
  private DatagramPacket receivePacket;
  private TxQueue queue;

 	public Receive(DatagramSocket socket, byte[] ackarray, Segment ackseg, DatagramPacket rpkt, TxQueue txqueue)
 	{
    UDPsocket = socket;
    this.ack = ackarray;
    this.ackData = ackseg;
    this.receivePacket = rpkt;
    queue = txqueue;
 	}

 	public void run()
 	{
    try
		{
      // Recieve acks until file is fully transfered
			while(!UDPsocket.isClosed())
			{
        // receive ACK
        receivePacket =  new DatagramPacket(ack, ack.length);
        ackData = new Segment();
        UDPsocket.receive(receivePacket);
        ackData.setBytes(receivePacket.getData());

        // If in the queue then Acknowledge the node
        if(queue.getSegment(ackData.getSeqNum()) != null) {
          queue.getNode(ackData.getSeqNum()).setStatus(1);
          // Remove all Acknowledged nodes
          while (queue.getHeadNode() != null && queue.getHeadNode().getStatus() == 1) {
            queue.remove();
            FastClient.list--;
          }
        }
			}
      System.out.println("socket closed");
		}
		catch (Exception e) {}
 	}
 }

public class FastClient{

 	/**
        * Constructor to initialize the program
        *
        * @param server_name    server name or IP
        * @param server_port    server port
        * @param window         window size
	      * @param timeout	      time out value
        */
        public String name;
        public int port;
        public int wind;
        public int time;

        public final static int MAX_PAYLOAD_SIZE = 1000;
        public static int list = 0;
        public static int listsize = 0;

	public FastClient(String server_name, int server_port, int window, int timeout) {

	/* initialize */
  name = server_name;
  port = server_port;
  wind = window;
  time = timeout;
	}

	/* send file */

	public void send(String file_name) {

    // File stream
    FileInputStream fileIn = null;

    // Server streams
    DataInputStream serverIn = null;
    DataOutputStream serverOut = null;

    // Segment Payload arrays
    byte[] bytes = new byte[MAX_PAYLOAD_SIZE];
    byte[] ack = new byte[MAX_PAYLOAD_SIZE];

    // list of file segments
    ArrayList<byte[]> Segments = new ArrayList<byte[]>();

    // TCP and UDP sockets
    Socket TCPsocket = null;
    DatagramSocket UDPsocket = null;

    // Server Packets
    DatagramPacket sendPacket = null;
    DatagramPacket receivePacket = null;

    // File segments
    Segment ackData = null;
    Segment segment = null;

    int content = 0; // Number of bytes from read

    // Timer
    TimerTask timerTask = null;
    Timer timer = new Timer(true);

    // TxQueue
    TxQueue queue = new TxQueue(wind);

    // Read data from file
    try {
      // Create filestream
      fileIn = new FileInputStream(file_name);

      // Read until end of file
      while(content != -1){
        content = fileIn.read(bytes);

        // If this is the last byte in the file then shrink array
        if (content < MAX_PAYLOAD_SIZE && content != -1)
        {
          byte[] lastbytes = Arrays.copyOf(bytes, content);
          Segments.add(lastbytes);
        } else if (content != -1) {
          Segments.add(bytes);
        }
        bytes = new byte[MAX_PAYLOAD_SIZE];
      }
    } catch (Exception e)
    {
      System.out.println("Error: " + e.getMessage());
    }

    list = Segments.size();
    listsize = Segments.size();

    try {
      // Setup ports
		  TCPsocket = new Socket(name, port);
      UDPsocket = new DatagramSocket();
      InetAddress IPAddress = InetAddress.getByName("localhost");

      // Create necessary streams
      serverIn = new DataInputStream(TCPsocket.getInputStream());
      serverOut = new DataOutputStream(TCPsocket.getOutputStream());

      // initiate TCP Handshake
      while(true)
      {
        // Write filename to server
        serverOut.writeUTF(file_name);
        serverOut.flush();

        // Exit if message from server is 0
        if(serverIn.readByte() == 0){break;}
      }

      // Start receive thread
      Receive receive = new Receive(UDPsocket, ack, ackData, receivePacket, queue);
      receive.start();

      // Send File segments
      for (int i = 0; i < Segments.size(); i++) {
        // Create segment
        segment = new Segment(i,Segments.get(i));
        sendPacket =  new DatagramPacket(segment.getBytes(), segment.getLength(), IPAddress, port);

        // Wait for transmisson queue to have space
        while (queue.isFull()) {};

        // Send segment
        UDPsocket.send(sendPacket);

        // Add segment to transmission queue
        queue.add(segment);

        // Start timer
        timer.schedule(new TimeOutHandler(segment, IPAddress, port, UDPsocket, time), time);
      }

      // Wait for transmisson queue to be empty
      while (!queue.isEmpty()) {};
      UDPsocket.close();

      // Send EOT
      serverOut.writeByte(0);
      serverOut.flush();

      // Clean Up
      fileIn.close();
      serverIn.close();
      serverOut.close();
    }
    catch (Exception e)
    {
      System.out.println("Error: " + e.getMessage());
    }
    finally
    {
      if (TCPsocket != null)
      {
        try
        {
          TCPsocket.close();
        }
        catch (IOException ex) {}
      }
      if (UDPsocket != null)
      {
        UDPsocket.close();
      }
    }

	}

    /**
     * A simple test driver
     *
     */
	public static void main(String[] args) {
		int window = 10; //segments
		int timeout = 100; // milli-seconds (don't change this value)

		String server = "localhost";
		String file_name = "";
		int server_port = 0;

		// check for command line arguments
		if (args.length == 4) {
			// either provide 3 parameters
			server = args[0];
			server_port = Integer.parseInt(args[1]);
			file_name = args[2];
			window = Integer.parseInt(args[3]);
		}
		else {
			System.out.println("wrong number of arguments, try again.");
			System.out.println("usage: java FastClient server port file windowsize");
			System.exit(0);
		}

		FastClient fc = new FastClient(server, server_port, window, timeout);

		System.out.printf("sending file \'%s\' to server...\n", file_name);
		fc.send(file_name);
		System.out.println("file transfer completed.");
	}

}
