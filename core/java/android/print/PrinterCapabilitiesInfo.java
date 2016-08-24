/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.print;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.print.PrintAttributes.ColorMode;
import android.print.PrintAttributes.DuplexMode;
import android.print.PrintAttributes.Margins;
import android.print.PrintAttributes.MediaSize;
import android.print.PrintAttributes.Resolution;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * This class represents the capabilities of a printer. Instances
 * of this class are created by a print service to report the
 * capabilities of a printer it manages. The capabilities of a
 * printer specify how it can print content. For example, what
 * are the media sizes supported by the printer, what are the
 * minimal margins of the printer based on its technical design,
 * etc.
 */
public final class PrinterCapabilitiesInfo implements Parcelable {
    /**
     * Undefined default value.
     *
     * @hide
     */
    public static final int DEFAULT_UNDEFINED = -1;

    private static final int PROPERTY_MEDIA_SIZE = 0;
    private static final int PROPERTY_RESOLUTION = 1;
    private static final int PROPERTY_COLOR_MODE = 2;
    private static final int PROPERTY_DUPLEX_MODE = 3;
    private static final int PROPERTY_COUNT = 4;

    private static final Margins DEFAULT_MARGINS = new Margins(0,  0,  0,  0);

    private @NonNull Margins mMinMargins = DEFAULT_MARGINS;
    private @NonNull List<MediaSize> mMediaSizes;
    private @NonNull List<Resolution> mResolutions;

    private int mColorModes;
    private int mDuplexModes;

    private final int[] mDefaults = new int[PROPERTY_COUNT];

    /**
     * @hide
     */
    public PrinterCapabilitiesInfo() {
        Arrays.fill(mDefaults, DEFAULT_UNDEFINED);
    }

    /**
     * @hide
     */
    public PrinterCapabilitiesInfo(PrinterCapabilitiesInfo prototype) {
        copyFrom(prototype);
    }

    /**
     * @hide
     */
    public void copyFrom(PrinterCapabilitiesInfo other) {
        if (this == other) {
            return;
        }

        mMinMargins = other.mMinMargins;

        if (other.mMediaSizes != null) {
            if (mMediaSizes != null) {
                mMediaSizes.clear();
                mMediaSizes.addAll(other.mMediaSizes);
            } else {
                mMediaSizes = new ArrayList<MediaSize>(other.mMediaSizes);
            }
        } else {
            mMediaSizes = null;
        }

        if (other.mResolutions != null) {
            if (mResolutions != null) {
                mResolutions.clear();
                mResolutions.addAll(other.mResolutions);
            } else {
                mResolutions = new ArrayList<Resolution>(other.mResolutions);
            }
        } else {
            mResolutions = null;
        }

        mColorModes = other.mColorModes;
        mDuplexModes = other.mDuplexModes;

        final int defaultCount = other.mDefaults.length;
        for (int i = 0; i < defaultCount; i++) {
            mDefaults[i] = other.mDefaults[i];
        }
    }

    /**
     * Gets the supported media sizes.
     *
     * @return The media sizes.
     */
    public @NonNull List<MediaSize> getMediaSizes() {
        return Collections.unmodifiableList(mMediaSizes);
    }

    /**
     * Gets the supported resolutions.
     *
     * @return The resolutions.
     */
    public @NonNull List<Resolution> getResolutions() {
        return Collections.unmodifiableList(mResolutions);
    }

    /**
     * Gets the minimal margins. These are the minimal margins
     * the printer physically supports.
     *
     * @return The minimal margins.
     */
    public @NonNull Margins getMinMargins() {
        return mMinMargins;
    }

    /**
     * Gets the bit mask of supported color modes.
     *
     * @return The bit mask of supported color modes.
     *
     * @see PrintAttributes#COLOR_MODE_COLOR
     * @see PrintAttributes#COLOR_MODE_MONOCHROME
     */
    public @ColorMode int getColorModes() {
        return mColorModes;
    }

    /**
     * Gets the bit mask of supported duplex modes.
     *
     * @return The bit mask of supported duplex modes.
     *
     * @see PrintAttributes#DUPLEX_MODE_NONE
     * @see PrintAttributes#DUPLEX_MODE_LONG_EDGE
     * @see PrintAttributes#DUPLEX_MODE_SHORT_EDGE
     */
    public @DuplexMode int getDuplexModes() {
        return mDuplexModes;
    }

    /**
     * Gets the default print attributes.
     *
     * @return The default attributes.
     */
    public @NonNull PrintAttributes getDefaults() {
        PrintAttributes.Builder builder = new PrintAttributes.Builder();

        builder.setMinMargins(mMinMargins);

        final int mediaSizeIndex = mDefaults[PROPERTY_MEDIA_SIZE];
        if (mediaSizeIndex >= 0) {
            builder.setMediaSize(mMediaSizes.get(mediaSizeIndex));
        }

        final int resolutionIndex = mDefaults[PROPERTY_RESOLUTION];
        if (resolutionIndex >= 0) {
            builder.setResolution(mResolutions.get(resolutionIndex));
        }

        final int colorMode = mDefaults[PROPERTY_COLOR_MODE];
        if (colorMode > 0) {
            builder.setColorMode(colorMode);
        }

        final int duplexMode = mDefaults[PROPERTY_DUPLEX_MODE];
        if (duplexMode > 0) {
            builder.setDuplexMode(duplexMode);
        }

        return builder.build();
    }

    /**
     * Call enforceSingle for each bit in the mask.
     *
     * @param mask The mask
     * @param enforceSingle The function to call
     */
    private static void enforceValidMask(int mask, IntConsumer enforceSingle) {
        int current = mask;
        while (current > 0) {
            final int currentMode = (1 << Integer.numberOfTrailingZeros(current));
            current &= ~currentMode;
            enforceSingle.accept(currentMode);
        }
    }

    private PrinterCapabilitiesInfo(Parcel parcel) {
        mMinMargins = Preconditions.checkNotNull(readMargins(parcel));
        readMediaSizes(parcel);
        readResolutions(parcel);

        mColorModes = parcel.readInt();
        enforceValidMask(mColorModes,
                (currentMode) -> PrintAttributes.enforceValidColorMode(currentMode));

        mDuplexModes = parcel.readInt();
        enforceValidMask(mDuplexModes,
                (currentMode) -> PrintAttributes.enforceValidDuplexMode(currentMode));

        readDefaults(parcel);
        Preconditions.checkArgument(mMediaSizes.size() > mDefaults[PROPERTY_MEDIA_SIZE]);
        Preconditions.checkArgument(mResolutions.size() > mDefaults[PROPERTY_RESOLUTION]);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        writeMargins(mMinMargins, parcel);
        writeMediaSizes(parcel);
        writeResolutions(parcel);

        parcel.writeInt(mColorModes);
        parcel.writeInt(mDuplexModes);

        writeDefaults(parcel);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((mMinMargins == null) ? 0 : mMinMargins.hashCode());
        result = prime * result + ((mMediaSizes == null) ? 0 : mMediaSizes.hashCode());
        result = prime * result + ((mResolutions == null) ? 0 : mResolutions.hashCode());
        result = prime * result + mColorModes;
        result = prime * result + mDuplexModes;
        result = prime * result + Arrays.hashCode(mDefaults);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        PrinterCapabilitiesInfo other = (PrinterCapabilitiesInfo) obj;
        if (mMinMargins == null) {
            if (other.mMinMargins != null) {
                return false;
            }
        } else if (!mMinMargins.equals(other.mMinMargins)) {
            return false;
        }
        if (mMediaSizes == null) {
            if (other.mMediaSizes != null) {
                return false;
            }
        } else if (!mMediaSizes.equals(other.mMediaSizes)) {
            return false;
        }
        if (mResolutions == null) {
            if (other.mResolutions != null) {
                return false;
            }
        } else if (!mResolutions.equals(other.mResolutions)) {
            return false;
        }
        if (mColorModes != other.mColorModes) {
            return false;
        }
        if (mDuplexModes != other.mDuplexModes) {
            return false;
        }
        if (!Arrays.equals(mDefaults, other.mDefaults)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("PrinterInfo{");
        builder.append("minMargins=").append(mMinMargins);
        builder.append(", mediaSizes=").append(mMediaSizes);
        builder.append(", resolutions=").append(mResolutions);
        builder.append(", colorModes=").append(colorModesToString());
        builder.append(", duplexModes=").append(duplexModesToString());
        builder.append("\"}");
        return builder.toString();
    }

    private String colorModesToString() {
        StringBuilder builder = new StringBuilder();
        builder.append('[');
        int colorModes = mColorModes;
        while (colorModes != 0) {
            final int colorMode = 1 << Integer.numberOfTrailingZeros(colorModes);
            colorModes &= ~colorMode;
            if (builder.length() > 1) {
                builder.append(", ");
            }
            builder.append(PrintAttributes.colorModeToString(colorMode));
        }
        builder.append(']');
        return builder.toString();
    }

    private String duplexModesToString() {
        StringBuilder builder = new StringBuilder();
        builder.append('[');
        int duplexModes = mDuplexModes;
        while (duplexModes != 0) {
            final int duplexMode = 1 << Integer.numberOfTrailingZeros(duplexModes);
            duplexModes &= ~duplexMode;
            if (builder.length() > 1) {
                builder.append(", ");
            }
            builder.append(PrintAttributes.duplexModeToString(duplexMode));
        }
        builder.append(']');
        return builder.toString();
    }

    private void writeMediaSizes(Parcel parcel) {
        if (mMediaSizes == null) {
            parcel.writeInt(0);
            return;
        }
        final int mediaSizeCount = mMediaSizes.size();
        parcel.writeInt(mediaSizeCount);
        for (int i = 0; i < mediaSizeCount; i++) {
            mMediaSizes.get(i).writeToParcel(parcel);
        }
    }

    private void readMediaSizes(Parcel parcel) {
        final int mediaSizeCount = parcel.readInt();
        if (mediaSizeCount > 0 && mMediaSizes == null) {
            mMediaSizes = new ArrayList<MediaSize>();
        }
        for (int i = 0; i < mediaSizeCount; i++) {
            mMediaSizes.add(MediaSize.createFromParcel(parcel));
        }
    }

    private void writeResolutions(Parcel parcel) {
        if (mResolutions == null) {
            parcel.writeInt(0);
            return;
        }
        final int resolutionCount = mResolutions.size();
        parcel.writeInt(resolutionCount);
        for (int i = 0; i < resolutionCount; i++) {
            mResolutions.get(i).writeToParcel(parcel);
        }
    }

    private void readResolutions(Parcel parcel) {
        final int resolutionCount = parcel.readInt();
        if (resolutionCount > 0 && mResolutions == null) {
            mResolutions = new ArrayList<Resolution>();
        }
        for (int i = 0; i < resolutionCount; i++) {
            mResolutions.add(Resolution.createFromParcel(parcel));
        }
    }

    private void writeMargins(Margins margins, Parcel parcel) {
        if (margins == null) {
            parcel.writeInt(0);
        } else {
            parcel.writeInt(1);
            margins.writeToParcel(parcel);
        }
    }

    private Margins readMargins(Parcel parcel) {
        return (parcel.readInt() == 1) ? Margins.createFromParcel(parcel) : null;
    }

    private void readDefaults(Parcel parcel) {
        final int defaultCount = parcel.readInt();
        for (int i = 0; i < defaultCount; i++) {
            mDefaults[i] = parcel.readInt();
        }
    }

    private void writeDefaults(Parcel parcel) {
        final int defaultCount = mDefaults.length;
        parcel.writeInt(defaultCount);
        for (int i = 0; i < defaultCount; i++) {
            parcel.writeInt(mDefaults[i]);
        }
    }

    /**
     * Builder for creating of a {@link PrinterCapabilitiesInfo}. This class is
     * responsible to enforce that all required attributes have at least one
     * default value. In other words, this class creates only well-formed {@link
     * PrinterCapabilitiesInfo}s.
     * <p>
     * Look at the individual methods for a reference whether a property is
     * required or if it is optional.
     * </p>
     */
    public static final class Builder {
        private final PrinterCapabilitiesInfo mPrototype;

        /**
         * Creates a new instance.
         *
         * @param printerId The printer id. Cannot be <code>null</code>.
         *
         * @throws IllegalArgumentException If the printer id is <code>null</code>.
         */
        public Builder(@NonNull PrinterId printerId) {
            if (printerId == null) {
                throw new IllegalArgumentException("printerId cannot be null.");
            }
            mPrototype = new PrinterCapabilitiesInfo();
        }

        /**
         * Adds a supported media size.
         * <p>
         * <strong>Required:</strong> Yes
         * </p>
         *
         * @param mediaSize A media size.
         * @param isDefault Whether this is the default.
         * @return This builder.
         * @throws IllegalArgumentException If set as default and there
         *     is already a default.
         *
         * @see PrintAttributes.MediaSize
         */
        public @NonNull Builder addMediaSize(@NonNull MediaSize mediaSize, boolean isDefault) {
            if (mPrototype.mMediaSizes == null) {
                mPrototype.mMediaSizes = new ArrayList<MediaSize>();
            }
            final int insertionIndex = mPrototype.mMediaSizes.size();
            mPrototype.mMediaSizes.add(mediaSize);
            if (isDefault) {
                throwIfDefaultAlreadySpecified(PROPERTY_MEDIA_SIZE);
                mPrototype.mDefaults[PROPERTY_MEDIA_SIZE] = insertionIndex;
            }
            return this;
        }

        /**
         * Adds a supported resolution.
         * <p>
         * <strong>Required:</strong> Yes
         * </p>
         *
         * @param resolution A resolution.
         * @param isDefault Whether this is the default.
         * @return This builder.
         *
         * @throws IllegalArgumentException If set as default and there
         *     is already a default.
         *
         * @see PrintAttributes.Resolution
         */
        public @NonNull Builder addResolution(@NonNull Resolution resolution, boolean isDefault) {
            if (mPrototype.mResolutions == null) {
                mPrototype.mResolutions = new ArrayList<Resolution>();
            }
            final int insertionIndex = mPrototype.mResolutions.size();
            mPrototype.mResolutions.add(resolution);
            if (isDefault) {
                throwIfDefaultAlreadySpecified(PROPERTY_RESOLUTION);
                mPrototype.mDefaults[PROPERTY_RESOLUTION] = insertionIndex;
            }
            return this;
        }

        /**
         * Sets the minimal margins. These are the minimal margins
         * the printer physically supports.
         *
         * <p>
         * <strong>Required:</strong> Yes
         * </p>
         *
         * @param margins The margins.
         * @return This builder.
         *
         * @throws IllegalArgumentException If margins are <code>null</code>.
         *
         * @see PrintAttributes.Margins
         */
        public @NonNull Builder setMinMargins(@NonNull Margins margins) {
            if (margins == null) {
                throw new IllegalArgumentException("margins cannot be null");
            }
            mPrototype.mMinMargins = margins;
            return this;
        }

        /**
         * Sets the color modes.
         * <p>
         * <strong>Required:</strong> Yes
         * </p>
         *
         * @param colorModes The color mode bit mask.
         * @param defaultColorMode The default color mode.
         * @return This builder.
         * <p>
         * <strong>Note:</strong> On platform version 19 (Kitkat) specifying
         * only PrintAttributes#COLOR_MODE_MONOCHROME leads to a print spooler
         * crash. Hence, you should declare either both color modes or
         * PrintAttributes#COLOR_MODE_COLOR.
         * </p>
         *
         * @throws IllegalArgumentException If color modes contains an invalid
         *         mode bit or if the default color mode is invalid.
         *
         * @see PrintAttributes#COLOR_MODE_COLOR
         * @see PrintAttributes#COLOR_MODE_MONOCHROME
         */
        public @NonNull Builder setColorModes(@ColorMode int colorModes,
                @ColorMode int defaultColorMode) {
            enforceValidMask(colorModes,
                    (currentMode) -> PrintAttributes.enforceValidColorMode(currentMode));
            PrintAttributes.enforceValidColorMode(defaultColorMode);
            mPrototype.mColorModes = colorModes;
            mPrototype.mDefaults[PROPERTY_COLOR_MODE] = defaultColorMode;
            return this;
        }

        /**
         * Sets the duplex modes.
         * <p>
         * <strong>Required:</strong> No
         * </p>
         *
         * @param duplexModes The duplex mode bit mask.
         * @param defaultDuplexMode The default duplex mode.
         * @return This builder.
         *
         * @throws IllegalArgumentException If duplex modes contains an invalid
         *         mode bit or if the default duplex mode is invalid.
         *
         * @see PrintAttributes#DUPLEX_MODE_NONE
         * @see PrintAttributes#DUPLEX_MODE_LONG_EDGE
         * @see PrintAttributes#DUPLEX_MODE_SHORT_EDGE
         */
        public @NonNull Builder setDuplexModes(@DuplexMode int duplexModes,
                @DuplexMode int defaultDuplexMode) {
            enforceValidMask(duplexModes,
                    (currentMode) -> PrintAttributes.enforceValidDuplexMode(currentMode));
            PrintAttributes.enforceValidDuplexMode(defaultDuplexMode);
            mPrototype.mDuplexModes = duplexModes;
            mPrototype.mDefaults[PROPERTY_DUPLEX_MODE] = defaultDuplexMode;
            return this;
        }

        /**
         * Crates a new {@link PrinterCapabilitiesInfo} enforcing that all
         * required properties have been specified. See individual methods
         * in this class for reference about required attributes.
         * <p>
         * <strong>Note:</strong> If you do not add supported duplex modes,
         * {@link android.print.PrintAttributes#DUPLEX_MODE_NONE} will set
         * as the only supported mode and also as the default duplex mode.
         * </p>
         *
         * @return A new {@link PrinterCapabilitiesInfo}.
         *
         * @throws IllegalStateException If a required attribute was not specified.
         */
        public @NonNull PrinterCapabilitiesInfo build() {
            if (mPrototype.mMediaSizes == null || mPrototype.mMediaSizes.isEmpty()) {
                throw new IllegalStateException("No media size specified.");
            }
            if (mPrototype.mDefaults[PROPERTY_MEDIA_SIZE] == DEFAULT_UNDEFINED) {
                throw new IllegalStateException("No default media size specified.");
            }
            if (mPrototype.mResolutions == null || mPrototype.mResolutions.isEmpty()) {
                throw new IllegalStateException("No resolution specified.");
            }
            if (mPrototype.mDefaults[PROPERTY_RESOLUTION] == DEFAULT_UNDEFINED) {
                throw new IllegalStateException("No default resolution specified.");
            }
            if (mPrototype.mColorModes == 0) {
                throw new IllegalStateException("No color mode specified.");
            }
            if (mPrototype.mDefaults[PROPERTY_COLOR_MODE] == DEFAULT_UNDEFINED) {
                throw new IllegalStateException("No default color mode specified.");
            }
            if (mPrototype.mDuplexModes == 0) {
                setDuplexModes(PrintAttributes.DUPLEX_MODE_NONE,
                        PrintAttributes.DUPLEX_MODE_NONE);
            }
            if (mPrototype.mMinMargins == null) {
                throw new IllegalArgumentException("margins cannot be null");
            }
            return mPrototype;
        }

        private void throwIfDefaultAlreadySpecified(int propertyIndex) {
            if (mPrototype.mDefaults[propertyIndex] != DEFAULT_UNDEFINED) {
                throw new IllegalArgumentException("Default already specified.");
            }
        }
    }

    public static final Parcelable.Creator<PrinterCapabilitiesInfo> CREATOR =
            new Parcelable.Creator<PrinterCapabilitiesInfo>() {
        @Override
        public PrinterCapabilitiesInfo createFromParcel(Parcel parcel) {
            return new PrinterCapabilitiesInfo(parcel);
        }

        @Override
        public PrinterCapabilitiesInfo[] newArray(int size) {
            return new PrinterCapabilitiesInfo[size];
        }
    };
}
