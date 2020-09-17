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
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Process;
import android.text.TextUtils;
import android.util.Pair;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;

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

    // By default, the reason is not logged to prevent leaks of why it failed
    private static final boolean DEBUG_REASON = false;

    private final OverlayableInfoCallback mOverlayableCallback;

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
        if (namespace == null) {
            return Pair.create(null, ActorState.MISSING_NAMESPACE);
        }

        String actorName = actorPathSegments.get(0);
        String packageName = namespace.get(actorName);
        if (TextUtils.isEmpty(packageName)) {
            return Pair.create(null, ActorState.MISSING_ACTOR_NAME);
        }

        return Pair.create(packageName, ActorState.ALLOWED);
    }

    public OverlayActorEnforcer(@NonNull OverlayableInfoCallback overlayableCallback) {
        mOverlayableCallback = overlayableCallback;
    }

    void enforceActor(@NonNull OverlayInfo overlayInfo, @NonNull String methodName,
            int callingUid, int userId) throws SecurityException {
        ActorState actorState = isAllowedActor(methodName, overlayInfo, callingUid, userId);
        if (actorState == ActorState.ALLOWED) {
            return;
        }

        String targetOverlayableName = overlayInfo.targetOverlayableName;
        throw new SecurityException("UID" + callingUid + " is not allowed to call "
                + methodName + " for "
                + (TextUtils.isEmpty(targetOverlayableName) ? "" : (targetOverlayableName + " in "))
                + overlayInfo.targetPackageName + (DEBUG_REASON ? (" because " + actorState) : "")
        );
    }

    /**
     * See {@link OverlayActorEnforcer} class comment for actor requirements.
     * @return true if the actor is allowed to act on the target overlayInfo
     */
    private ActorState isAllowedActor(String methodName, OverlayInfo overlayInfo,
            int callingUid, int userId) {
        switch (callingUid) {
            case Process.ROOT_UID:
            case Process.SYSTEM_UID:
                return ActorState.ALLOWED;
        }

        String[] callingPackageNames = mOverlayableCallback.getPackagesForUid(callingUid);
        if (ArrayUtils.isEmpty(callingPackageNames)) {
            return ActorState.NO_PACKAGES_FOR_UID;
        }

        // A target is always an allowed actor for itself
        String targetPackageName = overlayInfo.targetPackageName;
        if (ArrayUtils.contains(callingPackageNames, targetPackageName)) {
            return ActorState.ALLOWED;
        }

        String targetOverlayableName = overlayInfo.targetOverlayableName;

        if (TextUtils.isEmpty(targetOverlayableName)) {
            try {
                if (mOverlayableCallback.doesTargetDefineOverlayable(targetPackageName, userId)) {
                    return ActorState.MISSING_TARGET_OVERLAYABLE_NAME;
                } else {
                    // If there's no overlayable defined, fallback to the legacy permission check
                    try {
                        mOverlayableCallback.enforcePermission(
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
            targetOverlayable = mOverlayableCallback.getOverlayableForTarget(targetPackageName,
                    targetOverlayableName, userId);
        } catch (IOException e) {
            return ActorState.UNABLE_TO_GET_TARGET;
        }

        if (targetOverlayable == null) {
            return ActorState.MISSING_OVERLAYABLE;
        }

        String actor = targetOverlayable.actor;
        if (TextUtils.isEmpty(actor)) {
            // If there's no actor defined, fallback to the legacy permission check
            try {
                mOverlayableCallback.enforcePermission(
                        android.Manifest.permission.CHANGE_OVERLAY_PACKAGES, methodName);

                // If the previous method didn't throw, check passed
                return ActorState.ALLOWED;
            } catch (SecurityException e) {
                return ActorState.MISSING_LEGACY_PERMISSION;
            }
        }

        Map<String, Map<String, String>> namedActors = mOverlayableCallback.getNamedActors();
        Pair<String, ActorState> actorUriPair = getPackageNameForActor(actor, namedActors);
        ActorState actorUriState = actorUriPair.second;
        if (actorUriState != ActorState.ALLOWED) {
            return actorUriState;
        }

        String packageName = actorUriPair.first;
        PackageInfo packageInfo = mOverlayableCallback.getPackageInfo(packageName, userId);
        if (packageInfo == null) {
            return ActorState.MISSING_APP_INFO;
        }

        ApplicationInfo appInfo = packageInfo.applicationInfo;
        if (appInfo == null) {
            return ActorState.MISSING_APP_INFO;
        }

        // Currently only pre-installed apps can be actors
        if (!appInfo.isSystemApp() && !appInfo.isUpdatedSystemApp()) {
            return ActorState.ACTOR_NOT_PREINSTALLED;
        }

        if (ArrayUtils.contains(callingPackageNames, packageName)) {
            return ActorState.ALLOWED;
        }

        return ActorState.INVALID_ACTOR;
    }

    /**
     * For easier logging/debugging, a set of all possible failure/success states when running
     * enforcement.
     */
    public enum ActorState {
        ALLOWED,
        INVALID_ACTOR,
        MISSING_NAMESPACE,
        MISSING_PACKAGE,
        MISSING_APP_INFO,
        ACTOR_NOT_PREINSTALLED,
        NO_PACKAGES_FOR_UID,
        MISSING_ACTOR_NAME,
        ERROR_READING_OVERLAYABLE,
        MISSING_TARGET_OVERLAYABLE_NAME,
        MISSING_OVERLAYABLE,
        INVALID_OVERLAYABLE_ACTOR_NAME,
        NO_NAMED_ACTORS,
        UNABLE_TO_GET_TARGET,
        MISSING_LEGACY_PERMISSION
    }
}
