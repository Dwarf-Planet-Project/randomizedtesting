package com.carrotsearch.ant.tasks.junit4.tests;

import java.io.IOException;
import java.lang.reflect.Field;

import org.junit.Test;

public class TestJvmCrash {
  /**
   * Check jvm crash. Simulated via memory seeding with unsafe.
   */
  @SuppressWarnings("all")
  @Test
  public void testEnvVar() throws IOException {
    try {
      sun.misc.Unsafe unsafe = null;
      Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
      field.setAccessible(true);
      unsafe = (sun.misc.Unsafe) field.get(null);
      unsafe.putAddress(0, 1);
    } catch (Exception e) {
      throw new AssertionError("Couldn't get hold of unsafe.");
    }
  }
}
