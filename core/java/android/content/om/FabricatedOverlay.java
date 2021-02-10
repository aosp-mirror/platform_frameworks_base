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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.FabricatedOverlayInternal;
import android.os.FabricatedOverlayInternalEntry;
import android.text.TextUtils;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;

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
         * Sets the name of the overlayable resources to overlay (can be null).
         */
        public Builder setTargetOverlayable(@Nullable String targetOverlayable) {
            mTargetOverlayable = TextUtils.emptyIfNull(targetOverlayable);
            return this;
        }

        /**
         * Sets the value of
         *
         * @param resourceName name of the target resource to overlay (in the form
         *                     [package]:type/entry)
         * @param dataType the data type of the new value
         * @param value the unsigned 32 bit integer representing the new value
         *
         * @see android.util.TypedValue#type
         */
        public Builder setResourceValue(@NonNull String resourceName, int dataType, int value) {
            final FabricatedOverlayInternalEntry entry = new FabricatedOverlayInternalEntry();
            entry.resourceName = resourceName;
            entry.dataType = dataType;
            entry.data = value;
            mEntries.add(entry);
            return this;
        }

        /** Builds an immutable fabricated overlay. */
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
