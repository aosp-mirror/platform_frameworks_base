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
import android.annotation.Nullable;
import android.content.om.OverlayInfo;
import android.content.om.OverlayableInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Process;
import android.os.RemoteException;
import android.text.TextUtils;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;
import com.android.server.SystemConfig;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Performs verification that a calling UID can act on a target package's overlayable.
 *
 * @hide
 */
public class OverlayActorEnforcer {

    private final VerifyCallback mVerifyCallback;

    public OverlayActorEnforcer(@NonNull VerifyCallback verifyCallback) {
        mVerifyCallback = verifyCallback;
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
                + overlayInfo.targetPackageName + " because " + actorState
        );
    }

    /**
     * An actor is valid if any of the following is true:
     * - is {@link Process#ROOT_UID}, {@link Process#SYSTEM_UID}
     * - is the target overlay package
     * - has the CHANGE_OVERLAY_PACKAGES permission and an actor is not defined
     * - is the same the as the package defined in {@link SystemConfig#getNamedActors()} for a given
     *     namespace and actor name
     *
     * @return true if the actor is allowed to act on the target overlayInfo
     */
    private ActorState isAllowedActor(String methodName, OverlayInfo overlayInfo,
            int callingUid, int userId) {
        switch (callingUid) {
            case Process.ROOT_UID:
            case Process.SYSTEM_UID:
                return ActorState.ALLOWED;
        }

        String[] callingPackageNames = mVerifyCallback.getPackagesForUid(callingUid);
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
                if (mVerifyCallback.doesTargetDefineOverlayable(targetPackageName, userId)) {
                    return ActorState.MISSING_TARGET_OVERLAYABLE_NAME;
                } else {
                    // If there's no overlayable defined, fallback to the legacy permission check
                    try {
                        mVerifyCallback.enforcePermission(
                                android.Manifest.permission.CHANGE_OVERLAY_PACKAGES, methodName);

                        // If the previous method didn't throw, check passed
                        return ActorState.ALLOWED;
                    } catch (SecurityException e) {
                        return ActorState.MISSING_LEGACY_PERMISSION;
                    }
                }
            } catch (RemoteException | IOException e) {
                return ActorState.ERROR_READING_OVERLAYABLE;
            }
        }

        OverlayableInfo targetOverlayable;
        try {
            targetOverlayable = mVerifyCallback.getOverlayableForTarget(targetPackageName,
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
                mVerifyCallback.enforcePermission(
                        android.Manifest.permission.CHANGE_OVERLAY_PACKAGES, methodName);

                // If the previous method didn't throw, check passed
                return ActorState.ALLOWED;
            } catch (SecurityException e) {
                return ActorState.MISSING_LEGACY_PERMISSION;
            }
        }

        Map<String, ? extends Map<String, String>> namedActors = mVerifyCallback.getNamedActors();
        if (namedActors.isEmpty()) {
            return ActorState.NO_NAMED_ACTORS;
        }

        Uri actorUri = Uri.parse(actor);

        String actorScheme = actorUri.getScheme();
        List<String> actorPathSegments = actorUri.getPathSegments();
        if (!"overlay".equals(actorScheme) || CollectionUtils.size(actorPathSegments) != 1) {
            return ActorState.INVALID_OVERLAYABLE_ACTOR_NAME;
        }

        String actorNamespace = actorUri.getAuthority();
        Map<String, String> namespace = namedActors.get(actorNamespace);
        if (namespace == null) {
            return ActorState.MISSING_NAMESPACE;
        }

        String actorName = actorPathSegments.get(0);
        String packageName = namespace.get(actorName);
        if (TextUtils.isEmpty(packageName)) {
            return ActorState.MISSING_ACTOR_NAME;
        }

        PackageInfo packageInfo = mVerifyCallback.getPackageInfo(packageName, userId);
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
    private enum ActorState {
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

    /**
     * Delegate to the system for querying information about packages.
     */
    public interface VerifyCallback {

        /**
         * Read from the APK and AndroidManifest of a package to return the overlayable defined for
         * a given name.
         *
         * @throws IOException if the target can't be read
         */
        @Nullable
        OverlayableInfo getOverlayableForTarget(@NonNull String packageName,
                @Nullable String targetOverlayableName, int userId)
                throws IOException;

        /**
         * @see android.content.pm.PackageManager#getPackagesForUid(int)
         */
        @Nullable
        String[] getPackagesForUid(int uid);

        /**
         * @param userId user to filter package visibility by
         * @see android.content.pm.PackageManager#getPackageInfo(String, int)
         */
        @Nullable
        PackageInfo getPackageInfo(@NonNull String packageName, int userId);

        /**
         * @return map of system pre-defined, uniquely named actors; keys are namespace,
         * value maps actor name to package name
         */
        @NonNull
        Map<String, ? extends Map<String, String>> getNamedActors();

        /**
         * @return true if the target package has declared an overlayable
         */
        boolean doesTargetDefineOverlayable(String targetPackageName, int userId)
                throws RemoteException, IOException;

        /**
         * @throws SecurityException containing message if the caller doesn't have the given
         *                           permission
         */
        void enforcePermission(String permission, String message) throws SecurityException;
    }
}
