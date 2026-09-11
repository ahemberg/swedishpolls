package se.swedishpolls.source.service;

import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;
import org.springframework.web.service.registry.ImportHttpServices;

/** Registers the poll source HTTP client with its bounded response conversion. */
@Configuration
@ImportHttpServices(group = "poll-source", types = SnapshotIngest.PollSourceClient.class)
class SourceHttpConfig {
  private static final int MAX_SOURCE_BYTES = 16 * 1024 * 1024;

  @Bean
  RestClientHttpServiceGroupConfigurer pollSourceClientConfigurer() {
    return groups ->
        groups
            .filterByName("poll-source")
            .forEachClient(
                (group, client) -> {
                  client.defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {});
                  client.configureMessageConverters(
                      converters -> converters.addCustomConverter(new LimitedByteArrayConverter()));
                });
  }

  private static final class LimitedByteArrayConverter extends ByteArrayHttpMessageConverter {
    @Override
    public byte[] readInternal(Class<? extends byte[]> type, HttpInputMessage input)
        throws IOException {
      final byte[] bytes = input.getBody().readNBytes(MAX_SOURCE_BYTES + 1);
      if (bytes.length > MAX_SOURCE_BYTES) throw new IOException("Source exceeds 16 MiB");
      return bytes;
    }
  }
}
