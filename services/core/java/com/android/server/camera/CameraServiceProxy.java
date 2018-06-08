/*
 * Copyright 2015 The Android Open Source Project
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
package com.android.server.camera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.ICameraService;
import android.hardware.ICameraServiceProxy;
import android.metrics.LogMaker;
import android.nfc.INfcAdapter;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * CameraServiceProxy is the system_server analog to the camera service running in mediaserver.
 *
 * @hide
 */
public class CameraServiceProxy extends SystemService
        implements Handler.Callback, IBinder.DeathRecipient {
    private static final String TAG = "CameraService_proxy";
    private static final boolean DEBUG = false;

    /**
     * This must match the ICameraService.aidl definition
     */
    private static final String CAMERA_SERVICE_BINDER_NAME = "media.camera";

    public static final String CAMERA_SERVICE_PROXY_BINDER_NAME = "media.camera.proxy";

    // Flags arguments to NFC adapter to enable/disable NFC
    public static final int DISABLE_POLLING_FLAGS = 0x1000;
    public static final int ENABLE_POLLING_FLAGS = 0x0000;

    // Handler message codes
    private static final int MSG_SWITCH_USER = 1;

    private static final int RETRY_DELAY_TIME = 20; //ms

    // Maximum entries to keep in usage history before dumping out
    private static final int MAX_USAGE_HISTORY = 100;

    private final Context mContext;
    private final ServiceThread mHandlerThread;
    private final Handler mHandler;
    private UserManager mUserManager;

    private final Object mLock = new Object();
    private Set<Integer> mEnabledCameraUsers;
    private int mLastUser;

    private ICameraService mCameraServiceRaw;

    private final ArrayMap<String, CameraUsageEvent> mActiveCameraUsage = new ArrayMap<>();
    private final List<CameraUsageEvent> mCameraUsageHistory = new ArrayList<>();
    private final MetricsLogger mLogger = new MetricsLogger();
    private static final String NFC_NOTIFICATION_PROP = "ro.camera.notify_nfc";
    private static final String NFC_SERVICE_BINDER_NAME = "nfc";
    private static final IBinder nfcInterfaceToken = new Binder();

    private final boolean mNotifyNfc;

    /**
     * Structure to track camera usage
     */
    private static class CameraUsageEvent {
        public final int mCameraFacing;
        public final String mClientName;
        public final int mAPILevel;

        private boolean mCompleted;
        private long mDurationOrStartTimeMs;  // Either start time, or duration once completed

        public CameraUsageEvent(int facing, String clientName, int apiLevel) {
            mCameraFacing = facing;
            mClientName = clientName;
            mAPILevel = apiLevel;
            mDurationOrStartTimeMs = SystemClock.elapsedRealtime();
            mCompleted = false;
        }

        public void markCompleted() {
            if (mCompleted) {
                return;
            }
            mCompleted = true;
            mDurationOrStartTimeMs = SystemClock.elapsedRealtime() - mDurationOrStartTimeMs;
            if (CameraServiceProxy.DEBUG) {
                Slog.v(TAG, "A camera facing " + cameraFacingToString(mCameraFacing) +
                        " was in use by " + mClientName + " for " +
                        mDurationOrStartTimeMs + " ms");
            }
        }

        /**
         * Return duration of camera usage event, or 0 if the event is not done
         */
        public long getDuration() {
            return mCompleted ? mDurationOrStartTimeMs : 0;
        }
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action == null) return;

            switch (action) {
                case Intent.ACTION_USER_ADDED:
                case Intent.ACTION_USER_REMOVED:
                case Intent.ACTION_USER_INFO_CHANGED:
                case Intent.ACTION_MANAGED_PROFILE_ADDED:
                case Intent.ACTION_MANAGED_PROFILE_REMOVED:
                    synchronized(mLock) {
                        // Return immediately if we haven't seen any users start yet
                        if (mEnabledCameraUsers == null) return;
                        switchUserLocked(mLastUser);
                    }
                    break;
                default:
                    break; // do nothing
            }

        }
    };

    private final ICameraServiceProxy.Stub mCameraServiceProxy = new ICameraServiceProxy.Stub() {
        @Override
        public void pingForUserUpdate() {
            if (Binder.getCallingUid() != Process.CAMERASERVER_UID) {
                Slog.e(TAG, "Calling UID: " + Binder.getCallingUid() + " doesn't match expected " +
                        " camera service UID!");
                return;
            }
            notifySwitchWithRetries(30);
        }

        @Override
        public void notifyCameraState(String cameraId, int newCameraState, int facing,
                String clientName, int apiLevel) {
            if (Binder.getCallingUid() != Process.CAMERASERVER_UID) {
                Slog.e(TAG, "Calling UID: " + Binder.getCallingUid() + " doesn't match expected " +
                        " camera service UID!");
                return;
            }
            String state = cameraStateToString(newCameraState);
            String facingStr = cameraFacingToString(facing);
            if (DEBUG) Slog.v(TAG, "Camera " + cameraId + " facing " + facingStr + " state now " +
                    state + " for client " + clientName + " API Level " + apiLevel);

            updateActivityCount(cameraId, newCameraState, facing, clientName, apiLevel);
        }
    };

    public CameraServiceProxy(Context context) {
        super(context);
        mContext = context;
        mHandlerThread = new ServiceThread(TAG, Process.THREAD_PRIORITY_DISPLAY, /*allowTo*/false);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper(), this);

        mNotifyNfc = SystemProperties.getInt(NFC_NOTIFICATION_PROP, 0) > 0;
        if (DEBUG) Slog.v(TAG, "Notify NFC behavior is " + (mNotifyNfc ? "active" : "disabled"));
    }

    @Override
    public boolean handleMessage(Message msg) {
        switch(msg.what) {
            case MSG_SWITCH_USER: {
                notifySwitchWithRetries(msg.arg1);
            } break;
            default: {
                Slog.e(TAG, "CameraServiceProxy error, invalid message: " + msg.what);
            } break;
        }
        return true;
    }

    @Override
    public void onStart() {
        mUserManager = UserManager.get(mContext);
        if (mUserManager == null) {
            // Should never see this unless someone messes up the SystemServer service boot order.
            throw new IllegalStateException("UserManagerService must start before" +
                    " CameraServiceProxy!");
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_ADDED);
        filter.addAction(Intent.ACTION_USER_REMOVED);
        filter.addAction(Intent.ACTION_USER_INFO_CHANGED);
        filter.addAction(Intent.ACTION_MANAGED_PROFILE_ADDED);
        filter.addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED);
        mContext.registerReceiver(mIntentReceiver, filter);

        publishBinderService(CAMERA_SERVICE_PROXY_BINDER_NAME, mCameraServiceProxy);
        publishLocalService(CameraServiceProxy.class, this);

        CameraStatsJobService.schedule(mContext);
    }

    @Override
    public void onStartUser(int userHandle) {
        synchronized(mLock) {
            if (mEnabledCameraUsers == null) {
                // Initialize mediaserver, or update mediaserver if we are recovering from a crash.
                switchUserLocked(userHandle);
            }
        }
    }

    @Override
    public void onSwitchUser(int userHandle) {
        synchronized(mLock) {
            switchUserLocked(userHandle);
        }
    }

    /**
     * Handle the death of the native camera service
     */
    @Override
    public void binderDied() {
        if (DEBUG) Slog.w(TAG, "Native camera service has died");
        synchronized(mLock) {
            mCameraServiceRaw = null;

            // All cameras reset to idle on camera service death
            boolean wasEmpty = mActiveCameraUsage.isEmpty();
            mActiveCameraUsage.clear();

            if ( mNotifyNfc && !wasEmpty ) {
                notifyNfcService(/*enablePolling*/ true);
            }
        }
    }

    /**
     * Dump camera usage events to log.
     * Package-private
     */
    void dumpUsageEvents() {
        synchronized(mLock) {
            // Randomize order of events so that it's not meaningful
            Collections.shuffle(mCameraUsageHistory);
            for (CameraUsageEvent e : mCameraUsageHistory) {
                if (DEBUG) {
                    Slog.v(TAG, "Camera: " + e.mClientName + " used a camera facing " +
                            cameraFacingToString(e.mCameraFacing) + " for " +
                            e.getDuration() + " ms");
                }
                int subtype = 0;
                switch(e.mCameraFacing) {
                    case ICameraServiceProxy.CAMERA_FACING_BACK:
                        subtype = MetricsEvent.CAMERA_BACK_USED;
                        break;
                    case ICameraServiceProxy.CAMERA_FACING_FRONT:
                        subtype = MetricsEvent.CAMERA_FRONT_USED;
                        break;
                    case ICameraServiceProxy.CAMERA_FACING_EXTERNAL:
                        subtype = MetricsEvent.CAMERA_EXTERNAL_USED;
                        break;
                    default:
                        continue;
                }
                LogMaker l = new LogMaker(MetricsEvent.ACTION_CAMERA_EVENT)
                        .setType(MetricsEvent.TYPE_ACTION)
                        .setSubtype(subtype)
                        .setLatency(e.getDuration())
                        .addTaggedData(MetricsEvent.FIELD_CAMERA_API_LEVEL, e.mAPILevel)
                        .setPackageName(e.mClientName);
                mLogger.write(l);
            }
            mCameraUsageHistory.clear();
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            CameraStatsJobService.schedule(mContext);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void switchUserLocked(int userHandle) {
        Set<Integer> currentUserHandles = getEnabledUserHandles(userHandle);
        mLastUser = userHandle;
        if (mEnabledCameraUsers == null || !mEnabledCameraUsers.equals(currentUserHandles)) {
            // Some user handles have been added or removed, update mediaserver.
            mEnabledCameraUsers = currentUserHandles;
            notifyMediaserverLocked(ICameraService.EVENT_USER_SWITCHED, currentUserHandles);
        }
    }

    private Set<Integer> getEnabledUserHandles(int currentUserHandle) {
        int[] userProfiles = mUserManager.getEnabledProfileIds(currentUserHandle);
        Set<Integer> handles = new ArraySet<>(userProfiles.length);

        for (int id : userProfiles) {
            handles.add(id);
        }

        return handles;
    }

    private void notifySwitchWithRetries(int retries) {
        synchronized(mLock) {
            if (mEnabledCameraUsers == null) {
                return;
            }
            if (notifyMediaserverLocked(ICameraService.EVENT_USER_SWITCHED, mEnabledCameraUsers)) {
                retries = 0;
            }
        }
        if (retries <= 0) {
            return;
        }
        Slog.i(TAG, "Could not notify camera service of user switch, retrying...");
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_SWITCH_USER, retries - 1, 0, null),
                RETRY_DELAY_TIME);
    }

    private boolean notifyMediaserverLocked(int eventType, Set<Integer> updatedUserHandles) {
        // Forward the user switch event to the native camera service running in the mediaserver
        // process.
        if (mCameraServiceRaw == null) {
            IBinder cameraServiceBinder = getBinderService(CAMERA_SERVICE_BINDER_NAME);
            if (cameraServiceBinder == null) {
                Slog.w(TAG, "Could not notify mediaserver, camera service not available.");
                return false; // Camera service not active, cannot evict user clients.
            }
            try {
                cameraServiceBinder.linkToDeath(this, /*flags*/ 0);
            } catch (RemoteException e) {
                Slog.w(TAG, "Could not link to death of native camera service");
                return false;
            }

            mCameraServiceRaw = ICameraService.Stub.asInterface(cameraServiceBinder);
        }

        try {
            mCameraServiceRaw.notifySystemEvent(eventType, toArray(updatedUserHandles));
        } catch (RemoteException e) {
            Slog.w(TAG, "Could not notify mediaserver, remote exception: " + e);
            // Not much we can do if camera service is dead.
            return false;
        }
        return true;
    }

    private void updateActivityCount(String cameraId, int newCameraState, int facing,
            String clientName, int apiLevel) {
        synchronized(mLock) {
            // Update active camera list and notify NFC if necessary
            boolean wasEmpty = mActiveCameraUsage.isEmpty();
            switch (newCameraState) {
                case ICameraServiceProxy.CAMERA_STATE_OPEN:
                    break;
                case ICameraServiceProxy.CAMERA_STATE_ACTIVE:
                    CameraUsageEvent newEvent = new CameraUsageEvent(facing, clientName, apiLevel);
                    CameraUsageEvent oldEvent = mActiveCameraUsage.put(cameraId, newEvent);
                    if (oldEvent != null) {
                        Slog.w(TAG, "Camera " + cameraId + " was already marked as active");
                        oldEvent.markCompleted();
                        mCameraUsageHistory.add(oldEvent);
                    }
                    break;
                case ICameraServiceProxy.CAMERA_STATE_IDLE:
                case ICameraServiceProxy.CAMERA_STATE_CLOSED:
                    CameraUsageEvent doneEvent = mActiveCameraUsage.remove(cameraId);
                    if (doneEvent != null) {
                        doneEvent.markCompleted();
                        mCameraUsageHistory.add(doneEvent);
                        if (mCameraUsageHistory.size() > MAX_USAGE_HISTORY) {
                            dumpUsageEvents();
                        }
                    }
                    break;
            }
            boolean isEmpty = mActiveCameraUsage.isEmpty();
            if ( mNotifyNfc && (wasEmpty != isEmpty) ) {
                notifyNfcService(isEmpty);
            }
        }
    }

    private void notifyNfcService(boolean enablePolling) {

        IBinder nfcServiceBinder = getBinderService(NFC_SERVICE_BINDER_NAME);
        if (nfcServiceBinder == null) {
            Slog.w(TAG, "Could not connect to NFC service to notify it of camera state");
            return;
        }
        INfcAdapter nfcAdapterRaw = INfcAdapter.Stub.asInterface(nfcServiceBinder);
        int flags = enablePolling ? ENABLE_POLLING_FLAGS : DISABLE_POLLING_FLAGS;
        if (DEBUG) Slog.v(TAG, "Setting NFC reader mode to flags " + flags);
        try {
            nfcAdapterRaw.setReaderMode(nfcInterfaceToken, null, flags, null);
        } catch (RemoteException e) {
            Slog.w(TAG, "Could not notify NFC service, remote exception: " + e);
        }
    }

    private static int[] toArray(Collection<Integer> c) {
        int len = c.size();
        int[] ret = new int[len];
        int idx = 0;
        for (Integer i : c) {
            ret[idx++] = i;
        }
        return ret;
    }

    private static String cameraStateToString(int newCameraState) {
        switch (newCameraState) {
            case ICameraServiceProxy.CAMERA_STATE_OPEN: return "CAMERA_STATE_OPEN";
            case ICameraServiceProxy.CAMERA_STATE_ACTIVE: return "CAMERA_STATE_ACTIVE";
            case ICameraServiceProxy.CAMERA_STATE_IDLE: return "CAMERA_STATE_IDLE";
            case ICameraServiceProxy.CAMERA_STATE_CLOSED: return "CAMERA_STATE_CLOSED";
            default: break;
        }
        return "CAMERA_STATE_UNKNOWN";
    }

    private static String cameraFacingToString(int cameraFacing) {
        switch (cameraFacing) {
            case ICameraServiceProxy.CAMERA_FACING_BACK: return "CAMERA_FACING_BACK";
            case ICameraServiceProxy.CAMERA_FACING_FRONT: return "CAMERA_FACING_FRONT";
            case ICameraServiceProxy.CAMERA_FACING_EXTERNAL: return "CAMERA_FACING_EXTERNAL";
            default: break;
        }
        return "CAMERA_FACING_UNKNOWN";
    }

}
