package tibco.bw.jms.weblogic.client;

import java.io.Serializable;

public class WeblogicEventMessageDTO implements Serializable {
	
	private static final long serialVersionUID = 1L;

	public String server;
	public String jmsCorrelationId;
	public String messageText;
	
	public WeblogicEventMessageDTO(){
		super();
	}
	
	public WeblogicEventMessageDTO(String server, String jmsCorrelationId, String messageText) {
		super();
		this.server = server;
		this.jmsCorrelationId = jmsCorrelationId;
		this.messageText = messageText;
	}	
	
	public String getServer() {
		return server;
	}

	public void setServer(String server) {
		this.server = server;
	}

	public String getJmsCorrelationId() {
		return jmsCorrelationId;
	}
	
	public void setJmsCorrelationId(String jmsCorrelationId) {
		this.jmsCorrelationId = jmsCorrelationId;
	}
	
	public String getMessageText() {
		return messageText;
	}
	
	public void setMessageText(String messageText) {
		this.messageText = messageText;
	}
}
