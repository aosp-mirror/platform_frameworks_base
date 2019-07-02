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

import android.test.AndroidTestCase;
import android.util.Log;

import androidx.test.filters.LargeTest;
import androidx.test.filters.SmallTest;
import androidx.test.filters.Suppress;

/**
 * This class is used to test the native tracing support.  Run this test
 * while tracing on the emulator and then run traceview to view the trace.
 */
public class TraceTest extends AndroidTestCase
{
    private static final String TAG = "TraceTest";
    private int eMethodCalls = 0;
    private int fMethodCalls = 0;
    private int gMethodCalls = 0;
    
    @SmallTest
    public void testNativeTracingFromJava()
    {
        long start = System.currentTimeMillis();
        Debug.startNativeTracing();
        //nativeMethod();
        int count = 0;
        for (int ii = 0; ii < 20; ii++) {
            count = eMethod();
        }
        Debug.stopNativeTracing();
        long end = System.currentTimeMillis();
        long elapsed = end - start;
        Log.i(TAG, "elapsed millis: " + elapsed);
        Log.i(TAG, "eMethod calls: " + eMethodCalls
                + " fMethod calls: " + fMethodCalls
                + " gMethod calls: " + gMethodCalls);
    }
    
    // This should not run in the automated suite.
    @Suppress
    public void disableTestNativeTracingFromC()
    {
        long start = System.currentTimeMillis();
        nativeMethodAndStartTracing();
        long end = System.currentTimeMillis();
        long elapsed = end - start;
        Log.i(TAG, "elapsed millis: " + elapsed);
    }

    native void nativeMethod();
    native void nativeMethodAndStartTracing();
    
    @LargeTest
    @Suppress  // Failing.
    public void testMethodTracing()
    {
        long start = System.currentTimeMillis();
        Debug.startMethodTracing("traceTest");
        topMethod();
        Debug.stopMethodTracing();
        long end = System.currentTimeMillis();
        long elapsed = end - start;
        Log.i(TAG, "elapsed millis: " + elapsed);
    }
    
    private void topMethod() {
        aMethod();
        bMethod();
        cMethod();
        dMethod(5);
        
        Thread t1 = new aThread();
        t1.start();
        Thread t2 = new aThread();
        t2.start();
        Thread t3 = new aThread();
        t3.start();
        try {
            t1.join();
            t2.join();
            t3.join();
        } catch (InterruptedException e) {
        }
    }
    
    private class aThread extends Thread {
        @Override
        public void run() {
            aMethod();
            bMethod();
            cMethod();
        }
    }
    
    /** Calls other methods to make some interesting trace data.
     * 
     * @return a meaningless value
     */
    private int aMethod() {
        int count = 0;
        for (int ii = 0; ii < 6; ii++) {
            count += bMethod();
        }
        for (int ii = 0; ii < 5; ii++) {
            count += cMethod();
        }
        for (int ii = 0; ii < 4; ii++) {
            count += dMethod(ii);
        }
        return count;
    }
    
    /** Calls another method to make some interesting trace data.
     * 
     * @return a meaningless value
     */
    private int bMethod() {
        int count = 0;
        for (int ii = 0; ii < 4; ii++) {
            count += cMethod();
        }
        return count;
    }
    
    /** Executes a simple loop to make some interesting trace data.
     * 
     * @return a meaningless value
     */
    private int cMethod() {
        int count = 0;
        for (int ii = 0; ii < 1000; ii++) {
            count += ii;
        }
        return count;
    }
    
    /** Calls itself recursively to make some interesting trace data.
     * 
     * @return a meaningless value
     */
    private int dMethod(int level) {
        int count = 0;
        if (level > 0) {
            count = dMethod(level - 1);
        }
        for (int ii = 0; ii < 100; ii++) {
            count += ii;
        }
        if (level == 0) {
            return count;
        }
        return dMethod(level - 1);
    }
    
    public int eMethod() {
        eMethodCalls += 1;
        int count = fMethod();
        count += gMethod(3);
        return count;
    }
    
    public int fMethod() {
        fMethodCalls += 1;
        int count = 0;
        for (int ii = 0; ii < 10; ii++) {
            count += ii;
        }
        return count;
    }
    
    public int gMethod(int level) {
        gMethodCalls += 1;
        int count = level;
        if (level > 1)
            count += gMethod(level - 1);
        return count;
    }

    /*
     * This causes the native shared library to be loaded when the
     * class is first used.  The library is only loaded once, even if
     * multiple classes include this line.
     *
     * The library must be in java.library.path, which is derived from
     * LD_LIBRARY_PATH.  The actual library name searched for will be
     * "libtrace_test.so" under Linux, but may be different on other
     * platforms.
     */
    static {
        Log.i(TAG, "Loading trace_test native library...");
        try {
            System.loadLibrary("trace_test");
            Log.i(TAG, "Successfully loaded trace_test native library");
        }
        catch (UnsatisfiedLinkError ule) {
            Log.w(TAG, "Could not load trace_test native library");
        }
    }
}
