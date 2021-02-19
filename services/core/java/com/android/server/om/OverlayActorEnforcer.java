/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.om;

import android.annotation.NonNull;
import android.content.om.OverlayInfo;
import android.content.om.OverlayableInfo;
import android.net.Uri;
import android.os.Process;
import android.text.TextUtils;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;
import com.android.server.pm.parsing.pkg.AndroidPackage;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Performs verification that a calling UID can act on a target package's overlayable.
 *
 * Actors requirements are specified in {@link android.content.om.OverlayManager}.
 *
 * @hide
 */
public class OverlayActorEnforcer {

    private final PackageManagerHelper mPackageManager;

    /**
     * @return nullable actor result with {@link ActorState} failure status
     */
    static Pair<String, ActorState> getPackageNameForActor(@NonNull String actorUriString,
            @NonNull Map<String, Map<String, String>> namedActors) {
        Uri actorUri = Uri.parse(actorUriString);

        String actorScheme = actorUri.getScheme();
        List<String> actorPathSegments = actorUri.getPathSegments();
        if (!"overlay".equals(actorScheme) || CollectionUtils.size(actorPathSegments) != 1) {
            return Pair.create(null, ActorState.INVALID_OVERLAYABLE_ACTOR_NAME);
        }

        if (namedActors.isEmpty()) {
            return Pair.create(null, ActorState.NO_NAMED_ACTORS);
        }

        String actorNamespace = actorUri.getAuthority();
        Map<String, String> namespace = namedActors.get(actorNamespace);
        if (ArrayUtils.isEmpty(namespace)) {
            return Pair.create(null, ActorState.MISSING_NAMESPACE);
        }

        String actorName = actorPathSegments.get(0);
        String packageName = namespace.get(actorName);
        if (TextUtils.isEmpty(packageName)) {
            return Pair.create(null, ActorState.MISSING_ACTOR_NAME);
        }

        return Pair.create(packageName, ActorState.ALLOWED);
    }

    public OverlayActorEnforcer(@NonNull PackageManagerHelper packageManager) {
        mPackageManager = packageManager;
    }

    void enforceActor(@NonNull OverlayInfo overlayInfo, @NonNull String methodName,
            int callingUid, int userId) throws SecurityException {
        final ActorState actorState = isAllowedActor(methodName, overlayInfo, callingUid, userId);
        if (actorState == ActorState.ALLOWED) {
            return;
        }

        final String targetOverlayableName = overlayInfo.targetOverlayableName;
        final String errorMessage = "UID" + callingUid + " is not allowed to call " + methodName
                + " for "
                + (TextUtils.isEmpty(targetOverlayableName) ? "" : (targetOverlayableName + " in "))
                + overlayInfo.targetPackageName + " for user " + userId;
        Slog.w(OverlayManagerService.TAG, errorMessage + " because " + actorState);
        throw new SecurityException(errorMessage);
    }

    /**
     * See {@link OverlayActorEnforcer} class comment for actor requirements.
     * @return true if the actor is allowed to act on the target overlayInfo
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public ActorState isAllowedActor(String methodName, OverlayInfo overlayInfo,
            int callingUid, int userId) {
        // Checked first to avoid package not found errors, which are ignored for calls from shell
        switch (callingUid) {
            case Process.ROOT_UID:
            case Process.SYSTEM_UID:
                return ActorState.ALLOWED;
        }

        final String targetPackageName = overlayInfo.targetPackageName;
        final AndroidPackage targetPkgInfo = mPackageManager.getPackageForUser(targetPackageName,
                userId);
        if (targetPkgInfo == null) {
            return ActorState.TARGET_NOT_FOUND;
        }

        if (targetPkgInfo.isDebuggable()) {
            return ActorState.ALLOWED;
        }

        String[] callingPackageNames = mPackageManager.getPackagesForUid(callingUid);
        if (ArrayUtils.isEmpty(callingPackageNames)) {
            return ActorState.NO_PACKAGES_FOR_UID;
        }

        // A target is always an allowed actor for itself
        if (ArrayUtils.contains(callingPackageNames, targetPackageName)) {
            return ActorState.ALLOWED;
        }

        String targetOverlayableName = overlayInfo.targetOverlayableName;

        if (TextUtils.isEmpty(targetOverlayableName)) {
            try {
                if (mPackageManager.doesTargetDefineOverlayable(targetPackageName, userId)) {
                    return ActorState.MISSING_TARGET_OVERLAYABLE_NAME;
                } else {
                    // If there's no overlayable defined, fallback to the legacy permission check
                    try {
                        mPackageManager.enforcePermission(
                                android.Manifest.permission.CHANGE_OVERLAY_PACKAGES, methodName);

                        // If the previous method didn't throw, check passed
                        return ActorState.ALLOWED;
                    } catch (SecurityException e) {
                        return ActorState.MISSING_LEGACY_PERMISSION;
                    }
                }
            } catch (IOException e) {
                return ActorState.ERROR_READING_OVERLAYABLE;
            }
        }

        OverlayableInfo targetOverlayable;
        try {
            targetOverlayable = mPackageManager.getOverlayableForTarget(targetPackageName,
                    targetOverlayableName, userId);
        } catch (IOException e) {
            return ActorState.UNABLE_TO_GET_TARGET_OVERLAYABLE;
        }

        if (targetOverlayable == null) {
            return ActorState.MISSING_OVERLAYABLE;
        }

        String actor = targetOverlayable.actor;
        if (TextUtils.isEmpty(actor)) {
            // If there's no actor defined, fallback to the legacy permission check
            try {
                mPackageManager.enforcePermission(
                        android.Manifest.permission.CHANGE_OVERLAY_PACKAGES, methodName);

                // If the previous method didn't throw, check passed
                return ActorState.ALLOWED;
            } catch (SecurityException e) {
                return ActorState.MISSING_LEGACY_PERMISSION;
            }
        }

        Map<String, Map<String, String>> namedActors = mPackageManager.getNamedActors();
        Pair<String, ActorState> actorUriPair = getPackageNameForActor(actor, namedActors);
        ActorState actorUriState = actorUriPair.second;
        if (actorUriState != ActorState.ALLOWED) {
            return actorUriState;
        }

        String actorPackageName = actorUriPair.first;
        AndroidPackage actorPackage = mPackageManager.getPackageForUser(actorPackageName, userId);
        if (actorPackage == null) {
            return ActorState.ACTOR_NOT_FOUND;
        }

        // Currently only pre-installed apps can be actors
        if (!actorPackage.isSystem()) {
            return ActorState.ACTOR_NOT_PREINSTALLED;
        }

        if (ArrayUtils.contains(callingPackageNames, actorPackageName)) {
            return ActorState.ALLOWED;
        }

        return ActorState.INVALID_ACTOR;
    }

    /**
     * For easier logging/debugging, a set of all possible failure/success states when running
     * enforcement.
     *
     * The ordering of this enum should be maintained in the order that cases are checked in code,
     * as this ordering is used inside OverlayActorEnforcerTests.
     */
    public enum ActorState {
        TARGET_NOT_FOUND,
        NO_PACKAGES_FOR_UID,
        MISSING_TARGET_OVERLAYABLE_NAME,
        MISSING_LEGACY_PERMISSION,
        ERROR_READING_OVERLAYABLE,
        UNABLE_TO_GET_TARGET_OVERLAYABLE,
        MISSING_OVERLAYABLE,
        INVALID_OVERLAYABLE_ACTOR_NAME,
        NO_NAMED_ACTORS,
        MISSING_NAMESPACE,
        MISSING_ACTOR_NAME,
        ACTOR_NOT_FOUND,
        ACTOR_NOT_PREINSTALLED,
        INVALID_ACTOR,
        ALLOWED
    }
}
