package com.carrotsearch.ant.tasks.junit4;

import com.carrotsearch.ant.tasks.junit4.events.aggregated.AggregatedSuiteResultEvent;
import com.carrotsearch.ant.tasks.junit4.events.aggregated.AggregatedTestResultEvent;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;

/**
 * Create a summary of tests execution.
 * 
 * @see EventBus
 */
public class TestsSummaryEventListener {
  private int failures;
  private int tests;
  private int errors;
  private int assumptions;
  private int ignores;

  private int suites;
  private int suiteErrors;

  /**
   * React to suite summaries only.
   */
  @Subscribe
  public void suiteSummary(AggregatedSuiteResultEvent e) {
    suites++;
    if (!e.getSuiteFailures().isEmpty()) {
      suiteErrors++;
    }

    for (AggregatedTestResultEvent testResult : e.getTests()) {
      tests++;

      switch (testResult.getStatus()) {
        case ERROR: 
          errors++; 
          break;

        case FAILURE: 
          failures++; 
          break;
          
        case IGNORED:
          ignores++;
          break;

        case IGNORED_ASSUMPTION:
          assumptions++;
          break;
      }
    }
  }

  /**
   * Return the summary of all tests.
   */
  public TestsSummary getResult() {
    return new TestsSummary(suites, suiteErrors, tests, failures, errors, assumptions, ignores);
  }
}
