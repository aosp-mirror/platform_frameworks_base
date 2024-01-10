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

package com.android.server.companion;

import static android.app.PendingIntent.FLAG_CANCEL_CURRENT;
import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.app.PendingIntent.FLAG_ONE_SHOT;
import static android.companion.CompanionDeviceManager.REASON_INTERNAL_ERROR;
import static android.companion.CompanionDeviceManager.RESULT_INTERNAL_ERROR;
import static android.content.ComponentName.createRelative;
import static android.content.pm.PackageManager.FEATURE_WATCH;

import static com.android.server.companion.CompanionDeviceManagerService.DEBUG;
import static com.android.server.companion.MetricUtils.logCreateAssociation;
import static com.android.server.companion.PackageUtils.enforceUsesCompanionDeviceFeature;
import static com.android.server.companion.PermissionsUtils.enforcePermissionsForAssociation;
import static com.android.server.companion.RolesUtils.addRoleHolderForAssociation;
import static com.android.server.companion.RolesUtils.isRoleHolder;
import static com.android.server.companion.Utils.prepareForIpc;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.companion.AssociatedDevice;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.IAssociationRequestCallback;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManagerInternal;
import android.net.MacAddress;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.R;

import java.util.List;

/**
 * Class responsible for handling incoming {@link AssociationRequest}s.
 * The main responsibilities of an {@link AssociationRequestsProcessor} are:
 * <ul>
 * <li> Requests validation and checking if the package that would own the association holds all
 * necessary permissions.
 * <li> Communication with the requester via a provided
 * {@link android.companion.CompanionDeviceManager.Callback}.
 * <li> Constructing an {@link Intent} for collecting user's approval (if needed), and handling the
 * approval.
 * <li> Calling to {@link CompanionDeviceManagerService} to create an association when/if the
 * request was found valid and was approved by user.
 * </ul>
 *
 * The class supports two variants of the "Association Flow": the full variant, and the shortened
 * (a.k.a. No-UI) variant.
 * Both flows start similarly: in
 * {@link #processNewAssociationRequest(AssociationRequest, String, int, IAssociationRequestCallback)}
 * invoked from
 * {@link CompanionDeviceManagerService.CompanionDeviceManagerImpl#associate(AssociationRequest, IAssociationRequestCallback, String, int)}
 * method call.
 * Then an {@link AssociationRequestsProcessor} makes a decision whether user's confirmation is
 * required.
 *
 * If the user's approval is NOT required: an {@link AssociationRequestsProcessor} invokes
 * {@link #createAssociationAndNotifyApplication(AssociationRequest, String, int, MacAddress, IAssociationRequestCallback, ResultReceiver)}
 * which after calling to  {@link CompanionDeviceManagerService} to create an association, notifies
 * the requester via
 * {@link android.companion.CompanionDeviceManager.Callback#onAssociationCreated(AssociationInfo)}.
 *
 * If the user's approval is required: an {@link AssociationRequestsProcessor} constructs a
 * {@link PendingIntent} for the approval UI and sends it back to the requester via
 * {@link android.companion.CompanionDeviceManager.Callback#onAssociationPending(IntentSender)}.
 * When/if user approves the request,  {@link AssociationRequestsProcessor} receives a "callback"
 * from the Approval UI in via {@link #mOnRequestConfirmationReceiver} and invokes
 * {@link #processAssociationRequestApproval(AssociationRequest, IAssociationRequestCallback, ResultReceiver, MacAddress)}
 * which one more time checks that the packages holds all necessary permissions before proceeding to
 * {@link #createAssociationAndNotifyApplication(AssociationRequest, String, int, MacAddress, IAssociationRequestCallback, ResultReceiver)}.
 *
 * @see #processNewAssociationRequest(AssociationRequest, String, int, IAssociationRequestCallback)
 * @see #processAssociationRequestApproval(AssociationRequest, IAssociationRequestCallback,
 * ResultReceiver, MacAddress)
 */
@SuppressLint("LongLogTag")
class AssociationRequestsProcessor {
    private static final String TAG = "CDM_AssociationRequestsProcessor";

    // AssociationRequestsProcessor <-> UI
    private static final String EXTRA_APPLICATION_CALLBACK = "application_callback";
    private static final String EXTRA_ASSOCIATION_REQUEST = "association_request";
    private static final String EXTRA_RESULT_RECEIVER = "result_receiver";
    private static final String EXTRA_FORCE_CANCEL_CONFIRMATION = "cancel_confirmation";

    // AssociationRequestsProcessor -> UI
    private static final int RESULT_CODE_ASSOCIATION_CREATED = 0;
    private static final String EXTRA_ASSOCIATION = "association";

    // UI -> AssociationRequestsProcessor
    private static final int RESULT_CODE_ASSOCIATION_APPROVED = 0;
    private static final String EXTRA_MAC_ADDRESS = "mac_address";

    private static final int ASSOCIATE_WITHOUT_PROMPT_MAX_PER_TIME_WINDOW = 5;
    private static final long ASSOCIATE_WITHOUT_PROMPT_WINDOW_MS = 60 * 60 * 1000; // 60 min;

    private final @NonNull Context mContext;
    private final @NonNull CompanionDeviceManagerService mService;
    private final @NonNull PackageManagerInternal mPackageManager;
    private final @NonNull AssociationStoreImpl mAssociationStore;
    @NonNull
    private final ComponentName mCompanionDeviceActivity;

    AssociationRequestsProcessor(@NonNull CompanionDeviceManagerService service,
            @NonNull AssociationStoreImpl associationStore) {
        mContext = service.getContext();
        mService = service;
        mPackageManager = service.mPackageManagerInternal;
        mAssociationStore = associationStore;
        mCompanionDeviceActivity = createRelative(
                mContext.getString(R.string.config_companionDeviceManagerPackage),
                ".CompanionDeviceActivity");
    }

    /**
     * Handle incoming {@link AssociationRequest}s, sent via
     * {@link android.companion.ICompanionDeviceManager#associate(AssociationRequest, IAssociationRequestCallback, String, int)}
     */
    void processNewAssociationRequest(@NonNull AssociationRequest request,
            @NonNull String packageName, @UserIdInt int userId,
            @NonNull IAssociationRequestCallback callback) {
        requireNonNull(request, "Request MUST NOT be null");
        if (request.isSelfManaged()) {
            requireNonNull(request.getDisplayName(), "AssociationRequest.displayName "
                    + "MUST NOT be null.");
        }
        requireNonNull(packageName, "Package name MUST NOT be null");
        requireNonNull(callback, "Callback MUST NOT be null");

        final int packageUid = mPackageManager.getPackageUid(packageName, 0, userId);
        if (DEBUG) {
            Slog.d(TAG, "processNewAssociationRequest() "
                    + "request=" + request + ", "
                    + "package=u" + userId + "/" + packageName + " (uid=" + packageUid + ")");
        }

        // 1. Enforce permissions and other requirements.
        enforcePermissionsForAssociation(mContext, request, packageUid);
        enforceUsesCompanionDeviceFeature(mContext, userId, packageName);

        // 2a. Check if association can be created without launching UI (i.e. CDM needs NEITHER
        // to perform discovery NOR to collect user consent).
        if (request.isSelfManaged() && !request.isForceConfirmation()
                && !willAddRoleHolder(request, packageName, userId)) {
            // 2a.1. Create association right away.
            createAssociationAndNotifyApplication(request, packageName, userId,
                    /* macAddress */ null, callback, /* resultReceiver */ null);
            return;
        }

        // 2a.2. Report an error if a 3p app tries to create a non-self-managed association and
        //       launch UI on watch.
        if (mContext.getPackageManager().hasSystemFeature(FEATURE_WATCH)) {
            String errorMessage = "3p apps are not allowed to create associations on watch.";
            Slog.e(TAG, errorMessage);
            try {
                callback.onFailure(errorMessage);
            } catch (RemoteException e) {
                // ignored
            }
            return;
        }

        // 2b. Build a PendingIntent for launching the confirmation UI, and send it back to the app:

        // 2b.1. Populate the request with required info.
        request.setPackageName(packageName);
        request.setUserId(userId);
        request.setSkipPrompt(mayAssociateWithoutPrompt(packageName, userId));

        // 2b.2. Prepare extras and create an Intent.
        final Bundle extras = new Bundle();
        extras.putParcelable(EXTRA_ASSOCIATION_REQUEST, request);
        extras.putBinder(EXTRA_APPLICATION_CALLBACK, callback.asBinder());
        extras.putParcelable(EXTRA_RESULT_RECEIVER, prepareForIpc(mOnRequestConfirmationReceiver));

        final Intent intent = new Intent();
        intent.setComponent(mCompanionDeviceActivity);
        intent.putExtras(extras);

        // 2b.3. Create a PendingIntent.
        final PendingIntent pendingIntent = createPendingIntent(packageUid, intent);

        // 2b.4. Send the PendingIntent back to the app.
        try {
            callback.onAssociationPending(pendingIntent);
        } catch (RemoteException ignore) { }
    }

    /**
     * Process another AssociationRequest in CompanionDeviceActivity to cancel current dialog.
     */
    PendingIntent buildAssociationCancellationIntent(@NonNull String packageName,
            @UserIdInt int userId) {
        requireNonNull(packageName, "Package name MUST NOT be null");

        enforceUsesCompanionDeviceFeature(mContext, userId, packageName);

        final int packageUid = mPackageManager.getPackageUid(packageName, 0, userId);

        final Bundle extras = new Bundle();
        extras.putBoolean(EXTRA_FORCE_CANCEL_CONFIRMATION, true);

        final Intent intent = new Intent();
        intent.setComponent(mCompanionDeviceActivity);
        intent.putExtras(extras);

        return createPendingIntent(packageUid, intent);
    }

    private void processAssociationRequestApproval(@NonNull AssociationRequest request,
            @NonNull IAssociationRequestCallback callback,
            @NonNull ResultReceiver resultReceiver, @Nullable MacAddress macAddress) {
        final String packageName = request.getPackageName();
        final int userId = request.getUserId();
        final int packageUid = mPackageManager.getPackageUid(packageName, 0, userId);

        if (DEBUG) {
            Slog.d(TAG, "processAssociationRequestApproval()\n"
                    + "   package=u" + userId + "/" + packageName + " (uid=" + packageUid + ")\n"
                    + "   request=" + request + "\n"
                    + "   macAddress=" + macAddress + "\n");
        }

        // 1. Need to check permissions again in case something changed, since we first received
        // this request.
        try {
            enforcePermissionsForAssociation(mContext, request, packageUid);
        } catch (SecurityException e) {
            // Since, at this point the caller is our own UI, we need to catch the exception on
            // forward it back to the application via the callback.
            try {
                callback.onFailure(e.getMessage());
            } catch (RemoteException ignore) { }
            return;
        }

        // 2. Create association and notify the application.
        createAssociationAndNotifyApplication(request, packageName, userId, macAddress, callback,
                resultReceiver);
    }

    private void createAssociationAndNotifyApplication(
            @NonNull AssociationRequest request, @NonNull String packageName, @UserIdInt int userId,
            @Nullable MacAddress macAddress, @NonNull IAssociationRequestCallback callback,
            @NonNull ResultReceiver resultReceiver) {
        final long callingIdentity = Binder.clearCallingIdentity();
        try {
            createAssociation(userId, packageName, macAddress, request.getDisplayName(),
                    request.getDeviceProfile(), request.getAssociatedDevice(),
                    request.isSelfManaged(),
                    callback, resultReceiver);
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }
    }

    public void createAssociation(@UserIdInt int userId, @NonNull String packageName,
            @Nullable MacAddress macAddress, @Nullable CharSequence displayName,
            @Nullable String deviceProfile, @Nullable AssociatedDevice associatedDevice,
            boolean selfManaged, @Nullable IAssociationRequestCallback callback,
            @Nullable ResultReceiver resultReceiver) {
        final int id = mService.getNewAssociationIdForPackage(userId, packageName);
        final long timestamp = System.currentTimeMillis();

        final AssociationInfo association = new AssociationInfo(id, userId, packageName,
                /* tag */ null, macAddress, displayName, deviceProfile, associatedDevice,
                selfManaged, /* notifyOnDeviceNearby */ false, /* revoked */ false,
                timestamp, Long.MAX_VALUE, /* systemDataSyncFlags */ 0);

        // Add role holder for association (if specified) and add new association to store.
        maybeGrantRoleAndStoreAssociation(association, callback, resultReceiver);

        // Don't need to update the mRevokedAssociationsPendingRoleHolderRemoval since
        // maybeRemoveRoleHolderForAssociation in PackageInactivityListener will handle the case
        // that there are other devices with the same profile, so the role holder won't be removed.
    }

    public void maybeGrantRoleAndStoreAssociation(@NonNull AssociationInfo association,
            @Nullable IAssociationRequestCallback callback,
            @Nullable ResultReceiver resultReceiver) {
        // If the "Device Profile" is specified, make the companion application a holder of the
        // corresponding role.
        // If it is null, then the operation will succeed without granting any role.
        addRoleHolderForAssociation(mService.getContext(), association, success -> {
            if (success) {
                addAssociationToStore(association);
                sendCallbackAndFinish(association, callback, resultReceiver);
            } else {
                Slog.e(TAG, "Failed to add u" + association.getUserId()
                        + "\\" + association.getPackageName()
                        + " to the list of " + association.getDeviceProfile() + " holders.");
                sendCallbackAndFinish(null, callback, resultReceiver);
            }
        });
    }

    public void enableSystemDataSync(int associationId, int flags) {
        AssociationInfo association = mAssociationStore.getAssociationById(associationId);
        AssociationInfo updated = (new AssociationInfo.Builder(association))
                .setSystemDataSyncFlags(association.getSystemDataSyncFlags() | flags).build();
        mAssociationStore.updateAssociation(updated);
    }

    public void disableSystemDataSync(int associationId, int flags) {
        AssociationInfo association = mAssociationStore.getAssociationById(associationId);
        AssociationInfo updated = (new AssociationInfo.Builder(association))
                .setSystemDataSyncFlags(association.getSystemDataSyncFlags() & (~flags)).build();
        mAssociationStore.updateAssociation(updated);
    }

    private void addAssociationToStore(@NonNull AssociationInfo association) {
        Slog.i(TAG, "New CDM association created=" + association);

        mAssociationStore.addAssociation(association);

        mService.updateSpecialAccessPermissionForAssociatedPackage(association);

        logCreateAssociation(association.getDeviceProfile());
    }

    private void sendCallbackAndFinish(@Nullable AssociationInfo association,
            @Nullable IAssociationRequestCallback callback,
            @Nullable ResultReceiver resultReceiver) {
        if (association != null) {
            // Send the association back via the app's callback
            if (callback != null) {
                try {
                    callback.onAssociationCreated(association);
                } catch (RemoteException ignore) {
                }
            }

            // Send the association back to CompanionDeviceActivity, so that it can report
            // back to the app via Activity.setResult().
            if (resultReceiver != null) {
                final Bundle data = new Bundle();
                data.putParcelable(EXTRA_ASSOCIATION, association);
                resultReceiver.send(RESULT_CODE_ASSOCIATION_CREATED, data);
            }
        } else {
            // Send the association back via the app's callback
            if (callback != null) {
                try {
                    callback.onFailure(REASON_INTERNAL_ERROR);
                } catch (RemoteException ignore) {
                }
            }

            // Send the association back to CompanionDeviceActivity, so that it can report
            // back to the app via Activity.setResult().
            if (resultReceiver != null) {
                final Bundle data = new Bundle();
                resultReceiver.send(RESULT_INTERNAL_ERROR, data);
            }
        }
    }

    private boolean willAddRoleHolder(@NonNull AssociationRequest request,
            @NonNull String packageName, @UserIdInt int userId) {
        final String deviceProfile = request.getDeviceProfile();
        if (deviceProfile == null) return false;

        final boolean isRoleHolder = Binder.withCleanCallingIdentity(
                () -> isRoleHolder(mContext, userId, packageName, deviceProfile));

        // Don't need to "grant" the role, if the package already holds the role.
        return !isRoleHolder;
    }

    private PendingIntent createPendingIntent(int packageUid, Intent intent) {
        final PendingIntent pendingIntent;
        final long token = Binder.clearCallingIdentity();

        // Using uid of the application that will own the association (usually the same
        // application that sent the request) allows us to have multiple "pending" association
        // requests at the same time.
        // If the application already has a pending association request, that PendingIntent
        // will be cancelled except application wants to cancel the request by the system.
        try {
            pendingIntent = PendingIntent.getActivityAsUser(
                    mContext, /*requestCode */ packageUid, intent,
                    FLAG_ONE_SHOT | FLAG_CANCEL_CURRENT | FLAG_IMMUTABLE,
                    ActivityOptions.makeBasic()
                            .setPendingIntentCreatorBackgroundActivityStartMode(
                                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                            .toBundle(),
                    UserHandle.CURRENT);
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        return pendingIntent;
    }

    private final ResultReceiver mOnRequestConfirmationReceiver =
            new ResultReceiver(Handler.getMain()) {
        @Override
        protected void onReceiveResult(int resultCode, Bundle data) {
            if (DEBUG) {
                Slog.d(TAG, "mOnRequestConfirmationReceiver.onReceiveResult() "
                        + "code=" + resultCode + ", " + "data=" + data);
            }

            if (resultCode != RESULT_CODE_ASSOCIATION_APPROVED) {
                Slog.w(TAG, "Unknown result code:" + resultCode);
                return;
            }

            final AssociationRequest request = data.getParcelable(EXTRA_ASSOCIATION_REQUEST, android.companion.AssociationRequest.class);
            final IAssociationRequestCallback callback = IAssociationRequestCallback.Stub
                    .asInterface(data.getBinder(EXTRA_APPLICATION_CALLBACK));
            final ResultReceiver resultReceiver = data.getParcelable(EXTRA_RESULT_RECEIVER, android.os.ResultReceiver.class);

            requireNonNull(request);
            requireNonNull(callback);
            requireNonNull(resultReceiver);

            final MacAddress macAddress;
            if (request.isSelfManaged()) {
                macAddress = null;
            } else {
                macAddress = data.getParcelable(EXTRA_MAC_ADDRESS, android.net.MacAddress.class);
                requireNonNull(macAddress);
            }

            processAssociationRequestApproval(request, callback, resultReceiver, macAddress);
        }
    };

    private boolean mayAssociateWithoutPrompt(@NonNull String packageName, @UserIdInt int userId) {
        // Throttle frequent associations
        final long now = System.currentTimeMillis();
        final List<AssociationInfo> associationForPackage =
                mAssociationStore.getAssociationsForPackage(userId, packageName);
        // Number of "recent" associations.
        int recent = 0;
        for (AssociationInfo association : associationForPackage) {
            final boolean isRecent =
                    now - association.getTimeApprovedMs() < ASSOCIATE_WITHOUT_PROMPT_WINDOW_MS;
            if (isRecent) {
                if (++recent >= ASSOCIATE_WITHOUT_PROMPT_MAX_PER_TIME_WINDOW) {
                    Slog.w(TAG, "Too many associations: " + packageName + " already "
                            + "associated " + recent + " devices within the last "
                            + ASSOCIATE_WITHOUT_PROMPT_WINDOW_MS + "ms");
                    return false;
                }
            }
        }

        return PackageUtils.isPackageAllowlisted(mContext, mPackageManager, packageName);
    }
}
