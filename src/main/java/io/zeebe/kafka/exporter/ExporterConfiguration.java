package io.zeebe.kafka.exporter;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class ExporterConfiguration {

  private static final String ENV_PREFIX = "ZEEBE_KAFKA_";

  private String format = "protobuf";

  private String enabledValueTypes = "";
  private String enabledRecordTypes = "";
  private String enabledIntents = "";

  private String name = "zeebe";

  private String bootstrapServers;

  // Kafka Producer Configuration
  private String acks = "all";
  private int retries = 3;
  private int kafkaBatchSize = 16384;
  private int lingerMs = 5;
  private long bufferMemory = 33554432L; // 32MB
  private String compressionType = "snappy";

  // Additional Kafka properties
  private Map<String, String> additionalProperties = new HashMap<>();

  // Exporter specific configuration
  private int batchCycleMillis = 500;

  public String getFormat() {
    return getEnv("FORMAT").orElse(format);
  }

  public String getEnabledRecordTypes() {
    return getEnv("ENABLED_RECORD_TYPES").orElse(enabledRecordTypes);
  }

  public String getEnabledValueTypes() {
    return getEnv("ENABLED_VALUE_TYPES").orElse(enabledValueTypes);
  }

  public String getEnabledIntents() {
    return getEnv("ENABLED_INTENTS").orElse(enabledIntents);
  }

  public String getName() {
    return getEnv("NAME").orElse(name);
  }

  public String getTopicPrefix() {
    return getName() + "-";
  }

  public Optional<String> getBootstrapServers() {
    return getEnv("BOOTSTRAP_SERVERS")
        .or(() -> Optional.ofNullable(bootstrapServers))
        .filter(servers -> !servers.isEmpty());
  }

  public String getAcks() {
    return getEnv("ACKS").orElse(acks);
  }

  public int getRetries() {
    return getEnv("RETRIES").map(Integer::parseInt).orElse(retries);
  }

  public int getKafkaBatchSize() {
    return getEnv("KAFKA_BATCH_SIZE").map(Integer::parseInt).orElse(kafkaBatchSize);
  }

  public int getLingerMs() {
    return getEnv("LINGER_MS").map(Integer::parseInt).orElse(lingerMs);
  }

  public long getBufferMemory() {
    return getEnv("BUFFER_MEMORY").map(Long::parseLong).orElse(bufferMemory);
  }

  public String getCompressionType() {
    return getEnv("COMPRESSION_TYPE").orElse(compressionType);
  }

  public Map<String, String> getAdditionalProperties() {
    // Parse additional properties from environment
    String additionalProps = getEnv("ADDITIONAL_PROPERTIES").orElse("");
    if (!additionalProps.isEmpty()) {
      Map<String, List<String>> parsedMap = parseAsMap(additionalProps);
      Map<String, String> result = new HashMap<>();
      parsedMap.forEach(
          (key, values) -> {
            if (!values.isEmpty()) {
              result.put(key, values.get(0)); // Take first value for simple key-value pairs
            }
          });
      return result;
    }
    return additionalProperties;
  }

  public int getBatchCycleMillis() {
    return getEnv("BATCH_CYCLE_MILLIS").map(Integer::parseInt).orElse(batchCycleMillis);
  }

  private Optional<String> getEnv(String name) {
    return Optional.ofNullable(System.getenv(ENV_PREFIX + name));
  }

  @Override
  public String toString() {
    return "["
        + "bootstrapServers='"
        + getBootstrapServers()
        + '\''
        + ", enabledRecordTypes='"
        + getEnabledRecordTypes()
        + '\''
        + ", enabledValueTypes='"
        + getEnabledValueTypes()
        + '\''
        + ", enabledIntents='"
        + getEnabledIntents()
        + '\''
        + ", format='"
        + getFormat()
        + '\''
        + ", name='"
        + getName()
        + '\''
        + ", acks='"
        + getAcks()
        + '\''
        + ", retries="
        + getRetries()
        + ", kafkaBatchSize="
        + getKafkaBatchSize()
        + ", lingerMs="
        + getLingerMs()
        + ", bufferMemory="
        + getBufferMemory()
        + ", compressionType='"
        + getCompressionType()
        + '\''
        + ", batchCycleMillis="
        + getBatchCycleMillis()
        + ']';
  }

  /**
   * Parse comma-separated configuration string into a list. Example:
   *
   * <pre>
   * <code>
   * "value1,value2,value3"
   * </code>
   * </pre>
   *
   * becomes
   *
   * <pre>
   * <code>
   * ["value1", "value2", "value3"]
   * </code>
   * </pre>
   *
   * @param listAsString the comma-separated string to parse
   * @return List of trimmed non-empty strings
   */
  public static List<String> parseAsList(String listAsString) {
    return Arrays.stream(listAsString.split(","))
        .map(String::trim)
        .filter(item -> !item.isEmpty())
        .collect(Collectors.toList());
  }

  /**
   * Parse configuration string into a map where keys are configuration keys and values are lists of
   * configuration values for each key. Example:
   *
   * <pre>
   * <code>
   * "Key1=Value1,Value2;Key2=Value3,Value4"
   * </code>
   * </pre>
   *
   * becomes
   *
   * <pre>
   * <code>
   * {"Key1": ["Value1", "Value2"], "Key2": ["Value3", "Value4"]}
   * </code>
   * </pre>
   *
   * @param mapAsString the configuration string to parse, can be null or empty
   * @return Map where keys are configuration keys and values are lists of configuration values for
   *     each key
   */
  public static Map<String, List<String>> parseAsMap(String mapAsString) {
    Map<String, List<String>> map = new HashMap<>();

    if (mapAsString == null || mapAsString.trim().isEmpty()) {
      return map;
    }

    Arrays.stream(mapAsString.split(";"))
        .map(String::trim)
        .filter(entry -> !entry.isEmpty())
        .forEach(
            entry -> {
              String[] parts = entry.split("=", 2);
              if (parts.length == 2) {
                String key = parts[0].trim();
                String values = parts[1].trim();
                List<String> valueList = parseAsList(values);
                map.put(key, valueList);
              }
            });

    return map;
  }
}
