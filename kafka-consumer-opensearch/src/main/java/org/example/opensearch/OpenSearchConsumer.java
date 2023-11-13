package org.example.opensearch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.DefaultConnectionKeepAliveStrategy;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.opensearch.action.bulk.BulkRequest;
import org.opensearch.action.bulk.BulkResponse;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestHighLevelClient;
import org.opensearch.client.indices.CreateIndexRequest;
import org.opensearch.client.indices.GetIndexRequest;
import org.opensearch.common.xcontent.XContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.Properties;

public class OpenSearchConsumer {
    static ObjectMapper objectMapper = new ObjectMapper();

    public static RestHighLevelClient createOpenSearchClient() {
        String connString = "http://localhost:9200";

        // we build a URI from the connection string
        RestHighLevelClient restHighLevelClient;
        URI connURI = URI.create(connString);
        // extract login information if it exists
        String userInfo = connURI.getUserInfo();

        if (userInfo == null) {
            // REST client without security
            restHighLevelClient = new RestHighLevelClient(RestClient.builder(
                    new HttpHost(connURI.getHost(), connURI.getPort(), connURI.getScheme())));
        } else {
            String[] auth = userInfo.split(":");

            CredentialsProvider cp = new BasicCredentialsProvider();
            cp.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(auth[0], auth[1]));

            restHighLevelClient = new RestHighLevelClient(RestClient.builder(
                            new HttpHost(connURI.getHost(), connURI.getPort(), connURI.getScheme()))
                    .setHttpClientConfigCallback(hcb -> hcb.setDefaultCredentialsProvider(cp).setKeepAliveStrategy(
                            new DefaultConnectionKeepAliveStrategy())));

        }
        return restHighLevelClient;
    }

    public static void main(String[] args) throws IOException {

        Logger log = LoggerFactory.getLogger(OpenSearchConsumer.class.getSimpleName());
        //first create an OpenSearch Client
        RestHighLevelClient openSearchClient = createOpenSearchClient();

        // create our Kafka Client
        KafkaConsumer<String, String> consumer = createKafkaConsumer();

        // get a reference to the main thread
        Thread mainThread = Thread.currentThread();

        // add a shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Detected a shutdown hook");
            consumer.wakeup();

            // join the main thread to allow the execution of the code in the main thread
            try {
                mainThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }));
        // we need to create the index on OpenSearch if it does not exist already
        try (openSearchClient; consumer) {

            boolean indexExists = openSearchClient.indices().exists(new GetIndexRequest("wikimedia"), RequestOptions.DEFAULT);
            if (!indexExists) {
                CreateIndexRequest createIndexRequest = new CreateIndexRequest("wikimidie");
                openSearchClient.indices().create(createIndexRequest, RequestOptions.DEFAULT);
                log.info("The Wikimedia index was created");
            } else {
                log.info("The Wikimedia index already exists");
            }

            // subscribe to the topic
            consumer.subscribe(Collections.singleton("wikimedia.recentchange"));

            // main code logic
            while (true) {
                // poll for new data
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(3000));

                int recordCount = records.count();
                log.info("Received " + recordCount + " records");

                BulkRequest bulkRequest = new BulkRequest();

                // insert data into OpenSearch
                for (ConsumerRecord<String, String> record : records) {
                    // send the record into OpenSearch

                    // strategy 1
                    // define an ID using Kafka Record coordinates
                    //String id = record.topic() + "_" + record.partition() + "_" + record.offset();
                    try {
                        String id = extractId(record.value());
                        IndexRequest indexRequest = new IndexRequest("wikimedia")
                                .source(record.value(), XContentType.JSON)
                                .id(id);
                        //IndexResponse response = openSearchClient.index(indexRequest, RequestOptions.DEFAULT);
                        bulkRequest.add(indexRequest);
                        //   log.info(response.getId());
                    } catch (Exception e) {
                        log.warn("Skipping bad data: " + record.value());
                    }

                    if (bulkRequest.numberOfActions() > 0) {
                        BulkResponse bulkResponse = openSearchClient.bulk(bulkRequest, RequestOptions.DEFAULT);
                        log.info("Inserted " + bulkResponse.getItems().length + " records into OpenSearch");

                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        // commit offsets after the batch is consumed
                        consumer.commitSync();
                        log.info("Offsets have been committed");
                    }


                    // commit offsets after the batch is consumed
                    consumer.commitSync();
                    log.info("Offsets have been committed");

                }
            }

        } catch (WakeupException e) {
            log.info("Consumer is starting to shut down");
        } catch (Exception e) {
            log.error("Unexpected exception in the consumer", e);
        } finally {
            consumer.close();
            log.info("The consumer is now gracefully shutt down");
        }

        // create our Kafka Client

        // main code logic

        // close the client gracefully
        //openSearchClient.close();
    }

    private static String extractId(String value) throws JsonProcessingException {
        JsonNode rootNode = objectMapper.readTree(value);
        return rootNode.path("meta").path("id").asText();
    }

    private static KafkaConsumer<String, String> createKafkaConsumer() {
        String bootstrapServers = "localhost:9092";
        String groupId = "consumer-opensearch-demo";

        // create Consumer Properties
        Properties properties = new Properties();
        properties.setProperty("bootstrap.servers", bootstrapServers);
        properties.setProperty("group.id", groupId);
        properties.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        properties.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false"); // disable auto commit of offsets

        // create the Consumer
        return new KafkaConsumer<>(properties);
    }
}
