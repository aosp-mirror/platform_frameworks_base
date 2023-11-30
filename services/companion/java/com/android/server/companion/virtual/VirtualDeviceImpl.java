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

package com.android.server.companion.virtual;

import static android.app.admin.DevicePolicyManager.NEARBY_STREAMING_ENABLED;
import static android.app.admin.DevicePolicyManager.NEARBY_STREAMING_NOT_CONTROLLED_BY_POLICY;
import static android.app.admin.DevicePolicyManager.NEARBY_STREAMING_SAME_MANAGED_ACCOUNT_ONLY;
import static android.companion.virtual.VirtualDeviceParams.ACTIVITY_POLICY_DEFAULT_ALLOWED;
import static android.companion.virtual.VirtualDeviceParams.DEVICE_POLICY_DEFAULT;
import static android.companion.virtual.VirtualDeviceParams.NAVIGATION_POLICY_DEFAULT_ALLOWED;
import static android.companion.virtual.VirtualDeviceParams.POLICY_TYPE_ACTIVITY;
import static android.companion.virtual.VirtualDeviceParams.POLICY_TYPE_CLIPBOARD;
import static android.companion.virtual.VirtualDeviceParams.POLICY_TYPE_RECENTS;
import static android.content.pm.PackageManager.ACTION_REQUEST_PERMISSIONS;
import static android.view.WindowManager.LayoutParams.FLAG_SECURE;
import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import android.annotation.EnforcePermission;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
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
import android.companion.virtual.VirtualDevice;
import android.companion.virtual.VirtualDeviceManager;
import android.companion.virtual.VirtualDeviceManager.ActivityListener;
import android.companion.virtual.VirtualDeviceParams;
import android.companion.virtual.audio.IAudioConfigChangedCallback;
import android.companion.virtual.audio.IAudioRoutingCallback;
import android.companion.virtual.camera.VirtualCameraConfig;
import android.companion.virtual.flags.Flags;
import android.companion.virtual.sensor.VirtualSensor;
import android.companion.virtual.sensor.VirtualSensorEvent;
import android.content.AttributionSource;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.PointF;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerGlobal;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.display.IVirtualDisplayCallback;
import android.hardware.display.VirtualDisplayConfig;
import android.hardware.input.VirtualDpadConfig;
import android.hardware.input.VirtualKeyEvent;
import android.hardware.input.VirtualKeyboardConfig;
import android.hardware.input.VirtualMouseButtonEvent;
import android.hardware.input.VirtualMouseConfig;
import android.hardware.input.VirtualMouseRelativeEvent;
import android.hardware.input.VirtualMouseScrollEvent;
import android.hardware.input.VirtualNavigationTouchpadConfig;
import android.hardware.input.VirtualStylusButtonEvent;
import android.hardware.input.VirtualStylusConfig;
import android.hardware.input.VirtualStylusMotionEvent;
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
import android.util.IntArray;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.WindowManager;
import android.widget.Toast;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.BlockedAppStreamingActivity;
import com.android.server.LocalServices;
import com.android.server.companion.virtual.GenericWindowPolicyController.RunningAppsChangedListener;
import com.android.server.companion.virtual.audio.VirtualAudioController;
import com.android.server.companion.virtual.camera.VirtualCameraController;
import com.android.server.inputmethod.InputMethodManagerInternal;

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

    private static final int DEFAULT_VIRTUAL_DISPLAY_FLAGS =
            DisplayManager.VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_FOCUS;

    private static final int DEFAULT_VIRTUAL_DISPLAY_FLAGS_PRE_VIC =
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;

    private static final String PERSISTENT_ID_PREFIX_CDM_ASSOCIATION = "companion:";

    /**
     * Timeout until {@link #launchPendingIntent} stops waiting for an activity to be launched.
     */
    private static final long PENDING_TRAMPOLINE_TIMEOUT_MS = 5000;

    private final Object mVirtualDeviceLock = new Object();

    private final int mBaseVirtualDisplayFlags;

    private final Context mContext;
    private final AssociationInfo mAssociationInfo;
    private final VirtualDeviceManagerService mService;
    private final PendingTrampolineCallback mPendingTrampolineCallback;
    private final int mOwnerUid;
    private final VirtualDeviceLog mVirtualDeviceLog;
    private final String mOwnerPackageName;
    private final int mDeviceId;
    @Nullable
    private final String mPersistentDeviceId;
    // Thou shall not hold the mVirtualDeviceLock over the mInputController calls.
    // Holding the lock can lead to lock inversion with GlobalWindowManagerLock.
    // 1. After display is created the window manager calls into VDM during construction
    //   of display specific context to fetch device id corresponding to the display.
    //   mVirtualDeviceLock will be held while this is done.
    // 2. InputController interactions result in calls to DisplayManager (to set IME,
    //    possibly more indirect calls), and those attempt to lock GlobalWindowManagerLock which
    //    creates lock inversion.
    private final InputController mInputController;
    private final SensorController mSensorController;
    private final CameraAccessController mCameraAccessController;
    @Nullable // Null if virtual camera flag is off.
    private final VirtualCameraController mVirtualCameraController;
    private VirtualAudioController mVirtualAudioController;
    private final IBinder mAppToken;
    private final VirtualDeviceParams mParams;
    @GuardedBy("mVirtualDeviceLock")
    private final SparseIntArray mDevicePolicies;
    @GuardedBy("mVirtualDeviceLock")
    private final SparseArray<VirtualDisplayWrapper> mVirtualDisplays = new SparseArray<>();
    private final IVirtualDeviceActivityListener mActivityListener;
    private final IVirtualDeviceSoundEffectListener mSoundEffectListener;
    private final DisplayManagerGlobal mDisplayManager;
    private final DisplayManagerInternal mDisplayManagerInternal;
    @GuardedBy("mVirtualDeviceLock")
    private final Map<IBinder, IntentFilter> mIntentInterceptors = new ArrayMap<>();
    @NonNull
    private final Consumer<ArraySet<Integer>> mRunningAppsChangedCallback;
    // The default setting for showing the pointer on new displays.
    @GuardedBy("mVirtualDeviceLock")
    private boolean mDefaultShowPointerIcon = true;
    @GuardedBy("mVirtualDeviceLock")
    @Nullable
    private LocaleList mLocaleList = null;

    @NonNull
    private final VirtualDevice mPublicVirtualDeviceObject;

    @GuardedBy("mVirtualDeviceLock")
    @NonNull
    private final Set<ComponentName> mActivityPolicyExemptions;
    private final ComponentName mPermissionDialogComponent;

    private ActivityListener createListenerAdapter() {
        return new ActivityListener() {

            @Override
            public void onTopActivityChanged(int displayId, @NonNull ComponentName topActivity) {
                try {
                    mActivityListener.onTopActivityChanged(displayId, topActivity,
                            UserHandle.USER_NULL);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Unable to call mActivityListener", e);
                }
            }

            @Override
            public void onTopActivityChanged(int displayId, @NonNull ComponentName topActivity,
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

    VirtualDeviceImpl(
            Context context,
            AssociationInfo associationInfo,
            VirtualDeviceManagerService service,
            VirtualDeviceLog virtualDeviceLog,
            IBinder token,
            AttributionSource attributionSource,
            int deviceId,
            CameraAccessController cameraAccessController,
            PendingTrampolineCallback pendingTrampolineCallback,
            IVirtualDeviceActivityListener activityListener,
            IVirtualDeviceSoundEffectListener soundEffectListener,
            Consumer<ArraySet<Integer>> runningAppsChangedCallback,
            VirtualDeviceParams params) {
        this(
                context,
                associationInfo,
                service,
                virtualDeviceLog,
                token,
                attributionSource,
                deviceId,
                /* inputController= */ null,
                cameraAccessController,
                pendingTrampolineCallback,
                activityListener,
                soundEffectListener,
                runningAppsChangedCallback,
                params,
                DisplayManagerGlobal.getInstance(),
                Flags.virtualCamera() ? new VirtualCameraController() : null);
    }

    @VisibleForTesting
    VirtualDeviceImpl(
            Context context,
            AssociationInfo associationInfo,
            VirtualDeviceManagerService service,
            VirtualDeviceLog virtualDeviceLog,
            IBinder token,
            AttributionSource attributionSource,
            int deviceId,
            InputController inputController,
            CameraAccessController cameraAccessController,
            PendingTrampolineCallback pendingTrampolineCallback,
            IVirtualDeviceActivityListener activityListener,
            IVirtualDeviceSoundEffectListener soundEffectListener,
            Consumer<ArraySet<Integer>> runningAppsChangedCallback,
            VirtualDeviceParams params,
            DisplayManagerGlobal displayManager,
            VirtualCameraController virtualCameraController) {
        super(PermissionEnforcer.fromContext(context));
        mVirtualDeviceLog = virtualDeviceLog;
        mOwnerPackageName = attributionSource.getPackageName();
        UserHandle ownerUserHandle = UserHandle.getUserHandleForUid(attributionSource.getUid());
        mContext = context.createContextAsUser(ownerUserHandle, 0);
        mAssociationInfo = associationInfo;
        mPersistentDeviceId = createPersistentDeviceId(associationInfo.getId());
        mService = service;
        mPendingTrampolineCallback = pendingTrampolineCallback;
        mActivityListener = activityListener;
        mSoundEffectListener = soundEffectListener;
        mRunningAppsChangedCallback = runningAppsChangedCallback;
        mOwnerUid = attributionSource.getUid();
        mDeviceId = deviceId;
        mAppToken = token;
        mParams = params;
        mDevicePolicies = params.getDevicePolicies();
        mDisplayManager = displayManager;
        mDisplayManagerInternal = LocalServices.getService(DisplayManagerInternal.class);
        if (inputController == null) {
            mInputController = new InputController(
                    context.getMainThreadHandler(),
                    context.getSystemService(WindowManager.class));
        } else {
            mInputController = inputController;
        }
        mSensorController = new SensorController(this, mDeviceId,
                mParams.getVirtualSensorCallback(), mParams.getVirtualSensorConfigs());
        mCameraAccessController = cameraAccessController;
        if (mCameraAccessController != null) {
            mCameraAccessController.startObservingIfNeeded();
        }
        if (!Flags.streamPermissions()) {
            mPermissionDialogComponent = getPermissionDialogComponent();
        } else {
            mPermissionDialogComponent = null;
        }
        mVirtualCameraController = virtualCameraController;
        try {
            token.linkToDeath(this, 0);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        mVirtualDeviceLog.logCreated(deviceId, mOwnerUid);

        if (Flags.vdmPublicApis()) {
            mPublicVirtualDeviceObject = new VirtualDevice(
                    this, getDeviceId(), getPersistentDeviceId(), mParams.getName(),
                    getDisplayName());
        } else {
            mPublicVirtualDeviceObject = new VirtualDevice(
                    this, getDeviceId(), getPersistentDeviceId(), mParams.getName());
        }

        if (Flags.dynamicPolicy()) {
            mActivityPolicyExemptions = new ArraySet<>(
                    mParams.getDevicePolicy(POLICY_TYPE_ACTIVITY) == DEVICE_POLICY_DEFAULT
                            ? mParams.getBlockedActivities()
                            : mParams.getAllowedActivities());
        } else {
            mActivityPolicyExemptions =
                    mParams.getDefaultActivityPolicy() == ACTIVITY_POLICY_DEFAULT_ALLOWED
                            ? mParams.getBlockedActivities()
                            : mParams.getAllowedActivities();
        }

        int flags = DEFAULT_VIRTUAL_DISPLAY_FLAGS;
        if (!Flags.consistentDisplayFlags()) {
            flags |= DEFAULT_VIRTUAL_DISPLAY_FLAGS_PRE_VIC;
        }
        if (mParams.getLockState() == VirtualDeviceParams.LOCK_STATE_ALWAYS_UNLOCKED) {
            flags |= DisplayManager.VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED;
        }
        mBaseVirtualDisplayFlags = flags;

        if (Flags.vdmCustomIme() && mParams.getInputMethodComponent() != null) {
            final String imeId = mParams.getInputMethodComponent().flattenToShortString();
            Slog.d(TAG, "Setting custom input method " + imeId + " as default for virtual device "
                    + deviceId);
            InputMethodManagerInternal.get().setVirtualDeviceInputMethodForAllUsers(
                    mDeviceId, imeId);
        }
    }

    @VisibleForTesting
    SensorController getSensorControllerForTest() {
        return mSensorController;
    }

    static String createPersistentDeviceId(int associationId) {
        return PERSISTENT_ID_PREFIX_CDM_ASSOCIATION + associationId;
    }

    /**
     * Returns the flags that should be added to any virtual displays created on this virtual
     * device.
     */
    int getBaseVirtualDisplayFlags() {
        return mBaseVirtualDisplayFlags;
    }

    /** Returns the camera access controller of this device. */
    CameraAccessController getCameraAccessController() {
        return mCameraAccessController;
    }

    /** Returns the device display name. */
    CharSequence getDisplayName() {
        return mAssociationInfo.getDisplayName();
    }

    /** Returns the public representation of the device. */
    VirtualDevice getPublicVirtualDeviceObject() {
        return mPublicVirtualDeviceObject;
    }

    /** Returns the locale of the device. */
    LocaleList getDeviceLocaleList() {
        synchronized (mVirtualDeviceLock) {
            return mLocaleList;
        }
    }

    @Override  // Binder call
    public @VirtualDeviceParams.DevicePolicy int getDevicePolicy(
            @VirtualDeviceParams.PolicyType int policyType) {
        if (Flags.dynamicPolicy()) {
            synchronized (mVirtualDeviceLock) {
                return mDevicePolicies.get(policyType, DEVICE_POLICY_DEFAULT);
            }
        } else {
            return mParams.getDevicePolicy(policyType);
        }
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

    /** Returns the unique device ID of this device. */
    @Override // Binder call
    public @Nullable String getPersistentDeviceId() {
        return mPersistentDeviceId;
    }

    @Override // Binder call
    public int getAssociationId() {
        return mAssociationInfo.getId();
    }

    @Override // Binder call
    public void launchPendingIntent(int displayId, PendingIntent pendingIntent,
            ResultReceiver resultReceiver) {
        Objects.requireNonNull(pendingIntent);
        synchronized (mVirtualDeviceLock) {
            if (!mVirtualDisplays.contains(displayId)) {
                throw new SecurityException("Display ID " + displayId
                        + " not found for this virtual device");
            }
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

    @Override // Binder call
    @EnforcePermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public void addActivityPolicyExemption(@NonNull ComponentName componentName) {
        super.addActivityPolicyExemption_enforcePermission();
        synchronized (mVirtualDeviceLock) {
            if (mActivityPolicyExemptions.add(componentName)) {
                for (int i = 0; i < mVirtualDisplays.size(); i++) {
                    mVirtualDisplays.valueAt(i).getWindowPolicyController()
                            .addActivityPolicyExemption(componentName);
                }
            }
        }
    }

    @Override // Binder call
    @EnforcePermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public void removeActivityPolicyExemption(@NonNull ComponentName componentName) {
        super.removeActivityPolicyExemption_enforcePermission();
        synchronized (mVirtualDeviceLock) {
            if (mActivityPolicyExemptions.remove(componentName)) {
                for (int i = 0; i < mVirtualDisplays.size(); i++) {
                    mVirtualDisplays.valueAt(i).getWindowPolicyController()
                            .removeActivityPolicyExemption(componentName);
                }
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
        // Remove about-to-be-closed virtual device from the service before butchering it.
        if (!mService.removeVirtualDevice(mDeviceId)) {
            // Device is already closed.
            return;
        }

        mVirtualDeviceLog.logClosed(mDeviceId, mOwnerUid);

        final long ident = Binder.clearCallingIdentity();
        try {
            VirtualDisplayWrapper[] virtualDisplaysToBeReleased;
            synchronized (mVirtualDeviceLock) {
                if (mVirtualAudioController != null) {
                    mVirtualAudioController.stopListening();
                    mVirtualAudioController = null;
                }
                mLocaleList = null;
                virtualDisplaysToBeReleased = new VirtualDisplayWrapper[mVirtualDisplays.size()];
                for (int i = 0; i < mVirtualDisplays.size(); i++) {
                    virtualDisplaysToBeReleased[i] = mVirtualDisplays.valueAt(i);
                }
                mVirtualDisplays.clear();
            }
            // Destroy the display outside locked section.
            for (VirtualDisplayWrapper virtualDisplayWrapper : virtualDisplaysToBeReleased) {
                mDisplayManager.releaseVirtualDisplay(virtualDisplayWrapper.getToken());
                // The releaseVirtualDisplay call above won't trigger
                // VirtualDeviceImpl.onVirtualDisplayRemoved callback because we already removed the
                // virtual device from the service - we release the other display-tied resources
                // here with the guarantee it will be done exactly once.
                releaseOwnedVirtualDisplayResources(virtualDisplayWrapper);
            }

            mAppToken.unlinkToDeath(this, 0);
            if (mCameraAccessController != null) {
                mCameraAccessController.stopObservingIfNeeded();
            }

            // Clear any previously set custom IME components.
            if (Flags.vdmCustomIme() && mParams.getInputMethodComponent() != null) {
                InputMethodManagerInternal.get().setVirtualDeviceInputMethodForAllUsers(
                        mDeviceId, null);
            }

            mInputController.close();
            mSensorController.close();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        if (mVirtualCameraController != null) {
            mVirtualCameraController.close();
        }
    }

    @Override
    public void binderDied() {
        close();
    }

    @Override
    @RequiresPermission(android.Manifest.permission.CAMERA_INJECT_EXTERNAL_CAMERA)
    public void onRunningAppsChanged(ArraySet<Integer> runningUids) {
        if (mCameraAccessController != null) {
            mCameraAccessController.blockCameraAccessIfNeeded(runningUids);
        }
        mRunningAppsChangedCallback.accept(runningUids);
    }

    @VisibleForTesting
    VirtualAudioController getVirtualAudioControllerForTesting() {
        return mVirtualAudioController;
    }

    @Override // Binder call
    @EnforcePermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public void onAudioSessionStarting(int displayId,
            @NonNull IAudioRoutingCallback routingCallback,
            @Nullable IAudioConfigChangedCallback configChangedCallback) {
        super.onAudioSessionStarting_enforcePermission();
        synchronized (mVirtualDeviceLock) {
            if (!mVirtualDisplays.contains(displayId)) {
                throw new SecurityException(
                        "Cannot start audio session for a display not associated with this virtual "
                                + "device");
            }

            if (mVirtualAudioController == null) {
                mVirtualAudioController = new VirtualAudioController(mContext);
                GenericWindowPolicyController gwpc = mVirtualDisplays.get(
                        displayId).getWindowPolicyController();
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
    public void setDevicePolicy(@VirtualDeviceParams.DynamicPolicyType int policyType,
            @VirtualDeviceParams.DevicePolicy int devicePolicy) {
        super.setDevicePolicy_enforcePermission();
        if (!Flags.dynamicPolicy()) {
            return;
        }

        switch (policyType) {
            case POLICY_TYPE_RECENTS:
                synchronized (mVirtualDeviceLock) {
                    mDevicePolicies.put(policyType, devicePolicy);
                    for (int i = 0; i < mVirtualDisplays.size(); i++) {
                        mVirtualDisplays.valueAt(i).getWindowPolicyController()
                                .setShowInHostDeviceRecents(devicePolicy == DEVICE_POLICY_DEFAULT);
                    }
                }
                break;
            case POLICY_TYPE_ACTIVITY:
                synchronized (mVirtualDeviceLock) {
                    mDevicePolicies.put(policyType, devicePolicy);
                    for (int i = 0; i < mVirtualDisplays.size(); i++) {
                        mVirtualDisplays.valueAt(i).getWindowPolicyController()
                                .setActivityLaunchDefaultAllowed(
                                        devicePolicy == DEVICE_POLICY_DEFAULT);
                    }
                }
                break;
            case POLICY_TYPE_CLIPBOARD:
                if (Flags.crossDeviceClipboard()) {
                    synchronized (mVirtualDeviceLock) {
                        mDevicePolicies.put(policyType, devicePolicy);
                    }
                }
                break;
            default:
                throw new IllegalArgumentException("Device policy " + policyType
                        + " cannot be changed at runtime. ");
        }
    }

    @Override // Binder call
    @EnforcePermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public void createVirtualDpad(VirtualDpadConfig config, @NonNull IBinder deviceToken) {
        super.createVirtualDpad_enforcePermission();
        Objects.requireNonNull(config);
        checkVirtualInputDeviceDisplayIdAssociation(config.getAssociatedDisplayId());
        final long ident = Binder.clearCallingIdentity();
        try {
            mInputController.createDpad(config.getInputDeviceName(), config.getVendorId(),
                    config.getProductId(), deviceToken,
                    getTargetDisplayIdForInput(config.getAssociatedDisplayId()));
        } catch (InputController.DeviceCreationException e) {
            throw new IllegalArgumentException(e);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    @EnforcePermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public void createVirtualKeyboard(VirtualKeyboardConfig config, @NonNull IBinder deviceToken) {
        super.createVirtualKeyboard_enforcePermission();
        Objects.requireNonNull(config);
        checkVirtualInputDeviceDisplayIdAssociation(config.getAssociatedDisplayId());
        final long ident = Binder.clearCallingIdentity();
        try {
            mInputController.createKeyboard(config.getInputDeviceName(), config.getVendorId(),
                    config.getProductId(), deviceToken,
                    getTargetDisplayIdForInput(config.getAssociatedDisplayId()),
                    config.getLanguageTag(), config.getLayoutType());
            synchronized (mVirtualDeviceLock) {
                mLocaleList = LocaleList.forLanguageTags(config.getLanguageTag());
            }
        } catch (InputController.DeviceCreationException e) {
            throw new IllegalArgumentException(e);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    @EnforcePermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public void createVirtualMouse(VirtualMouseConfig config, @NonNull IBinder deviceToken) {
        super.createVirtualMouse_enforcePermission();
        Objects.requireNonNull(config);
        checkVirtualInputDeviceDisplayIdAssociation(config.getAssociatedDisplayId());
        final long ident = Binder.clearCallingIdentity();
        try {
            mInputController.createMouse(config.getInputDeviceName(), config.getVendorId(),
                    config.getProductId(), deviceToken, config.getAssociatedDisplayId());
        } catch (InputController.DeviceCreationException e) {
            throw new IllegalArgumentException(e);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    @EnforcePermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public void createVirtualTouchscreen(VirtualTouchscreenConfig config,
            @NonNull IBinder deviceToken) {
        super.createVirtualTouchscreen_enforcePermission();
        Objects.requireNonNull(config);
        checkVirtualInputDeviceDisplayIdAssociation(config.getAssociatedDisplayId());
        final long ident = Binder.clearCallingIdentity();
        try {
            mInputController.createTouchscreen(config.getInputDeviceName(), config.getVendorId(),
                    config.getProductId(), deviceToken, config.getAssociatedDisplayId(),
                    config.getHeight(), config.getWidth());
        } catch (InputController.DeviceCreationException e) {
            throw new IllegalArgumentException(e);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    @EnforcePermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public void createVirtualNavigationTouchpad(VirtualNavigationTouchpadConfig config,
            @NonNull IBinder deviceToken) {
        super.createVirtualNavigationTouchpad_enforcePermission();
        Objects.requireNonNull(config);
        checkVirtualInputDeviceDisplayIdAssociation(config.getAssociatedDisplayId());
        final long ident = Binder.clearCallingIdentity();
        try {
            mInputController.createNavigationTouchpad(
                    config.getInputDeviceName(), config.getVendorId(),
                    config.getProductId(), deviceToken,
                    getTargetDisplayIdForInput(config.getAssociatedDisplayId()),
                    config.getHeight(), config.getWidth());
        } catch (InputController.DeviceCreationException e) {
            throw new IllegalArgumentException(e);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    @EnforcePermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public void createVirtualStylus(@NonNull VirtualStylusConfig config,
            @NonNull IBinder deviceToken) {
        super.createVirtualStylus_enforcePermission();
        Objects.requireNonNull(config);
        Objects.requireNonNull(deviceToken);
        checkVirtualInputDeviceDisplayIdAssociation(config.getAssociatedDisplayId());
        final long ident = Binder.clearCallingIdentity();
        try {
            mInputController.createStylus(config.getInputDeviceName(), config.getVendorId(),
                    config.getProductId(), deviceToken, config.getAssociatedDisplayId(),
                    config.getHeight(), config.getWidth());
        } catch (InputController.DeviceCreationException e) {
            throw new IllegalArgumentException(e);
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
    public boolean sendStylusMotionEvent(@NonNull IBinder token,
            @NonNull VirtualStylusMotionEvent event) {
        super.sendStylusMotionEvent_enforcePermission();
        Objects.requireNonNull(token);
        Objects.requireNonNull(event);
        final long ident = Binder.clearCallingIdentity();
        try {
            return mInputController.sendStylusMotionEvent(token, event);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    @EnforcePermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public boolean sendStylusButtonEvent(@NonNull IBinder token,
            @NonNull VirtualStylusButtonEvent event) {
        super.sendStylusButtonEvent_enforcePermission();
        Objects.requireNonNull(token);
        Objects.requireNonNull(event);
        final long ident = Binder.clearCallingIdentity();
        try {
            return mInputController.sendStylusButtonEvent(token, event);
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
            }
            final int[] displayIds = getDisplayIds();
            for (int i = 0; i < displayIds.length; ++i) {
                mInputController.setShowPointerIcon(showPointerIcon, displayIds[i]);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    @EnforcePermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public void setDisplayImePolicy(int displayId, @WindowManager.DisplayImePolicy int policy) {
        super.setDisplayImePolicy_enforcePermission();
        synchronized (mVirtualDeviceLock) {
            if (!mVirtualDisplays.contains(displayId)) {
                throw new SecurityException("Display ID " + displayId
                        + " not found for this virtual device");
            }
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            mInputController.setDisplayImePolicy(displayId, policy);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    @EnforcePermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    @Nullable
    public List<VirtualSensor> getVirtualSensorList() {
        super.getVirtualSensorList_enforcePermission();
        return mSensorController.getSensorList();
    }

    @Nullable
    VirtualSensor getVirtualSensorByHandle(int handle) {
        return mSensorController.getSensorByHandle(handle);
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

    @Override // Binder call
    @EnforcePermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public void registerVirtualCamera(@NonNull VirtualCameraConfig cameraConfig)
            throws RemoteException {
        super.registerVirtualCamera_enforcePermission();
        Objects.requireNonNull(cameraConfig);
        if (mVirtualCameraController == null) {
            throw new UnsupportedOperationException("Virtual camera controller is not available");
        }
        mVirtualCameraController.registerCamera(cameraConfig);
    }

    @Override // Binder call
    @EnforcePermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public void unregisterVirtualCamera(@NonNull VirtualCameraConfig cameraConfig)
            throws RemoteException {
        super.unregisterVirtualCamera_enforcePermission();
        Objects.requireNonNull(cameraConfig);
        if (mVirtualCameraController == null) {
            throw new UnsupportedOperationException("Virtual camera controller is not available");
        }
        mVirtualCameraController.unregisterCamera(cameraConfig);
    }

    @Override // Binder call
    @EnforcePermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public int getVirtualCameraId(@NonNull VirtualCameraConfig cameraConfig)
            throws RemoteException {
        super.getVirtualCameraId_enforcePermission();
        Objects.requireNonNull(cameraConfig);
        if (mVirtualCameraController == null) {
            throw new UnsupportedOperationException("Virtual camera controller is not available");
        }
        return mVirtualCameraController.getCameraId(cameraConfig);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
        String indent = "    ";
        fout.println("  VirtualDevice: ");
        fout.println(indent + "mDeviceId: " + mDeviceId);
        fout.println(indent + "mAssociationId: " + mAssociationInfo.getId());
        fout.println(indent + "mOwnerPackageName: " + mOwnerPackageName);
        fout.println(indent + "mParams: ");
        mParams.dump(fout, indent + indent);
        fout.println(indent + "mVirtualDisplayIds: ");
        synchronized (mVirtualDeviceLock) {
            fout.println("    mDevicePolicies: " + mDevicePolicies);
            for (int i = 0; i < mVirtualDisplays.size(); i++) {
                fout.println(indent + "  " + mVirtualDisplays.keyAt(i));
            }
            fout.println(indent + "mDefaultShowPointerIcon: " + mDefaultShowPointerIcon);
        }
        mInputController.dump(fout);
        mSensorController.dump(fout);
        if (mVirtualCameraController != null) {
            mVirtualCameraController.dump(fout, indent);
        }
    }

    // For display mirroring, we want to dispatch all key events to the source (default) display,
    // as the virtual display doesn't have any focused windows. Hence, call this for
    // associating any input device to the source display if the input device emits any key events.
    private int getTargetDisplayIdForInput(int displayId) {
        if (!Flags.interactiveScreenMirror()) {
            return displayId;
        }

        DisplayManagerInternal displayManager = LocalServices.getService(
                DisplayManagerInternal.class);
        int mirroredDisplayId = displayManager.getDisplayIdToMirror(displayId);
        return mirroredDisplayId == Display.INVALID_DISPLAY ? displayId : mirroredDisplayId;
    }

    @GuardedBy("mVirtualDeviceLock")
    private GenericWindowPolicyController createWindowPolicyControllerLocked(
            @NonNull Set<String> displayCategories) {
        final boolean activityLaunchAllowedByDefault =
                Flags.dynamicPolicy()
                    ? getDevicePolicy(POLICY_TYPE_ACTIVITY) == DEVICE_POLICY_DEFAULT
                    : mParams.getDefaultActivityPolicy() == ACTIVITY_POLICY_DEFAULT_ALLOWED;
        final boolean crossTaskNavigationAllowedByDefault =
                mParams.getDefaultNavigationPolicy() == NAVIGATION_POLICY_DEFAULT_ALLOWED;
        final boolean showTasksInHostDeviceRecents =
                getDevicePolicy(POLICY_TYPE_RECENTS) == DEVICE_POLICY_DEFAULT;
        final ComponentName homeComponent =
                Flags.vdmCustomHome() ? mParams.getHomeComponent() : null;

        final GenericWindowPolicyController gwpc = new GenericWindowPolicyController(
                FLAG_SECURE,
                SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS,
                getAllowedUserHandles(),
                activityLaunchAllowedByDefault,
                mActivityPolicyExemptions,
                crossTaskNavigationAllowedByDefault,
                /* crossTaskNavigationExemptions= */crossTaskNavigationAllowedByDefault
                        ? mParams.getBlockedCrossTaskNavigations()
                        : mParams.getAllowedCrossTaskNavigations(),
                mPermissionDialogComponent,
                createListenerAdapter(),
                this::onEnteringPipBlocked,
                this::onActivityBlocked,
                this::onSecureWindowShown,
                this::shouldInterceptIntent,
                displayCategories,
                showTasksInHostDeviceRecents,
                homeComponent);
        gwpc.registerRunningAppsChangedListener(/* listener= */ this);
        return gwpc;
    }

    private ComponentName getPermissionDialogComponent() {
        Intent intent = new Intent(ACTION_REQUEST_PERMISSIONS);
        PackageManager packageManager = mContext.getPackageManager();
        intent.setPackage(packageManager.getPermissionControllerPackageName());
        return intent.resolveActivity(packageManager);
    }

    int createVirtualDisplay(@NonNull VirtualDisplayConfig virtualDisplayConfig,
            @NonNull IVirtualDisplayCallback callback, String packageName) {
        GenericWindowPolicyController gwpc;
        synchronized (mVirtualDeviceLock) {
            gwpc = createWindowPolicyControllerLocked(virtualDisplayConfig.getDisplayCategories());
        }
        int displayId;
        displayId = mDisplayManagerInternal.createVirtualDisplay(virtualDisplayConfig, callback,
                this, gwpc, packageName);
        gwpc.setDisplayId(displayId, /* isMirrorDisplay= */ Flags.interactiveScreenMirror()
                && mDisplayManagerInternal.getDisplayIdToMirror(displayId)
                    != Display.INVALID_DISPLAY);

        boolean showPointer;
        synchronized (mVirtualDeviceLock) {
            if (mVirtualDisplays.contains(displayId)) {
                gwpc.unregisterRunningAppsChangedListener(this);
                throw new IllegalStateException(
                        "Virtual device already has a virtual display with ID " + displayId);
            }

            PowerManager.WakeLock wakeLock = createAndAcquireWakeLockForDisplay(displayId);
            mVirtualDisplays.put(displayId, new VirtualDisplayWrapper(callback, gwpc, wakeLock));
            showPointer = mDefaultShowPointerIcon;
        }

        final long token = Binder.clearCallingIdentity();
        try {
            mInputController.setShowPointerIcon(showPointer, displayId);
            mInputController.setMousePointerAccelerationEnabled(false, displayId);
            mInputController.setDisplayEligibilityForPointerCapture(/* isEligible= */ false,
                    displayId);
            // WM throws a SecurityException if the display is untrusted.
            if ((mDisplayManagerInternal.getDisplayInfo(displayId).flags & Display.FLAG_TRUSTED)
                    == Display.FLAG_TRUSTED) {
                mInputController.setDisplayImePolicy(displayId,
                        WindowManager.DISPLAY_IME_POLICY_LOCAL);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        return displayId;
    }

    private PowerManager.WakeLock createAndAcquireWakeLockForDisplay(int displayId) {
        final long token = Binder.clearCallingIdentity();
        try {
            PowerManager powerManager = mContext.getSystemService(PowerManager.class);
            PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                    TAG + ":" + displayId, displayId);
            wakeLock.acquire();
            return wakeLock;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @RequiresPermission(android.Manifest.permission.INTERACT_ACROSS_USERS)
    private void onActivityBlocked(int displayId, ActivityInfo activityInfo) {
        Intent intent = BlockedAppStreamingActivity.createIntent(
                activityInfo, mAssociationInfo.getDisplayName());
        mContext.startActivityAsUser(
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK),
                ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle(),
                mContext.getUser());
    }

    private void onSecureWindowShown(int displayId, int uid) {
        synchronized (mVirtualDeviceLock) {
            if (!mVirtualDisplays.contains(displayId)) {
                return;
            }
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
        final long token = Binder.clearCallingIdentity();
        try {
            DevicePolicyManager dpm = mContext.getSystemService(DevicePolicyManager.class);
            UserManager userManager = mContext.getSystemService(UserManager.class);
            for (UserHandle profile : userManager.getAllProfiles()) {
                int nearbyAppStreamingPolicy = dpm.getNearbyAppStreamingPolicy(
                        profile.getIdentifier());
                if (nearbyAppStreamingPolicy == NEARBY_STREAMING_ENABLED
                        || nearbyAppStreamingPolicy == NEARBY_STREAMING_NOT_CONTROLLED_BY_POLICY) {
                    result.add(profile);
                } else if (nearbyAppStreamingPolicy == NEARBY_STREAMING_SAME_MANAGED_ACCOUNT_ONLY) {
                    if (mParams.getUsersWithMatchingAccounts().contains(profile)) {
                        result.add(profile);
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return result;
    }


    void onVirtualDisplayRemoved(int displayId) {
        /* This is callback invoked by VirtualDeviceManagerService when VirtualDisplay was released
         * by DisplayManager (most probably caused by someone calling VirtualDisplay.close()).
         * At this point, the display is already released, but we still need to release the
         * corresponding wakeLock and unregister the RunningAppsChangedListener from corresponding
         * WindowPolicyController.
         *
         * Note that when the display is destroyed during VirtualDeviceImpl.close() call,
         * this callback won't be invoked because the display is removed from
         * VirtualDeviceManagerService before any resources are released.
         */
        VirtualDisplayWrapper virtualDisplayWrapper;
        synchronized (mVirtualDeviceLock) {
            virtualDisplayWrapper = mVirtualDisplays.removeReturnOld(displayId);
        }

        if (virtualDisplayWrapper == null) {
            Slog.w(TAG, "Virtual device " + mDeviceId + " doesn't have a virtual display with ID "
                    + displayId);
            return;
        }

        final long ident = Binder.clearCallingIdentity();
        try {
            releaseOwnedVirtualDisplayResources(virtualDisplayWrapper);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @SuppressWarnings("AndroidFrameworkRequiresPermission")
    private void checkVirtualInputDeviceDisplayIdAssociation(int displayId) {
        if (mContext.checkCallingPermission(android.Manifest.permission.INJECT_EVENTS)
                    == PackageManager.PERMISSION_GRANTED) {
            // The INJECT_EVENTS permission allows for injecting input to any window / display.
            return;
        }
        synchronized (mVirtualDeviceLock) {
            if (!mVirtualDisplays.contains(displayId)) {
                throw new SecurityException(
                        "Cannot create a virtual input device for display " + displayId
                                + " which not associated with this virtual device");
            }
        }
    }

    /**
     * Release resources tied to virtual display owned by this VirtualDevice instance.
     *
     * Note that this method won't release the virtual display itself.
     *
     * @param virtualDisplayWrapper - VirtualDisplayWrapper to release resources for.
     */
    private void releaseOwnedVirtualDisplayResources(VirtualDisplayWrapper virtualDisplayWrapper) {
        virtualDisplayWrapper.getWakeLock().release();
        virtualDisplayWrapper.getWindowPolicyController().unregisterRunningAppsChangedListener(
                this);
    }

    int getOwnerUid() {
        return mOwnerUid;
    }

    @Override  // Binder call
    public int[] getDisplayIds() {
        synchronized (mVirtualDeviceLock) {
            final int size = mVirtualDisplays.size();
            int[] displayIds = new int[size];
            for (int i = 0; i < size; i++) {
                displayIds[i] = mVirtualDisplays.keyAt(i);
            }
            return displayIds;
        }
    }

    @VisibleForTesting
    GenericWindowPolicyController getDisplayWindowPolicyControllerForTest(int displayId) {
        VirtualDisplayWrapper virtualDisplayWrapper;
        synchronized (mVirtualDeviceLock) {
            virtualDisplayWrapper = mVirtualDisplays.get(displayId);
        }
        return virtualDisplayWrapper != null ? virtualDisplayWrapper.getWindowPolicyController()
                : null;
    }

    /**
     * Returns true if an app with the given {@code uid} is currently running on this virtual
     * device.
     */
    boolean isAppRunningOnVirtualDevice(int uid) {
        synchronized (mVirtualDeviceLock) {
            for (int i = 0; i < mVirtualDisplays.size(); i++) {
                if (mVirtualDisplays.valueAt(i).getWindowPolicyController().containsUid(uid)) {
                    return true;
                }
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
        IntArray displayIdsForUid = getDisplayIdsWhereUidIsRunning(uid);
        if (displayIdsForUid.size() == 0) {
            return;
        }
        DisplayManager displayManager = mContext.getSystemService(DisplayManager.class);
        for (int i = 0; i < displayIdsForUid.size(); i++) {
            Display display = displayManager.getDisplay(displayIdsForUid.get(i));
            if (display != null && display.isValid()) {
                Toast.makeText(mContext.createDisplayContext(display), looper, text,
                        duration).show();
            }
        }
    }

    private IntArray getDisplayIdsWhereUidIsRunning(int uid) {
        IntArray displayIdsForUid = new IntArray();
        synchronized (mVirtualDeviceLock) {
            for (int i = 0; i < mVirtualDisplays.size(); i++) {
                if (mVirtualDisplays.valueAt(i).getWindowPolicyController().containsUid(uid)) {
                    displayIdsForUid.add(mVirtualDisplays.keyAt(i));
                }
            }
        }
        return displayIdsForUid;
    }

    boolean isDisplayOwnedByVirtualDevice(int displayId) {
        synchronized (mVirtualDeviceLock) {
            return mVirtualDisplays.contains(displayId);
        }
    }

    void onEnteringPipBlocked(int uid) {
        // Do nothing. ActivityRecord#checkEnterPictureInPictureState logs that the display does not
        // support PiP.
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

    /** Data class wrapping resources tied to single virtual display. */
    private static final class VirtualDisplayWrapper {
        private final IVirtualDisplayCallback mToken;
        private final GenericWindowPolicyController mWindowPolicyController;
        private final PowerManager.WakeLock mWakeLock;

        VirtualDisplayWrapper(@NonNull IVirtualDisplayCallback token,
                @NonNull GenericWindowPolicyController windowPolicyController,
                @NonNull PowerManager.WakeLock wakeLock) {
            mToken = Objects.requireNonNull(token);
            mWindowPolicyController = Objects.requireNonNull(windowPolicyController);
            mWakeLock = Objects.requireNonNull(wakeLock);
        }

        GenericWindowPolicyController getWindowPolicyController() {
            return mWindowPolicyController;
        }

        PowerManager.WakeLock getWakeLock() {
            return mWakeLock;
        }

        IVirtualDisplayCallback getToken() {
            return mToken;
        }
    }
}
