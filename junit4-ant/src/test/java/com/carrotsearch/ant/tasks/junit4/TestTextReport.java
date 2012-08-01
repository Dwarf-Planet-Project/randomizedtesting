package com.carrotsearch.ant.tasks.junit4;


import java.util.regex.Pattern;

import junit.framework.Assert;

import org.junit.Test;

import com.carrotsearch.ant.tasks.junit4.tests.FailInAfterClass;
import com.carrotsearch.ant.tasks.junit4.tests.ReasonForAssumptionIgnored;

/**
 * Test report-text listener.
 */
public class TestTextReport extends JUnit4XmlTestBase {
  @Test 
  public void suiteerror() {
    super.executeTarget("suiteerror");
    
    int count = countPattern(getLog(), FailInAfterClass.MESSAGE);
    Assert.assertEquals(1, count);
  }

  @Test 
  public void reasonForIgnored() {
    super.executeTarget("reasonForIgnored");
    assertLogContains("@DisabledGroup");
    assertLogContains("> Cause: Annotated @Ignore");
    assertLogContains("(Ignored method.)");
  }

	@Test 
	public void reasonForSuiteAssumptionIgnored() {
	  super.executeTarget("reasonForSuiteAssumptionIgnored");

	  int count = countPattern(getLog(), ReasonForAssumptionIgnored.MESSAGE);
    Assert.assertEquals(2, count);
	}

  @Test 
  public void listeners() {
    super.executeTarget("listeners");
    assertLogContains("testStarted: passing(com.carrotsearch.ant.tasks.junit4.tests.SuiteListeners)");
    assertLogContains("testFinished: passing(com.carrotsearch.ant.tasks.junit4.tests.SuiteListeners)");
  }

  @Test 
  public void timestamps() {
    super.executeTarget("timestamps");
    Assert.assertTrue(getLog(),
        Pattern.compile("\\[([0-9]{2}):([0-9]{2}):([0-9]{2})\\.([0-9]{3})\\]").matcher(getLog()).find());
  }
  
  @Test 
  public void sysoutsOnSuiteFailure() {
    super.executeTarget("sysoutsOnSuiteFailure");
    assertLogContains("ignored-sysout");
    assertLogContains("success-sysout");
    assertLogContains("afterclass-sysout");
    assertLogContains("beforeclass-sysout");
  }      
}
