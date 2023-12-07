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

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.AssetFileDescriptor;
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
 * FabricatedOverlay describes the content of Fabricated Runtime Resource Overlay (FRRO) that is
 * used to overlay the app's resources. The app should register the {@link FabricatedOverlay}
 * instance in an {@link OverlayManagerTransaction} by calling {@link
 * OverlayManagerTransaction#registerFabricatedOverlay(FabricatedOverlay)}. The FRRO is
 * created once the transaction is committed successfully.
 *
 * <p>The app creates a FabricatedOverlay to describe the how to overlay string, integer, and file
 * type resources. Before creating any frro, please define a target overlayable in {@code
 * res/values/overlayable.xml} that describes what kind of resources can be overlaid, what kind of
 * roles or applications can overlay the resources. Here is an example.
 *
 * <pre>{@code
 * <overlayable name="SignatureOverlayable" actor="overlay://theme">
 *     <!-- The app with the same signature can overlay the below resources -->
 *     <policy type="signature">
 *         <item type="color" name="mycolor" />
 *         <item type="string" name="mystring" />
 *     </policy>
 * </overlayable>
 * }</pre>
 *
 * <p>The overlay must assign the target overlayable name just like the above example by calling
 * {@link #setTargetOverlayable(String)}. Here is an example:
 *
 * <pre>{@code
 * FabricatedOverlay fabricatedOverlay = new FabricatedOverlay("overlay_name",
 *                                                             context.getPackageName());
 * fabricatedOverlay.setTargetOverlayable("SignatureOverlayable")
 * fabricatedOverlay.setResourceValue("mycolor", TypedValue.TYPE_INT_COLOR_ARGB8, Color.White)
 * fabricatedOverlay.setResourceValue("mystring", TypedValue.TYPE_STRING, "Hello")
 * }</pre>
 *
 * <p>The app can create any {@link FabricatedOverlay} instance by calling the following APIs.
 *
 * <ul>
 *   <li>{@link #setTargetOverlayable(String)}
 *   <li>{@link #setResourceValue(String, int, int, String)}
 *   <li>{@link #setResourceValue(String, int, String, String)}
 *   <li>{@link #setResourceValue(String, ParcelFileDescriptor, String)}
 * </ul>
 *
 * @see OverlayManager
 * @see OverlayManagerTransaction
 */
public class FabricatedOverlay {

    /**
     * Retrieves the identifier for this fabricated overlay.
     * @return the overlay identifier
     */
    @NonNull
    public OverlayIdentifier getIdentifier() {
        return new OverlayIdentifier(
                mOverlay.packageName, TextUtils.nullIfEmpty(mOverlay.overlayName));
    }

    /**
     * The builder of Fabricated Runtime Resource Overlays(FRROs).
     *
     * Fabricated overlays are enabled, disabled, and reordered just like normal overlays. The
     * overlayable policies a fabricated overlay fulfills are the same policies the creator of the
     * overlay fulfill. For example, a fabricated overlay created by a platform signed package on
     * the system partition would fulfil the {@code system} and {@code signature} policies.
     *
     * The owner of a fabricated overlay is the UID that created it. Overlays commit to the overlay
     * manager persist across reboots. When the UID is uninstalled, its fabricated overlays are
     * wiped.
     *
     * Processes with {@code android.Manifest.permission#CHANGE_OVERLAY_PACKAGES} can manage normal
     * overlays and fabricated overlays.
     *
     * @see FabricatedOverlay
     * @see OverlayManagerTransaction.Builder#registerFabricatedOverlay(FabricatedOverlay)
     * @hide
     */
    public static final class Builder {
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
         * Sets the name of the target overlayable to be overlaid.
         *
         * <p>The target package defines may define several overlayables. The
         * {@link FabricatedOverlay} should specify which overlayable to be overlaid.
         *
         * <p>The target overlayable should be defined in {@code <overlayable>} and pass the value
         * of its {@code name} attribute as the parameter.
         *
         * @param targetOverlayable is a name of the overlayable resources set
         * @hide
         */
        @NonNull
        public Builder setTargetOverlayable(@Nullable String targetOverlayable) {
            mTargetOverlayable = TextUtils.emptyIfNull(targetOverlayable);
            return this;
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
         * @see android.util.TypedValue#TYPE_INT_COLOR_ARGB8 android.util.TypedValue#type
         * @deprecated Framework should use {@link FabricatedOverlay#setResourceValue(String, int,
                       int, String)} instead.
         * @hide
         */
        @Deprecated(since = "Please use FabricatedOverlay#setResourceValue instead")
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
         * @return the builder itself
         * @see android.util.TypedValue#TYPE_INT_COLOR_ARGB8 android.util.TypedValue#type
         * @deprecated Framework should use {@link FabricatedOverlay#setResourceValue(String, int,
                       int, String)} instead.
         * @hide
         */
        @Deprecated(since = "Please use FabricatedOverlay#setResourceValue instead")
        @NonNull
        public Builder setResourceValue(
                @NonNull String resourceName,
                @IntRange(from = TypedValue.TYPE_FIRST_INT, to = TypedValue.TYPE_LAST_INT)
                        int dataType,
                int value,
                @Nullable String configuration) {
            ensureValidResourceName(resourceName);
            mEntries.add(generateFabricatedOverlayInternalEntry(resourceName, dataType, value,
                    configuration));
            return this;
        }

        /**
         * Sets the value of the fabricated overlay for the string-like type.
         *
         * @param resourceName name of the target resource to overlay (in the form
         *     [package]:type/entry)
         * @param dataType the data type of the new value
         * @param value the string representing the new value
         * @return the builder itself
         * @see android.util.TypedValue#TYPE_STRING android.util.TypedValue#type
         * @deprecated Framework should use {@link FabricatedOverlay#setResourceValue(String, int,
                       String, String)} instead.
         * @hide
         */
        @Deprecated(since = "Please use FabricatedOverlay#setResourceValue instead")
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
         * @return the builder itself
         * @see android.util.TypedValue#TYPE_STRING android.util.TypedValue#type
         * @deprecated Framework should use {@link FabricatedOverlay#setResourceValue(String, int,
                       String, String)} instead.
         * @hide
         */
        @Deprecated(since = "Please use FabricatedOverlay#setResourceValue instead")
        @NonNull
        public Builder setResourceValue(
                @NonNull String resourceName,
                @StringTypeOverlayResource int dataType,
                @NonNull String value,
                @Nullable String configuration) {
            ensureValidResourceName(resourceName);
            mEntries.add(generateFabricatedOverlayInternalEntry(resourceName, dataType, value,
                    configuration));
            return this;
        }

        /**
         * Sets the value of the fabricated overlay for the file descriptor type.
         *
         * @param resourceName name of the target resource to overlay (in the form
         *     [package]:type/entry)
         * @param value the file descriptor whose contents are the value of the frro
         * @param configuration The string representation of the config this overlay is enabled for
         * @return the builder itself
         * @deprecated Framework should use {@link FabricatedOverlay#setResourceValue(String,
                ParcelFileDescriptor, String)} instead.
         * @hide
         */
        @Deprecated(since = "Please use FabricatedOverlay#setResourceValue instead")
        @NonNull
        public Builder setResourceValue(
                @NonNull String resourceName,
                @NonNull ParcelFileDescriptor value,
                @Nullable String configuration) {
            ensureValidResourceName(resourceName);
            mEntries.add(generateFabricatedOverlayInternalEntry(
                    resourceName, value, configuration, false));
            return this;
        }

        /**
         * Sets the value of the fabricated overlay for the file descriptor type.
         *
         * @param resourceName name of the target resource to overlay (in the form
         *     [package]:type/entry)
         * @param value the file descriptor whose contents are the value of the frro
         * @param configuration The string representation of the config this overlay is enabled for
         * @return the builder itself
         * @deprecated Framework should use {@link FabricatedOverlay#setResourceValue(String,
                ParcelFileDescriptor, String)} instead.
         * @hide
         */
        @Deprecated(since = "Please use FabricatedOverlay#setResourceValue instead")
        @NonNull
        public Builder setResourceValue(
                @NonNull String resourceName,
                @NonNull AssetFileDescriptor value,
                @Nullable String configuration) {
            ensureValidResourceName(resourceName);
            mEntries.add(
                    generateFabricatedOverlayInternalEntry(resourceName, value, configuration));
            return this;
        }

        /**
         * Builds an immutable fabricated overlay.
         *
         * @return the fabricated overlay
         * @hide
         */
        @NonNull
        public FabricatedOverlay build() {
            return new FabricatedOverlay(
                    generateFabricatedOverlayInternal(mOwningPackage, mName, mTargetPackage,
                            mTargetOverlayable, mEntries));
        }
    }

    private static FabricatedOverlayInternal generateFabricatedOverlayInternal(
            @NonNull String owningPackage, @NonNull String overlayName,
            @NonNull String targetPackageName, @Nullable String targetOverlayable,
            @NonNull ArrayList<FabricatedOverlayInternalEntry> entries) {
        final FabricatedOverlayInternal overlay = new FabricatedOverlayInternal();
        overlay.packageName = owningPackage;
        overlay.overlayName = overlayName;
        overlay.targetPackageName = targetPackageName;
        overlay.targetOverlayable = TextUtils.emptyIfNull(targetOverlayable);
        overlay.entries = new ArrayList<>();
        overlay.entries.addAll(entries);
        return overlay;
    }

    final FabricatedOverlayInternal mOverlay;
    private FabricatedOverlay(FabricatedOverlayInternal overlay) {
        mOverlay = overlay;
    }

    /**
     * Create a fabricated overlay to overlay on the specified package.
     *
     * @param overlayName a name used to uniquely identify the fabricated overlay owned by the
     *                   caller itself.
     * @param targetPackage the name of the package to be overlaid
     */
    public FabricatedOverlay(@NonNull String overlayName, @NonNull String targetPackage) {
        this(generateFabricatedOverlayInternal(
                "" /* owningPackage, The package name is filled commitment */,
                OverlayManagerImpl.checkOverlayNameValid(overlayName),
                Preconditions.checkStringNotEmpty(targetPackage,
                        "'targetPackage' must not be empty nor null"),
                null /* targetOverlayable */,
                new ArrayList<>()));
    }

    /**
     * Set the package that owns the overlay
     *
     * @param owningPackage the package that should own the overlay.
     * @hide
     */
    public void setOwningPackage(@NonNull String owningPackage) {
        mOverlay.packageName = owningPackage;
    }

    /**
     * Set the target overlayable name of the overlay
     *
     * The target package defines may define several overlayables. The {@link FabricatedOverlay}
     * should specify which overlayable to be overlaid.
     *
     * @param targetOverlayable the overlayable name defined in target package.
     */
    public void setTargetOverlayable(@Nullable String targetOverlayable) {
        mOverlay.targetOverlayable = TextUtils.emptyIfNull(targetOverlayable);
    }

    /**
     * Return the target overlayable name of the overlay
     *
     * The target package defines may define several overlayables. The {@link FabricatedOverlay}
     * should specify which overlayable to be overlaid.
     *
     * @return the target overlayable name.
     * @hide
     */
    @Nullable
    public String getTargetOverlayable() {
        return mOverlay.targetOverlayable;
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

    @NonNull
    private static FabricatedOverlayInternalEntry generateFabricatedOverlayInternalEntry(
            @NonNull String resourceName,
            @IntRange(from = TypedValue.TYPE_FIRST_INT, to = TypedValue.TYPE_LAST_INT) int dataType,
            int value, @Nullable String configuration) {
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
        return entry;
    }

    @NonNull
    private static FabricatedOverlayInternalEntry generateFabricatedOverlayInternalEntry(
            @NonNull String resourceName, @StringTypeOverlayResource int dataType,
            @NonNull String value, @Nullable String configuration) {
        final FabricatedOverlayInternalEntry entry = new FabricatedOverlayInternalEntry();
        entry.resourceName = resourceName;
        entry.dataType =
                Preconditions.checkArgumentInRange(
                        dataType, TypedValue.TYPE_STRING, TypedValue.TYPE_FRACTION, "dataType");
        entry.stringData = Objects.requireNonNull(value);
        entry.configuration = configuration;
        return entry;
    }

    @NonNull
    private static FabricatedOverlayInternalEntry generateFabricatedOverlayInternalEntry(
            @NonNull String resourceName, @NonNull ParcelFileDescriptor parcelFileDescriptor,
            @Nullable String configuration, boolean isNinePatch) {
        final FabricatedOverlayInternalEntry entry = new FabricatedOverlayInternalEntry();
        entry.resourceName = resourceName;
        entry.binaryData = Objects.requireNonNull(parcelFileDescriptor);
        entry.configuration = configuration;
        entry.binaryDataOffset = 0;
        entry.binaryDataSize = parcelFileDescriptor.getStatSize();
        entry.isNinePatch = isNinePatch;
        return entry;
    }

    @NonNull
    private static FabricatedOverlayInternalEntry generateFabricatedOverlayInternalEntry(
            @NonNull String resourceName, @NonNull AssetFileDescriptor assetFileDescriptor,
            @Nullable String configuration) {
        final FabricatedOverlayInternalEntry entry = new FabricatedOverlayInternalEntry();
        entry.resourceName = resourceName;
        entry.binaryData = Objects.requireNonNull(assetFileDescriptor.getParcelFileDescriptor());
        entry.binaryDataOffset = assetFileDescriptor.getStartOffset();
        entry.binaryDataSize = assetFileDescriptor.getLength();
        entry.configuration = configuration;
        return entry;
    }

    /**
     * Sets the resource value in the fabricated overlay for the integer-like types with the
     * configuration.
     *
     * @param resourceName name of the target resource to overlay (in the form
     *     [package]:type/entry)
     * @param dataType the data type of the new value
     * @param value the integer representing the new value
     * @param configuration The string representation of the config this overlay is enabled for
     * @see android.util.TypedValue#TYPE_INT_COLOR_ARGB8 android.util.TypedValue#type
     */
    @NonNull
    public void setResourceValue(
            @NonNull String resourceName,
            @IntRange(from = TypedValue.TYPE_FIRST_INT, to = TypedValue.TYPE_LAST_INT) int dataType,
            int value,
            @Nullable String configuration) {
        ensureValidResourceName(resourceName);
        mOverlay.entries.add(generateFabricatedOverlayInternalEntry(resourceName, dataType, value,
                configuration));
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
     * Sets the resource value in the fabricated overlay for the string-like type with the
     * configuration.
     *
     * @param resourceName name of the target resource to overlay (in the form
     *     [package]:type/entry)
     * @param dataType the data type of the new value
     * @param value the string representing the new value
     * @param configuration The string representation of the config this overlay is enabled for
     * @see android.util.TypedValue#TYPE_STRING android.util.TypedValue#type
     */
    @NonNull
    public void setResourceValue(
            @NonNull String resourceName,
            @StringTypeOverlayResource int dataType,
            @NonNull String value,
            @Nullable String configuration) {
        ensureValidResourceName(resourceName);
        mOverlay.entries.add(generateFabricatedOverlayInternalEntry(resourceName, dataType, value,
                configuration));
    }

    /**
     * Sets the resource value in the fabricated overlay for the file descriptor type with the
     * configuration.
     *
     * @param resourceName name of the target resource to overlay (in the form
     *     [package]:type/entry)
     * @param value the file descriptor whose contents are the value of the frro
     * @param configuration The string representation of the config this overlay is enabled for
     */
    @NonNull
    public void setResourceValue(
            @NonNull String resourceName,
            @NonNull ParcelFileDescriptor value,
            @Nullable String configuration) {
        ensureValidResourceName(resourceName);
        mOverlay.entries.add(
                generateFabricatedOverlayInternalEntry(resourceName, value, configuration, false));
    }

    /**
     * Sets the resource value in the fabricated overlay from a nine patch.
     *
     * @param resourceName name of the target resource to overlay (in the form
     *     [package]:type/entry)
     * @param value the file descriptor whose contents are the value of the frro
     * @param configuration The string representation of the config this overlay is enabled for
     */
    @NonNull
    @FlaggedApi(android.content.res.Flags.FLAG_NINE_PATCH_FRRO)
    public void setNinePatchResourceValue(
            @NonNull String resourceName,
            @NonNull ParcelFileDescriptor value,
            @Nullable String configuration) {
        ensureValidResourceName(resourceName);
        mOverlay.entries.add(
                generateFabricatedOverlayInternalEntry(resourceName, value, configuration, true));
    }

    /**
     * Sets the resource value in the fabricated overlay for the file descriptor type with the
     * configuration.
     *
     * @param resourceName name of the target resource to overlay (in the form
     *     [package]:type/entry)
     * @param value the file descriptor whose contents are the value of the frro
     * @param configuration The string representation of the config this overlay is enabled for
     */
    @NonNull
    @FlaggedApi(android.content.res.Flags.FLAG_ASSET_FILE_DESCRIPTOR_FRRO)
    public void setResourceValue(
            @NonNull String resourceName,
            @NonNull AssetFileDescriptor value,
            @Nullable String configuration) {
        ensureValidResourceName(resourceName);
        mOverlay.entries.add(
                generateFabricatedOverlayInternalEntry(resourceName, value, configuration));
    }
}
