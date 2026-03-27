import java.io.*;
import java.net.*;

public class Client 
{
	private int port;
	private InetAddress address;


	public TCPClient(int port, InetAddress address){

}

	public static void main (String[] args) throws Exception {
	
	Socket socket = new Socket (args [0], port) ;
	System.out.println ("SOCKET = " + socket);
	BufferedReader plec = new BufferedReader (
	new InputStreamReader (socket.getInputStream () )
	);
	
	PrintWriter pred = new PrintWriter (
	new BufferedWriter (
	new OutputStreamWriter (Socket.getOutputStream () ) ),
	true);
	
	String str = "bonjour";
	
	for (int i = 0; i < 10; i++) {
		pred.println (str);
		str = plec.readLine();
	}
	
	System.out.println ("END");
	pred. println ("END");
	plec.close ();
	pred.close();
	socket.close ();
	}
