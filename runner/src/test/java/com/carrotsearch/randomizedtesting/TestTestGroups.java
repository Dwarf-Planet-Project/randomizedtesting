package com.carrotsearch.randomizedtesting;

import java.lang.annotation.*;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;

import com.carrotsearch.randomizedtesting.annotations.Nightly;
import com.carrotsearch.randomizedtesting.annotations.TestGroup;

/**
 * Custom test groups.
 */
public class TestTestGroups extends WithNestedTestClass {
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.METHOD, ElementType.TYPE})
  @Inherited
  @TestGroup(enabled = true)
  public static @interface Group1 {
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.METHOD, ElementType.TYPE})
  @Inherited
  @TestGroup(enabled = false, name = "abc", sysProperty = "custom.abc")
  public static @interface Group2 {
  }

  public static class Nested1 extends RandomizedTest {
    @Test @Group1 @Group2
    public void test1() {
    }
  }
  
  @Test
  public void checkDefaultNames() {
    Assert.assertEquals("group1", RuntimeTestGroup.getGroupName(Group1.class));
    Assert.assertEquals("abc", RuntimeTestGroup.getGroupName(Group2.class));
    Assert.assertEquals("tests.group1", RuntimeTestGroup.getGroupSysProperty(Group1.class));
    Assert.assertEquals("custom.abc", RuntimeTestGroup.getGroupSysProperty(Group2.class));
    Assert.assertEquals("nightly", RuntimeTestGroup.getGroupName(Nightly.class));
    Assert.assertEquals("tests.nightly", RuntimeTestGroup.getGroupSysProperty(Nightly.class));
  }  

  @Test
  public void invalidValueNightly() {
    String group1Property = RuntimeTestGroup.getGroupSysProperty(Group1.class);
    String group2Property = RuntimeTestGroup.getGroupSysProperty(Group2.class);
    try {
      checkResult(JUnitCore.runClasses(Nested1.class), 1, 1, 0);
      
      System.setProperty(group1Property, "true");
      checkResult(JUnitCore.runClasses(Nested1.class), 1, 1, 0);

      System.setProperty(group2Property, "true");
      checkResult(JUnitCore.runClasses(Nested1.class), 1, 0, 0);

      System.setProperty(group1Property, "false");      
      checkResult(JUnitCore.runClasses(Nested1.class), 1, 1, 0);
    } finally {
      System.clearProperty(group1Property);
      System.clearProperty(group2Property);
    }
  }

  private void checkResult(Result result, int run, int ignored, int failures) {
    Assert.assertEquals(run, result.getRunCount());
    Assert.assertEquals(ignored, result.getIgnoreCount());
    Assert.assertEquals(failures, result.getFailureCount());
  }
}
