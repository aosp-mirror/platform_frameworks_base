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
import android.app.admin.DevicePolicyManager;
import android.companion.AssociationInfo;
import android.companion.virtual.IVirtualDevice;
import android.companion.virtual.VirtualDeviceParams;
import android.content.Context;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.input.VirtualKeyEvent;
import android.hardware.input.VirtualMouseButtonEvent;
import android.hardware.input.VirtualMouseRelativeEvent;
import android.hardware.input.VirtualMouseScrollEvent;
import android.hardware.input.VirtualTouchEvent;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArraySet;
import android.util.SparseArray;
import android.window.DisplayWindowPolicyController;

import com.android.internal.annotations.VisibleForTesting;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;


final class VirtualDeviceImpl extends IVirtualDevice.Stub
        implements IBinder.DeathRecipient {

    private final Object mVirtualDeviceLock = new Object();

    private final Context mContext;
    private final AssociationInfo mAssociationInfo;
    private final int mOwnerUid;
    private final InputController mInputController;
    @VisibleForTesting
    final List<Integer> mVirtualDisplayIds = new ArrayList<>();
    private final OnDeviceCloseListener mListener;
    private final IBinder mAppToken;
    private final VirtualDeviceParams mParams;

    /**
     * A mapping from the virtual display ID to its corresponding
     * {@link GenericWindowPolicyController}.
     */
    private final SparseArray<GenericWindowPolicyController> mWindowPolicyControllers =
            new SparseArray<>();

    VirtualDeviceImpl(Context context, AssociationInfo associationInfo,
            IBinder token, int ownerUid, OnDeviceCloseListener listener,
            VirtualDeviceParams params) {
        this(context, associationInfo, token, ownerUid, /* inputController= */ null, listener,
                params);
    }

    @VisibleForTesting
    VirtualDeviceImpl(Context context, AssociationInfo associationInfo, IBinder token,
            int ownerUid, InputController inputController, OnDeviceCloseListener listener,
            VirtualDeviceParams params) {
        mContext = context;
        mAssociationInfo = associationInfo;
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

    @Override // Binder call
    public int getAssociationId() {
        return mAssociationInfo.getId();
    }

    @Override // Binder call
    public void close() {
        mListener.onClose(mAssociationInfo.getId());
        mAppToken.unlinkToDeath(this, 0);
        mInputController.close();
    }

    @Override
    public void binderDied() {
        close();
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
            mInputController.createKeyboard(deviceName, vendorId, productId, deviceToken);
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
            mInputController.createMouse(deviceName, vendorId, productId, deviceToken);
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
                    deviceToken, screenSize);
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

    @Override
    protected void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
        fout.println("  VirtualDevice: ");
        fout.println("    mVirtualDisplayIds: ");
        synchronized (mVirtualDeviceLock) {
            for (int id : mVirtualDisplayIds) {
                fout.println("      " + id);
            }
        }
        mInputController.dump(fout);
    }

    DisplayWindowPolicyController onVirtualDisplayCreatedLocked(int displayId) {
        if (mVirtualDisplayIds.contains(displayId)) {
            throw new IllegalStateException(
                    "Virtual device already have a virtual display with ID " + displayId);
        }
        mVirtualDisplayIds.add(displayId);
        final GenericWindowPolicyController dwpc =
                new GenericWindowPolicyController(FLAG_SECURE,
                        SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS, getAllowedUserHandles());
        mWindowPolicyControllers.put(displayId, dwpc);
        return dwpc;
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
        if (!mVirtualDisplayIds.contains(displayId)) {
            throw new IllegalStateException(
                    "Virtual device doesn't have a virtual display with ID " + displayId);
        }
        mVirtualDisplayIds.remove(displayId);
        mWindowPolicyControllers.remove(displayId);
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

    interface OnDeviceCloseListener {
        void onClose(int associationId);
    }
}
