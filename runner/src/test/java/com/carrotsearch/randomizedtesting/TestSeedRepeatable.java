package com.carrotsearch.randomizedtesting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.RunWith;

public class TestSeedRepeatable {
  @Seed("deadbeef")
  @RunWith(RandomizedRunner.class)
  public static class Nested {
    static Map<String, Object> seeds = new HashMap<String, Object>();

    @Test
    public void testMethod1() {
      checkSeed("method1");
    }

    @Test
    public void testMethod2() {
      checkSeed("method2");
    }
    
    private void checkSeed(String key) {
      final Object seed = RandomizedContext.current().getRandomness().getSeed();
      if (seeds.containsKey(key)) {
        assertEquals(seeds.get(key), seed);
      } else {
        seeds.put(key, seed);
      }
    }    
  }
  
  /**
   * Check if methods get the same seed on every run with a fixed runner's seed.
   */
  @Test
  public void testSameMethodRandomnessWithFixedRunner() {
    Nested.seeds.clear();
    Result result = JUnitCore.runClasses(Nested.class);
    assertTrue(result.getFailures().toString(), result.wasSuccessful());
    result = JUnitCore.runClasses(Nested.class);
    assertTrue(result.wasSuccessful());
    result = JUnitCore.runClasses(Nested.class);    
    assertTrue(result.wasSuccessful());
  }  
}
