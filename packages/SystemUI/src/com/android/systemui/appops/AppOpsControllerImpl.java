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

package com.android.systemui.appops;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Controller to keep track of applications that have requested access to given App Ops
 *
 * It can be subscribed to with callbacks. Additionally, it passes on the information to
 * NotificationPresenter to be displayed to the user.
 */
public class AppOpsControllerImpl implements AppOpsController,
        AppOpsManager.OnOpActiveChangedListener {

    private static final long LOCATION_TIME_DELAY_MS = 5000;
    private static final String TAG = "AppOpsControllerImpl";
    private static final boolean DEBUG = false;
    private final Context mContext;

    protected final AppOpsManager mAppOps;
    private final H mBGHandler;
    private final List<AppOpsController.Callback> mCallbacks = new ArrayList<>();
    private final ArrayMap<Integer, Set<Callback>> mCallbacksByCode = new ArrayMap<>();
    @GuardedBy("mActiveItems")
    private final List<AppOpItem> mActiveItems = new ArrayList<>();

    protected static final int[] OPS = new int[] {AppOpsManager.OP_CAMERA,
            AppOpsManager.OP_SYSTEM_ALERT_WINDOW,
            AppOpsManager.OP_RECORD_AUDIO,
            AppOpsManager.OP_COARSE_LOCATION,
            AppOpsManager.OP_FINE_LOCATION};

    public AppOpsControllerImpl(Context context, Looper bgLooper) {
        mContext = context;
        mAppOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        mBGHandler = new H(bgLooper);
        final int numOps = OPS.length;
        for (int i = 0; i < numOps; i++) {
            mCallbacksByCode.put(OPS[i], new ArraySet<>());
        }
    }

    @VisibleForTesting
    protected void setListening(boolean listening) {
        if (listening) {
            mAppOps.startWatchingActive(OPS, this);
        } else {
            mAppOps.stopWatchingActive(this);
        }
    }

    /**
     * Adds a callback that will get notifified when an AppOp of the type the controller tracks
     * changes
     *
     * @param callback Callback to report changes
     * @param opsCodes App Ops the callback is interested in checking
     *
     * @see #removeCallback(int[], Callback)
     */
    @Override
    public void addCallback(int[] opsCodes, AppOpsController.Callback callback) {
        boolean added = false;
        final int numCodes = opsCodes.length;
        for (int i = 0; i < numCodes; i++) {
            if (mCallbacksByCode.containsKey(opsCodes[i])) {
                mCallbacksByCode.get(opsCodes[i]).add(callback);
                added = true;
            } else {
                if (DEBUG) Log.wtf(TAG, "APP_OP " + opsCodes[i] + " not supported");
            }
        }
        if (added) mCallbacks.add(callback);
        if (!mCallbacks.isEmpty()) setListening(true);
    }

    /**
     * Removes a callback from those notified when an AppOp of the type the controller tracks
     * changes
     *
     * @param callback Callback to stop reporting changes
     * @param opsCodes App Ops the callback was interested in checking
     *
     * @see #addCallback(int[], Callback)
     */
    @Override
    public void removeCallback(int[] opsCodes, AppOpsController.Callback callback) {
        final int numCodes = opsCodes.length;
        for (int i = 0; i < numCodes; i++) {
            if (mCallbacksByCode.containsKey(opsCodes[i])) {
                mCallbacksByCode.get(opsCodes[i]).remove(callback);
            }
        }
        mCallbacks.remove(callback);
        if (mCallbacks.isEmpty()) setListening(false);
    }

    private AppOpItem getAppOpItem(int code, int uid, String packageName) {
        final int itemsQ = mActiveItems.size();
        for (int i = 0; i < itemsQ; i++) {
            AppOpItem item = mActiveItems.get(i);
            if (item.getCode() == code && item.getUid() == uid
                    && item.getPackageName().equals(packageName)) {
                return item;
            }
        }
        return null;
    }

    private boolean updateActives(int code, int uid, String packageName, boolean active) {
        synchronized (mActiveItems) {
            AppOpItem item = getAppOpItem(code, uid, packageName);
            if (item == null && active) {
                item = new AppOpItem(code, uid, packageName, System.currentTimeMillis());
                mActiveItems.add(item);
                if (code == AppOpsManager.OP_COARSE_LOCATION
                        || code == AppOpsManager.OP_FINE_LOCATION) {
                    mBGHandler.scheduleRemoval(item, LOCATION_TIME_DELAY_MS);
                }
                if (DEBUG) Log.w(TAG, "Added item: " + item.toString());
                return true;
            } else if (item != null && !active) {
                mActiveItems.remove(item);
                if (DEBUG) Log.w(TAG, "Removed item: " + item.toString());
                return true;
            } else if (item != null && active
                    && (code == AppOpsManager.OP_COARSE_LOCATION
                            || code == AppOpsManager.OP_FINE_LOCATION)) {
                mBGHandler.scheduleRemoval(item, LOCATION_TIME_DELAY_MS);
                return true;
            }
            return false;
        }
    }

    /**
     * Returns a copy of the list containing all the active AppOps that the controller tracks.
     *
     * @return List of active AppOps information
     */
    public List<AppOpItem> getActiveAppOps() {
        synchronized (mActiveItems) {
            return new ArrayList<>(mActiveItems);
        }
    }

    /**
     * Returns a copy of the list containing all the active AppOps that the controller tracks, for
     * a given user id.
     *
     * @param userId User id to track
     *
     * @return List of active AppOps information for that user id
     */
    public List<AppOpItem> getActiveAppOpsForUser(int userId) {
        List<AppOpItem> list = new ArrayList<>();
        synchronized (mActiveItems) {
            final int numActiveItems = mActiveItems.size();
            for (int i = 0; i < numActiveItems; i++) {
                AppOpItem item = mActiveItems.get(i);
                if (UserHandle.getUserId(item.getUid()) == userId) {
                    list.add(item);
                }
            }
        }
        return list;
    }

    @Override
    public void onOpActiveChanged(int code, int uid, String packageName, boolean active) {
        if (updateActives(code, uid, packageName, active)) {
            for (Callback cb: mCallbacksByCode.get(code)) {
                cb.onActiveStateChanged(code, uid, packageName, active);
            }
        }
    }

    private final class H extends Handler {
        H(Looper looper) {
            super(looper);
        }

        public void scheduleRemoval(AppOpItem item, long timeToRemoval) {
            removeCallbacksAndMessages(item);
            postDelayed(new Runnable() {
                @Override
                public void run() {
                    onOpActiveChanged(item.getCode(), item.getUid(),
                            item.getPackageName(), false);
                }
            }, item, timeToRemoval);
        }
    }
}
