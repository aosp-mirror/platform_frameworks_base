/* //device/java/android/com/android/tests/TestHandler.java
**
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License"); 
** you may not use this file except in compliance with the License. 
** You may obtain a copy of the License at 
**
**     http://www.apache.org/licenses/LICENSE-2.0 
**
** Unless required by applicable law or agreed to in writing, software 
** distributed under the License is distributed on an "AS IS" BASIS, 
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
** See the License for the specific language governing permissions and 
** limitations under the License.
*/

package android.core;

import com.android.internal.os.HandlerHelper;
import android.os.HandlerInterface;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;

/**
 * Naive class that implements a getNextMessage()
 * by running a Handler in a new thread. <p>
 * <p/>
 * This class blocks the Handler thread when the getNextMessage() thread
 * is not in getNextMessage(). This allows the getNextMessage() thread to
 * inspect state that is otherwise unguarded and would otherwise be prone to
 * race conditions.<p>
 * <p/>
 * Please note that both threads are allowed to run unsynchronized until
 * the first message is posted to this handler.
 * <p/>
 * Please call hh.looper.quit() when done to clean this up
 */
public class TestHandler implements Runnable, HandlerInterface {
    //***** Instance Variables

    public HandlerHelper hh;
    public Looper looper;

    Runnable setupRoutine;
    Message nextMessage;
    long failTimeoutMillis;
    boolean waitBeforeReturning = true;

    //***** Class Methods

    public static TestHandler create() {
        return create("TestHandler", null);
    }

    public static TestHandler create(String name) {
        return create(name, null);
    }

    public static TestHandler create(String name, Runnable doSetup) {
        TestHandler ret;

        ret = new TestHandler();

        ret.setupRoutine = doSetup;

        synchronized (ret) {
            new Thread(ret, name).start();
            while (ret.looper == null) {
                try {
                    ret.wait();
                } catch (InterruptedException ex) {
                }
            }
        }

        return ret;
    }

    //***** Public Methods

    /**
     * Maximum time to wait for a message before failing
     * by throwing exception
     */
    public void setFailTimeoutMillis(long msec) {
        failTimeoutMillis = msec;
    }

    /**
     * Waits for the next message to be sent to this handler and returns it.
     * Blocks the Handler's looper thread until another call to getNextMessage()
     * is made
     */

    public Message getNextMessage() {
        Message ret;

        synchronized (this) {
            long time = SystemClock.uptimeMillis();

            waitBeforeReturning = false;
            this.notifyAll();

            try {
                while (nextMessage == null) {
                    if (failTimeoutMillis > 0
                            && ((SystemClock.uptimeMillis() - time)
                            > failTimeoutMillis)) {
                        throw new RuntimeException("Timeout exceeded exceeded");
                    }

                    try {
                        this.wait(failTimeoutMillis);
                    } catch (InterruptedException ex) {
                    }
                }
                ret = nextMessage;
                nextMessage = null;
            } finally {
                waitBeforeReturning = true;
            }
        }

        return ret;
    }

    //***** Overridden from Runnable

    public void run() {
        Looper.prepare();
        hh = new HandlerHelper(this);

        if (setupRoutine != null) {
            setupRoutine.run();
        }

        synchronized (this) {
            looper = Looper.myLooper();
            this.notify();
        }

        Looper.loop();
    }

    //***** HandlerHelper implementation

    public void handleMessage(Message msg) {
        synchronized (this) {
            while (nextMessage != null) {
                try {
                    this.wait();
                } catch (InterruptedException ex) {
                }
            }

            // msg will be recycled when this method returns.
            // so we need to make a copy of it.
            nextMessage = Message.obtain();
            nextMessage.copyFrom(msg);
            this.notifyAll();

            while (waitBeforeReturning) {
                try {
                    this.wait();
                } catch (InterruptedException ex) {
                }
            }
        }
    }
}


