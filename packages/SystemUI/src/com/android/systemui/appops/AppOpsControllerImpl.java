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

import static android.hardware.SensorPrivacyManager.Sensors.CAMERA;
import static android.hardware.SensorPrivacyManager.Sensors.MICROPHONE;
import static android.media.AudioManager.ACTION_MICROPHONE_MUTE_CHANGED;

import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.permission.PermissionManager;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;

import androidx.annotation.WorkerThread;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.Dumpable;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.statusbar.policy.IndividualSensorPrivacyController;
import com.android.systemui.util.Assert;
import com.android.systemui.util.time.SystemClock;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Controller to keep track of applications that have requested access to given App Ops
 *
 * It can be subscribed to with callbacks. Additionally, it passes on the information to
 * NotificationPresenter to be displayed to the user.
 */
@SysUISingleton
public class AppOpsControllerImpl extends BroadcastReceiver implements AppOpsController,
        AppOpsManager.OnOpActiveChangedListener,
        AppOpsManager.OnOpNotedInternalListener, IndividualSensorPrivacyController.Callback,
        Dumpable {

    // This is the minimum time that we will keep AppOps that are noted on record. If multiple
    // occurrences of the same (op, package, uid) happen in a shorter interval, they will not be
    // notified to listeners.
    private static final long NOTED_OP_TIME_DELAY_MS = 5000;
    private static final String TAG = "AppOpsControllerImpl";
    private static final boolean DEBUG = false;

    private final BroadcastDispatcher mDispatcher;
    private final Context mContext;
    private final AppOpsManager mAppOps;
    private final AudioManager mAudioManager;
    private final IndividualSensorPrivacyController mSensorPrivacyController;
    private final SystemClock mClock;

    private H mBGHandler;
    private final Executor mBgExecutor;
    private final List<AppOpsController.Callback> mCallbacks = new ArrayList<>();
    private final SparseArray<Set<Callback>> mCallbacksByCode = new SparseArray<>();
    private boolean mListening;
    private boolean mMicMuted;
    private boolean mCameraDisabled;

    @GuardedBy("mActiveItems")
    private final List<AppOpItem> mActiveItems = new ArrayList<>();
    @GuardedBy("mNotedItems")
    private final List<AppOpItem> mNotedItems = new ArrayList<>();
    @GuardedBy("mActiveItems")
    private final SparseArray<ArrayList<AudioRecordingConfiguration>> mRecordingsByUid =
            new SparseArray<>();

    @VisibleForTesting
    protected static final int[] OPS_MIC = new int[] {
            AppOpsManager.OP_RECORD_AUDIO,
            AppOpsManager.OP_PHONE_CALL_MICROPHONE,
            AppOpsManager.OP_RECEIVE_AMBIENT_TRIGGER_AUDIO,
            AppOpsManager.OP_RECEIVE_SANDBOX_TRIGGER_AUDIO,
            AppOpsManager.OP_RECEIVE_EXPLICIT_USER_INTERACTION_AUDIO
    };

    protected static final int[] OPS_CAMERA = new int[] {
            AppOpsManager.OP_CAMERA,
            AppOpsManager.OP_PHONE_CALL_CAMERA
    };

    protected static final int[] OPS_LOC = new int[] {
            AppOpsManager.OP_FINE_LOCATION,
            AppOpsManager.OP_COARSE_LOCATION,
            AppOpsManager.OP_MONITOR_HIGH_POWER_LOCATION
    };

    protected static final int[] OPS_OTHERS = new int[] {
            AppOpsManager.OP_SYSTEM_ALERT_WINDOW
    };

    protected static final int[] OPS = concatOps(OPS_MIC, OPS_CAMERA, OPS_LOC, OPS_OTHERS);

    /**
     * @param opArrays the given op arrays.
     * @return the concatenations of the given op arrays. Null arrays are treated as empty.
     */
    private static int[] concatOps(@Nullable int[]...opArrays) {
        if (opArrays == null) {
            return new int[0];
        }
        int totalLength = 0;
        for (int[] opArray : opArrays) {
            if (opArray == null || opArray.length == 0) {
                continue;
            }
            totalLength += opArray.length;
        }
        final int[] concatOps = new int[totalLength];
        int index = 0;
        for (int[] opArray : opArrays) {
            if (opArray == null || opArray.length == 0) continue;
            System.arraycopy(opArray, 0, concatOps, index, opArray.length);
            index += opArray.length;
        }
        return concatOps;
    }

    @Inject
    public AppOpsControllerImpl(
            Context context,
            @Background Looper bgLooper,
            @Background Executor bgExecutor,
            DumpManager dumpManager,
            AudioManager audioManager,
            IndividualSensorPrivacyController sensorPrivacyController,
            BroadcastDispatcher dispatcher,
            SystemClock clock
    ) {
        mDispatcher = dispatcher;
        mAppOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        mBGHandler = new H(bgLooper);
        mBgExecutor = bgExecutor;
        final int numOps = OPS.length;
        for (int i = 0; i < numOps; i++) {
            mCallbacksByCode.put(OPS[i], new ArraySet<>());
        }
        mAudioManager = audioManager;
        mSensorPrivacyController = sensorPrivacyController;
        mMicMuted = audioManager.isMicrophoneMute()
                || mSensorPrivacyController.isSensorBlocked(MICROPHONE);
        mCameraDisabled = mSensorPrivacyController.isSensorBlocked(CAMERA);
        mContext = context;
        mClock = clock;
        dumpManager.registerDumpable(TAG, this);
    }

    @VisibleForTesting
    protected void setBGHandler(H handler) {
        mBGHandler = handler;
    }

    @VisibleForTesting
    protected void setListening(boolean listening) {
        mListening = listening;
        // Move IPCs to the background.
        mBgExecutor.execute(() -> {
            if (listening) {
                // System UI could be restarted while ops are active, so fetch the currently active
                // ops once System UI starts listening again -- see b/294104969.
                fetchCurrentActiveOps();

                mAppOps.startWatchingActive(OPS, this);
                mAppOps.startWatchingNoted(OPS, this);
                mAudioManager.registerAudioRecordingCallback(mAudioRecordingCallback, mBGHandler);
                mSensorPrivacyController.addCallback(this);

                mMicMuted = mAudioManager.isMicrophoneMute()
                        || mSensorPrivacyController.isSensorBlocked(MICROPHONE);
                mCameraDisabled = mSensorPrivacyController.isSensorBlocked(CAMERA);

                mBGHandler.post(() -> mAudioRecordingCallback.onRecordingConfigChanged(
                        mAudioManager.getActiveRecordingConfigurations()));
                mDispatcher.registerReceiverWithHandler(this,
                        new IntentFilter(ACTION_MICROPHONE_MUTE_CHANGED), mBGHandler);
            } else {
                mAppOps.stopWatchingActive(this);
                mAppOps.stopWatchingNoted(this);
                mAudioManager.unregisterAudioRecordingCallback(mAudioRecordingCallback);
                mSensorPrivacyController.removeCallback(this);

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
        });
    }

    private void fetchCurrentActiveOps() {
        List<AppOpsManager.PackageOps> packageOps = mAppOps.getPackagesForOps(OPS);
        if (packageOps == null) {
            return;
        }
        for (AppOpsManager.PackageOps op : packageOps) {
            for (AppOpsManager.OpEntry entry : op.getOps()) {
                for (Map.Entry<String, AppOpsManager.AttributedOpEntry> attributedOpEntry :
                        entry.getAttributedOpEntries().entrySet()) {
                    if (attributedOpEntry.getValue().isRunning()) {
                        onOpActiveChanged(
                                entry.getOpStr(),
                                op.getUid(),
                                op.getPackageName(),
                                /* attributionTag= */ attributedOpEntry.getKey(),
                                /* active= */ true,
                                // AppOpsManager doesn't have a way to fetch attribution flags or
                                // chain ID given an op entry, so default them to none.
                                AppOpsManager.ATTRIBUTION_FLAGS_NONE,
                                AppOpsManager.ATTRIBUTION_CHAIN_ID_NONE);
                    }
                }
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
            if (mCallbacksByCode.contains(opsCodes[i])) {
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
            if (mCallbacksByCode.contains(opsCodes[i])) {
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
                item = new AppOpItem(code, uid, packageName, mClock.elapsedRealtime());
                if (isOpMicrophone(code)) {
                    item.setDisabled(isAnyRecordingPausedLocked(uid));
                } else if (isOpCamera(code)) {
                    item.setDisabled(mCameraDisabled);
                }
                mActiveItems.add(item);
                if (DEBUG) Log.w(TAG, "Added item: " + item.toString());
                return !item.isDisabled();
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
                item = new AppOpItem(code, uid, packageName, mClock.elapsedRealtime());
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

    private boolean isUserVisible(String packageName) {
        return PermissionManager.shouldShowPackageForIndicatorCached(mContext, packageName);
    }

    @WorkerThread
    public List<AppOpItem> getActiveAppOps() {
        return getActiveAppOps(false);
    }

    /**
     * Returns a copy of the list containing all the active AppOps that the controller tracks.
     *
     * Call from a worker thread as it may perform long operations.
     *
     * @return List of active AppOps information
     */
    @WorkerThread
    public List<AppOpItem> getActiveAppOps(boolean showPaused) {
        return getActiveAppOpsForUser(UserHandle.USER_ALL, showPaused);
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
    public List<AppOpItem> getActiveAppOpsForUser(int userId, boolean showPaused) {
        Assert.isNotMainThread();
        List<AppOpItem> list = new ArrayList<>();
        synchronized (mActiveItems) {
            final int numActiveItems = mActiveItems.size();
            for (int i = 0; i < numActiveItems; i++) {
                AppOpItem item = mActiveItems.get(i);
                if ((userId == UserHandle.USER_ALL
                        || UserHandle.getUserId(item.getUid()) == userId)
                        && isUserVisible(item.getPackageName())
                        && (showPaused || !item.isDisabled())) {
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
                        && isUserVisible(item.getPackageName())) {
                    list.add(item);
                }
            }
        }
        return list;
    }

    private void notifySuscribers(int code, int uid, String packageName, boolean active) {
        mBGHandler.post(() -> notifySuscribersWorker(code, uid, packageName, active));
    }

    /**
     * Required to override, delegate to other. Should not be called.
     */
    public void onOpActiveChanged(String op, int uid, String packageName, boolean active) {
        onOpActiveChanged(op, uid, packageName, null, active,
                AppOpsManager.ATTRIBUTION_FLAGS_NONE, AppOpsManager.ATTRIBUTION_CHAIN_ID_NONE);
    }

    // Get active app ops, and check if their attributions are trusted
    @Override
    public void onOpActiveChanged(String op, int uid, String packageName, String attributionTag,
            boolean active, int attributionFlags, int attributionChainId) {
        int code = AppOpsManager.strOpToOp(op);
        if (DEBUG) {
            Log.w(TAG, String.format("onActiveChanged(%d,%d,%s,%s,%d,%d)", code, uid, packageName,
                    Boolean.toString(active), attributionChainId, attributionFlags));
        }
        if (active && attributionChainId != AppOpsManager.ATTRIBUTION_CHAIN_ID_NONE
                && attributionFlags != AppOpsManager.ATTRIBUTION_FLAGS_NONE
                && (attributionFlags & AppOpsManager.ATTRIBUTION_FLAG_ACCESSOR) == 0
                && (attributionFlags & AppOpsManager.ATTRIBUTION_FLAG_TRUSTED) == 0) {
            // if this attribution chain isn't trusted, and this isn't the accessor, do not show it.
            return;
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
    public void onOpNoted(int code, int uid, String packageName,
            String attributionTag, @AppOpsManager.OpFlags int flags,
            @AppOpsManager.Mode int result) {
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
        if (mCallbacksByCode.contains(code) && isUserVisible(packageName)) {
            if (DEBUG) Log.d(TAG, "Notifying of change in package " + packageName);
            for (Callback cb: mCallbacksByCode.get(code)) {
                cb.onActiveStateChanged(code, uid, packageName, active);
            }
        }
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
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

    private void updateSensorDisabledStatus() {
        synchronized (mActiveItems) {
            int size = mActiveItems.size();
            for (int i = 0; i < size; i++) {
                AppOpItem item = mActiveItems.get(i);

                boolean paused = false;
                if (isOpMicrophone(item.getCode())) {
                    paused = isAnyRecordingPausedLocked(item.getUid());
                } else if (isOpCamera(item.getCode())) {
                    paused = mCameraDisabled;
                }

                if (item.isDisabled() != paused) {
                    item.setDisabled(paused);
                    notifySuscribers(
                            item.getCode(),
                            item.getUid(),
                            item.getPackageName(),
                            !item.isDisabled()
                    );
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
            updateSensorDisabledStatus();
        }
    };

    @Override
    public void onReceive(Context context, Intent intent) {
        mMicMuted = mAudioManager.isMicrophoneMute()
                || mSensorPrivacyController.isSensorBlocked(MICROPHONE);
        updateSensorDisabledStatus();
    }

    @Override
    public void onSensorBlockedChanged(int sensor, boolean blocked) {
        mBGHandler.post(() -> {
            if (sensor == CAMERA) {
                mCameraDisabled = blocked;
            } else if (sensor == MICROPHONE) {
                mMicMuted = mAudioManager.isMicrophoneMute() || blocked;
            }
            updateSensorDisabledStatus();
        });
    }

    @Override
    public boolean isMicMuted() {
        return mMicMuted;
    }

    private boolean isOpCamera(int op) {
        for (int i = 0; i < OPS_CAMERA.length; i++) {
            if (op == OPS_CAMERA[i]) return true;
        }
        return false;
    }

    private boolean isOpMicrophone(int op) {
        for (int i = 0; i < OPS_MIC.length; i++) {
            if (op == OPS_MIC[i]) return true;
        }
        return false;
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
