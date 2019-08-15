/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.lock_checker;

import android.app.ActivityThread;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.util.Log;
import android.util.LogWriter;

import com.android.internal.os.RuntimeInit;
import com.android.internal.os.SomeArgs;
import com.android.internal.util.StatLogger;

import dalvik.system.AnnotatedStackTraceElement;

import libcore.util.HexEncoding;

import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Entry class for lock inversion infrastructure. The agent will inject calls to preLock
 * and postLock, and the hook will call the checker, and store violations.
 */
public class LockHook {
    private static final String TAG = "LockHook";

    private static final Charset sFilenameCharset = Charset.forName("UTF-8");

    private static final HandlerThread sHandlerThread;
    private static final WtfHandler sHandler;

    private static final AtomicInteger sTotalObtainCount = new AtomicInteger();
    private static final AtomicInteger sTotalReleaseCount = new AtomicInteger();
    private static final AtomicInteger sDeepestNest = new AtomicInteger();

    /**
     * Whether to do the lock check on this thread.
     */
    private static final ThreadLocal<Boolean> sDoCheck = ThreadLocal.withInitial(() -> true);

    interface Stats {
        int ON_THREAD = 0;
    }

    static final StatLogger sStats = new StatLogger(new String[] { "on-thread", });

    private static final ConcurrentLinkedQueue<Violation> sViolations =
            new ConcurrentLinkedQueue<>();
    private static final int MAX_VIOLATIONS = 50;

    private static final LockChecker[] sCheckers;

    private static boolean sNativeHandling = false;
    private static boolean sSimulateCrash = false;

    static {
        sHandlerThread = new HandlerThread("LockHook:wtf", Process.THREAD_PRIORITY_BACKGROUND);
        sHandlerThread.start();
        sHandler = new WtfHandler(sHandlerThread.getLooper());

        sCheckers = new LockChecker[] { new OnThreadLockChecker() };

        sNativeHandling = getNativeHandlingConfig();
        sSimulateCrash = getSimulateCrashConfig();
    }

    private static native boolean getNativeHandlingConfig();
    private static native boolean getSimulateCrashConfig();

    static <T> boolean shouldDumpStacktrace(StacktraceHasher hasher, Map<String, T> dumpedSet,
            T val, AnnotatedStackTraceElement[] st, int from, int to) {
        final String stacktraceHash = hasher.stacktraceHash(st, from, to);
        if (dumpedSet.containsKey(stacktraceHash)) {
            return false;
        }
        dumpedSet.put(stacktraceHash, val);
        return true;
    }

    static void updateDeepestNest(int nest) {
        for (;;) {
            final int knownDeepest = sDeepestNest.get();
            if (knownDeepest >= nest) {
                return;
            }
            if (sDeepestNest.compareAndSet(knownDeepest, nest)) {
                return;
            }
        }
    }

    static void wtf(Violation v) {
        sHandler.wtf(v);
    }

    static void doCheckOnThisThread(boolean check) {
        sDoCheck.set(check);
    }

    /**
     * This method is called when a lock is about to be held. (Except if it's a
     * synchronized, the lock is already held.)
     */
    public static void preLock(Object lock) {
        if (Thread.currentThread() != sHandlerThread && sDoCheck.get()) {
            sDoCheck.set(false);
            try {
                sTotalObtainCount.incrementAndGet();
                for (LockChecker checker : sCheckers) {
                    checker.pre(lock);
                }
            } finally {
                sDoCheck.set(true);
            }
        }
    }

    /**
     * This method is called when a lock is about to be released.
     */
    public static void postLock(Object lock) {
        if (Thread.currentThread() != sHandlerThread && sDoCheck.get()) {
            sDoCheck.set(false);
            try {
                sTotalReleaseCount.incrementAndGet();
                for (LockChecker checker : sCheckers) {
                    checker.post(lock);
                }
            } finally {
                sDoCheck.set(true);
            }
        }
    }

    private static class WtfHandler extends Handler {
        private static final int MSG_WTF = 1;

        WtfHandler(Looper looper) {
            super(looper);
        }

        public void wtf(Violation v) {
            sDoCheck.set(false);
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = v;
            obtainMessage(MSG_WTF, args).sendToTarget();
            sDoCheck.set(true);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_WTF:
                    SomeArgs args = (SomeArgs) msg.obj;
                    handleViolation((Violation) args.arg1);
                    args.recycle();
                    break;
            }
        }
    }

    private static void handleViolation(Violation v) {
        String msg = v.toString();
        Log.wtf(TAG, msg);
        if (sNativeHandling) {
            nWtf(msg);  // Also send to native.
        }
        if (sSimulateCrash) {
            RuntimeInit.logUncaught("LockAgent",
                    ActivityThread.isSystem() ? "system_server"
                            : ActivityThread.currentProcessName(),
                    Process.myPid(), v.getException());
        }
    }

    private static native void nWtf(String msg);

    /**
     * Generates a hash for a given stacktrace of a {@link Throwable}.
     */
    static class StacktraceHasher {
        private byte[] mLineNumberBuffer = new byte[4];
        private final MessageDigest mHash;

        StacktraceHasher() {
            try {
                mHash = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }

        public String stacktraceHash(Throwable t) {
            mHash.reset();
            for (StackTraceElement e : t.getStackTrace()) {
                hashStackTraceElement(e);
            }
            return HexEncoding.encodeToString(mHash.digest());
        }

        public String stacktraceHash(AnnotatedStackTraceElement[] annotatedStack, int from,
                int to) {
            mHash.reset();
            for (int i = from; i <= to; i++) {
                hashStackTraceElement(annotatedStack[i].getStackTraceElement());
            }
            return HexEncoding.encodeToString(mHash.digest());
        }

        private void hashStackTraceElement(StackTraceElement e) {
            if (e.getFileName() != null) {
                mHash.update(sFilenameCharset.encode(e.getFileName()).array());
            } else {
                if (e.getClassName() != null) {
                    mHash.update(sFilenameCharset.encode(e.getClassName()).array());
                }
                if (e.getMethodName() != null) {
                    mHash.update(sFilenameCharset.encode(e.getMethodName()).array());
                }
            }

            final int line = e.getLineNumber();
            mLineNumberBuffer[0] = (byte) ((line >> 24) & 0xff);
            mLineNumberBuffer[1] = (byte) ((line >> 16) & 0xff);
            mLineNumberBuffer[2] = (byte) ((line >> 8) & 0xff);
            mLineNumberBuffer[3] = (byte) ((line >> 0) & 0xff);
            mHash.update(mLineNumberBuffer);
        }
    }

    static void addViolation(Violation v) {
        wtf(v);

        sViolations.offer(v);
        while (sViolations.size() > MAX_VIOLATIONS) {
            sViolations.poll();
        }
    }

    /**
     * Dump stats to the given PrintWriter.
     */
    public static void dump(PrintWriter pw, String indent) {
        final int oc = LockHook.sTotalObtainCount.get();
        final int rc = LockHook.sTotalReleaseCount.get();
        final int dn = LockHook.sDeepestNest.get();
        pw.print("Lock stats: oc=");
        pw.print(oc);
        pw.print(" rc=");
        pw.print(rc);
        pw.print(" dn=");
        pw.print(dn);
        pw.println();

        for (LockChecker checker : sCheckers) {
            pw.print(indent);
            pw.print("  ");
            checker.dump(pw);
            pw.println();
        }

        sStats.dump(pw, indent);

        pw.print(indent);
        pw.println("Violations:");
        for (Object v : sViolations) {
            pw.print(indent); // This won't really indent a multiline string,
                              // though.
            pw.println(v);
        }
    }

    /**
     * Dump stats to logcat.
     */
    public static void dump() {
        // Dump to logcat.
        PrintWriter out = new PrintWriter(new LogWriter(Log.WARN, TAG), true);
        dump(out, "");
        out.close();
    }

    interface LockChecker {
        void pre(Object lock);

        void post(Object lock);

        int getNumDetected();

        int getNumDetectedUnique();

        String getCheckerName();

        void dump(PrintWriter pw);
    }

    interface Violation {
        Throwable getException();
    }
}
