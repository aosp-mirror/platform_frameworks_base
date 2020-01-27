/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server;

import static org.junit.Assert.assertEquals;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Unit tests for {@link WatchdogDiagnostics}
 */
@RunWith(AndroidJUnit4.class)
public class WatchdogDiagnosticsTest {

    private static class TestThread1 extends Thread {
        Object lock1;
        Object lock2;
        volatile boolean inB = false;

        public TestThread1(Object lock1, Object lock2) {
            super("TestThread1");
            this.lock1 = lock1;
            this.lock2 = lock2;
        }

        @Override
        public void run() {
            a();
        }

        private void a() {
            synchronized(lock1) {
                b();
            }
        }

        private void b() {
            inB = true;
            synchronized(lock2) {
                // Nothing.
            }
        }
    }

    private static class TestThread2 extends Thread {
        Object lock1;
        Object lock2;
        volatile boolean inY = false;

        public TestThread2(Object lock1, Object lock2) {
            super("TestThread2");
            this.lock1 = lock1;
            this.lock2 = lock2;
        }

        @Override
        public void run() {
            x();
        }

        private void x() {
            synchronized(lock1) {
                y();
            }
        }

        private void y() {
            synchronized(lock2) {
                inY = true;
                try {
                    lock2.wait();
                } catch (Exception exc) {
                    throw new RuntimeException(exc);
                }
            }
        }
    }

    @Test
    public void printAnnotatedStack() throws Exception {
        // Preparation.

        Object heldLock1 = new Object();
        Object heldLock2 = 0;
        Object waitLock = "123";

        TestThread1 thread1 = new TestThread1(heldLock1, heldLock2);
        TestThread2 thread2 = new TestThread2(heldLock2, waitLock);

        // Start the second thread, ensure it grabs heldLock2.
        thread2.start();
        while(!thread2.inY) {
            Thread.yield();
        }

        // Start the first thread, ensure it made progress.
        thread1.start();
        while(!thread1.inB) {
            Thread.yield();
        }

        // Now wait till both are no longer in runnable state.
        while (thread1.getState() == Thread.State.RUNNABLE) {
            Thread.yield();
        }
        while (thread2.getState() == Thread.State.RUNNABLE) {
            Thread.yield();
        }

        // Now do the test.
        StringWriter stringBuffer = new StringWriter();
        PrintWriter print = new PrintWriter(stringBuffer, true);

        {
            WatchdogDiagnostics.printAnnotatedStack(thread1, print);

            String output = stringBuffer.toString();
            String expected =
                    "TestThread1 annotated stack trace:\n" +
                    "    at com.android.server.WatchdogDiagnosticsTest$TestThread1.b(" +
                            "WatchdogDiagnosticsTest.java:59)\n" +
                    "    - waiting to lock <HASH> (a java.lang.Integer)\n" +
                    "    at com.android.server.WatchdogDiagnosticsTest$TestThread1.a(" +
                            "WatchdogDiagnosticsTest.java:53)\n" +
                    "    - locked <HASH> (a java.lang.Object)\n" +
                    "    at com.android.server.WatchdogDiagnosticsTest$TestThread1.run(" +
                            "WatchdogDiagnosticsTest.java:48)\n";
            assertEquals(expected, filterHashes(output));
        }

        stringBuffer.getBuffer().setLength(0);

        {
            WatchdogDiagnostics.printAnnotatedStack(thread2, print);

            String output = stringBuffer.toString();
            String expected =
                    "TestThread2 annotated stack trace:\n" +
                    "    at java.lang.Object.wait(Native Method)\n" +
                    "    at java.lang.Object.wait(Object.java:442)\n" +
                    "    at java.lang.Object.wait(Object.java:568)\n" +
                    "    at com.android.server.WatchdogDiagnosticsTest$TestThread2.y(" +
                            "WatchdogDiagnosticsTest.java:91)\n" +
                    "    - locked <HASH> (a java.lang.String)\n" +
                    "    at com.android.server.WatchdogDiagnosticsTest$TestThread2.x(" +
                            "WatchdogDiagnosticsTest.java:83)\n" +
                    "    - locked <HASH> (a java.lang.Integer)\n" +
                    "    at com.android.server.WatchdogDiagnosticsTest$TestThread2.run(" +
                            "WatchdogDiagnosticsTest.java:78)\n";
            assertEquals(expected, filterHashes(output));
        }

        // Let the threads finish.
        synchronized (waitLock) {
            waitLock.notifyAll();
        }

        thread1.join();
        thread2.join();
    }

    /**
     * A filter function that removes hash codes (which will change between tests and cannot be
     * controlled.)
     * <p>
     * Note: leaves "<HASH>" to indicate that something was replaced.
     */
    private static String filterHashes(String t) {
        return t.replaceAll("<0x[0-9a-f]{8}>", "<HASH>");
    }
}
