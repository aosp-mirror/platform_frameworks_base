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

import static com.android.systemui.Dependency.BG_LOOPER_NAME;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.Dumpable;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

/**
 * Controller to keep track of applications that have requested access to given App Ops
 *
 * It can be subscribed to with callbacks. Additionally, it passes on the information to
 * NotificationPresenter to be displayed to the user.
 */
@Singleton
public class AppOpsControllerImpl implements AppOpsController,
        AppOpsManager.OnOpActiveChangedListener,
        AppOpsManager.OnOpNotedListener, Dumpable {

    private static final long NOTED_OP_TIME_DELAY_MS = 5000;
    private static final String TAG = "AppOpsControllerImpl";
    private static final boolean DEBUG = false;
    private final Context mContext;

    private final AppOpsManager mAppOps;
    private H mBGHandler;
    private final List<AppOpsController.Callback> mCallbacks = new ArrayList<>();
    private final ArrayMap<Integer, Set<Callback>> mCallbacksByCode = new ArrayMap<>();

    @GuardedBy("mActiveItems")
    private final List<AppOpItem> mActiveItems = new ArrayList<>();
    @GuardedBy("mNotedItems")
    private final List<AppOpItem> mNotedItems = new ArrayList<>();

    protected static final int[] OPS = new int[] {
            AppOpsManager.OP_CAMERA,
            AppOpsManager.OP_SYSTEM_ALERT_WINDOW,
            AppOpsManager.OP_RECORD_AUDIO,
            AppOpsManager.OP_COARSE_LOCATION,
            AppOpsManager.OP_FINE_LOCATION
    };

    @Inject
    public AppOpsControllerImpl(Context context, @Named(BG_LOOPER_NAME) Looper bgLooper) {
        mContext = context;
        mAppOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        mBGHandler = new H(bgLooper);
        final int numOps = OPS.length;
        for (int i = 0; i < numOps; i++) {
            mCallbacksByCode.put(OPS[i], new ArraySet<>());
        }
    }

    @VisibleForTesting
    protected void setBGHandler(H handler) {
        mBGHandler = handler;
    }

    @VisibleForTesting
    protected void setListening(boolean listening) {
        if (listening) {
            mAppOps.startWatchingActive(OPS, this);
            mAppOps.startWatchingNoted(OPS, this);
        } else {
            mAppOps.stopWatchingActive(this);
            mAppOps.stopWatchingNoted(this);
            mBGHandler.removeCallbacksAndMessages(null); // null removes all
            synchronized (mActiveItems) {
                mActiveItems.clear();
            }
            synchronized (mNotedItems) {
                mNotedItems.clear();
            }
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

    private AppOpItem getAppOpItem(List<AppOpItem> appOpList, int code, int uid,
            String packageName) {
        final int itemsQ = appOpList.size();
        for (int i = 0; i < itemsQ; i++) {
            AppOpItem item = appOpList.get(i);
            if (item.getCode() == code && item.getUid() == uid
                    && item.getPackageName().equals(packageName)) {
                return item;
            }
        }
        return null;
    }

    private boolean updateActives(int code, int uid, String packageName, boolean active) {
        synchronized (mActiveItems) {
            AppOpItem item = getAppOpItem(mActiveItems, code, uid, packageName);
            if (item == null && active) {
                item = new AppOpItem(code, uid, packageName, System.currentTimeMillis());
                mActiveItems.add(item);
                if (DEBUG) Log.w(TAG, "Added item: " + item.toString());
                return true;
            } else if (item != null && !active) {
                mActiveItems.remove(item);
                if (DEBUG) Log.w(TAG, "Removed item: " + item.toString());
                return true;
            }
            return false;
        }
    }

    private void removeNoted(int code, int uid, String packageName) {
        AppOpItem item;
        synchronized (mNotedItems) {
            item = getAppOpItem(mNotedItems, code, uid, packageName);
            if (item == null) return;
            mNotedItems.remove(item);
            if (DEBUG) Log.w(TAG, "Removed item: " + item.toString());
        }
        notifySuscribers(code, uid, packageName, false);
    }

    private void addNoted(int code, int uid, String packageName) {
        AppOpItem item;
        synchronized (mNotedItems) {
            item = getAppOpItem(mNotedItems, code, uid, packageName);
            if (item == null) {
                item = new AppOpItem(code, uid, packageName, System.currentTimeMillis());
                mNotedItems.add(item);
                if (DEBUG) Log.w(TAG, "Added item: " + item.toString());
            }
        }
        mBGHandler.scheduleRemoval(item, NOTED_OP_TIME_DELAY_MS);
    }

    /**
     * Does the app-op code refer to a user sensitive permission for the specified user id
     * and package. Only user sensitive permission should be shown to the user by default.
     *
     * @param appOpCode The code of the app-op.
     * @param uid The uid of the user.
     * @param packageName The name of the package.
     *
     * @return {@code true} iff the app-op item is user sensitive
     */
    private boolean isUserSensitive(int appOpCode, int uid, String packageName) {
        String permission = AppOpsManager.opToPermission(appOpCode);
        if (permission == null) {
            return false;
        }
        int permFlags = mContext.getPackageManager().getPermissionFlags(permission,
                packageName, UserHandle.getUserHandleForUid(uid));
        return (permFlags & PackageManager.FLAG_PERMISSION_USER_SENSITIVE_WHEN_GRANTED) != 0;
    }

    /**
     * Does the app-op item refer to an operation that should be shown to the user.
     * Only specficic ops (like SYSTEM_ALERT_WINDOW) or ops that refer to user sensitive
     * permission should be shown to the user by default.
     *
     * @param item The item
     *
     * @return {@code true} iff the app-op item should be shown to the user
     */
    private boolean isUserVisible(AppOpItem item) {
        return isUserVisible(item.getCode(), item.getUid(), item.getPackageName());
    }


    /**
     * Does the app-op, uid and package name, refer to an operation that should be shown to the
     * user. Only specficic ops (like {@link AppOpsManager.OP_SYSTEM_ALERT_WINDOW}) or
     * ops that refer to user sensitive permission should be shown to the user by default.
     *
     * @param item The item
     *
     * @return {@code true} iff the app-op for should be shown to the user
     */
    private boolean isUserVisible(int appOpCode, int uid, String packageName) {
        // currently OP_SYSTEM_ALERT_WINDOW does not correspond to a platform permission
        // which may be user senstive, so for now always show it to the user.
        if (appOpCode == AppOpsManager.OP_SYSTEM_ALERT_WINDOW) {
            return true;
        }

        return isUserSensitive(appOpCode, uid, packageName);
    }

    /**
     * Returns a copy of the list containing all the active AppOps that the controller tracks.
     *
     * @return List of active AppOps information
     */
    public List<AppOpItem> getActiveAppOps() {
        return getActiveAppOpsForUser(UserHandle.USER_ALL);
    }

    /**
     * Returns a copy of the list containing all the active AppOps that the controller tracks, for
     * a given user id.
     *
     * @param userId User id to track, can be {@link UserHandle#USER_ALL}
     *
     * @return List of active AppOps information for that user id
     */
    public List<AppOpItem> getActiveAppOpsForUser(int userId) {
        List<AppOpItem> list = new ArrayList<>();
        synchronized (mActiveItems) {
            final int numActiveItems = mActiveItems.size();
            for (int i = 0; i < numActiveItems; i++) {
                AppOpItem item = mActiveItems.get(i);
                if ((userId == UserHandle.USER_ALL || UserHandle.getUserId(item.getUid()) == userId)
                        && isUserVisible(item)) {
                    list.add(item);
                }
            }
        }
        synchronized (mNotedItems) {
            final int numNotedItems = mNotedItems.size();
            for (int i = 0; i < numNotedItems; i++) {
                AppOpItem item = mNotedItems.get(i);
                if ((userId == UserHandle.USER_ALL || UserHandle.getUserId(item.getUid()) == userId)
                        && isUserVisible(item)) {
                    list.add(item);
                }
            }
        }
        return list;
    }

    @Override
    public void onOpActiveChanged(int code, int uid, String packageName, boolean active) {
        if (updateActives(code, uid, packageName, active)) {
            notifySuscribers(code, uid, packageName, active);
        }
    }

    @Override
    public void onOpNoted(int code, int uid, String packageName, int result) {
        if (DEBUG) {
            Log.w(TAG, "Op: " + code + " with result " + AppOpsManager.MODE_NAMES[result]);
        }
        if (result != AppOpsManager.MODE_ALLOWED) return;
        addNoted(code, uid, packageName);
        notifySuscribers(code, uid, packageName, true);
    }

    private void notifySuscribers(int code, int uid, String packageName, boolean active) {
        if (mCallbacksByCode.containsKey(code)
                && isUserVisible(code, uid, packageName)) {
            for (Callback cb: mCallbacksByCode.get(code)) {
                cb.onActiveStateChanged(code, uid, packageName, active);
            }
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("AppOpsController state:");
        pw.println("  Active Items:");
        for (int i = 0; i < mActiveItems.size(); i++) {
            final AppOpItem item = mActiveItems.get(i);
            pw.print("    "); pw.println(item.toString());
        }
        pw.println("  Noted Items:");
        for (int i = 0; i < mNotedItems.size(); i++) {
            final AppOpItem item = mNotedItems.get(i);
            pw.print("    "); pw.println(item.toString());
        }

    }

    protected final class H extends Handler {
        H(Looper looper) {
            super(looper);
        }

        public void scheduleRemoval(AppOpItem item, long timeToRemoval) {
            removeCallbacksAndMessages(item);
            postDelayed(new Runnable() {
                @Override
                public void run() {
                    removeNoted(item.getCode(), item.getUid(), item.getPackageName());
                }
            }, item, timeToRemoval);
        }
    }
}
