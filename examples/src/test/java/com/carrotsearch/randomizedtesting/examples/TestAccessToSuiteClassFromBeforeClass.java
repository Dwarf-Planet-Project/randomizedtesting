package com.carrotsearch.randomizedtesting.examples;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.carrotsearch.randomizedtesting.RandomizedContext;
import com.carrotsearch.randomizedtesting.RandomizedRunner;

/**
 * {@link RandomizedContext} provides access to the suite class, so you can
 * verify whatever assertions need to be verified at the fixture level.
 */
@RunWith(RandomizedRunner.class)
public class TestAccessToSuiteClassFromBeforeClass {
  @BeforeClass
  public static void beforeClass() {
    System.out.println("@BeforeClass on suite class: "
        + RandomizedContext.current().getTargetClass());
  }

  @Test
  public void dummy() {
    // Do nothing.
  }
}
