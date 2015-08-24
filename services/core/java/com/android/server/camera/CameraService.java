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

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.hardware.ICameraService;
import android.hardware.ICameraServiceProxy;
import android.nfc.INfcAdapter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Binder;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserManager;
import android.os.SystemProperties;
import android.util.Slog;
import android.util.ArraySet;

import com.android.server.ServiceThread;
import com.android.server.SystemService;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * CameraService is the system_server analog to the camera service running in mediaserver.
 *
 * @hide
 */
public class CameraService extends SystemService
        implements Handler.Callback, IBinder.DeathRecipient {
    private static final String TAG = "CameraService_proxy";
    private static final boolean DEBUG = false;

    /**
     * This must match the ICameraService.aidl definition
     */
    private static final String CAMERA_SERVICE_BINDER_NAME = "media.camera";

    public static final String CAMERA_SERVICE_PROXY_BINDER_NAME = "media.camera.proxy";

    // Event arguments to use with the camera service notifySystemEvent call:
    public static final int NO_EVENT = 0; // NOOP
    public static final int USER_SWITCHED = 1; // User changed, argument is the new user handle

    // State arguments to use with the notifyCameraState call from camera service:
    public static final int CAMERA_STATE_OPEN = 0;
    public static final int CAMERA_STATE_ACTIVE = 1;
    public static final int CAMERA_STATE_IDLE = 2;
    public static final int CAMERA_STATE_CLOSED = 3;

    // Flags arguments to NFC adapter to enable/disable NFC
    public static final int DISABLE_POLLING_FLAGS = 0x1000;
    public static final int ENABLE_POLLING_FLAGS = 0x0000;

    // Handler message codes
    private static final int MSG_SWITCH_USER = 1;

    private static final int RETRY_DELAY_TIME = 20; //ms

    private final Context mContext;
    private final ServiceThread mHandlerThread;
    private final Handler mHandler;
    private UserManager mUserManager;

    private final Object mLock = new Object();
    private Set<Integer> mEnabledCameraUsers;
    private int mLastUser;

    private ICameraService mCameraServiceRaw;

    private final ArraySet<String> mActiveCameraIds = new ArraySet<>();

    private static final String NFC_NOTIFICATION_PROP = "ro.camera.notify_nfc";
    private static final String NFC_SERVICE_BINDER_NAME = "nfc";
    private static final IBinder nfcInterfaceToken = new Binder();

    private final boolean mNotifyNfc;
    private int mActiveCameraCount = 0;

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
            notifySwitchWithRetries(30);
        }

        @Override
        public void notifyCameraState(String cameraId, int newCameraState) {
            String state = cameraStateToString(newCameraState);
            if (DEBUG) Slog.v(TAG, "Camera " + cameraId + " state now " + state);

            updateActivityCount(cameraId, newCameraState);
        }
    };

    public CameraService(Context context) {
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
                Slog.e(TAG, "CameraService error, invalid message: " + msg.what);
            } break;
        }
        return true;
    }

    @Override
    public void onStart() {
        mUserManager = UserManager.get(mContext);
        if (mUserManager == null) {
            // Should never see this unless someone messes up the SystemServer service boot order.
            throw new IllegalStateException("UserManagerService must start before CameraService!");
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_ADDED);
        filter.addAction(Intent.ACTION_USER_REMOVED);
        filter.addAction(Intent.ACTION_USER_INFO_CHANGED);
        filter.addAction(Intent.ACTION_MANAGED_PROFILE_ADDED);
        filter.addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED);
        mContext.registerReceiver(mIntentReceiver, filter);

        publishBinderService(CAMERA_SERVICE_PROXY_BINDER_NAME, mCameraServiceProxy);
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
            boolean wasEmpty = mActiveCameraIds.isEmpty();
            mActiveCameraIds.clear();

            if ( mNotifyNfc && !wasEmpty ) {
                notifyNfcService(/*enablePolling*/ true);
            }
        }
    }

    private void switchUserLocked(int userHandle) {
        Set<Integer> currentUserHandles = getEnabledUserHandles(userHandle);
        mLastUser = userHandle;
        if (mEnabledCameraUsers == null || !mEnabledCameraUsers.equals(currentUserHandles)) {
            // Some user handles have been added or removed, update mediaserver.
            mEnabledCameraUsers = currentUserHandles;
            notifyMediaserverLocked(USER_SWITCHED, currentUserHandles);
        }
    }

    private Set<Integer> getEnabledUserHandles(int currentUserHandle) {
        List<UserInfo> userProfiles = mUserManager.getEnabledProfiles(currentUserHandle);
        Set<Integer> handles = new HashSet<>(userProfiles.size());

        for (UserInfo i : userProfiles) {
            handles.add(i.id);
        }

        return handles;
    }

    private void notifySwitchWithRetries(int retries) {
        synchronized(mLock) {
            if (mEnabledCameraUsers == null) {
                return;
            }
            if (notifyMediaserverLocked(USER_SWITCHED, mEnabledCameraUsers)) {
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

    private void updateActivityCount(String cameraId, int newCameraState) {
        synchronized(mLock) {
            boolean wasEmpty = mActiveCameraIds.isEmpty();
            switch (newCameraState) {
                case CAMERA_STATE_OPEN:
                    break;
                case CAMERA_STATE_ACTIVE:
                    mActiveCameraIds.add(cameraId);
                    break;
                case CAMERA_STATE_IDLE:
                case CAMERA_STATE_CLOSED:
                    mActiveCameraIds.remove(cameraId);
                    break;
            }
            boolean isEmpty = mActiveCameraIds.isEmpty();
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
            case CAMERA_STATE_OPEN: return "CAMERA_STATE_OPEN";
            case CAMERA_STATE_ACTIVE: return "CAMERA_STATE_ACTIVE";
            case CAMERA_STATE_IDLE: return "CAMERA_STATE_IDLE";
            case CAMERA_STATE_CLOSED: return "CAMERA_STATE_CLOSED";
            default: break;
        }
        return "CAMERA_STATE_UNKNOWN";
    }
}
