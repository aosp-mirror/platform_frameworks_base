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

package com.android.server.companion.virtual;

import static android.app.admin.DevicePolicyManager.NEARBY_STREAMING_ENABLED;
import static android.app.admin.DevicePolicyManager.NEARBY_STREAMING_NOT_CONTROLLED_BY_POLICY;
import static android.app.admin.DevicePolicyManager.NEARBY_STREAMING_SAME_MANAGED_ACCOUNT_ONLY;
import static android.view.WindowManager.LayoutParams.FLAG_SECURE;
import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import android.annotation.EnforcePermission;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.annotation.UserIdInt;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.companion.AssociationInfo;
import android.companion.virtual.IVirtualDevice;
import android.companion.virtual.IVirtualDeviceActivityListener;
import android.companion.virtual.IVirtualDeviceIntentInterceptor;
import android.companion.virtual.IVirtualDeviceSoundEffectListener;
import android.companion.virtual.VirtualDeviceManager;
import android.companion.virtual.VirtualDeviceManager.ActivityListener;
import android.companion.virtual.VirtualDeviceParams;
import android.companion.virtual.audio.IAudioConfigChangedCallback;
import android.companion.virtual.audio.IAudioRoutingCallback;
import android.companion.virtual.sensor.VirtualSensorConfig;
import android.companion.virtual.sensor.VirtualSensorEvent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.graphics.PointF;
import android.hardware.display.DisplayManager;
import android.hardware.input.VirtualDpadConfig;
import android.hardware.input.VirtualKeyEvent;
import android.hardware.input.VirtualKeyboardConfig;
import android.hardware.input.VirtualMouseButtonEvent;
import android.hardware.input.VirtualMouseConfig;
import android.hardware.input.VirtualMouseRelativeEvent;
import android.hardware.input.VirtualMouseScrollEvent;
import android.hardware.input.VirtualNavigationTouchpadConfig;
import android.hardware.input.VirtualTouchEvent;
import android.hardware.input.VirtualTouchscreenConfig;
import android.os.Binder;
import android.os.IBinder;
import android.os.LocaleList;
import android.os.Looper;
import android.os.PermissionEnforcer;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.WindowManager;
import android.widget.Toast;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.BlockedAppStreamingActivity;
import com.android.server.companion.virtual.GenericWindowPolicyController.RunningAppsChangedListener;
import com.android.server.companion.virtual.audio.VirtualAudioController;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;


final class VirtualDeviceImpl extends IVirtualDevice.Stub
        implements IBinder.DeathRecipient, RunningAppsChangedListener {

    private static final String TAG = "VirtualDeviceImpl";

    /**
     * Timeout until {@link #launchPendingIntent} stops waiting for an activity to be launched.
     */
    private static final long PENDING_TRAMPOLINE_TIMEOUT_MS = 5000;

    private final Object mVirtualDeviceLock = new Object();

    private final Context mContext;
    private final AssociationInfo mAssociationInfo;
    private final PendingTrampolineCallback mPendingTrampolineCallback;
    private final int mOwnerUid;
    private final int mDeviceId;
    private final InputController mInputController;
    private final SensorController mSensorController;
    private final CameraAccessController mCameraAccessController;
    private VirtualAudioController mVirtualAudioController;
    @VisibleForTesting
    final Set<Integer> mVirtualDisplayIds = new ArraySet<>();
    private final OnDeviceCloseListener mOnDeviceCloseListener;
    private final IBinder mAppToken;
    private final VirtualDeviceParams mParams;
    private final Map<Integer, PowerManager.WakeLock> mPerDisplayWakelocks = new ArrayMap<>();
    private final IVirtualDeviceActivityListener mActivityListener;
    private final IVirtualDeviceSoundEffectListener mSoundEffectListener;
    @GuardedBy("mVirtualDeviceLock")
    private final Map<IBinder, IntentFilter> mIntentInterceptors = new ArrayMap<>();
    @NonNull
    private Consumer<ArraySet<Integer>> mRunningAppsChangedCallback;
    // The default setting for showing the pointer on new displays.
    @GuardedBy("mVirtualDeviceLock")
    private boolean mDefaultShowPointerIcon = true;
    @GuardedBy("mVirtualDeviceLock")
    @Nullable
    private LocaleList mLocaleList = null;

    private ActivityListener createListenerAdapter() {
        return new ActivityListener() {

            @Override
            public void onTopActivityChanged(int displayId, ComponentName topActivity) {
                try {
                    mActivityListener.onTopActivityChanged(displayId, topActivity,
                            UserHandle.USER_NULL);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Unable to call mActivityListener", e);
                }
            }

            @Override
            public void onTopActivityChanged(int displayId, ComponentName topActivity,
                    @UserIdInt int userId) {
                try {
                    mActivityListener.onTopActivityChanged(displayId, topActivity, userId);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Unable to call mActivityListener", e);
                }
            }

            @Override
            public void onDisplayEmpty(int displayId) {
                try {
                    mActivityListener.onDisplayEmpty(displayId);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Unable to call mActivityListener", e);
                }
            }
        };
    }

    /**
     * A mapping from the virtual display ID to its corresponding
     * {@link GenericWindowPolicyController}.
     */
    private final SparseArray<GenericWindowPolicyController> mWindowPolicyControllers =
            new SparseArray<>();

    VirtualDeviceImpl(
            Context context,
            AssociationInfo associationInfo,
            IBinder token,
            int ownerUid,
            int deviceId,
            CameraAccessController cameraAccessController,
            OnDeviceCloseListener onDeviceCloseListener,
            PendingTrampolineCallback pendingTrampolineCallback,
            IVirtualDeviceActivityListener activityListener,
            IVirtualDeviceSoundEffectListener soundEffectListener,
            Consumer<ArraySet<Integer>> runningAppsChangedCallback,
            VirtualDeviceParams params) {
        this(
                context,
                associationInfo,
                token,
                ownerUid,
                deviceId,
                /* inputController= */ null,
                /* sensorController= */ null,
                cameraAccessController,
                onDeviceCloseListener,
                pendingTrampolineCallback,
                activityListener,
                soundEffectListener,
                runningAppsChangedCallback,
                params);
    }

    @VisibleForTesting
    VirtualDeviceImpl(
            Context context,
            AssociationInfo associationInfo,
            IBinder token,
            int ownerUid,
            int deviceId,
            InputController inputController,
            SensorController sensorController,
            CameraAccessController cameraAccessController,
            OnDeviceCloseListener onDeviceCloseListener,
            PendingTrampolineCallback pendingTrampolineCallback,
            IVirtualDeviceActivityListener activityListener,
            IVirtualDeviceSoundEffectListener soundEffectListener,
            Consumer<ArraySet<Integer>> runningAppsChangedCallback,
            VirtualDeviceParams params) {
        super(PermissionEnforcer.fromContext(context));
        UserHandle ownerUserHandle = UserHandle.getUserHandleForUid(ownerUid);
        mContext = context.createContextAsUser(ownerUserHandle, 0);
        mAssociationInfo = associationInfo;
        mPendingTrampolineCallback = pendingTrampolineCallback;
        mActivityListener = activityListener;
        mSoundEffectListener = soundEffectListener;
        mRunningAppsChangedCallback = runningAppsChangedCallback;
        mOwnerUid = ownerUid;
        mDeviceId = deviceId;
        mAppToken = token;
        mParams = params;
        if (inputController == null) {
            mInputController = new InputController(
                    mVirtualDeviceLock,
                    context.getMainThreadHandler(),
                    context.getSystemService(WindowManager.class));
        } else {
            mInputController = inputController;
        }
        if (sensorController == null) {
            mSensorController = new SensorController(mVirtualDeviceLock, mDeviceId);
        } else {
            mSensorController = sensorController;
        }
        mCameraAccessController = cameraAccessController;
        mCameraAccessController.startObservingIfNeeded();
        mOnDeviceCloseListener = onDeviceCloseListener;
        try {
            token.linkToDeath(this, 0);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the flags that should be added to any virtual displays created on this virtual
     * device.
     */
    int getBaseVirtualDisplayFlags() {
        int flags = 0;
        if (mParams.getLockState() == VirtualDeviceParams.LOCK_STATE_ALWAYS_UNLOCKED) {
            flags |= DisplayManager.VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED;
        }
        return flags;
    }

    /** Returns the camera access controller of this device. */
    CameraAccessController getCameraAccessController() {
        return mCameraAccessController;
    }

    /** Returns the device display name. */
    CharSequence getDisplayName() {
        return mAssociationInfo.getDisplayName();
    }

    /** Returns the optional name of the device. */
    String getDeviceName() {
        return mParams.getName();
    }

    /** Returns the locale of the device. */
    LocaleList getDeviceLocaleList() {
        synchronized (mVirtualDeviceLock) {
            return mLocaleList;
        }
    }

    /** Returns the policy specified for this policy type */
    public @VirtualDeviceParams.DevicePolicy int getDevicePolicy(
            @VirtualDeviceParams.PolicyType int policyType) {
        return mParams.getDevicePolicy(policyType);
    }

    /** Returns device-specific audio session id for playback. */
    public int getAudioPlaybackSessionId() {
        return mParams.getAudioPlaybackSessionId();
    }

    /** Returns device-specific audio session id for recording. */
    public int getAudioRecordingSessionId() {
        return mParams.getAudioRecordingSessionId();
    }

    /** Returns the unique device ID of this device. */
    @Override // Binder call
    public int getDeviceId() {
        return mDeviceId;
    }

    @Override // Binder call
    public int getAssociationId() {
        return mAssociationInfo.getId();
    }

    @Override // Binder call
    public void launchPendingIntent(int displayId, PendingIntent pendingIntent,
            ResultReceiver resultReceiver) {
        if (!mVirtualDisplayIds.contains(displayId)) {
            throw new SecurityException("Display ID " + displayId
                    + " not found for this virtual device");
        }
        if (pendingIntent.isActivity()) {
            try {
                sendPendingIntent(displayId, pendingIntent);
                resultReceiver.send(VirtualDeviceManager.LAUNCH_SUCCESS, null);
            } catch (PendingIntent.CanceledException e) {
                Slog.w(TAG, "Pending intent canceled", e);
                resultReceiver.send(
                        VirtualDeviceManager.LAUNCH_FAILURE_PENDING_INTENT_CANCELED, null);
            }
        } else {
            PendingTrampoline pendingTrampoline = new PendingTrampoline(pendingIntent,
                    resultReceiver, displayId);
            mPendingTrampolineCallback.startWaitingForPendingTrampoline(pendingTrampoline);
            mContext.getMainThreadHandler().postDelayed(() -> {
                pendingTrampoline.mResultReceiver.send(
                        VirtualDeviceManager.LAUNCH_FAILURE_NO_ACTIVITY, null);
                mPendingTrampolineCallback.stopWaitingForPendingTrampoline(pendingTrampoline);
            }, PENDING_TRAMPOLINE_TIMEOUT_MS);
            try {
                sendPendingIntent(displayId, pendingIntent);
            } catch (PendingIntent.CanceledException e) {
                Slog.w(TAG, "Pending intent canceled", e);
                resultReceiver.send(
                        VirtualDeviceManager.LAUNCH_FAILURE_PENDING_INTENT_CANCELED, null);
                mPendingTrampolineCallback.stopWaitingForPendingTrampoline(pendingTrampoline);
            }
        }
    }

    private void sendPendingIntent(int displayId, PendingIntent pendingIntent)
            throws PendingIntent.CanceledException {
        final ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId);
        options.setPendingIntentBackgroundActivityLaunchAllowed(true);
        options.setPendingIntentBackgroundActivityLaunchAllowedByPermission(true);
        pendingIntent.send(
                mContext,
                /* code= */ 0,
                /* intent= */ null,
                /* onFinished= */ null,
                /* handler= */ null,
                /* requiredPermission= */ null,
                options.toBundle());
    }

    @Override // Binder call
    @EnforcePermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public void close() {
        super.close_enforcePermission();
        synchronized (mVirtualDeviceLock) {
            if (!mPerDisplayWakelocks.isEmpty()) {
                mPerDisplayWakelocks.forEach((displayId, wakeLock) -> {
                    Slog.w(TAG, "VirtualDisplay " + displayId + " owned by UID " + mOwnerUid
                            + " was not properly released");
                    wakeLock.release();
                });
                mPerDisplayWakelocks.clear();
            }
            if (mVirtualAudioController != null) {
                mVirtualAudioController.stopListening();
                mVirtualAudioController = null;
            }
            mLocaleList = null;
        }
        mOnDeviceCloseListener.onClose(mDeviceId);
        mAppToken.unlinkToDeath(this, 0);
        mCameraAccessController.stopObservingIfNeeded();

        final long ident = Binder.clearCallingIdentity();
        try {
            mInputController.close();
            mSensorController.close();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void binderDied() {
        close();
    }

    @Override
    public void onRunningAppsChanged(ArraySet<Integer> runningUids) {
        mCameraAccessController.blockCameraAccessIfNeeded(runningUids);
        mRunningAppsChangedCallback.accept(runningUids);
    }

    @VisibleForTesting
    VirtualAudioController getVirtualAudioControllerForTesting() {
        return mVirtualAudioController;
    }

    @VisibleForTesting
    SparseArray<GenericWindowPolicyController> getWindowPolicyControllersForTesting() {
        return mWindowPolicyControllers;
    }

    @Override // Binder call
    @EnforcePermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public void onAudioSessionStarting(int displayId,
            @NonNull IAudioRoutingCallback routingCallback,
            @Nullable IAudioConfigChangedCallback configChangedCallback) {
        super.onAudioSessionStarting_enforcePermission();
        synchronized (mVirtualDeviceLock) {
            if (!mVirtualDisplayIds.contains(displayId)) {
                throw new SecurityException(
                        "Cannot start audio session for a display not associated with this virtual "
                                + "device");
            }

            if (mVirtualAudioController == null) {
                mVirtualAudioController = new VirtualAudioController(mContext);
                GenericWindowPolicyController gwpc = mWindowPolicyControllers.get(displayId);
                mVirtualAudioController.startListening(gwpc, routingCallback,
                        configChangedCallback);
            }
        }
    }

    @Override // Binder call
    @EnforcePermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public void onAudioSessionEnded() {
        super.onAudioSessionEnded_enforcePermission();
        synchronized (mVirtualDeviceLock) {
            if (mVirtualAudioController != null) {
                mVirtualAudioController.stopListening();
                mVirtualAudioController = null;
            }
        }
    }

    @Override // Binder call
    @EnforcePermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public void createVirtualDpad(VirtualDpadConfig config, @NonNull IBinder deviceToken) {
        super.createVirtualDpad_enforcePermission();
        synchronized (mVirtualDeviceLock) {
            if (!mVirtualDisplayIds.contains(config.getAssociatedDisplayId())) {
                throw new SecurityException(
                        "Cannot create a virtual dpad for a display not associated with "
                                + "this virtual device");
            }
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            mInputController.createDpad(config.getInputDeviceName(), config.getVendorId(),
                    config.getProductId(), deviceToken, config.getAssociatedDisplayId());
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    @EnforcePermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public void createVirtualKeyboard(VirtualKeyboardConfig config, @NonNull IBinder deviceToken) {
        super.createVirtualKeyboard_enforcePermission();
        synchronized (mVirtualDeviceLock) {
            if (!mVirtualDisplayIds.contains(config.getAssociatedDisplayId())) {
                throw new SecurityException(
                        "Cannot create a virtual keyboard for a display not associated with "
                                + "this virtual device");
            }
            mLocaleList = LocaleList.forLanguageTags(config.getLanguageTag());
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            mInputController.createKeyboard(config.getInputDeviceName(), config.getVendorId(),
                    config.getProductId(), deviceToken, config.getAssociatedDisplayId(),
                    config.getLanguageTag(), config.getLayoutType());
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    @EnforcePermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public void createVirtualMouse(VirtualMouseConfig config, @NonNull IBinder deviceToken) {
        super.createVirtualMouse_enforcePermission();
        synchronized (mVirtualDeviceLock) {
            if (!mVirtualDisplayIds.contains(config.getAssociatedDisplayId())) {
                throw new SecurityException(
                        "Cannot create a virtual mouse for a display not associated with this "
                                + "virtual device");
            }
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            mInputController.createMouse(config.getInputDeviceName(), config.getVendorId(),
                    config.getProductId(), deviceToken, config.getAssociatedDisplayId());
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    @EnforcePermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public void createVirtualTouchscreen(VirtualTouchscreenConfig config,
            @NonNull IBinder deviceToken) {
        super.createVirtualTouchscreen_enforcePermission();
        synchronized (mVirtualDeviceLock) {
            if (!mVirtualDisplayIds.contains(config.getAssociatedDisplayId())) {
                throw new SecurityException(
                        "Cannot create a virtual touchscreen for a display not associated with "
                                + "this virtual device");
            }
        }
        int screenHeight = config.getHeight();
        int screenWidth = config.getWidth();
        if (screenHeight <= 0 || screenWidth <= 0) {
            throw new IllegalArgumentException(
                    "Cannot create a virtual touchscreen, screen dimensions must be positive. Got: "
                            + "(" + screenWidth + ", " + screenHeight + ")");
        }

        final long ident = Binder.clearCallingIdentity();
        try {
            mInputController.createTouchscreen(config.getInputDeviceName(), config.getVendorId(),
                    config.getProductId(), deviceToken, config.getAssociatedDisplayId(),
                    screenHeight, screenWidth);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    @EnforcePermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public void createVirtualNavigationTouchpad(VirtualNavigationTouchpadConfig config,
            @NonNull IBinder deviceToken) {
        super.createVirtualNavigationTouchpad_enforcePermission();
        synchronized (mVirtualDeviceLock) {
            if (!mVirtualDisplayIds.contains(config.getAssociatedDisplayId())) {
                throw new SecurityException(
                        "Cannot create a virtual navigation touchpad for a display not associated "
                                + "with this virtual device");
            }
        }
        int touchpadHeight = config.getHeight();
        int touchpadWidth = config.getWidth();
        if (touchpadHeight <= 0 || touchpadWidth <= 0) {
            throw new IllegalArgumentException(
                "Cannot create a virtual navigation touchpad, touchpad dimensions must be positive."
                    + " Got: (" + touchpadHeight + ", " + touchpadWidth + ")");
        }

        final long ident = Binder.clearCallingIdentity();
        try {
            mInputController.createNavigationTouchpad(
                    config.getInputDeviceName(), config.getVendorId(),
                    config.getProductId(), deviceToken, config.getAssociatedDisplayId(),
                    touchpadHeight, touchpadWidth);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    @EnforcePermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public void unregisterInputDevice(IBinder token) {
        super.unregisterInputDevice_enforcePermission();
        final long ident = Binder.clearCallingIdentity();
        try {
            mInputController.unregisterInputDevice(token);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    public int getInputDeviceId(IBinder token) {
        final long ident = Binder.clearCallingIdentity();
        try {
            return mInputController.getInputDeviceId(token);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }


    @Override // Binder call
    @EnforcePermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public boolean sendDpadKeyEvent(IBinder token, VirtualKeyEvent event) {
        super.sendDpadKeyEvent_enforcePermission();
        final long ident = Binder.clearCallingIdentity();
        try {
            return mInputController.sendDpadKeyEvent(token, event);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    @EnforcePermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public boolean sendKeyEvent(IBinder token, VirtualKeyEvent event) {
        super.sendKeyEvent_enforcePermission();
        final long ident = Binder.clearCallingIdentity();
        try {
            return mInputController.sendKeyEvent(token, event);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    @EnforcePermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public boolean sendButtonEvent(IBinder token, VirtualMouseButtonEvent event) {
        super.sendButtonEvent_enforcePermission();
        final long ident = Binder.clearCallingIdentity();
        try {
            return mInputController.sendButtonEvent(token, event);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    @EnforcePermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public boolean sendTouchEvent(IBinder token, VirtualTouchEvent event) {
        super.sendTouchEvent_enforcePermission();
        final long ident = Binder.clearCallingIdentity();
        try {
            return mInputController.sendTouchEvent(token, event);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    @EnforcePermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public boolean sendRelativeEvent(IBinder token, VirtualMouseRelativeEvent event) {
        super.sendRelativeEvent_enforcePermission();
        final long ident = Binder.clearCallingIdentity();
        try {
            return mInputController.sendRelativeEvent(token, event);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    @EnforcePermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public boolean sendScrollEvent(IBinder token, VirtualMouseScrollEvent event) {
        super.sendScrollEvent_enforcePermission();
        final long ident = Binder.clearCallingIdentity();
        try {
            return mInputController.sendScrollEvent(token, event);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    public PointF getCursorPosition(IBinder token) {
        final long ident = Binder.clearCallingIdentity();
        try {
            return mInputController.getCursorPosition(token);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    @EnforcePermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public void setShowPointerIcon(boolean showPointerIcon) {
        super.setShowPointerIcon_enforcePermission();
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mVirtualDeviceLock) {
                mDefaultShowPointerIcon = showPointerIcon;
                for (int displayId : mVirtualDisplayIds) {
                    mInputController.setShowPointerIcon(mDefaultShowPointerIcon, displayId);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    @EnforcePermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public void createVirtualSensor(
            @NonNull IBinder deviceToken,
            @NonNull VirtualSensorConfig config) {
        super.createVirtualSensor_enforcePermission();
        Objects.requireNonNull(config);
        Objects.requireNonNull(deviceToken);
        final long ident = Binder.clearCallingIdentity();
        try {
            mSensorController.createSensor(deviceToken, config);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    @EnforcePermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public void unregisterSensor(@NonNull IBinder token) {
        super.unregisterSensor_enforcePermission();
        final long ident = Binder.clearCallingIdentity();
        try {
            mSensorController.unregisterSensor(token);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    @EnforcePermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public boolean sendSensorEvent(@NonNull IBinder token, @NonNull VirtualSensorEvent event) {
        super.sendSensorEvent_enforcePermission();
        final long ident = Binder.clearCallingIdentity();
        try {
            return mSensorController.sendSensorEvent(token, event);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    @EnforcePermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public void registerIntentInterceptor(IVirtualDeviceIntentInterceptor intentInterceptor,
            IntentFilter filter) {
        super.registerIntentInterceptor_enforcePermission();
        Objects.requireNonNull(intentInterceptor);
        Objects.requireNonNull(filter);
        synchronized (mVirtualDeviceLock) {
            mIntentInterceptors.put(intentInterceptor.asBinder(), filter);
        }
    }

    @Override // Binder call
    @EnforcePermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public void unregisterIntentInterceptor(
            @NonNull IVirtualDeviceIntentInterceptor intentInterceptor) {
        super.unregisterIntentInterceptor_enforcePermission();
        Objects.requireNonNull(intentInterceptor);
        synchronized (mVirtualDeviceLock) {
            mIntentInterceptors.remove(intentInterceptor.asBinder());
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
        fout.println("  VirtualDevice: ");
        fout.println("    mDeviceId: " + mDeviceId);
        fout.println("    mAssociationId: " + mAssociationInfo.getId());
        fout.println("    mParams: " + mParams);
        fout.println("    mVirtualDisplayIds: ");
        synchronized (mVirtualDeviceLock) {
            for (int id : mVirtualDisplayIds) {
                fout.println("      " + id);
            }
            fout.println("    mDefaultShowPointerIcon: " + mDefaultShowPointerIcon);
        }
        mInputController.dump(fout);
        mSensorController.dump(fout);
    }

    GenericWindowPolicyController createWindowPolicyController(
            @NonNull List<String> displayCategories) {
        synchronized (mVirtualDeviceLock) {
            final GenericWindowPolicyController gwpc =
                    new GenericWindowPolicyController(FLAG_SECURE,
                            SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS,
                            getAllowedUserHandles(),
                            mParams.getAllowedCrossTaskNavigations(),
                            mParams.getBlockedCrossTaskNavigations(),
                            mParams.getAllowedActivities(),
                            mParams.getBlockedActivities(),
                            mParams.getDefaultActivityPolicy(),
                            createListenerAdapter(),
                            this::onEnteringPipBlocked,
                            this::onActivityBlocked,
                            this::onSecureWindowShown,
                            this::shouldInterceptIntent,
                            displayCategories,
                            mParams.getDefaultRecentsPolicy());
            gwpc.registerRunningAppsChangedListener(/* listener= */ this);
            return gwpc;
        }
    }

    void onVirtualDisplayCreatedLocked(GenericWindowPolicyController gwpc, int displayId) {
        synchronized (mVirtualDeviceLock) {
            if (displayId == Display.INVALID_DISPLAY) {
                return;
            }
            if (mVirtualDisplayIds.contains(displayId)) {
                throw new IllegalStateException(
                        "Virtual device already has a virtual display with ID " + displayId);
            }
            mVirtualDisplayIds.add(displayId);

            gwpc.setDisplayId(displayId);
            mWindowPolicyControllers.put(displayId, gwpc);

            mInputController.setShowPointerIcon(mDefaultShowPointerIcon, displayId);
            mInputController.setPointerAcceleration(1f, displayId);
            mInputController.setDisplayEligibilityForPointerCapture(/* isEligible= */ false,
                    displayId);
            mInputController.setLocalIme(displayId);


            if (mPerDisplayWakelocks.containsKey(displayId)) {
                Slog.e(TAG, "Not creating wakelock for displayId " + displayId);
                return;
            }
            PowerManager powerManager = mContext.getSystemService(PowerManager.class);
            PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                    TAG + ":" + displayId, displayId);
            mPerDisplayWakelocks.put(displayId, wakeLock);
            wakeLock.acquire();
        }
    }

    private void onActivityBlocked(int displayId, ActivityInfo activityInfo) {
        Intent intent = BlockedAppStreamingActivity.createIntent(
                activityInfo, mAssociationInfo.getDisplayName());
        mContext.startActivityAsUser(
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK),
                ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle(),
                mContext.getUser());
    }

    private void onSecureWindowShown(int displayId, int uid) {
        if (!mVirtualDisplayIds.contains(displayId)) {
            return;
        }

        // If a virtual display isn't secure, the screen can't be captured. Show a warning toast
        // if the secure window is shown on a non-secure virtual display.
        DisplayManager displayManager = mContext.getSystemService(DisplayManager.class);
        Display display = displayManager.getDisplay(displayId);
        if ((display.getFlags() & FLAG_SECURE) == 0) {
            showToastWhereUidIsRunning(uid, com.android.internal.R.string.vdm_secure_window,
                    Toast.LENGTH_LONG, mContext.getMainLooper());
        }
    }

    private ArraySet<UserHandle> getAllowedUserHandles() {
        ArraySet<UserHandle> result = new ArraySet<>();
        DevicePolicyManager dpm = mContext.getSystemService(DevicePolicyManager.class);
        UserManager userManager = mContext.getSystemService(UserManager.class);
        for (UserHandle profile : userManager.getAllProfiles()) {
            int nearbyAppStreamingPolicy = dpm.getNearbyAppStreamingPolicy(profile.getIdentifier());
            if (nearbyAppStreamingPolicy == NEARBY_STREAMING_ENABLED
                    || nearbyAppStreamingPolicy == NEARBY_STREAMING_NOT_CONTROLLED_BY_POLICY) {
                result.add(profile);
            } else if (nearbyAppStreamingPolicy == NEARBY_STREAMING_SAME_MANAGED_ACCOUNT_ONLY) {
                if (mParams.getUsersWithMatchingAccounts().contains(profile)) {
                    result.add(profile);
                }
            }
        }
        return result;
    }

    void onVirtualDisplayRemovedLocked(int displayId) {
        synchronized (mVirtualDeviceLock) {
            if (!mVirtualDisplayIds.contains(displayId)) {
                throw new IllegalStateException(
                        "Virtual device doesn't have a virtual display with ID " + displayId);
            }
            PowerManager.WakeLock wakeLock = mPerDisplayWakelocks.get(displayId);
            if (wakeLock != null) {
                wakeLock.release();
                mPerDisplayWakelocks.remove(displayId);
            }
            GenericWindowPolicyController gwpc = mWindowPolicyControllers.get(displayId);
            if (gwpc != null) {
                gwpc.unregisterRunningAppsChangedListener(/* listener= */ this);
            }
            mVirtualDisplayIds.remove(displayId);
            mWindowPolicyControllers.remove(displayId);
        }
    }

    int getOwnerUid() {
        return mOwnerUid;
    }

    /**
     * Returns true if an app with the given {@code uid} is currently running on this virtual
     * device.
     */
    boolean isAppRunningOnVirtualDevice(int uid) {
        final int size = mWindowPolicyControllers.size();
        for (int i = 0; i < size; i++) {
            if (mWindowPolicyControllers.valueAt(i).containsUid(uid)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Shows a toast on virtual displays owned by this device which have a given uid running.
     */
    void showToastWhereUidIsRunning(int uid, @StringRes int resId, @Toast.Duration int duration,
            Looper looper) {
        showToastWhereUidIsRunning(uid, mContext.getString(resId), duration, looper);
    }

    /**
     * Shows a toast on virtual displays owned by this device which have a given uid running.
     */
    void showToastWhereUidIsRunning(int uid, String text, @Toast.Duration int duration,
            Looper looper) {
        synchronized (mVirtualDeviceLock) {
            DisplayManager displayManager = mContext.getSystemService(DisplayManager.class);
            final int size = mWindowPolicyControllers.size();
            for (int i = 0; i < size; i++) {
                if (mWindowPolicyControllers.valueAt(i).containsUid(uid)) {
                    int displayId = mWindowPolicyControllers.keyAt(i);
                    Display display = displayManager.getDisplay(displayId);
                    if (display != null && display.isValid()) {
                        Toast.makeText(mContext.createDisplayContext(display), looper, text,
                                duration).show();
                    }
                }
            }
        }
    }

    boolean isDisplayOwnedByVirtualDevice(int displayId) {
        return mVirtualDisplayIds.contains(displayId);
    }

    void onEnteringPipBlocked(int uid) {
        showToastWhereUidIsRunning(uid, com.android.internal.R.string.vdm_pip_blocked,
                Toast.LENGTH_LONG, mContext.getMainLooper());
    }

    void playSoundEffect(int effectType) {
        try {
            mSoundEffectListener.onPlaySoundEffect(effectType);
        } catch (RemoteException exception) {
            Slog.w(TAG, "Unable to invoke sound effect listener", exception);
        }
    }

    /**
     * Intercepts intent when matching any of the IntentFilter of any interceptor. Returns true if
     * the intent matches any filter notifying the DisplayPolicyController to abort the
     * activity launch to be replaced by the interception.
     */
    private boolean shouldInterceptIntent(Intent intent) {
        synchronized (mVirtualDeviceLock) {
            boolean hasInterceptedIntent = false;
            for (Map.Entry<IBinder, IntentFilter> interceptor : mIntentInterceptors.entrySet()) {
                if (interceptor.getValue().match(
                        intent.getAction(), intent.getType(), intent.getScheme(), intent.getData(),
                        intent.getCategories(), TAG) >= 0) {
                    try {
                        // For privacy reasons, only returning the intents action and data. Any
                        // other required field will require a review.
                        IVirtualDeviceIntentInterceptor.Stub.asInterface(interceptor.getKey())
                            .onIntentIntercepted(new Intent(intent.getAction(), intent.getData()));
                        hasInterceptedIntent = true;
                    } catch (RemoteException e) {
                        Slog.w(TAG, "Unable to call mVirtualDeviceIntentInterceptor", e);
                    }
                }
            }

            return hasInterceptedIntent;
        }
    }

    interface OnDeviceCloseListener {
        void onClose(int deviceId);
    }

    interface PendingTrampolineCallback {
        /**
         * Called when the callback should start waiting for the given pending trampoline.
         * Implementations should try to listen for activity starts associated with the given
         * {@code pendingTrampoline}, and launch the activity on the display with
         * {@link PendingTrampoline#mDisplayId}.
         */
        void startWaitingForPendingTrampoline(PendingTrampoline pendingTrampoline);

        /**
         * Called when the callback should stop waiting for the given pending trampoline. This can
         * happen, for example, when the pending intent failed to send.
         */
        void stopWaitingForPendingTrampoline(PendingTrampoline pendingTrampoline);
    }

    /**
     * A data class storing a pending trampoline this device is expecting.
     */
    static class PendingTrampoline {

        /**
         * The original pending intent sent, for which a trampoline activity launch is expected.
         */
        final PendingIntent mPendingIntent;

        /**
         * The result receiver associated with this pending call. {@link Activity#RESULT_OK} will
         * be sent to the receiver if the trampoline activity was captured successfully.
         * {@link Activity#RESULT_CANCELED} is sent otherwise.
         */
        final ResultReceiver mResultReceiver;

        /**
         * The display ID to send the captured trampoline activity launch to.
         */
        final int mDisplayId;

        private PendingTrampoline(PendingIntent pendingIntent, ResultReceiver resultReceiver,
                int displayId) {
            mPendingIntent = pendingIntent;
            mResultReceiver = resultReceiver;
            mDisplayId = displayId;
        }

        @Override
        public String toString() {
            return "PendingTrampoline{"
                    + "pendingIntent=" + mPendingIntent
                    + ", resultReceiver=" + mResultReceiver
                    + ", displayId=" + mDisplayId + "}";
        }
    }
}
