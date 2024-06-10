/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.companion.virtual;

import static android.companion.virtual.VirtualDeviceParams.DEVICE_POLICY_CUSTOM;
import static android.companion.virtual.VirtualDeviceParams.POLICY_TYPE_AUDIO;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.UserIdInt;
import android.app.PendingIntent;
import android.companion.virtual.audio.VirtualAudioDevice;
import android.companion.virtual.camera.VirtualCamera;
import android.companion.virtual.camera.VirtualCameraConfig;
import android.companion.virtual.sensor.VirtualSensor;
import android.companion.virtualdevice.flags.Flags;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManagerGlobal;
import android.hardware.display.IVirtualDisplayCallback;
import android.hardware.display.VirtualDisplay;
import android.hardware.display.VirtualDisplayConfig;
import android.hardware.input.VirtualDpad;
import android.hardware.input.VirtualDpadConfig;
import android.hardware.input.VirtualKeyboard;
import android.hardware.input.VirtualKeyboardConfig;
import android.hardware.input.VirtualMouse;
import android.hardware.input.VirtualMouseConfig;
import android.hardware.input.VirtualNavigationTouchpad;
import android.hardware.input.VirtualNavigationTouchpadConfig;
import android.hardware.input.VirtualStylus;
import android.hardware.input.VirtualStylusConfig;
import android.hardware.input.VirtualTouchscreen;
import android.hardware.input.VirtualTouchscreenConfig;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.util.ArrayMap;
import android.view.WindowManager;

import com.android.internal.annotations.GuardedBy;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.IntConsumer;

/**
 * An internal representation of a virtual device.
 *
 * @hide
 */
public class VirtualDeviceInternal {

    private final Context mContext;
    private final IVirtualDeviceManager mService;
    private final IVirtualDevice mVirtualDevice;
    private final Object mActivityListenersLock = new Object();
    @GuardedBy("mActivityListenersLock")
    private final ArrayMap<VirtualDeviceManager.ActivityListener, ActivityListenerDelegate>
            mActivityListeners =
            new ArrayMap<>();
    private final Object mIntentInterceptorListenersLock = new Object();
    @GuardedBy("mIntentInterceptorListenersLock")
    private final ArrayMap<VirtualDeviceManager.IntentInterceptorCallback,
            IntentInterceptorDelegate> mIntentInterceptorListeners =
            new ArrayMap<>();
    private final Object mSoundEffectListenersLock = new Object();
    @GuardedBy("mSoundEffectListenersLock")
    private final ArrayMap<VirtualDeviceManager.SoundEffectListener, SoundEffectListenerDelegate>
            mSoundEffectListeners = new ArrayMap<>();
    private final IVirtualDeviceActivityListener mActivityListenerBinder =
            new IVirtualDeviceActivityListener.Stub() {

                @Override
                public void onTopActivityChanged(int displayId, ComponentName topActivity,
                        @UserIdInt int userId) {
                    final long token = Binder.clearCallingIdentity();
                    try {
                        synchronized (mActivityListenersLock) {
                            for (int i = 0; i < mActivityListeners.size(); i++) {
                                mActivityListeners.valueAt(i)
                                        .onTopActivityChanged(displayId, topActivity);
                                mActivityListeners.valueAt(i)
                                        .onTopActivityChanged(displayId, topActivity, userId);
                            }
                        }
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }

                @Override
                public void onDisplayEmpty(int displayId) {
                    final long token = Binder.clearCallingIdentity();
                    try {
                        synchronized (mActivityListenersLock) {
                            for (int i = 0; i < mActivityListeners.size(); i++) {
                                mActivityListeners.valueAt(i).onDisplayEmpty(displayId);
                            }
                        }
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            };
    private final IVirtualDeviceSoundEffectListener mSoundEffectListener =
            new IVirtualDeviceSoundEffectListener.Stub() {
                @Override
                public void onPlaySoundEffect(int soundEffect) {
                    final long token = Binder.clearCallingIdentity();
                    try {
                        synchronized (mSoundEffectListenersLock) {
                            for (int i = 0; i < mSoundEffectListeners.size(); i++) {
                                mSoundEffectListeners.valueAt(i).onPlaySoundEffect(soundEffect);
                            }
                        }
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            };
    @Nullable
    private VirtualAudioDevice mVirtualAudioDevice;

    VirtualDeviceInternal(
            IVirtualDeviceManager service,
            Context context,
            int associationId,
            VirtualDeviceParams params) throws RemoteException {
        mService = service;
        mContext = context.getApplicationContext();
        mVirtualDevice = service.createVirtualDevice(
                new Binder(),
                mContext.getAttributionSource(),
                associationId,
                params,
                mActivityListenerBinder,
                mSoundEffectListener);
    }

    int getDeviceId() {
        try {
            return mVirtualDevice.getDeviceId();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Nullable String getPersistentDeviceId() {
        try {
            return mVirtualDevice.getPersistentDeviceId();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @NonNull Context createContext() {
        try {
            return mContext.createDeviceContext(mVirtualDevice.getDeviceId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @NonNull
    List<VirtualSensor> getVirtualSensorList() {
        try {
            return mVirtualDevice.getVirtualSensorList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    void launchPendingIntent(
            int displayId,
            @NonNull PendingIntent pendingIntent,
            @NonNull Executor executor,
            @NonNull IntConsumer listener) {
        try {
            mVirtualDevice.launchPendingIntent(
                    displayId,
                    pendingIntent,
                    new ResultReceiver(new Handler(Looper.getMainLooper())) {
                        @Override
                        protected void onReceiveResult(int resultCode, Bundle resultData) {
                            super.onReceiveResult(resultCode, resultData);
                            executor.execute(() -> listener.accept(resultCode));
                        }
                    });
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    @Nullable
    VirtualDisplay createVirtualDisplay(
            @NonNull VirtualDisplayConfig config,
            @Nullable @CallbackExecutor Executor executor,
            @Nullable VirtualDisplay.Callback callback) {
        IVirtualDisplayCallback callbackWrapper =
                new DisplayManagerGlobal.VirtualDisplayCallback(callback, executor);
        final int displayId;
        try {
            displayId = mService.createVirtualDisplay(config, callbackWrapper, mVirtualDevice,
                    mContext.getPackageName());
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
        DisplayManagerGlobal displayManager = DisplayManagerGlobal.getInstance();
        return displayManager.createVirtualDisplayWrapper(config, callbackWrapper,
                displayId);
    }

    void close() {
        try {
            // This also takes care of unregistering all virtual sensors.
            mVirtualDevice.close();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        if (mVirtualAudioDevice != null) {
            mVirtualAudioDevice.close();
            mVirtualAudioDevice = null;
        }
    }

    void setDevicePolicy(@VirtualDeviceParams.DynamicPolicyType int policyType,
            @VirtualDeviceParams.DevicePolicy int devicePolicy) {
        try {
            mVirtualDevice.setDevicePolicy(policyType, devicePolicy);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    void addActivityPolicyExemption(@NonNull ComponentName componentName) {
        try {
            mVirtualDevice.addActivityPolicyExemption(componentName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    void removeActivityPolicyExemption(@NonNull ComponentName componentName) {
        try {
            mVirtualDevice.removeActivityPolicyExemption(componentName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @NonNull
    VirtualDpad createVirtualDpad(@NonNull VirtualDpadConfig config) {
        try {
            final IBinder token = new Binder(
                    "android.hardware.input.VirtualDpad:" + config.getInputDeviceName());
            mVirtualDevice.createVirtualDpad(config, token);
            return new VirtualDpad(config, mVirtualDevice, token);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @NonNull
    VirtualKeyboard createVirtualKeyboard(@NonNull VirtualKeyboardConfig config) {
        try {
            final IBinder token = new Binder(
                    "android.hardware.input.VirtualKeyboard:" + config.getInputDeviceName());
            mVirtualDevice.createVirtualKeyboard(config, token);
            return new VirtualKeyboard(config, mVirtualDevice, token);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @NonNull
    VirtualMouse createVirtualMouse(@NonNull VirtualMouseConfig config) {
        try {
            final IBinder token = new Binder(
                    "android.hardware.input.VirtualMouse:" + config.getInputDeviceName());
            mVirtualDevice.createVirtualMouse(config, token);
            return new VirtualMouse(config, mVirtualDevice, token);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @NonNull
    VirtualTouchscreen createVirtualTouchscreen(
            @NonNull VirtualTouchscreenConfig config) {
        try {
            final IBinder token = new Binder(
                    "android.hardware.input.VirtualTouchscreen:" + config.getInputDeviceName());
            mVirtualDevice.createVirtualTouchscreen(config, token);
            return new VirtualTouchscreen(config, mVirtualDevice, token);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    @NonNull
    VirtualStylus createVirtualStylus(@NonNull VirtualStylusConfig config) {
        try {
            final IBinder token = new Binder(
                    "android.hardware.input.VirtualStylus:" + config.getInputDeviceName());
            mVirtualDevice.createVirtualStylus(config, token);
            return new VirtualStylus(config, mVirtualDevice, token);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @NonNull
    VirtualNavigationTouchpad createVirtualNavigationTouchpad(
            @NonNull VirtualNavigationTouchpadConfig config) {
        try {
            final IBinder token = new Binder(
                    "android.hardware.input.VirtualNavigationTouchpad:"
                            + config.getInputDeviceName());
            mVirtualDevice.createVirtualNavigationTouchpad(config, token);
            return new VirtualNavigationTouchpad(config, mVirtualDevice, token);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @NonNull
    VirtualAudioDevice createVirtualAudioDevice(
            @NonNull VirtualDisplay display,
            @Nullable Executor executor,
            @Nullable VirtualAudioDevice.AudioConfigurationChangeCallback callback) {
        if (mVirtualAudioDevice == null) {
            try {
                Context context = mContext;
                if (Flags.deviceAwareRecordAudioPermission()) {
                    // When using a default policy for audio device-aware RECORD_AUDIO permission
                    // should not take effect, thus register policies with the default context.
                    if (mVirtualDevice.getDevicePolicy(POLICY_TYPE_AUDIO) == DEVICE_POLICY_CUSTOM) {
                        context = mContext.createDeviceContext(getDeviceId());
                    }
                }
                mVirtualAudioDevice = new VirtualAudioDevice(context, mVirtualDevice, display,
                        executor, callback, () -> mVirtualAudioDevice = null);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return mVirtualAudioDevice;
    }

    @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    @NonNull
    VirtualCamera createVirtualCamera(@NonNull VirtualCameraConfig config) {
        try {
            mVirtualDevice.registerVirtualCamera(config);
            return new VirtualCamera(mVirtualDevice, mVirtualDevice.getVirtualCameraId(config),
                    config);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    void setShowPointerIcon(boolean showPointerIcon) {
        try {
            mVirtualDevice.setShowPointerIcon(showPointerIcon);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    void setDisplayImePolicy(int displayId, @WindowManager.DisplayImePolicy int policy) {
        try {
            mVirtualDevice.setDisplayImePolicy(displayId, policy);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    void addActivityListener(
            @CallbackExecutor @NonNull Executor executor,
            @NonNull VirtualDeviceManager.ActivityListener listener) {
        final ActivityListenerDelegate delegate = new ActivityListenerDelegate(
                Objects.requireNonNull(listener), Objects.requireNonNull(executor));
        synchronized (mActivityListenersLock) {
            mActivityListeners.put(listener, delegate);
        }
    }

    void removeActivityListener(@NonNull VirtualDeviceManager.ActivityListener listener) {
        synchronized (mActivityListenersLock) {
            mActivityListeners.remove(Objects.requireNonNull(listener));
        }
    }

    void addSoundEffectListener(@CallbackExecutor @NonNull Executor executor,
            @NonNull VirtualDeviceManager.SoundEffectListener soundEffectListener) {
        final SoundEffectListenerDelegate delegate =
                new SoundEffectListenerDelegate(Objects.requireNonNull(executor),
                        Objects.requireNonNull(soundEffectListener));
        synchronized (mSoundEffectListenersLock) {
            mSoundEffectListeners.put(soundEffectListener, delegate);
        }
    }

    void removeSoundEffectListener(
            @NonNull VirtualDeviceManager.SoundEffectListener soundEffectListener) {
        synchronized (mSoundEffectListenersLock) {
            mSoundEffectListeners.remove(Objects.requireNonNull(soundEffectListener));
        }
    }

    void registerIntentInterceptor(
            @NonNull IntentFilter interceptorFilter,
            @CallbackExecutor @NonNull Executor executor,
            @NonNull VirtualDeviceManager.IntentInterceptorCallback interceptorCallback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(interceptorFilter);
        Objects.requireNonNull(interceptorCallback);
        final IntentInterceptorDelegate delegate =
                new IntentInterceptorDelegate(executor, interceptorCallback);
        try {
            mVirtualDevice.registerIntentInterceptor(delegate, interceptorFilter);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        synchronized (mIntentInterceptorListenersLock) {
            mIntentInterceptorListeners.put(interceptorCallback, delegate);
        }
    }

    void unregisterIntentInterceptor(
            @NonNull VirtualDeviceManager.IntentInterceptorCallback interceptorCallback) {
        Objects.requireNonNull(interceptorCallback);
        final IntentInterceptorDelegate delegate;
        synchronized (mIntentInterceptorListenersLock) {
            delegate = mIntentInterceptorListeners.remove(interceptorCallback);
        }
        if (delegate != null) {
            try {
                mVirtualDevice.unregisterIntentInterceptor(delegate);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * A wrapper for {@link VirtualDeviceManager.ActivityListener} that executes callbacks on the
     * given executor.
     */
    private static class ActivityListenerDelegate {
        @NonNull private final VirtualDeviceManager.ActivityListener mActivityListener;
        @NonNull private final Executor mExecutor;

        ActivityListenerDelegate(@NonNull VirtualDeviceManager.ActivityListener listener,
                @NonNull Executor executor) {
            mActivityListener = listener;
            mExecutor = executor;
        }

        public void onTopActivityChanged(int displayId, ComponentName topActivity) {
            mExecutor.execute(() -> mActivityListener.onTopActivityChanged(displayId, topActivity));
        }

        public void onTopActivityChanged(int displayId, ComponentName topActivity,
                @UserIdInt int userId) {
            mExecutor.execute(() ->
                    mActivityListener.onTopActivityChanged(displayId, topActivity, userId));
        }

        public void onDisplayEmpty(int displayId) {
            mExecutor.execute(() -> mActivityListener.onDisplayEmpty(displayId));
        }
    }

    /**
     * A wrapper for {@link VirtualDeviceManager.IntentInterceptorCallback} that executes callbacks
     * on the given executor.
     */
    private static class IntentInterceptorDelegate extends IVirtualDeviceIntentInterceptor.Stub {
        @NonNull private final VirtualDeviceManager.IntentInterceptorCallback
                mIntentInterceptorCallback;
        @NonNull private final Executor mExecutor;

        private IntentInterceptorDelegate(Executor executor,
                VirtualDeviceManager.IntentInterceptorCallback interceptorCallback) {
            mExecutor = executor;
            mIntentInterceptorCallback = interceptorCallback;
        }

        @Override
        public void onIntentIntercepted(Intent intent) {
            final long token = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mIntentInterceptorCallback.onIntentIntercepted(intent));
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    /**
     * A wrapper for {@link VirtualDeviceManager.SoundEffectListener} that executes callbacks on the
     * given executor.
     */
    private static class SoundEffectListenerDelegate {
        @NonNull private final VirtualDeviceManager.SoundEffectListener mSoundEffectListener;
        @NonNull private final Executor mExecutor;

        private SoundEffectListenerDelegate(Executor executor,
                VirtualDeviceManager.SoundEffectListener soundEffectCallback) {
            mSoundEffectListener = soundEffectCallback;
            mExecutor = executor;
        }

        public void onPlaySoundEffect(@AudioManager.SystemSoundEffect int effectType) {
            mExecutor.execute(() -> mSoundEffectListener.onPlaySoundEffect(effectType));
        }
    }
}
