package org.example;

import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WikimediaChangeHandler extends EventSourceListener {
    private final Logger log = LoggerFactory.getLogger(WikimediaChangeHandler.class.getSimpleName());
    KafkaProducer<String, String> kafkaProducer;
    String topic;

    public WikimediaChangeHandler(KafkaProducer<String, String> kafkaProducer, String topic) {
        this.kafkaProducer = kafkaProducer;
        this.topic = topic;
    }

    @Override
    public void onClosed(@NotNull EventSource eventSource) {
        kafkaProducer.close();
    }

    @Override
    public void onOpen(@NotNull EventSource eventSource, @NotNull Response response) {
        //
        log.info("Connection opened");
    }

    @Override
    public void onEvent(@NotNull EventSource eventSource, @Nullable String id, @Nullable String type, @NotNull String data) {
        log.info("Received new data" +  data);
        kafkaProducer.send(new ProducerRecord<>(topic, data));
    }

    @Override
    public void onFailure(@NotNull EventSource eventSource, @Nullable Throwable t, @Nullable Response response) {
        log.error("Error in Stream Reading", t);
    }
}