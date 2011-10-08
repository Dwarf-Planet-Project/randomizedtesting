package com.carrotsearch.randomizedtesting.examples;

import junit.framework.Assert;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import com.carrotsearch.randomizedtesting.RandomizedTest;
import com.carrotsearch.randomizedtesting.StandardErrorInfoRunListener;
import com.carrotsearch.randomizedtesting.annotations.Listeners;
import com.carrotsearch.randomizedtesting.annotations.Repeat;

/*
 * Just a showcase of various things RandomizedRunner can do.
 */

// @Seed("deadbeef")
// @Repeat(100)
@Listeners({StandardErrorInfoRunListener.class})
public class TestEyeBalling extends RandomizedTest {
  @BeforeClass
  public static void setup() {
    info("before class");
  }

  @Before
  public void testSetup() {
    info("before test");
  }

  @Test
  public void alwaysFailing() {
    info("always failing");
    Assert.assertTrue(false);
  }

  @Repeat(iterations = 4)
  @Test
  public void halfFailing() {
    info("50% failing");
    Assert.assertTrue(randomBoolean());
  }

  @Repeat(iterations = 4)
  @Test
  public void halfAssumptionIgnored() {
    info("50% assumption ignored");
    Assume.assumeTrue(randomBoolean());
  }

  @Ignore
  @Test
  public void ignored() {
    info("ignored");
  }

  @After
  public void testCleanup() {
    info("after test");
    System.out.println();
  }

  @AfterClass
  public static void cleanup() {
    info("after class");
    throw new RuntimeException();
  }

  private static void info(String msg) {
    System.out.println(msg + ", context: " + getContext().getRandomness());
  }
}

