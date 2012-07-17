package com.carrotsearch.randomizedtesting.timeouts;

import junit.framework.Assert;

import org.junit.Test;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

import com.carrotsearch.randomizedtesting.RandomizedTest;
import com.carrotsearch.randomizedtesting.WithNestedTestClass;

/**
 * Test {@link Test#timeout()}.
 */
public class Test014Timeout extends WithNestedTestClass {
  public static class Nested extends RandomizedTest {
    @Test(timeout = 100)
    public void testMethod1() {
      assumeRunningNested();
      sleep(2000);
    }

    @Test(timeout = 100)
    public void testMethod2() {
      assumeRunningNested();
      while (!Thread.interrupted()) {
        // Do nothing.
      }
    }
  }

  @Test
  public void testTimeoutInTestAnnotation() {
    Result result = JUnitCore.runClasses(Nested.class);
    for (Failure f : result.getFailures()) {
      sysout.println("## " + f.getTrace());
    }
    Assert.assertEquals(0, result.getIgnoreCount());
    Assert.assertEquals(2, result.getRunCount());
    Assert.assertEquals(2, result.getFailureCount());
  }
}
