package org.example;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.jetbrains.annotations.NotNull;

import java.util.Properties;
import java.util.concurrent.TimeUnit;

public class WikimediaChangesProducerLocal {
    public static void main(String[] args) throws InterruptedException {
        KafkaProducer<String, String> producer = getStringStringKafkaProducer();

        String topic = "wikimedia.recentchange";
        var url = "https://stream.wikimedia.org/v2/stream/recentchange";

        var client = new OkHttpClient();
        var request = new Request.Builder()
                .url(url).build();

        EventSourceListener listener =
                new WikimediaChangeHandler(producer, topic);

        EventSource.Factory factory = EventSources.createFactory(client);

        EventSource eventSource = factory.newEventSource(request, listener);

        // we produce for 10 minutes and block the program until then
        TimeUnit.MINUTES.sleep(10);

        eventSource.cancel();
    }

    @NotNull
    private static KafkaProducer<String, String> getStringStringKafkaProducer() {
        String bootstrapServers = "127.0.0.1:9092";
        // create Producer Properties
        Properties properties = new Properties();
        properties.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.setProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        // set high throughput producer configs
        properties.setProperty(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
        properties.setProperty(ProducerConfig.LINGER_MS_CONFIG, "20");
        properties.setProperty(ProducerConfig.BATCH_SIZE_CONFIG, Integer.toString(32 * 1024)); // 32 KB batch size


        // create the Producer
        KafkaProducer<String, String> producer = new KafkaProducer<>(properties);
        return producer;
    }
}
