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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;

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
 * you will be passing that data to this app. Both attributions are not mutually
 * exclusive.
 *
 * <p>For example if you have a feature "foo" in your app which accesses
 * permissions on behalf of app "foo.bar.baz" with feature "bar" you need to
 * create a context like this:
 *
 * <pre class="prettyprint">
 * context.createContext(new ContextParams.Builder()
 *     .setAttributionTag("foo")
 *     .setReceiverPackage("foo.bar.baz", "bar")
 *     .build())
 * </pre>
 *
 * @see Context#createContext(ContextParams)
 */
public final class ContextParams {
    private final String mAttributionTag;
    private final String mReceiverPackage;
    private final String mReceiverAttributionTag;
    private final Set<String> mRenouncedPermissions;

    /** {@hide} */
    public static final ContextParams EMPTY = new ContextParams.Builder().build();

    private ContextParams(@NonNull ContextParams.Builder builder) {
        mAttributionTag = builder.mAttributionTag;
        mReceiverPackage = builder.mReceiverPackage;
        mReceiverAttributionTag = builder.mReceiverAttributionTag;
        mRenouncedPermissions = builder.mRenouncedPermissions;
    }

    /**
     * @return The attribution tag.
     */
    @Nullable
    public String getAttributionTag() {
        return mAttributionTag;
    }

    /**
     * @return The receiving package.
     */
    @Nullable
    public String getReceiverPackage() {
        return mReceiverPackage;
    }

    /**
     * @return The receiving package's attribution tag.
     */
    @Nullable
    public String getReceiverAttributionTag() {
        return mReceiverAttributionTag;
    }

    /**
     * @return The set of permissions to treat as renounced.
     * @hide
     */
    @SystemApi
    @SuppressLint("NullableCollection")
    @RequiresPermission(android.Manifest.permission.RENOUNCE_PERMISSIONS)
    public @Nullable Set<String> getRenouncedPermissions() {
        return mRenouncedPermissions;
    }

    /** @hide */
    public boolean isRenouncedPermission(@NonNull String permission) {
        return mRenouncedPermissions != null && mRenouncedPermissions.contains(permission);
    }

    /**
     * Builder for creating a {@link ContextParams}.
     */
    public static final class Builder {
        private String mAttributionTag;
        private String mReceiverPackage;
        private String mReceiverAttributionTag;
        private Set<String> mRenouncedPermissions;

        /**
         * Sets an attribution tag against which to track permission accesses.
         *
         * @param attributionTag The attribution tag.
         * @return This builder.
         */
        @NonNull
        public Builder setAttributionTag(@NonNull String attributionTag) {
            mAttributionTag = Objects.requireNonNull(attributionTag);
            return this;
        }

        /**
         * Sets the package and its optional attribution tag that would be receiving
         * the permission protected data.
         *
         * @param packageName The package name receiving the permission protected data.
         * @param attributionTag An attribution tag of the receiving package.
         * @return This builder.
         */
        @NonNull
        public Builder setReceiverPackage(@NonNull String packageName,
                @Nullable String attributionTag) {
            mReceiverPackage = Objects.requireNonNull(packageName);
            mReceiverAttributionTag = attributionTag;
            return this;
        }

        /**
         * Sets permissions which have been voluntarily "renounced" by the
         * calling app.
         * <p>
         * Interactions performed through the created Context will ideally be
         * treated as if these "renounced" permissions have not actually been
         * granted to the app, regardless of their actual grant status.
         * <p>
         * This is designed for use by separate logical components within an app
         * which have no intention of interacting with data or services that are
         * protected by the renounced permissions.
         * <p>
         * Note that only {@link PermissionInfo#PROTECTION_DANGEROUS}
         * permissions are supported by this mechanism.
         *
         * @param renouncedPermissions The set of permissions to treat as
         *            renounced.
         * @return This builder.
         * @hide
         */
        @SystemApi
        @RequiresPermission(android.Manifest.permission.RENOUNCE_PERMISSIONS)
        public @NonNull Builder setRenouncedPermissions(@NonNull Set<String> renouncedPermissions) {
            mRenouncedPermissions = Collections.unmodifiableSet(renouncedPermissions);
            return this;
        }

        /**
         * Creates a new instance.
         *
         * @return The new instance.
         */
        @NonNull
        public ContextParams build() {
            return new ContextParams(this);
        }
    }
}
