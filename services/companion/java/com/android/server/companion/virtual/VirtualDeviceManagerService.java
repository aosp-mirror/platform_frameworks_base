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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.companion.AssociationInfo;
import android.companion.CompanionDeviceManager;
import android.companion.CompanionDeviceManager.OnAssociationsChangedListener;
import android.companion.virtual.IVirtualDevice;
import android.companion.virtual.IVirtualDeviceManager;
import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.ExceptionUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.window.DisplayWindowPolicyController;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.DumpUtils;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;


@SuppressLint("LongLogTag")
public class VirtualDeviceManagerService extends SystemService {

    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "VirtualDeviceManagerService";
    private final Object mVirtualDeviceManagerLock = new Object();
    private final VirtualDeviceManagerImpl mImpl;

    /**
     * Mapping from CDM association IDs to virtual devices. Only one virtual device is allowed for
     * each CDM associated device.
     */
    @GuardedBy("mVirtualDeviceManagerLock")
    private final SparseArray<VirtualDeviceImpl> mVirtualDevices = new SparseArray<>();

    /**
     * Mapping from user ID to CDM associations. The associations come from
     * {@link CompanionDeviceManager#getAllAssociations()}, which contains associations across all
     * packages.
     */
    private final ConcurrentHashMap<Integer, List<AssociationInfo>> mAllAssociations =
            new ConcurrentHashMap<>();

    /**
     * Mapping from user ID to its change listener. The listeners are added when the user is
     * started and removed when the user stops.
     */
    private final SparseArray<OnAssociationsChangedListener> mOnAssociationsChangedListeners =
            new SparseArray<>();

    public VirtualDeviceManagerService(Context context) {
        super(context);
        mImpl = new VirtualDeviceManagerImpl();
    }

    @Override
    public void onStart() {
        publishBinderService(Context.VIRTUAL_DEVICE_SERVICE, mImpl);
        publishLocalService(VirtualDeviceManagerInternal.class, new LocalService());
    }

    @GuardedBy("mVirtualDeviceManagerLock")
    private boolean isValidVirtualDeviceLocked(IVirtualDevice virtualDevice) {
        try {
            return mVirtualDevices.contains(virtualDevice.getAssociationId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void onUserStarting(@NonNull TargetUser user) {
        super.onUserStarting(user);
        synchronized (mVirtualDeviceManagerLock) {
            final CompanionDeviceManager cdm = getContext()
                    .createContextAsUser(user.getUserHandle(), 0)
                    .getSystemService(CompanionDeviceManager.class);
            final int userId = user.getUserIdentifier();
            mAllAssociations.put(userId, cdm.getAllAssociations());
            OnAssociationsChangedListener listener =
                    associations -> mAllAssociations.put(userId, associations);
            mOnAssociationsChangedListeners.put(userId, listener);
            cdm.addOnAssociationsChangedListener(Runnable::run, listener);
        }
    }

    @Override
    public void onUserStopping(@NonNull TargetUser user) {
        super.onUserStopping(user);
        synchronized (mVirtualDeviceManagerLock) {
            int userId = user.getUserIdentifier();
            mAllAssociations.remove(userId);
            final CompanionDeviceManager cdm = getContext().createContextAsUser(
                    user.getUserHandle(), 0)
                    .getSystemService(CompanionDeviceManager.class);
            OnAssociationsChangedListener listener = mOnAssociationsChangedListeners.get(userId);
            if (listener != null) {
                cdm.removeOnAssociationsChangedListener(listener);
                mOnAssociationsChangedListeners.remove(userId);
            }
        }
    }

    private class VirtualDeviceImpl extends IVirtualDevice.Stub implements IBinder.DeathRecipient {

        private final AssociationInfo mAssociationInfo;
        private final int mOwnerUid;
        private final ArrayList<Integer> mDisplayIds = new ArrayList<>();

        private VirtualDeviceImpl(int ownerUid, IBinder token, AssociationInfo associationInfo) {
            mOwnerUid = ownerUid;
            mAssociationInfo = associationInfo;
            try {
                token.linkToDeath(this, 0);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            mVirtualDevices.put(associationInfo.getId(), this);
        }

        @Override
        public int getAssociationId() {
            return mAssociationInfo.getId();
        }

        @Override
        public void close() {
            synchronized (mVirtualDeviceManagerLock) {
                mVirtualDevices.remove(mAssociationInfo.getId());
            }
        }

        @Override
        public void binderDied() {
            close();
        }

        DisplayWindowPolicyController onVirtualDisplayCreatedLocked(int displayId) {
            if (mDisplayIds.contains(displayId)) {
                throw new IllegalStateException(
                        "Virtual device already have a virtual display with ID " + displayId);
            }
            mDisplayIds.add(displayId);
            // TODO(b/201712607): Return the corresponding DisplayWindowPolicyController.
            return null;
        }

        void onVirtualDisplayRemovedLocked(int displayId) {
            if (!mDisplayIds.contains(displayId)) {
                throw new IllegalStateException(
                        "Virtual device doesn't have a virtual display with ID " + displayId);
            }
            mDisplayIds.remove(displayId);
        }
    }

    class VirtualDeviceManagerImpl extends IVirtualDeviceManager.Stub {

        @Override
        public IVirtualDevice createVirtualDevice(
                IBinder token, String packageName, int associationId) {
            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.CREATE_VIRTUAL_DEVICE,
                    "createVirtualDevice");
            final int callingUid = getCallingUid();
            if (!PermissionUtils.validatePackageName(getContext(), packageName, callingUid)) {
                throw new SecurityException(
                        "Package name " + packageName + " does not belong to calling uid "
                                + callingUid);
            }
            AssociationInfo associationInfo = getAssociationInfo(packageName, associationId);
            if (associationInfo == null) {
                throw new IllegalArgumentException("No association with ID " + associationId);
            }
            synchronized (mVirtualDeviceManagerLock) {
                if (mVirtualDevices.contains(associationId)) {
                    throw new IllegalStateException(
                            "Virtual device for association ID " + associationId
                                    + " already exists");
                }
                return new VirtualDeviceImpl(callingUid, token, associationInfo);
            }
        }

        @Nullable
        private AssociationInfo getAssociationInfo(String packageName, int associationId) {
            final int callingUserId = getCallingUserHandle().getIdentifier();
            final List<AssociationInfo> associations =
                    mAllAssociations.get(callingUserId);
            if (associations != null) {
                final int associationSize = associations.size();
                for (int i = 0; i < associationSize; i++) {
                    AssociationInfo associationInfo = associations.get(i);
                    if (associationInfo.belongsToPackage(callingUserId, packageName)
                            && associationId == associationInfo.getId()) {
                        return associationInfo;
                    }
                }
            } else {
                Slog.w(LOG_TAG, "No associations for user " + callingUserId);
            }
            return null;
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            try {
                return super.onTransact(code, data, reply, flags);
            } catch (Throwable e) {
                Slog.e(LOG_TAG, "Error during IPC", e);
                throw ExceptionUtils.propagate(e, RemoteException.class);
            }
        }

        @Override
        public void dump(@NonNull FileDescriptor fd,
                @NonNull PrintWriter fout,
                @Nullable String[] args) {
            if (!DumpUtils.checkDumpAndUsageStatsPermission(getContext(), LOG_TAG, fout)) {
                return;
            }
            fout.println("Created virtual devices: ");
            synchronized (mVirtualDeviceManagerLock) {
                for (int i = 0; i < mVirtualDevices.size(); i++) {
                    VirtualDeviceImpl virtualDevice = mVirtualDevices.valueAt(i);
                    fout.printf("%d: %s\n", mVirtualDevices.keyAt(i), virtualDevice);
                }
            }
        }
    }

    private final class LocalService extends VirtualDeviceManagerInternal {

        @Override
        public boolean isValidVirtualDevice(IVirtualDevice virtualDevice) {
            synchronized (mVirtualDeviceManagerLock) {
                return isValidVirtualDeviceLocked(virtualDevice);
            }
        }

        @Override
        public DisplayWindowPolicyController onVirtualDisplayCreated(IVirtualDevice virtualDevice,
                int displayId) {
            synchronized (mVirtualDeviceManagerLock) {
                return ((VirtualDeviceImpl) virtualDevice).onVirtualDisplayCreatedLocked(displayId);
            }
        }

        @Override
        public void onVirtualDisplayRemoved(IVirtualDevice virtualDevice, int displayId) {
            synchronized (mVirtualDeviceManagerLock) {
                ((VirtualDeviceImpl) virtualDevice).onVirtualDisplayRemovedLocked(displayId);
            }
        }

        @Override
        public boolean isAppOwnerOfAnyVirtualDevice(int uid) {
            synchronized (mVirtualDeviceManagerLock) {
                int size = mVirtualDevices.size();
                for (int i = 0; i < size; i++) {
                    if (mVirtualDevices.valueAt(i).mOwnerUid == uid) {
                        return true;
                    }
                }
                return false;
            }
        }

        @Override
        public boolean isAppRunningOnAnyVirtualDevice(int uid) {
            // TODO(yukl): Implement this using DWPC.onRunningAppsChanged
            return false;
        }
    }
}
