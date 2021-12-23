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

import static android.Manifest.permission.MANAGE_COMPANION_DEVICES;
import static android.bluetooth.le.ScanSettings.CALLBACK_TYPE_ALL_MATCHES;
import static android.bluetooth.le.ScanSettings.SCAN_MODE_BALANCED;
import static android.content.pm.PackageManager.CERT_INPUT_SHA256;
import static android.content.pm.PackageManager.FEATURE_COMPANION_DEVICE_SETUP;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Binder.getCallingUid;
import static android.os.Process.SYSTEM_UID;
import static android.os.UserHandle.getCallingUserId;

import static com.android.internal.util.CollectionUtils.add;
import static com.android.internal.util.CollectionUtils.any;
import static com.android.internal.util.CollectionUtils.filter;
import static com.android.internal.util.CollectionUtils.find;
import static com.android.internal.util.CollectionUtils.forEach;
import static com.android.internal.util.CollectionUtils.map;
import static com.android.internal.util.Preconditions.checkState;
import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;
import static com.android.internal.util.function.pooled.PooledLambda.obtainRunnable;
import static com.android.server.companion.PermissionsUtils.checkCallerCanManageAssociationsForPackage;
import static com.android.server.companion.PermissionsUtils.checkCallerCanManageCompanionDevice;
import static com.android.server.companion.PermissionsUtils.enforceCallerCanInteractWithUserId;
import static com.android.server.companion.PermissionsUtils.enforceCallerCanManageAssociationsForPackage;
import static com.android.server.companion.PermissionsUtils.enforceCallerCanManageCompanionDevice;
import static com.android.server.companion.PermissionsUtils.enforceCallerIsSystemOr;
import static com.android.server.companion.RolesUtils.addRoleHolderForAssociation;
import static com.android.server.companion.RolesUtils.removeRoleHolderForAssociation;

import static java.util.Collections.emptySet;
import static java.util.Collections.unmodifiableSet;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MINUTES;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.DeviceNotAssociatedException;
import android.companion.IAssociationRequestCallback;
import android.companion.ICompanionDeviceManager;
import android.companion.IOnAssociationsChangedListener;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.UserInfo;
import android.net.MacAddress;
import android.net.NetworkPolicyManager;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.Parcel;
import android.os.PowerWhitelistManager;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.os.UserManager;
import android.permission.PermissionControllerManager;
import android.text.BidiFormatter;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.ExceptionUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IAppOpsService;
import com.android.internal.content.PackageMonitor;
import com.android.internal.notification.NotificationAccessConfirmationActivityContract;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.pm.UserManagerInternal;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.function.Function;
import java.util.function.Predicate;

/** @hide */
@SuppressLint("LongLogTag")
public class CompanionDeviceManagerService extends SystemService {
    static final String LOG_TAG = "CompanionDeviceManagerService";
    static final boolean DEBUG = false;

    /** Range of Association IDs allocated for a user.*/
    static final int ASSOCIATIONS_IDS_PER_USER_RANGE = 100000;

    private static final long DEVICE_DISAPPEARED_TIMEOUT_MS = 10 * 1000;
    private static final long DEVICE_DISAPPEARED_UNBIND_TIMEOUT_MS = 10 * 60 * 1000;

    static final long DEVICE_LISTENER_DIED_REBIND_TIMEOUT_MS = 10 * 1000;

    private static final long PAIR_WITHOUT_PROMPT_WINDOW_MS = 10 * 60 * 1000; // 10 min

    private static final String PREF_FILE_NAME = "companion_device_preferences.xml";
    private static final String PREF_KEY_AUTO_REVOKE_GRANTS_DONE = "auto_revoke_grants_done";

    private static DateFormat sDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    static {
        sDateFormat.setTimeZone(TimeZone.getDefault());
    }

    private final CompanionDeviceManagerImpl mImpl;
    // Persistent data store for all Associations.
    private final PersistentDataStore mPersistentDataStore;
    private final AssociationRequestsProcessor mAssociationRequestsProcessor;
    private PowerWhitelistManager mPowerWhitelistManager;
    private IAppOpsService mAppOpsManager;
    private BluetoothAdapter mBluetoothAdapter;
    private UserManager mUserManager;

    private ScanCallback mBleScanCallback = new BleScanCallback();
    PermissionControllerManager mPermissionControllerManager;

    private BluetoothDeviceConnectedListener mBluetoothDeviceConnectedListener =
            new BluetoothDeviceConnectedListener();
    private BleStateBroadcastReceiver mBleStateBroadcastReceiver = new BleStateBroadcastReceiver();
    private List<String> mCurrentlyConnectedDevices = new ArrayList<>();
    Set<Integer> mPresentSelfManagedDevices = new HashSet<>();
    private ArrayMap<String, Date> mDevicesLastNearby = new ArrayMap<>();
    private UnbindDeviceListenersRunnable
            mUnbindDeviceListenersRunnable = new UnbindDeviceListenersRunnable();
    private ArrayMap<String, TriggerDeviceDisappearedRunnable> mTriggerDeviceDisappearedRunnables =
            new ArrayMap<>();

    final Object mLock = new Object();
    final Handler mMainHandler = Handler.getMain();
    private CompanionDevicePresenceController mCompanionDevicePresenceController;

    /** Maps a {@link UserIdInt} to a set of associations for the user. */
    @GuardedBy("mLock")
    private final SparseArray<Set<AssociationInfo>> mCachedAssociations = new SparseArray<>();
    /**
     * A structure that consist of two nested maps, and effectively maps (userId + packageName) to
     * a list of IDs that have been previously assigned to associations for that package.
     * We maintain this structure so that we never re-use association IDs for the same package
     * (until it's uninstalled).
     */
    @GuardedBy("mLock")
    private final SparseArray<Map<String, Set<Integer>>> mPreviouslyUsedIds = new SparseArray<>();

    ActivityTaskManagerInternal mAtmInternal;
    ActivityManagerInternal mAmInternal;
    PackageManagerInternal mPackageManagerInternal;

    public CompanionDeviceManagerService(Context context) {
        super(context);
        mImpl = new CompanionDeviceManagerImpl();
        mPersistentDataStore = new PersistentDataStore();

        mPowerWhitelistManager = context.getSystemService(PowerWhitelistManager.class);
        mAppOpsManager = IAppOpsService.Stub.asInterface(
                ServiceManager.getService(Context.APP_OPS_SERVICE));
        mAtmInternal = LocalServices.getService(ActivityTaskManagerInternal.class);
        mAmInternal = LocalServices.getService(ActivityManagerInternal.class);
        mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
        mPermissionControllerManager = requireNonNull(
                context.getSystemService(PermissionControllerManager.class));
        mUserManager = context.getSystemService(UserManager.class);
        mCompanionDevicePresenceController = new CompanionDevicePresenceController(this);
        mAssociationRequestsProcessor = new AssociationRequestsProcessor(this);

        registerPackageMonitor();
    }

    private void registerPackageMonitor() {
        new PackageMonitor() {
            @Override
            public void onPackageRemoved(String packageName, int uid) {
                final int userId = getChangingUserId();
                Slog.i(LOG_TAG, "onPackageRemoved() u" + userId + "/" + packageName);

                clearAssociationForPackage(userId, packageName);
            }

            @Override
            public void onPackageDataCleared(String packageName, int uid) {
                final int userId = getChangingUserId();
                Slog.i(LOG_TAG, "onPackageDataCleared() u" + userId + "/" + packageName);

                clearAssociationForPackage(userId, packageName);
            }

            @Override
            public void onPackageModified(String packageName) {
                final int userId = getChangingUserId();
                Slog.i(LOG_TAG, "onPackageModified() u" + userId + "/" + packageName);

                forEach(getAssociations(userId, packageName), association ->
                        updateSpecialAccessPermissionForAssociatedPackage(association));
            }
        }.register(getContext(), FgThread.get().getLooper(), UserHandle.ALL, true);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.COMPANION_DEVICE_SERVICE, mImpl);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            // Init Bluetooth
            mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (mBluetoothAdapter != null) {
                mBluetoothAdapter.registerBluetoothConnectionCallback(
                        getContext().getMainExecutor(),
                        mBluetoothDeviceConnectedListener);
                getContext().registerReceiver(
                        mBleStateBroadcastReceiver, mBleStateBroadcastReceiver.mIntentFilter);
                initBleScanning();
            } else {
                Slog.w(LOG_TAG, "No BluetoothAdapter available");
            }
        }
    }

    @Override
    public void onUserUnlocking(@NonNull TargetUser user) {
        final int userId = user.getUserIdentifier();
        final Set<AssociationInfo> associations = getAllAssociationsForUser(userId);

        if (associations.isEmpty()) return;

        updateAtm(userId, associations);

        BackgroundThread.getHandler().sendMessageDelayed(
                obtainMessage(CompanionDeviceManagerService::maybeGrantAutoRevokeExemptions, this),
                MINUTES.toMillis(10));
    }

    @NonNull
    Set<AssociationInfo> getAllAssociationsForUser(@UserIdInt int userId) {
        synchronized (mLock) {
            readPersistedStateForUserIfNeededLocked(userId);
            // This returns non-null, because the readAssociationsInfoForUserIfNeededLocked() method
            // we just called adds an empty set, if there was no previously saved data.
            return mCachedAssociations.get(userId);
        }
    }

    @NonNull
    Set<AssociationInfo> getAssociations(@UserIdInt int userId, @NonNull String packageName) {
        return filter(getAllAssociationsForUser(userId),
                a -> a.belongsToPackage(userId, packageName));
    }

    @Nullable
    private AssociationInfo getAssociation(int associationId) {
        return find(getAllAssociations(), association -> association.getId() == associationId);
    }

    @Nullable
    AssociationInfo getAssociation(
            @UserIdInt int userId, @NonNull String packageName, @NonNull String macAddress) {
        return find(getAssociations(userId, packageName), a -> a.isLinkedTo(macAddress));
    }

    @Nullable
    AssociationInfo getAssociationWithCallerChecks(
            @UserIdInt int userId, @NonNull String packageName, @NonNull String macAddress) {
        return sanitizeWithCallerChecks(getAssociation(userId, packageName, macAddress));
    }

    @Nullable
    AssociationInfo getAssociationWithCallerChecks(int associationId) {
        return sanitizeWithCallerChecks(getAssociation(associationId));
    }

    @Nullable
    private AssociationInfo sanitizeWithCallerChecks(@Nullable AssociationInfo association) {
        if (association == null) return null;

        final int userId = association.getUserId();
        final String packageName = association.getPackageName();
        if (!checkCallerCanManageAssociationsForPackage(getContext(), userId, packageName)) {
            return null;
        }

        return association;
    }

    private Set<AssociationInfo> getAllAssociations() {
        final long identity = Binder.clearCallingIdentity();
        try {
            final Set<AssociationInfo> result = new ArraySet<>();
            for (UserInfo user : mUserManager.getAliveUsers()) {
                result.addAll(getAllAssociationsForUser(user.id));
            }
            return result;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    void maybeGrantAutoRevokeExemptions() {
        Slog.d(LOG_TAG, "maybeGrantAutoRevokeExemptions()");
        PackageManager pm = getContext().getPackageManager();
        for (int userId : LocalServices.getService(UserManagerInternal.class).getUserIds()) {
            SharedPreferences pref = getContext().getSharedPreferences(
                    new File(Environment.getUserSystemDirectory(userId), PREF_FILE_NAME),
                    Context.MODE_PRIVATE);
            if (pref.getBoolean(PREF_KEY_AUTO_REVOKE_GRANTS_DONE, false)) {
                continue;
            }

            try {
                Set<AssociationInfo> associations = getAllAssociationsForUser(userId);
                if (associations == null) {
                    continue;
                }
                for (AssociationInfo a : associations) {
                    try {
                        int uid = pm.getPackageUidAsUser(a.getPackageName(), userId);
                        exemptFromAutoRevoke(a.getPackageName(), uid);
                    } catch (PackageManager.NameNotFoundException e) {
                        Slog.w(LOG_TAG, "Unknown companion package: " + a.getPackageName(), e);
                    }
                }
            } finally {
                pref.edit().putBoolean(PREF_KEY_AUTO_REVOKE_GRANTS_DONE, true).apply();
            }
        }
    }

    class CompanionDeviceManagerImpl extends ICompanionDeviceManager.Stub {

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
        public void associate(AssociationRequest request, IAssociationRequestCallback callback,
                String packageName, int userId) throws RemoteException {
            Slog.i(LOG_TAG, "associate() "
                    + "request=" + request + ", "
                    + "package=u" + userId + "/" + packageName);
            enforceCallerCanManageAssociationsForPackage(getContext(), userId, packageName,
                    "create associations");

            mAssociationRequestsProcessor.processNewAssociationRequest(
                    request, packageName, userId, callback);
        }

        @Override
        public List<AssociationInfo> getAssociations(String packageName, int userId) {
            enforceCallerCanManageAssociationsForPackage(getContext(), userId, packageName,
                    "get associations");

            if (!checkCallerCanManageCompanionDevice(getContext())) {
                // If the caller neither is system nor holds MANAGE_COMPANION_DEVICES: it needs to
                // request the feature (also: the caller is the app itself).
                checkUsesFeature(packageName, getCallingUserId());
            }

            return new ArrayList<>(
                    CompanionDeviceManagerService.this.getAssociations(userId, packageName));
        }

        @Override
        public List<AssociationInfo> getAllAssociationsForUser(int userId) throws RemoteException {
            enforceCallerCanInteractWithUserId(getContext(), userId);
            enforceCallerCanManageCompanionDevice(getContext(), "getAllAssociationsForUser");

            return new ArrayList<>(
                    CompanionDeviceManagerService.this.getAllAssociationsForUser(userId));
        }

        @Override
        public void addOnAssociationsChangedListener(IOnAssociationsChangedListener listener,
                int userId) {
            enforceCallerCanInteractWithUserId(getContext(), userId);
            enforceCallerCanManageCompanionDevice(getContext(),
                    "addOnAssociationsChangedListener");

            //TODO: Implement.
        }

        @Override
        public void removeOnAssociationsChangedListener(IOnAssociationsChangedListener listener,
                int userId) {
            //TODO: Implement.
        }

        @Override
        public void legacyDisassociate(String deviceMacAddress, String packageName, int userId) {
            requireNonNull(deviceMacAddress);
            requireNonNull(packageName);

            final AssociationInfo association =
                    getAssociationWithCallerChecks(userId, packageName, deviceMacAddress);
            if (association == null) {
                throw new IllegalArgumentException("Association does not exist "
                        + "or the caller does not have permissions to manage it "
                        + "(ie. it belongs to a different package or a different user).");
            }

            disassociateInternal(userId, association.getId());
        }

        @Override
        public void disassociate(int associationId) {
            final AssociationInfo association = getAssociationWithCallerChecks(associationId);
            if (association == null) {
                throw new IllegalArgumentException("Association with ID " + associationId + " "
                        + "does not exist "
                        + "or belongs to a different package "
                        + "or belongs to a different user");
            }

            disassociateInternal(association.getUserId(), associationId);
        }

        @Override
        public PendingIntent requestNotificationAccess(ComponentName component, int userId)
                throws RemoteException {
            String callingPackage = component.getPackageName();
            checkCanCallNotificationApi(callingPackage);
            //TODO: check userId.
            String packageTitle = BidiFormatter.getInstance().unicodeWrap(
                    getPackageInfo(callingPackage, userId)
                            .applicationInfo
                            .loadSafeLabel(getContext().getPackageManager(),
                                    PackageItemInfo.DEFAULT_MAX_LABEL_SIZE_PX,
                                    PackageItemInfo.SAFE_LABEL_FLAG_TRIM
                                            | PackageItemInfo.SAFE_LABEL_FLAG_FIRST_LINE)
                            .toString());
            final long identity = Binder.clearCallingIdentity();
            try {
                return PendingIntent.getActivityAsUser(getContext(),
                        0 /* request code */,
                        NotificationAccessConfirmationActivityContract.launcherIntent(
                                getContext(), userId, component, packageTitle),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT
                                | PendingIntent.FLAG_CANCEL_CURRENT,
                        null /* options */,
                        new UserHandle(userId));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        /**
        * @deprecated Use
        * {@link NotificationManager#isNotificationListenerAccessGranted(ComponentName)} instead.
        */
        @Deprecated
        @Override
        public boolean hasNotificationAccess(ComponentName component) throws RemoteException {
            checkCanCallNotificationApi(component.getPackageName());
            NotificationManager nm = getContext().getSystemService(NotificationManager.class);
            return nm.isNotificationListenerAccessGranted(component);
        }

        @Override
        public boolean isDeviceAssociatedForWifiConnection(String packageName, String macAddress,
                int userId) {
            getContext().enforceCallingOrSelfPermission(
                    MANAGE_COMPANION_DEVICES, "isDeviceAssociated");

            boolean bypassMacPermission = getContext().getPackageManager().checkPermission(
                    android.Manifest.permission.COMPANION_APPROVE_WIFI_CONNECTIONS, packageName)
                    == PERMISSION_GRANTED;
            if (bypassMacPermission) {
                return true;
            }

            return any(CompanionDeviceManagerService.this.getAssociations(userId, packageName),
                    a -> a.isLinkedTo(macAddress));
        }

        @Override
        public void registerDevicePresenceListenerService(String deviceAddress,
                String callingPackage, int userId) throws RemoteException {
            //TODO: take the userId into account.
            registerDevicePresenceListenerActive(callingPackage, deviceAddress, true);
        }

        @Override
        public void unregisterDevicePresenceListenerService(String deviceAddress,
                String callingPackage, int userId) throws RemoteException {
            //TODO: take the userId into account.
            registerDevicePresenceListenerActive(callingPackage, deviceAddress, false);
        }

        @Override
        public void dispatchMessage(int messageId, int associationId, byte[] message)
                throws RemoteException {
            //TODO: b/199427116
        }

        @Override
        public void notifyDeviceAppeared(int associationId) {
            final AssociationInfo association = getAssociationWithCallerChecks(associationId);
            if (association == null) {
                throw new IllegalArgumentException("Association with ID " + associationId + " "
                        + "does not exist "
                        + "or belongs to a different package "
                        + "or belongs to a different user");
            }

            if (!association.isSelfManaged()) {
                throw new IllegalArgumentException("Association with ID " + associationId
                        + " is not self-managed. notifyDeviceAppeared(int) can only be called for"
                        + " self-managed associations.");
            }

            if (!mPresentSelfManagedDevices.add(associationId)) {
                Slog.w(LOG_TAG, "Association with ID " + associationId + " is already present");
                return;
            }

            mCompanionDevicePresenceController.onDeviceNotifyAppeared(
                    association, getContext(), mMainHandler);
        }

        @Override
        public void notifyDeviceDisappeared(int associationId) {
            final AssociationInfo association = getAssociationWithCallerChecks(associationId);
            if (association == null) {
                throw new IllegalArgumentException("Association with ID " + associationId + " "
                        + "does not exist "
                        + "or belongs to a different package "
                        + "or belongs to a different user");
            }

            if (!association.isSelfManaged()) {
                throw new IllegalArgumentException("Association with ID " + associationId
                        + " is not self-managed. notifyDeviceAppeared(int) can only be called for"
                        + " self-managed associations.");
            }

            if (!mPresentSelfManagedDevices.contains(associationId)) {
                Slog.w(LOG_TAG, "Association with ID " + associationId + " is not connected");
                return;
            }

            mPresentSelfManagedDevices.remove(associationId);
            mCompanionDevicePresenceController.onDeviceNotifyDisappearedAndUnbind(
                    association, getContext(), mMainHandler);
        }

        private void registerDevicePresenceListenerActive(String packageName, String deviceAddress,
                boolean active) throws RemoteException {
            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE,
                    "[un]registerDevicePresenceListenerService");
            final int userId = getCallingUserId();
            enforceCallerIsSystemOr(userId, packageName);

            Set<AssociationInfo> deviceAssociations = filter(
                    CompanionDeviceManagerService.this.getAssociations(userId, packageName),
                    a -> a.isLinkedTo(deviceAddress));

            if (deviceAssociations.isEmpty()) {
                throw new RemoteException(new DeviceNotAssociatedException("App " + packageName
                        + " is not associated with device " + deviceAddress
                        + " for user " + userId));
            }

            updateAssociations(associations -> map(associations, association -> {
                if (association.belongsToPackage(userId, packageName)
                        && association.isLinkedTo(deviceAddress)) {
                    association.setNotifyOnDeviceNearby(active);
                }
                return association;
            }), userId);

            restartBleScan();
        }

        @Override
        public void createAssociation(String packageName, String macAddress, int userId,
                byte[] certificate) {
            if (!getContext().getPackageManager().hasSigningCertificate(
                    packageName, certificate, CERT_INPUT_SHA256)) {
                Slog.e(LOG_TAG, "Given certificate doesn't match the package certificate.");
                return;
            }

            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.ASSOCIATE_COMPANION_DEVICES, "createAssociation");

            legacyCreateAssociation(userId, macAddress, packageName, null);
        }

        private void checkCanCallNotificationApi(String callingPackage) {
            final int userId = getCallingUserId();
            enforceCallerIsSystemOr(userId, callingPackage);

            checkState(!ArrayUtils.isEmpty(
                    CompanionDeviceManagerService.this.getAssociations(userId, callingPackage)),
                    "App must have an association before calling this API");
            checkUsesFeature(callingPackage, userId);
        }

        @Override
        public boolean canPairWithoutPrompt(String packageName, String macAddress, int userId) {
            final AssociationInfo association = getAssociation(userId, packageName, macAddress);
            if (association == null) {
                return false;
            }
            return System.currentTimeMillis() - association.getTimeApprovedMs()
                    < PAIR_WITHOUT_PROMPT_WINDOW_MS;
        }

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
                String[] args, ShellCallback callback, ResultReceiver resultReceiver)
                throws RemoteException {
            enforceCallerCanManageCompanionDevice(getContext(), "onShellCommand");
            new CompanionDeviceShellCommand(CompanionDeviceManagerService.this)
                    .exec(this, in, out, err, args, callback, resultReceiver);
        }

        @Override
        public void dump(@NonNull FileDescriptor fd,
                @NonNull PrintWriter fout,
                @Nullable String[] args) {
            if (!DumpUtils.checkDumpAndUsageStatsPermission(getContext(), LOG_TAG, fout)) {
                return;
            }

            fout.append("Companion Device Associations:").append('\n');
            synchronized (mLock) {
                for (UserInfo user : getAllUsers()) {
                    forEach(mCachedAssociations.get(user.id), a -> {
                        fout.append("  ").append(a.toString()).append('\n');
                    });
                }

            }

            fout.append("Currently Connected Devices:").append('\n');
            for (int i = 0, size = mCurrentlyConnectedDevices.size(); i < size; i++) {
                fout.append("  ").append(mCurrentlyConnectedDevices.get(i)).append('\n');
            }

            fout.append("Currently SelfManaged Connected Devices associationId:").append('\n');
            for (Integer associationId : mPresentSelfManagedDevices) {
                fout.append("  ").append("AssociationId:  ").append(
                        String.valueOf(associationId)).append('\n');
            }

            fout.append("Devices Last Nearby:").append('\n');
            for (int i = 0, size = mDevicesLastNearby.size(); i < size; i++) {
                String device = mDevicesLastNearby.keyAt(i);
                Date time = mDevicesLastNearby.valueAt(i);
                fout.append("  ").append(device).append(" -> ")
                        .append(sDateFormat.format(time)).append('\n');
            }

            fout.append("Device Listener Services State:").append('\n');
            for (int i = 0, size =  mCompanionDevicePresenceController.mBoundServices.size();
                    i < size; i++) {
                int userId = mCompanionDevicePresenceController.mBoundServices.keyAt(i);
                fout.append("  ")
                        .append("u").append(Integer.toString(userId)).append(": ")
                        .append(Objects.toString(
                                mCompanionDevicePresenceController.mBoundServices.valueAt(i)))
                        .append('\n');
            }
        }
    }

    /**
     * @deprecated use
     * {@link #createAssociation(int, String, MacAddress, CharSequence, String, boolean)}
     */
    @Deprecated
    void legacyCreateAssociation(@UserIdInt int userId, @NonNull String deviceMacAddress,
            @NonNull String packageName, @Nullable String deviceProfile) {
        final MacAddress macAddress = MacAddress.fromString(deviceMacAddress);
        createAssociation(userId, packageName, macAddress, null, deviceProfile, false);
    }

    AssociationInfo createAssociation(@UserIdInt int userId, @NonNull String packageName,
            @Nullable MacAddress macAddress, @Nullable CharSequence displayName,
            @Nullable String deviceProfile, boolean selfManaged) {
        final int id = getNewAssociationIdForPackage(userId, packageName);
        final long timestamp = System.currentTimeMillis();
        final AssociationInfo association = new AssociationInfo(id, userId, packageName,
                macAddress, displayName, deviceProfile, selfManaged, false, timestamp);

        updateSpecialAccessPermissionForAssociatedPackage(association);
        recordAssociation(association, userId);

        return association;
    }

    @GuardedBy("mLock")
    @NonNull
    private Set<Integer> getPreviouslyUsedIdsForPackageLocked(
            @UserIdInt int userId, @NonNull String packageName) {
        final Set<Integer> previouslyUsedIds = mPreviouslyUsedIds.get(userId).get(packageName);
        if (previouslyUsedIds != null) return previouslyUsedIds;
        return emptySet();
    }

    private int getNewAssociationIdForPackage(@UserIdInt int userId, @NonNull String packageName) {
        synchronized (mLock) {
            readPersistedStateForUserIfNeededLocked(userId);

            // First: collect all IDs currently in use for this user's Associations.
            final SparseBooleanArray usedIds = new SparseBooleanArray();
            for (AssociationInfo it : getAllAssociationsForUser(userId)) {
                usedIds.put(it.getId(), true);
            }

            // Second: collect all IDs that have been previously used for this package (and user).
            final Set<Integer> previouslyUsedIds =
                    getPreviouslyUsedIdsForPackageLocked(userId, packageName);

            int id = getFirstAssociationIdForUser(userId);
            final int lastAvailableIdForUser = getLastAssociationIdForUser(userId);

            // Find first ID that isn't used now AND has never been used for the given package.
            while (usedIds.get(id) || previouslyUsedIds.contains(id)) {
                // Increment and try again
                id++;
                // ... but first check if the ID is valid (within the range allocated to the user).
                if (id > lastAvailableIdForUser) {
                    throw new RuntimeException("Cannot create a new Association ID for "
                            + packageName + " for user " + userId);
                }
            }

            return id;
        }
    }

    //TODO also revoke notification access
    void disassociateInternal(@UserIdInt int userId, int associationId) {
        updateAssociations(associations ->
                filterOut(associations, it -> {
                    if (it.getId() != associationId) return false;

                    onAssociationPreRemove(it);
                    markIdAsPreviouslyUsedForPackage(
                            it.getId(), it.getUserId(), it.getPackageName());
                    return true;
                }), userId);

        restartBleScan();
    }

    void clearAssociationForPackage(@UserIdInt int userId, @NonNull String packageName) {
        if (DEBUG) Slog.d(LOG_TAG, "clearAssociationForPackage() u" + userId + "/" + packageName);

        mCompanionDevicePresenceController.unbindDevicePresenceListener(packageName, userId);
        updateAssociations(set -> filterOut(set, it -> it.belongsToPackage(userId, packageName)),
                userId);
    }

    private void markIdAsPreviouslyUsedForPackage(
            int associationId, @UserIdInt int userId, @NonNull String packageName) {
        synchronized (mLock) {
            // Mark as previously used.
            readPersistedStateForUserIfNeededLocked(userId);
            mPreviouslyUsedIds.get(userId)
                    .computeIfAbsent(packageName, it -> new HashSet<>())
                    .add(associationId);
        }
    }

    void onAssociationPreRemove(AssociationInfo association) {
        if (association.isNotifyOnDeviceNearby()
                || (association.isSelfManaged()
                && mPresentSelfManagedDevices.contains(association.getId()))) {
            mCompanionDevicePresenceController.unbindDevicePresenceListener(
                    association.getPackageName(), association.getUserId());
        }

        String deviceProfile = association.getDeviceProfile();
        if (deviceProfile != null) {
            AssociationInfo otherAssociationWithDeviceProfile = find(
                    getAllAssociationsForUser(association.getUserId()),
                    a -> !a.equals(association) && deviceProfile.equals(a.getDeviceProfile()));
            if (otherAssociationWithDeviceProfile != null) {
                Slog.i(LOG_TAG, "Not revoking " + deviceProfile
                        + " for " + association
                        + " - profile still present in " + otherAssociationWithDeviceProfile);
            } else {
                Binder.withCleanCallingIdentity(
                        () -> removeRoleHolderForAssociation(getContext(), association));
            }
        }
    }

    private void updateSpecialAccessPermissionForAssociatedPackage(AssociationInfo association) {
        PackageInfo packageInfo = getPackageInfo(
                association.getPackageName(),
                association.getUserId());
        if (packageInfo == null) {
            return;
        }

        Binder.withCleanCallingIdentity(obtainRunnable(CompanionDeviceManagerService::
                updateSpecialAccessPermissionAsSystem, this, association, packageInfo)
                .recycleOnUse());
    }

    private void updateSpecialAccessPermissionAsSystem(
            AssociationInfo association, PackageInfo packageInfo) {
        if (containsEither(packageInfo.requestedPermissions,
                android.Manifest.permission.RUN_IN_BACKGROUND,
                android.Manifest.permission.REQUEST_COMPANION_RUN_IN_BACKGROUND)) {
            mPowerWhitelistManager.addToWhitelist(packageInfo.packageName);
        } else {
            try {
                mPowerWhitelistManager.removeFromWhitelist(packageInfo.packageName);
            } catch (UnsupportedOperationException e) {
                Slog.w(LOG_TAG, packageInfo.packageName + " can't be removed from power save"
                        + " whitelist. It might due to the package is whitelisted by the system.");
            }
        }

        NetworkPolicyManager networkPolicyManager = NetworkPolicyManager.from(getContext());
        if (containsEither(packageInfo.requestedPermissions,
                android.Manifest.permission.USE_DATA_IN_BACKGROUND,
                android.Manifest.permission.REQUEST_COMPANION_USE_DATA_IN_BACKGROUND)) {
            networkPolicyManager.addUidPolicy(
                    packageInfo.applicationInfo.uid,
                    NetworkPolicyManager.POLICY_ALLOW_METERED_BACKGROUND);
        } else {
            networkPolicyManager.removeUidPolicy(
                    packageInfo.applicationInfo.uid,
                    NetworkPolicyManager.POLICY_ALLOW_METERED_BACKGROUND);
        }

        exemptFromAutoRevoke(packageInfo.packageName, packageInfo.applicationInfo.uid);

        if (!association.isSelfManaged()) {
            if (mCurrentlyConnectedDevices.contains(association.getDeviceMacAddressAsString())) {
                addRoleHolderForAssociation(getContext(), association);
            }

            if (association.isNotifyOnDeviceNearby()) {
                restartBleScan();
            }
        }
    }

    private void exemptFromAutoRevoke(String packageName, int uid) {
        try {
            mAppOpsManager.setMode(
                    AppOpsManager.OP_AUTO_REVOKE_PERMISSIONS_IF_UNUSED,
                    uid,
                    packageName,
                    AppOpsManager.MODE_IGNORED);
        } catch (RemoteException e) {
            Slog.w(LOG_TAG,
                    "Error while granting auto revoke exemption for " + packageName, e);
        }
    }

    private static <T> boolean containsEither(T[] array, T a, T b) {
        return ArrayUtils.contains(array, a) || ArrayUtils.contains(array, b);
    }

    @Nullable
    private PackageInfo getPackageInfo(String packageName, int userId) {
        final int flags = PackageManager.GET_PERMISSIONS | PackageManager.GET_CONFIGURATIONS;
        return Binder.withCleanCallingIdentity(
                () -> getContext().getPackageManager()
                        .getPackageInfoAsUser(packageName, flags , userId));
    }

    private void recordAssociation(AssociationInfo association, int userId) {
        Slog.i(LOG_TAG, "recordAssociation(" + association + ")");
        updateAssociations(associations -> add(associations, association), userId);
    }

    private void updateAssociations(Function<Set<AssociationInfo>, Set<AssociationInfo>> update,
            int userId) {
        synchronized (mLock) {
            if (DEBUG) Slog.d(LOG_TAG, "Updating Associations set...");

            final Set<AssociationInfo> prevAssociations = getAllAssociationsForUser(userId);
            if (DEBUG) Slog.d(LOG_TAG, "  > Before : " + prevAssociations + "...");

            final Set<AssociationInfo> updatedAssociations = update.apply(
                    new ArraySet<>(prevAssociations));
            if (DEBUG) Slog.d(LOG_TAG, "  > After: " + updatedAssociations);

            mCachedAssociations.put(userId, unmodifiableSet(updatedAssociations));

            BackgroundThread.getHandler().sendMessage(
                    PooledLambda.obtainMessage(
                            (associations, usedIds) ->
                                    mPersistentDataStore
                                            .persistStateForUser(userId, associations, usedIds),
                            updatedAssociations, deepCopy(mPreviouslyUsedIds.get(userId))));

            updateAtm(userId, updatedAssociations);
        }
    }

    private void updateAtm(int userId, Set<AssociationInfo> associations) {
        final Set<Integer> companionAppUids = new ArraySet<>();
        for (AssociationInfo association : associations) {
            final int uid = mPackageManagerInternal.getPackageUid(association.getPackageName(),
                    0, userId);
            if (uid >= 0) {
                companionAppUids.add(uid);
            }
        }
        if (mAmInternal != null) {
            // Make a copy of the set and send it to ActivityManager.
            mAmInternal.setCompanionAppUids(userId, new ArraySet<>(companionAppUids));
        }
    }

    @GuardedBy("mLock")
    private void readPersistedStateForUserIfNeededLocked(@UserIdInt int userId) {
        if (mCachedAssociations.get(userId) != null) return;

        Slog.i(LOG_TAG, "Reading state for user " + userId + "  from the disk");

        final Set<AssociationInfo> associations = new ArraySet<>();
        final Map<String, Set<Integer>> previouslyUsedIds = new ArrayMap<>();
        mPersistentDataStore.readStateForUser(userId, associations, previouslyUsedIds);

        if (DEBUG) {
            Slog.d(LOG_TAG, "  > associations=" + associations + "\n"
                    + "  > previouslyUsedIds=" + previouslyUsedIds);
        }

        mCachedAssociations.put(userId, unmodifiableSet(associations));
        mPreviouslyUsedIds.append(userId, previouslyUsedIds);
    }

    private List<UserInfo> getAllUsers() {
        final long identity = Binder.clearCallingIdentity();
        try {
            return mUserManager.getUsers();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    void onDeviceConnected(String address) {
        Slog.d(LOG_TAG, "onDeviceConnected(address = " + address + ")");

        mCurrentlyConnectedDevices.add(address);

        for (UserInfo user : getAllUsers()) {
            for (AssociationInfo association : getAllAssociationsForUser(user.id)) {
                if (association.isLinkedTo(address)) {
                    if (association.getDeviceProfile() != null) {
                        Slog.i(LOG_TAG, "Granting role " + association.getDeviceProfile()
                                + " to " + association.getPackageName()
                                + " due to device connected: " + association.getDeviceMacAddress());

                        addRoleHolderForAssociation(getContext(), association);
                    }
                }
            }
        }

        onDeviceNearby(address);
    }

    void onDeviceDisconnected(String address) {
        Slog.d(LOG_TAG, "onDeviceDisconnected(address = " + address + ")");

        mCurrentlyConnectedDevices.remove(address);

        Date lastSeen = mDevicesLastNearby.get(address);
        if (isDeviceDisappeared(lastSeen)) {
            onDeviceDisappeared(address);
            unscheduleTriggerDeviceDisappearedRunnable(address);
        }
    }

    private boolean isDeviceDisappeared(Date lastSeen) {
        return lastSeen == null || System.currentTimeMillis() - lastSeen.getTime()
                >= DEVICE_DISAPPEARED_UNBIND_TIMEOUT_MS;
    }

    private class BleScanCallback extends ScanCallback {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (DEBUG) {
                Slog.i(LOG_TAG, "onScanResult(callbackType = "
                        + callbackType + ", result = " + result + ")");
            }

            onDeviceNearby(result.getDevice().getAddress());
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (int i = 0, size = results.size(); i < size; i++) {
                onScanResult(CALLBACK_TYPE_ALL_MATCHES, results.get(i));
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            if (errorCode == SCAN_FAILED_ALREADY_STARTED) {
                // ignore - this might happen if BT tries to auto-restore scans for us in the
                // future
                Slog.i(LOG_TAG, "Ignoring BLE scan error: SCAN_FAILED_ALREADY_STARTED");
            } else {
                Slog.w(LOG_TAG, "Failed to start BLE scan: error " + errorCode);
            }
        }
    }

    private class BleStateBroadcastReceiver extends BroadcastReceiver {

        final IntentFilter mIntentFilter =
                new IntentFilter(BluetoothAdapter.ACTION_BLE_STATE_CHANGED);

        @Override
        public void onReceive(Context context, Intent intent) {
            int previousState = intent.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, -1);
            int newState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
            Slog.d(LOG_TAG, "Received BT state transition broadcast: "
                    + BluetoothAdapter.nameForState(previousState)
                    + " -> " + BluetoothAdapter.nameForState(newState));

            boolean bleOn = newState == BluetoothAdapter.STATE_ON
                    || newState == BluetoothAdapter.STATE_BLE_ON;
            if (bleOn) {
                if (mBluetoothAdapter.getBluetoothLeScanner() != null) {
                    startBleScan();
                } else {
                    Slog.wtf(LOG_TAG, "BLE on, but BluetoothLeScanner == null");
                }
            }
        }
    }

    private class UnbindDeviceListenersRunnable implements Runnable {

        public String getJobId(String address) {
            return "CDM_deviceGone_unbind_" + address;
        }

        @Override
        public void run() {
            int size = mDevicesLastNearby.size();
            for (int i = 0; i < size; i++) {
                String address = mDevicesLastNearby.keyAt(i);
                Date lastNearby = mDevicesLastNearby.valueAt(i);

                if (isDeviceDisappeared(lastNearby)) {
                    for (AssociationInfo association : getAllAssociations(address)) {
                        if (association.isNotifyOnDeviceNearby()) {
                            mCompanionDevicePresenceController.unbindDevicePresenceListener(
                                    association.getPackageName(), association.getUserId());
                        }
                    }
                }
            }
        }
    }

    private class TriggerDeviceDisappearedRunnable implements Runnable {

        private final String mAddress;

        TriggerDeviceDisappearedRunnable(String address) {
            mAddress = address;
        }

        public void schedule() {
            mMainHandler.removeCallbacks(this);
            mMainHandler.postDelayed(this, this, DEVICE_DISAPPEARED_TIMEOUT_MS);
        }

        @Override
        public void run() {
            Slog.d(LOG_TAG, "TriggerDeviceDisappearedRunnable.run(address = " + mAddress + ")");
            if (!mCurrentlyConnectedDevices.contains(mAddress)) {
                onDeviceDisappeared(mAddress);
            }
        }
    }

    private void unscheduleTriggerDeviceDisappearedRunnable(String address) {
        Runnable r = mTriggerDeviceDisappearedRunnables.get(address);
        if (r != null) {
            Slog.d(LOG_TAG,
                    "unscheduling TriggerDeviceDisappearedRunnable(address = " + address + ")");
            mMainHandler.removeCallbacks(r);
        }
    }

    private Set<AssociationInfo> getAllAssociations(String deviceAddress) {
        List<UserInfo> aliveUsers = mUserManager.getAliveUsers();
        Set<AssociationInfo> result = new ArraySet<>();
        for (int i = 0, size = aliveUsers.size(); i < size; i++) {
            UserInfo user = aliveUsers.get(i);
            for (AssociationInfo association : getAllAssociationsForUser(user.id)) {
                if (association.isLinkedTo(deviceAddress)) {
                    result.add(association);
                }
            }
        }
        return result;
    }

    private void onDeviceNearby(String address) {
        Date timestamp = new Date();
        Date oldTimestamp = mDevicesLastNearby.put(address, timestamp);

        cancelUnbindDeviceListener(address);

        mTriggerDeviceDisappearedRunnables
                .computeIfAbsent(address, addr -> new TriggerDeviceDisappearedRunnable(address))
                .schedule();

        // Avoid spamming the app if device is already known to be nearby
        boolean justAppeared = oldTimestamp == null
                || timestamp.getTime() - oldTimestamp.getTime() >= DEVICE_DISAPPEARED_TIMEOUT_MS;
        if (justAppeared) {
            Slog.i(LOG_TAG, "onDeviceNearby(justAppeared, address = " + address + ")");
            for (AssociationInfo association : getAllAssociations(address)) {
                if (association.isNotifyOnDeviceNearby()) {
                    mCompanionDevicePresenceController.onDeviceNotifyAppeared(association,
                            getContext(), mMainHandler);
                }
            }
        }
    }

    private void onDeviceDisappeared(String address) {
        Slog.i(LOG_TAG, "onDeviceDisappeared(address = " + address + ")");

        boolean hasDeviceListeners = false;
        for (AssociationInfo association : getAllAssociations(address)) {
            if (association.isNotifyOnDeviceNearby()) {
                mCompanionDevicePresenceController.onDeviceNotifyDisappeared(
                        association, getContext(), mMainHandler);
                hasDeviceListeners = true;
            }
        }

        cancelUnbindDeviceListener(address);
        if (hasDeviceListeners) {
            mMainHandler.postDelayed(
                    mUnbindDeviceListenersRunnable,
                    mUnbindDeviceListenersRunnable.getJobId(address),
                    DEVICE_DISAPPEARED_UNBIND_TIMEOUT_MS);
        }
    }

    private void cancelUnbindDeviceListener(String address) {
        mMainHandler.removeCallbacks(
                mUnbindDeviceListenersRunnable, mUnbindDeviceListenersRunnable.getJobId(address));
    }

    private void initBleScanning() {
        Slog.i(LOG_TAG, "initBleScanning()");

        boolean bluetoothReady = mBluetoothAdapter.registerServiceLifecycleCallback(
                new BluetoothAdapter.ServiceLifecycleCallback() {
                    @Override
                    public void onBluetoothServiceUp() {
                        Slog.i(LOG_TAG, "Bluetooth stack is up");
                        startBleScan();
                    }

                    @Override
                    public void onBluetoothServiceDown() {
                        Slog.w(LOG_TAG, "Bluetooth stack is down");
                    }
                });
        if (bluetoothReady) {
            startBleScan();
        }
    }

    void startBleScan() {
        Slog.i(LOG_TAG, "startBleScan()");

        List<ScanFilter> filters = getBleScanFilters();
        if (filters.isEmpty()) {
            return;
        }
        BluetoothLeScanner scanner = mBluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            Slog.w(LOG_TAG, "scanner == null (likely BLE isn't ON yet)");
        } else {
            scanner.startScan(
                    filters,
                    new ScanSettings.Builder().setScanMode(SCAN_MODE_BALANCED).build(),
                    mBleScanCallback);
        }
    }

    void restartBleScan() {
        if (mBluetoothAdapter.getBluetoothLeScanner() != null) {
            mBluetoothAdapter.getBluetoothLeScanner().stopScan(mBleScanCallback);
            startBleScan();
        } else {
            Slog.w(LOG_TAG, "BluetoothLeScanner is null (likely BLE isn't ON yet).");
        }
    }

    private List<ScanFilter> getBleScanFilters() {
        ArrayList<ScanFilter> result = new ArrayList<>();
        ArraySet<String> addressesSeen = new ArraySet<>();
        for (AssociationInfo association : getAllAssociations()) {
            if (association.isSelfManaged()) {
                continue;
            }
            String address = association.getDeviceMacAddressAsString();
            if (addressesSeen.contains(address)) {
                continue;
            }
            if (association.isNotifyOnDeviceNearby()) {
                result.add(new ScanFilter.Builder().setDeviceAddress(address).build());
                addressesSeen.add(address);
            }
        }
        return result;
    }

    static int getFirstAssociationIdForUser(@UserIdInt int userId) {
        // We want the IDs to start from 1, not 0.
        return userId * ASSOCIATIONS_IDS_PER_USER_RANGE + 1;
    }

    static int getLastAssociationIdForUser(@UserIdInt int userId) {
        return (userId + 1) * ASSOCIATIONS_IDS_PER_USER_RANGE;
    }

    private class BluetoothDeviceConnectedListener
            extends BluetoothAdapter.BluetoothConnectionCallback {
        @Override
        public void onDeviceConnected(BluetoothDevice device) {
            CompanionDeviceManagerService.this.onDeviceConnected(device.getAddress());
        }

        @Override
        public void onDeviceDisconnected(BluetoothDevice device, @DisconnectReason int reason) {
            Slog.d(LOG_TAG, device.getAddress() + " disconnected w/ reason: (" + reason + ") "
                    + BluetoothAdapter.BluetoothConnectionCallback.disconnectReasonText(reason));
            CompanionDeviceManagerService.this.onDeviceDisconnected(device.getAddress());
        }
    }

    private static @NonNull <T> Set<T> filterOut(
            @NonNull Set<T> set, @NonNull Predicate<? super T> predicate) {
        return CollectionUtils.filter(set, predicate.negate());
    }

    private Map<String, Set<Integer>> deepCopy(Map<String, Set<Integer>> orig) {
        final Map<String, Set<Integer>> copy = new HashMap<>(orig.size(), 1f);
        forEach(orig, (key, value) -> copy.put(key, new ArraySet<>(value)));
        return copy;
    }

    void checkUsesFeature(@NonNull String pkg, @UserIdInt int userId) {
        if (getCallingUid() == SYSTEM_UID) return;

        final FeatureInfo[] requestedFeatures = getPackageInfo(pkg, userId).reqFeatures;
        if (requestedFeatures != null) {
            for (int i = 0; i < requestedFeatures.length; i++) {
                if (FEATURE_COMPANION_DEVICE_SETUP.equals(requestedFeatures[i].name)) return;
            }
        }

        throw new IllegalStateException("Must declare uses-feature "
                + FEATURE_COMPANION_DEVICE_SETUP
                + " in manifest to use this API");
    }
}
