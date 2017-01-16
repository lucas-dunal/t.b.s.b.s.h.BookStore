package tibco.bw.jms.weblogic.client;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.Queue;
import javax.jms.QueueConnection;
import javax.jms.QueueConnectionFactory;
import javax.jms.QueueReceiver;
import javax.jms.QueueSession;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;

import com.tibco.bw.palette.shared.java.JavaProcessStarter;

@SuppressWarnings("rawtypes")
public class WeblogicProcessStarter extends JavaProcessStarter implements Serializable {
	 
	private static final long serialVersionUID = 1L;
	
	private static final java.util.logging.Logger logger = Logger.getLogger(WeblogicProcessStarter.class.getName());
	
	public WeblogicProcessStarter(){
		logger.info("======> WeblogicProcessStarter constructor");
	}
	
	private InitialContext ctx;
	private QueueConnectionFactory qcf;
	private Queue qresp;
	private Map<String, QueueConnection> qc; 
	private List<QueueSession> qsess;
	private List<QueueReceiver> qrecvr;
		
	private class InnerListener implements javax.jms.MessageListener {
		
		private String server;
		
		public InnerListener(String s) {
			this.server = s;
		}
		
		@Override
		public void onMessage(Message message) {
			try {
				final TextMessage tms = (TextMessage) message;
				
				logger.info(String.format("Server [%s] Got message [%s]. Notifing BW process...", server, tms.getText()));
				
				onEvent(new WeblogicEventMessageDTO(
						this.server,
						tms.getJMSCorrelationID(), 
						tms.getText())); // notify BW process

			} catch (Exception e) {
				e.printStackTrace(System.err);
				try {
					onError(e);
				} catch (Exception e1) {
					e.printStackTrace(System.err); // clusterfuck
				}
			}
		}
	}
	
	private void registerModuleProperties() throws Exception {
		getEventSourceContext().registerModuleProperty("/JNDI_WLS/CTX_FACTORY");
		getEventSourceContext().registerModuleProperty("/JNDI_WLS/PROVIDER_URL");
		getEventSourceContext().registerModuleProperty("/JNDI_WLS/USERNAME");
		getEventSourceContext().registerModuleProperty("/JNDI_WLS/PASSWORD");		
		getEventSourceContext().registerModuleProperty("/JMS_WLS/QUEUE_CF");
		getEventSourceContext().registerModuleProperty("/JMS_WLS/DESTINATION_REQUEST");
		getEventSourceContext().registerModuleProperty("/JMS_WLS/DESTINATION_RESPONSE");
		getEventSourceContext().registerModuleProperty("/JMS_WLS/CONCURRENT_SESSIONS_COUNT");
	}
		
	@Override
	public void init() throws Exception {	
		logger.info("init()");
		
		// register properies used by ProcessStarter
		registerModuleProperties();
	
		// cheat na klasie weblogic.kernel.KernelStatus umożliwiający użycie extension classloadera 
		// zamiast weblogic.utils.classloaders.GenericClassLoader
		System.setProperty("weblogic.j2ee.client.isWebStart", "true");		
		
		// create InitialContext
		Hashtable<String, String> properties = new Hashtable<String, String>();
		properties.put(Context.INITIAL_CONTEXT_FACTORY, getEventSourceContext().getModuleProperty("/JNDI_WLS/CTX_FACTORY")); 
		properties.put(Context.PROVIDER_URL, getEventSourceContext().getModuleProperty("/JNDI_WLS/PROVIDER_URL"));
		properties.put(Context.SECURITY_PRINCIPAL, getEventSourceContext().getModuleProperty("/JNDI_WLS/USERNAME"));
		properties.put(Context.SECURITY_CREDENTIALS, getEventSourceContext().getModuleProperty("/JNDI_WLS/PASSWORD"));		
		logger.fine("Properties " + properties);
		
		try {
			
			try {
				ctx = (InitialContext)new InitialContext(properties);
			} catch(ExceptionInInitializerError e){
				logger.info("ExceptionInInitializerError, removing ContextClassLoader");
				
				//java.lang.RuntimeException: META-INF/services/weblogic.invocation.ComponentInvocationContextManager is not found in the search path of Thread Context ClassLoader
				ClassLoader prevCl = Thread.currentThread().getContextClassLoader();
				ClassLoader newCl = getClass().getClassLoader();
				// null
				System.out.println(prevCl.getResource("META-INF/services/weblogic.invocation.ComponentInvocationContextManager")); 
				//bundleresource://346.fwk1504109395/META-INF/services/weblogic.invocation.ComponentInvocationContextManager
				System.out.println(newCl.getResource("META-INF/services/weblogic.invocation.ComponentInvocationContextManager")); 
				Thread.currentThread().setContextClassLoader(newCl);
					
				ctx = (InitialContext)new InitialContext(properties);
			}				
		
		} catch (Exception e) {
			e.printStackTrace(System.err);
			throw e;
		}
		logger.fine("Got InitialContext " + ctx.toString());
		
		// create QueueConnectionFactory
		try {
			qcf = (QueueConnectionFactory)ctx.lookup(getEventSourceContext().getModuleProperty("/JMS_WLS/QUEUE_CF"));
		} catch (NamingException e) {
			e.printStackTrace(System.err);
			throw e;
		}
		logger.fine("Got QueueConnectionFactory " + qcf.toString());
		
		// lookup Queue
		try {
			qresp = (Queue) ctx.lookup(getEventSourceContext().getModuleProperty("/JMS_WLS/DESTINATION_REQUEST"));
		} catch (NamingException e) {
			e.printStackTrace(System.err);
			throw e;
		}
		logger.fine("Got Queue " + qresp.toString());

		// create QueueConnection
		try {			
			logger.fine("QueueConnectionFactory.createConnection()");
			
			String[] servers = getEventSourceContext().getModuleProperty("/JNDI_WLS/PROVIDER_URL").replaceAll("t3://", "").split(",");
		
			qc = new HashMap<String, QueueConnection>();					
			for(String server : servers){
				qc.put(server, qcf.createQueueConnection());
				logger.fine("Got QueueConnection for " + server);
			}
			
		} catch (JMSException e) {
			e.printStackTrace(System.err);
			throw e;
		}
		logger.fine("Got QueueConnections " + qc.size());		

		logger.fine("init() done.");
	}

	@Override
	public void onStart() throws Exception {
		logger.info("onStart()");
		
		//start fresh connection	
		for(String server : qc.keySet()){
			qc.get(server).start();
		}
		
		try {	
			final int numberOfReceivers = Integer.parseInt(getEventSourceContext().getModuleProperty("/JMS_WLS/CONCURRENT_SESSIONS_COUNT"));
			logger.fine(String.format("/JMS_WLS/CONCURRENT_SESSIONS_COUNT = %d", numberOfReceivers));
			
			qsess =  new ArrayList<QueueSession>(numberOfReceivers);
			qrecvr = new ArrayList<QueueReceiver>(numberOfReceivers);
			
			for(String server : qc.keySet()){
				for(int i = 0; i < numberOfReceivers; i++){
					// create QueueSession
					QueueSession qs = qc.get(server).createQueueSession(false, Session.AUTO_ACKNOWLEDGE);
					logger.fine("Got QueueSession " + qs.toString());	
					
					// create QueueReceiver
					QueueReceiver qrec = qs.createReceiver(qresp);
					logger.fine("Got QueueReceiver " + qrec);
					
					// set MessageListener
					qrec.setMessageListener(new InnerListener(server));
					logger.fine("MessageListener set.");
					
					qsess.add(qs);
					qrecvr.add(qrec);	
				}
			}
						
		} catch (JMSException e) {
			e.printStackTrace(System.err);
			throw e;
		}	
		
		logger.fine("onStart() done.");
	}

	@Override
	public void onStop() throws Exception {
		logger.info("onStop()");
		
		// clean up
		try {
			if (qrecvr != null) {
				for(QueueReceiver qrec : qrecvr){
					qrec.close();
				}
				qrecvr.clear();
			}
			if (qsess != null) {
				for(QueueSession qs : qsess){
					qs.close();
				}
				qsess.clear();
			}
			if (qc != null) {
				for(String server : qc.keySet()){
					qc.get(server).close();
				}
			}
		} catch (JMSException e) {
			e.printStackTrace(System.err);
		}
		logger.fine("onStop() done.");		
	}
	
	@Override
	public void onShutdown() {
		logger.info("onShutdown()");
		
		// clean up
		try {			
			if (qrecvr != null) {
				for(QueueReceiver qrec : qrecvr){
					qrec.close();
				}
				qrecvr.clear();
			}
			if (qsess != null) {
				for(QueueSession qs : qsess){
					qs.close();
				}
				qsess.clear();
			}
			if (qc != null) {
				for(String server : qc.keySet()){
					qc.get(server).close();
				}
				qc.clear();			
			}
		} catch (JMSException e) {
			e.printStackTrace(System.err);
		}
		logger.fine("onShutdown() done.");
	}
}
