package com.carrotsearch.ant.tasks.junit4;

import java.io.*;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.apache.commons.io.output.TeeOutputStream;
import org.apache.tools.ant.*;
import org.apache.tools.ant.taskdefs.Execute;
import org.apache.tools.ant.types.*;
import org.apache.tools.ant.types.Environment.Variable;
import org.apache.tools.ant.types.resources.Resources;
import org.apache.tools.ant.util.LoaderUtils;
import org.junit.runner.Description;
import org.objectweb.asm.ClassReader;

import com.carrotsearch.ant.tasks.junit4.SuiteBalancer.Assignment;
import com.carrotsearch.ant.tasks.junit4.balancers.RoundRobinBalancer;
import com.carrotsearch.ant.tasks.junit4.balancers.SuiteHint;
import com.carrotsearch.ant.tasks.junit4.events.BootstrapEvent;
import com.carrotsearch.ant.tasks.junit4.events.QuitEvent;
import com.carrotsearch.ant.tasks.junit4.events.aggregated.*;
import com.carrotsearch.ant.tasks.junit4.listeners.AggregatedEventListener;
import com.carrotsearch.ant.tasks.junit4.listeners.TextReport;
import com.carrotsearch.ant.tasks.junit4.slave.SlaveMain;
import com.carrotsearch.ant.tasks.junit4.slave.SlaveMainSafe;
import com.carrotsearch.randomizedtesting.*;
import com.carrotsearch.randomizedtesting.generators.RandomPicks;
import com.google.common.base.*;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multiset;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.google.common.io.CharStreams;
import com.google.common.io.Closeables;
import com.google.common.io.Files;
import com.google.gson.Gson;

import static com.carrotsearch.randomizedtesting.SysGlobals.*;

/**
 * An ANT task to run JUnit4 tests. Differences (benefits?) compared to ANT's default JUnit task:
 * <ul>
 *  <li>Built-in parallel test execution support (spawns multiple JVMs to avoid 
 *  test interactions).</li>
 *  <li>Randomization of the order of test suites within a single JVM.</li>
 *  <li>Aggregates and synchronizes test events from executors. All reports run on
 *  the task's JVM (not on the test JVM).</li>
 *  <li>Fully configurable reporting via listeners (console, ANT-compliant XML, JSON).
 *  Report listeners use Google Guava's {@link EventBus} and receive full information
 *  about tests' execution (including skipped, assumption-skipped tests, streamlined
 *  output and error stream chunks, etc.).</li>
 *  <li>JUnit 4.10+ is required both for the task and for the tests classpath. 
 *  Older versions will cause build failure.</li>
 *  <li>Integration with {@link RandomizedRunner} (randomization seed is passed to
 *  children JVMs).</li>
 * </ul>
 */
public class JUnit4 extends Task {
  /**
   * Welcome messages.
   */
  private static String [] WELCOME_MESSAGES = {
    "hello!",               // en
    "hi!",                  // en
    "g'day!",               // en, australia
    "¡Hola!",               // es
    "jolly good day!",      // monty python
    "aloha!",               // en, hawaii
    "cześć!",               // pl
    "مرحبا!",               // arabic (modern)
    "kaixo!",               // basque
    "Привет!",              // bulgarian, russian
    "你好!",                 // cn, traditional
    "ahoj!",                // czech
    "salut!",               // french
    "hallo!",               // german
    "שלום!",                // hebrew
    "नमस्ते!",                // hindi
    "ᐊᐃ!",                  // inuktitut
    "ciao!",                // italian
    "今日は!",               // japanese
    "olá!",                 // portuguese
    // add more if your country/ place is not on the list ;)
  };

  /** Name of the antlib resource inside JUnit4 JAR. */
  public static final String ANTLIB_RESOURCE_NAME = "com/carrotsearch/junit4/antlib.xml";

  /** @see #setParallelism(String) */
  public static final Object PARALLELISM_AUTO = "auto";

  /** @see #setParallelism(String) */
  public static final String PARALLELISM_MAX = "max";

  /** Default value of {@link #setShuffleOnSlave}. */
  public static final boolean DEFAULT_SHUFFLE_ON_SLAVE = true;

  /** Default value of {@link #setParallelism}. */
  public static final String  DEFAULT_PARALLELISM = "1";

  /** Default value of {@link #setPrintSummary}. */
  public static final boolean DEFAULT_PRINT_SUMMARY = true;

  /** Default value of {@link #setHaltOnFailure}. */
  public static final boolean DEFAULT_HALT_ON_FAILURE = true;
  
  /** Default value of {@link #setIsolateWorkingDirectories(boolean)}. */
  public static final boolean DEFAULT_ISOLATE_WORKING_DIRECTORIES = true;

  /** Default value of {@link #setDynamicAssignmentRatio(float)} */
  public static final float DEFAULT_DYNAMIC_ASSIGNMENT_RATIO = .25f;

  /** Default value of {@link #setSysouts}. */
  public static final boolean DEFAULT_SYSOUTS = false;

  /** System property passed to forked VMs: current working directory (absolute). */
  private static final String CHILDVM_SYSPROP_CWD = "junit4.childvm.cwd";

  /** System property passed to forked VMs: VM ID (integer). */
  private static final String CHILDVM_SYSPROP_ID = "junit4.childvm.id";
  
  /** What to do on JVM output? */
  public static enum JvmOutputAction {
    PIPE,
    IGNORE,
    FAIL,
    WARN
  }

  /**
   * @see #setJvmOutputAction(String)
   */
  public EnumSet<JvmOutputAction> jvmOutputAction = EnumSet.of(
      JvmOutputAction.PIPE,
      JvmOutputAction.WARN);

  /**
   * @see #setSysouts
   */
  private boolean sysouts = DEFAULT_SYSOUTS; 
  
  /**
   * Slave VM command line.
   */
  private CommandlineJava slaveCommand = new CommandlineJava();

  /**
   * Set new environment for the forked process?
   */
  private boolean newEnvironment;

  /**
   * @see #setUniqueSuiteNames
   */
  private boolean uniqueSuiteNames = true;
  
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
  private boolean haltOnFailure = DEFAULT_HALT_ON_FAILURE;

  /**
   * Print summary of all tests at the end.
   */
  private boolean printSummary = DEFAULT_PRINT_SUMMARY;

  /**
   * Property to set if there were test failures or errors.
   */
  private String failureProperty;
  
  /**
   * A folder to store temporary files in. Defaults to {@link #dir} or  
   * the project's basedir.
   */
  private File tempDir;

  /**
   * Listeners listening on the event bus.
   */
  private List<Object> listeners = Lists.newArrayList();

  /**
   * Balancers scheduling tests for individual JVMs in parallel mode.
   */
  private List<SuiteBalancer> balancers = Lists.newArrayList();

  /**
   * Class loader used to resolve annotations and classes referenced from annotations
   * when {@link Description}s containing them are passed from slaves.
   */
  private AntClassLoader testsClassLoader;

  /**
   * @see #setParallelism(String)
   */
  private String parallelism = DEFAULT_PARALLELISM;

  /**
   * Set to true to leave temporary files (for diagnostics).
   */
  private boolean leaveTemporary;

  /**
   * A list of temporary files to leave or remove if build passes.
   */
  private List<File> temporaryFiles = Collections.synchronizedList(Lists.<File>newArrayList());

  /**
   * @see #setSeed(String)
   */
  private String random;

  /**
   * @see #setIsolateWorkingDirectories(boolean)
   */
  private boolean isolateWorkingDirectories = DEFAULT_ISOLATE_WORKING_DIRECTORIES;

  /**
   * Multiple path resolution in {@link CommandlineJava#getCommandline()} is very slow
   * so we construct and canonicalize paths.
   */
  private Path classpath;
  private Path bootclasspath;

  /**
   * @see #setDynamicAssignmentRatio(float)
   */
  private float dynamicAssignmentRatio = DEFAULT_DYNAMIC_ASSIGNMENT_RATIO;

  /**
   * @see #setShuffleOnSlave(boolean)
   */
  private boolean shuffleOnSlave = DEFAULT_SHUFFLE_ON_SLAVE;

  /**
   * @see #setHeartbeat
   */
  private long heartbeat; 
  
  /**
   * 
   */
  public JUnit4() {
    resources = new Resources();
  }

  /**
   * What should be done on unexpected JVM output? JVM may write directly to the 
   * original descriptors, bypassing redirections of System.out and System.err. Typically,
   * these messages will be important and should fail the build (permgen space exceeded,
   * compiler errors, crash dumps). However, certain legitimate logs (gc activity, class loading
   * logs) are also printed to these streams so sometimes the output can be ignored.
   * 
   * <p>Allowed values (any comma-delimited combination of): {@link JvmOutputAction} 
   * constants.
   */
  public void setJvmOutputAction(String jvmOutputActions) {
    EnumSet<JvmOutputAction> actions = EnumSet.noneOf(JvmOutputAction.class); 
    for (String s : jvmOutputActions.split("[\\,\\ ]+")) {
      s = s.trim().toUpperCase(Locale.ENGLISH);
      actions.add(JvmOutputAction.valueOf(s));
    }
    this.jvmOutputAction = actions;
  }

  /**
   * If set to true, any sysout and syserr calls will be written to original
   * output and error streams (and in effect will appear as "jvm output". By default
   * sysout and syserrs are captured and proxied to the event stream to be synchronized
   * with other test events but occasionally one may want to synchronize them with direct 
   * JVM output (to synchronize with compiler output or GC output for example). 
   */
  public void setSysouts(boolean sysouts) {
    this.sysouts = sysouts;
  }
  
  /**
   * Allow or disallow duplicate suite names in resource collections. By default this option
   * is <code>true</code> because certain ANT-compatible report types (like XML reports)
   * will have a problem with duplicate suite names (will overwrite files).
   */
  public void setUniqueSuiteNames(boolean uniqueSuiteNames) {
    this.uniqueSuiteNames = uniqueSuiteNames;
  }

  /**
   * Specifies the ratio of suites moved to dynamic assignment list. A dynamic
   * assignment list dispatches suites to the first idle slave JVM. Theoretically
   * this is an optimal strategy, but it is usually better to have some static assignments
   * to avoid communication costs.
   * 
   * <p>A ratio of 0 means only static assignments are used. A ratio of 1 means
   * only dynamic assignments are used.
   * 
   * <p>The list of dynamic assignments is sorted by decreasing cost (always) and
   * is inherently prone to race conditions in distributing suites. Should there
   * be an error based on suite-dependency it will not be directly repeatable. In such
   * case use the per-slave-jvm list of suites file dumped to disk for each slave JVM.
   * (see {@link #setLeaveTemporary(boolean)}).
   */
  public void setDynamicAssignmentRatio(float ratio) {
    if (ratio < 0 || ratio > 1) {
      throw new IllegalArgumentException("Dynamic assignment ratio must be " +
      		"between 0 (only static assignments) to 1 (fully dynamic assignments).");
    }
    this.dynamicAssignmentRatio = ratio;
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
   * Do not propagate the old environment when new environment variables are specified.
   */
  public void setNewEnvironment(boolean v) {
    this.newEnvironment = v;
  }
  
  /**
   * Initial random seed used for shuffling test suites and other sources
   * of pseudo-randomness. If not set, any random value is set. 
   * 
   * <p>The seed's format is compatible with {@link RandomizedRunner} so that
   * seed can be fixed for suites and methods alike.
   */
  public void setSeed(String randomSeed) {
    if (!Strings.isNullOrEmpty(getProject().getUserProperty(SYSPROP_RANDOM_SEED()))) {
      String userProperty = getProject().getUserProperty(SYSPROP_RANDOM_SEED());
      if (!userProperty.equals(randomSeed)) {
        log("Ignoring seed attribute because it is overridden by user properties.", Project.MSG_WARN);
      }
    } else if (!Strings.isNullOrEmpty(randomSeed)) {
      this.random = randomSeed;
    }
  }

  /**
   * Initializes custom prefix for all junit4 properties. This must be consistent
   * across all junit4 invocations if done from the same classpath. Use only when REALLY needed.
   */
  public void setPrefix(String prefix) {
    if (!Strings.isNullOrEmpty(getProject().getUserProperty(SYSPROP_PREFIX()))) {
      log("Ignoring prefix attribute because it is overridden by user properties.", Project.MSG_WARN);
    } else {
      SysGlobals.initializeWith(prefix);
    }
  }

  /**
   * @see #setSeed(String) 
   */
  public String getSeed() {
    return random;
  }  

  /**
   * Predictably shuffle tests order after balancing. This will help in spreading
   * lighter and heavier tests over a single slave's execution timeline while
   * still keeping the same tests order depending on the seed. 
   */ 
  public void setShuffleOnSlave(boolean shuffle) {
    this.shuffleOnSlave = shuffle;
  }

  /*
   * 
   */
  @Override
  public void setProject(Project project) {
    super.setProject(project);

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
    if (!Strings.isNullOrEmpty(max)) {
      getCommandline().setMaxmemory(max);
    }
  }

  /**
   * Set to true to leave temporary files for diagnostics.
   */
  public void setLeaveTemporary(boolean leaveTemporary) {
    this.leaveTemporary = leaveTemporary;
  }
  
  /**
   * Add an additional argument to any forked JVM.
   */
  public Commandline.Argument createJvmarg() {
    return getCommandline().createVmArgument();
  }

  /**
   * The directory to invoke forked VMs in.
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

  /**
   * A {@link org.apache.tools.ant.types.Environment.Variable} with an additional
   * attribute specifying whether or not empty values should be propagated or ignored.  
   */
  public static class ExtendedVariable extends Environment.Variable {
    private boolean ignoreEmptyValue = false;

    public void setIgnoreEmpty(boolean ignoreEmptyValue) {
      this.ignoreEmptyValue = ignoreEmptyValue;
    }

    public boolean shouldIgnore() {
      return ignoreEmptyValue && Strings.isNullOrEmpty(getValue());
    }

    @Override
    public String toString() {
      return getContent() + " (ignoreEmpty=" + ignoreEmptyValue + ")";
    }
  }
  
  /**
   * Adds a system property to any forked JVM.
   */
  public void addConfiguredSysproperty(ExtendedVariable sysp) {
    if (!sysp.shouldIgnore()) {
      getCommandline().addSysproperty(sysp);
    }
  }
  
  /**
   * A {@link PropertySet} with an additional
   * attribute specifying whether or not empty values should be propagated or ignored.
   */
  public static class ExtendedPropertySet extends PropertySet {
    private boolean ignoreEmptyValue = false;

    public void setIgnoreEmpty(boolean ignoreEmptyValue) {
      this.ignoreEmptyValue = ignoreEmptyValue;
    }

    @Override
    public Properties getProperties() {
      Properties properties = super.getProperties();
      Properties clone = new Properties();
      for (String s : properties.stringPropertyNames()) {
        String value = (String) properties.get(s);
        if (ignoreEmptyValue && Strings.isNullOrEmpty(value)) {
          continue;
        } else {
          clone.setProperty(s, value);
        }
      }
      return clone;
    }
  }

  /**
   * Adds a set of properties that will be used as system properties that tests
   * can access.
   * 
   * This might be useful to transfer Ant properties to the testcases.
   */
  public void addConfiguredSyspropertyset(ExtendedPropertySet sysp) {
    getCommandline().addSyspropertyset(sysp);
  }
  
  /**
   * The command used to invoke the Java Virtual Machine, default is 'java'. The
   * command is resolved by java.lang.Runtime.exec().
   */
  public void setJvm(String jvm) {
    if (!Strings.isNullOrEmpty(jvm)) {
      getCommandline().setVm(jvm);
    }
  }

  /**
   * If set to <code>true</code> each slave JVM gets a separate working directory
   * under whatever is set in {@link #setDir(File)}. The directory naming for each slave
   * follows: "S<i>num</i>", where <i>num</i> is slave's number. Directories are created
   * automatically and removed unless {@link #setLeaveTemporary(boolean)} is set to
   * <code>true</code>.
   */
  public void setIsolateWorkingDirectories(boolean isolateWorkingDirectories) {
    this.isolateWorkingDirectories = isolateWorkingDirectories;
  }

  /**
   * Adds an environment variable; used when forking.
   */
  public void addEnv(ExtendedVariable var) {
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
   * Creates a new list of listeners.
   */
  public ListenersList createListeners() {
    return new ListenersList(listeners);
  }

  /**
   * Add assertions to tests execution.
   */
  public void addAssertions(Assertions asserts) {
    if (getCommandline().getAssertions() != null) {
        throw new BuildException("Only one assertion declaration is allowed");
    }
    getCommandline().setAssertions(asserts);
  }

  /**
   * Creates a new list of balancers.
   */
  public BalancersList createBalancers() {
    return new BalancersList(balancers);
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
  
  /* ANT-junit compat only. */
  public void setFork(boolean fork) {
    warnUnsupported("fork");
  }

  public void setForkmode(String forkMode) {
    warnUnsupported("forkmode");
  }
  
  public void setHaltOnError(boolean haltOnError) {
    warnUnsupported("haltonerror");
  }

  public void setFiltertrace(boolean filterTrace) {
    warnUnsupported("filtertrace");
  }

  public void setTimeout(String v) {
    warnUnsupported("timeout");
  }

  public void setIncludeantruntime(String v) {
    warnUnsupported("includeantruntime");
  }

  public void setShowoutput(String v) {
    warnUnsupported("showoutput");
  }

  public void setOutputtoformatters(String v) {
    warnUnsupported("outputtoformatters");
  }

  public void setReloading(String v) {
    warnUnsupported("reloading");
  }
  
  public void setClonevm(String v) {
    warnUnsupported("clonevm");
  }

  public void setErrorproperty(String v) {
      warnUnsupported("errorproperty");
  }

  public void setLogfailedtests(String v) {
    warnUnsupported("logfailedtests");
  }
  
  public void setEnableTestListenerEvents(String v) {
    warnUnsupported("enableTestListenerEvents");
  }

  public Object createFormatter() {
      throw new BuildException("<formatter> elements are not supported by <junit4>. " +
      		"Refer to the documentation about listeners and reports.");
  }

  public Object createTest() {
      throw new BuildException("<test> elements are not supported by <junit4>. " +
            "Use regular ANT resource collections to point at individual tests or their groups.");
  }

  public Object createBatchtest() {
    throw new BuildException("<batchtest> elements are not supported by <junit4>. " +
        "Use regular ANT resource collections to point at individual tests or their groups.");
  }

  private void warnUnsupported(String attName) {
    log("The '" + attName + "' attribute is not supported by <junit4>.", Project.MSG_WARN);
  }

  /**
   * Sets the heartbeat used to detect inactive/ hung forked tests (JVMs) to the given
   * number of seconds. The heartbeat detects
   * no-event intervals and will report them to listeners. Notably, {@link TextReport} report will
   * emit heartbeat information (to a file or console).
   * 
   * <p>Setting the heartbeat to zero means no detection.
   */
  public void setHeartbeat(long heartbeat) {
    this.heartbeat = heartbeat;
  }
  
  @SuppressWarnings("deprecation")
  @Override
  public void execute() throws BuildException {
    validateJUnit4();

    // Initialize random if not already provided.
    if (random == null) {
      this.random = com.google.common.base.Objects.firstNonNull( 
          Strings.emptyToNull(getProject().getProperty(SYSPROP_RANDOM_SEED())),
          SeedUtils.formatSeed(new Random().nextLong()));
    }
    masterSeed();

    // Say hello and continue.
    log("<JUnit4> says " +
        RandomPicks.randomFrom(new Random(masterSeed()), WELCOME_MESSAGES) +
        " Master seed: " + getSeed(), Project.MSG_INFO);

    // Pass the random seed property.
    createJvmarg().setValue("-D" + SYSPROP_PREFIX() + "=" + CURRENT_PREFIX());
    createJvmarg().setValue("-D" + SYSPROP_RANDOM_SEED() + "=" + random);

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
    String testMethodFilter = Strings.emptyToNull(getProject().getProperty(SYSPROP_TESTMETHOD()));
    if (testMethodFilter != null) {
      Environment.Variable v = new Environment.Variable();
      v.setKey(SYSPROP_TESTMETHOD());
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

    if (testClassNames.isEmpty()) {
      aggregatedBus.post(new AggregatedQuitEvent());
    } else {
      start = System.currentTimeMillis();

      // Check if we allow duplicate suite names. Some reports (ANT compatible XML
      // reports) will have a problem with duplicate suite names, for example.
      if (uniqueSuiteNames) {
        List<String> unique = Lists.newArrayList(new HashSet<String>(testClassNames));
        testClassNames.clear();
        testClassNames.addAll(unique);
      }

      final int slaveCount = determineSlaveCount(testClassNames.size());
      final List<SlaveInfo> slaveInfos = Lists.newArrayList();
      for (int slave = 0; slave < slaveCount; slave++) {
        final SlaveInfo slaveInfo = new SlaveInfo(slave, slaveCount);
        slaveInfos.add(slaveInfo);
      }
      
      // Order test class names identically for balancers.
      Collections.sort(testClassNames);
      
      // Prepare a pool of suites dynamically dispatched to slaves as they become idle.
      final Deque<String> stealingQueue = 
          new ArrayDeque<String>(loadBalanceSuites(slaveInfos, testClassNames, balancers));
      aggregatedBus.register(new Object() {
        @Subscribe @SuppressWarnings("unused")
        public void onSlaveIdle(SlaveIdle slave) {
          if (stealingQueue.isEmpty()) {
            slave.finished();
          } else {
            String suiteName = stealingQueue.pop();
            slave.newSuite(suiteName);
          }
        }
      });

      // Create callables for the executor.
      final List<Callable<Void>> slaves = Lists.newArrayList();
      for (int slave = 0; slave < slaveCount; slave++) {
        final SlaveInfo slaveInfo = slaveInfos.get(slave);
        slaves.add(new Callable<Void>() {
          @Override
          public Void call() throws Exception {
            executeSlave(slaveInfo, aggregatedBus);
            return null;
          }
        });
      }

      ExecutorService executor = Executors.newCachedThreadPool();
      aggregatedBus.post(new AggregatedStartEvent(slaves.size(), testClassNames.size()));

      try {
        List<Future<Void>> all = executor.invokeAll(slaves);
        executor.shutdown();

        for (int i = 0; i < slaves.size(); i++) {
          Future<Void> f = all.get(i);
          try {
            f.get();
          } catch (ExecutionException e) {
            slaveInfos.get(i).executionError = e.getCause();
          }
        }
      } catch (InterruptedException e) {
        log("Master interrupted? Weird.", Project.MSG_ERR);
      }
      aggregatedBus.post(new AggregatedQuitEvent());

      for (SlaveInfo si : slaveInfos) {
        if (si.start > 0 && si.end > 0) {
          log(String.format(Locale.ENGLISH, "JVM J%d: %8.2f .. %8.2f = %8.2fs",
              si.id,
              (si.start - start) / 1000.0f,
              (si.end - start) / 1000.0f,
              (si.getExecutionTime() / 1000.0f)), 
              Project.MSG_INFO);
        }
      }
      log("Execution time total: " + Duration.toHumanDuration(
          (System.currentTimeMillis() - start)));

      SlaveInfo slaveInError = null;
      for (SlaveInfo i : slaveInfos) {
        if (i.executionError != null) {
          log("ERROR: JVM J" + i.id + " ended with an exception, command line: " + i.getCommandLine());
          log("ERROR: JVM J" + i.id + " ended with an exception: " + 
              Throwables.getStackTraceAsString(i.executionError), Project.MSG_ERR);
          if (slaveInError == null) {
            slaveInError = i;
          }
        }
      }

      if (slaveInError != null) {
        throw new BuildException("At least one slave process threw an exception, first: "
            + slaveInError.executionError.getMessage(), slaveInError.executionError);
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

    if (!leaveTemporary) {
      for (File f : temporaryFiles) {
        try {
          if (f != null) {
            Files.deleteRecursively(f);
          }
        } catch (IOException e) {
          log("Could not remove temporary path: " + f.getAbsolutePath(), Project.MSG_WARN);
        }
      }
    }
  }

  /**
   * Validate JUnit4 presence in a concrete version.
   */
  private void validateJUnit4() throws BuildException {
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
   * Perform load balancing of the set of suites. Sets {@link SlaveInfo#testSuites}
   * to suites preassigned to a given slave and returns a pool of suites
   * that should be load-balanced dynamically based on job stealing.
   */
  private List<String> loadBalanceSuites(List<SlaveInfo> slaveInfos,
      List<String> testClassNames, List<SuiteBalancer> balancers) {
    final List<SuiteBalancer> balancersWithFallback = Lists.newArrayList(balancers);
    balancersWithFallback.add(new RoundRobinBalancer());

    // Go through all the balancers, the first one to assign a suite wins.
    final Multiset<String> remaining = HashMultiset.create(testClassNames);
    final Map<Integer,List<Assignment>> perJvmAssignments = Maps.newHashMap();
    for (SlaveInfo si : slaveInfos) {
      perJvmAssignments.put(si.id, Lists.<Assignment> newArrayList());
    }
    final int jvmCount = slaveInfos.size();
    for (SuiteBalancer balancer : balancersWithFallback) {
      balancer.setOwner(this);
      final List<Assignment> assignments =
          balancer.assign(
              Collections.unmodifiableCollection(remaining), jvmCount, masterSeed());

      for (Assignment e : assignments) {
        if (e == null) {
          throw new RuntimeException("Balancer must return non-null assignments.");
        }
        if (!remaining.remove(e.suiteName)) {
          throw new RuntimeException("Balancer must return suite name as a key: " + e.suiteName);
        }

        log(String.format(Locale.ENGLISH,
            "Assignment hint: J%-2d (cost %5d) %s (by %s)",
            e.slaveId,
            e.estimatedCost,
            e.suiteName,
            balancer.getClass().getSimpleName()), Project.MSG_VERBOSE);

        perJvmAssignments.get(e.slaveId).add(e);
      }
    }

    if (remaining.size() != 0) {
      throw new RuntimeException("Not all suites assigned?: " + remaining);
    }
    
    if (shuffleOnSlave) {
      // Shuffle suites on slaves so that the result is always the same wrt master seed
      // (sort first, then shuffle with a constant seed).
      for (List<Assignment> assignments : perJvmAssignments.values()) {
        Collections.sort(assignments);
        Collections.shuffle(assignments, new Random(this.masterSeed()));
      }
    }

    // Take a fraction of suites scheduled as last on each slave and move them to a common
    // job-stealing queue.
    List<SuiteHint> stealingQueueWithHints = Lists.newArrayList();
    for (SlaveInfo si : slaveInfos) {
      final List<Assignment> assignments = perJvmAssignments.get(si.id);
      int moveToCommon = (int) (assignments.size() * dynamicAssignmentRatio);

      if (moveToCommon > 0) {
        final List<Assignment> movedToCommon = 
            assignments.subList(assignments.size() - moveToCommon, assignments.size());
        for (Assignment a : movedToCommon) {
          stealingQueueWithHints.add(new SuiteHint(a.suiteName, a.estimatedCost));
        }
        movedToCommon.clear();
      }

      final ArrayList<String> slaveSuites = (si.testSuites = Lists.newArrayList());
      for (Assignment a : assignments) {
        slaveSuites.add(a.suiteName);
      }
    }

    // Sort stealing queue according to descending cost.
    Collections.sort(stealingQueueWithHints, SuiteHint.DESCENDING_BY_WEIGHT);

    // Dump scheduling information.
    for (SlaveInfo si : slaveInfos) {
      log("Forked JVM J" + si.id + " assignments (after shuffle):", Project.MSG_VERBOSE);
      for (String suiteName : si.testSuites) {
        log("  " + suiteName, Project.MSG_VERBOSE);
      }
    }

    log("Stealing queue:", Project.MSG_VERBOSE);
    for (SuiteHint suiteHint : stealingQueueWithHints) {
      log("  " + suiteHint.suiteName + " " + suiteHint.cost, Project.MSG_VERBOSE);
    }

    List<String> stealingQueue = Lists.newArrayListWithCapacity(stealingQueueWithHints.size());
    for (SuiteHint suiteHint : stealingQueueWithHints) {
      stealingQueue.add(suiteHint.suiteName);
    }
    return stealingQueue;
  }

  /**
   * Return the master seed of {@link #getSeed()}.
   */
  private long masterSeed() {
    long[] seeds = SeedUtils.parseSeedChain(getSeed());
    if (seeds.length < 1) {
      throw new BuildException("Random seed is required.");
    }
    return seeds[0];
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
  private void executeSlave(final SlaveInfo slave, final EventBus aggregatedBus)
    throws Exception
  {
    final String uniqueSeed = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS").format(new Date());

    final File classNamesFile = tempFile(uniqueSeed,
        "junit4-J" + slave.id, ".suites", getTempDir());
    temporaryFiles.add(classNamesFile);

    final File classNamesDynamic = tempFile(uniqueSeed,
        "junit4-J" + slave.id, ".dynamic-suites", getTempDir());

    final File streamsBufferFile = tempFile(uniqueSeed,
        "junit4-J" + slave.id, ".spill", getTempDir());

    // Dump all test class names to a temporary file.
    String testClassPerLine = Joiner.on("\n").join(slave.testSuites);
    log("Test class names:\n" + testClassPerLine, Project.MSG_VERBOSE);

    Files.write(testClassPerLine, classNamesFile, Charsets.UTF_8);

    // Prepare command line for java execution.
    CommandlineJava commandline;
    commandline = (CommandlineJava) getCommandline().clone();
    commandline.createClasspath(getProject()).add(addSlaveClasspath());
    commandline.setClassname(SlaveMainSafe.class.getName());
    if (slave.slaves == 1) {
      commandline.createArgument().setValue(SlaveMain.OPTION_FREQUENT_FLUSH);
    }

    // Set up full output files.
    File sysoutFile = tempFile(uniqueSeed,
        "junit4-J" + slave.id, ".sysout", getTempDir());
    File syserrFile = tempFile(uniqueSeed,
        "junit4-J" + slave.id, ".syserr", getTempDir());

    // Set up communication channel.
    File eventFile = tempFile(uniqueSeed,
        "junit4-J" + slave.id, ".events", getTempDir());
    temporaryFiles.add(eventFile);
    commandline.createArgument().setValue(SlaveMain.OPTION_EVENTSFILE);
    commandline.createArgument().setFile(eventFile);
    
    if (sysouts) {
      commandline.createArgument().setValue(SlaveMain.OPTION_SYSOUTS);
    }

    InputStream eventStream = new TailInputStream(eventFile);

    // Set up input suites file.
    commandline.createArgument().setValue("@" + classNamesFile.getAbsolutePath());

    // Emit command line before -stdin to avoid confusion.
    slave.slaveCommandLine = escapeAndJoin(commandline.getCommandline());
    log("Forked JVM process command line (may need escape sequences for your shell):\n" + 
        slave.slaveCommandLine, Project.MSG_VERBOSE);

    commandline.createArgument().setValue(SlaveMain.OPTION_STDIN);

    final EventBus eventBus = new EventBus("slave-" + slave.id);
    final DiagnosticsListener diagnosticsListener = new DiagnosticsListener(slave, this);
    eventBus.register(diagnosticsListener);
    eventBus.register(new AggregatingListener(aggregatedBus, slave));

    final AtomicReference<Charset> clientCharset = new AtomicReference<Charset>();
    final AtomicBoolean clientWithLimitedCharset = new AtomicBoolean(); 
    final PrintWriter w = new PrintWriter(Files.newWriter(classNamesDynamic, Charsets.UTF_8));
    eventBus.register(new Object() {
      @SuppressWarnings("unused")
      @Subscribe
      public void onIdleSlave(final SlaveIdle idleSlave) {
        aggregatedBus.post(new SlaveIdle() {
          @Override
          public void finished() {
            idleSlave.finished();
          }

          @Override
          public void newSuite(String suiteName) {
            if (!clientCharset.get().newEncoder().canEncode(suiteName)) {
              clientWithLimitedCharset.set(true);
              log("Forked JVM J" + slave.id + " skipped suite (cannot encode suite name in charset " +
                  clientCharset.get() + "): " + suiteName, Project.MSG_WARN);
              return;
            }

            log("Forked JVM J" + slave.id + " stole suite: " + suiteName, Project.MSG_VERBOSE);
            w.println(suiteName);
            idleSlave.newSuite(suiteName);
          }
        });
      }

      @SuppressWarnings("unused")
      @Subscribe
      public void onBootstrap(final BootstrapEvent e) {
        Charset cs = Charset.forName(((BootstrapEvent) e).getDefaultCharsetName());
        clientCharset.set(cs);

        slave.start = System.currentTimeMillis();
        slave.setBootstrapEvent(e);
        aggregatedBus.post(new ChildBootstrap(slave));
      }

      @SuppressWarnings("unused")
      @Subscribe
      public void receiveQuit(QuitEvent e) {
        slave.end = System.currentTimeMillis();
      }      
    });

    OutputStream sysout = new BufferedOutputStream(new FileOutputStream(sysoutFile));
    OutputStream syserr = new BufferedOutputStream(new FileOutputStream(syserrFile));
    Exception error = null;
    RandomAccessFile streamsBuffer = new RandomAccessFile(streamsBufferFile, "rw");
    try {
      Execute execute = forkProcess(slave, eventBus, commandline, eventStream, sysout, syserr, streamsBuffer);
      log("Forked JVM J" + slave.id + " finished with exit code: " + execute.getExitValue(), Project.MSG_DEBUG);

      if (execute.isFailure()) {
        final int exitStatus = execute.getExitValue();
        switch (exitStatus) {
          case SlaveMain.ERR_NO_JUNIT:
            throw new BuildException("Forked JVM's classpath must include a junit4 JAR.");
          case SlaveMain.ERR_OLD_JUNIT:
            throw new BuildException("Forked JVM's classpath must use JUnit 4.10 or newer.");
          default:
            Closeables.closeQuietly(sysout);
            Closeables.closeQuietly(syserr);
            
            StringBuilder message = new StringBuilder();
            if (exitStatus == SlaveMain.ERR_OOM) {
              message.append("Forked JVM ran out of memory.");
            } else {
              message.append("Forked process returned with error code: ").append(exitStatus).append(".");
            }

            if (sysoutFile.length() > 0 || syserrFile.length() > 0) {
              if (exitStatus != SlaveMain.ERR_OOM) {
                message.append(" Very likely a JVM crash. ");
              }

              if (jvmOutputAction.contains(JvmOutputAction.PIPE)) {
                message.append(" Process output piped in logs above.");
              } else if (!jvmOutputAction.contains(JvmOutputAction.IGNORE)) {
                if (sysoutFile.length() > 0) {
                  message.append(" See process stdout at: " + sysoutFile.getAbsolutePath());
                }
                if (syserrFile.length() > 0) {
                  message.append(" See process stderr at: " + syserrFile.getAbsolutePath());
                }
              }
            }
            throw new BuildException(message.toString());
        }
      }
    } catch (Exception e) {
      error = e;
    } finally {
      Closeables.closeQuietly(eventStream);
      Closeables.closeQuietly(w);
      Closeables.closeQuietly(sysout);
      Closeables.closeQuietly(syserr);
      Closeables.closeQuietly(streamsBuffer);
      Files.copy(classNamesDynamic, Files.newOutputStreamSupplier(classNamesFile, true));
      classNamesDynamic.delete();
      streamsBufferFile.delete();
    }

    // Check sysout/syserr lengths.
    checkJvmOutput(sysoutFile, slave, "stdout");
    checkJvmOutput(syserrFile, slave, "stderr");

    if (error != null) {
      throw error;
    }

    if (!diagnosticsListener.quitReceived()) {
      throw new BuildException("Quit event not received from a slave process? This may indicate JVM crash or runner bugs.");
    }

    if (clientWithLimitedCharset.get() && dynamicAssignmentRatio > 0) {
      throw new BuildException("Forked JVM J" + slave.id + " was not be able to decode class names when using" +
          " charset: " + clientCharset + ". Do not use " +
          "dynamic suite balancing to work around this problem (-DdynamicAssignmentRatio=0).");
    }
  }

  private void checkJvmOutput(File file, SlaveInfo slave, String fileName) {
    if (file.length() > 0) {
      String message = "JVM J" + slave.id + ": " + fileName + " was not empty, see: " + file;
      if (jvmOutputAction.contains(JvmOutputAction.WARN)) {
        log(message, Project.MSG_WARN);
      }
      if (jvmOutputAction.contains(JvmOutputAction.PIPE)) {
        log(">>> JVM J" + slave.id + ": " + fileName + " (verbatim) ----", Project.MSG_INFO);
        try {
          // If file > 10 mb, stream directly. Otherwise use the logger.
          if (file.length() < 10 * (1024 * 1024)) {
            // Append to logger.
            log(Files.toString(file, slave.getCharset()), Project.MSG_INFO);
          } else {
            // Stream directly.
            CharStreams.copy(Files.newReader(file, slave.getCharset()), System.out);
          }
        } catch (IOException e) {
          log("Couldn't pipe file " + file + ": " + e.toString(), Project.MSG_INFO);
        }
        log("<<< JVM J" + slave.id + ": EOF ----", Project.MSG_INFO);
      }
      if (jvmOutputAction.contains(JvmOutputAction.IGNORE)) {
        file.delete();
      }
      if (jvmOutputAction.contains(JvmOutputAction.FAIL)) {
        throw new BuildException(message);
      }
      return;
    }
    file.delete();
  }

  private File tempFile(String uniqueSeed, String base, String suffix, File tempDir) throws IOException {
    int retry = 0;
    File finalName;
    do {
      if (retry > 0) {
        finalName = new File(tempDir, base + "-" + uniqueSeed + "_retry" + retry + suffix);
      } else {
        finalName = new File(tempDir, base + "-" + uniqueSeed + suffix);
      }
    } while (!finalName.createNewFile() && retry++ < 5);
    return finalName;
  }

  /**
   * Try to provide an escaped, ready-to-use shell line to repeat a given command line.
   */
  private String escapeAndJoin(String[] commandline) {
    // TODO: we should try to escape special characters here, depending on the OS.
    StringBuilder b = new StringBuilder();
    Pattern specials = Pattern.compile("[\\ ]");
    for (String arg : commandline) {
      if (b.length() > 0) {
        b.append(" ");
      }

      if (specials.matcher(arg).find()) {
        b.append('"').append(arg).append('"');
      } else {
        b.append(arg);
      }
    }
    return b.toString();
  }

  /**
   * Execute a slave process. Pump events to the given event bus.
   */
  private Execute forkProcess(SlaveInfo slaveInfo, EventBus eventBus, 
      CommandlineJava commandline, 
      InputStream eventStream, OutputStream sysout, OutputStream syserr, RandomAccessFile streamsBuffer) {
    try {
      final LocalSlaveStreamHandler streamHandler = 
          new LocalSlaveStreamHandler(
              eventBus, testsClassLoader, System.err, eventStream, 
              sysout, syserr, heartbeat, streamsBuffer);

      // Add certain properties to allow identification of the forked JVM from within
      // the subprocess. This can be used for policy files etc.
      final File cwd = getWorkingDirectory(slaveInfo);

      Variable v = new Variable();
      v.setKey(CHILDVM_SYSPROP_CWD);
      v.setFile(cwd.getAbsoluteFile());
      commandline.addSysproperty(v);

      v = new Variable();
      v.setKey(CHILDVM_SYSPROP_ID);
      v.setValue(Integer.toString(slaveInfo.id));
      commandline.addSysproperty(v);

      final Execute execute = new Execute();
      execute.setCommandline(commandline.getCommandline());
      execute.setVMLauncher(true);
      execute.setWorkingDirectory(cwd);
      execute.setStreamHandler(streamHandler);
      execute.setNewenvironment(newEnvironment);
      if (env.getVariables() != null)
        execute.setEnvironment(env.getVariables());
      log("Starting JVM J" + slaveInfo.id, Project.MSG_DEBUG);
      execute.execute();
      return execute;
    } catch (IOException e) {
      throw new BuildException("Could not execute slave process. Run ant with -verbose to get" +
      		" the execution details.", e);
    }
  }

  private File getWorkingDirectory(SlaveInfo slaveInfo) {
    File baseDir = (dir == null ? getProject().getBaseDir() : dir);
    final File slaveDir;
    if (isolateWorkingDirectories) {
      slaveDir = new File(baseDir, "J" + slaveInfo.id);
      slaveDir.mkdirs();
      temporaryFiles.add(slaveDir);
    } else {
      slaveDir = baseDir;
    }
    return slaveDir;
  }

  /**
   * Resolve temporary folder.
   */
  private File getTempDir() {
    if (this.tempDir == null) {
      if (this.dir != null) {
        this.tempDir = dir;
      } else {
        this.tempDir = getProject().getBaseDir();
      }
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
    boolean javaSourceWarn = false;
    while (iter.hasNext()) {
      final Resource r = iter.next();
      if (!r.isExists()) 
        throw new BuildException("Test class resource does not exist?: " + r.getName());

      try {
        if (r.getName().endsWith(".java")) {
          String pathname = r.getName();
          String className = pathname.substring(0, pathname.length() - ".java".length());
          className = className
            .replace(File.separatorChar, '.')
            .replace('/', '.')
            .replace('\\', '.');
          testClassNames.add(className);
          
          if (!javaSourceWarn) {
            log("Source (.java) files used for naming source suites. This is discouraged, " +
            		"use a resource collection pointing to .class files instead.", Project.MSG_INFO);
            javaSourceWarn = true;
          }
        } else {
          // Assume .class file.
          InputStream is = r.getInputStream();
          if (!is.markSupported()) {
            is = new BufferedInputStream(is);          
          }
  
          try {
            is.mark(4);
            if (is.read() != 0xca ||
                is.read() != 0xfe ||
                is.read() != 0xba ||
                is.read() != 0xbe) {
              throw new BuildException("File does not start with a class magic 0xcafebabe: "
                  + r.getName() + ", " + r.getLocation());
            }
            is.reset();
  
            ClassReader reader = new ClassReader(is);
            String className = reader.getClassName().replace('/', '.');
            log("Test class parsed: " + r.getName() + " as " 
                + reader.getClassName(), Project.MSG_DEBUG);
            testClassNames.add(className);
          } finally {
            is.close();
          }
        }
      } catch (IOException e) {
        throw new BuildException("Could not read or parse as Java class: "
            + r.getName() + ", " + r.getLocation());
      }
    }

    String testClassFilter = Strings.emptyToNull(getProject().getProperty(SYSPROP_TESTCLASS()));
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
        Gson.class.getName(),
        TeeOutputStream.class.getName()
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
