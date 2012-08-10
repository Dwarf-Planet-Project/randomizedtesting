package com.carrotsearch.ant.tasks.junit4.slave;

import java.io.*;
import java.util.*;

import org.junit.runner.*;
import org.junit.runner.manipulation.Filter;
import org.junit.runner.manipulation.NoTestsRemainException;
import org.junit.runner.notification.*;

import com.carrotsearch.ant.tasks.junit4.events.*;
import com.carrotsearch.randomizedtesting.MethodGlobFilter;
import com.carrotsearch.randomizedtesting.SysGlobals;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;

/**
 * A slave process running the actual tests on the target JVM.
 */
public class SlaveMain {
  /** Runtime exception. */
  public static final int ERR_EXCEPTION = 240;

  /** No JUnit on classpath. */
  public static final int ERR_NO_JUNIT = 239;

  /** Old JUnit on classpath. */
  public static final int ERR_OLD_JUNIT = 238;

  /**
   * Frequent event strean flushing.
   */
  public static final String OPTION_FREQUENT_FLUSH = "-flush";

  /**
   * Multiplex sysout and syserr to original streams (aside from
   * pumping them to event stream).
   */
  public static final String OPTION_SYSOUTS = "-sysouts";

  /**
   * Read class names from standard input.
   */
  public static final String OPTION_STDIN = "-stdin";

  /**
   * Name the sink for events. If given, accepts one argument - name of a file
   * to which events should be dumped. The file has to be initially empty!
   */
  public static final String OPTION_EVENTSFILE = "-eventsfile";

  /**
   * Fire a runner failure after startup to verify messages
   * are propagated properly. Not really useful in practice...
   */
  public static final String SYSPROP_FIRERUNNERFAILURE =
      SlaveMain.class.getName() + ".fireRunnerFailure";

  /**
   * Event sink.
   */
  private final Serializer serializer;

  /** A sink for warnings (non-event stream). */
  private static PrintStream warnings;

  /** Flush serialization stream frequently. */
  private boolean flushFrequently = false;
  
  /** 
   * Multiplex calls to System streams to both event stream
   * and the original streams?
   */
  private static boolean multiplexStdStreams = false;

  /**
   * Base for redirected streams. 
   */
  private static class ChunkedStream extends OutputStream {
    public void write(int b) throws IOException {
      throw new IOException("Only buffered write(byte[],int,int) calls expected from super stream.");
    }
    
    @Override
    public void close() throws IOException {
      throw new IOException("Not supposed to be called on redirected streams.");
    }
  }

  /**
   * Creates a slave emitting events to the given serializer.
   */
  public SlaveMain(Serializer serializer) {
    this.serializer = serializer;
  }

  /**
   * Execute tests.
   */
  private void execute(Iterator<String> classNames) {
    final RunNotifier fNotifier = new OrderedRunNotifier();
    final Result result = new Result();

    fNotifier.addListener(result.createListener());

    fNotifier.addListener(
        new StreamFlusherDecorator(
            new NoExceptionRunListenerDecorator(new RunListenerEmitter(serializer)) {
              @Override
              protected void exception(Throwable t) {
                warn("Event serializer exception.", t);
              }
            }));

    fNotifier.addListener(new RunListener() {
      public void testRunFinished(Result result) throws Exception {
        serializer.flush();
      }

      @Override
      public void testRunStarted(Description description) throws Exception {
        serializer.flush();
      }
      
      @Override
      public void testStarted(Description description) throws Exception {
        serializer.flush();
      }
      
      public void testFinished(Description description) throws Exception {
        serializer.flush();
      }
    });

    /*
     * Instantiate method filter if any.
     */
    String methodFilterGlob = Strings.emptyToNull(System.getProperty(SysGlobals.SYSPROP_TESTMETHOD()));
    Filter methodFilter = Filter.ALL;
    if (methodFilterGlob != null) {
      methodFilter = new MethodGlobFilter(methodFilterGlob);
    }

    /*
     * Important. Run each class separately so that we get separate 
     * {@link RunListener} callbacks for the top extracted description.
     */
    while (classNames.hasNext()) {
      Class<?> clazz = instantiate(classNames.next());
      if (clazz == null) 
        continue;

      Request request = Request.aClass(clazz);
      try {
        Runner runner = request.getRunner();
        methodFilter.apply(runner);

        fNotifier.fireTestRunStarted(runner.getDescription());
        runner.run(fNotifier);
        fNotifier.fireTestRunFinished(result);
      } catch (NoTestsRemainException e) {
        // Don't complain if all methods have been filtered out. 
        // I don't understand the reason why this exception has been
        // built in to filters at all.
      }
    }
  }

  /**
   * Instantiate test classes (or try to).
   */
  private Class<?> instantiate(String className) {
    try {
      return Class.forName(className, false, Thread.currentThread().getContextClassLoader());
    } catch (Throwable t) {
      try {
        serializer.serialize(
            new SuiteFailureEvent(
                new Failure(Description.createSuiteDescription(className), t)));
        if (flushFrequently)
          serializer.flush();
      } catch (Exception e) {
        warn("Could not report failure back to master.", t);
      }
      return null;
    }
  }

  /**
   * Console entry point.
   */
  public static void main(String[] allArgs) {
    int exitStatus = 0;

    Serializer serializer = null;
    try {
      final ArrayDeque<String> args = new ArrayDeque<String>(Arrays.asList(allArgs));

      // Options.
      boolean flushFrequently = false;
      File  eventsFile = null;
      boolean suitesOnStdin = false;
      List<String> testClasses = Lists.newArrayList();

      while (!args.isEmpty()) {
        String option = args.pop();
        if (option.equals(OPTION_FREQUENT_FLUSH)) {
          flushFrequently = true;
        } else if (option.equals(OPTION_STDIN)) {
          suitesOnStdin = true;
        } else if (option.equals(OPTION_SYSOUTS)) {
          multiplexStdStreams = true;
        } else if (option.equals(OPTION_EVENTSFILE)) {
          eventsFile = new File(args.pop());
          if (eventsFile.isFile() && eventsFile.length() > 0) {
            RandomAccessFile raf = new RandomAccessFile(eventsFile, "rw");
            raf.setLength(0);
            raf.close();
          }
        } else if (option.startsWith("@")) {
          // Append arguments file, one line per option.
          args.addAll(Arrays.asList(readArgsFile(option.substring(1))));
        } else {
          // The default expectation is a test class.
          testClasses.add(option);
        }
      }

      // Set up events channel and events serializer.
      if (eventsFile == null) {
        throw new IOException("You must specify communication channel for events.");
      }
      final OutputStream eventsChannel = new RandomAccessFileOutputStream(eventsFile);

      // Send bootstrap package.
      final int bufferSize = 8 * 1024;
      serializer = new Serializer(new BufferedOutputStream(eventsChannel, bufferSize))
        .serialize(new BootstrapEvent())
        .flush();

      // Redirect original streams and start running tests.
      redirectStreams(serializer, flushFrequently);

      final SlaveMain main = new SlaveMain(serializer);
      main.flushFrequently = flushFrequently;

      final Iterator<String> stdInput;
      if (suitesOnStdin) { 
        stdInput = new StdInLineIterator(main.serializer);
      } else {
        stdInput = Collections.<String>emptyList().iterator();
      }
      main.execute(Iterators.concat(testClasses.iterator(), stdInput));

      // For unhandled exceptions tests.
      if (System.getProperty(SYSPROP_FIRERUNNERFAILURE) != null) {
        throw new Exception(System.getProperty(SYSPROP_FIRERUNNERFAILURE));
      }
    } catch (Throwable t) {
      warn("Exception at main loop level.", t);
      exitStatus = ERR_EXCEPTION;
    }

    try {
      if (serializer != null) {
        try {
          serializer.close();
        } catch (Throwable t) {
          warn("Exception closing serializer.", t);
        }
      }
    } finally {
      System.exit(exitStatus);
    }
  }

  /**
   * Read arguments from a file. Newline delimited, UTF-8 encoded. No fanciness to 
   * avoid dependencies.
   */
  private static String[] readArgsFile(String argsFile) throws IOException {
    final ArrayList<String> lines = new ArrayList<String>();
    final BufferedReader reader = new BufferedReader(
        new InputStreamReader(
            new FileInputStream(argsFile), "UTF-8"));
    try {
      String line;
      while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (!line.isEmpty() && !line.startsWith("#")) {
          lines.add(line);
        }
      }
    } finally {
      reader.close();
    }
    return lines.toArray(new String [lines.size()]);
  }

  /**
   * Redirect standard streams so that the output can be passed to listeners.
   */
  private static void redirectStreams(final Serializer serializer, final boolean flushFrequently) {
    final PrintStream origSysOut = System.out;
    final PrintStream origSysErr = System.err;

    // Set warnings stream to System.err.
    warnings = System.err;
    
    System.setOut(new PrintStream(new BufferedOutputStream(new ChunkedStream() {
      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        if (multiplexStdStreams) {
          origSysOut.write(b, off, len);
        }
        serializer.serialize(new AppendStdOutEvent(b, off, len));
        if (flushFrequently) serializer.flush();
      }
    })));

    System.setErr(new PrintStream(new BufferedOutputStream(new ChunkedStream() {
      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        if (multiplexStdStreams) {
          origSysErr.write(b, off, len);
        }
        serializer.serialize(new AppendStdErrEvent(b, off, len));
        if (flushFrequently) serializer.flush();
      }
    })));
  }

  /**
   * Warning emitter. Uses whatever alternative non-event communication channel is.
   */
  public static void warn(String string, Throwable t) {
    try {
      PrintStream w = (warnings == null ? System.err : warnings);
      if (t != null) {
        w.println("WARN: " + string + " -> " + Throwables.getStackTraceAsString(t));
      } else {
        w.println("WARN: " + string);
      }
      w.flush();
    } catch (Throwable t2) {
      // Can't do anything, really.
    }
  }
}
