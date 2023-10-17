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
import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
import static android.content.pm.PackageManager.CERT_INPUT_SHA256;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Process.SYSTEM_UID;
import static android.os.UserHandle.getCallingUserId;

import static com.android.internal.util.CollectionUtils.any;
import static com.android.internal.util.Preconditions.checkState;
import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;
import static com.android.server.companion.AssociationStore.CHANGE_TYPE_UPDATED_ADDRESS_UNCHANGED;
import static com.android.server.companion.MetricUtils.logCreateAssociation;
import static com.android.server.companion.MetricUtils.logRemoveAssociation;
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
import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
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
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArraySet;
import android.util.ExceptionUtils;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IAppOpsService;
import com.android.internal.content.PackageMonitor;
import com.android.internal.infra.PerUser;
import com.android.internal.notification.NotificationAccessConfirmationActivityContract;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.companion.presence.CompanionDevicePresenceMonitor;
import com.android.server.pm.UserManagerInternal;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
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
    private static final String SYS_PROP_DEBUG_REMOVAL_TIME_WINDOW =
            "debug.cdm.cdmservice.removal_time_window";

    private static final long ASSOCIATION_REMOVAL_TIME_WINDOW_DEFAULT = DAYS.toMillis(90);
    private static final int MAX_CN_LENGTH = 500;

    private final ActivityManager mActivityManager;
    private final OnPackageVisibilityChangeListener mOnPackageVisibilityChangeListener;

    private PersistentDataStore mPersistentStore;
    private final PersistUserStateHandler mUserPersistenceHandler;

    private final AssociationStoreImpl mAssociationStore;
    private AssociationRequestsProcessor mAssociationRequestsProcessor;
    private CompanionDevicePresenceMonitor mDevicePresenceMonitor;
    private CompanionApplicationController mCompanionAppController;

    private final ActivityTaskManagerInternal mAtmInternal;
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

    /**
     * A structure that consists of a set of revoked associations that pending for role holder
     * removal per each user.
     *
     * @see #maybeRemoveRoleHolderForAssociation(AssociationInfo)
     * @see #addToPendingRoleHolderRemoval(AssociationInfo)
     * @see #removeFromPendingRoleHolderRemoval(AssociationInfo)
     * @see #getPendingRoleHolderRemovalAssociationsForUser(int)
     */
    @GuardedBy("mRevokedAssociationsPendingRoleHolderRemoval")
    private final PerUserAssociationSet mRevokedAssociationsPendingRoleHolderRemoval =
            new PerUserAssociationSet();
    /**
     * Contains uid-s of packages pending to be removed from the role holder list (after
     * revocation of an association), which will happen one the package is no longer visible to the
     * user.
     * For quicker uid -> (userId, packageName) look-up this is not a {@code Set<Integer>} but
     * a {@code Map<Integer, String>} which maps uid-s to packageName-s (userId-s can be derived
     * from uid-s using {@link UserHandle#getUserId(int)}).
     *
     * @see #maybeRemoveRoleHolderForAssociation(AssociationInfo)
     * @see #addToPendingRoleHolderRemoval(AssociationInfo)
     * @see #removeFromPendingRoleHolderRemoval(AssociationInfo)
     */
    @GuardedBy("mRevokedAssociationsPendingRoleHolderRemoval")
    private final Map<Integer, String> mUidsPendingRoleHolderRemoval = new HashMap<>();

    private final RemoteCallbackList<IOnAssociationsChangedListener> mListeners =
            new RemoteCallbackList<>();

    public CompanionDeviceManagerService(Context context) {
        super(context);

        mActivityManager = context.getSystemService(ActivityManager.class);
        mPowerWhitelistManager = context.getSystemService(PowerWhitelistManager.class);
        mAppOpsManager = IAppOpsService.Stub.asInterface(
                ServiceManager.getService(Context.APP_OPS_SERVICE));
        mAtmInternal = LocalServices.getService(ActivityTaskManagerInternal.class);
        mAmInternal = LocalServices.getService(ActivityManagerInternal.class);
        mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
        mUserManager = context.getSystemService(UserManager.class);

        mUserPersistenceHandler = new PersistUserStateHandler();
        mAssociationStore = new AssociationStoreImpl();

        mOnPackageVisibilityChangeListener =
                new OnPackageVisibilityChangeListener(mActivityManager);
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

        final Set<AssociationInfo> activeAssociations =
                new ArraySet<>(/* capacity */ allAssociations.size());
        // A set contains the userIds that need to persist state after remove the app
        // from the list of role holders.
        final Set<Integer> usersToPersistStateFor = new ArraySet<>();

        for (AssociationInfo association : allAssociations) {
            if (!association.isRevoked()) {
                activeAssociations.add(association);
            } else if (maybeRemoveRoleHolderForAssociation(association)) {
                // Nothing more to do here, but we'll need to persist all the associations to the
                // disk afterwards.
                usersToPersistStateFor.add(association.getUserId());
            } else {
                addToPendingRoleHolderRemoval(association);
            }
        }

        mAssociationStore.setAssociations(activeAssociations);

        // IMPORTANT: only do this AFTER mAssociationStore.setAssociations(), because
        // persistStateForUser() queries AssociationStore.
        // (If persistStateForUser() is invoked before mAssociationStore.setAssociations() it
        // would effectively just clear-out all the persisted associations).
        for (int userId : usersToPersistStateFor) {
            persistStateForUser(userId);
        }
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
        for (AssociationInfo ai :
                mAssociationStore.getAssociationsForPackage(userId, packageName)) {
            final int associationId = ai.getId();
            if (ai.isSelfManaged()
                    && mDevicePresenceMonitor.isDevicePresent(associationId)) {
                mDevicePresenceMonitor.onSelfManagedDeviceReporterBinderDied(associationId);
            }
        }
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
        // We want to store both active associations and the revoked (removed) association that we
        // are keeping around for the final clean-up (delayed role holder removal).
        final List<AssociationInfo> allAssociations;
        // Start with the active associations - these we can get from the AssociationStore.
        allAssociations = new ArrayList<>(
                mAssociationStore.getAssociationsForUser(userId));
        // ... and add the revoked (removed) association, that are yet to be permanently removed.
        allAssociations.addAll(getPendingRoleHolderRemovalAssociationsForUser(userId));

        final Map<String, Set<Integer>> usedIdsForUser = getPreviouslyUsedIdsForUser(userId);

        mPersistentStore.persistStateForUser(userId, allAssociations, usedIdsForUser);
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
            disassociateInternal(id);
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
            checkCanCallNotificationApi(callingPackage, userId);
            if (component.flattenToString().length() > MAX_CN_LENGTH) {
                throw new IllegalArgumentException("Component name is too long.");
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                return PendingIntent.getActivityAsUser(getContext(),
                        0 /* request code */,
                        NotificationAccessConfirmationActivityContract.launcherIntent(
                                getContext(), userId, component),
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
            checkCanCallNotificationApi(component.getPackageName(), getCallingUserId());
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
            if (DEBUG) {
                Log.i(TAG, "registerDevicePresenceListenerActive()"
                        + " active=" + active
                        + " deviceAddress=" + deviceAddress);
            }

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

            // If already at specified state, then no-op.
            if (active == association.isNotifyOnDeviceNearby()) {
                if (DEBUG) Log.d(TAG, "Device presence listener is already at desired state.");
                return;
            }

            // AssociationInfo class is immutable: create a new AssociationInfo object with updated
            // flag.
            association = AssociationInfo.builder(association)
                    .setNotifyOnDeviceNearby(active)
                    .build();
            // Do not need to call {@link BleCompanionDeviceScanner#restartScan()} since it will
            // trigger {@link BleCompanionDeviceScanner#restartScan(int, AssociationInfo)} when
            // an application sets/unsets the mNotifyOnDeviceNearby flag.
            mAssociationStore.updateAssociation(association);

            // If device is already present, then trigger callback.
            if (active && mDevicePresenceMonitor.isDevicePresent(association.getId())) {
                if (DEBUG) Log.d(TAG, "Device is already present. Triggering callback.");
                onDeviceAppearedInternal(association.getId());
            }

            // If last listener is unregistered, then unbind application.
            if (!active && !shouldBindPackage(userId, packageName)) {
                if (DEBUG) Log.d(TAG, "Last listener unregistered. Unbinding application.");
                mCompanionAppController.unbindCompanionApplication(userId, packageName);
            }
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

        private void checkCanCallNotificationApi(String callingPackage, int userId) {
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

            final CompanionDeviceShellCommand cmd = new CompanionDeviceShellCommand(
                    CompanionDeviceManagerService.this,
                    mAssociationStore,
                    mDevicePresenceMonitor);
            cmd.exec(this, in, out, err, args, callback, resultReceiver);
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
                macAddress, displayName, deviceProfile, selfManaged,
                /* notifyOnDeviceNearby */ false, /* revoked */ false, timestamp, Long.MAX_VALUE);
        Slog.i(TAG, "New CDM association created=" + association);
        mAssociationStore.addAssociation(association);

        // If the "Device Profile" is specified, make the companion application a holder of the
        // corresponding role.
        if (deviceProfile != null) {
            addRoleHolderForAssociation(getContext(), association);
        }

        updateSpecialAccessPermissionForAssociatedPackage(association);
        logCreateAssociation(deviceProfile);

        // Don't need to update the mRevokedAssociationsPendingRoleHolderRemoval since
        // maybeRemoveRoleHolderForAssociation in PackageInactivityListener will handle the case
        // that there are other devices with the same profile, so the role holder won't be removed.

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

        if (!maybeRemoveRoleHolderForAssociation(association)) {
            // Need to remove the app from list of the role holders, but will have to do it later
            // (the app is in foreground at the moment).
            addToPendingRoleHolderRemoval(association);
        }

        // Need to check if device still present now because CompanionDevicePresenceMonitor will
        // remove current connected device after mAssociationStore.removeAssociation
        final boolean wasPresent = mDevicePresenceMonitor.isDevicePresent(associationId);

        // Removing the association.
        mAssociationStore.removeAssociation(associationId);
        // Do not need to persistUserState since CompanionDeviceManagerService will get callback
        // from #onAssociationChanged, and it will handle the persistUserState which including
        // active and revoked association.
        logRemoveAssociation(deviceProfile);

        if (!wasPresent || !association.isNotifyOnDeviceNearby()) return;
        // The device was connected and the app was notified: check if we need to unbind the app
        // now.
        final boolean shouldStayBound = any(
                mAssociationStore.getAssociationsForPackage(userId, packageName),
                it -> it.isNotifyOnDeviceNearby()
                        && mDevicePresenceMonitor.isDevicePresent(it.getId()));
        if (shouldStayBound) return;
        mCompanionAppController.unbindCompanionApplication(userId, packageName);
    }

    /**
     * First, checks if the companion application should be removed from the list role holders when
     * upon association's removal, i.e.: association's profile (matches the role) is not null,
     * the application does not have other associations with the same profile, etc.
     *
     * <p>
     * Then, if establishes that the application indeed has to be removed from the list of the role
     * holders, checks if it could be done right now -
     * {@link android.app.role.RoleManager#removeRoleHolderAsUser(String, String, int, UserHandle, java.util.concurrent.Executor, java.util.function.Consumer) RoleManager#removeRoleHolderAsUser()}
     * will kill the application's process, which leads poor user experience if the application was
     * in foreground when this happened, to avoid this CDMS delays invoking
     * {@code RoleManager.removeRoleHolderAsUser()} until the app is no longer in foreground.
     *
     * @return {@code true} if the application does NOT need be removed from the list of the role
     *         holders OR if the application was successfully removed from the list of role holders.
     *         I.e.: from the role-management perspective the association is done with.
     *         {@code false} if the application needs to be removed from the list of role the role
     *         holders, BUT it CDMS would prefer to do it later.
     *         I.e.: application is in the foreground at the moment, but invoking
     *         {@code RoleManager.removeRoleHolderAsUser()} will kill the application's process,
     *         which would lead to the poor UX, hence need to try later.
     */

    private boolean maybeRemoveRoleHolderForAssociation(@NonNull AssociationInfo association) {
        if (DEBUG) Log.d(TAG, "maybeRemoveRoleHolderForAssociation() association=" + association);

        final String deviceProfile = association.getDeviceProfile();
        if (deviceProfile == null) {
            // No role was granted to for this association, there is nothing else we need to here.
            return true;
        }

        // Check if the applications is associated with another devices with the profile. If so,
        // it should remain the role holder.
        final int id = association.getId();
        final int userId = association.getUserId();
        final String packageName = association.getPackageName();
        final boolean roleStillInUse = any(
                mAssociationStore.getAssociationsForPackage(userId, packageName),
                it -> deviceProfile.equals(it.getDeviceProfile()) && id != it.getId());
        if (roleStillInUse) {
            // Application should remain a role holder, there is nothing else we need to here.
            return true;
        }

        final int packageProcessImportance = getPackageProcessImportance(userId, packageName);
        if (packageProcessImportance <= IMPORTANCE_VISIBLE) {
            // Need to remove the app from the list of role holders, but the process is visible to
            // the user at the moment, so we'll need to it later: log and return false.
            Slog.i(TAG, "Cannot remove role holder for the removed association id=" + id
                    + " now - process is visible.");
            return false;
        }

        removeRoleHolderForAssociation(getContext(), association);
        return true;
    }

    private int getPackageProcessImportance(@UserIdInt int userId, @NonNull String packageName) {
        return Binder.withCleanCallingIdentity(() -> {
            final int uid =
                    mPackageManagerInternal.getPackageUid(packageName, /* flags */0, userId);
            return mActivityManager.getUidImportance(uid);
        });
    }

    /**
     * Set revoked flag for active association and add the revoked association and the uid into
     * the caches.
     *
     * @see #mRevokedAssociationsPendingRoleHolderRemoval
     * @see #mUidsPendingRoleHolderRemoval
     * @see OnPackageVisibilityChangeListener
     */
    private void addToPendingRoleHolderRemoval(@NonNull AssociationInfo association) {
        // First: set revoked flag.
        association = AssociationInfo.builder(association)
                .setRevoked(true)
                .build();

        final String packageName = association.getPackageName();
        final int userId = association.getUserId();
        final int uid = mPackageManagerInternal.getPackageUid(packageName, /* flags */0, userId);

        // Second: add to the set.
        synchronized (mRevokedAssociationsPendingRoleHolderRemoval) {
            mRevokedAssociationsPendingRoleHolderRemoval.forUser(association.getUserId())
                    .add(association);
            if (!mUidsPendingRoleHolderRemoval.containsKey(uid)) {
                mUidsPendingRoleHolderRemoval.put(uid, packageName);

                if (mUidsPendingRoleHolderRemoval.size() == 1) {
                    // Just added first uid: start the listener
                    mOnPackageVisibilityChangeListener.startListening();
                }
            }
        }
    }

    /**
     * Remove the revoked association form the cache and also remove the uid form the map if
     * there are other associations with the same package still pending for role holder removal.
     *
     * @see #mRevokedAssociationsPendingRoleHolderRemoval
     * @see #mUidsPendingRoleHolderRemoval
     * @see OnPackageVisibilityChangeListener
     */
    private void removeFromPendingRoleHolderRemoval(@NonNull AssociationInfo association) {
        final String packageName = association.getPackageName();
        final int userId = association.getUserId();
        final int uid = mPackageManagerInternal.getPackageUid(packageName, /* flags */0, userId);

        synchronized (mRevokedAssociationsPendingRoleHolderRemoval) {
            mRevokedAssociationsPendingRoleHolderRemoval.forUser(userId)
                    .remove(association);

            final boolean shouldKeepUidForRemoval = any(
                    getPendingRoleHolderRemovalAssociationsForUser(userId),
                    ai -> packageName.equals(ai.getPackageName()));
            // Do not remove the uid form the map since other associations with
            // the same packageName still pending for role holder removal.
            if (!shouldKeepUidForRemoval) {
                mUidsPendingRoleHolderRemoval.remove(uid);
            }

            if (mUidsPendingRoleHolderRemoval.isEmpty()) {
                // The set is empty now - can "turn off" the listener.
                mOnPackageVisibilityChangeListener.stopListening();
            }
        }
    }

    /**
     * @return a copy of the revoked associations set (safeguarding against
     *         {@code ConcurrentModificationException}-s).
     */
    private @NonNull Set<AssociationInfo> getPendingRoleHolderRemovalAssociationsForUser(
            @UserIdInt int userId) {
        synchronized (mRevokedAssociationsPendingRoleHolderRemoval) {
            // Return a copy.
            return new ArraySet<>(mRevokedAssociationsPendingRoleHolderRemoval.forUser(userId));
        }
    }

    private String getPackageNameByUid(int uid) {
        synchronized (mRevokedAssociationsPendingRoleHolderRemoval) {
            return mUidsPendingRoleHolderRemoval.get(uid);
        }
    }

    private void updateSpecialAccessPermissionForAssociatedPackage(AssociationInfo association) {
        final PackageInfo packageInfo =
                getPackageInfo(getContext(), association.getUserId(), association.getPackageName());

        Binder.withCleanCallingIdentity(() -> updateSpecialAccessPermissionAsSystem(packageInfo));
    }

    private void updateSpecialAccessPermissionAsSystem(PackageInfo packageInfo) {
        if (packageInfo == null) {
            return;
        }
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

    private class LocalService implements CompanionDeviceManagerServiceInternal {
        @Override
        public void removeInactiveSelfManagedAssociations() {
            CompanionDeviceManagerService.this.removeInactiveSelfManagedAssociations();
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

    /**
     * An OnUidImportanceListener class which watches the importance of the packages.
     * In this class, we ONLY interested in the importance of the running process is greater than
     * {@link RunningAppProcessInfo.IMPORTANCE_VISIBLE} for the uids have been added into the
     * {@link mUidsPendingRoleHolderRemoval}. Lastly remove the role holder for the revoked
     * associations for the same packages.
     *
     * @see #maybeRemoveRoleHolderForAssociation(AssociationInfo)
     * @see #removeFromPendingRoleHolderRemoval(AssociationInfo)
     * @see #getPendingRoleHolderRemovalAssociationsForUser(int)
     */
    private class OnPackageVisibilityChangeListener implements
            ActivityManager.OnUidImportanceListener {
        final @NonNull ActivityManager mAm;

        OnPackageVisibilityChangeListener(@NonNull ActivityManager am) {
            this.mAm = am;
        }

        void startListening() {
            Binder.withCleanCallingIdentity(
                    () -> mAm.addOnUidImportanceListener(
                            /* listener */ OnPackageVisibilityChangeListener.this,
                            RunningAppProcessInfo.IMPORTANCE_VISIBLE));
        }

        void stopListening() {
            Binder.withCleanCallingIdentity(
                    () -> mAm.removeOnUidImportanceListener(
                            /* listener */ OnPackageVisibilityChangeListener.this));
        }

        @Override
        public void onUidImportance(int uid, int importance) {
            if (importance <= RunningAppProcessInfo.IMPORTANCE_VISIBLE) {
                // The lower the importance value the more "important" the process is.
                // We are only interested when the process ceases to be visible.
                return;
            }

            final String packageName = getPackageNameByUid(uid);
            if (packageName == null) {
                // Not interested in this uid.
                return;
            }

            final int userId = UserHandle.getUserId(uid);

            boolean needToPersistStateForUser = false;

            for (AssociationInfo association :
                    getPendingRoleHolderRemovalAssociationsForUser(userId)) {
                if (!packageName.equals(association.getPackageName())) continue;

                if (!maybeRemoveRoleHolderForAssociation(association)) {
                    // Did not remove the role holder, will have to try again later.
                    continue;
                }

                removeFromPendingRoleHolderRemoval(association);
                needToPersistStateForUser = true;
            }

            if (needToPersistStateForUser) {
                mUserPersistenceHandler.postPersistUserState(userId);
            }
        }
    }

    private static class PerUserAssociationSet extends PerUser<Set<AssociationInfo>> {
        @Override
        protected @NonNull Set<AssociationInfo> create(int userId) {
            return new ArraySet<>();
        }
    }
}
