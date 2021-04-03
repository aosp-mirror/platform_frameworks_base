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

import static android.bluetooth.le.ScanSettings.CALLBACK_TYPE_ALL_MATCHES;
import static android.bluetooth.le.ScanSettings.SCAN_MODE_BALANCED;
import static android.content.Context.BIND_IMPORTANT;
import static android.content.pm.PackageManager.MATCH_ALL;

import static com.android.internal.util.CollectionUtils.any;
import static com.android.internal.util.CollectionUtils.emptyIfNull;
import static com.android.internal.util.CollectionUtils.filter;
import static com.android.internal.util.CollectionUtils.find;
import static com.android.internal.util.CollectionUtils.forEach;
import static com.android.internal.util.CollectionUtils.map;
import static com.android.internal.util.FunctionalUtils.uncheckExceptions;
import static com.android.internal.util.Preconditions.checkArgument;
import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.internal.util.Preconditions.checkState;
import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;
import static com.android.internal.util.function.pooled.PooledLambda.obtainRunnable;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MINUTES;

import android.Manifest;
import android.annotation.CheckResult;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.app.role.RoleManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.companion.Association;
import android.companion.AssociationRequest;
import android.companion.CompanionDeviceManager;
import android.companion.CompanionDeviceService;
import android.companion.DeviceNotAssociatedException;
import android.companion.ICompanionDeviceDiscoveryService;
import android.companion.ICompanionDeviceManager;
import android.companion.ICompanionDeviceService;
import android.companion.IFindDeviceCallback;
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
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.net.NetworkPolicyManager;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.PowerWhitelistManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.UserHandle;
import android.os.UserManager;
import android.permission.PermissionControllerManager;
import android.provider.Settings;
import android.provider.SettingsStringUtil.ComponentNameSet;
import android.text.BidiFormatter;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.ExceptionUtils;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IAppOpsService;
import com.android.internal.content.PackageMonitor;
import com.android.internal.infra.AndroidFuture;
import com.android.internal.infra.PerUser;
import com.android.internal.infra.ServiceConnector;
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

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/** @hide */
@SuppressLint("LongLogTag")
public class CompanionDeviceManagerService extends SystemService implements Binder.DeathRecipient {

    private static final ComponentName SERVICE_TO_BIND_TO = ComponentName.createRelative(
            CompanionDeviceManager.COMPANION_DEVICE_DISCOVERY_PACKAGE_NAME,
            ".CompanionDeviceDiscoveryService");

    private static final long DEVICE_DISAPPEARED_TIMEOUT_MS = 10 * 1000;
    private static final long DEVICE_DISAPPEARED_UNBIND_TIMEOUT_MS = 10 * 60 * 1000;

    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "CompanionDeviceManagerService";

    private static final long PAIR_WITHOUT_PROMPT_WINDOW_MS = 10 * 60 * 1000; // 10 min

    private static final String PREF_FILE_NAME = "companion_device_preferences.xml";
    private static final String PREF_KEY_AUTO_REVOKE_GRANTS_DONE = "auto_revoke_grants_done";

    private static final String XML_TAG_ASSOCIATIONS = "associations";
    private static final String XML_TAG_ASSOCIATION = "association";
    private static final String XML_ATTR_PACKAGE = "package";
    private static final String XML_ATTR_DEVICE = "device";
    private static final String XML_ATTR_PROFILE = "profile";
    private static final String XML_ATTR_NOTIFY_DEVICE_NEARBY = "notify_device_nearby";
    private static final String XML_ATTR_TIME_APPROVED = "time_approved";
    private static final String XML_FILE_NAME = "companion_device_manager_associations.xml";

    private static DateFormat sDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    static {
        sDateFormat.setTimeZone(TimeZone.getDefault());
    }

    private final CompanionDeviceManagerImpl mImpl;
    private final ConcurrentMap<Integer, AtomicFile> mUidToStorage = new ConcurrentHashMap<>();
    private PowerWhitelistManager mPowerWhitelistManager;
    private PerUser<ServiceConnector<ICompanionDeviceDiscoveryService>> mServiceConnectors;
    /** userId -> packageName -> serviceConnector */
    private PerUser<ArrayMap<String, ServiceConnector<ICompanionDeviceService>>>
            mDeviceListenerServiceConnectors;
    private IAppOpsService mAppOpsManager;
    private RoleManager mRoleManager;
    private BluetoothAdapter mBluetoothAdapter;
    private UserManager mUserManager;

    private IFindDeviceCallback mFindDeviceCallback;
    private ScanCallback mBleScanCallback = new BleScanCallback();
    private AssociationRequest mRequest;
    private String mCallingPackage;
    private AndroidFuture<Association> mOngoingDeviceDiscovery;
    private PermissionControllerManager mPermissionControllerManager;

    private BluetoothDeviceConnectedListener mBluetoothDeviceConnectedListener =
            new BluetoothDeviceConnectedListener();
    private BleStateBroadcastReceiver mBleStateBroadcastReceiver = new BleStateBroadcastReceiver();
    private List<String> mCurrentlyConnectedDevices = new ArrayList<>();
    private ArrayMap<String, Date> mDevicesLastNearby = new ArrayMap<>();
    private UnbindDeviceListenersRunnable
            mUnbindDeviceListenersRunnable = new UnbindDeviceListenersRunnable();
    private ArrayMap<String, TriggerDeviceDisappearedRunnable> mTriggerDeviceDisappearedRunnables =
            new ArrayMap<>();

    private final Object mLock = new Object();
    private final Handler mMainHandler = Handler.getMain();

    /** userId -> [association] */
    @GuardedBy("mLock")
    private @Nullable SparseArray<Set<Association>> mCachedAssociations = new SparseArray<>();

    ActivityTaskManagerInternal mAtmInternal;
    ActivityManagerInternal mAmInternal;
    PackageManagerInternal mPackageManagerInternal;

    public CompanionDeviceManagerService(Context context) {
        super(context);
        mImpl = new CompanionDeviceManagerImpl();
        mPowerWhitelistManager = context.getSystemService(PowerWhitelistManager.class);
        mRoleManager = context.getSystemService(RoleManager.class);
        mAppOpsManager = IAppOpsService.Stub.asInterface(
                ServiceManager.getService(Context.APP_OPS_SERVICE));
        mAtmInternal = LocalServices.getService(ActivityTaskManagerInternal.class);
        mAmInternal = LocalServices.getService(ActivityManagerInternal.class);
        mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
        mPermissionControllerManager = requireNonNull(
                context.getSystemService(PermissionControllerManager.class));
        mUserManager = context.getSystemService(UserManager.class);

        Intent serviceIntent = new Intent().setComponent(SERVICE_TO_BIND_TO);
        mServiceConnectors = new PerUser<ServiceConnector<ICompanionDeviceDiscoveryService>>() {
            @Override
            protected ServiceConnector<ICompanionDeviceDiscoveryService> create(int userId) {
                return new ServiceConnector.Impl<>(
                        getContext(),
                        serviceIntent, 0/* bindingFlags */, userId,
                        ICompanionDeviceDiscoveryService.Stub::asInterface);
            }
        };

        mDeviceListenerServiceConnectors = new PerUser<ArrayMap<String,
                ServiceConnector<ICompanionDeviceService>>>() {
            @NonNull
            @Override
            protected ArrayMap<String, ServiceConnector<ICompanionDeviceService>> create(
                    int userId) {
                return new ArrayMap<>();
            }
        };

        registerPackageMonitor();
    }

    private void registerPackageMonitor() {
        new PackageMonitor() {
            @Override
            public void onPackageRemoved(String packageName, int uid) {
                Slog.d(LOG_TAG, "onPackageRemoved(packageName = " + packageName
                        + ", uid = " + uid + ")");
                int userId = getChangingUserId();
                updateAssociations(
                        as -> CollectionUtils.filter(as,
                                a -> !Objects.equals(a.getPackageName(), packageName)),
                        userId);

                unbindDevicePresenceListener(packageName, userId);
            }

            @Override
            public void onPackageModified(String packageName) {
                Slog.d(LOG_TAG, "onPackageModified(packageName = " + packageName + ")");
                int userId = getChangingUserId();
                forEach(getAllAssociations(userId, packageName), association -> {
                    updateSpecialAccessPermissionForAssociatedPackage(association);
                });
            }

        }.register(getContext(), FgThread.get().getLooper(), UserHandle.ALL, true);
    }

    private void unbindDevicePresenceListener(String packageName, int userId) {
        ServiceConnector<ICompanionDeviceService> deviceListener =
                mDeviceListenerServiceConnectors.forUser(userId)
                        .remove(packageName);
        if (deviceListener != null) {
            deviceListener.unbind();
        }
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
        int userHandle = user.getUserIdentifier();
        Set<Association> associations = getAllAssociations(userHandle);
        if (associations == null || associations.isEmpty()) {
            return;
        }
        updateAtm(userHandle, associations);

        BackgroundThread.getHandler().sendMessageDelayed(
                obtainMessage(CompanionDeviceManagerService::maybeGrantAutoRevokeExemptions, this),
                MINUTES.toMillis(10));
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
                Set<Association> associations = getAllAssociations(userId);
                if (associations == null) {
                    continue;
                }
                for (Association a : associations) {
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

    @Override
    public void binderDied() {
        Slog.w(LOG_TAG, "binderDied()");
        mMainHandler.post(this::cleanup);
    }

    private void cleanup() {
        Slog.d(LOG_TAG, "cleanup(); discovery = "
                + mOngoingDeviceDiscovery + ", request = " + mRequest);
        synchronized (mLock) {
            AndroidFuture<Association> ongoingDeviceDiscovery = mOngoingDeviceDiscovery;
            if (ongoingDeviceDiscovery != null && !ongoingDeviceDiscovery.isDone()) {
                ongoingDeviceDiscovery.cancel(true);
            }
            mFindDeviceCallback = unlinkToDeath(mFindDeviceCallback, this, 0);
            mRequest = null;
            mCallingPackage = null;
        }
    }

    /**
     * Usage: {@code a = unlinkToDeath(a, deathRecipient, flags); }
     */
    @Nullable
    @CheckResult
    private static <T extends IInterface> T unlinkToDeath(T iinterface,
            IBinder.DeathRecipient deathRecipient, int flags) {
        if (iinterface != null) {
            iinterface.asBinder().unlinkToDeath(deathRecipient, flags);
        }
        return null;
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
        public void associate(
                AssociationRequest request,
                IFindDeviceCallback callback,
                String callingPackage) throws RemoteException {
            Slog.i(LOG_TAG, "associate(request = " + request + ", callback = " + callback
                    + ", callingPackage = " + callingPackage + ")");
            checkNotNull(request, "Request cannot be null");
            checkNotNull(callback, "Callback cannot be null");
            checkCallerIsSystemOr(callingPackage);
            int userId = getCallingUserId();
            checkUsesFeature(callingPackage, userId);
            checkProfilePermissions(request);

            mFindDeviceCallback = callback;
            mRequest = request;
            mCallingPackage = callingPackage;
            request.setCallingPackage(callingPackage);
            callback.asBinder().linkToDeath(CompanionDeviceManagerService.this /* recipient */, 0);

            AndroidFuture<String> fetchProfileDescription =
                    request.getDeviceProfile() == null
                            ? AndroidFuture.completedFuture(null)
                            : getDeviceProfilePermissionDescription(
                                    request.getDeviceProfile());

            mOngoingDeviceDiscovery = fetchProfileDescription.thenComposeAsync(description -> {
                Slog.d(LOG_TAG, "fetchProfileDescription done: " + description);

                request.setDeviceProfilePrivilegesDescription(description);

                return mServiceConnectors.forUser(userId).postAsync(service -> {
                    Slog.d(LOG_TAG, "Connected to CDM service; starting discovery for " + request);

                    AndroidFuture<Association> future = new AndroidFuture<>();
                    service.startDiscovery(request, callingPackage, callback, future);
                    return future;
                }).cancelTimeout();

            }, FgThread.getExecutor()).whenComplete(uncheckExceptions((association, err) -> {

                final long callingIdentity = Binder.clearCallingIdentity();
                try {
                    if (err == null) {
                        addAssociation(association);
                    } else {
                        Slog.e(LOG_TAG, "Failed to discover device(s)", err);
                        callback.onFailure("No devices found: " + err.getMessage());
                    }
                    cleanup();
                } finally {
                    Binder.restoreCallingIdentity(callingIdentity);
                }
            }));
        }

        @Override
        public void stopScan(AssociationRequest request,
                IFindDeviceCallback callback,
                String callingPackage) {
            Slog.d(LOG_TAG, "stopScan(request = " + request + ")");
            if (Objects.equals(request, mRequest)
                    && Objects.equals(callback, mFindDeviceCallback)
                    && Objects.equals(callingPackage, mCallingPackage)) {
                cleanup();
            }
        }

        @Override
        public List<String> getAssociations(String callingPackage, int userId)
                throws RemoteException {
            if (!callerCanManageCompanionDevices()) {
                checkCallerIsSystemOr(callingPackage, userId);
                checkUsesFeature(callingPackage, getCallingUserId());
            }
            return new ArrayList<>(map(
                    getAllAssociations(userId, callingPackage),
                    a -> a.getDeviceMacAddress()));
        }

        @Override
        public List<Association> getAssociationsForUser(int userId) {
            if (!callerCanManageCompanionDevices()) {
                throw new SecurityException("Caller must hold "
                        + android.Manifest.permission.MANAGE_COMPANION_DEVICES);
            }

            return new ArrayList<>(getAllAssociations(userId, null /* packageFilter */));
        }

        //TODO also revoke notification access
        @Override
        public void disassociate(String deviceMacAddress, String callingPackage)
                throws RemoteException {
            checkNotNull(deviceMacAddress);
            checkCallerIsSystemOr(callingPackage);
            checkUsesFeature(callingPackage, getCallingUserId());
            removeAssociation(getCallingUserId(), callingPackage, deviceMacAddress);
        }

        private boolean callerCanManageCompanionDevices() {
            return getContext().checkCallingOrSelfPermission(
                    android.Manifest.permission.MANAGE_COMPANION_DEVICES)
                    == PackageManager.PERMISSION_GRANTED;
        }

        private void checkCallerIsSystemOr(String pkg) throws RemoteException {
            checkCallerIsSystemOr(pkg, getCallingUserId());
        }

        private void checkCallerIsSystemOr(String pkg, int userId) throws RemoteException {
            if (isCallerSystem()) {
                return;
            }

            checkArgument(getCallingUserId() == userId,
                    "Must be called by either same user or system");
            int callingUid = Binder.getCallingUid();
            if (mAppOpsManager.checkPackage(callingUid, pkg) != AppOpsManager.MODE_ALLOWED) {
                throw new SecurityException(pkg + " doesn't belong to uid " + callingUid);
            }
        }

        private void checkProfilePermissions(AssociationRequest request) {
            checkProfilePermission(request,
                    AssociationRequest.DEVICE_PROFILE_WATCH,
                    Manifest.permission.REQUEST_COMPANION_PROFILE_WATCH);
        }

        private void checkProfilePermission(
                AssociationRequest request, String profile, String permission) {
            if (profile.equals(request.getDeviceProfile())
                    && getContext().checkCallingOrSelfPermission(permission)
                            != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Using " + profile + " requires " + permission);
            }
        }

        @Override
        public PendingIntent requestNotificationAccess(ComponentName component)
                throws RemoteException {
            String callingPackage = component.getPackageName();
            checkCanCallNotificationApi(callingPackage);
            int userId = getCallingUserId();
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
                                userId, component, packageTitle),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT
                                | PendingIntent.FLAG_CANCEL_CURRENT,
                        null /* options */,
                        new UserHandle(userId));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public boolean hasNotificationAccess(ComponentName component) throws RemoteException {
            checkCanCallNotificationApi(component.getPackageName());
            String setting = Settings.Secure.getString(getContext().getContentResolver(),
                    Settings.Secure.ENABLED_NOTIFICATION_LISTENERS);
            return new ComponentNameSet(setting).contains(component);
        }

        @Override
        public boolean isDeviceAssociatedForWifiConnection(String packageName, String macAddress,
                int userId) {
            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.MANAGE_COMPANION_DEVICES, "isDeviceAssociated");

            boolean bypassMacPermission = getContext().getPackageManager().checkPermission(
                    android.Manifest.permission.COMPANION_APPROVE_WIFI_CONNECTIONS, packageName)
                    == PackageManager.PERMISSION_GRANTED;
            if (bypassMacPermission) {
                return true;
            }

            return any(
                    getAllAssociations(userId, packageName),
                    a -> Objects.equals(a.getDeviceMacAddress(), macAddress));
        }

        @Override
        public void registerDevicePresenceListenerService(
                String packageName, String deviceAddress)
                throws RemoteException {
            registerDevicePresenceListenerActive(packageName, deviceAddress, true);
        }

        @Override
        public void unregisterDevicePresenceListenerService(
                String packageName, String deviceAddress)
                throws RemoteException {
            registerDevicePresenceListenerActive(packageName, deviceAddress, false);
        }

        private void registerDevicePresenceListenerActive(String packageName, String deviceAddress,
                boolean active) throws RemoteException {
            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE,
                    "[un]registerDevicePresenceListenerService");
            checkCallerIsSystemOr(packageName);

            int userId = getCallingUserId();
            Set<Association> deviceAssociations = CollectionUtils.filter(
                    getAllAssociations(userId, packageName),
                    association -> deviceAddress.equals(association.getDeviceMacAddress()));

            if (deviceAssociations.isEmpty()) {
                throw new RemoteException(new DeviceNotAssociatedException("App " + packageName
                        + " is not associated with device " + deviceAddress
                        + " for user " + userId));
            }

            updateAssociations(associations -> map(associations, association -> {
                if (Objects.equals(association.getPackageName(), packageName)
                        && Objects.equals(association.getDeviceMacAddress(), deviceAddress)) {
                    return new Association(
                            association.getUserId(),
                            association.getDeviceMacAddress(),
                            association.getPackageName(),
                            association.getDeviceProfile(),
                            active /* notifyOnDeviceNearby */,
                            association.getTimeApprovedMs());
                } else {
                    return association;
                }
            }));

            restartBleScan();
        }

        @Override
        public boolean createAssociation(String packageName, String macAddress, int userId) {
            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.ASSOCIATE_COMPANION_DEVICES, "createAssociation");

            addAssociation(new Association(
                    userId, macAddress, packageName, null, false, System.currentTimeMillis()));
            return true;
        }

        private void checkCanCallNotificationApi(String callingPackage) throws RemoteException {
            checkCallerIsSystemOr(callingPackage);
            int userId = getCallingUserId();
            checkState(!ArrayUtils.isEmpty(getAllAssociations(userId, callingPackage)),
                    "App must have an association before calling this API");
            checkUsesFeature(callingPackage, userId);
        }

        private void checkUsesFeature(String pkg, int userId) {
            if (isCallerSystem()) {
                // Drop the requirement for calls from system process
                return;
            }

            FeatureInfo[] reqFeatures = getPackageInfo(pkg, userId).reqFeatures;
            String requiredFeature = PackageManager.FEATURE_COMPANION_DEVICE_SETUP;
            int numFeatures = ArrayUtils.size(reqFeatures);
            for (int i = 0; i < numFeatures; i++) {
                if (requiredFeature.equals(reqFeatures[i].name)) return;
            }
            throw new IllegalStateException("Must declare uses-feature "
                    + requiredFeature
                    + " in manifest to use this API");
        }

        @Override
        public boolean canPairWithoutPrompt(
                String packageName, String deviceMacAddress, int userId) {
            return CollectionUtils.any(
                    getAllAssociations(userId, packageName, deviceMacAddress),
                    a -> System.currentTimeMillis() - a.getTimeApprovedMs()
                            < PAIR_WITHOUT_PROMPT_WINDOW_MS);
        }

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
                String[] args, ShellCallback callback, ResultReceiver resultReceiver)
                throws RemoteException {
            new ShellCmd().exec(this, in, out, err, args, callback, resultReceiver);
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

            fout.append("Devices Last Nearby:").append('\n');
            for (int i = 0, size = mDevicesLastNearby.size(); i < size; i++) {
                String device = mDevicesLastNearby.keyAt(i);
                Date time = mDevicesLastNearby.valueAt(i);
                fout.append("  ").append(device).append(" -> ")
                        .append(sDateFormat.format(time)).append('\n');
            }

            fout.append("Discovery Service State:").append('\n');
            for (int i = 0, size = mServiceConnectors.size(); i < size; i++) {
                int userId = mServiceConnectors.keyAt(i);
                fout.append("  ")
                        .append("u").append(Integer.toString(userId)).append(": ")
                        .append(Objects.toString(mServiceConnectors.valueAt(i)))
                        .append('\n');
            }

            fout.append("Device Listener Services State:").append('\n');
            for (int i = 0, size = mDeviceListenerServiceConnectors.size(); i < size; i++) {
                int userId = mDeviceListenerServiceConnectors.keyAt(i);
                fout.append("  ")
                        .append("u").append(Integer.toString(userId)).append(": ")
                        .append(Objects.toString(mDeviceListenerServiceConnectors.valueAt(i)))
                        .append('\n');
            }
        }
    }

    private static int getCallingUserId() {
        return UserHandle.getUserId(Binder.getCallingUid());
    }

    private static boolean isCallerSystem() {
        return Binder.getCallingUid() == Process.SYSTEM_UID;
    }

    void addAssociation(Association association) {
        updateSpecialAccessPermissionForAssociatedPackage(association);
        recordAssociation(association);
    }

    void removeAssociation(int userId, String pkg, String deviceMacAddress) {
        updateAssociations(associations -> CollectionUtils.filter(associations, association -> {
            boolean notMatch = association.getUserId() != userId
                    || !Objects.equals(association.getDeviceMacAddress(), deviceMacAddress)
                    || !Objects.equals(association.getPackageName(), pkg);
            if (!notMatch) {
                onAssociationPreRemove(association);
            }
            return notMatch;
        }));
    }

    void onAssociationPreRemove(Association association) {
        String deviceProfile = association.getDeviceProfile();
        if (deviceProfile != null) {
            Association otherAssociationWithDeviceProfile = find(
                    getAllAssociations(association.getUserId()),
                    a -> !a.equals(association) && deviceProfile.equals(a.getDeviceProfile()));
            if (otherAssociationWithDeviceProfile != null) {
                Slog.i(LOG_TAG, "Not revoking " + deviceProfile
                        + " for " + association
                        + " - profile still present in " + otherAssociationWithDeviceProfile);
            } else {
                long identity = Binder.clearCallingIdentity();
                try {
                    mRoleManager.removeRoleHolderAsUser(
                            association.getDeviceProfile(),
                            association.getPackageName(),
                            RoleManager.MANAGE_HOLDERS_FLAG_DONT_KILL_APP,
                            UserHandle.of(association.getUserId()),
                            getContext().getMainExecutor(),
                            success -> {
                                if (!success) {
                                    Slog.e(LOG_TAG, "Failed to revoke device profile role "
                                            + association.getDeviceProfile()
                                            + " to " + association.getPackageName()
                                            + " for user " + association.getUserId());
                                }
                            });
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }

        if (association.isNotifyOnDeviceNearby()) {
            ServiceConnector<ICompanionDeviceService> serviceConnector =
                    mDeviceListenerServiceConnectors.forUser(association.getUserId())
                            .get(association.getPackageName());
            if (serviceConnector != null) {
                serviceConnector.unbind();
                restartBleScan();
            }
        }
    }

    private void updateSpecialAccessPermissionForAssociatedPackage(Association association) {
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
            Association association, PackageInfo packageInfo) {
        if (containsEither(packageInfo.requestedPermissions,
                android.Manifest.permission.RUN_IN_BACKGROUND,
                android.Manifest.permission.REQUEST_COMPANION_RUN_IN_BACKGROUND)) {
            mPowerWhitelistManager.addToWhitelist(packageInfo.packageName);
        } else {
            mPowerWhitelistManager.removeFromWhitelist(packageInfo.packageName);
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

        if (mCurrentlyConnectedDevices.contains(association.getDeviceMacAddress())) {
            grantDeviceProfile(association);
        }

        if (association.isNotifyOnDeviceNearby()) {
            restartBleScan();
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
        return Binder.withCleanCallingIdentity(PooledLambda.obtainSupplier((context, pkg, id) -> {
            try {
                return context.getPackageManager().getPackageInfoAsUser(
                        pkg,
                        PackageManager.GET_PERMISSIONS | PackageManager.GET_CONFIGURATIONS,
                        id);
            } catch (PackageManager.NameNotFoundException e) {
                Slog.e(LOG_TAG, "Failed to get PackageInfo for package " + pkg, e);
                return null;
            }
        }, getContext(), packageName, userId).recycleOnUse());
    }

    private void recordAssociation(Association association) {
        Slog.i(LOG_TAG, "recordAssociation(" + association + ")");
        updateAssociations(associations -> CollectionUtils.add(associations, association));
    }

    private void updateAssociations(Function<Set<Association>, Set<Association>> update) {
        updateAssociations(update, getCallingUserId());
    }

    private void updateAssociations(Function<Set<Association>, Set<Association>> update,
            int userId) {
        synchronized (mLock) {
            final Set<Association> old = getAllAssociations(userId);
            Set<Association> associations = new ArraySet<>(old);
            associations = update.apply(associations);
            Slog.i(LOG_TAG, "Updating associations: " + old + "  -->  " + associations);
            mCachedAssociations.put(userId, Collections.unmodifiableSet(associations));
            BackgroundThread.getHandler().sendMessage(PooledLambda.obtainMessage(
                    CompanionDeviceManagerService::persistAssociations,
                    this, associations, userId));

            updateAtm(userId, associations);
        }
    }

    private void updateAtm(int userId, Set<Association> associations) {
        final Set<Integer> companionAppUids = new ArraySet<>();
        for (Association association : associations) {
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
            // Make a copy of companionAppUids and send it to ActivityManager.
            mAmInternal.setCompanionAppUids(userId, new ArraySet<>(companionAppUids));
        }
    }

    private void persistAssociations(Set<Association> associations, int userId) {
        Slog.i(LOG_TAG, "Writing associations to disk: " + associations);
        final AtomicFile file = getStorageFileForUser(userId);
        synchronized (file) {
            file.write(out -> {
                XmlSerializer xml = Xml.newSerializer();
                try {
                    xml.setOutput(out, StandardCharsets.UTF_8.name());
                    xml.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
                    xml.startDocument(null, true);
                    xml.startTag(null, XML_TAG_ASSOCIATIONS);

                    forEach(associations, association -> {
                        XmlSerializer tag = xml.startTag(null, XML_TAG_ASSOCIATION)
                                .attribute(null, XML_ATTR_PACKAGE, association.getPackageName())
                                .attribute(null, XML_ATTR_DEVICE,
                                        association.getDeviceMacAddress());
                        if (association.getDeviceProfile() != null) {
                            tag.attribute(null, XML_ATTR_PROFILE, association.getDeviceProfile());
                            tag.attribute(null, XML_ATTR_NOTIFY_DEVICE_NEARBY,
                                    Boolean.toString(
                                            association.isNotifyOnDeviceNearby()));
                        }
                        tag.attribute(null, XML_ATTR_TIME_APPROVED,
                                Long.toString(association.getTimeApprovedMs()));
                        tag.endTag(null, XML_TAG_ASSOCIATION);
                    });

                    xml.endTag(null, XML_TAG_ASSOCIATIONS);
                    xml.endDocument();
                } catch (Exception e) {
                    Slog.e(LOG_TAG, "Error while writing associations file", e);
                    throw ExceptionUtils.propagate(e);
                }
            });
        }
    }

    private AtomicFile getStorageFileForUser(int userId) {
        return mUidToStorage.computeIfAbsent(userId, (u) ->
                new AtomicFile(new File(
                        //TODO deprecated method - what's the right replacement?
                        Environment.getUserSystemDirectory(u),
                        XML_FILE_NAME)));
    }

    @Nullable
    private Set<Association> getAllAssociations(int userId) {
        synchronized (mLock) {
            if (mCachedAssociations.get(userId) == null) {
                mCachedAssociations.put(userId, Collections.unmodifiableSet(
                        emptyIfNull(readAllAssociations(userId))));
                Slog.i(LOG_TAG, "Read associations from disk: " + mCachedAssociations);
            }
            return mCachedAssociations.get(userId);
        }
    }

    private List<UserInfo> getAllUsers() {
        long identity = Binder.clearCallingIdentity();
        try {
            return mUserManager.getUsers();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private Set<Association> getAllAssociations(int userId, @Nullable String packageFilter) {
        return CollectionUtils.filter(
                getAllAssociations(userId),
                // Null filter == get all associations
                a -> packageFilter == null || Objects.equals(packageFilter, a.getPackageName()));
    }

    private Set<Association> getAllAssociations() {
        long identity = Binder.clearCallingIdentity();
        try {
            ArraySet<Association> result = new ArraySet<>();
            for (UserInfo user : mUserManager.getAliveUsers()) {
                result.addAll(getAllAssociations(user.id));
            }
            return result;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }


    private Set<Association> getAllAssociations(
            int userId, @Nullable String packageFilter, @Nullable String addressFilter) {
        return CollectionUtils.filter(
                getAllAssociations(userId),
                // Null filter == get all associations
                a -> (packageFilter == null || Objects.equals(packageFilter, a.getPackageName()))
                        && (addressFilter == null
                                || Objects.equals(addressFilter, a.getDeviceMacAddress())));
    }

    private Set<Association> readAllAssociations(int userId) {
        final AtomicFile file = getStorageFileForUser(userId);

        if (!file.getBaseFile().exists()) return null;

        ArraySet<Association> result = null;
        final XmlPullParser parser = Xml.newPullParser();
        synchronized (file) {
            try (FileInputStream in = file.openRead()) {
                parser.setInput(in, StandardCharsets.UTF_8.name());
                int type;
                while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                    if (type != XmlPullParser.START_TAG
                            && !XML_TAG_ASSOCIATIONS.equals(parser.getName())) continue;

                    final String appPackage = parser.getAttributeValue(null, XML_ATTR_PACKAGE);
                    final String deviceAddress = parser.getAttributeValue(null, XML_ATTR_DEVICE);

                    final String profile = parser.getAttributeValue(null, XML_ATTR_PROFILE);
                    final boolean persistentGrants = Boolean.valueOf(
                            parser.getAttributeValue(null, XML_ATTR_NOTIFY_DEVICE_NEARBY));
                    final long timeApproved = parseLongOrDefault(
                            parser.getAttributeValue(null, XML_ATTR_TIME_APPROVED), 0L);

                    if (appPackage == null || deviceAddress == null) continue;

                    result = ArrayUtils.add(result,
                            new Association(userId, deviceAddress, appPackage,
                                    profile, persistentGrants, timeApproved));
                }
                return result;
            } catch (XmlPullParserException | IOException e) {
                Slog.e(LOG_TAG, "Error while reading associations file", e);
                return null;
            }
        }
    }

    void onDeviceConnected(String address) {
        Slog.d(LOG_TAG, "onDeviceConnected(address = " + address + ")");

        mCurrentlyConnectedDevices.add(address);

        for (UserInfo user : getAllUsers()) {
            for (Association association : getAllAssociations(user.id)) {
                if (Objects.equals(address, association.getDeviceMacAddress())) {
                    if (association.getDeviceProfile() != null) {
                        Slog.i(LOG_TAG, "Granting role " + association.getDeviceProfile()
                                + " to " + association.getPackageName()
                                + " due to device connected: " + association.getDeviceMacAddress());
                        grantDeviceProfile(association);
                    }
                }
            }
        }

        onDeviceNearby(address);
    }

    private void grantDeviceProfile(Association association) {
        Slog.i(LOG_TAG, "grantDeviceProfile(association = " + association + ")");

        if (association.getDeviceProfile() != null) {
            mRoleManager.addRoleHolderAsUser(
                    association.getDeviceProfile(),
                    association.getPackageName(),
                    RoleManager.MANAGE_HOLDERS_FLAG_DONT_KILL_APP,
                    UserHandle.of(association.getUserId()),
                    getContext().getMainExecutor(),
                    success -> {
                        if (!success) {
                            Slog.e(LOG_TAG, "Failed to grant device profile role "
                                    + association.getDeviceProfile()
                                    + " to " + association.getPackageName()
                                    + " for user " + association.getUserId());
                        }
                    });
        }
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

    private ServiceConnector<ICompanionDeviceService> getDeviceListenerServiceConnector(
            Association a) {
        return mDeviceListenerServiceConnectors.forUser(a.getUserId()).computeIfAbsent(
                a.getPackageName(),
                pkg -> createDeviceListenerServiceConnector(a));
    }

    private ServiceConnector<ICompanionDeviceService> createDeviceListenerServiceConnector(
            Association a) {
        List<ResolveInfo> resolveInfos = getContext().getPackageManager().queryIntentServicesAsUser(
                new Intent(CompanionDeviceService.SERVICE_INTERFACE), MATCH_ALL, a.getUserId());
        List<ResolveInfo> packageResolveInfos = filter(resolveInfos,
                info -> Objects.equals(info.serviceInfo.packageName, a.getPackageName()));
        if (packageResolveInfos.size() != 1) {
            Slog.w(LOG_TAG, "Device presence listener package must have exactly one "
                    + "CompanionDeviceService, but " + a.getPackageName()
                    + " has " + packageResolveInfos.size());
            return new ServiceConnector.NoOp<>();
        }
        ComponentName componentName = packageResolveInfos.get(0).serviceInfo.getComponentName();
        Slog.i(LOG_TAG, "Initializing CompanionDeviceService binding for " + componentName);
        return new ServiceConnector.Impl<ICompanionDeviceService>(getContext(),
                new Intent(CompanionDeviceService.SERVICE_INTERFACE).setComponent(componentName),
                BIND_IMPORTANT,
                a.getUserId(),
                ICompanionDeviceService.Stub::asInterface) {

            @Override
            protected long getAutoDisconnectTimeoutMs() {
                // Service binding is managed manually based on corresponding device being nearby
                return Long.MAX_VALUE;
            }
        };
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
            Slog.i(LOG_TAG, "UnbindDeviceListenersRunnable.run(); devicesNearby = "
                    + mDevicesLastNearby);
            int size = mDevicesLastNearby.size();
            for (int i = 0; i < size; i++) {
                String address = mDevicesLastNearby.keyAt(i);
                Date lastNearby = mDevicesLastNearby.valueAt(i);

                if (isDeviceDisappeared(lastNearby)) {
                    for (Association association : getAllAssociations(address)) {
                        if (association.isNotifyOnDeviceNearby()) {
                            getDeviceListenerServiceConnector(association).unbind();
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

    private Set<Association> getAllAssociations(String deviceAddress) {
        List<UserInfo> aliveUsers = mUserManager.getAliveUsers();
        Set<Association> result = new ArraySet<>();
        for (int i = 0, size = aliveUsers.size(); i < size; i++) {
            UserInfo user = aliveUsers.get(i);
            for (Association association : getAllAssociations(user.id)) {
                if (Objects.equals(association.getDeviceMacAddress(), deviceAddress)) {
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
            for (Association association : getAllAssociations(address)) {
                if (association.isNotifyOnDeviceNearby()) {
                    Slog.i(LOG_TAG,
                            "Sending onDeviceAppeared to " + association.getPackageName() + ")");
                    getDeviceListenerServiceConnector(association).run(
                            service -> service.onDeviceAppeared(association.getDeviceMacAddress()));
                }
            }
        }
    }

    private void onDeviceDisappeared(String address) {
        Slog.i(LOG_TAG, "onDeviceDisappeared(address = " + address + ")");

        boolean hasDeviceListeners = false;
        for (Association association : getAllAssociations(address)) {
            if (association.isNotifyOnDeviceNearby()) {
                Slog.i(LOG_TAG,
                        "Sending onDeviceDisappeared to " + association.getPackageName() + ")");
                getDeviceListenerServiceConnector(association).run(
                        service -> service.onDeviceDisappeared(address));
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
        mBluetoothAdapter.getBluetoothLeScanner().stopScan(mBleScanCallback);
        startBleScan();
    }

    private List<ScanFilter> getBleScanFilters() {
        ArrayList<ScanFilter> result = new ArrayList<>();
        ArraySet<String> addressesSeen = new ArraySet<>();
        for (Association association : getAllAssociations()) {
            String address = association.getDeviceMacAddress();
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

    private AndroidFuture<String> getDeviceProfilePermissionDescription(String deviceProfile) {
        AndroidFuture<String> result = new AndroidFuture<>();
        mPermissionControllerManager.getPrivilegesDescriptionStringForProfile(
                deviceProfile, FgThread.getExecutor(), desc -> {
                        try {
                            result.complete(String.valueOf(desc));
                        } catch (Exception e) {
                            result.completeExceptionally(e);
                        }
                });
        return result;
    }

    private static long parseLongOrDefault(String str, long def) {
        try {
            return Long.parseLong(str);
        } catch (NumberFormatException e) {
            Slog.w(LOG_TAG, "Failed to parse", e);
            return def;
        }
    }

    private class ShellCmd extends ShellCommand {
        public static final String USAGE = "help\n"
                + "list USER_ID\n"
                + "associate USER_ID PACKAGE MAC_ADDRESS\n"
                + "disassociate USER_ID PACKAGE MAC_ADDRESS";

        ShellCmd() {
            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.MANAGE_COMPANION_DEVICES, "ShellCmd");
        }

        @Override
        public int onCommand(String cmd) {
            try {
                switch (cmd) {
                    case "list": {
                        forEach(
                                getAllAssociations(getNextArgInt()),
                                a -> getOutPrintWriter()
                                        .println(a.getPackageName() + " "
                                                + a.getDeviceMacAddress()));
                    }
                    break;

                    case "associate": {
                        int userId = getNextArgInt();
                        String pkg = getNextArgRequired();
                        String address = getNextArgRequired();
                        addAssociation(new Association(userId, address, pkg, null, false,
                                System.currentTimeMillis()));
                    }
                    break;

                    case "disassociate": {
                        removeAssociation(getNextArgInt(), getNextArgRequired(),
                                getNextArgRequired());
                    }
                    break;

                    case "simulate_connect": {
                        onDeviceConnected(getNextArgRequired());
                    }
                    break;

                    case "simulate_disconnect": {
                        onDeviceDisconnected(getNextArgRequired());
                    }
                    break;

                    default:
                        return handleDefaultCommands(cmd);
                }
                return 0;
            } catch (Throwable t) {
                Slog.e(LOG_TAG, "Error running a command: $ " + cmd, t);
                getErrPrintWriter().println(Log.getStackTraceString(t));
                return 1;
            }
        }

        private int getNextArgInt() {
            return Integer.parseInt(getNextArgRequired());
        }

        @Override
        public void onHelp() {
            getOutPrintWriter().println(USAGE);
        }
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
}
