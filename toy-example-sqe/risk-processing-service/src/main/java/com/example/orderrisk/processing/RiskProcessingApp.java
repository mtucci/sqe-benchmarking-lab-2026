package com.example.orderrisk.processing;

import com.example.orderrisk.common.ServiceApp;
import com.example.orderrisk.common.ServiceConfig;
import com.example.orderrisk.common.ServiceHttpClient;
import com.example.orderrisk.common.Telemetry;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Processing service: consumes risk-analysis messages from RabbitMQ and computes the
 * CPU-intensive risk score on a bounded worker pool. Its HTTP server also exposes a read-only synchronous risk-evaluation endpoint.
 */
public final class RiskProcessingApp {

    private static final Logger LOG = LoggerFactory.getLogger(RiskProcessingApp.class);

    private RiskProcessingApp() {
    }

    public static void main(String[] args) throws Exception {
        ServiceConfig config = ServiceConfig.load();
        Telemetry.init("risk-processing-service", config);

        Duration timeout = Duration.ofMillis(config.getInt("http.client.timeoutMs"));
        RiskAnalyzer analyzer = new RiskAnalyzer(
                new ServiceHttpClient("order-service", config.get("order.baseUrl"), timeout),
                new ServiceHttpClient("pricing-profile-service", config.get("pricing.baseUrl"), timeout),
                config.getInt("processing.ruleCount"));

        Connection connection = connect(config);
        Channel channel = connection.createChannel();
        String exchange = config.get("rabbitmq.exchange");
        String queue = config.get("rabbitmq.queue");
        // Messaging topology (also declared by workflow-service; declarations are idempotent):
        // one direct exchange -> one durable work queue
        channel.exchangeDeclare(exchange, BuiltinExchangeType.DIRECT, true);
        channel.queueDeclare(queue, true, false, false, null);
        channel.queueBind(queue, exchange, config.get("rabbitmq.routingKey"));
        channel.basicQos(config.getInt("processing.prefetchCount"));

        RiskMessageConsumer consumer = new RiskMessageConsumer(channel, analyzer, queue,
                config.getInt("processing.workerThreads"), config.getInt("processing.queueCapacity"));
        channel.basicConsume(queue, false, consumer); // manual acknowledgements

        Javalin app = ServiceApp.create("risk-processing-service", config, javalin -> { });
        RiskEvaluationApi.registerRoutes(app);
        app.start(config.getInt("server.port"));
        LOG.info("risk-processing-service started: workerThreads={}, queueCapacity={}, prefetchCount={}, ruleCount={}",
                config.get("processing.workerThreads"), config.get("processing.queueCapacity"),
                config.get("processing.prefetchCount"), config.get("processing.ruleCount"));
    }

    /** Connects to RabbitMQ, retrying for about a minute while the broker is starting. */
    static Connection connect(ServiceConfig config) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(config.get("rabbitmq.host"));
        factory.setPort(config.getInt("rabbitmq.port"));
        factory.setUsername(config.get("rabbitmq.username"));
        factory.setPassword(config.get("rabbitmq.password"));
        for (int attempt = 1; ; attempt++) {
            try {
                return factory.newConnection("risk-processing-service");
            } catch (Exception e) {
                if (attempt >= 30) {
                    throw e;
                }
                LOG.info("RabbitMQ not reachable yet (attempt {}): {}", attempt, e.getMessage());
                Thread.sleep(2000);
            }
        }
    }
}
