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

import static android.media.AudioManager.ACTION_MICROPHONE_MUTE_CHANGED;

import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;

import androidx.annotation.WorkerThread;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.Dumpable;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dump.DumpManager;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Controller to keep track of applications that have requested access to given App Ops
 *
 * It can be subscribed to with callbacks. Additionally, it passes on the information to
 * NotificationPresenter to be displayed to the user.
 */
@Singleton
public class AppOpsControllerImpl extends BroadcastReceiver implements AppOpsController,
        AppOpsManager.OnOpActiveChangedInternalListener,
        AppOpsManager.OnOpNotedListener, Dumpable {

    // This is the minimum time that we will keep AppOps that are noted on record. If multiple
    // occurrences of the same (op, package, uid) happen in a shorter interval, they will not be
    // notified to listeners.
    private static final long NOTED_OP_TIME_DELAY_MS = 5000;
    private static final String TAG = "AppOpsControllerImpl";
    private static final boolean DEBUG = false;

    private final BroadcastDispatcher mDispatcher;
    private final AppOpsManager mAppOps;
    private final AudioManager mAudioManager;
    private final LocationManager mLocationManager;

    // mLocationProviderPackages are cached and updated only occasionally
    private static final long LOCATION_PROVIDER_UPDATE_FREQUENCY_MS = 30000;
    private long mLastLocationProviderPackageUpdate;
    private List<String> mLocationProviderPackages;

    private H mBGHandler;
    private final List<AppOpsController.Callback> mCallbacks = new ArrayList<>();
    private final ArrayMap<Integer, Set<Callback>> mCallbacksByCode = new ArrayMap<>();
    private final PermissionFlagsCache mFlagsCache;
    private boolean mListening;
    private boolean mMicMuted;

    @GuardedBy("mActiveItems")
    private final List<AppOpItem> mActiveItems = new ArrayList<>();
    @GuardedBy("mNotedItems")
    private final List<AppOpItem> mNotedItems = new ArrayList<>();
    @GuardedBy("mActiveItems")
    private final SparseArray<ArrayList<AudioRecordingConfiguration>> mRecordingsByUid =
            new SparseArray<>();

    protected static final int[] OPS = new int[] {
            AppOpsManager.OP_CAMERA,
            AppOpsManager.OP_PHONE_CALL_CAMERA,
            AppOpsManager.OP_SYSTEM_ALERT_WINDOW,
            AppOpsManager.OP_RECORD_AUDIO,
            AppOpsManager.OP_PHONE_CALL_MICROPHONE,
            AppOpsManager.OP_COARSE_LOCATION,
            AppOpsManager.OP_FINE_LOCATION
    };

    @Inject
    public AppOpsControllerImpl(
            Context context,
            @Background Looper bgLooper,
            DumpManager dumpManager,
            PermissionFlagsCache cache,
            AudioManager audioManager,
            BroadcastDispatcher dispatcher
    ) {
        mDispatcher = dispatcher;
        mAppOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        mFlagsCache = cache;
        mBGHandler = new H(bgLooper);
        final int numOps = OPS.length;
        for (int i = 0; i < numOps; i++) {
            mCallbacksByCode.put(OPS[i], new ArraySet<>());
        }
        mAudioManager = audioManager;
        mMicMuted = audioManager.isMicrophoneMute();
        mLocationManager = context.getSystemService(LocationManager.class);
        dumpManager.registerDumpable(TAG, this);
    }

    @VisibleForTesting
    protected void setBGHandler(H handler) {
        mBGHandler = handler;
    }

    @VisibleForTesting
    protected void setListening(boolean listening) {
        mListening = listening;
        if (listening) {
            mAppOps.startWatchingActive(OPS, this);
            mAppOps.startWatchingNoted(OPS, this);
            mAudioManager.registerAudioRecordingCallback(mAudioRecordingCallback, mBGHandler);
            mBGHandler.post(() -> mAudioRecordingCallback.onRecordingConfigChanged(
                    mAudioManager.getActiveRecordingConfigurations()));
            mDispatcher.registerReceiverWithHandler(this,
                    new IntentFilter(ACTION_MICROPHONE_MUTE_CHANGED), mBGHandler);

        } else {
            mAppOps.stopWatchingActive(this);
            mAppOps.stopWatchingNoted(this);
            mAudioManager.unregisterAudioRecordingCallback(mAudioRecordingCallback);

            mBGHandler.removeCallbacksAndMessages(null); // null removes all
            mDispatcher.unregisterReceiver(this);
            synchronized (mActiveItems) {
                mActiveItems.clear();
                mRecordingsByUid.clear();
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

    // Find item number in list, only call if the list passed is locked
    private AppOpItem getAppOpItemLocked(List<AppOpItem> appOpList, int code, int uid,
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
            AppOpItem item = getAppOpItemLocked(mActiveItems, code, uid, packageName);
            if (item == null && active) {
                item = new AppOpItem(code, uid, packageName, System.currentTimeMillis());
                if (code == AppOpsManager.OP_RECORD_AUDIO) {
                    item.setSilenced(isAnyRecordingPausedLocked(uid));
                }
                mActiveItems.add(item);
                if (DEBUG) Log.w(TAG, "Added item: " + item.toString());
                return !item.isSilenced();
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
            item = getAppOpItemLocked(mNotedItems, code, uid, packageName);
            if (item == null) return;
            mNotedItems.remove(item);
            if (DEBUG) Log.w(TAG, "Removed item: " + item.toString());
        }
        boolean active;
        // Check if the item is also active
        synchronized (mActiveItems) {
            active = getAppOpItemLocked(mActiveItems, code, uid, packageName) != null;
        }
        if (!active) {
            notifySuscribersWorker(code, uid, packageName, false);
        }
    }

    private boolean addNoted(int code, int uid, String packageName) {
        AppOpItem item;
        boolean createdNew = false;
        synchronized (mNotedItems) {
            item = getAppOpItemLocked(mNotedItems, code, uid, packageName);
            if (item == null) {
                item = new AppOpItem(code, uid, packageName, System.currentTimeMillis());
                mNotedItems.add(item);
                if (DEBUG) Log.w(TAG, "Added item: " + item.toString());
                createdNew = true;
            }
        }
        // We should keep this so we make sure it cannot time out.
        mBGHandler.removeCallbacksAndMessages(item);
        mBGHandler.scheduleRemoval(item, NOTED_OP_TIME_DELAY_MS);
        return createdNew;
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
        int permFlags = mFlagsCache.getPermissionFlags(permission,
                packageName, uid);
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
     * Checks if a package is the current location provider.
     *
     * <p>Data is cached to avoid too many calls into system server
     *
     * @param packageName The package that might be the location provider
     *
     * @return {@code true} iff the package is the location provider.
     */
    private boolean isLocationProvider(String packageName) {
        long now = System.currentTimeMillis();

        if (mLastLocationProviderPackageUpdate + LOCATION_PROVIDER_UPDATE_FREQUENCY_MS < now) {
            mLastLocationProviderPackageUpdate = now;
            mLocationProviderPackages = mLocationManager.getProviderPackages(
                    LocationManager.FUSED_PROVIDER);
        }

        return mLocationProviderPackages.contains(packageName);
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
        // currently OP_SYSTEM_ALERT_WINDOW and OP_MONITOR_HIGH_POWER_LOCATION
        // does not correspond to a platform permission
        // which may be user sensitive, so for now always show it to the user.
        if (appOpCode == AppOpsManager.OP_SYSTEM_ALERT_WINDOW
                || appOpCode == AppOpsManager.OP_MONITOR_HIGH_POWER_LOCATION
                || appOpCode == AppOpsManager.OP_PHONE_CALL_CAMERA
                || appOpCode == AppOpsManager.OP_PHONE_CALL_MICROPHONE) {
            return true;
        }

        if (appOpCode == AppOpsManager.OP_CAMERA && isLocationProvider(packageName)) {
            return true;
        }

        return isUserSensitive(appOpCode, uid, packageName);
    }

    /**
     * Returns a copy of the list containing all the active AppOps that the controller tracks.
     *
     * Call from a worker thread as it may perform long operations.
     *
     * @return List of active AppOps information
     */
    @WorkerThread
    public List<AppOpItem> getActiveAppOps() {
        return getActiveAppOpsForUser(UserHandle.USER_ALL);
    }

    /**
     * Returns a copy of the list containing all the active AppOps that the controller tracks, for
     * a given user id.
     *
     * Call from a worker thread as it may perform long operations.
     *
     * @param userId User id to track, can be {@link UserHandle#USER_ALL}
     *
     * @return List of active AppOps information for that user id
     */
    @WorkerThread
    public List<AppOpItem> getActiveAppOpsForUser(int userId) {
        List<AppOpItem> list = new ArrayList<>();
        synchronized (mActiveItems) {
            final int numActiveItems = mActiveItems.size();
            for (int i = 0; i < numActiveItems; i++) {
                AppOpItem item = mActiveItems.get(i);
                if ((userId == UserHandle.USER_ALL
                        || UserHandle.getUserId(item.getUid()) == userId)
                        && isUserVisible(item) && !item.isSilenced()) {
                    list.add(item);
                }
            }
        }
        synchronized (mNotedItems) {
            final int numNotedItems = mNotedItems.size();
            for (int i = 0; i < numNotedItems; i++) {
                AppOpItem item = mNotedItems.get(i);
                if ((userId == UserHandle.USER_ALL
                        || UserHandle.getUserId(item.getUid()) == userId)
                        && isUserVisible(item)) {
                    list.add(item);
                }
            }
        }
        return list;
    }

    private void notifySuscribers(int code, int uid, String packageName, boolean active) {
        mBGHandler.post(() -> notifySuscribersWorker(code, uid, packageName, active));
    }

    @Override
    public void onOpActiveChanged(int code, int uid, String packageName, boolean active) {
        if (DEBUG) {
            Log.w(TAG, String.format("onActiveChanged(%d,%d,%s,%s", code, uid, packageName,
                    Boolean.toString(active)));
        }
        boolean activeChanged = updateActives(code, uid, packageName, active);
        if (!activeChanged) return; // early return
        // Check if the item is also noted, in that case, there's no update.
        boolean alsoNoted;
        synchronized (mNotedItems) {
            alsoNoted = getAppOpItemLocked(mNotedItems, code, uid, packageName) != null;
        }
        // If active is true, we only send the update if the op is not actively noted (already true)
        // If active is false, we only send the update if the op is not actively noted (prevent
        // early removal)
        if (!alsoNoted) {
            notifySuscribers(code, uid, packageName, active);
        }
    }

    @Override
    public void onOpNoted(int code, int uid, String packageName, int result) {
        if (DEBUG) {
            Log.w(TAG, "Noted op: " + code + " with result "
                    + AppOpsManager.MODE_NAMES[result] + " for package " + packageName);
        }
        if (result != AppOpsManager.MODE_ALLOWED) return;
        boolean notedAdded = addNoted(code, uid, packageName);
        if (!notedAdded) return; // early return
        boolean alsoActive;
        synchronized (mActiveItems) {
            alsoActive = getAppOpItemLocked(mActiveItems, code, uid, packageName) != null;
        }
        if (!alsoActive) {
            notifySuscribers(code, uid, packageName, true);
        }
    }

    private void notifySuscribersWorker(int code, int uid, String packageName, boolean active) {
        if (mCallbacksByCode.containsKey(code) && isUserVisible(code, uid, packageName)) {
            if (DEBUG) Log.d(TAG, "Notifying of change in package " + packageName);
            for (Callback cb: mCallbacksByCode.get(code)) {
                cb.onActiveStateChanged(code, uid, packageName, active);
            }
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("AppOpsController state:");
        pw.println("  Listening: " + mListening);
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

    private boolean isAnyRecordingPausedLocked(int uid) {
        if (mMicMuted) {
            return true;
        }
        List<AudioRecordingConfiguration> configs = mRecordingsByUid.get(uid);
        if (configs == null) return false;
        int configsNum = configs.size();
        for (int i = 0; i < configsNum; i++) {
            AudioRecordingConfiguration config = configs.get(i);
            if (config.isClientSilenced()) return true;
        }
        return false;
    }

    private void updateRecordingPausedStatus() {
        synchronized (mActiveItems) {
            int size = mActiveItems.size();
            for (int i = 0; i < size; i++) {
                AppOpItem item = mActiveItems.get(i);
                if (item.getCode() == AppOpsManager.OP_RECORD_AUDIO) {
                    boolean paused = isAnyRecordingPausedLocked(item.getUid());
                    if (item.isSilenced() != paused) {
                        item.setSilenced(paused);
                        notifySuscribers(
                                item.getCode(),
                                item.getUid(),
                                item.getPackageName(),
                                !item.isSilenced()
                        );
                    }
                }
            }
        }
    }

    private AudioManager.AudioRecordingCallback mAudioRecordingCallback =
            new AudioManager.AudioRecordingCallback() {
        @Override
        public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
            synchronized (mActiveItems) {
                mRecordingsByUid.clear();
                final int recordingsCount = configs.size();
                for (int i = 0; i < recordingsCount; i++) {
                    AudioRecordingConfiguration recording = configs.get(i);

                    ArrayList<AudioRecordingConfiguration> recordings = mRecordingsByUid.get(
                            recording.getClientUid());
                    if (recordings == null) {
                        recordings = new ArrayList<>();
                        mRecordingsByUid.put(recording.getClientUid(), recordings);
                    }
                    recordings.add(recording);
                }
            }
            updateRecordingPausedStatus();
        }
    };

    @Override
    public void onReceive(Context context, Intent intent) {
        mMicMuted = mAudioManager.isMicrophoneMute();
        updateRecordingPausedStatus();
    }

    protected class H extends Handler {
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
