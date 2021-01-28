/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.content.om;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.compat.Compatibility;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;

import com.android.server.SystemConfig;

import java.util.List;

/**
 * Updates OverlayManager state; gets information about installed overlay packages.
 *
 * <p>Users of this API must be actors of any overlays they desire to change the state of.</p>
 *
 * <p>An actor is a package responsible for managing the state of overlays targeting overlayables
 * that specify the actor. For example, an actor may enable or disable an overlay or otherwise
 * change its state.</p>
 *
 * <p>Actors are specified as part of the overlayable definition.
 *
 * <pre>{@code
 * <overlayable name="OverlayableResourcesName" actor="overlay://namespace/actorName">
 * }</pre></p>
 *
 * <p>Actors are defined through {@link SystemConfig}. Only system packages can be used.
 * The namespace "android" is reserved for use by AOSP and any "android" definitions must
 * have an implementation on device that fulfill their intended functionality.</p>
 *
 * <pre>{@code
 * <named-actor
 *     namespace="namespace"
 *     name="actorName"
 *     package="com.example.pkg"
 *     />
 * }</pre></p>
 *
 * <p>An actor can manipulate a particular overlay if any of the following is true:
 * <ul>
 * <li>its UID is {@link Process#ROOT_UID}, {@link Process#SYSTEM_UID}</li>
 * <li>it is the target of the overlay package</li>
 * <li>it has the CHANGE_OVERLAY_PACKAGES permission and the target does not specify an actor</li>
 * <li>it is the actor specified by the overlayable</li>
 * </ul></p>
 *
 * @hide
 */
@SystemApi
@SystemService(Context.OVERLAY_SERVICE)
public class OverlayManager {

    private final IOverlayManager mService;
    private final Context mContext;

    /**
     * Pre R a {@link java.lang.SecurityException} would only be thrown by setEnabled APIs (e
     * .g. {@link #setEnabled(String, boolean, UserHandle)}) for a permission error.
     * Since R this no longer holds true, and {@link java.lang.SecurityException} can be
     * thrown for any number of reasons, none of which are exposed to the caller.
     *
     * <p>To maintain existing API behavior, if a legacy permission failure or actor enforcement
     * failure occurs for an app not yet targeting R, coerce it into an {@link
     * java.lang.IllegalStateException}, which existed in the source prior to R.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.R)
    private static final long THROW_SECURITY_EXCEPTIONS = 147340954;

    /**
     * Creates a new instance.
     *
     * @param context The current context in which to operate.
     * @param service The backing system service.
     *
     * @hide
     */
    public OverlayManager(Context context, IOverlayManager service) {
        mContext = context;
        mService = service;
    }

    /** @hide */
    public OverlayManager(Context context) {
        this(context, IOverlayManager.Stub.asInterface(
            ServiceManager.getService(Context.OVERLAY_SERVICE)));
    }

    /**
     * Request that an overlay package is enabled and any other overlay packages with the same
     * target package and category are disabled.
     *
     * If a set of overlay packages share the same category, single call to this method is
     * equivalent to multiple calls to {@link #setEnabled(String, boolean, UserHandle)}.
     *
     * The caller must pass the actor requirements specified in the class comment.
     *
     * @param packageName the name of the overlay package to enable.
     * @param user The user for which to change the overlay.
     *
     * @throws SecurityException when caller is not allowed to enable {@param packageName}
     * @throws IllegalStateException when enabling fails otherwise
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            "android.permission.INTERACT_ACROSS_USERS",
            "android.permission.INTERACT_ACROSS_USERS_FULL"
    })
    public void setEnabledExclusiveInCategory(@NonNull final String packageName,
            @NonNull UserHandle user) throws SecurityException, IllegalStateException {
        try {
            if (!mService.setEnabledExclusiveInCategory(packageName, user.getIdentifier())) {
                throw new IllegalStateException("setEnabledExclusiveInCategory failed");
            }
        } catch (SecurityException e) {
            rethrowSecurityException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Request that an overlay package is enabled or disabled.
     *
     * While {@link #setEnabledExclusiveInCategory(String, UserHandle)} doesn't support disabling
     * every overlay in a category, this method allows you to disable everything.
     *
     * The caller must pass the actor requirements specified in the class comment.
     *
     * @param packageName the name of the overlay package to enable.
     * @param enable {@code false} if the overlay should be turned off.
     * @param user The user for which to change the overlay.
     *
     * @throws SecurityException when caller is not allowed to enable/disable {@param packageName}
     * @throws IllegalStateException when enabling/disabling fails otherwise
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            "android.permission.INTERACT_ACROSS_USERS",
            "android.permission.INTERACT_ACROSS_USERS_FULL"
    })
    public void setEnabled(@NonNull final String packageName, final boolean enable,
            @NonNull UserHandle user) throws SecurityException, IllegalStateException {
        try {
            if (!mService.setEnabled(packageName, enable, user.getIdentifier())) {
                throw new IllegalStateException("setEnabled failed");
            }
        } catch (SecurityException e) {
            rethrowSecurityException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns information about the overlay with the given package name for
     * the specified user.
     *
     * @param packageName The name of the package.
     * @param userHandle The user to get the OverlayInfos for.
     * @return An OverlayInfo object; if no overlays exist with the
     *         requested package name, null is returned.
     *
     * @hide
     */
    @SystemApi
    @Nullable
    public OverlayInfo getOverlayInfo(@NonNull final String packageName,
            @NonNull final UserHandle userHandle) {
        try {
            return mService.getOverlayInfo(packageName, userHandle.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns information about the overlay represented by the identifier for the specified user.
     *
     * @param overlay the identifier representing the overlay
     * @param userHandle the user of which to get overlay state info
     * @return the overlay info or null if the overlay cannot be found
     *
     * @hide
     */
    @Nullable
    public OverlayInfo getOverlayInfo(@NonNull final OverlayIdentifier overlay,
            @NonNull final UserHandle userHandle) {
        try {
            return mService.getOverlayInfoByIdentifier(overlay, userHandle.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns information about all overlays for the given target package for
     * the specified user. The returned list is ordered according to the
     * overlay priority with the highest priority at the end of the list.
     *
     * @param targetPackageName The name of the target package.
     * @param user The user to get the OverlayInfos for.
     * @return A list of OverlayInfo objects; if no overlays exist for the
     *         requested package, an empty list is returned.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            "android.permission.INTERACT_ACROSS_USERS",
            "android.permission.INTERACT_ACROSS_USERS_FULL"
    })
    @NonNull
    public List<OverlayInfo> getOverlayInfosForTarget(@NonNull final String targetPackageName,
            @NonNull UserHandle user) {
        try {
            return mService.getOverlayInfosForTarget(targetPackageName, user.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Clear part of the overlay manager's internal cache of PackageInfo
     * objects. Only intended for testing.
     *
     * @param targetPackageName The name of the target package.
     * @param user The user to get the OverlayInfos for.
     *
     * @hide
     */
    @RequiresPermission(anyOf = {
            "android.permission.INTERACT_ACROSS_USERS",
    })
    @NonNull
    public void invalidateCachesForOverlay(@NonNull final String targetPackageName,
            @NonNull UserHandle user) {
        try {
            mService.invalidateCachesForOverlay(targetPackageName, user.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Perform a series of requests related to overlay packages. This is an
     * atomic operation: either all requests were performed successfully and
     * the changes were propagated to the rest of the system, or at least one
     * request could not be performed successfully and nothing is changed and
     * nothing is propagated to the rest of the system.
     *
     * @see OverlayManagerTransaction
     *
     * @param transaction the series of overlay related requests to perform
     * @throws Exception if not all the requests could be successfully and
     *         atomically executed
     *
     * @hide
     */
    public void commit(@NonNull final OverlayManagerTransaction transaction) {
        try {
            mService.commit(transaction);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Starting on R, actor enforcement and app visibility changes introduce additional failure
     * cases, but the SecurityException thrown with these checks is unexpected for existing
     * consumers of the API.
     *
     * The only prior case it would be thrown is with a permission failure, but the calling
     * application would be able to verify that themselves, and so they may choose to ignore
     * catching SecurityException when calling these APIs.
     *
     * For R, this no longer holds true, and SecurityExceptions can be thrown for any number of
     * reasons, none of which are exposed to the caller. So for consumers targeting below R,
     * transform these SecurityExceptions into IllegalStateExceptions, which are a little more
     * expected to be thrown by the setEnabled APIs.
     *
     * This will mask the prior permission exception if it applies, but it's assumed that apps
     * wouldn't call the APIs without the permission on prior versions, and so it's safe to ignore.
     */
    private void rethrowSecurityException(SecurityException e) {
        if (!Compatibility.isChangeEnabled(THROW_SECURITY_EXCEPTIONS)) {
            throw new IllegalStateException(e);
        } else {
            throw e;
        }
    }
}
