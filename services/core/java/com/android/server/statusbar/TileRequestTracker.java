/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.statusbar;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.SparseArrayMap;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.FileDescriptor;

/**
 * Tracks user denials of requests from {@link StatusBarManagerService#requestAddTile}.
 *
 * After a certain number of denials for a particular pair (user,ComponentName), requests will be
 * auto-denied without showing a dialog to the user.
 */
public class TileRequestTracker {

    @VisibleForTesting
    static final int MAX_NUM_DENIALS = 3;

    private final Context mContext;
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final SparseArrayMap<ComponentName, Integer> mTrackingMap = new SparseArrayMap<>();
    @GuardedBy("mLock")
    private final ArraySet<ComponentName> mComponentsToRemove = new ArraySet<>();

    private final BroadcastReceiver mUninstallReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                return;
            }

            Uri data = intent.getData();
            String packageName = data.getEncodedSchemeSpecificPart();

            if (!intent.hasExtra(Intent.EXTRA_UID)) {
                return;
            }
            int userId = UserHandle.getUserId(intent.getIntExtra(Intent.EXTRA_UID, -1));
            synchronized (mLock) {
                mComponentsToRemove.clear();
                final int elementsForUser = mTrackingMap.numElementsForKey(userId);
                final int userKeyIndex = mTrackingMap.indexOfKey(userId);
                for (int compKeyIndex = 0; compKeyIndex < elementsForUser; compKeyIndex++) {
                    ComponentName c = mTrackingMap.keyAt(userKeyIndex, compKeyIndex);
                    if (c.getPackageName().equals(packageName)) {
                        mComponentsToRemove.add(c);
                    }
                }
                final int compsToRemoveNum = mComponentsToRemove.size();
                for (int i = 0; i < compsToRemoveNum; i++) {
                    ComponentName c = mComponentsToRemove.valueAt(i);
                    mTrackingMap.delete(userId, c);
                }
            }
        }
    };

    TileRequestTracker(Context context) {
        mContext = context;

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_DATA_CLEARED);
        intentFilter.addDataScheme("package");
        mContext.registerReceiverAsUser(mUninstallReceiver, UserHandle.ALL, intentFilter, null,
                null);
    }

    /**
     * Return whether this combination of {@code userId} and {@link ComponentName} should be
     * auto-denied.
     */
    boolean shouldBeDenied(int userId, ComponentName componentName) {
        synchronized (mLock) {
            return mTrackingMap.getOrDefault(userId, componentName, 0) >= MAX_NUM_DENIALS;
        }
    }

    /**
     * Add a new denial instance for a given {@code userId} and {@link ComponentName}.
     */
    void addDenial(int userId, ComponentName componentName) {
        synchronized (mLock) {
            int current = mTrackingMap.getOrDefault(userId, componentName, 0);
            mTrackingMap.add(userId, componentName, current + 1);
        }
    }

    /**
     * Reset the number of denied request for a given {@code userId} and {@link ComponentName}.
     */
    void resetRequests(int userId, ComponentName componentName) {
        synchronized (mLock) {
            mTrackingMap.delete(userId, componentName);
        }
    }

    void dump(FileDescriptor fd, IndentingPrintWriter pw, String[] args) {
        pw.println("TileRequestTracker:");
        pw.increaseIndent();
        synchronized (mLock) {
            mTrackingMap.forEach((user, componentName, value) -> {
                pw.println("user=" + user + ", " + componentName.toShortString() + ": " + value);
            });
        }
        pw.decreaseIndent();
    }
}
