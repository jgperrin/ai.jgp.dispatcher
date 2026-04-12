package ai.jgp.gha.dataproduct;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaPublisherTest {

    @Mock
    private KafkaProducer<String, byte[]> producer;

    @Test
    void publishZip_success() {
        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition(K.KAFKA_TOPIC_SPEC_INGEST, 0),
                0L, 0, 0L, 0, 0);

        Future<RecordMetadata> future = CompletableFuture.completedFuture(metadata);
        when(producer.send(any(ProducerRecord.class))).thenReturn(future);

        KafkaPublisher publisher = new KafkaPublisher(producer, true);
        byte[] zipData = {1, 2, 3, 4, 5};

        boolean result = publisher.publishZip(K.KAFKA_TOPIC_SPEC_INGEST, "test.zip", zipData);

        assertTrue(result);
        verify(producer).send(any(ProducerRecord.class));
    }

    @Test
    void publishZip_failsOnException() {
        CompletableFuture<RecordMetadata> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("Kafka send failed"));
        when(producer.send(any(ProducerRecord.class))).thenReturn(future);

        KafkaPublisher publisher = new KafkaPublisher(producer, true);
        byte[] zipData = {1, 2, 3};

        boolean result = publisher.publishZip(K.KAFKA_TOPIC_SPEC_INGEST, "test.zip", zipData);

        assertFalse(result);
    }

    @Test
    void publishZip_sendsCorrectRecord() {
        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition("test-topic", 0),
                0L, 0, 0L, 0, 0);

        Future<RecordMetadata> future = CompletableFuture.completedFuture(metadata);
        when(producer.send(any(ProducerRecord.class))).thenReturn(future);

        KafkaPublisher publisher = new KafkaPublisher(producer, true);
        byte[] zipData = {10, 20, 30};

        publisher.publishZip("my-topic", "my-key.zip", zipData);

        verify(producer).send(argThat(record ->
                "my-topic".equals(record.topic()) &&
                "my-key.zip".equals(record.key()) &&
                record.value().length == 3
        ));
    }

    @Test
    void isConnected_returnsTrue() {
        KafkaPublisher publisher = new KafkaPublisher(producer, true);
        assertTrue(publisher.isConnected());
    }

    @Test
    void isConnected_returnsFalse() {
        KafkaPublisher publisher = new KafkaPublisher(producer, false);
        assertFalse(publisher.isConnected());
    }

    @Test
    void close_closesProducer() {
        KafkaPublisher publisher = new KafkaPublisher(producer, true);
        publisher.close();
        verify(producer).close(any());
    }

    @Test
    void publishZip_handlesEmptyData() {
        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition(K.KAFKA_TOPIC_SPEC_INGEST, 0),
                0L, 0, 0L, 0, 0);

        Future<RecordMetadata> future = CompletableFuture.completedFuture(metadata);
        when(producer.send(any(ProducerRecord.class))).thenReturn(future);

        KafkaPublisher publisher = new KafkaPublisher(producer, true);

        boolean result = publisher.publishZip(K.KAFKA_TOPIC_SPEC_INGEST, "empty.zip", new byte[0]);
        assertTrue(result);
    }

    @Test
    void publishZip_handlesLargeData() {
        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition(K.KAFKA_TOPIC_SPEC_INGEST, 0),
                0L, 0, 0L, 0, 0);

        Future<RecordMetadata> future = CompletableFuture.completedFuture(metadata);
        when(producer.send(any(ProducerRecord.class))).thenReturn(future);

        KafkaPublisher publisher = new KafkaPublisher(producer, true);
        byte[] largeData = new byte[1024 * 1024]; // 1 MB

        boolean result = publisher.publishZip(K.KAFKA_TOPIC_SPEC_INGEST, "large.zip", largeData);
        assertTrue(result);
    }

    @Test
    void publishZip_failsOnExecutionException() {
        CompletableFuture<RecordMetadata> future = new CompletableFuture<>();
        future.completeExceptionally(new ExecutionException("Wrapped error",
                new RuntimeException("Underlying cause")));
        when(producer.send(any(ProducerRecord.class))).thenReturn(future);

        KafkaPublisher publisher = new KafkaPublisher(producer, true);

        boolean result = publisher.publishZip(K.KAFKA_TOPIC_SPEC_INGEST, "fail.zip", new byte[]{1});
        assertFalse(result);
    }
}
