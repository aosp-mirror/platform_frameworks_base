/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.media.permission;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread;
import android.content.Context;
import android.content.PermissionChecker;
import android.os.Binder;
import android.os.Process;

import java.util.Objects;

/**
 * This module provides some utility methods for facilitating our permission enforcement patterns.
 * <p>
 * <h1>Intended usage:</h1>
 * Close to the client-facing edge of the server, first authenticate the client, using {@link
 * #establishIdentityDirect(Identity)}, or {@link #establishIdentityIndirect(Context, String,
 * Identity, Identity)}, depending on whether the client is trying to authenticate as the
 * originator or a middleman. Those methods will establish a scope with the originator in the
 * {@link android.media.permission.IdentityContext} and a cleared binder calling identity.
 * Typically there would be two distinct API methods for the two different options, and typically
 * those API methods would be used to establish a client session which is associated with the
 * originator for the lifetime of the session.
 * <p>
 * When performing an operation that requires permissions, use {@link
 * #checkPermissionForPreflight(Context, Identity, String)} or {@link
 * #checkPermissionForDataDelivery(Context, Identity, String, String)} on the originator
 * identity. Note that this won't typically be the identity pulled from the {@link
 * android.media.permission.IdentityContext}, since we are working with a session-based approach,
 * the originator identity will be established once upon creation of a session, and then all
 * interactions with this session will using the identity attached to the session. This also covers
 * performing checks prior to invoking client callbacks for data delivery.
 *
 * @hide
 */
public class PermissionUtil {

    /**
     * Authenticate an originator, where the binder call is coming from a middleman.
     *
     * The middleman is expected to hold a special permission to act as such, or else a
     * {@link SecurityException} will be thrown. If the call succeeds:
     * <ul>
     *     <li>The passed middlemanIdentity argument will have its uid/pid fields overridden with
     *     those provided by binder.
     *     <li>An {@link SafeCloseable} is returned, used to established a scope in which the
     *     originator identity is available via {@link android.media.permission.IdentityContext}
     *     and in which the binder
     *     calling ID is cleared.
     * </ul>
     * Example usage:
     * <pre>
     *     try (SafeCloseable ignored = PermissionUtil.establishIdentityIndirect(...)) {
     *         // Within this scope we have the identity context established, and the binder calling
     *         // identity cleared.
     *         ...
     *         Identity originator = IdentityContext.getNonNull();
     *         ...
     *     }
     *     // outside the scope, everything is back to the prior state.
     * </pre>
     * <p>
     * <b>Important note:</b> The binder calling ID will be used to securely establish the identity
     * of the middleman. However, if the middleman is on the same process as the server,
     * the middleman must remember to clear the binder calling identity, or else the binder calling
     * ID will reflect the process calling into the middleman, not the middleman process itself. If
     * the middleman itself is using this API, this is typically not an issue, since this method
     * will take care of that.
     *
     * @param context             A {@link Context}, used for permission checks.
     * @param middlemanPermission The permission that will be checked in order to authorize the
     *                            middleman to act as such (i.e. be trusted to convey the
     *                            originator
     *                            identity reliably).
     * @param middlemanIdentity   The identity of the middleman.
     * @param originatorIdentity  The identity of the originator.
     * @return A {@link SafeCloseable}, used to establish a scope, as mentioned above.
     */
    public static @NonNull
    SafeCloseable establishIdentityIndirect(
            @NonNull Context context,
            @NonNull String middlemanPermission,
            @NonNull Identity middlemanIdentity,
            @NonNull Identity originatorIdentity) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(middlemanPermission);
        Objects.requireNonNull(middlemanIdentity);
        Objects.requireNonNull(originatorIdentity);

        // Override uid/pid with the secure values provided by binder.
        middlemanIdentity.pid = Binder.getCallingPid();
        middlemanIdentity.uid = Binder.getCallingUid();

        // Authorize middleman to delegate identity.
        context.enforcePermission(middlemanPermission, middlemanIdentity.pid,
                middlemanIdentity.uid,
                String.format("Middleman must have the %s permision.", middlemanPermission));
        return new CompositeSafeCloseable(IdentityContext.create(originatorIdentity),
                ClearCallingIdentityContext.create());
    }

    /**
     * Authenticate an originator, where the binder call is coming directly from the originator.
     *
     * If the call succeeds:
     * <ul>
     *     <li>The passed originatorIdentity argument will have its uid/pid fields overridden with
     *     those provided by binder.
     *     <li>A {@link SafeCloseable} is returned, used to established a scope in which the
     *     originator identity is available via {@link IdentityContext} and in which the binder
     *     calling ID is cleared.
     * </ul>
     * Example usage:
     * <pre>
     *     try (AutoClosable ignored = PermissionUtil.establishIdentityDirect(...)) {
     *         // Within this scope we have the identity context established, and the binder calling
     *         // identity cleared.
     *         ...
     *         Identity originator = IdentityContext.getNonNull();
     *         ...
     *     }
     *     // outside the scope, everything is back to the prior state.
     * </pre>
     * <p>
     * <b>Important note:</b> The binder calling ID will be used to securely establish the identity
     * of the client. However, if the client is on the same process as the server, and is itself a
     * binder server, it must remember to clear the binder calling identity, or else the binder
     * calling ID will reflect the process calling into the client, not the client process itself.
     * If the client itself is using this API, this is typically not an issue, since this method
     * will take care of that.
     *
     * @param originatorIdentity The identity of the originator.
     * @return A {@link SafeCloseable}, used to establish a scope, as mentioned above.
     */
    public static @NonNull
    SafeCloseable establishIdentityDirect(@NonNull Identity originatorIdentity) {
        Objects.requireNonNull(originatorIdentity);

        originatorIdentity.uid = Binder.getCallingUid();
        originatorIdentity.pid = Binder.getCallingPid();
        return new CompositeSafeCloseable(
                IdentityContext.create(originatorIdentity),
                ClearCallingIdentityContext.create());
    }

    /**
     * Checks whether the given identity has the given permission to receive data.
     *
     * @param context    A {@link Context}, used for permission checks.
     * @param identity   The identity to check.
     * @param permission The identifier of the permission we want to check.
     * @param reason     The reason why we're requesting the permission, for auditing purposes.
     * @return The permission check result which is either
     * {@link PermissionChecker#PERMISSION_GRANTED}
     * or {@link PermissionChecker#PERMISSION_SOFT_DENIED} or
     * {@link PermissionChecker#PERMISSION_HARD_DENIED}.
     */
    public static int checkPermissionForDataDelivery(@NonNull Context context,
            @NonNull Identity identity,
            @NonNull String permission,
            @NonNull String reason) {
        return PermissionChecker.checkPermissionForDataDelivery(context, permission,
                identity.pid, identity.uid, identity.packageName, identity.attributionTag,
                reason);
    }

    /**
     * Checks whether the given identity has the given permission.
     *
     * @param context    A {@link Context}, used for permission checks.
     * @param identity   The identity to check.
     * @param permission The identifier of the permission we want to check.
     * @return The permission check result which is either
     * {@link PermissionChecker#PERMISSION_GRANTED}
     * or {@link PermissionChecker#PERMISSION_SOFT_DENIED} or
     * {@link PermissionChecker#PERMISSION_HARD_DENIED}.
     */
    public static int checkPermissionForPreflight(@NonNull Context context,
            @NonNull Identity identity,
            @NonNull String permission) {
        return PermissionChecker.checkPermissionForPreflight(context, permission,
                identity.pid, identity.uid, identity.packageName);
    }
}
