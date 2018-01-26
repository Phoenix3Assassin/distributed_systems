package test;

import app_kvServer.KVServer;
import junit.framework.Test;
import junit.framework.TestSuite;
import logger.LogSetup;
import org.apache.log4j.Level;

import java.io.IOException;


public class AllTests {

	static {
		try {
			new LogSetup("logs/test/test.log", Level.ERROR);
			new Thread(new KVServer(50000, 10, "FIFO")).start();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	
	public static Test suite() {
		TestSuite clientSuite = new TestSuite("Basic Storage ServerTest-Suite");
		clientSuite.addTestSuite(ConnectionTest.class);
		clientSuite.addTestSuite(InteractionTest.class); 
		clientSuite.addTestSuite(AdditionalTest.class); 
		return clientSuite;
	}
	
}