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

import static android.companion.virtual.VirtualDeviceParams.DEVICE_POLICY_DEFAULT;
import static android.media.AudioManager.AUDIO_SESSION_ID_GENERATE;

import static com.android.server.wm.ActivityInterceptorCallback.VIRTUAL_DEVICE_SERVICE_ORDERED_ID;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.ActivityOptions;
import android.companion.AssociationInfo;
import android.companion.CompanionDeviceManager;
import android.companion.virtual.IVirtualDevice;
import android.companion.virtual.IVirtualDeviceActivityListener;
import android.companion.virtual.IVirtualDeviceManager;
import android.companion.virtual.VirtualDevice;
import android.companion.virtual.VirtualDeviceManager;
import android.companion.virtual.VirtualDeviceParams;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.display.IVirtualDisplayCallback;
import android.hardware.display.VirtualDisplayConfig;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.LocaleList;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.ExceptionUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.widget.Toast;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.server.SystemService;
import com.android.server.companion.virtual.VirtualDeviceImpl.PendingTrampoline;
import com.android.server.wm.ActivityInterceptorCallback;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;


@SuppressLint("LongLogTag")
public class VirtualDeviceManagerService extends SystemService {

    private static final String TAG = "VirtualDeviceManagerService";

    private final Object mVirtualDeviceManagerLock = new Object();
    private final VirtualDeviceManagerImpl mImpl;
    private final VirtualDeviceManagerInternal mLocalService;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final PendingTrampolineMap mPendingTrampolines = new PendingTrampolineMap(mHandler);

    private static AtomicInteger sNextUniqueIndex = new AtomicInteger(
            VirtualDeviceManager.DEVICE_ID_DEFAULT + 1);

    /**
     * Mapping from device IDs to virtual devices.
     */
    @GuardedBy("mVirtualDeviceManagerLock")
    private final SparseArray<VirtualDeviceImpl> mVirtualDevices = new SparseArray<>();

    /**
     * Mapping from device IDs to app UIDs running on the corresponding virtual device.
     */
    @GuardedBy("mVirtualDeviceManagerLock")
    private final SparseArray<ArraySet<Integer>> mAppsOnVirtualDevices = new SparseArray<>();

    public VirtualDeviceManagerService(Context context) {
        super(context);
        mImpl = new VirtualDeviceManagerImpl();
        mLocalService = new LocalService();
    }

    private final ActivityInterceptorCallback mActivityInterceptorCallback =
            new ActivityInterceptorCallback() {

        @Nullable
        @Override
        public ActivityInterceptResult onInterceptActivityLaunch(@NonNull
                ActivityInterceptorInfo info) {
            if (info.getCallingPackage() == null) {
                return null;
            }
            PendingTrampoline pt = mPendingTrampolines.remove(info.getCallingPackage());
            if (pt == null) {
                return null;
            }
            pt.mResultReceiver.send(VirtualDeviceManager.LAUNCH_SUCCESS, null);
            ActivityOptions options = info.getCheckedOptions();
            if (options == null) {
                options = ActivityOptions.makeBasic();
            }
            return new ActivityInterceptResult(
                    info.getIntent(), options.setLaunchDisplayId(pt.mDisplayId));
        }
    };

    @Override
    public void onStart() {
        publishBinderService(Context.VIRTUAL_DEVICE_SERVICE, mImpl);
        publishLocalService(VirtualDeviceManagerInternal.class, mLocalService);
        ActivityTaskManagerInternal activityTaskManagerInternal = getLocalService(
                ActivityTaskManagerInternal.class);
        activityTaskManagerInternal.registerActivityStartInterceptor(
                VIRTUAL_DEVICE_SERVICE_ORDERED_ID,
                mActivityInterceptorCallback);
    }

    void onCameraAccessBlocked(int appUid) {
        synchronized (mVirtualDeviceManagerLock) {
            for (int i = 0; i < mVirtualDevices.size(); i++) {
                CharSequence deviceName = mVirtualDevices.valueAt(i).getDisplayName();
                mVirtualDevices.valueAt(i).showToastWhereUidIsRunning(appUid,
                        getContext().getString(
                            com.android.internal.R.string.vdm_camera_access_denied,
                            deviceName),
                        Toast.LENGTH_LONG, Looper.myLooper());
            }
        }
    }

    CameraAccessController getCameraAccessController(UserHandle userHandle) {
        int userId = userHandle.getIdentifier();
        synchronized (mVirtualDeviceManagerLock) {
            for (int i = 0; i < mVirtualDevices.size(); i++) {
                final CameraAccessController cameraAccessController =
                        mVirtualDevices.valueAt(i).getCameraAccessController();
                if (cameraAccessController.getUserId() == userId) {
                    return cameraAccessController;
                }
            }
        }
        Context userContext = getContext().createContextAsUser(userHandle, 0);
        return new CameraAccessController(userContext, mLocalService, this::onCameraAccessBlocked);
    }

    @VisibleForTesting
    VirtualDeviceManagerInternal getLocalServiceInstance() {
        return mLocalService;
    }

    @VisibleForTesting
    void notifyRunningAppsChanged(int deviceId, ArraySet<Integer> uids) {
        synchronized (mVirtualDeviceManagerLock) {
            mAppsOnVirtualDevices.put(deviceId, uids);
        }
        mLocalService.onAppsOnVirtualDeviceChanged();
    }

    @VisibleForTesting
    void addVirtualDevice(VirtualDeviceImpl virtualDevice) {
        synchronized (mVirtualDeviceManagerLock) {
            mVirtualDevices.put(virtualDevice.getDeviceId(), virtualDevice);
        }
    }

    @VisibleForTesting
    void removeVirtualDevice(int deviceId) {
        synchronized (mVirtualDeviceManagerLock) {
            mAppsOnVirtualDevices.remove(deviceId);
            mVirtualDevices.remove(deviceId);
        }
    }

    class VirtualDeviceManagerImpl extends IVirtualDeviceManager.Stub {

        private final VirtualDeviceImpl.PendingTrampolineCallback mPendingTrampolineCallback =
                new VirtualDeviceImpl.PendingTrampolineCallback() {
            @Override
            public void startWaitingForPendingTrampoline(PendingTrampoline pendingTrampoline) {
                PendingTrampoline existing = mPendingTrampolines.put(
                        pendingTrampoline.mPendingIntent.getCreatorPackage(),
                        pendingTrampoline);
                if (existing != null) {
                    existing.mResultReceiver.send(
                            VirtualDeviceManager.LAUNCH_FAILURE_NO_ACTIVITY, null);
                }
            }

            @Override
            public void stopWaitingForPendingTrampoline(PendingTrampoline pendingTrampoline) {
                mPendingTrampolines.remove(pendingTrampoline.mPendingIntent.getCreatorPackage());
            }
        };

        @Override // Binder call
        public IVirtualDevice createVirtualDevice(
                IBinder token,
                String packageName,
                int associationId,
                @NonNull VirtualDeviceParams params,
                @NonNull IVirtualDeviceActivityListener activityListener) {
            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.CREATE_VIRTUAL_DEVICE,
                    "createVirtualDevice");
            final int callingUid = getCallingUid();
            if (!PermissionUtils.validateCallingPackageName(getContext(), packageName)) {
                throw new SecurityException(
                        "Package name " + packageName + " does not belong to calling uid "
                                + callingUid);
            }
            AssociationInfo associationInfo = getAssociationInfo(packageName, associationId);
            if (associationInfo == null) {
                throw new IllegalArgumentException("No association with ID " + associationId);
            }
            synchronized (mVirtualDeviceManagerLock) {
                final UserHandle userHandle = getCallingUserHandle();
                final CameraAccessController cameraAccessController =
                        getCameraAccessController(userHandle);
                final int deviceId = sNextUniqueIndex.getAndIncrement();
                final Consumer<ArraySet<Integer>> runningAppsChangedCallback =
                        runningUids -> notifyRunningAppsChanged(deviceId, runningUids);
                VirtualDeviceImpl virtualDevice = new VirtualDeviceImpl(getContext(),
                        associationInfo, token, callingUid, deviceId, cameraAccessController,
                        this::onDeviceClosed, mPendingTrampolineCallback, activityListener,
                        runningAppsChangedCallback, params);
                mVirtualDevices.put(deviceId, virtualDevice);
                return virtualDevice;
            }
        }

        @Override // Binder call
        public int createVirtualDisplay(VirtualDisplayConfig virtualDisplayConfig,
                IVirtualDisplayCallback callback, IVirtualDevice virtualDevice, String packageName)
                throws RemoteException {
            final int callingUid = getCallingUid();
            if (!PermissionUtils.validateCallingPackageName(getContext(), packageName)) {
                throw new SecurityException(
                        "Package name " + packageName + " does not belong to calling uid "
                                + callingUid);
            }
            VirtualDeviceImpl virtualDeviceImpl;
            synchronized (mVirtualDeviceManagerLock) {
                virtualDeviceImpl = mVirtualDevices.get(virtualDevice.getDeviceId());
                if (virtualDeviceImpl == null) {
                    throw new SecurityException("Invalid VirtualDevice");
                }
            }
            if (virtualDeviceImpl.getOwnerUid() != callingUid) {
                throw new SecurityException(
                        "uid " + callingUid
                                + " is not the owner of the supplied VirtualDevice");
            }
            GenericWindowPolicyController gwpc;
            final long token = Binder.clearCallingIdentity();
            try {
                gwpc = virtualDeviceImpl.createWindowPolicyController(
                    virtualDisplayConfig.getDisplayCategories());
            } finally {
                Binder.restoreCallingIdentity(token);
            }

            DisplayManagerInternal displayManager = getLocalService(
                    DisplayManagerInternal.class);
            int displayId = displayManager.createVirtualDisplay(virtualDisplayConfig, callback,
                    virtualDevice, gwpc, packageName);

            final long tokenTwo = Binder.clearCallingIdentity();
            try {
                virtualDeviceImpl.onVirtualDisplayCreatedLocked(gwpc, displayId);
            } finally {
                Binder.restoreCallingIdentity(tokenTwo);
            }
            mLocalService.onVirtualDisplayCreated(displayId);
            return displayId;
        }

        @Override // Binder call
        public List<VirtualDevice> getVirtualDevices() {
            List<VirtualDevice> virtualDevices = new ArrayList<>();
            synchronized (mVirtualDeviceManagerLock) {
                for (int i = 0; i < mVirtualDevices.size(); i++) {
                    final VirtualDeviceImpl device = mVirtualDevices.valueAt(i);
                    virtualDevices.add(
                            new VirtualDevice(device.getDeviceId(), device.getDeviceName()));
                }
            }
            return virtualDevices;
        }

        @Override // BinderCall
        @VirtualDeviceParams.DevicePolicy
        public int getDevicePolicy(int deviceId, @VirtualDeviceParams.PolicyType int policyType) {
            synchronized (mVirtualDeviceManagerLock) {
                VirtualDeviceImpl virtualDevice = mVirtualDevices.get(deviceId);
                return virtualDevice != null
                        ? virtualDevice.getDevicePolicy(policyType) : DEVICE_POLICY_DEFAULT;
            }
        }


        @Override // Binder call
        public int getDeviceIdForDisplayId(int displayId) {
            if (displayId == Display.INVALID_DISPLAY || displayId == Display.DEFAULT_DISPLAY) {
                return VirtualDeviceManager.DEVICE_ID_DEFAULT;
            }
            synchronized (mVirtualDeviceManagerLock) {
                for (int i = 0; i < mVirtualDevices.size(); i++) {
                    VirtualDeviceImpl virtualDevice = mVirtualDevices.valueAt(i);
                    if (virtualDevice.isDisplayOwnedByVirtualDevice(displayId)) {
                        return virtualDevice.getDeviceId();
                    }
                }
            }
            return VirtualDeviceManager.DEVICE_ID_DEFAULT;
        }

        // Binder call
        @Override
        public boolean isValidVirtualDeviceId(int deviceId) {
            synchronized (mVirtualDeviceManagerLock) {
                return mVirtualDevices.contains(deviceId);
            }
        }

        @Override // Binder call
        public int getAudioPlaybackSessionId(int deviceId) {
            synchronized (mVirtualDeviceManagerLock) {
                VirtualDeviceImpl virtualDevice = mVirtualDevices.get(deviceId);
                return virtualDevice != null
                        ? virtualDevice.getAudioPlaybackSessionId() : AUDIO_SESSION_ID_GENERATE;
            }
        }

        @Override // Binder call
        public int getAudioRecordingSessionId(int deviceId) {
            synchronized (mVirtualDeviceManagerLock) {
                VirtualDeviceImpl virtualDevice = mVirtualDevices.get(deviceId);
                return virtualDevice != null
                        ? virtualDevice.getAudioRecordingSessionId() : AUDIO_SESSION_ID_GENERATE;
            }
        }

        @Nullable
        private AssociationInfo getAssociationInfo(String packageName, int associationId) {
            final UserHandle userHandle = getCallingUserHandle();
            final CompanionDeviceManager cdm =
                    getContext().createContextAsUser(userHandle, 0)
                            .getSystemService(CompanionDeviceManager.class);
            List<AssociationInfo> associations;
            final long identity = Binder.clearCallingIdentity();
            try {
                associations = cdm.getAllAssociations();
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            final int callingUserId = userHandle.getIdentifier();
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
                Slog.w(TAG, "No associations for user " + callingUserId);
            }
            return null;
        }

        private void onDeviceClosed(int deviceId) {
            removeVirtualDevice(deviceId);
            Intent i = new Intent(VirtualDeviceManager.ACTION_VIRTUAL_DEVICE_REMOVED);
            i.putExtra(VirtualDeviceManager.EXTRA_VIRTUAL_DEVICE_ID, deviceId);
            i.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            final long identity = Binder.clearCallingIdentity();
            try {
                getContext().sendBroadcastAsUser(i, UserHandle.ALL);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            try {
                return super.onTransact(code, data, reply, flags);
            } catch (Throwable e) {
                Slog.e(TAG, "Error during IPC", e);
                throw ExceptionUtils.propagate(e, RemoteException.class);
            }
        }

        @Override
        public void dump(@NonNull FileDescriptor fd,
                @NonNull PrintWriter fout,
                @Nullable String[] args) {
            if (!DumpUtils.checkDumpAndUsageStatsPermission(getContext(), TAG, fout)) {
                return;
            }
            fout.println("Created virtual devices: ");
            synchronized (mVirtualDeviceManagerLock) {
                for (int i = 0; i < mVirtualDevices.size(); i++) {
                    mVirtualDevices.valueAt(i).dump(fd, fout, args);
                }
            }
        }
    }

    private final class LocalService extends VirtualDeviceManagerInternal {
        @GuardedBy("mVirtualDeviceManagerLock")
        private final ArrayList<VirtualDisplayListener>
                mVirtualDisplayListeners = new ArrayList<>();
        @GuardedBy("mVirtualDeviceManagerLock")
        private final ArrayList<AppsOnVirtualDeviceListener>
                mAppsOnVirtualDeviceListeners = new ArrayList<>();
        @GuardedBy("mVirtualDeviceManagerLock")
        private final ArraySet<Integer> mAllUidsOnVirtualDevice = new ArraySet<>();

        @Override
        public int getDeviceOwnerUid(int deviceId) {
            synchronized (mVirtualDeviceManagerLock) {
                VirtualDeviceImpl virtualDevice = mVirtualDevices.get(deviceId);
                return virtualDevice != null ? virtualDevice.getOwnerUid() : Process.INVALID_UID;
            }
        }

        @Override
        public @NonNull Set<Integer> getDeviceIdsForUid(int uid) {
            ArraySet<Integer> result = new ArraySet<>();
            synchronized (mVirtualDeviceManagerLock) {
                int size = mVirtualDevices.size();
                for (int i = 0; i < size; i++) {
                    VirtualDeviceImpl device = mVirtualDevices.valueAt(i);
                    if (device.isAppRunningOnVirtualDevice(uid)) {
                        result.add(device.getDeviceId());
                    }
                }
            }
            return result;
        }

        @Override
        public void onVirtualDisplayCreated(int displayId) {
            final VirtualDisplayListener[] listeners;
            synchronized (mVirtualDeviceManagerLock) {
                listeners = mVirtualDisplayListeners.toArray(new VirtualDisplayListener[0]);
            }
            mHandler.post(() -> {
                for (VirtualDisplayListener listener : listeners) {
                    listener.onVirtualDisplayCreated(displayId);
                }
            });
        }

        @Override
        public void onVirtualDisplayRemoved(IVirtualDevice virtualDevice, int displayId) {
            final VirtualDisplayListener[] listeners;
            synchronized (mVirtualDeviceManagerLock) {
                ((VirtualDeviceImpl) virtualDevice).onVirtualDisplayRemovedLocked(displayId);
                listeners = mVirtualDisplayListeners.toArray(new VirtualDisplayListener[0]);
            }
            mHandler.post(() -> {
                for (VirtualDisplayListener listener : listeners) {
                    listener.onVirtualDisplayRemoved(displayId);
                }
            });
        }

        @Override
        public void onAppsOnVirtualDeviceChanged() {
            ArraySet<Integer> latestRunningUids = new ArraySet<>();
            final AppsOnVirtualDeviceListener[] listeners;
            synchronized (mVirtualDeviceManagerLock) {
                int size = mAppsOnVirtualDevices.size();
                for (int i = 0; i < size; i++) {
                    latestRunningUids.addAll(mAppsOnVirtualDevices.valueAt(i));
                }
                if (!mAllUidsOnVirtualDevice.equals(latestRunningUids)) {
                    mAllUidsOnVirtualDevice.clear();
                    mAllUidsOnVirtualDevice.addAll(latestRunningUids);
                    listeners =
                            mAppsOnVirtualDeviceListeners.toArray(
                                    new AppsOnVirtualDeviceListener[0]);
                } else {
                    listeners = null;
                }
            }
            if (listeners != null) {
                mHandler.post(() -> {
                    for (AppsOnVirtualDeviceListener listener : listeners) {
                        listener.onAppsOnAnyVirtualDeviceChanged(latestRunningUids);
                    }
                });
            }
        }

        @Override
        public int getBaseVirtualDisplayFlags(IVirtualDevice virtualDevice) {
            return ((VirtualDeviceImpl) virtualDevice).getBaseVirtualDisplayFlags();
        }

        @Override
        @Nullable
        public LocaleList getPreferredLocaleListForUid(int uid) {
            // TODO: b/263188984 support the case where an app is running on multiple VDs
            synchronized (mVirtualDeviceManagerLock) {
                for (int i = 0; i < mAppsOnVirtualDevices.size(); i++) {
                    if (mAppsOnVirtualDevices.valueAt(i).contains(uid)) {
                        int deviceId = mAppsOnVirtualDevices.keyAt(i);
                        return mVirtualDevices.get(deviceId).getDeviceLocaleList();
                    }
                }
            }
            return null;
        }

        @Override
        public boolean isAppRunningOnAnyVirtualDevice(int uid) {
            synchronized (mVirtualDeviceManagerLock) {
                int size = mVirtualDevices.size();
                for (int i = 0; i < size; i++) {
                    if (mVirtualDevices.valueAt(i).isAppRunningOnVirtualDevice(uid)) {
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public boolean isDisplayOwnedByAnyVirtualDevice(int displayId) {
            synchronized (mVirtualDeviceManagerLock) {
                int size = mVirtualDevices.size();
                for (int i = 0; i < size; i++) {
                    if (mVirtualDevices.valueAt(i).isDisplayOwnedByVirtualDevice(displayId)) {
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public void registerVirtualDisplayListener(
                @NonNull VirtualDisplayListener listener) {
            synchronized (mVirtualDeviceManagerLock) {
                mVirtualDisplayListeners.add(listener);
            }
        }

        @Override
        public void unregisterVirtualDisplayListener(
                @NonNull VirtualDisplayListener listener) {
            synchronized (mVirtualDeviceManagerLock) {
                mVirtualDisplayListeners.remove(listener);
            }
        }

        @Override
        public void registerAppsOnVirtualDeviceListener(
                @NonNull AppsOnVirtualDeviceListener listener) {
            synchronized (mVirtualDeviceManagerLock) {
                mAppsOnVirtualDeviceListeners.add(listener);
            }
        }

        @Override
        public void unregisterAppsOnVirtualDeviceListener(
                @NonNull AppsOnVirtualDeviceListener listener) {
            synchronized (mVirtualDeviceManagerLock) {
                mAppsOnVirtualDeviceListeners.remove(listener);
            }
        }
    }

    private static final class PendingTrampolineMap {
        /**
         * The maximum duration, in milliseconds, to wait for a trampoline activity launch after
         * invoking a pending intent.
         */
        private static final int TRAMPOLINE_WAIT_MS = 5000;

        private final ConcurrentHashMap<String, PendingTrampoline> mMap = new ConcurrentHashMap<>();
        private final Handler mHandler;

        PendingTrampolineMap(Handler handler) {
            mHandler = handler;
        }

        PendingTrampoline put(
                @NonNull String packageName, @NonNull PendingTrampoline pendingTrampoline) {
            PendingTrampoline existing = mMap.put(packageName, pendingTrampoline);
            mHandler.removeCallbacksAndMessages(existing);
            mHandler.postDelayed(
                    () -> {
                        final String creatorPackage =
                                pendingTrampoline.mPendingIntent.getCreatorPackage();
                        if (creatorPackage != null) {
                            remove(creatorPackage);
                        }
                    },
                    pendingTrampoline,
                    TRAMPOLINE_WAIT_MS);
            return existing;
        }

        PendingTrampoline remove(@NonNull String packageName) {
            PendingTrampoline pendingTrampoline = mMap.remove(packageName);
            mHandler.removeCallbacksAndMessages(pendingTrampoline);
            return pendingTrampoline;
        }
    }
}
