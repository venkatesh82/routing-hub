package com.venkat.camel.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.atlasmap.api.AtlasContext;
import io.atlasmap.api.AtlasContextFactory;
import io.atlasmap.api.AtlasSession;
import io.atlasmap.core.DefaultAtlasContextFactory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.rest.RestBindingMode;
import org.apache.camel.util.ObjectHelper;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.util.ResourceUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import javax.persistence.*;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Base64;

@SpringBootApplication
@RestController
public class DemoApplication implements CommandLineRunner {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    private final ServiceBrokerRepository serviceBrokerRepository;
    private final ServiceBrokerEndpointRepository serviceBrokerEndpointRepository;
    private final ServiceEndpointRepository serviceEndpointRepository;
    private final ServiceEndpointTransformationRepository serviceEndpointTransformationRepository;

    @Autowired
    private RoutManager routManager;

    public DemoApplication(ServiceBrokerRepository serviceBrokerRepository, ServiceBrokerEndpointRepository serviceBrokerEndpointRepository, ServiceEndpointRepository serviceEndpointRepository, ServiceEndpointTransformationRepository serviceEndpointTransformationRepository) {
        this.serviceBrokerRepository = serviceBrokerRepository;
        this.serviceBrokerEndpointRepository = serviceBrokerEndpointRepository;
        this.serviceEndpointRepository = serviceEndpointRepository;
        this.serviceEndpointTransformationRepository = serviceEndpointTransformationRepository;
    }

    @Override
    public void run(String... args) throws Exception {
        // Service Broker Data
        ServiceBroker serviceBroker = ServiceBroker.builder().brokerName("OFSLL").brokerDesc("OFSLL").build();
        serviceBroker = serviceBrokerRepository.save(serviceBroker);

        //Service Broker Endpoint Data
        ServiceBrokerEndpoint serviceBrokerEndpoint = ServiceBrokerEndpoint.builder().brokerId(serviceBroker.getBrokerId())
                .brokerEndpointName("sayHello").brokerEndpointDescription("Saying Hello")
                .status(true).build();


        //Service Endpoint Data
        ServiceEndpoint serviceEndpoint = ServiceEndpoint.builder()
                .serviceEndpointName("say hello").serviceEndpointHost("localhost")
                .serviceEndpointPort("8081").serviceEndpointUrl("/api/sayhello").serviceEndpointHttpMethod(HttpMethod.POST.method).serviceEndpointType("http")
                .status(true).build();


        //ServiceEndpointTransformation Data

        byte[] data = FileUtils.readFileToByteArray(ResourceUtils.getFile("classpath:name.adm"));

        ServiceEndpointTransformation serviceEndpointTransformation = ServiceEndpointTransformation.builder()
                .isRequestTransformationEnabled(true)
                .requestTransformation(data)
                .requestTransformationTemplateType("atlas")
                .isResponseTransformationEnabled(false).build();
        // serviceEndpointTransformation = serviceEndpointTransformationRepository.save(serviceEndpointTransformation);
        serviceEndpoint.setServiceEndpointTransformation(serviceEndpointTransformation);
        serviceEndpointTransformation.setServiceEndpoint(serviceEndpoint);
        serviceBrokerEndpoint.setServiceEndpoint(serviceEndpoint);
        serviceEndpoint.setServiceBrokerEndpoint(serviceBrokerEndpoint);
        serviceBrokerEndpoint = serviceBrokerEndpointRepository.save(serviceBrokerEndpoint);
        routManager.addRoutes(serviceBrokerEndpoint);
    }

    @GetMapping("/add/{id}")
    public void addRoutes(@PathVariable("id") Integer id) throws Exception {
        ServiceBrokerEndpoint serviceBrokerEndpoint = this.serviceBrokerEndpointRepository.findById(id).get();
        ServiceEndpoint serviceEndpoint = serviceBrokerEndpoint.getServiceEndpoint();
        routManager.addRoutes(serviceBrokerEndpoint);
    }
}

interface RoutManager {
    void addRoutes(ServiceBrokerEndpoint serviceBrokerEndpoint) throws Exception;
}

@Component
class RoutManagerImpl implements RoutManager {
    private final CamelContext context;

    RoutManagerImpl(CamelContext context) {
        this.context = context;
    }

    @Override
    public synchronized void addRoutes(ServiceBrokerEndpoint serviceBrokerEndpoint) throws Exception {
        if (context.isStarted())
            context.start();
        context.addRoutes(new AppRouteBuilder(serviceBrokerEndpoint));
    }
}

@Component("admFileMapper")
class AdfFileMapper {
    private String base64AdmFile;

    public void setAdmFile(String file,Exchange exchange) {
        if (exchange.getProperty(file) != null) {
            base64AdmFile = exchange.getProperty(file, String.class);
        }
    }

    public ByteArrayResource adm() {
        if (base64AdmFile != null)
            return new ByteArrayResource(Base64.getDecoder().decode(base64AdmFile));
        return null;
    }
}

class AppRouteBuilder extends RouteBuilder {
    private final ServiceBrokerEndpoint serviceBrokerEndpoint;
    private final ServiceEndpoint serviceEndpoint;
    private final ServiceEndpointTransformation serviceEndpointTransformation;

    AppRouteBuilder(ServiceBrokerEndpoint serviceBrokerEndpoint) {
        this.serviceBrokerEndpoint = serviceBrokerEndpoint;
        this.serviceEndpoint = serviceBrokerEndpoint.getServiceEndpoint();
        this.serviceEndpointTransformation = serviceEndpoint.getServiceEndpointTransformation();
    }

    @Override
    public void configure() throws Exception {
        String routeId = serviceBrokerEndpoint.getBrokerEndpointName();
        restConfiguration()
                .component("servlet")
                .contextPath("/")
                //.dataFormatProperty("disableFeatures", "FAIL_ON_EMPTY_BEANS")
                .bindingMode(RestBindingMode.auto);

        //Broker Router
        rest("/api/" + routeId).id(routeId).post()
                .to("direct:" + routeId + "ReqProcessor");

        //Request Transformation and invocation
        from("direct:" + routeId + "ReqProcessor")
                .log("Before Request Transformation ${body}")
                .choice()
                .when(constant(serviceEndpointTransformation.isRequestTransformationEnabled() && serviceEndpointTransformation.getRequestTransformationTemplateType() != null && serviceEndpointTransformation.getRequestTransformationTemplateType().equalsIgnoreCase("atlas")))
                .marshal().json()
                .setProperty("admRequestFile", simple(Base64.getEncoder().encodeToString(serviceEndpointTransformation.getRequestTransformation())))
                .bean("admFileMapper", "setAdmFile('admRequestFile',*)")
                .to("atlasmap:bean:admFileMapper.adm")
                .removeProperty("admRequestFile")
                .when(constant(serviceEndpointTransformation.isRequestTransformationEnabled() && serviceEndpointTransformation.getRequestTransformationTemplateType() != null && serviceEndpointTransformation.getRequestTransformationTemplateType().equalsIgnoreCase("velocity")))
                .process(new RequestProcessor(serviceEndpointTransformation))
                .end()
                .unmarshal().json()
                .log("After Request Transformation ${body}")
                .setHeader(Exchange.HTTP_METHOD, simple(serviceEndpoint.getServiceEndpointHttpMethod()))
                .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
                .marshal().json()
                .to(prepareUrl(serviceEndpoint))
                .to("direct:" + routeId + "ResProcessor");

        //Response Transformation and dispatch
        from("direct:" + routeId + "ResProcessor")
                .choice()
                .when(constant(serviceEndpointTransformation.isRequestTransformationEnabled() && serviceEndpointTransformation.getResponseTransformationTemplateType() != null && serviceEndpointTransformation.getResponseTransformationTemplateType().equalsIgnoreCase("atlas")))
                .marshal().json()
                .setProperty("admResponseFile", simple(Base64.getEncoder().encodeToString(serviceEndpointTransformation.getRequestTransformation())))
                .bean("admFileMapper", "setAdmFile('admResponseFile',*)")
                .to("atlasmap:bean:admFileMapper.adm")
                .removeProperty("admResponseFile")
                .when(constant(serviceEndpointTransformation.isRequestTransformationEnabled() && serviceEndpointTransformation.getResponseTransformationTemplateType() != null && serviceEndpointTransformation.getResponseTransformationTemplateType().equalsIgnoreCase("velocity")))
                .process(new RequestProcessor(serviceEndpointTransformation))
                .end()
                .unmarshal().json()
                .transform().body()
                .endRest();
    }

    private String prepareUrl(ServiceEndpoint serviceEndpoint) {
        StringBuilder urlBuilder = new StringBuilder();
        urlBuilder.append(serviceEndpoint.getServiceEndpointType());
        urlBuilder.append("://").append(serviceEndpoint.getServiceEndpointHost() + ":").append(serviceEndpoint.getServiceEndpointPort());
        urlBuilder.append(serviceEndpoint.getServiceEndpointUrl());
        urlBuilder.append("?bridgeEndpoint=true");
        System.out.println(urlBuilder.toString());
        return urlBuilder.toString();
    }

    public ByteArrayResource admFile() {
        return new ByteArrayResource(serviceEndpointTransformation.getRequestTransformation());
    }
}

class RequestProcessor implements Processor {

    private final ServiceEndpointTransformation serviceEndpointTransformation;

    RequestProcessor(ServiceEndpointTransformation serviceEndpointTransformation) {
        this.serviceEndpointTransformation = serviceEndpointTransformation;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        System.out.println("Request Processor Invoked");
        AtlasContextFactory factory = DefaultAtlasContextFactory.getInstance();
        InputStream is = new ByteArrayInputStream(serviceEndpointTransformation.getRequestTransformation());
        AtlasContext context = factory.createContext(AtlasContextFactory.Format.ADM, is);
        AtlasSession session = context.createSession();
        Message in = exchange.getIn();
        String body = in.getBody(String.class);
        JsonNode node = new ObjectMapper().readTree(body);
        session.setSourceDocument("source", body);
        context.process(session);
        Object obj = session.getTargetDocument("target-2db007fa-4775-4993-b43f-e25619d8480b");
        exchange.getIn().setBody(obj);
    }

}

class ResponseProcessor implements Processor {

    @Override
    public void process(Exchange exchange) throws Exception {
        Message in = exchange.getIn();
        String body = in.getBody(String.class);
        JsonNode node = new ObjectMapper().readTree(body);
        System.out.println(node.toPrettyString());
        if (ObjectHelper.isNotEmpty(body)) {
            in.setBody(body);
        } else {
            in.setBody("{}");
        }
    }
}

enum HttpMethod {
    POST("POST"), GET("GET"), PATCH("PATCH"), PUT("PUT"), DELETE("DELETE");
    public final String method;

    private HttpMethod(String method) {
        this.method = method;
    }
}


@Repository
interface ServiceBrokerRepository extends JpaRepository<ServiceBroker, Integer> {
}

@Repository
interface ServiceBrokerEndpointRepository extends JpaRepository<ServiceBrokerEndpoint, Integer> {
}

@Repository
interface ServiceEndpointRepository extends JpaRepository<ServiceEndpoint, Integer> {
}

@Repository
interface ServiceEndpointTransformationRepository extends JpaRepository<ServiceEndpointTransformation, Integer> {

}


// One broker can route to multiple endpoints
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
class ServiceBroker {
    @Id
    @GeneratedValue
    private Integer brokerId;
    private String brokerName;
    private String brokerDesc;
}

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
class ServiceBrokerEndpoint {
    private Integer brokerId;
    @Id
    @GeneratedValue
    private Integer brokerEndpointId;
    private String brokerEndpointName;
    private String brokerEndpointDescription;
    private boolean status;

    @OneToOne(fetch = FetchType.LAZY,
            cascade = CascadeType.ALL,
            mappedBy = "serviceBrokerEndpoint")
    private ServiceEndpoint serviceEndpoint;
}

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
class ServiceEndpoint {
    @Id
    @GeneratedValue
    private Integer serviceEndpointId;
    private String serviceEndpointName;
    private String serviceEndpointHost;
    private String serviceEndpointPort;
    private String serviceEndpointUrl;
    private String serviceEndpointHttpMethod;
    private String serviceEndpointType;
    //Map should be stored in clob
    private String serviceEndpointHeaders;
    //Map should be stored in clob
    private String serviceEndpointQueryParameters;
    //Map should be stored in clob
    private String serviceEndpointPathParameters;
    private String requestDefinition;
    private String responseDefinition;
    private boolean status;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "BROKER_ENDPOINT_ID", nullable = false)
    private ServiceBrokerEndpoint serviceBrokerEndpoint;

    @OneToOne(fetch = FetchType.LAZY,
            cascade = CascadeType.ALL,
            mappedBy = "serviceEndpoint")
    private ServiceEndpointTransformation serviceEndpointTransformation;

}

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
class ServiceEndpointTransformation {
    @Id
    @GeneratedValue
    private Integer serviceEndpointTransId;
    private String requestTransformationTemplateType;
    private boolean isRequestTransformationEnabled;
    @Lob
    private byte[] requestTransformation;
    private boolean isResponseTransformationEnabled;
    private String responseTransformationTemplateType;
    @Lob
    private byte[] responseTransformation;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "SERVICE_ENDPOINT_ID", nullable = false)
    private ServiceEndpoint serviceEndpoint;

}
