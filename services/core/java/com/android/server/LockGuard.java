/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * LockGuard is a mechanism to help detect lock inversions inside the system
 * server. It works by requiring each lock acquisition site to follow this
 * pattern:
 *
 * <pre>
 * synchronized (LockGuard.guard(lock)) {
 * }
 * </pre>
 *
 * <pre>
 * $ find services/ -name "*.java" -exec sed -i -r \
 *     's/synchronized.?\((.+?)\)/synchronized \(com.android.server.LockGuard.guard\(\1\)\)/' {} \;
 * </pre>
 *
 * The {@link #guard(Object)} method internally verifies that all locking is
 * done in a consistent order, and will log if any inversion is detected. For
 * example, if the calling thread is trying to acquire the
 * {@code ActivityManager} lock while holding the {@code PackageManager} lock,
 * it will yell.
 * <p>
 * This class requires no prior knowledge of locks or their ordering; it derives
 * all of this data at runtime. However, this means the overhead is
 * <em>substantial</em> and it should not be enabled by default. For example,
 * here are some benchmarked timings:
 * <ul>
 * <li>An unguarded synchronized block takes 40ns.
 * <li>A guarded synchronized block takes 50ns when disabled.
 * <li>A guarded synchronized block takes 460ns per lock checked when enabled.
 * </ul>
 * <p>
 * This class also supports a second simpler mode of operation where well-known
 * locks are explicitly registered and checked via indexes.
 */
public class LockGuard {
    private static final String TAG = "LockGuard";

    public static final boolean ENABLED = false;

    /**
     * Well-known locks ordered by fixed index. Locks with a specific index
     * should never be acquired while holding a lock of a lower index.
     */
    public static final int INDEX_APP_OPS = 0;
    public static final int INDEX_POWER = 1;
    public static final int INDEX_USER = 2;
    public static final int INDEX_PACKAGES = 3;
    public static final int INDEX_STORAGE = 4;
    public static final int INDEX_WINDOW = 5;
    public static final int INDEX_ACTIVITY = 6;

    private static Object[] sKnownFixed = new Object[INDEX_ACTIVITY + 1];

    private static ArrayMap<Object, LockInfo> sKnown = new ArrayMap<>(0, true);

    private static class LockInfo {
        /** Friendly label to describe this lock */
        public String label;

        /** Child locks that can be acquired while this lock is already held */
        public ArraySet<Object> children = new ArraySet<>(0, true);
    }

    private static LockInfo findOrCreateLockInfo(Object lock) {
        LockInfo info = sKnown.get(lock);
        if (info == null) {
            info = new LockInfo();
            info.label = "0x" + Integer.toHexString(System.identityHashCode(lock)) + " ["
                    + new Throwable().getStackTrace()[2].toString() + "]";
            sKnown.put(lock, info);
        }
        return info;
    }

    /**
     * Check if the calling thread is holding any locks in an inverted order.
     *
     * @param lock The lock the calling thread is attempting to acquire.
     */
    public static Object guard(Object lock) {
        // If we already hold this lock, ignore
        if (lock == null || Thread.holdsLock(lock)) return lock;

        // Check to see if we're already holding any child locks
        boolean triggered = false;
        final LockInfo info = findOrCreateLockInfo(lock);
        for (int i = 0; i < info.children.size(); i++) {
            final Object child = info.children.valueAt(i);
            if (child == null) continue;

            if (Thread.holdsLock(child)) {
                Slog.w(TAG, "Calling thread " + Thread.currentThread().getName() + " is holding "
                      + lockToString(child) + " while trying to acquire "
                      + lockToString(lock), new Throwable());
                triggered = true;
            }
        }

        if (!triggered) {
            // If no trouble found above, record this lock as being a valid
            // child of all locks currently being held
            for (int i = 0; i < sKnown.size(); i++) {
                final Object test = sKnown.keyAt(i);
                if (test == null || test == lock) continue;

                if (Thread.holdsLock(test)) {
                    sKnown.valueAt(i).children.add(lock);
                }
            }
        }

        return lock;
    }

    /**
     * Yell if any lower-level locks are being held by the calling thread that
     * is about to acquire the given lock.
     */
    public static void guard(int index) {
        for (int i = 0; i < index; i++) {
            final Object lock = sKnownFixed[i];
            if (lock != null && Thread.holdsLock(lock)) {
                Slog.w(TAG, "Calling thread " + Thread.currentThread().getName() + " is holding "
                        + lockToString(i) + " while trying to acquire "
                        + lockToString(index), new Throwable());
            }
        }
    }

    /**
     * Report the given lock with a well-known label.
     */
    public static Object installLock(Object lock, String label) {
        final LockInfo info = findOrCreateLockInfo(lock);
        info.label = label;
        return lock;
    }

    /**
     * Report the given lock with a well-known index.
     */
    public static Object installLock(Object lock, int index) {
        sKnownFixed[index] = lock;
        return lock;
    }

    public static Object installNewLock(int index) {
        final Object lock = new Object();
        installLock(lock, index);
        return lock;
    }

    private static String lockToString(Object lock) {
        final LockInfo info = sKnown.get(lock);
        if (info != null) {
            return info.label;
        } else {
            return "0x" + Integer.toHexString(System.identityHashCode(lock));
        }
    }

    private static String lockToString(int index) {
        switch (index) {
            case INDEX_APP_OPS: return "APP_OPS";
            case INDEX_POWER: return "POWER";
            case INDEX_USER: return "USER";
            case INDEX_PACKAGES: return "PACKAGES";
            case INDEX_STORAGE: return "STORAGE";
            case INDEX_WINDOW: return "WINDOW";
            case INDEX_ACTIVITY: return "ACTIVITY";
            default: return Integer.toString(index);
        }
    }

    public static void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        for (int i = 0; i < sKnown.size(); i++) {
            final Object lock = sKnown.keyAt(i);
            final LockInfo info = sKnown.valueAt(i);
            pw.println("Lock " + lockToString(lock) + ":");
            for (int j = 0; j < info.children.size(); j++) {
                pw.println("  Child " + lockToString(info.children.valueAt(j)));
            }
            pw.println();
        }
    }
}
