package com.carrotsearch.ant.tasks.junit4;


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

  private int countPattern(String output, String substr) {
    int count = 0;
    for (int i = 0; i < output.length();) {
      int index = output.indexOf(substr, i);
      if (index < 0) {
        break;
      }
      count++;
      i = index + 1;
    }
    return count;
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
    // System.out.println(getLog());
  }  
}
