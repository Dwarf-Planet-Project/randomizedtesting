package com.carrotsearch.randomizedtesting.timeouts;

import java.util.ArrayList;
import java.util.HashMap;

import junit.framework.Assert;

import org.fest.assertions.api.Assertions;
import org.junit.Test;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;

import com.carrotsearch.randomizedtesting.RandomizedTest;
import com.carrotsearch.randomizedtesting.Utils;
import com.carrotsearch.randomizedtesting.WithNestedTestClass;
import com.carrotsearch.randomizedtesting.annotations.*;

import static org.fest.assertions.data.MapEntry.*;

/**
 * Check {@link Seeds}.
 */
public class Test008SeedsAnnotation extends WithNestedTestClass {
  final static ArrayList<String> seeds = new ArrayList<String>();

  public static class Nested extends RandomizedTest {
    @Seeds({
      @Seed("deadbeef"),
      @Seed("cafebabe"),
      @Seed // Adds a randomized execution too.
    })
    @Test
    @Repeat(iterations = 2, useConstantSeed = true)
    public void testMe() {
      assumeRunningNested();
      seeds.add(Long.toHexString(Utils.getSeed(getContext().getRandomness())));
    }
  }

  @Test
  public void checkSeeds() {
    HashMap<String, Long> counts = new HashMap<String, Long>();
    int N = 4;
    for (int i = 0; i < N; i++) {
      seeds.clear();
      Result result = JUnitCore.runClasses(Nested.class);
      Assert.assertEquals(3 * 2, result.getRunCount());
      Assert.assertEquals(0, result.getFailureCount());
      for (String s : seeds) {
        if (!counts.containsKey(s))
          counts.put(s, 1L);
        else
          counts.put(s, counts.get(s) + 1);
      }
    }

    Assertions.assertThat(counts).contains(entry("deadbeef", N * 2L));
    Assertions.assertThat(counts).contains(entry("cafebabe", N * 2L));
    counts.remove("deadbeef");
    counts.remove("cafebabe");

    // Allow for a single collision.
    Assert.assertTrue(counts.size() >= N - 1);
  }
}
