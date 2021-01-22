package com.venkat.camel.integration;

import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

//@Component
public class DemoRouter extends RouteBuilder {

	@Override
	public void configure() throws Exception {	
	 /*from("timer:foo?period=5000")
	 .setBody(simple("resource:classpath:source.json"))
	 .log("> Sending: [${body}]")
     .to("atlasmap:name.adm?sourceMapName=source&targetMapName=target")
     .log("< Received: [${body}]");*/
		
	}

}
