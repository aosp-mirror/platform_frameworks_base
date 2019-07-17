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

import android.util.Log;

import dalvik.system.AnnotatedStackTraceElement;
import dalvik.system.VMStack;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;


class OnThreadLockChecker implements LockHook.LockChecker {
    private static final String TAG = "LockCheckOnThread";

    private static final boolean SKIP_RECURSIVE = true;

    private final Thread mChecker;

    private final AtomicInteger mNumDetected = new AtomicInteger();

    private final AtomicInteger mNumDetectedUnique = new AtomicInteger();

    // Queue for possible violations, to handle them on the sChecker thread.
    private final LinkedBlockingQueue<Violation> mQueue = new LinkedBlockingQueue<>();

    // The stack of locks held on the current thread.
    private final ThreadLocal<List<Object>> mHeldLocks = ThreadLocal
            .withInitial(() -> new ArrayList<>(10));

    // A cached stacktrace hasher for each thread. The hasher caches internal objects and is not
    // thread-safe.
    private final ThreadLocal<LockHook.StacktraceHasher> mStacktraceHasher = ThreadLocal
            .withInitial(() -> new LockHook.StacktraceHasher());

    // A map of stacktrace hashes we have seen.
    private final ConcurrentMap<String, Boolean> mDumpedStacktraceHashes =
            new ConcurrentHashMap<>();

    OnThreadLockChecker() {
        mChecker = new Thread(() -> checker());
        mChecker.setName(TAG);
        mChecker.setPriority(Thread.MIN_PRIORITY);
        mChecker.start();
    }

    private static class LockPair {
        // Consider WeakReference. It will require also caching the String
        // description for later reporting, though.
        Object mFirst;
        Object mSecond;

        private int mCachedHashCode;

        LockPair(Object first, Object second) {
            mFirst = first;
            mSecond = second;
            computeHashCode();
        }

        public void set(Object newFirst, Object newSecond) {
            mFirst = newFirst;
            mSecond = newSecond;
            computeHashCode();
        }

        private void computeHashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((mFirst == null) ? 0 : System.identityHashCode(mFirst));
            result = prime * result + ((mSecond == null) ? 0 : System.identityHashCode(mSecond));
            mCachedHashCode = result;
        }

        @Override
        public int hashCode() {
            return mCachedHashCode;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            LockPair other = (LockPair) obj;
            return mFirst == other.mFirst && mSecond == other.mSecond;
        }
    }

    private static class OrderData {
        final int mTid;
        final String mThreadName;
        final AnnotatedStackTraceElement[] mStack;

        OrderData(int tid, String threadName, AnnotatedStackTraceElement[] stack) {
            this.mTid = tid;
            this.mThreadName = threadName;
            this.mStack = stack;
        }
    }

    private static ConcurrentMap<LockPair, OrderData> sLockOrderMap = new ConcurrentHashMap<>();

    @Override
    public void pre(Object lock) {
        handlePre(Thread.currentThread(), lock);
    }

    @Override
    public void post(Object lock) {
        handlePost(Thread.currentThread(), lock);
    }

    private void handlePre(Thread self, Object lock) {
        List<Object> heldLocks = mHeldLocks.get();

        LockHook.updateDeepestNest(heldLocks.size() + 1);

        heldLocks.add(lock);
        if (heldLocks.size() == 1) {
            return;
        }

        // Data about this location. Cached and lazily initialized.
        AnnotatedStackTraceElement[] annotatedStack = null;
        OrderData orderData = null;

        // Reused tmp pair;
        LockPair tmp = new LockPair(lock, lock);

        int size = heldLocks.size() - 1;
        for (int i = 0; i < size; i++) {
            Object alreadyHeld = heldLocks.get(i);
            if (SKIP_RECURSIVE && lock == alreadyHeld) {
                return;
            }

            // Check if we've already seen alreadyHeld -> lock.
            tmp.set(alreadyHeld, lock);
            if (sLockOrderMap.containsKey(tmp)) {
                continue; // Already seen.
            }

            // Note: could insert the OrderData now. This would mean we only
            // report one instance for each order violation, but it avoids
            // the expensive hashing in handleViolation for duplicate stacks.

            // Locking alreadyHeld -> lock, check whether the inverse exists.
            tmp.set(lock, alreadyHeld);

            // We technically need a critical section here. Add synchronized and
            // skip
            // instrumenting this class. For now, a concurrent hash map is good
            // enough.

            OrderData oppositeData = sLockOrderMap.getOrDefault(tmp, null);
            if (oppositeData != null) {
                if (annotatedStack == null) {
                    annotatedStack = VMStack.getAnnotatedThreadStackTrace(self);
                }
                postViolation(self, alreadyHeld, lock, annotatedStack, oppositeData);
                continue;
            }

            // Enter our occurrence.
            if (annotatedStack == null) {
                annotatedStack = VMStack.getAnnotatedThreadStackTrace(self);
            }
            if (orderData == null) {
                orderData = new OrderData((int) self.getId(), self.getName(), annotatedStack);
            }
            sLockOrderMap.putIfAbsent(new LockPair(alreadyHeld, lock), orderData);

            // Check again whether we might have raced with the opposite.
            oppositeData = sLockOrderMap.getOrDefault(tmp, null);
            if (oppositeData != null) {
                postViolation(self, alreadyHeld, lock, annotatedStack, oppositeData);
            }
        }
    }

    private void handlePost(Thread self, Object lock) {
        List<Object> heldLocks = mHeldLocks.get();
        if (heldLocks.isEmpty()) {
            Log.wtf("LockCheckMine", "Empty thread list on post()");
            return;
        }
        int index = heldLocks.size() - 1;
        if (heldLocks.get(index) != lock) {
            Log.wtf("LockCheckMine", "post(" + Violation.describeLock(lock) + ") vs [..., "
                    + Violation.describeLock(heldLocks.get(index)) + "]");
            return;
        }
        heldLocks.remove(index);
    }

    private static class Violation implements LockHook.Violation {
        int mSelfTid;
        String mSelfName;
        Object mAlreadyHeld;
        Object mLock;
        AnnotatedStackTraceElement[] mStack;
        OrderData mOppositeData;

        private static final int STACK_OFFSET = 4;

        Violation(Thread self, Object alreadyHeld, Object lock,
                AnnotatedStackTraceElement[] stack, OrderData oppositeData) {
            this.mSelfTid = (int) self.getId();
            this.mSelfName = self.getName();
            this.mAlreadyHeld = alreadyHeld;
            this.mLock = lock;
            this.mStack = stack;
            this.mOppositeData = oppositeData;
        }

        private static String getAnnotatedStackString(AnnotatedStackTraceElement[] stackTrace,
                int skip, String extra, int prefixAfter, String prefix) {
            StringBuilder sb = new StringBuilder();
            for (int i = skip; i < stackTrace.length; i++) {
                AnnotatedStackTraceElement element = stackTrace[i];
                sb.append("    ").append(i >= prefixAfter ? prefix : "").append("at ")
                        .append(element.getStackTraceElement()).append('\n');
                if (i == skip && extra != null) {
                    sb.append("    ").append(extra).append('\n');
                }
                if (element.getHeldLocks() != null) {
                    for (Object held : element.getHeldLocks()) {
                        sb.append("    ").append(i >= prefixAfter ? prefix : "")
                                .append(describeLocking(held, "locked")).append('\n');
                    }
                }
            }
            return sb.toString();
        }

        private static String describeLocking(Object lock, String action) {
            return String.format("- %s %s", action, describeLock(lock));
        }

        private static int getTo(AnnotatedStackTraceElement[] stack, Object searchFor) {
            // Extract the range of the annotated stack.
            int to = stack.length - 1;
            for (int i = 0; i < stack.length; i++) {
                Object[] locks = stack[i].getHeldLocks();
                if (locks != null) {
                    for (Object heldLock : locks) {
                        if (heldLock == searchFor) {
                            to = i;
                            break;
                        }
                    }
                }
            }
            return to;
        }

        private static String describeLock(Object lock) {
            return String.format("<0x%08x> (a %s)", System.identityHashCode(lock),
                    lock.getClass().getName());
        }

        // Synthesize an exception.
        public Throwable getException() {
            RuntimeException inner = new RuntimeException("Previously locked");
            inner.setStackTrace(synthesizeStackTrace(mOppositeData.mStack));

            RuntimeException outer = new RuntimeException(toString(), inner);
            outer.setStackTrace(synthesizeStackTrace(mStack));

            return outer;
        }

        private StackTraceElement[] synthesizeStackTrace(AnnotatedStackTraceElement[] stack) {

            StackTraceElement[] out = new StackTraceElement[stack.length - STACK_OFFSET];
            for (int i = 0; i < out.length; i++) {
                out[i] = stack[i + STACK_OFFSET].getStackTraceElement();
            }
            return out;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Lock inversion detected!\n");
            sb.append("  Locked ");
            sb.append(describeLock(mLock));
            sb.append(" -> ");
            sb.append(describeLock(mAlreadyHeld));
            sb.append(" on thread ").append(mOppositeData.mTid).append(" (")
                    .append(mOppositeData.mThreadName).append(")");
            sb.append(" at:\n");
            sb.append(getAnnotatedStackString(mOppositeData.mStack, STACK_OFFSET,
                    describeLocking(mAlreadyHeld, "will lock"), getTo(mOppositeData.mStack, mLock)
                    + 1, "    | "));
            sb.append("  Locking ");
            sb.append(describeLock(mAlreadyHeld));
            sb.append(" -> ");
            sb.append(describeLock(mLock));
            sb.append(" on thread ").append(mSelfTid).append(" (").append(mSelfName).append(")");
            sb.append(" at:\n");
            sb.append(getAnnotatedStackString(mStack, STACK_OFFSET,
                    describeLocking(mLock, "will lock"),
                    getTo(mStack, mAlreadyHeld) + 1, "    | "));

            return sb.toString();
        }
    }

    private void postViolation(Thread self, Object alreadyHeld, Object lock,
            AnnotatedStackTraceElement[] annotatedStack, OrderData oppositeData) {
        mQueue.offer(new Violation(self, alreadyHeld, lock, annotatedStack, oppositeData));
    }

    private void handleViolation(Violation v) {
        mNumDetected.incrementAndGet();
        // Extract the range of the annotated stack.
        int to = Violation.getTo(v.mStack, v.mAlreadyHeld);

        if (LockHook.shouldDumpStacktrace(mStacktraceHasher.get(), mDumpedStacktraceHashes,
                Boolean.TRUE, v.mStack, 0, to)) {
            mNumDetectedUnique.incrementAndGet();
            LockHook.addViolation(v);
        }
    }

    private void checker() {
        LockHook.doCheckOnThisThread(false);

        for (;;) {
            try {
                Violation v = mQueue.take();
                handleViolation(v);
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }

    @Override
    public int getNumDetected() {
        return mNumDetected.get();
    }

    @Override
    public int getNumDetectedUnique() {
        return mNumDetectedUnique.get();
    }

    @Override
    public String getCheckerName() {
        return "Standard LockChecker";
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.print(getCheckerName());
        pw.print(": d=");
        pw.print(getNumDetected());
        pw.print(" du=");
        pw.print(getNumDetectedUnique());
    }
}
