package com.carrotsearch.randomizedtesting;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.runner.Description;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunNotifier;

/**
 * A {@link ThreadGroup} under which all tests (and hooks) are executed. Theoretically, there
 * should be no thread ouside of this group's control (but life will verify this).
 */
final class RunnerThreadGroup extends ThreadGroup {
  /**
   * @see #markAsBeingTerminated(Thread)
   */
  private final static Throwable terminationMarker = new Throwable();
  
  /**
   * We'll keep track of uncaught exceptions within the runner's thread group.
   */
  private final List<Pair<Thread, Throwable>> uncaughtExceptions = 
      new ArrayList<Pair<Thread, Throwable>>();

  /* */
  RunnerThreadGroup(String name) {
    super(name);
  }

  /**
   * Capture all uncaught exceptions from this group's threads. 
   */
  @Override
  public void uncaughtException(Thread t, Throwable e) {
    synchronized (uncaughtExceptions) {
      uncaughtExceptions.add(Pair.newInstance(t, e));
    }
  }

  /**
   * Process all exceptions logged so far and notify {@link RunNotifier}
   * about them.
   * 
   * @param description The description to which the failures will be logically
   *  attached.
   */
  void processUncaught(RunNotifier notifier, Description description) {
    Set<Thread> terminated = new HashSet<Thread>();
    synchronized (uncaughtExceptions) {
      for (Pair<Thread, Throwable> p : uncaughtExceptions) {
        if (p.b == terminationMarker) {
          terminated.add(p.a);
          continue;
        }

        // Ignore ThreadDeath and InterruptedException after termination marker.
        boolean afterTermination = terminated.contains(p.a);
        if (afterTermination && 
            (p.b instanceof ThreadDeath ||
             p.b instanceof InterruptedException)) {
          continue;
        }

        String message = 
            "Thread threw an uncaught exception" + 
            (afterTermination ? " (after termination attempt)" : "") +
            ", thread: " + p.a;

        notifier.fireTestFailure(new Failure(description, 
            new RuntimeException(message, p.b)));
      }
    }
  }

  /**
   * If a thread is being terminated, we add a sentinel value so that we know when exactly
   * this happened. This is later used to append a message that a potential exception
   * occurred after a forced interrupt or stop call.  
   */
  public void markAsBeingTerminated(Thread t) {
    synchronized (uncaughtExceptions) {
      uncaughtExceptions.add(Pair.newInstance(t, terminationMarker));
    }
  }  
}
