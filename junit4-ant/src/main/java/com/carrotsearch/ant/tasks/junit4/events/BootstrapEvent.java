package com.carrotsearch.ant.tasks.junit4.events;

import java.lang.management.ManagementFactory;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.TreeMap;

/**
 * Initial message sent from the slave to the master (if forked locally).
 */
public class BootstrapEvent extends AbstractEvent {
  private String defaultCharset;
  private Map<String, String> systemProperties;
  private String pidString;

  /** Preinitialization with local machine's configuration. */
  public BootstrapEvent() {
    super(EventType.BOOTSTRAP);

    this.defaultCharset = Charset.defaultCharset().name();

    try {
      pidString = ManagementFactory.getRuntimeMXBean().getName();
    } catch (Throwable t) {
      pidString = "<pid acquire exception: " + t.toString() + ">";
    }

    this.systemProperties = new TreeMap<String, String>();
    for (Map.Entry<Object, Object> e : System.getProperties().entrySet()) {
      Object key = e.getKey();
      Object value = e.getValue();
      if (key != null) {
        systemProperties.put(
            key.toString(), value != null ? value.toString() : "");
      }
    }

    systemProperties.put("junit4.memory.total", 
        Long.toString(Runtime.getRuntime().totalMemory()));
    systemProperties.put("junit4.processors", 
        Long.toString(Runtime.getRuntime().availableProcessors()));
    systemProperties.put("junit4.pidString", pidString);
  }

  /**
   * Default charset on the slave.
   */
  public String getDefaultCharsetName() {
    return defaultCharset;
  }

  /**
   * System properties on the slave.
   */
  public Map<String,String> getSystemProperties() {
    return systemProperties;
  }

  /**
   * Returns a PID string or anything that approximates it and would
   * help in dumping a stack trace externally, for example.
   */
  public String getPidString() {
    return pidString;
  }
}
