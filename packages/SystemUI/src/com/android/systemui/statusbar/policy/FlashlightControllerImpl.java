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

import android.annotation.WorkerThread;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.internal.annotations.GuardedBy;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.util.settings.SecureSettings;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;

/**
 * Manages the flashlight.
 */
@SysUISingleton
public class FlashlightControllerImpl implements FlashlightController {

    private static final String TAG = "FlashlightController";
    private static final boolean DEBUG = true;

    private static final int DISPATCH_ERROR = 0;
    private static final int DISPATCH_CHANGED = 1;
    private static final int DISPATCH_AVAILABILITY_CHANGED = 2;

    private static final String ACTION_FLASHLIGHT_CHANGED =
        "com.android.settings.flashlight.action.FLASHLIGHT_CHANGED";

    private final CameraManager mCameraManager;
    private final Executor mExecutor;
    private final SecureSettings mSecureSettings;
    private final DumpManager mDumpManager;
    private final BroadcastSender mBroadcastSender;

    private final boolean mHasFlashlight;

    @GuardedBy("mListeners")
    private final ArrayList<WeakReference<FlashlightListener>> mListeners = new ArrayList<>(1);

    @GuardedBy("this")
    private boolean mFlashlightEnabled;
    @GuardedBy("this")
    private boolean mTorchAvailable;

    private final AtomicReference<String> mCameraId;
    private final AtomicBoolean mInitted = new AtomicBoolean(false);

    @Inject
    public FlashlightControllerImpl(
            DumpManager dumpManager,
            CameraManager cameraManager,
            @Background Executor bgExecutor,
            SecureSettings secureSettings,
            BroadcastSender broadcastSender,
            PackageManager packageManager
    ) {
        mCameraManager = cameraManager;
        mExecutor = bgExecutor;
        mCameraId = new AtomicReference<>(null);
        mSecureSettings = secureSettings;
        mDumpManager = dumpManager;
        mBroadcastSender = broadcastSender;

        mHasFlashlight = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH);
        init();
    }

    private void init() {
        if (!mInitted.getAndSet(true)) {
            mDumpManager.registerDumpable(getClass().getSimpleName(), this);
            mExecutor.execute(this::tryInitCamera);
        }
    }

    @WorkerThread
    private void tryInitCamera() {
        if (!mHasFlashlight || mCameraId.get() != null) return;
        try {
            mCameraId.set(getCameraId());
        } catch (Throwable e) {
            Log.e(TAG, "Couldn't initialize.", e);
            return;
        }

        if (mCameraId.get() != null) {
            mCameraManager.registerTorchCallback(mExecutor, mTorchCallback);
        }
    }

    public void setFlashlight(boolean enabled) {
        if (!mHasFlashlight) return;
        if (mCameraId.get() == null) {
            mExecutor.execute(this::tryInitCamera);
        }
        mExecutor.execute(() -> {
            if (mCameraId.get() == null) return;
            synchronized (this) {
                if (mFlashlightEnabled != enabled) {
                    try {
                        mCameraManager.setTorchMode(mCameraId.get(), enabled);
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "Couldn't set torch mode", e);
                        dispatchError();
                    }
                }
            }
        });
    }

    public boolean hasFlashlight() {
        return mHasFlashlight;
    }

    public synchronized boolean isEnabled() {
        return mFlashlightEnabled;
    }

    public synchronized boolean isAvailable() {
        return mTorchAvailable;
    }

    @Override
    public void addCallback(@NonNull FlashlightListener l) {
        synchronized (mListeners) {
            if (mCameraId.get() == null) {
                mExecutor.execute(this::tryInitCamera);
            }
            cleanUpListenersLocked(l);
            mListeners.add(new WeakReference<>(l));
            l.onFlashlightAvailabilityChanged(isAvailable());
            l.onFlashlightChanged(isEnabled());
        }
    }

    @Override
    public void removeCallback(@NonNull FlashlightListener l) {
        synchronized (mListeners) {
            cleanUpListenersLocked(l);
        }
    }

    @WorkerThread
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
            final ArrayList<WeakReference<FlashlightController.FlashlightListener>> copy =
                    new ArrayList<>(mListeners);
            final int n = copy.size();
            boolean cleanup = false;
            for (int i = 0; i < n; i++) {
                FlashlightListener l = copy.get(i).get();
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
        @WorkerThread
        public void onTorchModeUnavailable(String cameraId) {
            if (TextUtils.equals(cameraId, mCameraId.get())) {
                setCameraAvailable(false);
                mSecureSettings.putInt(Settings.Secure.FLASHLIGHT_AVAILABLE, 0);

            }
        }

        @Override
        @WorkerThread
        public void onTorchModeChanged(String cameraId, boolean enabled) {
            if (TextUtils.equals(cameraId, mCameraId.get())) {
                setCameraAvailable(true);
                setTorchMode(enabled);
                mSecureSettings.putInt(Settings.Secure.FLASHLIGHT_AVAILABLE, 1);
                mSecureSettings.putInt(Secure.FLASHLIGHT_ENABLED, enabled ? 1 : 0);
            }
        }

        private void setCameraAvailable(boolean available) {
            boolean changed;
            synchronized (FlashlightControllerImpl.this) {
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
            synchronized (FlashlightControllerImpl.this) {
                changed = mFlashlightEnabled != enabled;
                mFlashlightEnabled = enabled;
            }
            if (changed) {
                if (DEBUG) Log.d(TAG, "dispatchModeChanged(" + enabled + ")");
                dispatchModeChanged(enabled);
            }
        }
    };

    public void dump(PrintWriter pw, String[] args) {
        pw.println("FlashlightController state:");

        pw.print("  mCameraId=");
        pw.println(mCameraId);
        pw.print("  mFlashlightEnabled=");
        pw.println(mFlashlightEnabled);
        pw.print("  mTorchAvailable=");
        pw.println(mTorchAvailable);
    }
}
