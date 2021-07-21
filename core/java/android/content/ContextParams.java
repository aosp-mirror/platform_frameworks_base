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

package android.content;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.app.ActivityThread;
import android.content.pm.PackageManager;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * This class represents rules around how a context being created via
 * {@link Context#createContext} should behave.
 *
 * <p>One of the dimensions to customize is how permissions should behave.
 * For example, you can specify how permission accesses from a context should
 * be attributed in the platform's permission tracking system.
 *
 * <p>The two main types of attribution are: against an attribution tag which
 * is an arbitrary string your app specifies for the purposes of tracking permission
 * accesses from a given portion of your app; against another package and optionally
 * its attribution tag if you are accessing the data on behalf of another app and
 * you will be passing that data to this app, recursively. Both attributions are
 * not mutually exclusive.
 *
 * @see Context#createContext(ContextParams)
 * @see AttributionSource
 */
public final class ContextParams {
    private final @Nullable String mAttributionTag;
    private final @Nullable AttributionSource mNext;
    private final @NonNull Set<String> mRenouncedPermissions;

    /** {@hide} */
    public static final ContextParams EMPTY = new ContextParams.Builder().build();

    private ContextParams(@Nullable String attributionTag,
            @Nullable AttributionSource next,
            @Nullable Set<String> renouncedPermissions) {
        mAttributionTag = attributionTag;
        mNext = next;
        mRenouncedPermissions = (renouncedPermissions != null)
                ? renouncedPermissions : Collections.emptySet();
    }

    /**
     * @return The attribution tag.
     */
    @Nullable
    public String getAttributionTag() {
        return mAttributionTag;
    }

    /**
     * @return The set of permissions to treat as renounced.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.RENOUNCE_PERMISSIONS)
    public @NonNull Set<String> getRenouncedPermissions() {
        return mRenouncedPermissions;
    }

    /** @hide */
    public boolean isRenouncedPermission(@NonNull String permission) {
        return mRenouncedPermissions.contains(permission);
    }

    /**
     * @return The receiving attribution source.
     */
    @Nullable
    public AttributionSource getNextAttributionSource() {
        return mNext;
    }

    /**
     * Builder for creating a {@link ContextParams}.
     */
    public static final class Builder {
        private @Nullable String mAttributionTag;
        private @NonNull Set<String> mRenouncedPermissions = Collections.emptySet();
        private @Nullable AttributionSource mNext;

        /**
         * Create a new builder.
         * <p>
         * This is valuable when you are interested in having explicit control
         * over every sub-parameter, and don't want to inherit any values from
         * an existing Context.
         * <p>
         * Developers should strongly consider using
         * {@link #Builder(ContextParams)} instead of this constructor, since
         * that will will automatically inherit any new sub-parameters added in
         * future platform releases.
         */
        public Builder() {
        }

        /**
         * Create a new builder that inherits all sub-parameters by default.
         * <p>
         * This is valuable when you are only interested in overriding specific
         * sub-parameters, and want to preserve all other parameters. Setting a
         * specific sub-parameter on the returned builder will override any
         * inherited value.
         */
        public Builder(@NonNull ContextParams params) {
            Objects.requireNonNull(params);
            mAttributionTag = params.mAttributionTag;
            mRenouncedPermissions = params.mRenouncedPermissions;
            mNext = params.mNext;
        }

        /**
         * Sets an attribution tag against which to track permission accesses.
         *
         * @param attributionTag The attribution tag.
         * @return This builder.
         */
        @NonNull
        public Builder setAttributionTag(@Nullable String attributionTag) {
            mAttributionTag = attributionTag;
            return this;
        }

        /**
         * Sets the attribution source for the app on whose behalf you are doing the work.
         *
         * @param next The permission identity of the receiving app.
         * @return This builder.
         *
         * @see AttributionSource
         */
        @NonNull
        public Builder setNextAttributionSource(@Nullable AttributionSource next) {
            mNext = next;
            return this;
        }

        /**
         * Sets permissions which have been voluntarily "renounced" by the
         * calling app.
         * <p>
         * Interactions performed through services obtained from the created
         * Context will ideally be treated as if these "renounced" permissions
         * have not actually been granted to the app, regardless of their actual
         * grant status.
         * <p>
         * This is designed for use by separate logical components within an app
         * which have no intention of interacting with data or services that are
         * protected by the renounced permissions.
         * <p>
         * Note that only {@link PermissionInfo#PROTECTION_DANGEROUS}
         * permissions are supported by this mechanism. Additionally, this
         * mechanism only applies to calls made through services obtained via
         * {@link Context#getSystemService}; it has no effect on static or raw
         * Binder calls.
         *
         * @param renouncedPermissions The set of permissions to treat as
         *            renounced, which is as if not granted.
         * @return This builder.
         * @hide
         */
        @SystemApi
        @RequiresPermission(android.Manifest.permission.RENOUNCE_PERMISSIONS)
        public @NonNull Builder setRenouncedPermissions(
                @Nullable Set<String> renouncedPermissions) {
            // This is not a security check but a fail fast - the OS enforces the permission too
            if (renouncedPermissions != null && !renouncedPermissions.isEmpty()
                    && ActivityThread.currentApplication().checkSelfPermission(Manifest.permission
                    .RENOUNCE_PERMISSIONS) != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Renouncing permissions requires: "
                        + Manifest.permission.RENOUNCE_PERMISSIONS);
            }
            mRenouncedPermissions = renouncedPermissions;
            return this;
        }

        /**
         * Creates a new instance.
         *
         * @return The new instance.
         */
        @NonNull
        public ContextParams build() {
            return new ContextParams(mAttributionTag, mNext,
                    mRenouncedPermissions);
        }
    }
}
