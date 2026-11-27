package com.banking.cardservice.infrastructure.config;

import java.util.LinkedHashSet;
import java.util.Set;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka topics and what happens to a message that cannot be handled.
 *
 * <p>Every topic this service reads or writes is declared here and in the services on the other
 * side, with the same shape; whichever starts first creates them. Three partitions let several
 * instances share the work, and the key of every message (account, card or request) keeps its
 * messages in one partition, so in order.
 */
@Configuration(proxyBeanMethods = false)
public class MessagingConfiguration {

  static final int PARTITIONS = 3;
  static final long RETRIES = 3;

  /** The topics of the settings: commands, answers, and where this service asks and answers. */
  @Bean
  KafkaAdmin.NewTopics cardTopics(CardProperties properties) {
    CardProperties.Messaging messaging = properties.messaging();
    Set<String> names = new LinkedHashSet<>();
    names.add(messaging.commandsTopic());
    names.add(messaging.repliesTopic());
    names.add(messaging.accountCommandsTopic());
    names.add(messaging.defaultReplyTopic());
    names.addAll(messaging.replyTopics());
    return new KafkaAdmin.NewTopics(
        names.stream().map(MessagingConfiguration::topic).toArray(NewTopic[]::new));
  }

  /**
   * A message that fails to be handled (the database is down, for example) is tried three more
   * times and then skipped with an error in the log. Skipping loses nothing: whoever waits for the
   * answer asks again.
   */
  @Bean
  CommonErrorHandler messageErrorHandler(CardProperties properties) {
    return new DefaultErrorHandler(
        new FixedBackOff(properties.messaging().retryInterval().toMillis(), RETRIES));
  }

  private static NewTopic topic(String name) {
    return TopicBuilder.name(name).partitions(PARTITIONS).replicas(1).build();
  }
}
