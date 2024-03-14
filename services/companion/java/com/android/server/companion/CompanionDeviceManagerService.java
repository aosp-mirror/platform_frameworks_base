/*
 * Copyright (C) 2017 The Android Open Source Project
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


package com.android.server.companion;

import static android.Manifest.permission.ASSOCIATE_COMPANION_DEVICES;
import static android.Manifest.permission.DELIVER_COMPANION_MESSAGES;
import static android.Manifest.permission.MANAGE_COMPANION_DEVICES;
import static android.Manifest.permission.REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE;
import static android.Manifest.permission.USE_COMPANION_TRANSPORTS;
import static android.companion.AssociationRequest.DEVICE_PROFILE_AUTOMOTIVE_PROJECTION;
import static android.companion.DevicePresenceEvent.EVENT_BLE_APPEARED;
import static android.companion.DevicePresenceEvent.EVENT_BLE_DISAPPEARED;
import static android.companion.DevicePresenceEvent.EVENT_BT_CONNECTED;
import static android.companion.DevicePresenceEvent.EVENT_BT_DISCONNECTED;
import static android.companion.DevicePresenceEvent.EVENT_SELF_MANAGED_APPEARED;
import static android.companion.DevicePresenceEvent.EVENT_SELF_MANAGED_DISAPPEARED;
import static android.content.pm.PackageManager.CERT_INPUT_SHA256;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Process.SYSTEM_UID;
import static android.os.UserHandle.getCallingUserId;

import static com.android.internal.util.CollectionUtils.any;
import static com.android.internal.util.Preconditions.checkState;
import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;
import static com.android.server.companion.utils.PackageUtils.enforceUsesCompanionDeviceFeature;
import static com.android.server.companion.utils.PackageUtils.getPackageInfo;
import static com.android.server.companion.utils.PackageUtils.isRestrictedSettingsAllowed;
import static com.android.server.companion.utils.PermissionsUtils.checkCallerCanManageCompanionDevice;
import static com.android.server.companion.utils.PermissionsUtils.enforceCallerCanManageAssociationsForPackage;
import static com.android.server.companion.utils.PermissionsUtils.enforceCallerCanObservingDevicePresenceByUuid;
import static com.android.server.companion.utils.PermissionsUtils.enforceCallerIsSystemOr;
import static com.android.server.companion.utils.PermissionsUtils.enforceCallerIsSystemOrCanInteractWithUserId;
import static com.android.server.companion.utils.PermissionsUtils.sanitizeWithCallerChecks;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.MINUTES;

import android.annotation.EnforcePermission;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.DeviceNotAssociatedException;
import android.companion.IAssociationRequestCallback;
import android.companion.ICompanionDeviceManager;
import android.companion.IOnAssociationsChangedListener;
import android.companion.IOnMessageReceivedListener;
import android.companion.IOnTransportsChangedListener;
import android.companion.ISystemDataTransferCallback;
import android.companion.ObservingDevicePresenceRequest;
import android.companion.datatransfer.PermissionSyncRequest;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.hardware.power.Mode;
import android.net.MacAddress;
import android.net.NetworkPolicyManager;
import android.os.Binder;
import android.os.Environment;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.ParcelUuid;
import android.os.PowerExemptionManager;
import android.os.PowerManagerInternal;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArraySet;
import android.util.ExceptionUtils;
import android.util.Log;
import android.util.Slog;

import com.android.internal.app.IAppOpsService;
import com.android.internal.content.PackageMonitor;
import com.android.internal.notification.NotificationAccessConfirmationActivityContract;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.companion.association.AssociationDiskStore;
import com.android.server.companion.association.AssociationRequestsProcessor;
import com.android.server.companion.association.AssociationStore;
import com.android.server.companion.association.DisassociationProcessor;
import com.android.server.companion.association.InactiveAssociationsRemovalService;
import com.android.server.companion.datatransfer.SystemDataTransferProcessor;
import com.android.server.companion.datatransfer.SystemDataTransferRequestStore;
import com.android.server.companion.datatransfer.contextsync.CrossDeviceCall;
import com.android.server.companion.datatransfer.contextsync.CrossDeviceSyncController;
import com.android.server.companion.datatransfer.contextsync.CrossDeviceSyncControllerCallback;
import com.android.server.companion.presence.CompanionDevicePresenceMonitor;
import com.android.server.companion.presence.ObservableUuid;
import com.android.server.companion.presence.ObservableUuidStore;
import com.android.server.companion.transport.CompanionTransportManager;
import com.android.server.pm.UserManagerInternal;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SuppressLint("LongLogTag")
public class CompanionDeviceManagerService extends SystemService {
    static final String TAG = "CDM_CompanionDeviceManagerService";
    static final boolean DEBUG = false;

    private static final long PAIR_WITHOUT_PROMPT_WINDOW_MS = 10 * 60 * 1000; // 10 min

    private static final String PREF_FILE_NAME = "companion_device_preferences.xml";
    private static final String PREF_KEY_AUTO_REVOKE_GRANTS_DONE = "auto_revoke_grants_done";
    private static final String SYS_PROP_DEBUG_REMOVAL_TIME_WINDOW =
            "debug.cdm.cdmservice.removal_time_window";

    private static final long ASSOCIATION_REMOVAL_TIME_WINDOW_DEFAULT = DAYS.toMillis(90);
    private static final int MAX_CN_LENGTH = 500;

    private final ActivityTaskManagerInternal mAtmInternal;
    private final ActivityManagerInternal mAmInternal;
    private final IAppOpsService mAppOpsManager;
    private final PowerExemptionManager mPowerExemptionManager;
    private final PackageManagerInternal mPackageManagerInternal;
    private final PowerManagerInternal mPowerManagerInternal;

    private final AssociationStore mAssociationStore;
    private final SystemDataTransferRequestStore mSystemDataTransferRequestStore;
    private final ObservableUuidStore mObservableUuidStore;
    private final AssociationRequestsProcessor mAssociationRequestsProcessor;
    private final SystemDataTransferProcessor mSystemDataTransferProcessor;
    private final BackupRestoreProcessor mBackupRestoreProcessor;
    private final CompanionDevicePresenceMonitor mDevicePresenceMonitor;
    private final CompanionApplicationController mCompanionAppController;
    private final CompanionTransportManager mTransportManager;
    private final DisassociationProcessor mDisassociationProcessor;
    private final CrossDeviceSyncController mCrossDeviceSyncController;

    public CompanionDeviceManagerService(Context context) {
        super(context);

        final ActivityManager activityManager = context.getSystemService(ActivityManager.class);
        mPowerExemptionManager = context.getSystemService(PowerExemptionManager.class);
        mAppOpsManager = IAppOpsService.Stub.asInterface(
                ServiceManager.getService(Context.APP_OPS_SERVICE));
        mAtmInternal = LocalServices.getService(ActivityTaskManagerInternal.class);
        mAmInternal = LocalServices.getService(ActivityManagerInternal.class);
        mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
        final UserManager userManager = context.getSystemService(UserManager.class);
        mPowerManagerInternal = LocalServices.getService(PowerManagerInternal.class);

        final AssociationDiskStore associationDiskStore = new AssociationDiskStore();
        mAssociationStore = new AssociationStore(userManager, associationDiskStore);
        mSystemDataTransferRequestStore = new SystemDataTransferRequestStore();
        mObservableUuidStore = new ObservableUuidStore();

        // Init processors
        mAssociationRequestsProcessor = new AssociationRequestsProcessor(context,
                mPackageManagerInternal, mAssociationStore);
        mBackupRestoreProcessor = new BackupRestoreProcessor(context, mPackageManagerInternal,
                mAssociationStore, associationDiskStore, mSystemDataTransferRequestStore,
                mAssociationRequestsProcessor);

        mDevicePresenceMonitor = new CompanionDevicePresenceMonitor(userManager,
                mAssociationStore, mObservableUuidStore, mDevicePresenceCallback);

        mCompanionAppController = new CompanionApplicationController(
                context, mAssociationStore, mObservableUuidStore, mDevicePresenceMonitor,
                mPowerManagerInternal);

        mTransportManager = new CompanionTransportManager(context, mAssociationStore);

        mDisassociationProcessor = new DisassociationProcessor(context, activityManager,
                mAssociationStore, mPackageManagerInternal, mDevicePresenceMonitor,
                mCompanionAppController, mSystemDataTransferRequestStore, mTransportManager);

        mSystemDataTransferProcessor = new SystemDataTransferProcessor(this,
                mPackageManagerInternal, mAssociationStore,
                mSystemDataTransferRequestStore, mTransportManager);

        // TODO(b/279663946): move context sync to a dedicated system service
        mCrossDeviceSyncController = new CrossDeviceSyncController(getContext(), mTransportManager);
    }

    @Override
    public void onStart() {
        // Init association stores
        mAssociationStore.refreshCache();
        mAssociationStore.registerLocalListener(mAssociationStoreChangeListener);

        // Init UUID store
        mObservableUuidStore.getObservableUuidsForUser(getContext().getUserId());

        // Publish "binder" service.
        final CompanionDeviceManagerImpl impl = new CompanionDeviceManagerImpl();
        publishBinderService(Context.COMPANION_DEVICE_SERVICE, impl);

        // Publish "local" service.
        LocalServices.addService(CompanionDeviceManagerServiceInternal.class, new LocalService());
    }

    @Override
    public void onBootPhase(int phase) {
        final Context context = getContext();
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            // WARNING: moving PackageMonitor to another thread (Looper) may introduce significant
            // delays (even in case of the Main Thread). It may be fine overall, but would require
            // updating the tests (adding a delay there).
            mPackageMonitor.register(context, FgThread.get().getLooper(), UserHandle.ALL, true);
            mDevicePresenceMonitor.init(context);
        } else if (phase == PHASE_BOOT_COMPLETED) {
            // Run the Inactive Association Removal job service daily.
            InactiveAssociationsRemovalService.schedule(getContext());
            mCrossDeviceSyncController.onBootCompleted();
        }
    }

    @Override
    public void onUserUnlocking(@NonNull TargetUser user) {
        Slog.d(TAG, "onUserUnlocking...");
        final int userId = user.getUserIdentifier();
        final List<AssociationInfo> associations = mAssociationStore.getActiveAssociationsByUser(
                userId);

        if (associations.isEmpty()) return;

        updateAtm(userId, associations);

        BackgroundThread.getHandler().sendMessageDelayed(
                obtainMessage(CompanionDeviceManagerService::maybeGrantAutoRevokeExemptions, this),
                MINUTES.toMillis(10));
    }

    @Override
    public void onUserUnlocked(@NonNull TargetUser user) {
        // Notify and bind the app after the phone is unlocked.
        final int userId = user.getUserIdentifier();
        final Set<BluetoothDevice> blueToothDevices =
                mDevicePresenceMonitor.getPendingConnectedDevices().get(userId);

        final List<ObservableUuid> observableUuids =
                mObservableUuidStore.getObservableUuidsForUser(userId);

        if (blueToothDevices != null) {
            for (BluetoothDevice bluetoothDevice : blueToothDevices) {
                final ParcelUuid[] bluetoothDeviceUuids = bluetoothDevice.getUuids();

                final List<ParcelUuid> deviceUuids = ArrayUtils.isEmpty(bluetoothDeviceUuids)
                        ? Collections.emptyList() : Arrays.asList(bluetoothDeviceUuids);

                for (AssociationInfo ai :
                        mAssociationStore.getActiveAssociationsByAddress(
                                bluetoothDevice.getAddress())) {
                    Slog.i(TAG, "onUserUnlocked, device id( " + ai.getId() + " ) is connected");
                    mDevicePresenceMonitor.onBluetoothCompanionDeviceConnected(ai.getId());
                }

                for (ObservableUuid observableUuid : observableUuids) {
                    if (deviceUuids.contains(observableUuid.getUuid())) {
                        Slog.i(TAG, "onUserUnlocked, UUID( "
                                + observableUuid.getUuid() + " ) is connected");
                        mDevicePresenceMonitor.onDevicePresenceEventByUuid(
                                observableUuid, EVENT_BT_CONNECTED);
                    }
                }
            }
        }
    }

    @NonNull
    AssociationInfo getAssociationWithCallerChecks(
            @UserIdInt int userId, @NonNull String packageName, @NonNull String macAddress) {
        AssociationInfo association = mAssociationStore.getFirstAssociationByAddress(
                userId, packageName, macAddress);
        association = sanitizeWithCallerChecks(getContext(), association);
        if (association != null) {
            return association;
        } else {
            throw new IllegalArgumentException("Association does not exist "
                    + "or the caller does not have permissions to manage it "
                    + "(ie. it belongs to a different package or a different user).");
        }
    }

    @NonNull
    AssociationInfo getAssociationWithCallerChecks(int associationId) {
        AssociationInfo association = mAssociationStore.getAssociationById(associationId);
        association = sanitizeWithCallerChecks(getContext(), association);
        if (association != null) {
            return association;
        } else {
            throw new IllegalArgumentException("Association does not exist "
                    + "or the caller does not have permissions to manage it "
                    + "(ie. it belongs to a different package or a different user).");
        }
    }

    private void onDeviceAppearedInternal(int associationId) {
        if (DEBUG) Log.i(TAG, "onDevice_Appeared_Internal() id=" + associationId);

        final AssociationInfo association = mAssociationStore.getAssociationById(associationId);
        if (DEBUG) Log.d(TAG, "  association=" + association);

        if (!association.shouldBindWhenPresent()) return;

        bindApplicationIfNeeded(association);

        mCompanionAppController.notifyCompanionApplicationDeviceAppeared(association);
    }

    private void onDeviceDisappearedInternal(int associationId) {
        if (DEBUG) Log.i(TAG, "onDevice_Disappeared_Internal() id=" + associationId);

        final AssociationInfo association = mAssociationStore.getAssociationById(associationId);
        if (DEBUG) Log.d(TAG, "  association=" + association);

        final int userId = association.getUserId();
        final String packageName = association.getPackageName();

        if (!mCompanionAppController.isCompanionApplicationBound(userId, packageName)) {
            if (DEBUG) Log.w(TAG, "u" + userId + "\\" + packageName + " is NOT bound");
            return;
        }

        if (association.shouldBindWhenPresent()) {
            mCompanionAppController.notifyCompanionApplicationDeviceDisappeared(association);
        }
    }

    private void onDevicePresenceEventInternal(int associationId, int event) {
        Slog.i(TAG, "onDevicePresenceEventInternal() id=" + associationId + " event= " + event);
        final AssociationInfo association = mAssociationStore.getAssociationById(associationId);
        final String packageName = association.getPackageName();
        final int userId = association.getUserId();
        switch (event) {
            case EVENT_BLE_APPEARED:
            case EVENT_BT_CONNECTED:
            case EVENT_SELF_MANAGED_APPEARED:
                if (!association.shouldBindWhenPresent()) return;

                bindApplicationIfNeeded(association);

                mCompanionAppController.notifyCompanionDevicePresenceEvent(
                        association, event);
                break;
            case EVENT_BLE_DISAPPEARED:
            case EVENT_BT_DISCONNECTED:
            case EVENT_SELF_MANAGED_DISAPPEARED:
                if (!mCompanionAppController.isCompanionApplicationBound(userId, packageName)) {
                    if (DEBUG) Log.w(TAG, "u" + userId + "\\" + packageName + " is NOT bound");
                    return;
                }
                if (association.shouldBindWhenPresent()) {
                    mCompanionAppController.notifyCompanionDevicePresenceEvent(
                            association, event);
                }
                // Check if there are other devices associated to the app that are present.
                if (shouldBindPackage(userId, packageName)) return;
                mCompanionAppController.unbindCompanionApplication(userId, packageName);
                break;
            default:
                Slog.e(TAG, "Event: " + event + "is not supported");
                break;
        }
    }

    private void onDevicePresenceEventByUuidInternal(ObservableUuid uuid, int event) {
        Slog.i(TAG, "onDevicePresenceEventByUuidInternal() id=" + uuid.getUuid()
                + "for package=" + uuid.getPackageName() + " event=" + event);
        final String packageName = uuid.getPackageName();
        final int userId = uuid.getUserId();

        switch (event) {
            case EVENT_BT_CONNECTED:
                if (!mCompanionAppController.isCompanionApplicationBound(userId, packageName)) {
                    mCompanionAppController.bindCompanionApplication(
                            userId, packageName, /*bindImportant*/ false);

                } else if (DEBUG) {
                    Log.i(TAG, "u" + userId + "\\" + packageName + " is already bound");
                }

                mCompanionAppController.notifyUuidDevicePresenceEvent(uuid, event);

                break;
            case EVENT_BT_DISCONNECTED:
                if (!mCompanionAppController.isCompanionApplicationBound(userId, packageName)) {
                    if (DEBUG) Log.w(TAG, "u" + userId + "\\" + packageName + " is NOT bound");
                    return;
                }

                mCompanionAppController.notifyUuidDevicePresenceEvent(uuid, event);
                // Check if there are other devices associated to the app or the UUID to be
                // observed are present.
                if (shouldBindPackage(userId, packageName)) return;

                mCompanionAppController.unbindCompanionApplication(userId, packageName);

                break;
            default:
                Slog.e(TAG, "Event: " + event + "is not supported");
                break;
        }
    }

    private void bindApplicationIfNeeded(AssociationInfo association) {
        final String packageName = association.getPackageName();
        final int userId = association.getUserId();
        // Set bindImportant to true when the association is self-managed to avoid the target
        // service being killed.
        final boolean bindImportant = association.isSelfManaged();
        if (!mCompanionAppController.isCompanionApplicationBound(userId, packageName)) {
            mCompanionAppController.bindCompanionApplication(
                    userId, packageName, bindImportant);
        } else if (DEBUG) {
            Log.i(TAG, "u" + userId + "\\" + packageName + " is already bound");
        }
    }

    /**
     * @return whether the package should be bound (i.e. at least one of the devices associated with
     * the package is currently present OR the UUID to be observed by this package is
     * currently present).
     */
    private boolean shouldBindPackage(@UserIdInt int userId, @NonNull String packageName) {
        final List<AssociationInfo> packageAssociations =
                mAssociationStore.getActiveAssociationsByPackage(userId, packageName);
        final List<ObservableUuid> observableUuids =
                mObservableUuidStore.getObservableUuidsForPackage(userId, packageName);

        for (AssociationInfo association : packageAssociations) {
            if (!association.shouldBindWhenPresent()) continue;
            if (mDevicePresenceMonitor.isDevicePresent(association.getId())) return true;
        }

        for (ObservableUuid uuid : observableUuids) {
            if (mDevicePresenceMonitor.isDeviceUuidPresent(uuid.getUuid())) {
                return true;
            }
        }

        return false;
    }

    private void onPackageRemoveOrDataClearedInternal(
            @UserIdInt int userId, @NonNull String packageName) {
        if (DEBUG) {
            Log.i(TAG, "onPackageRemove_Or_DataCleared() u" + userId + "/"
                    + packageName);
        }

        // Clear all associations for the package.
        final List<AssociationInfo> associationsForPackage =
                mAssociationStore.getAssociationsByPackage(userId, packageName);
        if (!associationsForPackage.isEmpty()) {
            Slog.i(TAG, "Package removed or data cleared for user=[" + userId + "], package=["
                    + packageName + "]. Cleaning up CDM data...");
        }
        for (AssociationInfo association : associationsForPackage) {
            mDisassociationProcessor.disassociate(association.getId());
        }

        // Clear observable UUIDs for the package.
        final List<ObservableUuid> uuidsTobeObserved =
                mObservableUuidStore.getObservableUuidsForPackage(userId, packageName);
        for (ObservableUuid uuid : uuidsTobeObserved) {
            mObservableUuidStore.removeObservableUuid(userId, uuid.getUuid(), packageName);
        }

        mCompanionAppController.onPackagesChanged(userId);
    }

    private void onPackageModifiedInternal(@UserIdInt int userId, @NonNull String packageName) {
        if (DEBUG) Log.i(TAG, "onPackageModified() u" + userId + "/" + packageName);

        updateSpecialAccessPermissionForAssociatedPackage(userId, packageName);

        mCompanionAppController.onPackagesChanged(userId);
    }

    private void onPackageAddedInternal(@UserIdInt int userId, @NonNull String packageName) {
        mBackupRestoreProcessor.restorePendingAssociations(userId, packageName);
    }

    // Revoke associations if the selfManaged companion device does not connect for 3 months.
    void removeInactiveSelfManagedAssociations() {
        final long currentTime = System.currentTimeMillis();
        long removalWindow = SystemProperties.getLong(SYS_PROP_DEBUG_REMOVAL_TIME_WINDOW, -1);
        if (removalWindow <= 0) {
            // 0 or negative values indicate that the sysprop was never set or should be ignored.
            removalWindow = ASSOCIATION_REMOVAL_TIME_WINDOW_DEFAULT;
        }

        for (AssociationInfo association : mAssociationStore.getAssociations()) {
            if (!association.isSelfManaged()) continue;

            final boolean isInactive =
                    currentTime - association.getLastTimeConnectedMs() >= removalWindow;
            if (!isInactive) continue;

            final int id = association.getId();

            Slog.i(TAG, "Removing inactive self-managed association id=" + id);
            mDisassociationProcessor.disassociate(id);
        }
    }

    public class CompanionDeviceManagerImpl extends ICompanionDeviceManager.Stub {
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
        public void associate(AssociationRequest request, IAssociationRequestCallback callback,
                String packageName, int userId) throws RemoteException {
            Slog.i(TAG, "associate() "
                    + "request=" + request + ", "
                    + "package=u" + userId + "/" + packageName);
            enforceCallerCanManageAssociationsForPackage(getContext(), userId, packageName,
                    "create associations");

            mAssociationRequestsProcessor.processNewAssociationRequest(
                    request, packageName, userId, callback);
        }

        @Override
        public PendingIntent buildAssociationCancellationIntent(String packageName,
                int userId) throws RemoteException {
            Slog.i(TAG, "buildAssociationCancellationIntent() "
                    + "package=u" + userId + "/" + packageName);
            enforceCallerCanManageAssociationsForPackage(getContext(), userId, packageName,
                    "build association cancellation intent");

            return mAssociationRequestsProcessor.buildAssociationCancellationIntent(
                    packageName, userId);
        }

        @Override
        public List<AssociationInfo> getAssociations(String packageName, int userId) {
            enforceCallerCanManageAssociationsForPackage(getContext(), userId, packageName,
                    "get associations");

            if (!checkCallerCanManageCompanionDevice(getContext())) {
                // If the caller neither is system nor holds MANAGE_COMPANION_DEVICES: it needs to
                // request the feature (also: the caller is the app itself).
                enforceUsesCompanionDeviceFeature(getContext(), userId, packageName);
            }

            return mAssociationStore.getActiveAssociationsByPackage(userId, packageName);
        }

        @Override
        @EnforcePermission(MANAGE_COMPANION_DEVICES)
        public List<AssociationInfo> getAllAssociationsForUser(int userId) throws RemoteException {
            getAllAssociationsForUser_enforcePermission();

            enforceCallerIsSystemOrCanInteractWithUserId(getContext(), userId);

            if (userId == UserHandle.USER_ALL) {
                return mAssociationStore.getActiveAssociations();
            }
            return mAssociationStore.getActiveAssociationsByUser(userId);
        }

        @Override
        @EnforcePermission(MANAGE_COMPANION_DEVICES)
        public void addOnAssociationsChangedListener(IOnAssociationsChangedListener listener,
                int userId) {
            addOnAssociationsChangedListener_enforcePermission();

            enforceCallerIsSystemOrCanInteractWithUserId(getContext(), userId);

            mAssociationStore.registerRemoteListener(listener, userId);
        }

        @Override
        @EnforcePermission(MANAGE_COMPANION_DEVICES)
        public void removeOnAssociationsChangedListener(IOnAssociationsChangedListener listener,
                int userId) {
            removeOnAssociationsChangedListener_enforcePermission();

            enforceCallerIsSystemOrCanInteractWithUserId(getContext(), userId);

            mAssociationStore.unregisterRemoteListener(listener);
        }

        @Override
        @EnforcePermission(USE_COMPANION_TRANSPORTS)
        public void addOnTransportsChangedListener(IOnTransportsChangedListener listener) {
            addOnTransportsChangedListener_enforcePermission();

            mTransportManager.addListener(listener);
        }

        @Override
        @EnforcePermission(USE_COMPANION_TRANSPORTS)
        public void removeOnTransportsChangedListener(IOnTransportsChangedListener listener) {
            removeOnTransportsChangedListener_enforcePermission();

            mTransportManager.removeListener(listener);
        }

        @Override
        @EnforcePermission(USE_COMPANION_TRANSPORTS)
        public void sendMessage(int messageType, byte[] data, int[] associationIds) {
            sendMessage_enforcePermission();

            mTransportManager.sendMessage(messageType, data, associationIds);
        }

        @Override
        @EnforcePermission(USE_COMPANION_TRANSPORTS)
        public void addOnMessageReceivedListener(int messageType,
                IOnMessageReceivedListener listener) {
            addOnMessageReceivedListener_enforcePermission();

            mTransportManager.addListener(messageType, listener);
        }

        @Override
        @EnforcePermission(USE_COMPANION_TRANSPORTS)
        public void removeOnMessageReceivedListener(int messageType,
                IOnMessageReceivedListener listener) {
            removeOnMessageReceivedListener_enforcePermission();

            mTransportManager.removeListener(messageType, listener);
        }

        /**
         * @deprecated use {@link #disassociate(int)} instead
         */
        @Deprecated
        @Override
        public void legacyDisassociate(String deviceMacAddress, String packageName, int userId) {
            Log.i(TAG, "legacyDisassociate() pkg=u" + userId + "/" + packageName
                    + ", macAddress=" + deviceMacAddress);

            requireNonNull(deviceMacAddress);
            requireNonNull(packageName);

            final AssociationInfo association =
                    getAssociationWithCallerChecks(userId, packageName, deviceMacAddress);
            mDisassociationProcessor.disassociate(association.getId());
        }

        @Override
        public void disassociate(int associationId) {
            Slog.i(TAG, "disassociate() associationId=" + associationId);

            final AssociationInfo association =
                    getAssociationWithCallerChecks(associationId);
            mDisassociationProcessor.disassociate(association.getId());
        }

        @Override
        public PendingIntent requestNotificationAccess(ComponentName component, int userId)
                throws RemoteException {
            int callingUid = getCallingUid();
            final String callingPackage = component.getPackageName();

            checkCanCallNotificationApi(callingPackage, userId);

            if (component.flattenToString().length() > MAX_CN_LENGTH) {
                throw new IllegalArgumentException("Component name is too long.");
            }

            return Binder.withCleanCallingIdentity(() -> {
                if (!isRestrictedSettingsAllowed(getContext(), callingPackage, callingUid)) {
                    Slog.e(TAG, "Side loaded app must enable restricted "
                            + "setting before request the notification access");
                    return null;
                }
                return PendingIntent.getActivityAsUser(getContext(),
                        0 /* request code */,
                        NotificationAccessConfirmationActivityContract.launcherIntent(
                                getContext(), userId, component),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT
                                | PendingIntent.FLAG_CANCEL_CURRENT,
                        null /* options */,
                        new UserHandle(userId));
            });
        }

        /**
         * @deprecated Use
         * {@link NotificationManager#isNotificationListenerAccessGranted(ComponentName)} instead.
         */
        @Deprecated
        @Override
        public boolean hasNotificationAccess(ComponentName component) throws RemoteException {
            checkCanCallNotificationApi(component.getPackageName(), getCallingUserId());
            NotificationManager nm = getContext().getSystemService(NotificationManager.class);
            return nm.isNotificationListenerAccessGranted(component);
        }

        @Override
        @EnforcePermission(MANAGE_COMPANION_DEVICES)
        public boolean isDeviceAssociatedForWifiConnection(String packageName, String macAddress,
                int userId) {
            isDeviceAssociatedForWifiConnection_enforcePermission();

            boolean bypassMacPermission = getContext().getPackageManager().checkPermission(
                    android.Manifest.permission.COMPANION_APPROVE_WIFI_CONNECTIONS, packageName)
                    == PERMISSION_GRANTED;
            if (bypassMacPermission) {
                return true;
            }

            return any(mAssociationStore.getActiveAssociationsByPackage(userId, packageName),
                    a -> a.isLinkedTo(macAddress));
        }

        @Override
        @EnforcePermission(REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE)
        public void registerDevicePresenceListenerService(String deviceAddress,
                String callingPackage, int userId) throws RemoteException {
            registerDevicePresenceListenerService_enforcePermission();
            // TODO: take the userId into account.
            registerDevicePresenceListenerActive(callingPackage, deviceAddress, true);
        }

        @Override
        @EnforcePermission(REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE)
        public void unregisterDevicePresenceListenerService(String deviceAddress,
                String callingPackage, int userId) throws RemoteException {
            unregisterDevicePresenceListenerService_enforcePermission();
            // TODO: take the userId into account.
            registerDevicePresenceListenerActive(callingPackage, deviceAddress, false);
        }

        @Override
        @EnforcePermission(REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE)
        public void startObservingDevicePresence(ObservingDevicePresenceRequest request,
                String packageName, int userId) {
            startObservingDevicePresence_enforcePermission();
            registerDevicePresenceListener(request, packageName, userId, /* active */ true);
        }

        @Override
        @EnforcePermission(REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE)
        public void stopObservingDevicePresence(ObservingDevicePresenceRequest request,
                String packageName, int userId) {
            stopObservingDevicePresence_enforcePermission();
            registerDevicePresenceListener(request, packageName, userId, /* active */ false);
        }

        private void registerDevicePresenceListener(ObservingDevicePresenceRequest request,
                String packageName, int userId, boolean active) {
            enforceUsesCompanionDeviceFeature(getContext(), userId, packageName);
            enforceCallerIsSystemOr(userId, packageName);

            final int associationId = request.getAssociationId();
            final AssociationInfo associationInfo = mAssociationStore.getAssociationById(
                    associationId);
            final ParcelUuid uuid = request.getUuid();

            if (uuid != null) {
                enforceCallerCanObservingDevicePresenceByUuid(getContext());
                if (active) {
                    startObservingDevicePresenceByUuid(uuid, packageName, userId);
                } else {
                    stopObservingDevicePresenceByUuid(uuid, packageName, userId);
                }
            } else if (associationInfo == null) {
                throw new IllegalArgumentException("App " + packageName
                        + " is not associated with device " + request.getAssociationId()
                        + " for user " + userId);
            } else {
                processDevicePresenceListener(
                        associationInfo, userId, packageName, active);
            }
        }

        private void startObservingDevicePresenceByUuid(ParcelUuid uuid, String packageName,
                int userId) {
            final List<ObservableUuid> observableUuids =
                    mObservableUuidStore.getObservableUuidsForPackage(userId, packageName);

            for (ObservableUuid observableUuid : observableUuids) {
                if (observableUuid.getUuid().equals(uuid)) {
                    Slog.i(TAG, "The uuid: " + uuid + " for package:" + packageName
                            + "has been already scheduled for observing");
                    return;
                }
            }

            final ObservableUuid observableUuid = new ObservableUuid(userId, uuid,
                    packageName, System.currentTimeMillis());

            mObservableUuidStore.writeObservableUuid(userId, observableUuid);
        }

        private void stopObservingDevicePresenceByUuid(ParcelUuid uuid, String packageName,
                int userId) {
            final List<ObservableUuid> uuidsTobeObserved =
                    mObservableUuidStore.getObservableUuidsForPackage(userId, packageName);
            boolean isScheduledObserving = false;

            for (ObservableUuid observableUuid : uuidsTobeObserved) {
                if (observableUuid.getUuid().equals(uuid)) {
                    isScheduledObserving = true;
                    break;
                }
            }

            if (!isScheduledObserving) {
                Slog.i(TAG, "The uuid: " + uuid.toString() + " for package:" + packageName
                        + "has NOT been scheduled for observing yet");
                return;
            }

            mObservableUuidStore.removeObservableUuid(userId, uuid, packageName);
            mDevicePresenceMonitor.removeCurrentConnectedUuidDevice(uuid);

            if (!shouldBindPackage(userId, packageName)) {
                mCompanionAppController.unbindCompanionApplication(userId, packageName);
            }
        }

        @Override
        public PendingIntent buildPermissionTransferUserConsentIntent(String packageName,
                int userId, int associationId) {
            return mSystemDataTransferProcessor.buildPermissionTransferUserConsentIntent(
                    packageName, userId, associationId);
        }

        @Override
        public boolean isPermissionTransferUserConsented(String packageName, int userId,
                int associationId) {
            return mSystemDataTransferProcessor.isPermissionTransferUserConsented(packageName,
                    userId, associationId);
        }

        @Override
        public void startSystemDataTransfer(String packageName, int userId, int associationId,
                ISystemDataTransferCallback callback) {
            mSystemDataTransferProcessor.startSystemDataTransfer(packageName, userId,
                    associationId, callback);
        }

        @Override
        @EnforcePermission(DELIVER_COMPANION_MESSAGES)
        public void attachSystemDataTransport(String packageName, int userId, int associationId,
                ParcelFileDescriptor fd) {
            attachSystemDataTransport_enforcePermission();

            getAssociationWithCallerChecks(associationId);
            mTransportManager.attachSystemDataTransport(packageName, userId, associationId, fd);
        }

        @Override
        @EnforcePermission(DELIVER_COMPANION_MESSAGES)
        public void detachSystemDataTransport(String packageName, int userId, int associationId) {
            detachSystemDataTransport_enforcePermission();

            getAssociationWithCallerChecks(associationId);
            mTransportManager.detachSystemDataTransport(packageName, userId, associationId);
        }

        @Override
        public void enableSystemDataSync(int associationId, int flags) {
            getAssociationWithCallerChecks(associationId);
            mAssociationRequestsProcessor.enableSystemDataSync(associationId, flags);
        }

        @Override
        public void disableSystemDataSync(int associationId, int flags) {
            getAssociationWithCallerChecks(associationId);
            mAssociationRequestsProcessor.disableSystemDataSync(associationId, flags);
        }

        @Override
        public void enablePermissionsSync(int associationId) {
            getAssociationWithCallerChecks(associationId);
            mSystemDataTransferProcessor.enablePermissionsSync(associationId);
        }

        @Override
        public void disablePermissionsSync(int associationId) {
            getAssociationWithCallerChecks(associationId);
            mSystemDataTransferProcessor.disablePermissionsSync(associationId);
        }

        @Override
        public PermissionSyncRequest getPermissionSyncRequest(int associationId) {
            // TODO: temporary fix, will remove soon
            AssociationInfo association = mAssociationStore.getAssociationById(associationId);
            if (association == null) {
                return null;
            }
            getAssociationWithCallerChecks(associationId);
            return mSystemDataTransferProcessor.getPermissionSyncRequest(associationId);
        }

        @Override
        @EnforcePermission(MANAGE_COMPANION_DEVICES)
        public void enableSecureTransport(boolean enabled) {
            enableSecureTransport_enforcePermission();
            mTransportManager.enableSecureTransport(enabled);
        }

        @Override
        public void notifyDeviceAppeared(int associationId) {
            if (DEBUG) Log.i(TAG, "notifyDevice_Appeared() id=" + associationId);

            AssociationInfo association = getAssociationWithCallerChecks(associationId);
            if (!association.isSelfManaged()) {
                throw new IllegalArgumentException("Association with ID " + associationId
                        + " is not self-managed. notifyDeviceAppeared(int) can only be called for"
                        + " self-managed associations.");
            }
            // AssociationInfo class is immutable: create a new AssociationInfo object with updated
            // timestamp.
            association = (new AssociationInfo.Builder(association))
                    .setLastTimeConnected(System.currentTimeMillis())
                    .build();
            mAssociationStore.updateAssociation(association);

            mDevicePresenceMonitor.onSelfManagedDeviceConnected(associationId);

            final String deviceProfile = association.getDeviceProfile();
            if (DEVICE_PROFILE_AUTOMOTIVE_PROJECTION.equals(deviceProfile)) {
                Slog.i(TAG, "Enable hint mode for device device profile: " + deviceProfile);
                mPowerManagerInternal.setPowerMode(Mode.AUTOMOTIVE_PROJECTION, true);
            }
        }

        @Override
        public void notifyDeviceDisappeared(int associationId) {
            if (DEBUG) Log.i(TAG, "notifyDevice_Disappeared() id=" + associationId);

            final AssociationInfo association = getAssociationWithCallerChecks(associationId);
            if (!association.isSelfManaged()) {
                throw new IllegalArgumentException("Association with ID " + associationId
                        + " is not self-managed. notifyDeviceAppeared(int) can only be called for"
                        + " self-managed associations.");
            }

            mDevicePresenceMonitor.onSelfManagedDeviceDisconnected(associationId);

            final String deviceProfile = association.getDeviceProfile();
            if (DEVICE_PROFILE_AUTOMOTIVE_PROJECTION.equals(deviceProfile)) {
                Slog.i(TAG, "Disable hint mode for device profile: " + deviceProfile);
                mPowerManagerInternal.setPowerMode(Mode.AUTOMOTIVE_PROJECTION, false);
            }
        }

        @Override
        public boolean isCompanionApplicationBound(String packageName, int userId) {
            return mCompanionAppController.isCompanionApplicationBound(userId, packageName);
        }

        private void registerDevicePresenceListenerActive(String packageName, String deviceAddress,
                boolean active) throws RemoteException {
            if (DEBUG) {
                Log.i(TAG, "registerDevicePresenceListenerActive()"
                        + " active=" + active
                        + " deviceAddress=" + deviceAddress);
            }
            final int userId = getCallingUserId();
            enforceCallerIsSystemOr(userId, packageName);

            AssociationInfo association = mAssociationStore.getFirstAssociationByAddress(
                    userId, packageName, deviceAddress);

            if (association == null) {
                throw new RemoteException(new DeviceNotAssociatedException("App " + packageName
                        + " is not associated with device " + deviceAddress
                        + " for user " + userId));
            }

            processDevicePresenceListener(association, userId, packageName, active);
        }

        private void processDevicePresenceListener(AssociationInfo association,
                int userId, String packageName, boolean active) {
            // If already at specified state, then no-op.
            if (active == association.isNotifyOnDeviceNearby()) {
                if (DEBUG) Log.d(TAG, "Device presence listener is already at desired state.");
                return;
            }

            // AssociationInfo class is immutable: create a new AssociationInfo object with updated
            // flag.
            association = (new AssociationInfo.Builder(association))
                    .setNotifyOnDeviceNearby(active)
                    .build();
            // Do not need to call {@link BleCompanionDeviceScanner#restartScan()} since it will
            // trigger {@link BleCompanionDeviceScanner#restartScan(int, AssociationInfo)} when
            // an application sets/unsets the mNotifyOnDeviceNearby flag.
            mAssociationStore.updateAssociation(association);

            int associationId = association.getId();
            // If device is already present, then trigger callback.
            if (active && mDevicePresenceMonitor.isDevicePresent(associationId)) {
                Slog.i(TAG, "Device is already present. Triggering callback.");
                if (mDevicePresenceMonitor.isBlePresent(associationId)
                        || mDevicePresenceMonitor.isSimulatePresent(associationId)) {
                    onDeviceAppearedInternal(associationId);
                    onDevicePresenceEventInternal(associationId, EVENT_BLE_APPEARED);
                } else if (mDevicePresenceMonitor.isBtConnected(associationId)) {
                    onDevicePresenceEventInternal(associationId, EVENT_BT_CONNECTED);
                }
            }

            // If last listener is unregistered, then unbind application.
            if (!active && !shouldBindPackage(userId, packageName)) {
                if (DEBUG) Log.d(TAG, "Last listener unregistered. Unbinding application.");
                mCompanionAppController.unbindCompanionApplication(userId, packageName);
            }
        }

        @Override
        @EnforcePermission(ASSOCIATE_COMPANION_DEVICES)
        public void createAssociation(String packageName, String macAddress, int userId,
                byte[] certificate) {
            createAssociation_enforcePermission();

            if (!getContext().getPackageManager().hasSigningCertificate(
                    packageName, certificate, CERT_INPUT_SHA256)) {
                Slog.e(TAG, "Given certificate doesn't match the package certificate.");
                return;
            }

            final MacAddress macAddressObj = MacAddress.fromString(macAddress);
            createNewAssociation(userId, packageName, macAddressObj, null, null, false);
        }

        private void checkCanCallNotificationApi(String callingPackage, int userId) {
            enforceCallerIsSystemOr(userId, callingPackage);

            if (getCallingUid() == SYSTEM_UID) return;

            enforceUsesCompanionDeviceFeature(getContext(), userId, callingPackage);
            checkState(!ArrayUtils.isEmpty(
                            mAssociationStore.getActiveAssociationsByPackage(userId,
                                    callingPackage)),
                    "App must have an association before calling this API");
        }

        @Override
        public boolean canPairWithoutPrompt(String packageName, String macAddress, int userId) {
            final AssociationInfo association =
                    mAssociationStore.getFirstAssociationByAddress(
                            userId, packageName, macAddress);
            if (association == null) {
                return false;
            }
            return System.currentTimeMillis() - association.getTimeApprovedMs()
                    < PAIR_WITHOUT_PROMPT_WINDOW_MS;
        }

        @Override
        public void setAssociationTag(int associationId, String tag) {
            AssociationInfo association = getAssociationWithCallerChecks(associationId);
            association = (new AssociationInfo.Builder(association)).setTag(tag).build();
            mAssociationStore.updateAssociation(association);
        }

        @Override
        public void clearAssociationTag(int associationId) {
            setAssociationTag(associationId, null);
        }

        @Override
        public byte[] getBackupPayload(int userId) {
            return mBackupRestoreProcessor.getBackupPayload(userId);
        }

        @Override
        public void applyRestoredPayload(byte[] payload, int userId) {
            mBackupRestoreProcessor.applyRestoredPayload(payload, userId);
        }

        @Override
        public int handleShellCommand(@NonNull ParcelFileDescriptor in,
                @NonNull ParcelFileDescriptor out, @NonNull ParcelFileDescriptor err,
                @NonNull String[] args) {
            return new CompanionDeviceShellCommand(CompanionDeviceManagerService.this,
                    mAssociationStore, mDevicePresenceMonitor, mTransportManager,
                    mSystemDataTransferProcessor, mAssociationRequestsProcessor,
                    mBackupRestoreProcessor, mDisassociationProcessor)
                    .exec(this, in.getFileDescriptor(), out.getFileDescriptor(),
                            err.getFileDescriptor(), args);
        }

        @Override
        public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter out,
                @Nullable String[] args) {
            if (!DumpUtils.checkDumpAndUsageStatsPermission(getContext(), TAG, out)) {
                return;
            }

            mAssociationStore.dump(out);
            mDevicePresenceMonitor.dump(out);
            mCompanionAppController.dump(out);
            mTransportManager.dump(out);
            mSystemDataTransferRequestStore.dump(out);
        }
    }

    void createNewAssociation(@UserIdInt int userId, @NonNull String packageName,
            @Nullable MacAddress macAddress, @Nullable CharSequence displayName,
            @Nullable String deviceProfile, boolean isSelfManaged) {
        mAssociationRequestsProcessor.createAssociation(userId, packageName, macAddress,
                displayName, deviceProfile, /* associatedDevice */ null, isSelfManaged,
                /* callback */ null, /* resultReceiver */ null);
    }

    /**
     * Update special access for the association's package
     */
    public void updateSpecialAccessPermissionForAssociatedPackage(int userId, String packageName) {
        final PackageInfo packageInfo =
                getPackageInfo(getContext(), userId, packageName);

        Binder.withCleanCallingIdentity(() -> updateSpecialAccessPermissionAsSystem(packageInfo,
                userId, packageName));
    }

    private void updateSpecialAccessPermissionAsSystem(PackageInfo packageInfo, int userId,
            String packageName) {
        if (packageInfo == null) {
            return;
        }

        List<AssociationInfo> associations = mAssociationStore.getActiveAssociationsByPackage(
                userId, packageName);

        if (containsEither(packageInfo.requestedPermissions,
                android.Manifest.permission.RUN_IN_BACKGROUND,
                android.Manifest.permission.REQUEST_COMPANION_RUN_IN_BACKGROUND)
                && !associations.isEmpty()) {
            mPowerExemptionManager.addToPermanentAllowList(packageInfo.packageName);
        } else {
            try {
                mPowerExemptionManager.removeFromPermanentAllowList(packageInfo.packageName);
            } catch (UnsupportedOperationException e) {
                Slog.w(TAG, packageInfo.packageName + " can't be removed from power save"
                        + " whitelist. It might due to the package is whitelisted by the system.");
            }
        }

        NetworkPolicyManager networkPolicyManager = NetworkPolicyManager.from(getContext());
        try {
            if (containsEither(packageInfo.requestedPermissions,
                    android.Manifest.permission.USE_DATA_IN_BACKGROUND,
                    android.Manifest.permission.REQUEST_COMPANION_USE_DATA_IN_BACKGROUND)
                    && !associations.isEmpty()) {
                networkPolicyManager.addUidPolicy(
                        packageInfo.applicationInfo.uid,
                        NetworkPolicyManager.POLICY_ALLOW_METERED_BACKGROUND);
            } else {
                networkPolicyManager.removeUidPolicy(
                        packageInfo.applicationInfo.uid,
                        NetworkPolicyManager.POLICY_ALLOW_METERED_BACKGROUND);
            }
        } catch (IllegalArgumentException e) {
            Slog.e(TAG, e.getMessage());
        }

        exemptFromAutoRevoke(packageInfo.packageName, packageInfo.applicationInfo.uid);
    }

    private void exemptFromAutoRevoke(String packageName, int uid) {
        try {
            mAppOpsManager.setMode(
                    AppOpsManager.OP_AUTO_REVOKE_PERMISSIONS_IF_UNUSED,
                    uid,
                    packageName,
                    AppOpsManager.MODE_IGNORED);
        } catch (RemoteException e) {
            Slog.w(TAG, "Error while granting auto revoke exemption for " + packageName, e);
        }
    }

    private void updateAtm(int userId, List<AssociationInfo> associations) {
        final Set<Integer> companionAppUids = new ArraySet<>();
        for (AssociationInfo association : associations) {
            final int uid = mPackageManagerInternal.getPackageUid(association.getPackageName(),
                    0, userId);
            if (uid >= 0) {
                companionAppUids.add(uid);
            }
        }
        if (mAtmInternal != null) {
            mAtmInternal.setCompanionAppUids(userId, companionAppUids);
        }
        if (mAmInternal != null) {
            // Make a copy of the set and send it to ActivityManager.
            mAmInternal.setCompanionAppUids(userId, new ArraySet<>(companionAppUids));
        }
    }

    private void maybeGrantAutoRevokeExemptions() {
        Slog.d(TAG, "maybeGrantAutoRevokeExemptions()");

        PackageManager pm = getContext().getPackageManager();
        for (int userId : LocalServices.getService(UserManagerInternal.class).getUserIds()) {
            SharedPreferences pref = getContext().getSharedPreferences(
                    new File(Environment.getUserSystemDirectory(userId), PREF_FILE_NAME),
                    Context.MODE_PRIVATE);
            if (pref.getBoolean(PREF_KEY_AUTO_REVOKE_GRANTS_DONE, false)) {
                continue;
            }

            try {
                final List<AssociationInfo> associations =
                        mAssociationStore.getActiveAssociationsByUser(userId);
                for (AssociationInfo a : associations) {
                    try {
                        int uid = pm.getPackageUidAsUser(a.getPackageName(), userId);
                        exemptFromAutoRevoke(a.getPackageName(), uid);
                    } catch (PackageManager.NameNotFoundException e) {
                        Slog.w(TAG, "Unknown companion package: " + a.getPackageName(), e);
                    }
                }
            } finally {
                pref.edit().putBoolean(PREF_KEY_AUTO_REVOKE_GRANTS_DONE, true).apply();
            }
        }
    }

    private final AssociationStore.OnChangeListener mAssociationStoreChangeListener =
            new AssociationStore.OnChangeListener() {
                @Override
                public void onAssociationChanged(int changeType, AssociationInfo association) {
                    Slog.d(TAG, "onAssociationChanged changeType=[" + changeType
                            + "], association=[" + association);

                    final int userId = association.getUserId();
                    final List<AssociationInfo> updatedAssociations =
                            mAssociationStore.getActiveAssociationsByUser(userId);

                    updateAtm(userId, updatedAssociations);
                    updateSpecialAccessPermissionForAssociatedPackage(association.getUserId(),
                            association.getPackageName());
                }
            };

    private final CompanionDevicePresenceMonitor.Callback mDevicePresenceCallback =
            new CompanionDevicePresenceMonitor.Callback() {
                @Override
                public void onDeviceAppeared(int associationId) {
                    onDeviceAppearedInternal(associationId);
                }

                @Override
                public void onDeviceDisappeared(int associationId) {
                    onDeviceDisappearedInternal(associationId);
                }

                @Override
                public void onDevicePresenceEvent(int associationId, int event) {
                    onDevicePresenceEventInternal(associationId, event);
                }

                @Override
                public void onDevicePresenceEventByUuid(ObservableUuid uuid, int event) {
                    onDevicePresenceEventByUuidInternal(uuid, event);
                }
            };

    private final PackageMonitor mPackageMonitor = new PackageMonitor() {
        @Override
        public void onPackageRemoved(String packageName, int uid) {
            onPackageRemoveOrDataClearedInternal(getChangingUserId(), packageName);
        }

        @Override
        public void onPackageDataCleared(String packageName, int uid) {
            onPackageRemoveOrDataClearedInternal(getChangingUserId(), packageName);
        }

        @Override
        public void onPackageModified(String packageName) {
            onPackageModifiedInternal(getChangingUserId(), packageName);
        }

        @Override
        public void onPackageAdded(String packageName, int uid) {
            onPackageAddedInternal(getChangingUserId(), packageName);
        }
    };

    private static Map<String, Set<Integer>> deepUnmodifiableCopy(Map<String, Set<Integer>> orig) {
        final Map<String, Set<Integer>> copy = new HashMap<>();

        for (Map.Entry<String, Set<Integer>> entry : orig.entrySet()) {
            final Set<Integer> valueCopy = new HashSet<>(entry.getValue());
            copy.put(entry.getKey(), Collections.unmodifiableSet(valueCopy));
        }

        return Collections.unmodifiableMap(copy);
    }

    private static <T> boolean containsEither(T[] array, T a, T b) {
        return ArrayUtils.contains(array, a) || ArrayUtils.contains(array, b);
    }

    private class LocalService implements CompanionDeviceManagerServiceInternal {
        @Override
        public void removeInactiveSelfManagedAssociations() {
            CompanionDeviceManagerService.this.removeInactiveSelfManagedAssociations();
        }

        @Override
        public void registerCallMetadataSyncCallback(CrossDeviceSyncControllerCallback callback,
                @CrossDeviceSyncControllerCallback.Type int type) {
            if (CompanionDeviceConfig.isEnabled(
                    CompanionDeviceConfig.ENABLE_CONTEXT_SYNC_TELECOM)) {
                mCrossDeviceSyncController.registerCallMetadataSyncCallback(callback, type);
            }
        }

        @Override
        public void crossDeviceSync(int userId, Collection<CrossDeviceCall> calls) {
            if (CompanionDeviceConfig.isEnabled(
                    CompanionDeviceConfig.ENABLE_CONTEXT_SYNC_TELECOM)) {
                mCrossDeviceSyncController.syncToAllDevicesForUserId(userId, calls);
            }
        }

        @Override
        public void crossDeviceSync(AssociationInfo associationInfo,
                Collection<CrossDeviceCall> calls) {
            if (CompanionDeviceConfig.isEnabled(
                    CompanionDeviceConfig.ENABLE_CONTEXT_SYNC_TELECOM)) {
                mCrossDeviceSyncController.syncToSingleDevice(associationInfo, calls);
            }
        }

        @Override
        public void sendCrossDeviceSyncMessage(int associationId, byte[] message) {
            if (CompanionDeviceConfig.isEnabled(
                    CompanionDeviceConfig.ENABLE_CONTEXT_SYNC_TELECOM)) {
                mCrossDeviceSyncController.syncMessageToDevice(associationId, message);
            }
        }

        @Override
        public void sendCrossDeviceSyncMessageToAllDevices(int userId, byte[] message) {
            if (CompanionDeviceConfig.isEnabled(
                    CompanionDeviceConfig.ENABLE_CONTEXT_SYNC_TELECOM)) {
                mCrossDeviceSyncController.syncMessageToAllDevicesForUserId(userId, message);
            }
        }

        @Override
        public void addSelfOwnedCallId(String callId) {
            if (CompanionDeviceConfig.isEnabled(
                    CompanionDeviceConfig.ENABLE_CONTEXT_SYNC_TELECOM)) {
                mCrossDeviceSyncController.addSelfOwnedCallId(callId);
            }
        }

        @Override
        public void removeSelfOwnedCallId(String callId) {
            if (CompanionDeviceConfig.isEnabled(
                    CompanionDeviceConfig.ENABLE_CONTEXT_SYNC_TELECOM)) {
                mCrossDeviceSyncController.removeSelfOwnedCallId(callId);
            }
        }
    }
}
