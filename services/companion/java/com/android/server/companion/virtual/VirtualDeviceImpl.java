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

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.StringRes;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.companion.AssociationInfo;
import android.companion.virtual.IVirtualDevice;
import android.companion.virtual.IVirtualDeviceActivityListener;
import android.companion.virtual.VirtualDeviceManager.ActivityListener;
import android.companion.virtual.VirtualDeviceParams;
import android.companion.virtual.audio.IAudioSessionCallback;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Point;
import android.graphics.PointF;
import android.hardware.display.DisplayManager;
import android.hardware.input.VirtualKeyEvent;
import android.hardware.input.VirtualMouseButtonEvent;
import android.hardware.input.VirtualMouseRelativeEvent;
import android.hardware.input.VirtualMouseScrollEvent;
import android.hardware.input.VirtualTouchEvent;
import android.os.Binder;
import android.os.IBinder;
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
import android.widget.Toast;
import android.window.DisplayWindowPolicyController;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.BlockedAppStreamingActivity;
import com.android.server.companion.virtual.audio.VirtualAudioController;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Map;
import java.util.Set;


final class VirtualDeviceImpl extends IVirtualDevice.Stub
        implements IBinder.DeathRecipient {

    private static final String TAG = "VirtualDeviceImpl";
    private final Object mVirtualDeviceLock = new Object();

    private final Context mContext;
    private final AssociationInfo mAssociationInfo;
    private final PendingTrampolineCallback mPendingTrampolineCallback;
    private final int mOwnerUid;
    private final InputController mInputController;
    private VirtualAudioController mVirtualAudioController;
    @VisibleForTesting
    final Set<Integer> mVirtualDisplayIds = new ArraySet<>();
    private final OnDeviceCloseListener mListener;
    private final IBinder mAppToken;
    private final VirtualDeviceParams mParams;
    private final Map<Integer, PowerManager.WakeLock> mPerDisplayWakelocks = new ArrayMap<>();
    private final IVirtualDeviceActivityListener mActivityListener;
    // The default setting for showing the pointer on new displays.
    @GuardedBy("mVirtualDeviceLock")
    private boolean mDefaultShowPointerIcon = true;

    private ActivityListener createListenerAdapter(int displayId) {
        return new ActivityListener() {

            @Override
            public void onTopActivityChanged(int unusedDisplayId, ComponentName topActivity) {
                try {
                    mActivityListener.onTopActivityChanged(displayId, topActivity);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Unable to call mActivityListener", e);
                }
            }

            @Override
            public void onDisplayEmpty(int unusedDisplayId) {
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

    VirtualDeviceImpl(Context context, AssociationInfo associationInfo,
            IBinder token, int ownerUid, OnDeviceCloseListener listener,
            PendingTrampolineCallback pendingTrampolineCallback,
            IVirtualDeviceActivityListener activityListener,
            VirtualDeviceParams params) {
        this(context, associationInfo, token, ownerUid, /* inputController= */ null, listener,
                pendingTrampolineCallback, activityListener, params);
    }

    @VisibleForTesting
    VirtualDeviceImpl(Context context, AssociationInfo associationInfo, IBinder token,
            int ownerUid, InputController inputController, OnDeviceCloseListener listener,
            PendingTrampolineCallback pendingTrampolineCallback,
            IVirtualDeviceActivityListener activityListener, VirtualDeviceParams params) {
        UserHandle ownerUserHandle = UserHandle.getUserHandleForUid(ownerUid);
        mContext = context.createContextAsUser(ownerUserHandle, 0);
        mAssociationInfo = associationInfo;
        mPendingTrampolineCallback = pendingTrampolineCallback;
        mActivityListener = activityListener;
        mOwnerUid = ownerUid;
        mAppToken = token;
        mParams = params;
        if (inputController == null) {
            mInputController = new InputController(mVirtualDeviceLock);
        } else {
            mInputController = inputController;
        }
        mListener = listener;
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

    /** Returns the device display name. */
    CharSequence getDisplayName() {
        return mAssociationInfo.getDisplayName();
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
                resultReceiver.send(Activity.RESULT_OK, null);
            } catch (PendingIntent.CanceledException e) {
                Slog.w(TAG, "Pending intent canceled", e);
                resultReceiver.send(Activity.RESULT_CANCELED, null);
            }
        } else {
            PendingTrampoline pendingTrampoline = new PendingTrampoline(pendingIntent,
                    resultReceiver, displayId);
            mPendingTrampolineCallback.startWaitingForPendingTrampoline(pendingTrampoline);
            try {
                sendPendingIntent(displayId, pendingIntent);
            } catch (PendingIntent.CanceledException e) {
                Slog.w(TAG, "Pending intent canceled", e);
                resultReceiver.send(Activity.RESULT_CANCELED, null);
                mPendingTrampolineCallback.stopWaitingForPendingTrampoline(pendingTrampoline);
            }
        }
    }

    private void sendPendingIntent(int displayId, PendingIntent pendingIntent)
            throws PendingIntent.CanceledException {
        pendingIntent.send(
                mContext,
                /* code= */ 0,
                /* intent= */ null,
                /* onFinished= */ null,
                /* handler= */ null,
                /* requiredPermission= */ null,
                ActivityOptions.makeBasic()
                        .setLaunchDisplayId(displayId)
                        .toBundle());
    }

    @Override // Binder call
    public void close() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CREATE_VIRTUAL_DEVICE,
                "Permission required to close the virtual device");

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
        }
        mListener.onClose(mAssociationInfo.getId());
        mAppToken.unlinkToDeath(this, 0);

        final long token = Binder.clearCallingIdentity();
        try {
            mInputController.close();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void binderDied() {
        close();
    }

    @VisibleForTesting
    VirtualAudioController getVirtualAudioControllerForTesting() {
        return mVirtualAudioController;
    }

    @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    @Override // Binder call
    public void onAudioSessionStarting(int displayId, IAudioSessionCallback callback) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CREATE_VIRTUAL_DEVICE,
                "Permission required to start audio session");
        synchronized (mVirtualDeviceLock) {
            if (!mVirtualDisplayIds.contains(displayId)) {
                throw new SecurityException(
                        "Cannot start audio session for a display not associated with this virtual "
                                + "device");
            }

            if (mVirtualAudioController == null) {
                mVirtualAudioController = new VirtualAudioController(mContext);
                GenericWindowPolicyController gwpc = mWindowPolicyControllers.get(displayId);
                mVirtualAudioController.startListening(gwpc, callback);
            }
        }
    }

    @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    @Override // Binder call
    public void onAudioSessionEnded() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CREATE_VIRTUAL_DEVICE,
                "Permission required to stop audio session");
        synchronized (mVirtualDeviceLock) {
            if (mVirtualAudioController != null) {
                mVirtualAudioController.stopListening();
                mVirtualAudioController = null;
            }
        }
    }

    @Override // Binder call
    public void createVirtualKeyboard(
            int displayId,
            @NonNull String deviceName,
            int vendorId,
            int productId,
            @NonNull IBinder deviceToken) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CREATE_VIRTUAL_DEVICE,
                "Permission required to create a virtual keyboard");
        synchronized (mVirtualDeviceLock) {
            if (!mVirtualDisplayIds.contains(displayId)) {
                throw new SecurityException(
                        "Cannot create a virtual keyboard for a display not associated with "
                                + "this virtual device");
            }
        }
        final long token = Binder.clearCallingIdentity();
        try {
            mInputController.createKeyboard(deviceName, vendorId, productId, deviceToken,
                    displayId);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override // Binder call
    public void createVirtualMouse(
            int displayId,
            @NonNull String deviceName,
            int vendorId,
            int productId,
            @NonNull IBinder deviceToken) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CREATE_VIRTUAL_DEVICE,
                "Permission required to create a virtual mouse");
        synchronized (mVirtualDeviceLock) {
            if (!mVirtualDisplayIds.contains(displayId)) {
                throw new SecurityException(
                        "Cannot create a virtual mouse for a display not associated with this "
                                + "virtual device");
            }
        }
        final long token = Binder.clearCallingIdentity();
        try {
            mInputController.createMouse(deviceName, vendorId, productId, deviceToken, displayId);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override // Binder call
    public void createVirtualTouchscreen(
            int displayId,
            @NonNull String deviceName,
            int vendorId,
            int productId,
            @NonNull IBinder deviceToken,
            @NonNull Point screenSize) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CREATE_VIRTUAL_DEVICE,
                "Permission required to create a virtual touchscreen");
        synchronized (mVirtualDeviceLock) {
            if (!mVirtualDisplayIds.contains(displayId)) {
                throw new SecurityException(
                        "Cannot create a virtual touchscreen for a display not associated with "
                                + "this virtual device");
            }
        }
        final long token = Binder.clearCallingIdentity();
        try {
            mInputController.createTouchscreen(deviceName, vendorId, productId,
                    deviceToken, displayId, screenSize);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override // Binder call
    public void unregisterInputDevice(IBinder token) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CREATE_VIRTUAL_DEVICE,
                "Permission required to unregister this input device");

        final long binderToken = Binder.clearCallingIdentity();
        try {
            mInputController.unregisterInputDevice(token);
        } finally {
            Binder.restoreCallingIdentity(binderToken);
        }
    }

    @Override // Binder call
    public boolean sendKeyEvent(IBinder token, VirtualKeyEvent event) {
        final long binderToken = Binder.clearCallingIdentity();
        try {
            return mInputController.sendKeyEvent(token, event);
        } finally {
            Binder.restoreCallingIdentity(binderToken);
        }
    }

    @Override // Binder call
    public boolean sendButtonEvent(IBinder token, VirtualMouseButtonEvent event) {
        final long binderToken = Binder.clearCallingIdentity();
        try {
            return mInputController.sendButtonEvent(token, event);
        } finally {
            Binder.restoreCallingIdentity(binderToken);
        }
    }

    @Override // Binder call
    public boolean sendTouchEvent(IBinder token, VirtualTouchEvent event) {
        final long binderToken = Binder.clearCallingIdentity();
        try {
            return mInputController.sendTouchEvent(token, event);
        } finally {
            Binder.restoreCallingIdentity(binderToken);
        }
    }

    @Override // Binder call
    public boolean sendRelativeEvent(IBinder token, VirtualMouseRelativeEvent event) {
        final long binderToken = Binder.clearCallingIdentity();
        try {
            return mInputController.sendRelativeEvent(token, event);
        } finally {
            Binder.restoreCallingIdentity(binderToken);
        }
    }

    @Override // Binder call
    public boolean sendScrollEvent(IBinder token, VirtualMouseScrollEvent event) {
        final long binderToken = Binder.clearCallingIdentity();
        try {
            return mInputController.sendScrollEvent(token, event);
        } finally {
            Binder.restoreCallingIdentity(binderToken);
        }
    }

    @Override // Binder call
    public PointF getCursorPosition(IBinder token) {
        final long binderToken = Binder.clearCallingIdentity();
        try {
            return mInputController.getCursorPosition(token);
        } finally {
            Binder.restoreCallingIdentity(binderToken);
        }
    }

    @Override // Binder call
    public void setShowPointerIcon(boolean showPointerIcon) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CREATE_VIRTUAL_DEVICE,
                "Permission required to unregister this input device");

        final long binderToken = Binder.clearCallingIdentity();
        try {
            synchronized (mVirtualDeviceLock) {
                mDefaultShowPointerIcon = showPointerIcon;
                for (int displayId : mVirtualDisplayIds) {
                    mInputController.setShowPointerIcon(mDefaultShowPointerIcon, displayId);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(binderToken);
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
        fout.println("  VirtualDevice: ");
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
    }

    DisplayWindowPolicyController onVirtualDisplayCreatedLocked(int displayId) {
        synchronized (mVirtualDeviceLock) {
            if (mVirtualDisplayIds.contains(displayId)) {
                throw new IllegalStateException(
                        "Virtual device already have a virtual display with ID " + displayId);
            }
            mVirtualDisplayIds.add(displayId);
            mInputController.setShowPointerIcon(mDefaultShowPointerIcon, displayId);
            mInputController.setPointerAcceleration(1f, displayId);
            mInputController.setDisplayEligibilityForPointerCapture(/* isEligible= */ false,
                    displayId);

            // Since we're being called in the middle of the display being created, we post a
            // task to grab the wakelock instead of doing it synchronously here, to avoid
            // reentrancy  problems.
            mContext.getMainThreadHandler().post(() -> addWakeLockForDisplay(displayId));

            final GenericWindowPolicyController dwpc =
                    new GenericWindowPolicyController(FLAG_SECURE,
                            SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS,
                            getAllowedUserHandles(),
                            mParams.getAllowedActivities(),
                            mParams.getBlockedActivities(),
                            mParams.getDefaultActivityPolicy(),
                            createListenerAdapter(displayId),
                            activityInfo -> onActivityBlocked(displayId, activityInfo));
            mWindowPolicyControllers.put(displayId, dwpc);
            return dwpc;
        }
    }

    void addWakeLockForDisplay(int displayId) {
        synchronized (mVirtualDeviceLock) {
            if (!mVirtualDisplayIds.contains(displayId)
                    || mPerDisplayWakelocks.containsKey(displayId)) {
                Slog.e(TAG, "Not creating wakelock for displayId " + displayId);
                return;
            }
            PowerManager powerManager = mContext.getSystemService(PowerManager.class);
            PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                            | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    TAG + ":" + displayId, displayId);
            wakeLock.acquire();
            mPerDisplayWakelocks.put(displayId, wakeLock);
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
    void showToastWhereUidIsRunning(int uid, @StringRes int resId, @Toast.Duration int duration) {
        showToastWhereUidIsRunning(uid, mContext.getString(resId), duration);
    }

    /**
     * Shows a toast on virtual displays owned by this device which have a given uid running.
     */
    void showToastWhereUidIsRunning(int uid, String text, @Toast.Duration int duration) {
        synchronized (mVirtualDeviceLock) {
            DisplayManager displayManager = mContext.getSystemService(DisplayManager.class);
            final int size = mWindowPolicyControllers.size();
            for (int i = 0; i < size; i++) {
                if (mWindowPolicyControllers.valueAt(i).containsUid(uid)) {
                    int displayId = mWindowPolicyControllers.keyAt(i);
                    Display display = displayManager.getDisplay(displayId);
                    if (display != null && display.isValid()) {
                        Toast.makeText(mContext.createDisplayContext(display), text,
                                duration).show();
                    }
                }
            }
        }
    }

    boolean isDisplayOwnedByVirtualDevice(int displayId) {
        return mVirtualDisplayIds.contains(displayId);
    }

    interface OnDeviceCloseListener {
        void onClose(int associationId);
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
