/*
 * Copyright (C) 2014 The Android Open Source Project
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
package android.media.routing;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.media.routing.MediaRouter.RouteFeatures;
import android.os.Bundle;
import android.os.IInterface;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * A media route selector consists of a set of constraints that are used to select
 * the routes to which an application would like to connect.  The constraints consist
 * of a set of required or optional features and protocols.  The constraints may also
 * require the use of a specific media route service package or additional characteristics
 * that are described by a bundle of extra parameters.
 * <p>
 * The application will typically create several different selectors that express
 * various combinations of characteristics that it would like to use together when
 * it connects to a destination media device.  For each destination that is discovered,
 * media route services will publish some number of routes and include information
 * about which selector each route matches.  The application will then choose among
 * these routes to determine which best satisfies its desired purpose and connect to it.
 * </p>
 */
public final class MediaRouteSelector implements Parcelable {
    private final int mRequiredFeatures;
    private final int mOptionalFeatures;
    private final List<String> mRequiredProtocols;
    private final List<String> mOptionalProtocols;
    private final String mServicePackageName;
    private final Bundle mExtras;

    MediaRouteSelector(int requiredFeatures, int optionalFeatures,
            List<String> requiredProtocols, List<String> optionalProtocols,
            String servicePackageName, Bundle extras) {
        mRequiredFeatures = requiredFeatures;
        mOptionalFeatures = optionalFeatures;
        mRequiredProtocols = requiredProtocols;
        mOptionalProtocols = optionalProtocols;
        mServicePackageName = servicePackageName;
        mExtras = extras;
    }

    /**
     * Gets the set of required route features.
     *
     * @return A set of required route feature flags.
     */
    public @RouteFeatures int getRequiredFeatures() {
        return mRequiredFeatures;
    }

    /**
     * Gets the set of optional route features.
     *
     * @return A set of optional route feature flags.
     */
    public @RouteFeatures int getOptionalFeatures() {
        return mOptionalFeatures;
    }

    /**
     * Gets the list of route protocols that a route must support in order to be selected.
     * <p>
     * Refer to <code>android.support.media.protocols.MediaRouteProtocol</code>
     * for more information.
     * </p>
     *
     * @return The list of fully qualified route protocol names.
     */
    public @NonNull List<String> getRequiredProtocols() {
        return mRequiredProtocols;
    }

    /**
     * Gets the list of optional route protocols that a client may use if they are available.
     * <p>
     * Refer to <code>android.support.media.protocols.MediaRouteProtocol</code>
     * for more information.
     * </p>
     *
     * @return The list of optional fully qualified route protocol names.
     */
    public @NonNull List<String> getOptionalProtocols() {
        return mOptionalProtocols;
    }

    /**
     * Returns true if the selector includes a required or optional request for
     * the specified protocol using its fully qualified class name.
     * <p>
     * Refer to <code>android.support.media.protocols.MediaRouteProtocol</code>
     * for more information.
     * </p>
     *
     * @param clazz The protocol class.
     * @return True if the protocol was requested.
     */
    public boolean containsProtocol(@NonNull Class<?> clazz) {
        return containsProtocol(clazz.getName());
    }

    /**
     * Returns true if the selector includes a required or optional request for
     * the specified protocol.
     * <p>
     * Refer to <code>android.support.media.protocols.MediaRouteProtocol</code>
     * for more information.
     * </p>
     *
     * @param name The name of the protocol.
     * @return True if the protocol was requested.
     */
    public boolean containsProtocol(@NonNull String name) {
        return mRequiredProtocols.contains(name)
                || mOptionalProtocols.contains(name);
    }

    /**
     * Gets the package name of a specific media route service that this route selector
     * requires.
     *
     * @return The required media route service package name, or null if none.
     */
    public @Nullable String getServicePackageName() {
        return mServicePackageName;
    }

    /**
     * Gets optional extras that may be used to select or configure routes for a
     * particular purpose.  Some extras may be used by media route services to apply
     * additional constraints or parameters for the routes to be discovered.
     *
     * @return The optional extras, or null if none.
     */
    public @Nullable Bundle getExtras() {
        return mExtras;
    }

    @Override
    public String toString() {
        return "MediaRouteSelector{ "
                + ", requiredFeatures=0x" + Integer.toHexString(mRequiredFeatures)
                + ", optionalFeatures=0x" + Integer.toHexString(mOptionalFeatures)
                + ", requiredProtocols=" + mRequiredProtocols
                + ", optionalProtocols=" + mOptionalProtocols
                + ", servicePackageName=" + mServicePackageName
                + ", extras=" + mExtras + " }";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mRequiredFeatures);
        dest.writeInt(mOptionalFeatures);
        dest.writeStringList(mRequiredProtocols);
        dest.writeStringList(mOptionalProtocols);
        dest.writeString(mServicePackageName);
        dest.writeBundle(mExtras);
    }

    public static final Parcelable.Creator<MediaRouteSelector> CREATOR =
            new Parcelable.Creator<MediaRouteSelector>() {
        @Override
        public MediaRouteSelector createFromParcel(Parcel source) {
            int requiredFeatures = source.readInt();
            int optionalFeatures = source.readInt();
            ArrayList<String> requiredProtocols = new ArrayList<String>();
            ArrayList<String> optionalProtocols = new ArrayList<String>();
            source.readStringList(requiredProtocols);
            source.readStringList(optionalProtocols);
            return new MediaRouteSelector(requiredFeatures, optionalFeatures,
                    requiredProtocols, optionalProtocols,
                    source.readString(), source.readBundle());
        }

        @Override
        public MediaRouteSelector[] newArray(int size) {
            return new MediaRouteSelector[size];
        }
    };

    /**
     * Builder for {@link MediaRouteSelector} objects.
     */
    public static final class Builder {
        private int mRequiredFeatures;
        private int mOptionalFeatures;
        private final ArrayList<String> mRequiredProtocols = new ArrayList<String>();
        private final ArrayList<String> mOptionalProtocols = new ArrayList<String>();
        private String mServicePackageName;
        private Bundle mExtras;

        /**
         * Creates an initially empty selector builder.
         */
        public Builder() {
        }

        /**
         * Sets the set of required route features.
         *
         * @param features A set of required route feature flags.
         */
        public @NonNull Builder setRequiredFeatures(@RouteFeatures int features) {
            mRequiredFeatures = features;
            return this;
        }

        /**
         * Sets the set of optional route features.
         *
         * @param features A set of optional route feature flags.
         */
        public @NonNull Builder setOptionalFeatures(@RouteFeatures int features) {
            mOptionalFeatures = features;
            return this;
        }

        /**
         * Adds a route protocol that a route must support in order to be selected
         * using its fully qualified class name.
         * <p>
         * Refer to <code>android.support.media.protocols.MediaRouteProtocol</code>
         * for more information.
         * </p>
         *
         * @param clazz The protocol class.
         * @return this
         */
        public @NonNull Builder addRequiredProtocol(@NonNull Class<?> clazz) {
            if (clazz == null) {
                throw new IllegalArgumentException("clazz must not be null");
            }
            return addRequiredProtocol(clazz.getName());
        }

        /**
         * Adds a route protocol that a route must support in order to be selected.
         * <p>
         * Refer to <code>android.support.media.protocols.MediaRouteProtocol</code>
         * for more information.
         * </p>
         *
         * @param name The fully qualified name of the required protocol.
         * @return this
         */
        public @NonNull Builder addRequiredProtocol(@NonNull String name) {
            if (TextUtils.isEmpty(name)) {
                throw new IllegalArgumentException("name must not be null or empty");
            }
            mRequiredProtocols.add(name);
            return this;
        }

        /**
         * Adds an optional route protocol that a client may use if available
         * using its fully qualified class name.
         * <p>
         * Refer to <code>android.support.media.protocols.MediaRouteProtocol</code>
         * for more information.
         * </p>
         *
         * @param clazz The protocol class.
         * @return this
         */
        public @NonNull Builder addOptionalProtocol(@NonNull Class<?> clazz) {
            if (clazz == null) {
                throw new IllegalArgumentException("clazz must not be null");
            }
            return addOptionalProtocol(clazz.getName());
        }

        /**
         * Adds an optional route protocol that a client may use if available.
         * <p>
         * Refer to <code>android.support.media.protocols.MediaRouteProtocol</code>
         * for more information.
         * </p>
         *
         * @param name The fully qualified name of the optional protocol.
         * @return this
         */
        public @NonNull Builder addOptionalProtocol(@NonNull String name) {
            if (TextUtils.isEmpty(name)) {
                throw new IllegalArgumentException("name must not be null or empty");
            }
            mOptionalProtocols.add(name);
            return this;
        }

        /**
         * Sets the package name of the media route service to which this selector
         * appertains.
         * <p>
         * If a package name is specified here then this selector will only be
         * passed to media route services from that package.  This has the effect
         * of restricting the set of matching routes to just those that are offered
         * by that package.
         * </p>
         *
         * @param packageName The required service package name, or null if none.
         * @return this
         */
        public @NonNull Builder setServicePackageName(@Nullable String packageName) {
            mServicePackageName = packageName;
            return this;
        }

        /**
         * Sets optional extras that may be used to select or configure routes for a
         * particular purpose.  Some extras may be used by route services to specify
         * additional constraints or parameters for the routes to be discovered.
         *
         * @param extras The optional extras, or null if none.
         * @return this
         */
        public @NonNull Builder setExtras(@Nullable Bundle extras) {
            mExtras = extras;
            return this;
        }

        /**
         * Builds the {@link MediaRouteSelector} object.
         *
         * @return The new media route selector instance.
         */
        public @NonNull MediaRouteSelector build() {
            return new MediaRouteSelector(mRequiredFeatures, mOptionalFeatures,
                    mRequiredProtocols, mOptionalProtocols, mServicePackageName, mExtras);
        }
    }
}
