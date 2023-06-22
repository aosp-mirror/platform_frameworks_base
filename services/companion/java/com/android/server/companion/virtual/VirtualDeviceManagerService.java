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
import android.companion.virtual.IVirtualDeviceSoundEffectListener;
import android.companion.virtual.VirtualDevice;
import android.companion.virtual.VirtualDeviceManager;
import android.companion.virtual.VirtualDeviceParams;
import android.companion.virtual.sensor.VirtualSensor;
import android.content.Context;
import android.content.Intent;
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

import com.android.internal.R;
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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
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
            Context.DEVICE_ID_DEFAULT + 1);

    private final CompanionDeviceManager.OnAssociationsChangedListener mCdmAssociationListener =
            new CompanionDeviceManager.OnAssociationsChangedListener() {
                @Override
                public void onAssociationsChanged(@NonNull List<AssociationInfo> associations) {
                    syncVirtualDevicesToCdmAssociations(associations);
                }
            };

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
        ArrayList<VirtualDeviceImpl> virtualDevicesSnapshot = getVirtualDevicesSnapshot();
        for (int i = 0; i < virtualDevicesSnapshot.size(); i++) {
            VirtualDeviceImpl virtualDevice = virtualDevicesSnapshot.get(i);
            virtualDevice.showToastWhereUidIsRunning(appUid,
                    getContext().getString(
                            R.string.vdm_camera_access_denied,
                            virtualDevice.getDisplayName()),
                    Toast.LENGTH_LONG, Looper.myLooper());
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
            if (!mVirtualDevices.contains(deviceId)) {
                Slog.e(TAG, "notifyRunningAppsChanged called for unknown deviceId:" + deviceId
                        + " (maybe it was recently closed?)");
                return;
            }
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

    /**
     * Remove the virtual device. Sends the
     * {@link VirtualDeviceManager#ACTION_VIRTUAL_DEVICE_REMOVED} broadcast as a result.
     *
     * @param deviceId deviceId to be removed
     * @return {@code true} if the device was removed, {@code false} if the operation was a no-op
     */
    boolean removeVirtualDevice(int deviceId) {
        synchronized (mVirtualDeviceManagerLock) {
            if (!mVirtualDevices.contains(deviceId)) {
                return false;
            }

            mAppsOnVirtualDevices.remove(deviceId);
            mVirtualDevices.remove(deviceId);
        }

        Intent i = new Intent(VirtualDeviceManager.ACTION_VIRTUAL_DEVICE_REMOVED);
        i.putExtra(VirtualDeviceManager.EXTRA_VIRTUAL_DEVICE_ID, deviceId);
        i.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        final long identity = Binder.clearCallingIdentity();
        try {
            getContext().sendBroadcastAsUser(i, UserHandle.ALL);

            synchronized (mVirtualDeviceManagerLock) {
                if (mVirtualDevices.size() == 0) {
                    unregisterCdmAssociationListener();
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return true;
    }

    private void syncVirtualDevicesToCdmAssociations(List<AssociationInfo> associations) {
        Set<VirtualDeviceImpl> virtualDevicesToRemove = new HashSet<>();
        synchronized (mVirtualDeviceManagerLock) {
            if (mVirtualDevices.size() == 0) {
                return;
            }

            Set<Integer> activeAssociationIds = new HashSet<>(associations.size());
            for (AssociationInfo association : associations) {
                activeAssociationIds.add(association.getId());
            }

            for (int i = 0; i < mVirtualDevices.size(); i++) {
                VirtualDeviceImpl virtualDevice = mVirtualDevices.valueAt(i);
                if (!activeAssociationIds.contains(virtualDevice.getAssociationId())) {
                    virtualDevicesToRemove.add(virtualDevice);
                }
            }
        }

        for (VirtualDeviceImpl virtualDevice : virtualDevicesToRemove) {
            virtualDevice.close();
        }
    }

    private void registerCdmAssociationListener() {
        final CompanionDeviceManager cdm = getContext().getSystemService(
                CompanionDeviceManager.class);
        cdm.addOnAssociationsChangedListener(getContext().getMainExecutor(),
                mCdmAssociationListener);
    }

    private void unregisterCdmAssociationListener() {
        final CompanionDeviceManager cdm = getContext().getSystemService(
                CompanionDeviceManager.class);
        cdm.removeOnAssociationsChangedListener(mCdmAssociationListener);
    }

    private ArrayList<VirtualDeviceImpl> getVirtualDevicesSnapshot() {
        synchronized (mVirtualDeviceManagerLock) {
            ArrayList<VirtualDeviceImpl> virtualDevices = new ArrayList<>(mVirtualDevices.size());
            for (int i = 0; i < mVirtualDevices.size(); i++) {
                virtualDevices.add(mVirtualDevices.valueAt(i));
            }
            return virtualDevices;
        }
    }

    class VirtualDeviceManagerImpl extends IVirtualDeviceManager.Stub {

        private final VirtualDeviceImpl.PendingTrampolineCallback mPendingTrampolineCallback =
                new VirtualDeviceImpl.PendingTrampolineCallback() {
                    @Override
                    public void startWaitingForPendingTrampoline(
                            PendingTrampoline pendingTrampoline) {
                        PendingTrampoline existing = mPendingTrampolines.put(
                                pendingTrampoline.mPendingIntent.getCreatorPackage(),
                                pendingTrampoline);
                        if (existing != null) {
                            existing.mResultReceiver.send(
                                    VirtualDeviceManager.LAUNCH_FAILURE_NO_ACTIVITY, null);
                        }
                    }

                    @Override
                    public void stopWaitingForPendingTrampoline(
                            PendingTrampoline pendingTrampoline) {
                        mPendingTrampolines.remove(
                                pendingTrampoline.mPendingIntent.getCreatorPackage());
                    }
                };

        @android.annotation.EnforcePermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
        @Override // Binder call
        public IVirtualDevice createVirtualDevice(
                IBinder token,
                String packageName,
                int associationId,
                @NonNull VirtualDeviceParams params,
                @NonNull IVirtualDeviceActivityListener activityListener,
                @NonNull IVirtualDeviceSoundEffectListener soundEffectListener) {
            createVirtualDevice_enforcePermission();
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
            Objects.requireNonNull(params);
            Objects.requireNonNull(activityListener);
            Objects.requireNonNull(soundEffectListener);

            final UserHandle userHandle = getCallingUserHandle();
            final CameraAccessController cameraAccessController =
                    getCameraAccessController(userHandle);
            final int deviceId = sNextUniqueIndex.getAndIncrement();
            final Consumer<ArraySet<Integer>> runningAppsChangedCallback =
                    runningUids -> notifyRunningAppsChanged(deviceId, runningUids);
            VirtualDeviceImpl virtualDevice = new VirtualDeviceImpl(getContext(),
                    associationInfo, VirtualDeviceManagerService.this, token, callingUid,
                    deviceId, cameraAccessController,
                    mPendingTrampolineCallback, activityListener,
                    soundEffectListener, runningAppsChangedCallback, params);
            synchronized (mVirtualDeviceManagerLock) {
                if (mVirtualDevices.size() == 0) {
                    final long callindId = Binder.clearCallingIdentity();
                    try {
                        registerCdmAssociationListener();
                    } finally {
                        Binder.restoreCallingIdentity(callindId);
                    }
                }
                mVirtualDevices.put(deviceId, virtualDevice);
            }
            return virtualDevice;
        }

        @Override // Binder call
        public int createVirtualDisplay(VirtualDisplayConfig virtualDisplayConfig,
                IVirtualDisplayCallback callback, IVirtualDevice virtualDevice, String packageName)
                throws RemoteException {
            Objects.requireNonNull(virtualDisplayConfig);
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

            int displayId = virtualDeviceImpl.createVirtualDisplay(virtualDisplayConfig, callback,
                    packageName);
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
                            new VirtualDevice(device.getDeviceId(), device.getPersistentDeviceId(),
                                    device.getDeviceName()));
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
                return Context.DEVICE_ID_DEFAULT;
            }
            ArrayList<VirtualDeviceImpl> virtualDevicesSnapshot = getVirtualDevicesSnapshot();
            for (int i = 0; i < virtualDevicesSnapshot.size(); i++) {
                VirtualDeviceImpl virtualDevice = virtualDevicesSnapshot.get(i);
                if (virtualDevice.isDisplayOwnedByVirtualDevice(displayId)) {
                    return virtualDevice.getDeviceId();
                }
            }
            return Context.DEVICE_ID_DEFAULT;
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

        @Override // Binder call
        public void playSoundEffect(int deviceId, int effectType) {
            VirtualDeviceImpl virtualDevice;
            synchronized (mVirtualDeviceManagerLock) {
                virtualDevice = mVirtualDevices.get(deviceId);
            }

            if (virtualDevice != null) {
                virtualDevice.playSoundEffect(effectType);
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
            ArrayList<VirtualDeviceImpl> virtualDevicesSnapshot = getVirtualDevicesSnapshot();
            for (int i = 0; i < virtualDevicesSnapshot.size(); i++) {
                virtualDevicesSnapshot.get(i).dump(fd, fout, args);
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
            VirtualDeviceImpl virtualDevice;
            synchronized (mVirtualDeviceManagerLock) {
                virtualDevice = mVirtualDevices.get(deviceId);
            }
            return virtualDevice != null ? virtualDevice.getOwnerUid() : Process.INVALID_UID;
        }

        @Override
        public @Nullable VirtualSensor getVirtualSensor(int deviceId, int handle) {
            VirtualDeviceImpl virtualDevice;
            synchronized (mVirtualDeviceManagerLock) {
                virtualDevice = mVirtualDevices.get(deviceId);
            }
            return virtualDevice != null ? virtualDevice.getVirtualSensorByHandle(handle) : null;
        }

        @Override
        public @NonNull ArraySet<Integer> getDeviceIdsForUid(int uid) {
            ArrayList<VirtualDeviceImpl> virtualDevicesSnapshot = getVirtualDevicesSnapshot();
            ArraySet<Integer> result = new ArraySet<>();
            for (int i = 0; i < virtualDevicesSnapshot.size(); i++) {
                VirtualDeviceImpl device = virtualDevicesSnapshot.get(i);
                if (device.isAppRunningOnVirtualDevice(uid)) {
                    result.add(device.getDeviceId());
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
            VirtualDeviceImpl virtualDeviceImpl;
            synchronized (mVirtualDeviceManagerLock) {
                listeners = mVirtualDisplayListeners.toArray(new VirtualDisplayListener[0]);
                virtualDeviceImpl = mVirtualDevices.get(
                        ((VirtualDeviceImpl) virtualDevice).getDeviceId());
            }
            if (virtualDeviceImpl != null) {
                virtualDeviceImpl.onVirtualDisplayRemoved(displayId);
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
        public void onAuthenticationPrompt(int uid) {
            synchronized (mVirtualDeviceManagerLock) {
                for (int i = 0; i < mVirtualDevices.size(); i++) {
                    VirtualDeviceImpl device = mVirtualDevices.valueAt(i);
                    device.showToastWhereUidIsRunning(uid,
                            R.string.app_streaming_blocked_message_for_fingerprint_dialog,
                            Toast.LENGTH_LONG, Looper.getMainLooper());
                }
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
            ArrayList<VirtualDeviceImpl> virtualDevicesSnapshot = getVirtualDevicesSnapshot();
            for (int i = 0; i < virtualDevicesSnapshot.size(); i++) {
                if (virtualDevicesSnapshot.get(i).isAppRunningOnVirtualDevice(uid)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean isDisplayOwnedByAnyVirtualDevice(int displayId) {
            ArrayList<VirtualDeviceImpl> virtualDevicesSnapshot = getVirtualDevicesSnapshot();
            for (int i = 0; i < virtualDevicesSnapshot.size(); i++) {
                if (virtualDevicesSnapshot.get(i).isDisplayOwnedByVirtualDevice(displayId)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public @NonNull ArraySet<Integer> getDisplayIdsForDevice(int deviceId) {
            VirtualDeviceImpl virtualDevice;
            synchronized (mVirtualDeviceManagerLock) {
                virtualDevice = mVirtualDevices.get(deviceId);
            }
            return virtualDevice == null ? new ArraySet<>() : virtualDevice.getDisplayIds();
        }

        @Override
        public int getAssociationIdForDevice(int deviceId) {
            VirtualDeviceImpl virtualDevice;
            synchronized (mVirtualDeviceManagerLock) {
                virtualDevice = mVirtualDevices.get(deviceId);
            }
            return virtualDevice == null
                    ? VirtualDeviceManager.ASSOCIATION_ID_INVALID
                    : virtualDevice.getAssociationId();
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
