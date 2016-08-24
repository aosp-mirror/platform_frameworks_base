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
 */
public class LockGuard {
    private static final String TAG = "LockGuard";

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
     * Report the given lock with a well-known label.
     */
    public static void installLock(Object lock, String label) {
        final LockInfo info = findOrCreateLockInfo(lock);
        info.label = label;
    }

    private static String lockToString(Object lock) {
        final LockInfo info = sKnown.get(lock);
        if (info != null) {
            return info.label;
        } else {
            return "0x" + Integer.toHexString(System.identityHashCode(lock));
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
