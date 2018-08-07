/**
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.android.server.usage;

import android.annotation.UserIdInt;
import android.app.PendingIntent;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Monitors and informs of any app time limits exceeded. It must be informed when an app
 * enters the foreground and exits. Used by UsageStatsService. Manages multiple users.
 *
 * Test: atest FrameworksServicesTests:AppTimeLimitControllerTests
 * Test: manual: frameworks/base/tests/UsageStatsTest
 */
public class AppTimeLimitController {

    private static final String TAG = "AppTimeLimitController";

    private static final boolean DEBUG = false;

    /** Lock class for this object */
    private static class Lock {}

    /** Lock object for the data in this class. */
    private final Lock mLock = new Lock();

    private final MyHandler mHandler;

    private OnLimitReachedListener mListener;

    private static final long MAX_OBSERVER_PER_UID = 1000;

    private static final long ONE_MINUTE = 60_000L;

    @GuardedBy("mLock")
    private final SparseArray<UserData> mUsers = new SparseArray<>();

    private static class UserData {
        /** userId of the user */
        private @UserIdInt int userId;

        /** The app that is currently in the foreground */
        private String currentForegroundedPackage;

        /** The time when the current app came to the foreground */
        private long currentForegroundedTime;

        /** Map from package name for quick lookup */
        private ArrayMap<String, ArrayList<TimeLimitGroup>> packageMap = new ArrayMap<>();

        /** Map of observerId to details of the time limit group */
        private SparseArray<TimeLimitGroup> groups = new SparseArray<>();

        /** Map of the number of observerIds registered by uid */
        private SparseIntArray observerIdCounts = new SparseIntArray();

        private UserData(@UserIdInt int userId) {
            this.userId = userId;
        }
    }

    /**
     * Listener interface for being informed when an app group's time limit is reached.
     */
    public interface OnLimitReachedListener {
        /**
         * Time limit for a group, keyed by the observerId, has been reached.
         * @param observerId The observerId of the group whose limit was reached
         * @param userId The userId
         * @param timeLimit The original time limit in milliseconds
         * @param timeElapsed How much time was actually spent on apps in the group, in milliseconds
         * @param callbackIntent The PendingIntent to send when the limit is reached
         */
        public void onLimitReached(int observerId, @UserIdInt int userId, long timeLimit,
                long timeElapsed, PendingIntent callbackIntent);
    }

    static class TimeLimitGroup {
        int requestingUid;
        int observerId;
        String[] packages;
        long timeLimit;
        long timeRequested;
        long timeRemaining;
        PendingIntent callbackIntent;
        String currentPackage;
        long timeCurrentPackageStarted;
        int userId;
    }

    private class MyHandler extends Handler {

        static final int MSG_CHECK_TIMEOUT = 1;
        static final int MSG_INFORM_LISTENER = 2;

        MyHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_CHECK_TIMEOUT:
                    checkTimeout((TimeLimitGroup) msg.obj);
                    break;
                case MSG_INFORM_LISTENER:
                    informListener((TimeLimitGroup) msg.obj);
                    break;
                default:
                    super.handleMessage(msg);
                    break;
            }
        }
    }

    public AppTimeLimitController(OnLimitReachedListener listener, Looper looper) {
        mHandler = new MyHandler(looper);
        mListener = listener;
    }

    /** Overrideable by a test */
    @VisibleForTesting
    protected long getUptimeMillis() {
        return SystemClock.uptimeMillis();
    }

    /** Overrideable for testing purposes */
    @VisibleForTesting
    protected long getObserverPerUidLimit() {
        return MAX_OBSERVER_PER_UID;
    }

    /** Overrideable for testing purposes */
    @VisibleForTesting
    protected long getMinTimeLimit() {
        return ONE_MINUTE;
    }

    /** Returns an existing UserData object for the given userId, or creates one */
    private UserData getOrCreateUserDataLocked(int userId) {
        UserData userData = mUsers.get(userId);
        if (userData == null) {
            userData = new UserData(userId);
            mUsers.put(userId, userData);
        }
        return userData;
    }

    /** Clean up data if user is removed */
    public void onUserRemoved(int userId) {
        synchronized (mLock) {
            // TODO: Remove any inflight delayed messages
            mUsers.remove(userId);
        }
    }

    /**
     * Registers an observer with the given details. Existing observer with the same observerId
     * is removed.
     */
    public void addObserver(int requestingUid, int observerId, String[] packages, long timeLimit,
            PendingIntent callbackIntent, @UserIdInt int userId) {

        if (timeLimit < getMinTimeLimit()) {
            throw new IllegalArgumentException("Time limit must be >= " + getMinTimeLimit());
        }
        synchronized (mLock) {
            UserData user = getOrCreateUserDataLocked(userId);
            removeObserverLocked(user, requestingUid, observerId, /*readding =*/ true);

            final int observerIdCount = user.observerIdCounts.get(requestingUid, 0);
            if (observerIdCount >= getObserverPerUidLimit()) {
                throw new IllegalStateException(
                        "Too many observers added by uid " + requestingUid);
            }
            user.observerIdCounts.put(requestingUid, observerIdCount + 1);

            TimeLimitGroup group = new TimeLimitGroup();
            group.observerId = observerId;
            group.callbackIntent = callbackIntent;
            group.packages = packages;
            group.timeLimit = timeLimit;
            group.timeRemaining = group.timeLimit;
            group.timeRequested = getUptimeMillis();
            group.requestingUid = requestingUid;
            group.timeCurrentPackageStarted = -1L;
            group.userId = userId;

            user.groups.append(observerId, group);

            addGroupToPackageMapLocked(user, packages, group);

            if (DEBUG) {
                Slog.d(TAG, "addObserver " + packages + " for " + timeLimit);
            }
            // Handle the case where a target package is already in the foreground when observer
            // is added.
            if (user.currentForegroundedPackage != null && inPackageList(group.packages,
                    user.currentForegroundedPackage)) {
                group.timeCurrentPackageStarted = group.timeRequested;
                group.currentPackage = user.currentForegroundedPackage;
                if (group.timeRemaining > 0) {
                    postCheckTimeoutLocked(group, group.timeRemaining);
                }
            }
        }
    }

    /**
     * Remove a registered observer by observerId and calling uid.
     * @param requestingUid The calling uid
     * @param observerId The unique observer id for this user
     * @param userId The user id of the observer
     */
    public void removeObserver(int requestingUid, int observerId, @UserIdInt int userId) {
        synchronized (mLock) {
            UserData user = getOrCreateUserDataLocked(userId);
            removeObserverLocked(user, requestingUid, observerId, /*readding =*/ false);
        }
    }

    @VisibleForTesting
    TimeLimitGroup getObserverGroup(int observerId, int userId) {
        synchronized (mLock) {
            return getOrCreateUserDataLocked(userId).groups.get(observerId);
        }
    }

    private static boolean inPackageList(String[] packages, String packageName) {
        return ArrayUtils.contains(packages, packageName);
    }

    @GuardedBy("mLock")
    private void removeObserverLocked(UserData user, int requestingUid, int observerId,
            boolean readding) {
        TimeLimitGroup group = user.groups.get(observerId);
        if (group != null && group.requestingUid == requestingUid) {
            removeGroupFromPackageMapLocked(user, group);
            user.groups.remove(observerId);
            mHandler.removeMessages(MyHandler.MSG_CHECK_TIMEOUT, group);
            final int observerIdCount = user.observerIdCounts.get(requestingUid);
            if (observerIdCount <= 1 && !readding) {
                user.observerIdCounts.delete(requestingUid);
            } else {
                user.observerIdCounts.put(requestingUid, observerIdCount - 1);
            }
        }
    }

    /**
     * Called when an app has moved to the foreground.
     * @param packageName The app that is foregrounded
     * @param className The className of the activity
     * @param userId The user
     */
    public void moveToForeground(String packageName, String className, int userId) {
        synchronized (mLock) {
            UserData user = getOrCreateUserDataLocked(userId);
            if (DEBUG) Slog.d(TAG, "Setting mCurrentForegroundedPackage to " + packageName);
            // Note the current foreground package
            user.currentForegroundedPackage = packageName;
            user.currentForegroundedTime = getUptimeMillis();

            // Check if any of the groups need to watch for this package
            maybeWatchForPackageLocked(user, packageName, user.currentForegroundedTime);
        }
    }

    /**
     * Called when an app is sent to the background.
     *
     * @param packageName
     * @param className
     * @param userId
     */
    public void moveToBackground(String packageName, String className, int userId) {
        synchronized (mLock) {
            UserData user = getOrCreateUserDataLocked(userId);
            if (!TextUtils.equals(user.currentForegroundedPackage, packageName)) {
                Slog.w(TAG, "Eh? Last foregrounded package = " + user.currentForegroundedPackage
                        + " and now backgrounded = " + packageName);
                return;
            }
            final long stopTime = getUptimeMillis();

            // Add up the usage time to all groups that contain the package
            ArrayList<TimeLimitGroup> groups = user.packageMap.get(packageName);
            if (groups != null) {
                final int size = groups.size();
                for (int i = 0; i < size; i++) {
                    final TimeLimitGroup group = groups.get(i);
                    // Don't continue to send
                    if (group.timeRemaining <= 0) continue;

                    final long startTime = Math.max(user.currentForegroundedTime,
                            group.timeRequested);
                    long diff = stopTime - startTime;
                    group.timeRemaining -= diff;
                    if (group.timeRemaining <= 0) {
                        if (DEBUG) Slog.d(TAG, "MTB informing group obs=" + group.observerId);
                        postInformListenerLocked(group);
                    }
                    // Reset indicators that observer was added when package was already fg
                    group.currentPackage = null;
                    group.timeCurrentPackageStarted = -1L;
                    mHandler.removeMessages(MyHandler.MSG_CHECK_TIMEOUT, group);
                }
            }
            user.currentForegroundedPackage = null;
        }
    }

    private void postInformListenerLocked(TimeLimitGroup group) {
        mHandler.sendMessage(mHandler.obtainMessage(MyHandler.MSG_INFORM_LISTENER,
                group));
    }

    /**
     * Inform the observer and unregister it, as the limit has been reached.
     * @param group the observed group
     */
    private void informListener(TimeLimitGroup group) {
        if (mListener != null) {
            mListener.onLimitReached(group.observerId, group.userId, group.timeLimit,
                    group.timeLimit - group.timeRemaining, group.callbackIntent);
        }
        // Unregister since the limit has been met and observer was informed.
        synchronized (mLock) {
            UserData user = getOrCreateUserDataLocked(group.userId);
            removeObserverLocked(user, group.requestingUid, group.observerId, false);
        }
    }

    /** Check if any of the groups care about this package and set up delayed messages */
    @GuardedBy("mLock")
    private void maybeWatchForPackageLocked(UserData user, String packageName, long uptimeMillis) {
        ArrayList<TimeLimitGroup> groups = user.packageMap.get(packageName);
        if (groups == null) return;

        final int size = groups.size();
        for (int i = 0; i < size; i++) {
            TimeLimitGroup group = groups.get(i);
            if (group.timeRemaining > 0) {
                group.timeCurrentPackageStarted = uptimeMillis;
                group.currentPackage = packageName;
                if (DEBUG) {
                    Slog.d(TAG, "Posting timeout for " + packageName + " for "
                            + group.timeRemaining + "ms");
                }
                postCheckTimeoutLocked(group, group.timeRemaining);
            }
        }
    }

    private void addGroupToPackageMapLocked(UserData user, String[] packages,
            TimeLimitGroup group) {
        for (int i = 0; i < packages.length; i++) {
            ArrayList<TimeLimitGroup> list = user.packageMap.get(packages[i]);
            if (list == null) {
                list = new ArrayList<>();
                user.packageMap.put(packages[i], list);
            }
            list.add(group);
        }
    }

    /**
     * Remove the group reference from the package to group mapping, which is 1 to many.
     * @param group The group to remove from the package map.
     */
    private void removeGroupFromPackageMapLocked(UserData user, TimeLimitGroup group) {
        final int mapSize = user.packageMap.size();
        for (int i = 0; i < mapSize; i++) {
            ArrayList<TimeLimitGroup> list = user.packageMap.valueAt(i);
            list.remove(group);
        }
    }

    private void postCheckTimeoutLocked(TimeLimitGroup group, long timeout) {
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MyHandler.MSG_CHECK_TIMEOUT, group),
                timeout);
    }

    /**
     * See if the given group has reached the timeout if the current foreground app is included
     * and it exceeds the time remaining.
     * @param group the group of packages to check
     */
    void checkTimeout(TimeLimitGroup group) {
        // For each package in the group, check if any of the currently foregrounded apps are adding
        // up to hit the limit and inform the observer
        synchronized (mLock) {
            UserData user = getOrCreateUserDataLocked(group.userId);
            // This group doesn't exist anymore, nothing to see here.
            if (user.groups.get(group.observerId) != group) return;

            if (DEBUG) Slog.d(TAG, "checkTimeout timeRemaining=" + group.timeRemaining);

            // Already reached the limit, no need to report again
            if (group.timeRemaining <= 0) return;

            if (DEBUG) {
                Slog.d(TAG, "checkTimeout foregroundedPackage="
                        + user.currentForegroundedPackage);
            }

            if (inPackageList(group.packages, user.currentForegroundedPackage)) {
                if (DEBUG) {
                    Slog.d(TAG, "checkTimeout package in foreground="
                            + user.currentForegroundedPackage);
                }
                if (group.timeCurrentPackageStarted < 0) {
                    Slog.w(TAG, "startTime was not set correctly for " + group);
                }
                final long timeInForeground = getUptimeMillis() - group.timeCurrentPackageStarted;
                if (group.timeRemaining <= timeInForeground) {
                    if (DEBUG) Slog.d(TAG, "checkTimeout : Time limit reached");
                    // Hit the limit, set timeRemaining to zero to avoid checking again
                    group.timeRemaining -= timeInForeground;
                    postInformListenerLocked(group);
                    // Reset
                    group.timeCurrentPackageStarted = -1L;
                    group.currentPackage = null;
                } else {
                    if (DEBUG) Slog.d(TAG, "checkTimeout : Some more time remaining");
                    postCheckTimeoutLocked(group, group.timeRemaining - timeInForeground);
                }
            }
        }
    }

    void dump(PrintWriter pw) {
        synchronized (mLock) {
            pw.println("\n  App Time Limits");
            int nUsers = mUsers.size();
            for (int i = 0; i < nUsers; i++) {
                UserData user = mUsers.valueAt(i);
                pw.print("   User "); pw.println(user.userId);
                int nGroups = user.groups.size();
                for (int j = 0; j < nGroups; j++) {
                    TimeLimitGroup group = user.groups.valueAt(j);
                    pw.print("    Group id="); pw.print(group.observerId);
                    pw.print(" timeLimit="); pw.print(group.timeLimit);
                    pw.print(" remaining="); pw.print(group.timeRemaining);
                    pw.print(" currentPackage="); pw.print(group.currentPackage);
                    pw.print(" timeCurrentPkgStarted="); pw.print(group.timeCurrentPackageStarted);
                    pw.print(" packages="); pw.println(Arrays.toString(group.packages));
                }
                pw.println();
                pw.print("    currentForegroundedPackage=");
                pw.println(user.currentForegroundedPackage);
            }
        }
    }
}
