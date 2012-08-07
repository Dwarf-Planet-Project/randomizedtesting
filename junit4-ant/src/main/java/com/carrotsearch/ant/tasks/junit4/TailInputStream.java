package com.carrotsearch.ant.tasks.junit4;

import java.io.*;

/**
 * An input stream that tails from a random access file as new input appears there.
 * It's a lousy solution but we don't have access to real interprocess pipes from Java.
 */
class TailInputStream extends InputStream {
  /** How long to sleep (millis) before checking for updates? */
  private static final long TAIL_CHECK_DELAY = 250;

  private RandomAccessFile raf;

  private volatile boolean closed;

  public TailInputStream(File file) throws FileNotFoundException {
    this.raf = new RandomAccessFile(file, "r");
  }

  @Override
  public int read() throws IOException {
    if (closed) return -1;

    try {
      int c;
      while ((c = raf.read()) == -1) {
        try {
          Thread.sleep(TAIL_CHECK_DELAY);
        } catch (InterruptedException e) {
          throw new IOException(e);
        }
      }
      return c;
    } catch (IOException e) {
      if (closed) 
        return -1;
      else
        throw e;
    }
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    if (closed) return -1;

    if (b == null) {
      throw new NullPointerException();
    } else if (off < 0 || len < 0 || len > b.length - off) {
      throw new IndexOutOfBoundsException();
    } else if (len == 0) {
      return 0;
    }

    try {
      int bytesRead = 0;
      for (; len > 0;) {
        int rafRead = raf.read(b, off, len);
        if (rafRead == -1) {
          if (bytesRead == 0) {
            // If nothing in the buffer, wait.
            do {
              try {
                Thread.sleep(TAIL_CHECK_DELAY);
              } catch (InterruptedException e) {
                throw new IOException(e);
              }
            } while ((rafRead = raf.read(b, off, len)) == -1);
          } else {
            // otherwise return what's been read so far without blocking.
            return bytesRead;
          }
        }
  
        bytesRead += rafRead;
        off += bytesRead;
        len -= bytesRead;
      }
      return bytesRead;
    } catch (IOException e) {
      if (closed) 
        return -1;
      else
        throw e;
    }
  }

  @Override
  public int read(byte[] b) throws IOException {
    return read(b, 0, b.length);
  }
  
  @Override
  public boolean markSupported() {
    return false;
  }

  @Override
  public void close() throws IOException {
    closed = true;
    this.raf.close();
  }
}
