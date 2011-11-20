package com.carrotsearch.ant.tasks.junit4;

import java.net.URL;

import org.apache.tools.ant.BuildEvent;
import org.apache.tools.ant.BuildFileTest;
import org.apache.tools.ant.DefaultLogger;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestJUnit4 extends BuildFileTest {
  @BeforeClass
  public void setUp() {
    URL resource = getClass().getClassLoader().getResource("junit4.xml");
    assertNotNull(resource);
    configureProject(resource.getFile());
  }
  
  @Test
  public void testSimple() {
    final StringBuilder builder = new StringBuilder();
    getProject().addBuildListener(new DefaultLogger() {
      @Override
      public void messageLogged(BuildEvent e) {
        builder.append(e.getMessage());
        builder.append("\n");
      }
    });
    super.executeTarget("junit4");
    System.out.println(builder.toString());
  }
}
