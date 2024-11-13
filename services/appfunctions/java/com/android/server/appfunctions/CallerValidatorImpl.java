/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.appfunctions;

import static android.app.appfunctions.AppFunctionStaticMetadataHelper.APP_FUNCTION_STATIC_METADATA_DB;
import static android.app.appfunctions.AppFunctionStaticMetadataHelper.APP_FUNCTION_STATIC_NAMESPACE;
import static android.app.appfunctions.AppFunctionStaticMetadataHelper.STATIC_PROPERTY_RESTRICT_CALLERS_WITH_EXECUTE_APP_FUNCTIONS;
import static android.app.appfunctions.AppFunctionStaticMetadataHelper.getDocumentIdForAppFunction;

import static com.android.server.appfunctions.AppFunctionExecutors.THREAD_POOL_EXECUTOR;

import android.Manifest;
import android.annotation.BinderThread;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.app.admin.DevicePolicyManager;
import android.app.appsearch.AppSearchBatchResult;
import android.app.appsearch.AppSearchManager;
import android.app.appsearch.AppSearchManager.SearchContext;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.GetByDocumentIdRequest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;

import com.android.internal.infra.AndroidFuture;

import java.util.Objects;

/* Validates that caller has the correct privilege to call an AppFunctionManager Api. */
class CallerValidatorImpl implements CallerValidator {
    private final Context mContext;

    CallerValidatorImpl(@NonNull Context context) {
        mContext = Objects.requireNonNull(context);
    }

    @Override
    @NonNull
    @BinderThread
    public String validateCallingPackage(@NonNull String claimedCallingPackage) {
        int callingUid = Binder.getCallingUid();
        final long callingIdentityToken = Binder.clearCallingIdentity();
        try {
            validateCallingPackageInternal(callingUid, claimedCallingPackage);
            return claimedCallingPackage;
        } finally {
            Binder.restoreCallingIdentity(callingIdentityToken);
        }
    }

    @Override
    @NonNull
    @BinderThread
    public UserHandle verifyTargetUserHandle(
            @NonNull UserHandle targetUserHandle, @NonNull String claimedCallingPackage) {
        int callingPid = Binder.getCallingPid();
        int callingUid = Binder.getCallingUid();
        final long callingIdentityToken = Binder.clearCallingIdentity();
        try {
            return handleIncomingUser(
                    claimedCallingPackage, targetUserHandle, callingPid, callingUid);
        } finally {
            Binder.restoreCallingIdentity(callingIdentityToken);
        }
    }

    @Override
    @RequiresPermission(
            anyOf = {
                Manifest.permission.EXECUTE_APP_FUNCTIONS_TRUSTED,
                Manifest.permission.EXECUTE_APP_FUNCTIONS
            },
            conditional = true)
    public AndroidFuture<Boolean> verifyCallerCanExecuteAppFunction(
            int callingUid,
            int callingPid,
            @NonNull String callerPackageName,
            @NonNull String targetPackageName,
            @NonNull String functionId) {
        if (callerPackageName.equals(targetPackageName)) {
            return AndroidFuture.completedFuture(true);
        }

        boolean hasTrustedExecutionPermission =
                mContext.checkPermission(
                                Manifest.permission.EXECUTE_APP_FUNCTIONS_TRUSTED,
                                callingPid,
                                callingUid)
                        == PackageManager.PERMISSION_GRANTED;

        if (hasTrustedExecutionPermission) {
            return AndroidFuture.completedFuture(true);
        }

        boolean hasExecutionPermission =
                mContext.checkPermission(
                                Manifest.permission.EXECUTE_APP_FUNCTIONS, callingPid, callingUid)
                        == PackageManager.PERMISSION_GRANTED;

        if (!hasExecutionPermission) {
            return AndroidFuture.completedFuture(false);
        }

        FutureAppSearchSession futureAppSearchSession =
                new FutureAppSearchSessionImpl(
                        mContext.getSystemService(AppSearchManager.class),
                        THREAD_POOL_EXECUTOR,
                        new SearchContext.Builder(APP_FUNCTION_STATIC_METADATA_DB).build());

        String documentId = getDocumentIdForAppFunction(targetPackageName, functionId);

        return futureAppSearchSession
                .getByDocumentId(
                        new GetByDocumentIdRequest.Builder(APP_FUNCTION_STATIC_NAMESPACE)
                                .addIds(documentId)
                                .build())
                .thenApply(
                        batchResult -> getGenericDocumentFromBatchResult(batchResult, documentId))
                .thenApply(document -> !getRestrictCallersWithExecuteAppFunctionsProperty(document))
                .whenComplete(
                        (result, throwable) -> {
                            futureAppSearchSession.close();
                        });
    }

    private static GenericDocument getGenericDocumentFromBatchResult(
            AppSearchBatchResult<String, GenericDocument> result, String documentId) {
        if (result.isSuccess()) {
            return result.getSuccesses().get(documentId);
        }

        AppSearchResult<GenericDocument> failedResult = result.getFailures().get(documentId);
        throw new AppSearchException(
                failedResult.getResultCode(),
                "Unable to retrieve document with id: "
                        + documentId
                        + " due to "
                        + failedResult.getErrorMessage());
    }

    private static boolean getRestrictCallersWithExecuteAppFunctionsProperty(
            GenericDocument genericDocument) {
        return genericDocument.getPropertyBoolean(
                STATIC_PROPERTY_RESTRICT_CALLERS_WITH_EXECUTE_APP_FUNCTIONS);
    }

    @Override
    public boolean isUserOrganizationManaged(@NonNull UserHandle targetUser) {
        if (Objects.requireNonNull(mContext.getSystemService(DevicePolicyManager.class))
                .isDeviceManaged()) {
            return true;
        }
        return Objects.requireNonNull(mContext.getSystemService(UserManager.class))
                .isManagedProfile(targetUser.getIdentifier());
    }

    /**
     * Helper for dealing with incoming user arguments to system service calls.
     *
     * <p>Takes care of checking permissions and if the target is special user, this method will
     * simply throw.
     *
     * @param callingPackageName The package name of the caller.
     * @param targetUserHandle The user which the caller is requesting to execute as.
     * @param callingPid The actual pid of the caller as determined by Binder.
     * @param callingUid The actual uid of the caller as determined by Binder.
     * @return the user handle that the call should run as. Will always be a concrete user.
     * @throws IllegalArgumentException if the target user is a special user.
     * @throws SecurityException if caller trying to interact across user without {@link
     *     Manifest.permission#INTERACT_ACROSS_USERS_FULL}
     */
    @NonNull
    private UserHandle handleIncomingUser(
            @NonNull String callingPackageName,
            @NonNull UserHandle targetUserHandle,
            int callingPid,
            int callingUid) {
        UserHandle callingUserHandle = UserHandle.getUserHandleForUid(callingUid);
        if (callingUserHandle.equals(targetUserHandle)) {
            return targetUserHandle;
        }

        // Duplicates UserController#ensureNotSpecialUser
        if (targetUserHandle.getIdentifier() < 0) {
            throw new IllegalArgumentException(
                    "Call does not support special user " + targetUserHandle);
        }

        if (mContext.checkPermission(
                        Manifest.permission.INTERACT_ACROSS_USERS_FULL, callingPid, callingUid)
                == PackageManager.PERMISSION_GRANTED) {
            try {
                mContext.createPackageContextAsUser(
                        callingPackageName, /* flags= */ 0, targetUserHandle);
            } catch (PackageManager.NameNotFoundException e) {
                throw new SecurityException(
                        "Package: "
                                + callingPackageName
                                + " haven't installed for user "
                                + targetUserHandle.getIdentifier());
            }
            return targetUserHandle;
        }
        throw new SecurityException(
                "Permission denied while calling from uid "
                        + callingUid
                        + " with "
                        + targetUserHandle
                        + "; Requires permission: "
                        + Manifest.permission.INTERACT_ACROSS_USERS_FULL);
    }

    /**
     * Checks that the caller's supposed package name matches the uid making the call.
     *
     * @throws SecurityException if the package name and uid don't match.
     */
    private void validateCallingPackageInternal(
            int actualCallingUid, @NonNull String claimedCallingPackage) {
        UserHandle callingUserHandle = UserHandle.getUserHandleForUid(actualCallingUid);
        Context actualCallingUserContext =
                mContext.createContextAsUser(callingUserHandle, /* flags= */ 0);
        int claimedCallingUid = getPackageUid(actualCallingUserContext, claimedCallingPackage);
        if (claimedCallingUid != actualCallingUid) {
            throw new SecurityException(
                    "Specified calling package ["
                            + claimedCallingPackage
                            + "] does not match the calling uid "
                            + actualCallingUid);
        }
    }

    /**
     * Finds the UID of the {@code packageName} in the given {@code context}. Returns {@link
     * Process#INVALID_UID} if unable to find the UID.
     */
    private int getPackageUid(@NonNull Context context, @NonNull String packageName) {
        try {
            return context.getPackageManager().getPackageUid(packageName, /* flags= */ 0);
        } catch (PackageManager.NameNotFoundException e) {
            return Process.INVALID_UID;
        }
    }
}
