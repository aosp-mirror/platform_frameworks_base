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

import static com.android.internal.util.CollectionUtils.size;
import static com.android.internal.util.FunctionalUtils.uncheckExceptions;
import static com.android.internal.util.Preconditions.checkArgument;
import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.internal.util.Preconditions.checkState;
import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;
import static com.android.internal.util.function.pooled.PooledLambda.obtainRunnable;

import static java.util.concurrent.TimeUnit.MINUTES;

import android.annotation.CheckResult;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.companion.Association;
import android.companion.AssociationRequest;
import android.companion.CompanionDeviceManager;
import android.companion.ICompanionDeviceDiscoveryService;
import android.companion.ICompanionDeviceManager;
import android.companion.IFindDeviceCallback;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.net.NetworkPolicyManager;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.IDeviceIdleController;
import android.os.IInterface;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.UserHandle;
import android.os.UserManagerInternal;
import android.provider.Settings;
import android.provider.SettingsStringUtil.ComponentNameSet;
import android.text.BidiFormatter;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.ExceptionUtils;
import android.util.Log;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.app.IAppOpsService;
import com.android.internal.content.PackageMonitor;
import com.android.internal.infra.AndroidFuture;
import com.android.internal.infra.PerUser;
import com.android.internal.infra.ServiceConnector;
import com.android.internal.notification.NotificationAccessConfirmationActivityContract;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.wm.ActivityTaskManagerInternal;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

//TODO onStop schedule unbind in 5 seconds
//TODO make sure APIs are only callable from currently focused app
//TODO schedule stopScan on activity destroy(except if configuration change)
//TODO on associate called again after configuration change -> replace old callback with new
//TODO avoid leaking calling activity in IFindDeviceCallback (see PrintManager#print for example)
/** @hide */
public class CompanionDeviceManagerService extends SystemService implements Binder.DeathRecipient {

    private static final ComponentName SERVICE_TO_BIND_TO = ComponentName.createRelative(
            CompanionDeviceManager.COMPANION_DEVICE_DISCOVERY_PACKAGE_NAME,
            ".DeviceDiscoveryService");

    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "CompanionDeviceManagerService";

    private static final String PREF_FILE_NAME = "companion_device_preferences.xml";
    private static final String PREF_KEY_AUTO_REVOKE_GRANTS_DONE = "auto_revoke_grants_done";

    private static final String XML_TAG_ASSOCIATIONS = "associations";
    private static final String XML_TAG_ASSOCIATION = "association";
    private static final String XML_ATTR_PACKAGE = "package";
    private static final String XML_ATTR_DEVICE = "device";
    private static final String XML_FILE_NAME = "companion_device_manager_associations.xml";

    private final CompanionDeviceManagerImpl mImpl;
    private final ConcurrentMap<Integer, AtomicFile> mUidToStorage = new ConcurrentHashMap<>();
    private IDeviceIdleController mIdleController;
    private PerUser<ServiceConnector<ICompanionDeviceDiscoveryService>> mServiceConnectors;
    private IAppOpsService mAppOpsManager;

    private IFindDeviceCallback mFindDeviceCallback;
    private AssociationRequest mRequest;
    private String mCallingPackage;
    private AndroidFuture<Association> mOngoingDeviceDiscovery;

    private final Object mLock = new Object();

    public CompanionDeviceManagerService(Context context) {
        super(context);
        mImpl = new CompanionDeviceManagerImpl();
        mIdleController = IDeviceIdleController.Stub.asInterface(
                ServiceManager.getService(Context.DEVICE_IDLE_CONTROLLER));
        mAppOpsManager = IAppOpsService.Stub.asInterface(
                ServiceManager.getService(Context.APP_OPS_SERVICE));

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

        registerPackageMonitor();
    }

    private void registerPackageMonitor() {
        new PackageMonitor() {
            @Override
            public void onPackageRemoved(String packageName, int uid) {
                updateAssociations(
                        as -> CollectionUtils.filter(as,
                                a -> !Objects.equals(a.companionAppPackage, packageName)),
                        getChangingUserId());
            }

            @Override
            public void onPackageModified(String packageName) {
                int userId = getChangingUserId();
                if (!ArrayUtils.isEmpty(readAllAssociations(userId, packageName))) {
                    updateSpecialAccessPermissionForAssociatedPackage(packageName, userId);
                }
            }

        }.register(getContext(), FgThread.get().getLooper(), UserHandle.ALL, true);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.COMPANION_DEVICE_SERVICE, mImpl);
    }

    @Override
    public void onUnlockUser(int userHandle) {
        Set<Association> associations = readAllAssociations(userHandle);
        if (associations == null || associations.isEmpty()) {
            return;
        }
        Set<String> companionAppPackages = new HashSet<>();
        for (Association association : associations) {
            companionAppPackages.add(association.companionAppPackage);
        }
        ActivityTaskManagerInternal atmInternal = LocalServices.getService(
                ActivityTaskManagerInternal.class);
        if (atmInternal != null) {
            atmInternal.setCompanionAppPackages(userHandle, companionAppPackages);
        }

        BackgroundThread.getHandler().sendMessageDelayed(
                obtainMessage(CompanionDeviceManagerService::maybeGrantAutoRevokeExemptions, this),
                MINUTES.toMillis(10));
    }

    void maybeGrantAutoRevokeExemptions() {
        PackageManager pm = getContext().getPackageManager();
        for (int userId : LocalServices.getService(UserManagerInternal.class).getUserIds()) {
            SharedPreferences pref = getContext().getSharedPreferences(
                    new File(Environment.getUserSystemDirectory(userId), PREF_FILE_NAME),
                    Context.MODE_PRIVATE);
            if (pref.getBoolean(PREF_KEY_AUTO_REVOKE_GRANTS_DONE, false)) {
                continue;
            }

            try {
                Set<Association> associations = readAllAssociations(userId);
                if (associations == null) {
                    continue;
                }
                for (Association a : associations) {
                    try {
                        int uid = pm.getPackageUidAsUser(a.companionAppPackage, userId);
                        exemptFromAutoRevoke(a.companionAppPackage, uid);
                    } catch (PackageManager.NameNotFoundException e) {
                        Log.w(LOG_TAG, "Unknown companion package: " + a.companionAppPackage, e);
                    }
                }
            } finally {
                pref.edit().putBoolean(PREF_KEY_AUTO_REVOKE_GRANTS_DONE, true).apply();
            }
        }
    }

    @Override
    public void binderDied() {
        Handler.getMain().post(this::cleanup);
    }

    private void cleanup() {
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
            if (DEBUG) {
                Slog.i(LOG_TAG, "associate(request = " + request + ", callback = " + callback
                        + ", callingPackage = " + callingPackage + ")");
            }
            checkNotNull(request, "Request cannot be null");
            checkNotNull(callback, "Callback cannot be null");
            checkCallerIsSystemOr(callingPackage);
            int userId = getCallingUserId();
            checkUsesFeature(callingPackage, userId);

            mFindDeviceCallback = callback;
            mRequest = request;
            mCallingPackage = callingPackage;
            request.setCallingPackage(callingPackage);
            callback.asBinder().linkToDeath(CompanionDeviceManagerService.this /* recipient */, 0);

            final long callingIdentity = Binder.clearCallingIdentity();
            try {
                mOngoingDeviceDiscovery = mServiceConnectors.forUser(userId).postAsync(service -> {
                    AndroidFuture<Association> future = new AndroidFuture<>();
                    service.startDiscovery(request, callingPackage, callback, future);
                    return future;
                }).cancelTimeout().whenComplete(uncheckExceptions((association, err) -> {
                    if (err == null) {
                        addAssociation(association);
                    } else {
                        Log.e(LOG_TAG, "Failed to discover device(s)", err);
                        callback.onFailure("No devices found: " + err.getMessage());
                    }
                    cleanup();
                }));
            } finally {
                Binder.restoreCallingIdentity(callingIdentity);
            }
        }

        @Override
        public void stopScan(AssociationRequest request,
                IFindDeviceCallback callback,
                String callingPackage) {
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
            return new ArrayList<>(CollectionUtils.map(
                    readAllAssociations(userId, callingPackage),
                    a -> a.deviceAddress));
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
            long identity = Binder.clearCallingIdentity();
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

            return CollectionUtils.any(
                    readAllAssociations(userId, packageName),
                    a -> Objects.equals(a.deviceAddress, macAddress));
        }

        private void checkCanCallNotificationApi(String callingPackage) throws RemoteException {
            checkCallerIsSystemOr(callingPackage);
            int userId = getCallingUserId();
            checkState(!ArrayUtils.isEmpty(readAllAssociations(userId, callingPackage)),
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
        public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
                String[] args, ShellCallback callback, ResultReceiver resultReceiver)
                throws RemoteException {
            new ShellCmd().exec(this, in, out, err, args, callback, resultReceiver);
        }
    }

    private static int getCallingUserId() {
        return UserHandle.getUserId(Binder.getCallingUid());
    }

    private static boolean isCallerSystem() {
        return Binder.getCallingUid() == Process.SYSTEM_UID;
    }

    void addAssociation(int userId, String packageName, String deviceAddress) {
        addAssociation(new Association(userId, deviceAddress, packageName));
    }

    void addAssociation(Association association) {
        updateSpecialAccessPermissionForAssociatedPackage(
                association.companionAppPackage, association.userId);
        recordAssociation(association);
    }

    void removeAssociation(int userId, String pkg, String deviceMacAddress) {
        updateAssociations(associations -> CollectionUtils.remove(associations,
                new Association(userId, deviceMacAddress, pkg)));
    }

    private void updateSpecialAccessPermissionForAssociatedPackage(String packageName, int userId) {
        PackageInfo packageInfo = getPackageInfo(packageName, userId);
        if (packageInfo == null) {
            return;
        }

        Binder.withCleanCallingIdentity(obtainRunnable(CompanionDeviceManagerService::
                updateSpecialAccessPermissionAsSystem, this, packageInfo).recycleOnUse());
    }

    private void updateSpecialAccessPermissionAsSystem(PackageInfo packageInfo) {
        try {
            if (containsEither(packageInfo.requestedPermissions,
                    android.Manifest.permission.RUN_IN_BACKGROUND,
                    android.Manifest.permission.REQUEST_COMPANION_RUN_IN_BACKGROUND)) {
                mIdleController.addPowerSaveWhitelistApp(packageInfo.packageName);
            } else {
                mIdleController.removePowerSaveWhitelistApp(packageInfo.packageName);
            }
        } catch (RemoteException e) {
            /* ignore - local call */
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
    }

    private void exemptFromAutoRevoke(String packageName, int uid) {
        try {
            mAppOpsManager.setMode(
                    AppOpsManager.OP_AUTO_REVOKE_PERMISSIONS_IF_UNUSED,
                    uid,
                    packageName,
                    AppOpsManager.MODE_IGNORED);
        } catch (RemoteException e) {
            Log.w(LOG_TAG,
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
        if (DEBUG) {
            Log.i(LOG_TAG, "recordAssociation(" + association + ")");
        }
        updateAssociations(associations -> CollectionUtils.add(associations, association),
                        association.userId);
    }

    private void recordAssociation(String privilegedPackage, String deviceAddress) {
        recordAssociation(new Association(getCallingUserId(), deviceAddress, privilegedPackage));
    }

    private void updateAssociations(Function<Set<Association>, Set<Association>> update) {
        updateAssociations(update, getCallingUserId());
    }

    private void updateAssociations(Function<Set<Association>, Set<Association>> update,
            int userId) {
        final AtomicFile file = getStorageFileForUser(userId);
        synchronized (file) {
            Set<Association> associations = readAllAssociations(userId);
            final Set<Association> old = CollectionUtils.copyOf(associations);
            associations = update.apply(associations);
            if (size(old) == size(associations)) return;

            Set<Association> finalAssociations = associations;
            Set<String> companionAppPackages = new HashSet<>();
            for (Association association : finalAssociations) {
                companionAppPackages.add(association.companionAppPackage);
            }

            file.write((out) -> {
                XmlSerializer xml = Xml.newSerializer();
                try {
                    xml.setOutput(out, StandardCharsets.UTF_8.name());
                    xml.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
                    xml.startDocument(null, true);
                    xml.startTag(null, XML_TAG_ASSOCIATIONS);

                    CollectionUtils.forEach(finalAssociations, association -> {
                        xml.startTag(null, XML_TAG_ASSOCIATION)
                                .attribute(null, XML_ATTR_PACKAGE, association.companionAppPackage)
                                .attribute(null, XML_ATTR_DEVICE, association.deviceAddress)
                                .endTag(null, XML_TAG_ASSOCIATION);
                    });

                    xml.endTag(null, XML_TAG_ASSOCIATIONS);
                    xml.endDocument();
                } catch (Exception e) {
                    Slog.e(LOG_TAG, "Error while writing associations file", e);
                    throw ExceptionUtils.propagate(e);
                }

            });
            ActivityTaskManagerInternal atmInternal = LocalServices.getService(
                    ActivityTaskManagerInternal.class);
            atmInternal.setCompanionAppPackages(userId, companionAppPackages);
        }
    }

    private AtomicFile getStorageFileForUser(int uid) {
        return mUidToStorage.computeIfAbsent(uid, (u) ->
                new AtomicFile(new File(
                        //TODO deprecated method - what's the right replacement?
                        Environment.getUserSystemDirectory(u),
                        XML_FILE_NAME)));
    }

    @Nullable
    private Set<Association> readAllAssociations(int userId) {
        return readAllAssociations(userId, null);
    }

    @Nullable
    private Set<Association> readAllAssociations(int userId, @Nullable String packageFilter) {
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

                    if (appPackage == null || deviceAddress == null) continue;
                    if (packageFilter != null && !packageFilter.equals(appPackage)) continue;

                    result = ArrayUtils.add(result,
                            new Association(userId, deviceAddress, appPackage));
                }
                return result;
            } catch (XmlPullParserException | IOException e) {
                Slog.e(LOG_TAG, "Error while reading associations file", e);
                return null;
            }
        }
    }

    void onDeviceConnected(String address) {
    }

    void onDeviceDisconnected(String address) {
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
            switch (cmd) {
                case "list": {
                    CollectionUtils.forEach(
                            readAllAssociations(getNextArgInt()),
                            a -> getOutPrintWriter()
                                    .println(a.companionAppPackage + " " + a.deviceAddress));
                } break;

                case "associate": {
                    addAssociation(getNextArgInt(), getNextArgRequired(), getNextArgRequired());
                } break;

                case "disassociate": {
                    removeAssociation(getNextArgInt(), getNextArgRequired(), getNextArgRequired());
                } break;

                default: return handleDefaultCommands(cmd);
            }
            return 0;
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
