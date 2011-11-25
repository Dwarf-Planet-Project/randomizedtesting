package com.carrotsearch.ant.tasks.junit4.events.aggregated;

import java.util.List;

import org.junit.runner.Description;

import com.carrotsearch.ant.tasks.junit4.SlaveInfo;
import com.carrotsearch.ant.tasks.junit4.events.IEvent;
import com.carrotsearch.ant.tasks.junit4.events.mirrors.FailureMirror;

/**
 * Aggregated result from a suite or test.
 */
public interface AggregatedResultEvent {
  public Description getDescription();
  public SlaveInfo getSlave();
  public boolean isSuccessful();
  public List<FailureMirror> getFailures();
  List<IEvent> getEventStream();  
}
