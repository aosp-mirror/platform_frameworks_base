/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.os;

import android.util.Log;
import android.util.Printer;
import android.util.PrefixPrinter;

/**
  * Class used to run a message loop for a thread.  Threads by default do
  * not have a message loop associated with them; to create one, call
  * {@link #prepare} in the thread that is to run the loop, and then
  * {@link #loop} to have it process messages until the loop is stopped.
  * 
  * <p>Most interaction with a message loop is through the
  * {@link Handler} class.
  * 
  * <p>This is a typical example of the implementation of a Looper thread,
  * using the separation of {@link #prepare} and {@link #loop} to create an
  * initial Handler to communicate with the Looper.
  *
  * <pre>
  *  class LooperThread extends Thread {
  *      public Handler mHandler;
  *
  *      public void run() {
  *          Looper.prepare();
  *
  *          mHandler = new Handler() {
  *              public void handleMessage(Message msg) {
  *                  // process incoming messages here
  *              }
  *          };
  *
  *          Looper.loop();
  *      }
  *  }</pre>
  */
public class Looper {
    private static final String TAG = "Looper";

    // sThreadLocal.get() will return null unless you've called prepare().
    private static final ThreadLocal<Looper> sThreadLocal = new ThreadLocal<Looper>();

    final MessageQueue mQueue;
    final Thread mThread;
    volatile boolean mRun;

    private Printer mLogging = null;
    private static Looper mMainLooper = null;  // guarded by Looper.class

     /** Initialize the current thread as a looper.
      * This gives you a chance to create handlers that then reference
      * this looper, before actually starting the loop. Be sure to call
      * {@link #loop()} after calling this method, and end it by calling
      * {@link #quit()}.
      */
    public static void prepare() {
        if (sThreadLocal.get() != null) {
            throw new RuntimeException("Only one Looper may be created per thread");
        }
        sThreadLocal.set(new Looper());
    }

    /**
     * Initialize the current thread as a looper, marking it as an
     * application's main looper. The main looper for your application
     * is created by the Android environment, so you should never need
     * to call this function yourself.  See also: {@link #prepare()}
     */
    public static void prepareMainLooper() {
        prepare();
        setMainLooper(myLooper());
        myLooper().mQueue.mQuitAllowed = false;
    }

    private synchronized static void setMainLooper(Looper looper) {
        mMainLooper = looper;
    }

    /** Returns the application's main looper, which lives in the main thread of the application.
     */
    public synchronized static Looper getMainLooper() {
        return mMainLooper;
    }

    /**
     * Run the message queue in this thread. Be sure to call
     * {@link #quit()} to end the loop.
     */
    public static void loop() {
        Looper me = myLooper();
        if (me == null) {
            throw new RuntimeException("No Looper; Looper.prepare() wasn't called on this thread.");
        }
        MessageQueue queue = me.mQueue;
        
        // Make sure the identity of this thread is that of the local process,
        // and keep track of what that identity token actually is.
        Binder.clearCallingIdentity();
        final long ident = Binder.clearCallingIdentity();
        
        while (true) {
            Message msg = queue.next(); // might block
            if (msg != null) {
                if (msg.target == null) {
                    // No target is a magic identifier for the quit message.
                    return;
                }

                long wallStart = 0;
                long threadStart = 0;

                // This must be in a local variable, in case a UI event sets the logger
                Printer logging = me.mLogging;
                if (logging != null) {
                    logging.println(">>>>> Dispatching to " + msg.target + " " +
                            msg.callback + ": " + msg.what);
                    wallStart = SystemClock.currentTimeMicro();
                    threadStart = SystemClock.currentThreadTimeMicro();
                }

                msg.target.dispatchMessage(msg);

                if (logging != null) {
                    long wallTime = SystemClock.currentTimeMicro() - wallStart;
                    long threadTime = SystemClock.currentThreadTimeMicro() - threadStart;

                    logging.println("<<<<< Finished to " + msg.target + " " + msg.callback);
                    if (logging instanceof Profiler) {
                        ((Profiler) logging).profile(msg, wallStart, wallTime,
                                threadStart, threadTime);
                    }
                }

                // Make sure that during the course of dispatching the
                // identity of the thread wasn't corrupted.
                final long newIdent = Binder.clearCallingIdentity();
                if (ident != newIdent) {
                    Log.wtf(TAG, "Thread identity changed from 0x"
                            + Long.toHexString(ident) + " to 0x"
                            + Long.toHexString(newIdent) + " while dispatching to "
                            + msg.target.getClass().getName() + " "
                            + msg.callback + " what=" + msg.what);
                }
                
                msg.recycle();
            }
        }
    }

    /**
     * Return the Looper object associated with the current thread.  Returns
     * null if the calling thread is not associated with a Looper.
     */
    public static Looper myLooper() {
        return sThreadLocal.get();
    }

    /**
     * Control logging of messages as they are processed by this Looper.  If
     * enabled, a log message will be written to <var>printer</var> 
     * at the beginning and ending of each message dispatch, identifying the
     * target Handler and message contents.
     * 
     * @param printer A Printer object that will receive log messages, or
     * null to disable message logging.
     */
    public void setMessageLogging(Printer printer) {
        mLogging = printer;
    }
    
    /**
     * Return the {@link MessageQueue} object associated with the current
     * thread.  This must be called from a thread running a Looper, or a
     * NullPointerException will be thrown.
     */
    public static MessageQueue myQueue() {
        return myLooper().mQueue;
    }

    private Looper() {
        mQueue = new MessageQueue();
        mRun = true;
        mThread = Thread.currentThread();
    }

    public void quit() {
        Message msg = Message.obtain();
        // NOTE: By enqueueing directly into the message queue, the
        // message is left with a null target.  This is how we know it is
        // a quit message.
        mQueue.enqueueMessage(msg, 0);
    }

    /**
     * Return the Thread associated with this Looper.
     */
    public Thread getThread() {
        return mThread;
    }

    /** @hide */
    public MessageQueue getQueue() {
        return mQueue;
    }

    public void dump(Printer pw, String prefix) {
        pw = PrefixPrinter.create(pw, prefix);
        pw.println(this.toString());
        pw.println("mRun=" + mRun);
        pw.println("mThread=" + mThread);
        pw.println("mQueue=" + ((mQueue != null) ? mQueue : "(null"));
        if (mQueue != null) {
            synchronized (mQueue) {
                long now = SystemClock.uptimeMillis();
                Message msg = mQueue.mMessages;
                int n = 0;
                while (msg != null) {
                    pw.println("  Message " + n + ": " + msg.toString(now));
                    n++;
                    msg = msg.next;
                }
                pw.println("(Total messages: " + n + ")");
            }
        }
    }

    public String toString() {
        return "Looper{" + Integer.toHexString(System.identityHashCode(this)) + "}";
    }

    /**
     * @hide
     */
    public static interface Profiler {
        void profile(Message message, long wallStart, long wallTime,
                long threadStart, long threadTime);
    }
}
