package org.subethamail.smtp.server;

import org.subethamail.smtp.util.Client;
import org.subethamail.smtp.util.ServerTestCase;
import org.subethamail.smtp.util.Testing;
import org.subethamail.wiser.Wiser;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * @author Erik van Oosten
 */
public class RequireTlsTest extends ServerTestCase
{

	public RequireTlsTest(String name)
	{
		super(name);
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.subethamail.smtp.ServerTestCase#setUp()
	 */
	@Override
	@SuppressFBWarnings
	protected void setUp() throws Exception
	{
		this.wiser = Wiser.accepter(Testing.ACCEPTER).server(SMTPServer.port(PORT).requireTLS());
//		this.wiser.setHostname("localhost");

		this.wiser.start();
		this.c = new Client("localhost", PORT);
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.subethamail.smtp.ServerTestCase#tearDown()
	 */
	@Override
	protected void tearDown() throws Exception
	{
		super.tearDown();
	}

	public void testNeedSTARTTLS() throws Exception
	{
		this.expect("220");

		this.send("HELO foo.com");
		this.expect("530 Must issue a STARTTLS command first");

		this.send("EHLO foo.com");
		this.expect("250");

		this.send("NOOP");
		this.expect("250");

		this.send("MAIL FROM: test@example.com");
		this.expect("530 Must issue a STARTTLS command first");

		this.send("STARTTLS foo");
		this.expect("501 Syntax error (no parameters allowed)");

		this.send("QUIT");
		this.expect("221 Bye");
	}

}