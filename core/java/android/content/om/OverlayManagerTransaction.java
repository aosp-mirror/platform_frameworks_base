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

package android.content.om;

import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.NonUiContext;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A container for a batch of requests to the OverlayManager.
 *
 * <p>An app can get an {@link OverlayManagerTransaction} with the specified {@link OverlayManager}
 * to handle the transaction. The app can register multiple overlays and unregister multiple
 * registered overlays in one transaction commitment.
 *
 * <p>The below example is registering a {@code updatingOverlay} and unregistering a {@code
 * deprecatedOverlay} in one transaction commitment.
 *
 * <pre>{@code
 * final OverlayManager overlayManager = ctx.getSystemService(OverlayManager.class);
 * final OverlayManagerTransaction transaction = new OverlayManagerTransaction(overlayManager);
 * transaction.registerFabricatedOverlay(updatingOverlay);
 * transaction.unregisterFabricatedOverlay(deprecatedOverlay)
 * transaction.commit();
 * }</pre>
 *
 * @see OverlayManager
 * @see FabricatedOverlay
 */
public final class OverlayManagerTransaction implements Parcelable {
    // TODO: remove @hide from this class when OverlayManager is added to the
    // SDK, but keep OverlayManagerTransaction.Request @hidden
    private final List<Request> mRequests;
    private final OverlayManager mOverlayManager;

    /**
     * Container for a batch of requests to the OverlayManagerService.
     *
     * <p>Transactions are created using a builder interface. Example usage:
     * <pre>{@code
     * final OverlayManager om = ctx.getSystemService(OverlayManager.class);
     * final OverlayManagerTransaction t = new OverlayManagerTransaction.Builder()
     *     .setEnabled(...)
     *     .setEnabled(...)
     *     .build();
     * om.commit(t);
     * }</pre>
     */
    private OverlayManagerTransaction(
            @NonNull final List<Request> requests, @Nullable OverlayManager overlayManager) {
        Objects.requireNonNull(requests);
        if (requests.contains(null)) {
            throw new IllegalArgumentException("null request");
        }
        mRequests = requests;
        mOverlayManager = overlayManager;
    }

    /**
     * Get an overlay manager transaction with the specified handler.
     * @param overlayManager handles this transaction.
     */
    public OverlayManagerTransaction(@NonNull OverlayManager overlayManager) {
        this(new ArrayList<>(), Objects.requireNonNull(overlayManager));
    }

    private OverlayManagerTransaction(@NonNull final Parcel source) {
        final int size = source.readInt();
        mRequests = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            final int request = source.readInt();
            final OverlayIdentifier overlay = source.readParcelable(null, android.content.om.OverlayIdentifier.class);
            final int userId = source.readInt();
            final Bundle extras = source.readBundle(null);
            mRequests.add(new Request(request, overlay, userId, extras));
        }
        mOverlayManager = null;
    }

    /**
     * Get the iterator of requests
     *
     * @return the iterator of request
     * @hide
     */
    @SuppressLint("ReferencesHidden")
    @NonNull
    public Iterator<Request> getRequests() {
        return mRequests.iterator();
    }

    /**
     * {@inheritDoc}
     *
     * @hide
     */
    @Override
    public String toString() {
        return String.format("OverlayManagerTransaction { mRequests = %s }", mRequests);
    }

    /**
     * A single unit of the transaction, such as a request to enable an
     * overlay, or to disable an overlay.
     *
     * @hide
     */
    public static final class Request {
        @IntDef(prefix = "TYPE_", value = {
                TYPE_SET_ENABLED,
                TYPE_SET_DISABLED,
        })
        @Retention(RetentionPolicy.SOURCE)
        @interface RequestType {}

        public static final int TYPE_SET_ENABLED = 0;
        public static final int TYPE_SET_DISABLED = 1;
        public static final int TYPE_REGISTER_FABRICATED = 2;
        public static final int TYPE_UNREGISTER_FABRICATED = 3;

        public static final String BUNDLE_FABRICATED_OVERLAY = "fabricated_overlay";

        @RequestType
        public final int type;
        @NonNull
        public final OverlayIdentifier overlay;
        public final int userId;

        @SuppressLint("NullableCollection")
        @Nullable
        public final Bundle extras;

        public Request(@RequestType final int type, @NonNull final OverlayIdentifier overlay,
                final int userId) {
            this(type, overlay, userId, null /* extras */);
        }

        public Request(@RequestType final int type, @NonNull final OverlayIdentifier overlay,
                final int userId, @Nullable Bundle extras) {
            this.type = type;
            this.overlay = overlay;
            this.userId = userId;
            this.extras = extras;
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "Request{type=0x%02x (%s), overlay=%s, userId=%d}",
                    type, typeToString(), overlay, userId);
        }

        /**
         * Translate the request type into a human readable string. Only
         * intended for debugging.
         *
         * @hide
         */
        public String typeToString() {
            switch (type) {
                case TYPE_SET_ENABLED: return "TYPE_SET_ENABLED";
                case TYPE_SET_DISABLED: return "TYPE_SET_DISABLED";
                case TYPE_REGISTER_FABRICATED: return "TYPE_REGISTER_FABRICATED";
                case TYPE_UNREGISTER_FABRICATED: return "TYPE_UNREGISTER_FABRICATED";
                default: return String.format("TYPE_UNKNOWN (0x%02x)", type);
            }
        }
    }

    /**
     * Builder class for OverlayManagerTransaction objects.
     *
     * @hide
     */
    public static final class Builder {
        private final List<Request> mRequests = new ArrayList<>();

        /**
         * Request that an overlay package be enabled and change its loading
         * order to the last package to be loaded, or disabled
         *
         * If the caller has the correct permissions, it is always possible to
         * disable an overlay. Due to technical and security reasons it may not
         * always be possible to enable an overlay, for instance if the overlay
         * does not successfully overlay any target resources due to
         * overlayable policy restrictions.
         *
         * An enabled overlay is a part of target package's resources, i.e. it will
         * be part of any lookups performed via {@link android.content.res.Resources}
         * and {@link android.content.res.AssetManager}. A disabled overlay will no
         * longer affect the resources of the target package. If the target is
         * currently running, its outdated resources will be replaced by new ones.
         *
         * @param overlay The name of the overlay package.
         * @param enable true to enable the overlay, false to disable it.
         * @return this Builder object, so you can chain additional requests
         */
        public Builder setEnabled(@NonNull OverlayIdentifier overlay, boolean enable) {
            return setEnabled(overlay, enable, UserHandle.myUserId());
        }

        /**
         * @hide
         */
        public Builder setEnabled(@NonNull OverlayIdentifier overlay, boolean enable, int userId) {
            checkNotNull(overlay);
            @Request.RequestType final int type =
                enable ? Request.TYPE_SET_ENABLED : Request.TYPE_SET_DISABLED;
            mRequests.add(new Request(type, overlay, userId));
            return this;
        }

        /**
         * Registers the fabricated overlay with the overlay manager so it can be enabled and
         * disabled for any user.
         *
         * The fabricated overlay is initialized in a disabled state. If an overlay is re-registered
         * the existing overlay will be replaced by the newly registered overlay and the enabled
         * state of the overlay will be left unchanged if the target package and target overlayable
         * have not changed.
         *
         * @param overlay the overlay to register with the overlay manager
         *
         * @hide
         */
        @NonNull
        public Builder registerFabricatedOverlay(@NonNull FabricatedOverlay overlay) {
            mRequests.add(generateRegisterFabricatedOverlayRequest(overlay));
            return this;
        }

        /**
         * Disables and removes the overlay from the overlay manager for all users.
         *
         * @param overlay the overlay to disable and remove
         *
         * @hide
         */
        @NonNull
        public Builder unregisterFabricatedOverlay(@NonNull OverlayIdentifier overlay) {
            mRequests.add(generateUnRegisterFabricatedOverlayRequest(overlay));
            return this;
        }

        /**
         * Create a new transaction out of the requests added so far. Execute
         * the transaction by calling OverlayManager#commit.
         *
         * @see OverlayManager#commit
         * @return a new transaction
         */
        @NonNull
        public OverlayManagerTransaction build() {
            return new OverlayManagerTransaction(mRequests, null /* overlayManager */);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        final int size = mRequests.size();
        dest.writeInt(size);
        for (int i = 0; i < size; i++) {
            final Request req = mRequests.get(i);
            dest.writeInt(req.type);
            dest.writeParcelable(req.overlay, flags);
            dest.writeInt(req.userId);
            dest.writeBundle(req.extras);
        }
    }

    @NonNull
    public static final Parcelable.Creator<OverlayManagerTransaction> CREATOR =
            new Parcelable.Creator<OverlayManagerTransaction>() {

        @Override
        public OverlayManagerTransaction createFromParcel(Parcel source) {
            return new OverlayManagerTransaction(source);
        }

        @Override
        public OverlayManagerTransaction[] newArray(int size) {
            return new OverlayManagerTransaction[size];
        }
    };

    /**
     * Commit the overlay manager transaction to register or unregister overlays for self-targeting.
     *
     * <p>Applications can register overlays and unregister the registered overlays via {@link
     * OverlayManagerTransaction}.
     *
     * @throws IOException if there is a file operation error.
     * @throws PackageManager.NameNotFoundException if the package name is not found.
     */
    @NonUiContext
    public void commit() throws PackageManager.NameNotFoundException, IOException {
        mOverlayManager.commitSelfTarget(this);
    }

    private static Request generateRegisterFabricatedOverlayRequest(
            @NonNull FabricatedOverlay overlay) {
        Objects.requireNonNull(overlay);

        final Bundle extras = new Bundle();
        extras.putParcelable(Request.BUNDLE_FABRICATED_OVERLAY, overlay.mOverlay);
        return new Request(Request.TYPE_REGISTER_FABRICATED, overlay.getIdentifier(),
                UserHandle.USER_ALL, extras);
    }

    private static Request generateUnRegisterFabricatedOverlayRequest(
            @NonNull OverlayIdentifier overlayIdentifier) {
        Objects.requireNonNull(overlayIdentifier);

        return new Request(Request.TYPE_UNREGISTER_FABRICATED, overlayIdentifier,
                UserHandle.USER_ALL);
    }

    /**
     * Registers the fabricated overlays with the overlay manager so it can be used to overlay
     * the app resources in runtime.
     *
     * <p>If an overlay is re-registered the existing overlay will be replaced by the newly
     * registered overlay. The registered overlay will be left unchanged until the target
     * package or target overlayable is changed.
     *
     * @param overlay the overlay to register with the overlay manager
     */
    @NonNull
    public void registerFabricatedOverlay(@NonNull FabricatedOverlay overlay) {
        mRequests.add(generateRegisterFabricatedOverlayRequest(overlay));
    }

    /**
     * Unregisters the registered overlays from the overlay manager.
     *
     * @param overlay the overlay to be unregistered
     *
     * @see OverlayManager#getOverlayInfosForTarget(String)
     * @see OverlayInfo#getOverlayIdentifier()
     */
    @NonNull
    public void unregisterFabricatedOverlay(@NonNull OverlayIdentifier overlay) {
        mRequests.add(generateUnRegisterFabricatedOverlayRequest(overlay));
    }

    boolean isSelfTargetingTransaction() {
        return mOverlayManager != null;
    }
}
