package tibco.bw.jms.weblogic.client;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Logger;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.Topic;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;


public class WeblogicHelper implements Serializable {
	 	
	private static final long serialVersionUID = 1L;
	
	private static final java.util.logging.Logger logger = Logger.getLogger(WeblogicHelper.class.getName());
	
	private boolean isInitialized;
	private InitialContext ctx = null;
	private ConnectionFactory connectionFactory = null;
	private Queue qreq = null;
	private Topic tresp = null;
	private Map<String, Connection> connMap = null;
	
	public WeblogicHelper(){
		logger.info("======> WeblogicHelper constructor");
		isInitialized = false;
	}
	
	private synchronized void init(String jobId, String jndiContextFactory, String jndiProviderUrl, String jndiUsername, String jndiPassword, String jndiConnectionFactory, String jndiReqQueueName, String jndiRespTopicName) throws Exception {
		// // =================================================> INIT
		logger.info(String.format("JobId [%s] init: isInitialized %s", jobId, isInitialized));
		if (isInitialized){
			return;
		}

		// create InitialContext
		Hashtable<String, String> properties = new Hashtable<String, String>();
		properties.put(Context.INITIAL_CONTEXT_FACTORY, jndiContextFactory);
		properties.put(Context.PROVIDER_URL, jndiProviderUrl);
		properties.put(Context.SECURITY_PRINCIPAL, jndiUsername);
		properties.put(Context.SECURITY_CREDENTIALS, jndiPassword);
		logger.info(String.format("JobId [%s] init: Properties %s", jobId, properties));

		try {
			ctx = (InitialContext) new InitialContext(properties);
			logger.info(String.format("JobId [%s] init: Got InitialContext %s", jobId, ctx.toString()));

		} catch (NamingException e) {
			e.printStackTrace(System.err);
			throw e;
		}

		// create ConnectionFactory
		try {
			connectionFactory = (ConnectionFactory) ctx.lookup(jndiConnectionFactory);
			logger.info(String.format("JobId [%s] init: Got JMS ConnectionFactory %s", jobId, connectionFactory.toString()));

		} catch (NamingException e) {
			e.printStackTrace(System.err);
			throw e;
		}

		// lookup Request Queue
		try {
			qreq = (Queue) ctx.lookup(jndiReqQueueName);
			logger.info(String.format("JobId [%s] init: Got Queue %s", jobId, qreq.toString()));

		} catch (NamingException e) {
			e.printStackTrace(System.err);
			throw e;
		}

		// lookup Response Queue
		try {
			tresp = (Topic) ctx.lookup(jndiRespTopicName);
			logger.info(String.format("JobId [%s] init: Got Topic %s", jobId, tresp.toString()));

		} catch (NamingException e) {
			e.printStackTrace(System.err);
			throw e;
		}
		
		// create QueueConnection
		logger.info(String.format("JobId [%s] init: ConnectionFactory.createConnection()", jobId));		
		
		String[] servers = jndiProviderUrl.replaceAll("t3://", "").split(",");
	
		connMap = new HashMap<String, Connection>();					
		for(String server : servers){
			Connection conn = connectionFactory.createConnection();
			conn.start();			
			connMap.put(server, conn);
			logger.info(String.format("JobId [%s] init: Got connection to %s %s", jobId, server, conn.toString()));
		}
		
		logger.info(String.format("JobId [%s] init: Got  %d connections", jobId, connMap.size()));		
		
		isInitialized = true;
	} 
	
	@Override
	public void finalize(){
		try {
			if (connMap != null) {
				for(String server : connMap.keySet()){
					connMap.get(server).close();
				}
				connMap.clear();			
			}		
		} catch (JMSException e) {
			e.printStackTrace(System.err);
		}
	}
		
	public String jmsResponse(String jobId, String jndiContextFactory, String jndiProviderUrl, String jndiUsername, String jndiPassword, String jndiConnectionFactory, String jndiReqQueueName, String jndiRespTopicName, String messageText, String messageCorrelation, String respondToServer) throws Exception {
		logger.info(String.format("JobId [%s] jmsResponse(String ... params)", jobId));				
		
		Session tsess = null;
		MessageProducer tpublshr = null;
		TextMessage message = null;

		//// =================================================> INIT	
		init(jobId, jndiContextFactory, jndiProviderUrl, jndiUsername, jndiPassword, jndiConnectionFactory, jndiReqQueueName, jndiRespTopicName);
		
		//// =================================================> SEND MESSSAGE	
		// create JMS Session
		try {
			logger.info(String.format("JobId [%s] jmsResponse: Responding to server [%s] ", jobId, respondToServer));
			tsess = connMap.get(respondToServer).createSession(false, Session.AUTO_ACKNOWLEDGE);
			logger.info(String.format("JobId [%s] jmsResponse: Got JMS Session %s", jobId, tsess.toString()));

			// create TextMessage
			try {
				message = tsess.createTextMessage(messageText);
				message.setJMSCorrelationID(messageCorrelation);
				logger.info(String.format("JobId [%s] jmsResponse: Created TextMessage %s", jobId, message.toString()));

			} catch (JMSException e) {
				e.printStackTrace(System.err);
				throw e;
			}

			// create MessageProducer and send message
			try {
				tpublshr = tsess.createProducer(tresp);
				logger.info(String.format("JobId [%s] jmsResponse: Got MessageProducer %s", jobId, tpublshr.toString()));
				tpublshr.send(message);
				logger.info(String.format("JobId [%s] jmsResponse: Sent response", jobId));

			} catch (JMSException e) {
				e.printStackTrace(System.err);
				throw e;
			} finally {
				if (tpublshr != null)
					tpublshr.close();
			}

		} catch (JMSException e) {
			e.printStackTrace(System.err);
			throw e;
		} finally {
			if (tsess != null)
				tsess.close();
		}

		return "OK";
	}
	
	public String jmsRequestResponse(String jobId, String jndiContextFactory, String jndiProviderUrl, String jndiUsername, String jndiPassword, String jndiConnectionFactory, String jndiReqQueueName, String jndiRespTopicName, String messageText, long timeout) throws Exception {
		logger.info(String.format("JobId [%s] jmsRequestResponse(String ... params)", jobId));
		
		final UUID guid = UUID.randomUUID();			
		
		Session sess = null;		
		MessageProducer qsndr = null;		
		MessageConsumer tsubr = null;
		TextMessage message = null;		
		
		//// =================================================> INIT	
		init(jobId, jndiContextFactory, jndiProviderUrl, jndiUsername, jndiPassword, jndiConnectionFactory, jndiReqQueueName, jndiRespTopicName);
				
		//// =================================================> REQUEST	/ RESPONSE
						
		// create QueueSession
		try {
			final String [] servers = connMap.keySet().toArray(new String[connMap.size()]);
			final String server = servers[ new Random().nextInt(connMap.size()) ];
			logger.info(String.format("JobId [%s] jmsRequestResponse: Randomized server is %s", jobId, server));
			
			sess = connMap.get(server).createSession(false, Session.AUTO_ACKNOWLEDGE);
			logger.info(String.format("JobId [%s] jmsRequestResponse: Got JMS Session %s", jobId, sess.toString()));

			try {
				// create Queue Sender
				qsndr = sess.createProducer(qreq);
				logger.info(String.format("JobId [%s] jmsRequestResponse: Got MessageProducer %s", jobId, qsndr.toString()));

				// create TextMessage
				message = sess.createTextMessage(messageText);
				message.setJMSCorrelationID(guid.toString());
				logger.info(String.format("JobId [%s] jmsRequestResponse: Created TextMessage %s", jobId, message));

				// send request message
				qsndr.send(message);
				logger.info(String.format("JobId [%s] jmsRequestResponse: Sent message", jobId));

				try {
					// create Topic Subscriber with JMSCorrelationID selector
					final String selector = String.format("JMSCorrelationID='%s'", guid.toString());
					logger.info(String.format("JobId [%s] jmsRequestResponse: JMS selector %s", jobId, selector));
					
					tsubr = sess.createConsumer(tresp, selector);					
					logger.info(String.format("JobId [%s] jmsRequestResponse: Got MessageConsumer %s", jobId, tsubr.toString()));

					// receive response with timeout
					logger.info(String.format("JobId [%s] jmsRequestResponse: JMS timeout %s", jobId, timeout));
					message = (TextMessage) tsubr.receive(timeout);
					
					if(message != null){
						logger.info(String.format("JobId [%s] jmsRequestResponse: Received response %s with JMSCorrelationId %s", jobId, message, message.getJMSCorrelationID()));
					} else {
						logger.warning(String.format("JobId [%s] jmsRequestResponse: No response receied!", jobId));
					}
				} catch (JMSException e) {
					e.printStackTrace(System.err);
					throw e;
				} finally {
					if (tsubr != null)
						tsubr.close();
				}

			} catch (JMSException e) {
				e.printStackTrace(System.err);
				throw e;
			} finally {
				if (qsndr != null)
					qsndr.close();
			}

		} catch (JMSException e) {
			e.printStackTrace(System.err);
			throw e;
		} finally {
			if (sess != null)
				sess.close();
		}	
		if(message != null){
			return message.getText();
		} else {
			return "No response receied!";
		}
		
	}
}
