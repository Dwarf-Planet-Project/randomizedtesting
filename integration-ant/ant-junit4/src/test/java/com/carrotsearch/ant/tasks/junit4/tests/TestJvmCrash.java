package com.carrotsearch.ant.tasks.junit4.tests;

import java.io.IOException;

import org.junit.*;

public class TestJvmCrash {
  /**
   * Check jvm crash.
   */
  @Test
  public void testEnvVar() throws IOException {
    // Try going into native mode first and cause a sigsegv. 
    // This will work for any jvm (?).
    Crash.loadLibrary();
    Crash.crashMe();
    Assert.fail();
  }
}
