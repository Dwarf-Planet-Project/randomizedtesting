package com.carrotsearch.ant.tasks.junit4;

import java.io.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.tools.ant.AntClassLoader;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.ProjectComponent;
import org.apache.tools.ant.Task;
import org.apache.tools.ant.taskdefs.Execute;
import org.apache.tools.ant.types.Commandline;
import org.apache.tools.ant.types.CommandlineJava;
import org.apache.tools.ant.types.Environment;
import org.apache.tools.ant.types.FileSet;
import org.apache.tools.ant.types.Path;
import org.apache.tools.ant.types.PropertySet;
import org.apache.tools.ant.types.Resource;
import org.apache.tools.ant.types.ResourceCollection;
import org.apache.tools.ant.types.resources.Resources;
import org.apache.tools.ant.util.LoaderUtils;
import org.junit.runner.Description;
import org.objectweb.asm.ClassReader;

import com.carrotsearch.ant.tasks.junit4.events.aggregated.AggregatedQuitEvent;
import com.carrotsearch.ant.tasks.junit4.events.aggregated.AggregatedStartEvent;
import com.carrotsearch.ant.tasks.junit4.events.aggregated.AggregatingListener;
import com.carrotsearch.ant.tasks.junit4.listeners.AggregatedEventListener;
import com.carrotsearch.ant.tasks.junit4.slave.SlaveMain;
import com.carrotsearch.ant.tasks.junit4.slave.SlaveMainSafe;
import com.carrotsearch.randomizedtesting.ClassGlobFilter;
import com.carrotsearch.randomizedtesting.MethodGlobFilter;
import com.carrotsearch.randomizedtesting.RandomizedRunner;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.eventbus.EventBus;
import com.google.common.io.Files;

/**
 * A simple ANT task to run JUnit4 tests.
 */
public class JUnit4 extends Task {
  /** @see #setParallelism(String) */
  public static final Object PARALLELISM_AUTO = "auto";

  /** @see #setParallelism(String) */
  public static final String PARALLELISM_MAX = "max";

  /**
   * Random seed for shuffling the order of suites. Override
   * using {@link #setRandom(long)} or by setting this property globally.
   */
  public static final String PROPERTY_RANDOM = "junit4.random";

  /**
   * Project property for picking out a single test class to execute. All other
   * classes are ignored. The property can contain "globbing patterns" similar
   * to shell expansion patterns. For example:
   * <pre>
   * *MyTest
   * </pre>
   * will pick all classes ending in MyTest (in any package, including nested static
   * classes if they appear on input).
   */
  public static final String PROP_TESTCLASS = RandomizedRunner.SYSPROP_TESTCLASS;

  /**
   * Project property for picking out a single test method to execute. All other
   * methods are ignored. The property can contain "globbing patterns" similar
   * to shell expansion patterns. For example:
   * <pre>
   * test*
   * </pre>
   * will pick all methods starting with <code>test</code>.
   */
  public static final String PROP_TESTMETHOD = RandomizedRunner.SYSPROP_TESTMETHOD;

  /**
   * Slave VM command line.
   */
  private CommandlineJava slaveCommand = new CommandlineJava();

  /**
   * Set new environment for the forked process?
   */
  private boolean newEnvironment;

  /**
   * Environment variables to use in the forked JVM.
   */
  private Environment env = new Environment();
  
  /**
   * Directory to invoke slave VM in.
   */
  private File dir;

  /**
   * Test names.
   */
  private final Resources resources;

  /**
   * Stop the build process if there were errors?
   */
  private boolean haltOnFailure;

  /**
   * Print summary of all tests at the end.
   */
  private boolean printSummary = true;

  /**
   * Property to set if there were test failures or errors.
   */
  private String failureProperty;
  
  /**
   * A folder to store temporary files in. Defaults to the project's basedir.
   */
  private File tempDir;

  /**
   * Listeners listening on the event bus.
   */
  private List<Object> listeners = Lists.newArrayList();

  /**
   * Class loader used to resolve annotations and classes referenced from annotations
   * when {@link Description}s containing them are passed from slaves.
   */
  private AntClassLoader testsClassLoader;

  /**
   * @see #setParallelism(String)
   */
  private String parallelism = "1";

  /**
   * Set to true to leave temporary files (for diagnostics).
   */
  private boolean leaveTemporary;
  
  /**
   * @see #setRandom(long)
   */
  private long random;

  /**
   * Multiple path resolution in {@link CommandlineJava#getCommandline()} is very slow
   * so we construct and canonicalize paths.
   */
  private Path classpath;
  private Path bootclasspath;

  /**
   * 
   */
  public JUnit4() {
    resources = new Resources();
    resources.setCache(true);
  }
  
  /**
   * The number of parallel slaves. Can be set to a constant "max" for the
   * number of cores returned from {@link Runtime#availableProcessors()} or 
   * "auto" for sensible defaults depending on the number of cores.
   * The default is a single subprocess.
   * 
   * <p>Note that this setting forks physical JVM processes so it multiplies the 
   * requirements for heap memory, IO, etc. 
   */
  public void setParallelism(String parallelism) {
    this.parallelism = parallelism;
  }

  /**
   * Property to set to "true" if there is a failure in a test.
   */
  public void setFailureProperty(String failureProperty) {
    this.failureProperty = failureProperty;
  }
  
  /**
   * Initial random seed used for shuffling test suites and other sources
   * of pseudo-randomness. If not set, any random value is set. 
   */
  public void setRandom(long randomSeed) {
    this.random = randomSeed;
  }

  /*
   * 
   */
  @Override
  public void setProject(Project project) {
    super.setProject(project);

    this.random = new Random().nextLong();
    String randomProperty = getProject().getProperty(PROPERTY_RANDOM);
    if (randomProperty != null) {
      try {
        this.random = Long.parseLong(randomProperty);
      } catch (NumberFormatException e) {
        log("Wrong number format for " + PROPERTY_RANDOM + ": " +
            randomProperty, Project.MSG_ERR);
      }
    }

    this.resources.setProject(project);
    this.classpath = new Path(getProject());
    this.bootclasspath = new Path(getProject());
  }
  
  /**
   * Prints the summary of all executed, ignored etc. tests at the end. 
   */
  public void setPrintSummary(boolean printSummary) {
    this.printSummary = printSummary;
  }

  /**
   * Stop the build process if there were failures or errors during test execution.
   */
  public void setHaltOnFailure(boolean haltOnFailure) {
    this.haltOnFailure = haltOnFailure;
  }

  /**
   * Set the maximum memory to be used by all forked JVMs.
   * 
   * @param max
   *          the value as defined by <tt>-mx</tt> or <tt>-Xmx</tt> in the java
   *          command line options.
   */
  public void setMaxmemory(String max) {
    getCommandline().setMaxmemory(max);
  }
  
  /**
   * Set to true to leave temporary files for diagnostics.
   */
  public void setLeaveTemporary(boolean leaveTemporary) {
    this.leaveTemporary = leaveTemporary;
  }
  
  /**
   * Adds a JVM argument; ignored if not forking.
   * 
   * @return create a new JVM argument so that any argument can be passed to the
   *         JVM.
   */
  public Commandline.Argument createJvmarg() {
    return getCommandline().createVmArgument();
  }

  /**
   * Creates a new list of listeners.
   */
  public ListenersList createListeners() {
    return new ListenersList(listeners);
  }
  
  /**
   * Adds a system property to the forked JVM.
   */
  public void addConfiguredSysproperty(Environment.Variable sysp) {
    getCommandline().addSysproperty(sysp);
  }
  
  /**
   * Adds a set of properties that will be used as system properties that tests
   * can access.
   * 
   * This might be useful to tranfer Ant properties to the testcases.
   */
  public void addSyspropertyset(PropertySet sysp) {
    getCommandline().addSyspropertyset(sysp);
  }
  
  /**
   * The command used to invoke the Java Virtual Machine, default is 'java'. The
   * command is resolved by java.lang.Runtime.exec().
   * 
   * @param value
   *          the new VM to use instead of <tt>java</tt>
   */
  public void setJvm(String value) {
    getCommandline().setVm(value);
  }

  /**
   * Adds path to classpath used for tests.
   * 
   * @return reference to the classpath in the embedded java command line
   */
  public Path createClasspath() {
    return classpath.createPath();
  }

  /**
   * Adds a path to the bootclasspath.
   * 
   * @return reference to the bootclasspath in the embedded java command line
   */
  public Path createBootclasspath() {
    return bootclasspath.createPath();
  }

  /**
   * Adds an environment variable; used when forking.
   */
  public void addEnv(Environment.Variable var) {
    env.addVariable(var);
  }

  /**
   * Adds a set of tests based on pattern matching.
   */
  public void addFileSet(FileSet fs) {
    add(fs);
    if (fs.getProject() == null) {
      fs.setProject(getProject());
    }
  }

  /**
   * Adds a set of tests based on pattern matching.
   */
  public void add(ResourceCollection rc) {
    resources.add(rc);
  }

  /**
   * The directory to invoke the VM in.
   * 
   * @param dir
   *          the directory to invoke the JVM from.
   */
  public void setDir(File dir) {
    this.dir = dir;
  }

  /**
   * The directory to store temporary files in.
   */
  public void setTempDir(File tempDir) {
    this.tempDir = tempDir;
  }

  @Override
  public void execute() throws BuildException {
    log("<JUnit4> says hello. Random seed: " + getSeed(), Project.MSG_INFO);

    // Verify we have access to JUnit.
    verifyJUnit4Present();
    
    // Resolve paths first.
    this.classpath = resolveFiles(classpath);
    this.bootclasspath = resolveFiles(bootclasspath);
    getCommandline().createClasspath(getProject()).add(classpath);
    getCommandline().createBootclasspath(getProject()).add(bootclasspath);

    // Setup a class loader over test classes. This will be used for loading annotations
    // and referenced classes. This is kind of ugly, but mirroring annotation content will
    // be even worse and Description carries these.
    testsClassLoader = new AntClassLoader(
        this.getClass().getClassLoader(),
        getProject(),
        getCommandline().getClasspath(),
        true);

    // Pass method filter if any.
    String testMethodFilter = Strings.emptyToNull(getProject().getProperty(PROP_TESTMETHOD));
    if (testMethodFilter != null) {
      Environment.Variable v = new Environment.Variable();
      v.setKey(PROP_TESTMETHOD);
      v.setValue(testMethodFilter);
      getCommandline().addSysproperty(v);
    }

    // Process test classes and resources.
    long start = System.currentTimeMillis();    
    final List<String> testClassNames = processTestResources();

    final EventBus aggregatedBus = new EventBus("aggregated");
    final TestsSummaryEventListener summaryListener = new TestsSummaryEventListener();
    aggregatedBus.register(summaryListener);
    
    for (Object o : listeners) {
      if (o instanceof ProjectComponent) {
        ((ProjectComponent) o).setProject(getProject());
      }
      if (o instanceof AggregatedEventListener) {
        ((AggregatedEventListener) o).setOuter(this);
      }
      aggregatedBus.register(o);
    }

    if (!testClassNames.isEmpty()) {
      Collections.shuffle(testClassNames, new Random(getSeed()));

      start = System.currentTimeMillis();
      int slaveCount = determineSlaveCount(testClassNames.size());

      final int total = testClassNames.size();
      List<SlaveInfo> slaveInfos = Lists.newArrayList();
      List<Callable<Void>> slaves = Lists.newArrayList();
      for (int slave = 0; slave < slaveCount; slave++) {
        final SlaveInfo slaveInfo = new SlaveInfo(slave, slaveCount);
        final int from = slave * total / slaveCount;
        final int to = (slave + 1) * total / slaveCount;
        final List<String> sublist = testClassNames.subList(from, to);
        slaveInfos.add(slaveInfo);
        slaves.add(new Callable<Void>() {
          @Override
          public Void call() throws Exception {
            executeSlave(slaveInfo, aggregatedBus, sublist);
            return null;
          }
        });
      }

      ExecutorService executor = Executors.newCachedThreadPool();
      aggregatedBus.post(new AggregatedStartEvent(slaves.size()));

      List<Throwable> slaveErrors = Lists.newArrayList();
      try {
        List<Future<Void>> all = executor.invokeAll(slaves);
        executor.shutdown();

        for (Future<Void> f : all) {
          try {
            f.get();
          } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            slaveErrors.add(cause);
          }
        }
      } catch (InterruptedException e) {
        log("Master interrupted? Weird.", Project.MSG_ERR);
      }
      aggregatedBus.post(new AggregatedQuitEvent());

      for (SlaveInfo si : slaveInfos) {
        if (si.start > 0 && si.end > 0) {
          log(String.format(Locale.ENGLISH, "Slave %d: %8.2f .. %8.2f = %8.2fs",
              si.id,
              (si.start - start) / 1000.0f,
              (si.end - start) / 1000.0f,
              (si.getExecutionTime() / 1000.0f)), 
              Project.MSG_INFO);
        }
      }
      log(String.format(Locale.ENGLISH, "Execution time total: %.2fs", 
          (System.currentTimeMillis() - start) / 1000.0));

      if (!slaveErrors.isEmpty()) {
        for (Throwable t : slaveErrors) {
          log("ERROR: Slave execution exception: " + t, t, Project.MSG_ERR);
        }
        throw new BuildException("At least one slave process threw an exception, first: "
            + slaveErrors.get(0).toString(), slaveErrors.get(0));
      }
    }

    final TestsSummary testsSummary = summaryListener.getResult();
    if (printSummary) {
      log("Tests summary: " + testsSummary, Project.MSG_INFO);
    }

    if (!testsSummary.isSuccessful()) {
      if (!Strings.isNullOrEmpty(failureProperty)) {
        getProject().setNewProperty(failureProperty, "true");        
      }
      if (haltOnFailure) {
        throw new BuildException("There were test failures: " + testsSummary);
      }
    }
  }

  /**
   * Verify JUnit presence and version.
   */
  private void verifyJUnit4Present() {
    try {
      Class<?> clazz = Class.forName("org.junit.runner.Description");
      if (!Serializable.class.isAssignableFrom(clazz)) {
        throw new BuildException("At least JUnit version 4.10 is required on junit4's taskdef classpath.");
      }
    } catch (ClassNotFoundException e) {
      throw new BuildException("JUnit JAR must be added to junit4 taskdef's classpath.");
    }
  }

  /**
   * Return the initial random seed. Always non-negative for simplicity.
   */
  public long getSeed() {
    return this.random & 0x7fffffffffffL;
  }

  /**
   * Resolve all files from a given path and simplify its definition.
   */
  private Path resolveFiles(Path path) {
    Path cloned = new Path(getProject());
    for (String location : path.list()) {
      cloned.createPathElement().setLocation(new File(location));
    }
    return cloned;
  }

  /**
   * Determine how many slaves to use.
   */
  private int determineSlaveCount(int testCases) {
    int cores = Runtime.getRuntime().availableProcessors();
    int slaveCount;
    if (this.parallelism.equals(PARALLELISM_AUTO)) {
      if (cores >= 8) {
        // Maximum parallel jvms is 4, conserve some memory and memory bandwidth.
        slaveCount = 4;
      } else if (cores >= 4) {
        // Make some space for the aggregator.
        slaveCount = 3;
      } else {
        // even for dual cores it usually makes no sense to fork more than one
        // JVM.
        slaveCount = 1;
      }
    } else if (this.parallelism.equals(PARALLELISM_MAX)) {
      slaveCount = Runtime.getRuntime().availableProcessors();
    } else {
      try {
        slaveCount = Math.max(1, Integer.parseInt(parallelism));
      } catch (NumberFormatException e) {
        throw new BuildException("parallelism must be 'auto', 'max' or a valid integer: "
            + parallelism);
      }
    }

    slaveCount = Math.min(testCases, slaveCount);
    return slaveCount;
  }

  /**
   * Attach listeners and execute a slave process.
   */
  private void executeSlave(SlaveInfo slave, EventBus aggregatedBus, List<String> testClassNames)
    throws Exception
  {
    final File classNamesFile = File.createTempFile("junit4-", ".testmethods", getTempDir());
    try {
      // Dump all test class names to a temporary file.
      String testClassPerLine = Joiner.on("\n").join(testClassNames);
      log("Test class names:\n" + testClassPerLine, Project.MSG_VERBOSE);
  
      Files.write(testClassPerLine, classNamesFile, Charsets.UTF_8);
  
      // Prepare command line for java execution.
      CommandlineJava commandline;
      commandline = (CommandlineJava) getCommandline().clone();
      commandline.createClasspath(getProject()).add(addSlaveClasspath());
      commandline.setClassname(SlaveMainSafe.class.getName());
      commandline.createArgument().setValue("@" + classNamesFile.getAbsolutePath());
      if (slave.slaves == 1) {
        commandline.createArgument().setValue(SlaveMain.OPTION_FREQUENT_FLUSH);
      }
  
      String [] commandLineArgs = commandline.getCommandline();
  
      log("Slave process command line:\n" + 
          Joiner.on(" ").join(commandLineArgs), Project.MSG_VERBOSE);
  
      final EventBus eventBus = new EventBus("slave");
      final DiagnosticsListener diagnosticsListener = new DiagnosticsListener(slave, getProject());
      eventBus.register(diagnosticsListener);
      eventBus.register(new AggregatingListener(aggregatedBus, slave));
      executeProcess(eventBus, commandline);
      if (!diagnosticsListener.quitReceived()) {
        throw new BuildException("Quit event not received from a slave process?");
      }
    } finally {
      if (!leaveTemporary) {
        classNamesFile.delete();
      }
    }
  }

  /**
   * Execute a slave process. Pump events to the given event bus.
   */
  private void executeProcess(EventBus eventBus, CommandlineJava commandline) {
    try {
      final LocalSlaveStreamHandler streamHandler = 
          new LocalSlaveStreamHandler(eventBus, testsClassLoader, System.err);
      final Execute execute = new Execute();
      execute.setCommandline(commandline.getCommandline());
      execute.setVMLauncher(true);
      execute.setWorkingDirectory(dir == null ? getProject().getBaseDir() : dir);
      execute.setStreamHandler(streamHandler);
      execute.setNewenvironment(newEnvironment);
      if (env.getVariables() != null)
        execute.setEnvironment(env.getVariables());
      getProject().log("Starting slave.", Project.MSG_DEBUG);
      int exitStatus = execute.execute();
      getProject().log("Slave finished with exit code: " + exitStatus, Project.MSG_DEBUG);

      if (streamHandler.isErrorStreamNonEmpty()) {
        log("-- error stream from forked JVM (verbatim) --", Project.MSG_ERR);
        log(streamHandler.getErrorStreamAsString(), Project.MSG_ERR);
        log("-- EOF --", Project.MSG_ERR);

        // Anything on the altErr will cause a build failure.
        String msg = "Unexpected output from forked JVM. This" +
            " most likely indicates JVM crash. Inspect the logs above and look for crash" +
            " dumps in: " + getProject().getBaseDir().getAbsolutePath();
        log(msg, Project.MSG_ERR);
        throw new BuildException("Unexpected output from forked JVM. This" +
            " most likely indicates JVM crash.");
      }

      if (execute.isFailure()) {
        if (exitStatus == SlaveMain.ERR_NO_JUNIT) {
          throw new BuildException("Forked JVM's classpath must include a junit4 JAR.");
        }
        throw new BuildException("Forked process exited with an error code: " + exitStatus);
      }
    } catch (IOException e) {
      throw new BuildException("Could not execute slave process. Run ant with -verbose to get" +
      		" the execution details.", e);
    }
  }

  /**
   * Resolve temporary folder.
   */
  private File getTempDir() {
    if (this.tempDir == null) {
      this.tempDir = getProject().getBaseDir();
    }
    return tempDir;
  }

  /**
   * Process test resources. If there are any test resources that are _not_ class files,
   * this will cause a build error.   
   */
  private List<String> processTestResources() {
    List<String> testClassNames = Lists.newArrayList();
    resources.setProject(getProject());
    
    @SuppressWarnings("unchecked")
    Iterator<Resource> iter = (Iterator<Resource>) resources.iterator();
    while (iter.hasNext()) {
      final Resource r = iter.next();
      if (!r.isExists()) 
        throw new BuildException("Test class resource does not exist?: " + r.getName());

      try {
        InputStream is = r.getInputStream();
        try {
          ClassReader reader = new ClassReader(is);
          String className = reader.getClassName().replace('/', '.');
          getProject().log("Test class parsed: " + r.getName() + " as " 
              + reader.getClassName(), Project.MSG_DEBUG);
          testClassNames.add(className);
        } finally {
          is.close();
        }
      } catch (IOException e) {
        throw new BuildException("Could not read or parse as Java class: "
            + r.getName() + ", " + r.getLocation());
      }
    }

    String testClassFilter = Strings.emptyToNull(getProject().getProperty(PROP_TESTCLASS));
    if (testClassFilter != null) {
      ClassGlobFilter filter = new ClassGlobFilter(testClassFilter);
      for (Iterator<String> i = testClassNames.iterator(); i.hasNext();) {
        if (!filter.shouldRun(Description.createSuiteDescription(i.next()))) {
          i.remove();
        }
      }
    }

    return testClassNames;
  }

  /**
   * Returns the slave VM command line.
   */
  private CommandlineJava getCommandline() {
    return slaveCommand;
  }

  /**
   * Adds a classpath source which contains the given resource.
   */
  private Path addSlaveClasspath() {
    Path path = new Path(getProject());

    String [] REQUIRED_SLAVE_CLASSES = {
        SlaveMain.class.getName(),
        Strings.class.getName(),
        MethodGlobFilter.class.getName(),
    };

    for (String clazz : Arrays.asList(REQUIRED_SLAVE_CLASSES)) {
      String resource = clazz.replace(".", "/") + ".class";
      File f = LoaderUtils.getResourceSource(getClass().getClassLoader(), resource);
      if (f != null) {
        path.createPath().setLocation(f);
      } else {
        throw new BuildException("Could not locate classpath for resource: "
            + resource);
      }
    }
    return path;
  }  
}
