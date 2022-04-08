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
import static android.content.pm.PackageManager.CERT_INPUT_SHA256;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Process.SYSTEM_UID;
import static android.os.UserHandle.getCallingUserId;

import static com.android.internal.util.CollectionUtils.any;
import static com.android.internal.util.Preconditions.checkState;
import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;
import static com.android.server.companion.AssociationStore.CHANGE_TYPE_UPDATED_ADDRESS_UNCHANGED;
import static com.android.server.companion.PackageUtils.enforceUsesCompanionDeviceFeature;
import static com.android.server.companion.PackageUtils.getPackageInfo;
import static com.android.server.companion.PermissionsUtils.checkCallerCanManageCompanionDevice;
import static com.android.server.companion.PermissionsUtils.enforceCallerCanManageAssociationsForPackage;
import static com.android.server.companion.PermissionsUtils.enforceCallerCanManageCompanionDevice;
import static com.android.server.companion.PermissionsUtils.enforceCallerIsSystemOr;
import static com.android.server.companion.PermissionsUtils.enforceCallerIsSystemOrCanInteractWithUserId;
import static com.android.server.companion.PermissionsUtils.sanitizeWithCallerChecks;
import static com.android.server.companion.RolesUtils.addRoleHolderForAssociation;
import static com.android.server.companion.RolesUtils.removeRoleHolderForAssociation;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.MINUTES;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.DeviceNotAssociatedException;
import android.companion.IAssociationRequestCallback;
import android.companion.ICompanionDeviceManager;
import android.companion.IOnAssociationsChangedListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
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
import android.os.Message;
import android.os.Parcel;
import android.os.PowerWhitelistManager;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.BidiFormatter;
import android.util.ArraySet;
import android.util.ExceptionUtils;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IAppOpsService;
import com.android.internal.content.PackageMonitor;
import com.android.internal.notification.NotificationAccessConfirmationActivityContract;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.companion.presence.CompanionDevicePresenceMonitor;
import com.android.server.pm.UserManagerInternal;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SuppressLint("LongLogTag")
public class CompanionDeviceManagerService extends SystemService {
    static final String TAG = "CompanionDeviceManagerService";
    static final boolean DEBUG = false;

    /** Range of Association IDs allocated for a user.*/
    private static final int ASSOCIATIONS_IDS_PER_USER_RANGE = 100000;
    private static final long PAIR_WITHOUT_PROMPT_WINDOW_MS = 10 * 60 * 1000; // 10 min

    private static final String PREF_FILE_NAME = "companion_device_preferences.xml";
    private static final String PREF_KEY_AUTO_REVOKE_GRANTS_DONE = "auto_revoke_grants_done";

    private static final long ASSOCIATION_CLEAN_UP_TIME_WINDOW = DAYS.toMillis(3 * 30); // 3 months

    private PersistentDataStore mPersistentStore;
    private final PersistUserStateHandler mUserPersistenceHandler;

    private final AssociationStoreImpl mAssociationStore;
    private AssociationRequestsProcessor mAssociationRequestsProcessor;
    private CompanionDevicePresenceMonitor mDevicePresenceMonitor;
    private CompanionApplicationController mCompanionAppController;

    private final ActivityManagerInternal mAmInternal;
    private final IAppOpsService mAppOpsManager;
    private final PowerWhitelistManager mPowerWhitelistManager;
    private final UserManager mUserManager;
    final PackageManagerInternal mPackageManagerInternal;

    /**
     * A structure that consists of two nested maps, and effectively maps (userId + packageName) to
     * a list of IDs that have been previously assigned to associations for that package.
     * We maintain this structure so that we never re-use association IDs for the same package
     * (until it's uninstalled).
     */
    @GuardedBy("mPreviouslyUsedIds")
    private final SparseArray<Map<String, Set<Integer>>> mPreviouslyUsedIds = new SparseArray<>();

    private final RemoteCallbackList<IOnAssociationsChangedListener> mListeners =
            new RemoteCallbackList<>();

    public CompanionDeviceManagerService(Context context) {
        super(context);

        mPowerWhitelistManager = context.getSystemService(PowerWhitelistManager.class);
        mAppOpsManager = IAppOpsService.Stub.asInterface(
                ServiceManager.getService(Context.APP_OPS_SERVICE));
        mAmInternal = LocalServices.getService(ActivityManagerInternal.class);
        mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
        mUserManager = context.getSystemService(UserManager.class);

        mUserPersistenceHandler = new PersistUserStateHandler();
        mAssociationStore = new AssociationStoreImpl();
    }

    @Override
    public void onStart() {
        mPersistentStore = new PersistentDataStore();

        loadAssociationsFromDisk();
        mAssociationStore.registerListener(mAssociationStoreChangeListener);

        mDevicePresenceMonitor = new CompanionDevicePresenceMonitor(
                mAssociationStore, mDevicePresenceCallback);

        mAssociationRequestsProcessor = new AssociationRequestsProcessor(
                /* cdmService */this, mAssociationStore);

        final Context context = getContext();
        mCompanionAppController = new CompanionApplicationController(
                context, mApplicationControllerCallback);

        // Publish "binder" service.
        final CompanionDeviceManagerImpl impl = new CompanionDeviceManagerImpl();
        publishBinderService(Context.COMPANION_DEVICE_SERVICE, impl);

        // Publish "local" service.
        LocalServices.addService(CompanionDeviceManagerServiceInternal.class, new LocalService());
    }

    void loadAssociationsFromDisk() {
        final Set<AssociationInfo> allAssociations = new ArraySet<>();
        synchronized (mPreviouslyUsedIds) {
            // The data is stored in DE directories, so we can read the data for all users now
            // (which would not be possible if the data was stored to CE directories).
            mPersistentStore.readStateForUsers(
                    mUserManager.getAliveUsers(), allAssociations, mPreviouslyUsedIds);
        }

        mAssociationStore.setAssociations(allAssociations);
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
            // Run the Association CleanUp job service daily.
            AssociationCleanUpService.schedule(getContext());
        }
    }

    @Override
    public void onUserUnlocking(@NonNull TargetUser user) {
        final int userId = user.getUserIdentifier();
        final List<AssociationInfo> associations = mAssociationStore.getAssociationsForUser(userId);

        if (associations.isEmpty()) return;

        updateAtm(userId, associations);

        BackgroundThread.getHandler().sendMessageDelayed(
                obtainMessage(CompanionDeviceManagerService::maybeGrantAutoRevokeExemptions, this),
                MINUTES.toMillis(10));
    }

    @Nullable
    AssociationInfo getAssociationWithCallerChecks(
            @UserIdInt int userId, @NonNull String packageName, @NonNull String macAddress) {
        final AssociationInfo association = mAssociationStore.getAssociationsForPackageWithAddress(
                userId, packageName, macAddress);
        return sanitizeWithCallerChecks(getContext(), association);
    }

    @Nullable
    AssociationInfo getAssociationWithCallerChecks(int associationId) {
        final AssociationInfo association = mAssociationStore.getAssociationById(associationId);
        return sanitizeWithCallerChecks(getContext(), association);
    }

    private void onDeviceAppearedInternal(int associationId) {
        if (DEBUG) Log.i(TAG, "onDevice_Appeared_Internal() id=" + associationId);

        final AssociationInfo association = mAssociationStore.getAssociationById(associationId);
        if (DEBUG) Log.d(TAG, "  association=" + associationId);

        if (!association.shouldBindWhenPresent()) return;

        final int userId = association.getUserId();
        final String packageName = association.getPackageName();
        // Set bindImportant to true when the association is self-managed to avoid the target
        // service being killed.
        final boolean bindImportant = association.isSelfManaged();

        if (!mCompanionAppController.isCompanionApplicationBound(userId, packageName)) {
            mCompanionAppController.bindCompanionApplication(userId, packageName, bindImportant);
        } else if (DEBUG) {
            Log.i(TAG, "u" + userId + "\\" + packageName + " is already bound");
        }
        mCompanionAppController.notifyCompanionApplicationDeviceAppeared(association);
    }

    private void onDeviceDisappearedInternal(int associationId) {
        if (DEBUG) Log.i(TAG, "onDevice_Disappeared_Internal() id=" + associationId);

        final AssociationInfo association = mAssociationStore.getAssociationById(associationId);
        if (DEBUG) Log.d(TAG, "  association=" + associationId);

        final int userId = association.getUserId();
        final String packageName = association.getPackageName();

        if (!mCompanionAppController.isCompanionApplicationBound(userId, packageName)) {
            if (DEBUG) Log.w(TAG, "u" + userId + "\\" + packageName + " is NOT bound");
            return;
        }

        if (association.shouldBindWhenPresent()) {
            mCompanionAppController.notifyCompanionApplicationDeviceDisappeared(association);
        }

        // Check if there are other devices associated to the app that are present.
        if (shouldBindPackage(userId, packageName)) return;

        mCompanionAppController.unbindCompanionApplication(userId, packageName);
    }

    private boolean onCompanionApplicationBindingDiedInternal(
            @UserIdInt int userId, @NonNull String packageName) {
        // TODO(b/218613015): implement.
        return false;
    }

    private void onRebindCompanionApplicationTimeoutInternal(
            @UserIdInt int userId, @NonNull String packageName) {
        // TODO(b/218613015): implement.
    }

    /**
     * @return whether the package should be bound (i.e. at least one of the devices associated with
     *         the package is currently present).
     */
    private boolean shouldBindPackage(@UserIdInt int userId, @NonNull String packageName) {
        final List<AssociationInfo> packageAssociations =
                mAssociationStore.getAssociationsForPackage(userId, packageName);
        for (AssociationInfo association : packageAssociations) {
            if (!association.shouldBindWhenPresent()) continue;
            if (mDevicePresenceMonitor.isDevicePresent(association.getId())) return true;
        }
        return false;
    }

    private void onAssociationChangedInternal(
            @AssociationStore.ChangeType int changeType, AssociationInfo association) {
        final int id = association.getId();
        final int userId = association.getUserId();
        final String packageName = association.getPackageName();

        if (changeType == AssociationStore.CHANGE_TYPE_REMOVED) {
            markIdAsPreviouslyUsedForPackage(id, userId, packageName);
        }

        final List<AssociationInfo> updatedAssociations =
                mAssociationStore.getAssociationsForUser(userId);

        mUserPersistenceHandler.postPersistUserState(userId);

        // Notify listeners if ADDED, REMOVED or UPDATED_ADDRESS_CHANGED.
        // Do NOT notify when UPDATED_ADDRESS_UNCHANGED, which means a minor tweak in association's
        // configs, which "listeners" won't (and shouldn't) be able to see.
        if (changeType != CHANGE_TYPE_UPDATED_ADDRESS_UNCHANGED) {
            notifyListeners(userId, updatedAssociations);
        }
        updateAtm(userId, updatedAssociations);
    }

    private void persistStateForUser(@UserIdInt int userId) {
        final List<AssociationInfo> updatedAssociations =
                mAssociationStore.getAssociationsForUser(userId);
        final Map<String, Set<Integer>> usedIdsForUser = getPreviouslyUsedIdsForUser(userId);
        mPersistentStore.persistStateForUser(userId, updatedAssociations, usedIdsForUser);
    }

    private void notifyListeners(
            @UserIdInt int userId, @NonNull List<AssociationInfo> associations) {
        mListeners.broadcast((listener, callbackUserId) -> {
            if ((int) callbackUserId == userId) {
                try {
                    listener.onAssociationsChanged(associations);
                } catch (RemoteException ignored) {
                }
            }
        });
    }

    private void markIdAsPreviouslyUsedForPackage(
            int associationId, @UserIdInt int userId, @NonNull String packageName) {
        synchronized (mPreviouslyUsedIds) {
            Map<String, Set<Integer>> usedIdsForUser = mPreviouslyUsedIds.get(userId);
            if (usedIdsForUser == null) {
                usedIdsForUser = new HashMap<>();
                mPreviouslyUsedIds.put(userId, usedIdsForUser);
            }

            final Set<Integer> usedIdsForPackage =
                    usedIdsForUser.computeIfAbsent(packageName, it -> new HashSet<>());
            usedIdsForPackage.add(associationId);
        }
    }

    private void onPackageRemoveOrDataClearedInternal(
            @UserIdInt int userId, @NonNull String packageName) {
        if (DEBUG) {
            Log.i(TAG, "onPackageRemove_Or_DataCleared() u" + userId + "/"
                    + packageName);
        }

        // Clear associations.
        final List<AssociationInfo> associationsForPackage =
                mAssociationStore.getAssociationsForPackage(userId, packageName);
        for (AssociationInfo association : associationsForPackage) {
            mAssociationStore.removeAssociation(association.getId());
        }

        mCompanionAppController.onPackagesChanged(userId);
    }

    private void onPackageModifiedInternal(@UserIdInt int userId, @NonNull String packageName) {
        if (DEBUG) Log.i(TAG, "onPackageModified() u" + userId + "/" + packageName);

        final List<AssociationInfo> associationsForPackage =
                mAssociationStore.getAssociationsForPackage(userId, packageName);
        for (AssociationInfo association : associationsForPackage) {
            updateSpecialAccessPermissionForAssociatedPackage(association);
        }

        mCompanionAppController.onPackagesChanged(userId);
    }

    // Revoke associations if the selfManaged companion device does not connect for 3
    // months for specific profile.
    private void associationCleanUp(String profile) {
        for (AssociationInfo ai : mAssociationStore.getAssociations()) {
            if (ai.isSelfManaged()
                    && profile.equals(ai.getDeviceProfile())
                    && System.currentTimeMillis() - ai.getLastTimeConnectedMs()
                    >= ASSOCIATION_CLEAN_UP_TIME_WINDOW) {
                Slog.i(TAG, "Removing the association for associationId: "
                        + ai.getId()
                        + " due to the device does not connect for 3 months.");
                disassociateInternal(ai.getId());
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
        public List<AssociationInfo> getAssociations(String packageName, int userId) {
            enforceCallerCanManageAssociationsForPackage(getContext(), userId, packageName,
                    "get associations");

            if (!checkCallerCanManageCompanionDevice(getContext())) {
                // If the caller neither is system nor holds MANAGE_COMPANION_DEVICES: it needs to
                // request the feature (also: the caller is the app itself).
                enforceUsesCompanionDeviceFeature(getContext(), userId, packageName);
            }

            return mAssociationStore.getAssociationsForPackage(userId, packageName);
        }

        @Override
        public List<AssociationInfo> getAllAssociationsForUser(int userId) throws RemoteException {
            enforceCallerIsSystemOrCanInteractWithUserId(getContext(), userId);
            enforceCallerCanManageCompanionDevice(getContext(), "getAllAssociationsForUser");

            return mAssociationStore.getAssociationsForUser(userId);
        }

        @Override
        public void addOnAssociationsChangedListener(IOnAssociationsChangedListener listener,
                int userId) {
            enforceCallerIsSystemOrCanInteractWithUserId(getContext(), userId);
            enforceCallerCanManageCompanionDevice(getContext(),
                    "addOnAssociationsChangedListener");

            mListeners.register(listener, userId);
        }

        @Override
        public void removeOnAssociationsChangedListener(IOnAssociationsChangedListener listener,
                int userId) {
            enforceCallerIsSystemOrCanInteractWithUserId(getContext(), userId);
            enforceCallerCanManageCompanionDevice(
                    getContext(), "removeOnAssociationsChangedListener");

            mListeners.unregister(listener);
        }

        @Override
        public void legacyDisassociate(String deviceMacAddress, String packageName, int userId) {
            if (DEBUG) {
                Log.i(TAG, "legacyDisassociate() pkg=u" + userId + "/" + packageName
                        + ", macAddress=" + deviceMacAddress);
            }

            requireNonNull(deviceMacAddress);
            requireNonNull(packageName);

            final AssociationInfo association =
                    getAssociationWithCallerChecks(userId, packageName, deviceMacAddress);
            if (association == null) {
                throw new IllegalArgumentException("Association does not exist "
                        + "or the caller does not have permissions to manage it "
                        + "(ie. it belongs to a different package or a different user).");
            }

            disassociateInternal(association.getId());
        }

        @Override
        public void disassociate(int associationId) {
            if (DEBUG) Log.i(TAG, "disassociate() associationId=" + associationId);

            final AssociationInfo association = getAssociationWithCallerChecks(associationId);
            if (association == null) {
                throw new IllegalArgumentException("Association with ID " + associationId + " "
                        + "does not exist "
                        + "or belongs to a different package "
                        + "or belongs to a different user");
            }

            disassociateInternal(associationId);
        }

        @Override
        public PendingIntent requestNotificationAccess(ComponentName component, int userId)
                throws RemoteException {
            String callingPackage = component.getPackageName();
            checkCanCallNotificationApi(callingPackage);
            // TODO: check userId.
            String packageTitle = BidiFormatter.getInstance().unicodeWrap(
                    getPackageInfo(getContext(), userId, callingPackage)
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

            return any(mAssociationStore.getAssociationsForPackage(userId, packageName),
                    a -> a.isLinkedTo(macAddress));
        }

        @Override
        public void registerDevicePresenceListenerService(String deviceAddress,
                String callingPackage, int userId) throws RemoteException {
            // TODO: take the userId into account.
            registerDevicePresenceListenerActive(callingPackage, deviceAddress, true);
        }

        @Override
        public void unregisterDevicePresenceListenerService(String deviceAddress,
                String callingPackage, int userId) throws RemoteException {
            // TODO: take the userId into account.
            registerDevicePresenceListenerActive(callingPackage, deviceAddress, false);
        }

        @Override
        public void dispatchMessage(int messageId, int associationId, byte[] message)
                throws RemoteException {
            // TODO(b/199427116): implement.
        }

        @Override
        public void notifyDeviceAppeared(int associationId) {
            if (DEBUG) Log.i(TAG, "notifyDevice_Appeared() id=" + associationId);

            AssociationInfo association = getAssociationWithCallerChecks(associationId);
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
            // AssociationInfo class is immutable: create a new AssociationInfo object with updated
            // timestamp.
            association = AssociationInfo.builder(association)
                    .setLastTimeConnected(System.currentTimeMillis())
                    .build();
            mAssociationStore.updateAssociation(association);

            mDevicePresenceMonitor.onSelfManagedDeviceConnected(associationId);
        }

        @Override
        public void notifyDeviceDisappeared(int associationId) {
            if (DEBUG) Log.i(TAG, "notifyDevice_Disappeared() id=" + associationId);

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

            mDevicePresenceMonitor.onSelfManagedDeviceDisconnected(associationId);
        }

        private void registerDevicePresenceListenerActive(String packageName, String deviceAddress,
                boolean active) throws RemoteException {
            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE,
                    "[un]registerDevicePresenceListenerService");
            final int userId = getCallingUserId();
            enforceCallerIsSystemOr(userId, packageName);

            AssociationInfo association = mAssociationStore.getAssociationsForPackageWithAddress(
                            userId, packageName, deviceAddress);

            if (association == null) {
                throw new RemoteException(new DeviceNotAssociatedException("App " + packageName
                        + " is not associated with device " + deviceAddress
                        + " for user " + userId));
            }

            // AssociationInfo class is immutable: create a new AssociationInfo object with updated
            // flag.
            association = AssociationInfo.builder(association)
                    .setNotifyOnDeviceNearby(active)
                    .build();
            mAssociationStore.updateAssociation(association);

            // TODO(b/218615198): correctly handle the case when the device is currently present.
        }

        @Override
        public void createAssociation(String packageName, String macAddress, int userId,
                byte[] certificate) {
            if (!getContext().getPackageManager().hasSigningCertificate(
                    packageName, certificate, CERT_INPUT_SHA256)) {
                Slog.e(TAG, "Given certificate doesn't match the package certificate.");
                return;
            }

            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.ASSOCIATE_COMPANION_DEVICES, "createAssociation");

            legacyCreateAssociation(userId, macAddress, packageName, null);
        }

        private void checkCanCallNotificationApi(String callingPackage) {
            final int userId = getCallingUserId();
            enforceCallerIsSystemOr(userId, callingPackage);

            if (getCallingUid() == SYSTEM_UID) return;

            enforceUsesCompanionDeviceFeature(getContext(), userId, callingPackage);
            checkState(!ArrayUtils.isEmpty(
                    mAssociationStore.getAssociationsForPackage(userId, callingPackage)),
                    "App must have an association before calling this API");
        }

        @Override
        public boolean canPairWithoutPrompt(String packageName, String macAddress, int userId) {
            final AssociationInfo association =
                    mAssociationStore.getAssociationsForPackageWithAddress(
                            userId, packageName, macAddress);
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
            new CompanionDeviceShellCommand(
                    CompanionDeviceManagerService.this, mAssociationStore)
                    .exec(this, in, out, err, args, callback, resultReceiver);
        }

        @Override
        public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter out,
                @Nullable String[] args) {
            if (!DumpUtils.checkDumpAndUsageStatsPermission(getContext(), TAG, out)) {
                return;
            }

            // TODO(b/218615185): mAssociationStore.dump() instead
            out.append("Companion Device Associations:").append('\n');
            for (AssociationInfo a : mAssociationStore.getAssociations()) {
                out.append("  ").append(a.toString()).append('\n');
            }

            // TODO(b/218615185): mDevicePresenceMonitor.dump()
            // TODO(b/218615185): mCompanionAppController.dump()
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
                macAddress, displayName, deviceProfile, selfManaged, false, timestamp,
                Long.MAX_VALUE);
        Slog.i(TAG, "New CDM association created=" + association);
        mAssociationStore.addAssociation(association);

        // If the "Device Profile" is specified, make the companion application a holder of the
        // corresponding role.
        if (deviceProfile != null) {
            addRoleHolderForAssociation(getContext(), association);
        }

        updateSpecialAccessPermissionForAssociatedPackage(association);

        return association;
    }

    @NonNull
    private Map<String, Set<Integer>> getPreviouslyUsedIdsForUser(@UserIdInt int userId) {
        synchronized (mPreviouslyUsedIds) {
            return getPreviouslyUsedIdsForUserLocked(userId);
        }
    }

    @GuardedBy("mPreviouslyUsedIds")
    @NonNull
    private Map<String, Set<Integer>> getPreviouslyUsedIdsForUserLocked(@UserIdInt int userId) {
        final Map<String, Set<Integer>> usedIdsForUser = mPreviouslyUsedIds.get(userId);
        if (usedIdsForUser == null) {
            return Collections.emptyMap();
        }
        return deepUnmodifiableCopy(usedIdsForUser);
    }

    @GuardedBy("mPreviouslyUsedIds")
    @NonNull
    private Set<Integer> getPreviouslyUsedIdsForPackageLocked(
            @UserIdInt int userId, @NonNull String packageName) {
        // "Deeply unmodifiable" map: the map itself and the Set<Integer> values it contains are all
        // unmodifiable.
        final Map<String, Set<Integer>> usedIdsForUser = getPreviouslyUsedIdsForUserLocked(userId);
        final Set<Integer> usedIdsForPackage = usedIdsForUser.get(packageName);

        if (usedIdsForPackage == null) {
            return Collections.emptySet();
        }

        //The set is already unmodifiable.
        return usedIdsForPackage;
    }

    private int getNewAssociationIdForPackage(@UserIdInt int userId, @NonNull String packageName) {
        synchronized (mPreviouslyUsedIds) {
            // First: collect all IDs currently in use for this user's Associations.
            final SparseBooleanArray usedIds = new SparseBooleanArray();

            // We should really only be checking associations for the given user (i.e.:
            // mAssociationStore.getAssociationsForUser(userId)), BUT in the past we've got in a
            // state where association IDs were not assigned correctly in regard to
            // user-to-association-ids-range (e.g. associations with IDs from 1 to 100,000 should
            // always belong to u0), so let's check all the associations.
            for (AssociationInfo it : mAssociationStore.getAssociations()) {
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

    // TODO: also revoke notification access
    void disassociateInternal(int associationId) {
        final AssociationInfo association = mAssociationStore.getAssociationById(associationId);
        final int userId = association.getUserId();
        final String packageName = association.getPackageName();
        final String deviceProfile = association.getDeviceProfile();

        final boolean wasPresent = mDevicePresenceMonitor.isDevicePresent(associationId);

        // Removing the association.
        mAssociationStore.removeAssociation(associationId);

        final List<AssociationInfo> otherAssociations =
                mAssociationStore.getAssociationsForPackage(userId, packageName);

        // Check if the package is associated with other devices with the same profile.
        // If not: take away the role.
        if (deviceProfile != null) {
            final boolean shouldKeepTheRole = any(otherAssociations,
                    it -> deviceProfile.equals(it.getDeviceProfile()));
            if (!shouldKeepTheRole) {
                Binder.withCleanCallingIdentity(() ->
                        removeRoleHolderForAssociation(getContext(), association));
            }
        }

        if (!wasPresent || !association.isNotifyOnDeviceNearby()) return;
        // The device was connected and the app was notified: check if we need to unbind the app
        // now.
        final boolean shouldStayBound = any(otherAssociations,
                it -> it.isNotifyOnDeviceNearby()
                        && mDevicePresenceMonitor.isDevicePresent(it.getId()));
        if (shouldStayBound) return;
        mCompanionAppController.unbindCompanionApplication(userId, packageName);
    }

    private void updateSpecialAccessPermissionForAssociatedPackage(AssociationInfo association) {
        final PackageInfo packageInfo =
                getPackageInfo(getContext(), association.getUserId(), association.getPackageName());

        Binder.withCleanCallingIdentity(() -> updateSpecialAccessPermissionAsSystem(packageInfo));
    }

    private void updateSpecialAccessPermissionAsSystem(PackageInfo packageInfo) {
        if (containsEither(packageInfo.requestedPermissions,
                android.Manifest.permission.RUN_IN_BACKGROUND,
                android.Manifest.permission.REQUEST_COMPANION_RUN_IN_BACKGROUND)) {
            mPowerWhitelistManager.addToWhitelist(packageInfo.packageName);
        } else {
            try {
                mPowerWhitelistManager.removeFromWhitelist(packageInfo.packageName);
            } catch (UnsupportedOperationException e) {
                Slog.w(TAG, packageInfo.packageName + " can't be removed from power save"
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
                        mAssociationStore.getAssociationsForUser(userId);
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
            onAssociationChangedInternal(changeType, association);
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
    };

    private final CompanionApplicationController.Callback mApplicationControllerCallback =
            new CompanionApplicationController.Callback() {
        @Override
        public boolean onCompanionApplicationBindingDied(int userId, @NonNull String packageName) {
            return onCompanionApplicationBindingDiedInternal(userId, packageName);
        }

        @Override
        public void onRebindCompanionApplicationTimeout(int userId, @NonNull String packageName) {
            onRebindCompanionApplicationTimeoutInternal(userId, packageName);
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
    };

    static int getFirstAssociationIdForUser(@UserIdInt int userId) {
        // We want the IDs to start from 1, not 0.
        return userId * ASSOCIATIONS_IDS_PER_USER_RANGE + 1;
    }

    static int getLastAssociationIdForUser(@UserIdInt int userId) {
        return (userId + 1) * ASSOCIATIONS_IDS_PER_USER_RANGE;
    }

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

    private class LocalService extends CompanionDeviceManagerServiceInternal {
        @Override
        public void associationCleanUp(String profile) {
            CompanionDeviceManagerService.this.associationCleanUp(profile);
        }
    }

    /**
     * This method must only be called from {@link CompanionDeviceShellCommand} for testing
     * purposes only!
     */
    void persistState() {
        mUserPersistenceHandler.clearMessages();
        for (UserInfo user : mUserManager.getAliveUsers()) {
            persistStateForUser(user.id);
        }
    }

    /**
     * This class is dedicated to handling requests to persist user state.
     */
    @SuppressLint("HandlerLeak")
    private class PersistUserStateHandler extends Handler {
        PersistUserStateHandler() {
            super(BackgroundThread.get().getLooper());
        }

        /**
         * Persists user state unless there is already an outstanding request for the given user.
         */
        synchronized void postPersistUserState(@UserIdInt int userId) {
            if (!hasMessages(userId)) {
                sendMessage(obtainMessage(userId));
            }
        }

        /**
         * Clears *ALL* outstanding persist requests for *ALL* users.
         */
        synchronized void clearMessages() {
            removeCallbacksAndMessages(null);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            final int userId = msg.what;
            persistStateForUser(userId);
        }
    }
}
