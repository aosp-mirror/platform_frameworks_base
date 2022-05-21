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
import static android.companion.CompanionDeviceManager.COMPANION_DEVICE_DISCOVERY_PACKAGE_NAME;
import static android.content.ComponentName.createRelative;

import static com.android.server.companion.CompanionDeviceManagerService.DEBUG;
import static com.android.server.companion.PackageUtils.enforceUsesCompanionDeviceFeature;
import static com.android.server.companion.PermissionsUtils.enforcePermissionsForAssociation;
import static com.android.server.companion.RolesUtils.isRoleHolder;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.app.PendingIntent;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.IAssociationRequestCallback;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManagerInternal;
import android.content.pm.Signature;
import android.net.MacAddress;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.util.Log;
import android.util.PackageUtils;
import android.util.Slog;

import com.android.internal.util.ArrayUtils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
 * {@link #createAssociationAndNotifyApplication(AssociationRequest, String, int, MacAddress, IAssociationRequestCallback)}
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
 * {@link #createAssociationAndNotifyApplication(AssociationRequest, String, int, MacAddress, IAssociationRequestCallback)}.
 *
 * @see #processNewAssociationRequest(AssociationRequest, String, int, IAssociationRequestCallback)
 * @see #processAssociationRequestApproval(AssociationRequest, IAssociationRequestCallback,
 * ResultReceiver, MacAddress)
 */
@SuppressLint("LongLogTag")
class AssociationRequestsProcessor {
    private static final String TAG = "CompanionDevice_AssociationRequestsProcessor";

    private static final ComponentName ASSOCIATION_REQUEST_APPROVAL_ACTIVITY =
            createRelative(COMPANION_DEVICE_DISCOVERY_PACKAGE_NAME, ".CompanionDeviceActivity");

    // AssociationRequestsProcessor <-> UI
    private static final String EXTRA_APPLICATION_CALLBACK = "application_callback";
    private static final String EXTRA_ASSOCIATION_REQUEST = "association_request";
    private static final String EXTRA_RESULT_RECEIVER = "result_receiver";

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
    private final @NonNull AssociationStore mAssociationStore;

    AssociationRequestsProcessor(@NonNull CompanionDeviceManagerService service,
            @NonNull AssociationStore associationStore) {
        mContext = service.getContext();
        mService = service;
        mPackageManager = service.mPackageManagerInternal;
        mAssociationStore = associationStore;
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

        // 2. Check if association can be created without launching UI (i.e. CDM needs NEITHER
        // to perform discovery NOR to collect user consent).
        if (request.isSelfManaged() && !request.isForceConfirmation()
                && !willAddRoleHolder(request, packageName, userId)) {
            // 2a. Create association right away.
            createAssociationAndNotifyApplication(request, packageName, userId,
                    /*macAddress*/ null, callback);
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
        intent.setComponent(ASSOCIATION_REQUEST_APPROVAL_ACTIVITY);
        intent.putExtras(extras);

        // 2b.3. Create a PendingIntent.
        final PendingIntent pendingIntent;
        final long token = Binder.clearCallingIdentity();
        try {
            // Using uid of the application that will own the association (usually the same
            // application that sent the request) allows us to have multiple "pending" association
            // requests at the same time.
            // If the application already has a pending association request, that PendingIntent
            // will be cancelled.
            pendingIntent = PendingIntent.getActivityAsUser(
                    mContext, /*requestCode */ packageUid, intent,
                    FLAG_ONE_SHOT | FLAG_CANCEL_CURRENT | FLAG_IMMUTABLE,
                    /* options= */ null, UserHandle.CURRENT);
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        // 2b.4. Send the PendingIntent back to the app.
        try {
            callback.onAssociationPending(pendingIntent);
        } catch (RemoteException ignore) { }
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
        final AssociationInfo association = createAssociationAndNotifyApplication(
                request, packageName, userId, macAddress, callback);

        // 3. Send the association back the Approval Activity, so that it can report back to the app
        // via Activity.setResult().
        final Bundle data = new Bundle();
        data.putParcelable(EXTRA_ASSOCIATION, association);
        resultReceiver.send(RESULT_CODE_ASSOCIATION_CREATED, data);
    }

    private AssociationInfo createAssociationAndNotifyApplication(
            @NonNull AssociationRequest request, @NonNull String packageName, @UserIdInt int userId,
            @Nullable MacAddress macAddress, @NonNull IAssociationRequestCallback callback) {
        final AssociationInfo association;
        final long callingIdentity = Binder.clearCallingIdentity();
        try {
            association = mService.createAssociation(userId, packageName, macAddress,
                    request.getDisplayName(), request.getDeviceProfile(), request.isSelfManaged());
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }

        try {
            callback.onAssociationCreated(association);
        } catch (RemoteException ignore) { }

        return association;
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

            final AssociationRequest request = data.getParcelable(EXTRA_ASSOCIATION_REQUEST);
            final IAssociationRequestCallback callback = IAssociationRequestCallback.Stub
                    .asInterface(data.getBinder(EXTRA_APPLICATION_CALLBACK));
            final ResultReceiver resultReceiver = data.getParcelable(EXTRA_RESULT_RECEIVER);

            requireNonNull(request);
            requireNonNull(callback);
            requireNonNull(resultReceiver);

            final MacAddress macAddress;
            if (request.isSelfManaged()) {
                macAddress = null;
            } else {
                macAddress = data.getParcelable(EXTRA_MAC_ADDRESS);
                requireNonNull(macAddress);
            }

            processAssociationRequestApproval(request, callback, resultReceiver, macAddress);
        }
    };

    private boolean mayAssociateWithoutPrompt(@NonNull String packageName, @UserIdInt int userId) {
        // Below we check if the requesting package is allowlisted (usually by the OEM) for creating
        // CDM associations without user confirmation (prompt).
        // For this we'll check to config arrays:
        // - com.android.internal.R.array.config_companionDevicePackages
        // and
        // - com.android.internal.R.array.config_companionDeviceCerts.
        // Both arrays are expected to contain similar number of entries.
        // config_companionDevicePackages contains package names of the allowlisted packages.
        // config_companionDeviceCerts contains SHA256 digests of the signatures of the
        // corresponding packages.
        // If a package may be signed with one of several certificates, its package name would
        // appear multiple times in the config_companionDevicePackages, with different entries
        // (one for each of the valid signing certificates) at the corresponding positions in
        // config_companionDeviceCerts.
        final String[] allowlistedPackages = mContext.getResources()
                .getStringArray(com.android.internal.R.array.config_companionDevicePackages);
        if (!ArrayUtils.contains(allowlistedPackages, packageName)) {
            if (DEBUG) {
                Log.d(TAG, packageName + " is not allowlisted for creating associations "
                        + "without user confirmation (prompt)");
                Log.v(TAG, "Allowlisted packages=" + Arrays.toString(allowlistedPackages));
            }
            return false;
        }

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

        final String[] allowlistedPackagesSignatureDigests = mContext.getResources()
                .getStringArray(com.android.internal.R.array.config_companionDeviceCerts);
        final Set<String> allowlistedSignatureDigestsForRequestingPackage = new HashSet<>();
        for (int i = 0; i < allowlistedPackages.length; i++) {
            if (allowlistedPackages[i].equals(packageName)) {
                final String digest = allowlistedPackagesSignatureDigests[i].replaceAll(":", "");
                allowlistedSignatureDigestsForRequestingPackage.add(digest);
            }
        }

        final Signature[] requestingPackageSignatures = mPackageManager.getPackage(packageName)
                .getSigningDetails().getSignatures();
        final String[] requestingPackageSignatureDigests =
                PackageUtils.computeSignaturesSha256Digests(requestingPackageSignatures);

        boolean requestingPackageSignatureAllowlisted = false;
        for (String signatureDigest : requestingPackageSignatureDigests) {
            if (allowlistedSignatureDigestsForRequestingPackage.contains(signatureDigest)) {
                requestingPackageSignatureAllowlisted = true;
                break;
            }
        }

        if (!requestingPackageSignatureAllowlisted) {
            Slog.w(TAG, "Certificate mismatch for allowlisted package " + packageName);
            if (DEBUG) {
                Log.d(TAG, "  > allowlisted signatures for " + packageName + ": ["
                        + String.join(", ", allowlistedSignatureDigestsForRequestingPackage)
                        + "]");
                Log.d(TAG, "  > actual signatures for " + packageName + ": "
                        + Arrays.toString(requestingPackageSignatureDigests));
            }
        }

        return requestingPackageSignatureAllowlisted;
    }

    /**
     * Convert an instance of a "locally-defined" ResultReceiver to an instance of
     * {@link android.os.ResultReceiver} itself, which the receiving process will be able to
     * unmarshall.
     */
    private static <T extends ResultReceiver> ResultReceiver prepareForIpc(T resultReceiver) {
        final Parcel parcel = Parcel.obtain();
        resultReceiver.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        final ResultReceiver ipcFriendly = ResultReceiver.CREATOR.createFromParcel(parcel);
        parcel.recycle();

        return ipcFriendly;
    }
}
