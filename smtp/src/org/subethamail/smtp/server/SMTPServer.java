package org.subethamail.smtp.server;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.util.Collection;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.subethamail.smtp.MessageListener;
import org.subethamail.smtp.Version;

/**
 * Main SMTPServer class.  Construct this object, set the
 * hostName, port, and bind address if you wish to override the 
 * defaults, and call start(). 
 * 
 * This class starts opens a ServerSocket and creates a new
 * instance of the ConnectionHandler class when a new connection
 * comes in.  The ConnectionHandler then parses the incoming SMTP
 * stream and hands off the processing to the CommandHandler which
 * will execute the appropriate SMTP command class.
 *  
 * This class also manages a watchdog thread which will timeout 
 * stale connections.
 *
 * In order to instantiate a new server, one must pass in a Set of
 * MessageListeners. These listener classes are executed during the
 * RCPT TO: (MessageListener.accept()) phase and after the CRLF.CRLF
 * data phase (MessageListener.deliver()). This way, the server itself
 * is not responsible for dealing with the actual SMTP data and that
 * aspect is essentially handed off to other tools to deal with.
 * This is unlike every other Java SMTP server on the net.
 * 
 * @author Jon Stevens
 * @author Ian McFarland &lt;ian@neo.com&gt;
 * @author Jeff Schnitzer
 */
@SuppressWarnings("serial")
public class SMTPServer implements Runnable
{
	private static Log log = LogFactory.getLog(SMTPServer.class);

	private InetAddress bindAddress = null;	// default to all interfaces
	private int port = 25;	// default to 25
	private String hostName;	// defaults to a lookup of the local address
	
	private Collection<MessageListener> listeners;

	private CommandHandler commandHandler;
	
	private ServerSocket serverSocket;
	private boolean go = false;
	
	private Thread serverThread;
	private Watchdog watchdog;

	private ThreadGroup connectionHanderGroup;
	
	/** 
	 * set a hard limit on the maximum number of connections this server will accept 
	 * once we reach this limit, the server will gracefully reject new connections.
	 * Default is 1000.
	 */
	private int maxConnections = 1000;

	/**
	 * The timeout for waiting for data on a connection is one minute: 1000 * 60 * 1
	 */
	private int connectionTimeout = 1000 * 60 * 1;

	/**
	 * The maximal number of recipients that this server accepts per message delivery request.
	 */
	private int maxRecipients = 1000;
	
	/**
	 * The main SMTPServer constructor.
	 */
	public SMTPServer(Collection<MessageListener> listeners) 
	{
		this.listeners = listeners;

		try
		{
			this.hostName = InetAddress.getLocalHost().getCanonicalHostName();
		}
		catch (UnknownHostException e)
		{
			this.hostName = "localhost";
		}

		this.commandHandler = new CommandHandler();		

		this.connectionHanderGroup = new ThreadGroup(SMTPServer.class.getName() + " ConnectionHandler Group");
	}

	/** @return the host name that will be reported to SMTP clients */
	public String getHostName()
	{
		return this.hostName;
	}

	/** The host name that will be reported to SMTP clients */
	public void setHostName(String hostName)
	{
		this.hostName = hostName;
	}

	/** null means all interfaces */
	public InetAddress getBindAddress()
	{
		return this.bindAddress;
	}

	/** null means all interfaces */
	public void setBindAddress(InetAddress bindAddress)
	{
		this.bindAddress = bindAddress;
	}

	/** */
	public int getPort()
	{
		return this.port;
	}

	public void setPort(int port)
	{
		this.port = port;
	}

	/**
	 * Call this method to get things rolling after instantiating
	 * the SMTPServer.
	 */
	public void start()
	{
		if (this.serverThread != null)
			throw new IllegalStateException("SMTPServer already started");
		
		this.serverThread = new Thread(this, SMTPServer.class.getName());
		//this.serverThread.setDaemon(true);	// Should this be set?
		this.serverThread.start();

		this.watchdog = new Watchdog(this);
		this.watchdog.setDaemon(true);
		this.watchdog.start();
	}

	/**
	 * Shut things down gracefully.
	 */
	public void stop()
	{
		this.go = false;
		this.serverThread = null;
		
		if (this.watchdog != null)
		{
			this.watchdog.quit();
			this.watchdog = null;
		}

		// force a socket close for good measure
		try
		{
			if (this.serverSocket != null && this.serverSocket.isBound() && !this.serverSocket.isClosed())
				this.serverSocket.close();
		}
		catch (IOException e)
		{
		}
	}

	/**
	 * This method is called by this thread when it starts up.
	 */
	public void run()
	{
		try
		{
			if (this.bindAddress == null)
				this.serverSocket = new ServerSocket(this.port, 50);
			else
				this.serverSocket = new ServerSocket(this.port, 50, this.bindAddress);
		}
		catch (Exception e)
		{
			throw new RuntimeException(e);
		}

		this.go = true;
		while (this.go)
		{
			try
			{
				ConnectionHandler connectionHandler = new ConnectionHandler(this, serverSocket.accept());
				connectionHandler.start();
			}
			catch (IOException ioe)
			{
				
//				Avoid this exception when shutting down.
//				20:34:50,624 ERROR [STDERR]     at java.net.PlainSocketImpl.socketAccept(Native Method)
//				20:34:50,624 ERROR [STDERR]     at java.net.PlainSocketImpl.accept(PlainSocketImpl.java:384)
//				20:34:50,624 ERROR [STDERR]     at java.net.ServerSocket.implAccept(ServerSocket.java:450)
//				20:34:50,624 ERROR [STDERR]     at java.net.ServerSocket.accept(ServerSocket.java:421)
//				20:34:50,624 ERROR [STDERR]     at org.subethamail.smtp2.SMTPServer.run(SMTPServer.java:92)
//				20:34:50,624 ERROR [STDERR]     at java.lang.Thread.run(Thread.java:613)
				if (this.go)
				{
					log.error(ioe.toString());
				}
			}
		}

		try
		{
			if (this.serverSocket != null && this.serverSocket.isBound() && !this.serverSocket.isClosed())
				this.serverSocket.close();
			log.info("SMTP Server socket shut down.");
		}
		catch (IOException e)
		{
			log.error("Failed to close server socket.", e);
		}
	}

	public String getName()
	{
		return "SubEthaSMTP";
	}

	public String getNameVersion()
	{
		return getName() + " " + Version.getSpecification();
	}

	/**
	 * The Listeners are what the SMTPServer delivers to.
	 */
	public Collection<MessageListener> getListeners()
	{
		return this.listeners;
	}

	/**
	 * The CommandHandler manages handling the SMTP commands
	 * such as QUIT, MAIL, RCPT, DATA, etc.
	 * 
	 * @return An instance of CommandHandler
	 */
	public CommandHandler getCommandHandler()
	{
		return this.commandHandler;
	}

	protected ThreadGroup getConnectionGroup()
	{
		return this.connectionHanderGroup;
	}

	public int getNumberOfConnections()
	{
		return this.connectionHanderGroup.activeCount();
	}
	
	public boolean hasTooManyConnections()
	{
		return (getNumberOfConnections() >= maxConnections);
	}
	
	public int getMaxConnections()
	{
		return this.maxConnections;
	}

	public void setMaxConnections(int maxConnections)
	{
		this.maxConnections = maxConnections;
	}

	public int getConnectionTimeout()
	{
		return this.connectionTimeout;
	}

	public void setConnectionTimeout(int connectionTimeout)
	{
		this.connectionTimeout = connectionTimeout;
	}

	public int getMaxRecipients()
	{
		return this.maxRecipients;
	}

	public void setMaxRecipients(int maxRecipients)
	{
		this.maxRecipients = maxRecipients;
	}

	/**
	 * A watchdog thread that makes sure that
	 * connections don't go stale. It prevents
	 * someone from opening up MAX_CONNECTIONS to 
	 * the server and holding onto them for more than
	 * 1 minute.
	 */
	private class Watchdog extends Thread
	{
		private SMTPServer server;
		private Thread[] groupThreads = new Thread[maxConnections];
		private boolean run = true;

		public Watchdog(SMTPServer server)
		{
			super(Watchdog.class.getName());
			this.server = server;
			setPriority(Thread.MAX_PRIORITY / 3);
		}

		public void quit()
		{
			this.run = false;
		}

		public void run()
		{
			while (this.run)
			{
				ThreadGroup connectionGroup = this.server.getConnectionGroup();
				connectionGroup.enumerate(this.groupThreads);

				for (int i=0; i<connectionGroup.activeCount(); i++)
				{
					ConnectionHandler aThread = ((ConnectionHandler)this.groupThreads[i]);
					if (aThread != null)
					{
						// one minute timeout
						long lastActiveTime = aThread.getLastActiveTime() + (this.server.connectionTimeout);
						if (lastActiveTime < System.currentTimeMillis())
						{
							try
							{
								aThread.timeout();
							}
							catch (IOException ioe)
							{
								log.debug("Lost connection to client during timeout");
							}
						}
					}
				}
				try
				{
					// go to sleep for 10 seconds.
					sleep(1000 * 10);
				}
				catch (InterruptedException e)
				{
					// ignore
				}
			}
		}
	}
}