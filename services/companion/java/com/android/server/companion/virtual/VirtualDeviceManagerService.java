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

import static com.android.server.wm.ActivityInterceptorCallback.VIRTUAL_DEVICE_SERVICE_ORDERED_ID;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.ActivityOptions;
import android.companion.AssociationInfo;
import android.companion.CompanionDeviceManager;
import android.companion.CompanionDeviceManager.OnAssociationsChangedListener;
import android.companion.virtual.IVirtualDevice;
import android.companion.virtual.IVirtualDeviceActivityListener;
import android.companion.virtual.IVirtualDeviceManager;
import android.companion.virtual.VirtualDeviceManager;
import android.companion.virtual.VirtualDeviceParams;
import android.content.Context;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.display.IVirtualDisplayCallback;
import android.hardware.display.VirtualDisplayConfig;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.ExceptionUtils;
import android.util.Slog;
import android.util.SparseArray;
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
import java.util.concurrent.ConcurrentHashMap;


@SuppressLint("LongLogTag")
public class VirtualDeviceManagerService extends SystemService {

    private static final boolean DEBUG = false;
    private static final String TAG = "VirtualDeviceManagerService";

    private final Object mVirtualDeviceManagerLock = new Object();
    private final VirtualDeviceManagerImpl mImpl;
    private final VirtualDeviceManagerInternal mLocalService;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final PendingTrampolineMap mPendingTrampolines = new PendingTrampolineMap(mHandler);
    /**
     * Mapping from user IDs to CameraAccessControllers.
     */
    @GuardedBy("mVirtualDeviceManagerLock")
    private final SparseArray<CameraAccessController> mCameraAccessControllers =
            new SparseArray<>();

    /**
     * Mapping from CDM association IDs to virtual devices. Only one virtual device is allowed for
     * each CDM associated device.
     */
    @GuardedBy("mVirtualDeviceManagerLock")
    private final SparseArray<VirtualDeviceImpl> mVirtualDevices = new SparseArray<>();

    /**
     * Mapping from CDM association IDs to app UIDs running on the corresponding virtual device.
     */
    @GuardedBy("mVirtualDeviceManagerLock")
    private final SparseArray<ArraySet<Integer>> mAppsOnVirtualDevices = new SparseArray<>();

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
        mLocalService = new LocalService();
    }

    private final ActivityInterceptorCallback mActivityInterceptorCallback =
            new ActivityInterceptorCallback() {

        @Nullable
        @Override
        public ActivityInterceptResult intercept(ActivityInterceptorInfo info) {
            if (info.callingPackage == null) {
                return null;
            }
            PendingTrampoline pt = mPendingTrampolines.remove(info.callingPackage);
            if (pt == null) {
                return null;
            }
            pt.mResultReceiver.send(VirtualDeviceManager.LAUNCH_SUCCESS, null);
            ActivityOptions options = info.checkedOptions;
            if (options == null) {
                options = ActivityOptions.makeBasic();
            }
            return new ActivityInterceptResult(
                    info.intent, options.setLaunchDisplayId(pt.mDisplayId));
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
        Context userContext = getContext().createContextAsUser(user.getUserHandle(), 0);
        synchronized (mVirtualDeviceManagerLock) {
            final CompanionDeviceManager cdm =
                    userContext.getSystemService(CompanionDeviceManager.class);
            final int userId = user.getUserIdentifier();
            mAllAssociations.put(userId, cdm.getAllAssociations());
            OnAssociationsChangedListener listener =
                    associations -> mAllAssociations.put(userId, associations);
            mOnAssociationsChangedListeners.put(userId, listener);
            cdm.addOnAssociationsChangedListener(Runnable::run, listener);
            CameraAccessController cameraAccessController = new CameraAccessController(
                    userContext, mLocalService, this::onCameraAccessBlocked);
            mCameraAccessControllers.put(user.getUserIdentifier(), cameraAccessController);
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
            CameraAccessController cameraAccessController = mCameraAccessControllers.get(
                    user.getUserIdentifier());
            if (cameraAccessController != null) {
                cameraAccessController.close();
                mCameraAccessControllers.remove(user.getUserIdentifier());
            } else {
                Slog.w(TAG, "Cannot unregister cameraAccessController for user " + user);
            }
        }
    }

    void onCameraAccessBlocked(int appUid) {
        synchronized (mVirtualDeviceManagerLock) {
            int size = mVirtualDevices.size();
            for (int i = 0; i < size; i++) {
                CharSequence deviceName = mVirtualDevices.valueAt(i).getDisplayName();
                mVirtualDevices.valueAt(i).showToastWhereUidIsRunning(appUid,
                        getContext().getString(
                            com.android.internal.R.string.vdm_camera_access_denied,
                            deviceName),
                        Toast.LENGTH_LONG, Looper.myLooper());
            }
        }
    }

    @VisibleForTesting
    VirtualDeviceManagerInternal getLocalServiceInstance() {
        return mLocalService;
    }

    @VisibleForTesting
    void notifyRunningAppsChanged(int associationId, ArraySet<Integer> uids) {
        synchronized (mVirtualDeviceManagerLock) {
            mAppsOnVirtualDevices.put(associationId, uids);
        }
        mLocalService.onAppsOnVirtualDeviceChanged();
    }

    class VirtualDeviceManagerImpl extends IVirtualDeviceManager.Stub implements
            VirtualDeviceImpl.PendingTrampolineCallback {

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
                if (mVirtualDevices.contains(associationId)) {
                    throw new IllegalStateException(
                            "Virtual device for association ID " + associationId
                                    + " already exists");
                }
                final int userId = UserHandle.getUserId(callingUid);
                final CameraAccessController cameraAccessController =
                        mCameraAccessControllers.get(userId);
                VirtualDeviceImpl virtualDevice = new VirtualDeviceImpl(getContext(),
                        associationInfo, token, callingUid,
                        new VirtualDeviceImpl.OnDeviceCloseListener() {
                            @Override
                            public void onClose(int associationId) {
                                synchronized (mVirtualDeviceManagerLock) {
                                    mVirtualDevices.remove(associationId);
                                    mAppsOnVirtualDevices.remove(associationId);
                                    if (cameraAccessController != null) {
                                        cameraAccessController.stopObservingIfNeeded();
                                    } else {
                                        Slog.w(TAG, "cameraAccessController not found for user "
                                                + userId);
                                    }
                                }
                            }
                        },
                        this, activityListener,
                        runningUids -> {
                            cameraAccessController.blockCameraAccessIfNeeded(runningUids);
                            notifyRunningAppsChanged(associationInfo.getId(), runningUids);
                        },
                        params);
                if (cameraAccessController != null) {
                    cameraAccessController.startObservingIfNeeded();
                } else {
                    Slog.w(TAG, "cameraAccessController not found for user " + userId);
                }
                mVirtualDevices.put(associationInfo.getId(), virtualDevice);
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
                virtualDeviceImpl = mVirtualDevices.get(virtualDevice.getAssociationId());
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
                gwpc = virtualDeviceImpl.createWindowPolicyController();
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
            synchronized (mVirtualDeviceManagerLock) {
                for (int i = 0; i < mVirtualDevices.size(); i++) {
                    mVirtualDevices.valueAt(i).dump(fd, fout, args);
                }
            }
        }

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
        public boolean isValidVirtualDevice(IVirtualDevice virtualDevice) {
            synchronized (mVirtualDeviceManagerLock) {
                return isValidVirtualDeviceLocked(virtualDevice);
            }
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
        public boolean isAppOwnerOfAnyVirtualDevice(int uid) {
            synchronized (mVirtualDeviceManagerLock) {
                int size = mVirtualDevices.size();
                for (int i = 0; i < size; i++) {
                    if (mVirtualDevices.valueAt(i).getOwnerUid() == uid) {
                        return true;
                    }
                }
                return false;
            }
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
