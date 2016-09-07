/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.policy;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import com.android.systemui.R;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Manages the flashlight.
 */
public class FlashlightController {

    private static final String TAG = "FlashlightController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final int DISPATCH_ERROR = 0;
    private static final int DISPATCH_CHANGED = 1;
    private static final int DISPATCH_AVAILABILITY_CHANGED = 2;

    private static final String ACTION_TURN_FLASHLIGHT_OFF = "com.android.systemui.action.TURN_FLASHLIGHT_OFF";

    private final CameraManager mCameraManager;
    private final Context mContext;
    /** Call {@link #ensureHandler()} before using */
    private Handler mHandler;

    /** Lock on mListeners when accessing */
    private final ArrayList<WeakReference<FlashlightListener>> mListeners = new ArrayList<>(1);

    /** Lock on {@code this} when accessing */
    private boolean mFlashlightEnabled;

    private String mCameraId;
    private boolean mTorchAvailable;

    private Notification mNotification = null;
    private boolean mReceiverRegistered;
    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_TURN_FLASHLIGHT_OFF.equals(intent.getAction())) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        setFlashlight(false);
                    }
                });
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                setNotificationShown(true);
            }
        }
    };

    public FlashlightController(Context context) {
        mContext = context;
        mCameraManager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);

        tryInitCamera();
    }

    private void tryInitCamera() {
        try {
            mCameraId = getCameraId();
        } catch (Throwable e) {
            Log.e(TAG, "Couldn't initialize.", e);
            return;
        }

        if (mCameraId != null) {
            ensureHandler();
            mCameraManager.registerTorchCallback(mTorchCallback, mHandler);
        }
    }

    public void setFlashlight(boolean enabled) {
        boolean pendingError = false;
        synchronized (this) {
            if (mCameraId == null) return;
            if (mFlashlightEnabled != enabled) {
                mFlashlightEnabled = enabled;
                try {
                    mCameraManager.setTorchMode(mCameraId, enabled);
                } catch (CameraAccessException e) {
                    Log.e(TAG, "Couldn't set torch mode", e);
                    mFlashlightEnabled = false;
                    pendingError = true;
                }
            }
        }
        dispatchModeChanged(mFlashlightEnabled);
        if (pendingError) {
            dispatchError();
        }
    }

    private void setNotificationShown(boolean showNotification) {
        NotificationManager nm = (NotificationManager)
                mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (showNotification) {
            nm.notify(R.string.quick_settings_tile_flashlight_not_title, buildNotification());
        } else {
            nm.cancel(R.string.quick_settings_tile_flashlight_not_title);
            mNotification = null;
        }
    }

    private void setListenForScreenOff(boolean listen) {
        if (listen && !mReceiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_TURN_FLASHLIGHT_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            mContext.registerReceiver(mReceiver, filter);
            mReceiverRegistered = true;
            setNotificationShown(true);
        } else if (!listen) {
            if (mReceiverRegistered) {
                mContext.unregisterReceiver(mReceiver);
                mReceiverRegistered = false;
            }
            setNotificationShown(false);
        }
    }

    private Notification buildNotification() {
        if (mNotification == null) {
            Intent fireMe = new Intent(ACTION_TURN_FLASHLIGHT_OFF);
            fireMe.addFlags(Intent.FLAG_FROM_BACKGROUND);
            fireMe.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            fireMe.setPackage(mContext.getPackageName());

            final PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, 0, fireMe, 0);
            mNotification = new Notification.Builder(mContext)
                    .setContentTitle(
                            mContext.getString(R.string.quick_settings_tile_flashlight_not_title))
                    .setContentText(
                            mContext.getString(R.string.quick_settings_tile_flashlight_not_summary))
                    .setAutoCancel(false)
                    .setOngoing(true)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setSmallIcon(R.drawable.ic_signal_flashlight_disable)
                    .setContentIntent(pendingIntent)
                    .build();
        }
        return mNotification;
    }

    public boolean hasFlashlight() {
        return mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH);
    }

    public synchronized boolean isEnabled() {
        return mFlashlightEnabled;
    }

    public synchronized boolean isAvailable() {
        return mTorchAvailable;
    }

    public void addListener(FlashlightListener l) {
        synchronized (mListeners) {
            if (mCameraId == null) {
                tryInitCamera();
            }
            cleanUpListenersLocked(l);
            mListeners.add(new WeakReference<>(l));
        }
    }

    public void removeListener(FlashlightListener l) {
        synchronized (mListeners) {
            cleanUpListenersLocked(l);
        }
    }

    private synchronized void ensureHandler() {
        if (mHandler == null) {
            HandlerThread thread = new HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            mHandler = new Handler(thread.getLooper());
        }
    }

    private String getCameraId() throws CameraAccessException {
        String[] ids = mCameraManager.getCameraIdList();
        for (String id : ids) {
            CameraCharacteristics c = mCameraManager.getCameraCharacteristics(id);
            Boolean flashAvailable = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            Integer lensFacing = c.get(CameraCharacteristics.LENS_FACING);
            if (flashAvailable != null && flashAvailable
                    && lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
        }
        return null;
    }

    private void dispatchModeChanged(boolean enabled) {
        dispatchListeners(DISPATCH_CHANGED, enabled);
    }

    private void dispatchError() {
        dispatchListeners(DISPATCH_CHANGED, false /* argument (ignored) */);
    }

    private void dispatchAvailabilityChanged(boolean available) {
        dispatchListeners(DISPATCH_AVAILABILITY_CHANGED, available);
    }

    private void dispatchListeners(int message, boolean argument) {
        synchronized (mListeners) {
            final int N = mListeners.size();
            boolean cleanup = false;
            for (int i = 0; i < N; i++) {
                FlashlightListener l = mListeners.get(i).get();
                if (l != null) {
                    if (message == DISPATCH_ERROR) {
                        l.onFlashlightError();
                    } else if (message == DISPATCH_CHANGED) {
                        l.onFlashlightChanged(argument);
                    } else if (message == DISPATCH_AVAILABILITY_CHANGED) {
                        l.onFlashlightAvailabilityChanged(argument);
                    }
                } else {
                    cleanup = true;
                }
            }
            if (cleanup) {
                cleanUpListenersLocked(null);
            }
        }
    }

    private void cleanUpListenersLocked(FlashlightListener listener) {
        for (int i = mListeners.size() - 1; i >= 0; i--) {
            FlashlightListener found = mListeners.get(i).get();
            if (found == null || found == listener) {
                mListeners.remove(i);
            }
        }
    }

    private final CameraManager.TorchCallback mTorchCallback =
            new CameraManager.TorchCallback() {

        @Override
        public void onTorchModeUnavailable(String cameraId) {
            if (TextUtils.equals(cameraId, mCameraId)) {
                setCameraAvailable(false);
                setListenForScreenOff(false);
            }
        }

        @Override
        public void onTorchModeChanged(String cameraId, boolean enabled) {
            if (TextUtils.equals(cameraId, mCameraId)) {
                setCameraAvailable(true);
                setTorchMode(enabled);
                setListenForScreenOff(enabled);
            }
        }

        private void setCameraAvailable(boolean available) {
            boolean changed;
            synchronized (FlashlightController.this) {
                changed = mTorchAvailable != available;
                mTorchAvailable = available;
            }
            if (changed) {
                if (DEBUG) Log.d(TAG, "dispatchAvailabilityChanged(" + available + ")");
                dispatchAvailabilityChanged(available);
            }
        }

        private void setTorchMode(boolean enabled) {
            boolean changed;
            synchronized (FlashlightController.this) {
                changed = mFlashlightEnabled != enabled;
                mFlashlightEnabled = enabled;
            }
            if (changed) {
                if (DEBUG) Log.d(TAG, "dispatchModeChanged(" + enabled + ")");
                dispatchModeChanged(enabled);
            }
        }
    };

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("FlashlightController state:");

        pw.print("  mCameraId=");
        pw.println(mCameraId);
        pw.print("  mFlashlightEnabled=");
        pw.println(mFlashlightEnabled);
        pw.print("  mTorchAvailable=");
        pw.println(mTorchAvailable);
    }

    public interface FlashlightListener {

        /**
         * Called when the flashlight was turned off or on.
         * @param enabled true if the flashlight is currently turned on.
         */
        void onFlashlightChanged(boolean enabled);


        /**
         * Called when there is an error that turns the flashlight off.
         */
        void onFlashlightError();

        /**
         * Called when there is a change in availability of the flashlight functionality
         * @param available true if the flashlight is currently available.
         */
        void onFlashlightAvailabilityChanged(boolean available);
    }
}
