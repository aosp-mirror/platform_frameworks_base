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

package android.content.om;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.FabricatedOverlayInternal;
import android.os.FabricatedOverlayInternalEntry;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.TypedValue;

import com.android.internal.content.om.OverlayManagerImpl;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Objects;

/**
 * Fabricated Runtime Resource Overlays (FRROs) are overlays generated ar runtime.
 *
 * Fabricated overlays are enabled, disabled, and reordered just like normal overlays. The
 * overlayable policies a fabricated overlay fulfills are the same policies the creator of the
 * overlay fulfill. For example, a fabricated overlay created by a platform signed package on the
 * system partition would fulfil the {@code system} and {@code signature} policies.
 *
 * The owner of a fabricated overlay is the UID that created it. Overlays commit to the overlay
 * manager persist across reboots. When the UID is uninstalled, its fabricated overlays are wiped.
 *
 * Processes with {@link Android.Manifest.permission.CHANGE_OVERLAY_PACKAGES} can manage normal
 * overlays and fabricated overlays.
 * @hide
 */
public class FabricatedOverlay {

    /** Retrieves the identifier for this fabricated overlay. */
    public OverlayIdentifier getIdentifier() {
        return new OverlayIdentifier(
                mOverlay.packageName, TextUtils.nullIfEmpty(mOverlay.overlayName));
    }

    public static class Builder {
        private final String mOwningPackage;
        private final String mName;
        private final String mTargetPackage;
        private String mTargetOverlayable = "";
        private final ArrayList<FabricatedOverlayInternalEntry> mEntries = new ArrayList<>();

        /**
         * Constructs a build for a fabricated overlay.
         *
         * @param owningPackage the name of the package that owns the fabricated overlay (must
         *                      be a package name of this UID).
         * @param name a name used to uniquely identify the fabricated overlay owned by
         *             {@param owningPackageName}
         * @param targetPackage the name of the package to overlay
         */
        public Builder(@NonNull String owningPackage, @NonNull String name,
                @NonNull String targetPackage) {
            Preconditions.checkStringNotEmpty(owningPackage,
                    "'owningPackage' must not be empty nor null");
            Preconditions.checkStringNotEmpty(name,
                    "'name'' must not be empty nor null");
            Preconditions.checkStringNotEmpty(targetPackage,
                    "'targetPackage' must not be empty nor null");

            mOwningPackage = owningPackage;
            mName = name;
            mTargetPackage = targetPackage;
        }

        /**
         * Constructs a builder for building a fabricated overlay.
         *
         * @param name a name used to uniquely identify the fabricated overlay owned by the caller
         *             itself.
         * @param targetPackage the name of the package to overlay
         */
        public Builder(@NonNull String name, @NonNull String targetPackage) {
            mName = OverlayManagerImpl.checkOverlayNameValid(name);
            mTargetPackage =
                    Preconditions.checkStringNotEmpty(
                            targetPackage, "'targetPackage' must not be empty nor null");
            mOwningPackage = ""; // The package name is filled in OverlayManager.commit
        }

        /**
         * Sets the name of the overlayable resources to overlay (can be null).
         */
        @NonNull
        public Builder setTargetOverlayable(@Nullable String targetOverlayable) {
            mTargetOverlayable = TextUtils.emptyIfNull(targetOverlayable);
            return this;
        }

        /**
         * Ensure the resource name is in the form [package]:type/entry.
         *
         * @param name name of the target resource to overlay (in the form [package]:type/entry)
         * @return the valid name
         */
        private static String ensureValidResourceName(@NonNull String name) {
            Objects.requireNonNull(name);
            final int slashIndex = name.indexOf('/'); /* must contain '/' */
            final int colonIndex = name.indexOf(':'); /* ':' should before '/' if ':' exist */

            // The minimum length of resource type is "id".
            Preconditions.checkArgument(
                    slashIndex >= 0 /* It must contain the type name */
                    && colonIndex != 0 /* 0 means the package name is empty */
                    && (slashIndex - colonIndex) > 2 /* The shortest length of type is "id" */,
                    "\"%s\" is invalid resource name",
                    name);
            return name;
        }

        /**
         * Sets the value of the fabricated overlay for the integer-like types.
         *
         * @param resourceName name of the target resource to overlay (in the form
         *     [package]:type/entry)
         * @param dataType the data type of the new value
         * @param value the unsigned 32 bit integer representing the new value
         * @return the builder itself
         * @see #setResourceValue(String, int, int, String)
         * @see android.util.TypedValue#type
         */
        @NonNull
        public Builder setResourceValue(
                @NonNull String resourceName,
                @IntRange(from = TypedValue.TYPE_FIRST_INT, to = TypedValue.TYPE_LAST_INT)
                        int dataType,
                int value) {
            return setResourceValue(resourceName, dataType, value, null /* configuration */);
        }

        /**
         * Sets the value of the fabricated overlay for the integer-like types with the
         * configuration.
         *
         * @param resourceName name of the target resource to overlay (in the form
         *     [package]:type/entry)
         * @param dataType the data type of the new value
         * @param value the unsigned 32 bit integer representing the new value
         * @param configuration The string representation of the config this overlay is enabled for
         * @see android.util.TypedValue#type
         */
        @NonNull
        public Builder setResourceValue(
                @NonNull String resourceName,
                @IntRange(from = TypedValue.TYPE_FIRST_INT, to = TypedValue.TYPE_LAST_INT)
                        int dataType,
                int value,
                @Nullable String configuration) {
            ensureValidResourceName(resourceName);

            final FabricatedOverlayInternalEntry entry = new FabricatedOverlayInternalEntry();
            entry.resourceName = resourceName;
            entry.dataType =
                    Preconditions.checkArgumentInRange(
                            dataType,
                            TypedValue.TYPE_FIRST_INT,
                            TypedValue.TYPE_LAST_INT,
                            "dataType");
            entry.data = value;
            entry.configuration = configuration;
            mEntries.add(entry);
            return this;
        }

        /** @hide */
        @IntDef(
                prefix = {"OVERLAY_TYPE"},
                value = {
                    TypedValue.TYPE_STRING,
                })
        @Retention(RetentionPolicy.SOURCE)
        public @interface StringTypeOverlayResource {}

        /**
         * Sets the value of the fabricated overlay for the string-like type.
         *
         * @param resourceName name of the target resource to overlay (in the form
         *     [package]:type/entry)
         * @param dataType the data type of the new value
         * @param value the string representing the new value
         * @return the builder itself
         * @see android.util.TypedValue#type
         */
        @NonNull
        public Builder setResourceValue(
                @NonNull String resourceName,
                @StringTypeOverlayResource int dataType,
                @NonNull String value) {
            return setResourceValue(resourceName, dataType, value, null /* configuration */);
        }

        /**
         * Sets the value of the fabricated overlay for the string-like type with the configuration.
         *
         * @param resourceName name of the target resource to overlay (in the form
         *     [package]:type/entry)
         * @param dataType the data type of the new value
         * @param value the string representing the new value
         * @param configuration The string representation of the config this overlay is enabled for
         * @see android.util.TypedValue#type
         */
        @NonNull
        public Builder setResourceValue(
                @NonNull String resourceName,
                @StringTypeOverlayResource int dataType,
                @NonNull String value,
                @Nullable String configuration) {
            ensureValidResourceName(resourceName);

            final FabricatedOverlayInternalEntry entry = new FabricatedOverlayInternalEntry();
            entry.resourceName = resourceName;
            entry.dataType =
                    Preconditions.checkArgumentInRange(
                            dataType, TypedValue.TYPE_STRING, TypedValue.TYPE_FRACTION, "dataType");
            entry.stringData = Objects.requireNonNull(value);
            entry.configuration = configuration;
            mEntries.add(entry);
            return this;
        }

        /**
         * Sets the value of the fabricated overlay
         *
         * @param resourceName name of the target resource to overlay (in the form
         *     [package]:type/entry)
         * @param value the file descriptor whose contents are the value of the frro
         * @param configuration The string representation of the config this overlay is enabled for
         * @return the builder itself
         */
        @NonNull
        public Builder setResourceValue(
                @NonNull String resourceName,
                @NonNull ParcelFileDescriptor value,
                @Nullable String configuration) {
            ensureValidResourceName(resourceName);

            final FabricatedOverlayInternalEntry entry = new FabricatedOverlayInternalEntry();
            entry.resourceName = resourceName;
            entry.binaryData = Objects.requireNonNull(value);
            entry.configuration = configuration;
            mEntries.add(entry);
            return this;
        }

        /**
         * Builds an immutable fabricated overlay.
         *
         * @return the fabricated overlay
         */
        @NonNull
        public FabricatedOverlay build() {
            final FabricatedOverlayInternal overlay = new FabricatedOverlayInternal();
            overlay.packageName = mOwningPackage;
            overlay.overlayName = mName;
            overlay.targetPackageName = mTargetPackage;
            overlay.targetOverlayable = mTargetOverlayable;
            overlay.entries = new ArrayList<>();
            overlay.entries.addAll(mEntries);
            return new FabricatedOverlay(overlay);
        }
    }

    final FabricatedOverlayInternal mOverlay;
    private FabricatedOverlay(FabricatedOverlayInternal overlay) {
        mOverlay = overlay;
    }
}
