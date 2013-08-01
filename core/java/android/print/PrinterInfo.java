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

import android.os.Parcel;
import android.os.Parcelable;
import android.print.PrintAttributes.Margins;
import android.print.PrintAttributes.MediaSize;
import android.print.PrintAttributes.Resolution;
import android.print.PrintAttributes.Tray;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * This class represents the description of a printer. A description
 * contains the printer id, human readable name, status, and available
 * options for various printer capabilities, such as media size, etc.
 */
public final class PrinterInfo implements Parcelable {
    /**
     * Undefined default value.
     *
     * @hide
     */
    public static final int DEFAULT_UNDEFINED = -1;

    private static final int PROPERTY_MEDIA_SIZE = 0;
    private static final int PROPERTY_RESOLUTION = 1;
    private static final int PROPERTY_INPUT_TRAY = 2;
    private static final int PROPERTY_OUTPUT_TRAY = 3;
    private static final int PROPERTY_DUPLEX_MODE = 4;
    private static final int PROPERTY_COLOR_MODE = 5;
    private static final int PROPERTY_FITTING_MODE = 6;
    private static final int PROPERTY_ORIENTATION = 7;
    private static final int PROPERTY_COUNT = 8;

    /** Printer status: the printer is ready to print. */
    public static final int STATUS_READY = 1;

    private static final Margins DEFAULT_MARGINS = new Margins(0,  0,  0,  0);

    // TODO: Add printer status constants.

    private PrinterId mId;
    private CharSequence mLabel;
    private int mStatus;

    private Margins mMinMargins = DEFAULT_MARGINS;
    private List<MediaSize> mMediaSizes;
    private List<Resolution> mResolutions;
    private List<Tray> mInputTrays;
    private List<Tray> mOutputTrays;

    private int mDuplexModes;
    private int mColorModes;
    private int mFittingModes;
    private int mOrientations;

    private final int[] mDefaults = new int[PROPERTY_COUNT];
    private Margins mDefaultMargins = DEFAULT_MARGINS;

    /**
     * @hide
     */
    public PrinterInfo() {
        Arrays.fill(mDefaults, DEFAULT_UNDEFINED);
    }

    private PrinterInfo(PrinterInfo prototype) {
        copyFrom(prototype);
    }

    /**
     * @hide
     */
    public void copyFrom(PrinterInfo other) {
        mId = other.mId;
        mLabel = other.mLabel;
        mStatus = other.mStatus;

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

        if (other.mInputTrays != null) {
            if (mInputTrays != null) {
                mInputTrays.clear();
                mInputTrays.addAll(other.mInputTrays);
            } else {
                mInputTrays = new ArrayList<Tray>(other.mInputTrays);
            }
        } else {
            mInputTrays = null;
        }

        if (other.mOutputTrays != null) {
            if (mOutputTrays != null) {
                mOutputTrays.clear();
                mOutputTrays.addAll(other.mOutputTrays);
            } else {
                mOutputTrays = new ArrayList<Tray>(other.mOutputTrays);
            }
        } else {
            mOutputTrays = null;
        }

        mDuplexModes = other.mDuplexModes;
        mColorModes = other.mColorModes;
        mFittingModes = other.mFittingModes;
        mOrientations = other.mOrientations;

        final int defaultCount = other.mDefaults.length;
        for (int i = 0; i < defaultCount; i++) {
            mDefaults[i] = other.mDefaults[i];
        }
        mDefaultMargins = other.mDefaultMargins;
    }

    /**
     * Get the globally unique printer id.
     *
     * @return The printer id.
     */
    public PrinterId getId() {
        return mId;
    }

    /**
     * Gets the human readable printer label.
     *
     * @return The human readable label.
     */
    public CharSequence getLabel() {
        return mLabel;
    }

    /**
     * Gets the status of the printer.
     *
     * @return The status.
     */
    public int getStatus() {
        return mStatus;
    }

    /**
     * Gets the supported media sizes.
     *
     * @return The supported media sizes.
     */
    public List<MediaSize> getMediaSizes() {
        return mMediaSizes;
    }

    /**
     * Gets the supported resolutions.
     *
     * @return The supported resolutions.
     */
    public List<Resolution> getResolutions() {
        return mResolutions;
    }

    /**
     * Gets the minimal supported margins.
     *
     * @return The minimal margins.
     */
    public Margins getMinMargins() {
        return mMinMargins;
    }

    /**
     * Gets the available input trays.
     *
     * @return The input trays.
     */
    public List<Tray> getInputTrays() {
        return mInputTrays;
    }

    /**
     * Gets the available output trays.
     *
     * @return The output trays.
     */
    public List<Tray> getOutputTrays() {
        return mOutputTrays;
    }

    /**
     * Gets the supported duplex modes.
     *
     * @return The duplex modes.
     *
     * @see PrintAttributes#DUPLEX_MODE_NONE
     * @see PrintAttributes#DUPLEX_MODE_LONG_EDGE
     * @see PrintAttributes#DUPLEX_MODE_SHORT_EDGE
     */
    public int getDuplexModes() {
        return mDuplexModes;
    }

    /**
     * Gets the supported color modes.
     *
     * @return The color modes.
     *
     * @see PrintAttributes#COLOR_MODE_COLOR
     * @see PrintAttributes#COLOR_MODE_MONOCHROME
     */
    public int getColorModes() {
        return mColorModes;
    }

    /**
     * Gets the supported fitting modes.
     *
     * @return The fitting modes.
     *
     * @see PrintAttributes#FITTING_MODE_NONE
     * @see PrintAttributes#FITTING_MODE_FIT_TO_PAGE
     */
    public int getFittingModes() {
        return mFittingModes;
    }

    /**
     * Gets the supported orientations.
     *
     * @return The orientations.
     *
     * @see PrintAttributes#ORIENTATION_PORTRAIT
     * @see PrintAttributes#ORIENTATION_LANDSCAPE
     */
    public int getOrientations() {
        return mOrientations;
    }

    /**
     * Gets the default print attributes.
     *
     * @param outAttributes The attributes to populated.
     */
    public void getDefaults(PrintAttributes outAttributes) {
        outAttributes.clear();

        outAttributes.setMargins(mDefaultMargins);

        final int mediaSizeIndex = mDefaults[PROPERTY_MEDIA_SIZE];
        if (mediaSizeIndex >= 0) {
            outAttributes.setMediaSize(mMediaSizes.get(mediaSizeIndex));
        }

        final int resolutionIndex = mDefaults[PROPERTY_RESOLUTION];
        if (resolutionIndex >= 0) {
            outAttributes.setResolution(mResolutions.get(resolutionIndex));
        }

        final int inputTrayIndex = mDefaults[PROPERTY_INPUT_TRAY];
        if (inputTrayIndex >= 0) {
            outAttributes.setInputTray(mInputTrays.get(inputTrayIndex));
        }

        final int outputTrayIndex = mDefaults[PROPERTY_OUTPUT_TRAY];
        if (outputTrayIndex >= 0) {
            outAttributes.setOutputTray(mOutputTrays.get(outputTrayIndex));
        }

        final int duplexMode = mDefaults[PROPERTY_DUPLEX_MODE];
        if (duplexMode > 0) {
            outAttributes.setDuplexMode(duplexMode);
        }

        final int colorMode = mDefaults[PROPERTY_COLOR_MODE];
        if (colorMode > 0) {
            outAttributes.setColorMode(mColorModes & colorMode);
        }

        final int fittingMode = mDefaults[PROPERTY_FITTING_MODE];
        if (fittingMode > 0) {
            outAttributes.setFittingMode(fittingMode);
        }

        final int orientation = mDefaults[PROPERTY_ORIENTATION];
        if (orientation > 0) {
            outAttributes.setOrientation(orientation);
        }
    }

    /**
     * Gets whether this printer info is fully-populated, i.e. whether
     * all required attributes are specified. See the {@link Builder}
     * documentation for which attributes are required.
     *
     * @return Whether this info has all required attributes.
     */
    public boolean hasAllRequiredAttributes() {
        return (mMediaSizes != null && !mMediaSizes.isEmpty()
                && mResolutions != null && !mResolutions.isEmpty()
                && mColorModes != 0 || mOrientations != 0
                && mDefaults[PROPERTY_MEDIA_SIZE] != DEFAULT_UNDEFINED
                && mDefaults[PROPERTY_RESOLUTION] != DEFAULT_UNDEFINED
                && mDefaults[PROPERTY_COLOR_MODE] != DEFAULT_UNDEFINED
                && mDefaults[PROPERTY_ORIENTATION] != DEFAULT_UNDEFINED);
    }

    private PrinterInfo(Parcel parcel) {
        mId = parcel.readParcelable(null);
        mLabel = parcel.readCharSequence();
        mStatus = parcel.readInt();

        mMinMargins = readMargins(parcel);
        readMediaSizes(parcel);
        readResolutions(parcel);
        mInputTrays = readInputTrays(parcel);
        mOutputTrays = readOutputTrays(parcel);

        mColorModes = parcel.readInt();
        mDuplexModes = parcel.readInt();
        mFittingModes = parcel.readInt();
        mOrientations = parcel.readInt();

        readDefaults(parcel);
        mDefaultMargins = readMargins(parcel);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeParcelable(mId, flags);
        parcel.writeCharSequence(mLabel);
        parcel.writeInt(mStatus);

        writeMargins(mMinMargins, parcel);
        writeMediaSizes(parcel);
        writeResolutions(parcel);
        writeInputTrays(parcel);
        writeOutputTrays(parcel);

        parcel.writeInt(mColorModes);
        parcel.writeInt(mDuplexModes);
        parcel.writeInt(mFittingModes);
        parcel.writeInt(mOrientations);

        writeDefaults(parcel);
        writeMargins(mDefaultMargins, parcel);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((mId == null) ? 0 : mId.hashCode());
        result = prime * result + ((mLabel == null) ? 0 : mLabel.hashCode());
        result = prime * result + mStatus;
        result = prime * result + ((mMinMargins == null) ? 0 : mMinMargins.hashCode());
        result = prime * result + ((mMediaSizes == null) ? 0 : mMediaSizes.hashCode());
        result = prime * result + ((mResolutions == null) ? 0 : mResolutions.hashCode());
        result = prime * result + ((mInputTrays == null) ? 0 : mInputTrays.hashCode());
        result = prime * result + ((mOutputTrays == null) ? 0 : mOutputTrays.hashCode());
        result = prime * result + mDuplexModes;
        result = prime * result + mColorModes;
        result = prime * result + mFittingModes;
        result = prime * result + mOrientations;
        result = prime * result + Arrays.hashCode(mDefaults);
        result = prime * result + ((mDefaultMargins == null) ? 0 : mDefaultMargins.hashCode());
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
        PrinterInfo other = (PrinterInfo) obj;
        if (mId == null) {
            if (other.mId != null) {
                return false;
            }
        } else if (!mId.equals(other.mId)) {
            return false;
        }
        if (!TextUtils.equals(mLabel, other.mLabel)) {
            return false;
        }
        if (mStatus != other.mStatus) {
            return false;
        }
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
        if (mInputTrays == null) {
            if (other.mInputTrays != null) {
                return false;
            }
        } else if (!mInputTrays.equals(other.mInputTrays)) {
            return false;
        }
        if (mOutputTrays == null) {
            if (other.mOutputTrays != null) {
                return false;
            }
        } else if (!mOutputTrays.equals(other.mOutputTrays)) {
            return false;
        }
        if (mDuplexModes != other.mDuplexModes) {
            return false;
        }
        if (mColorModes != other.mColorModes) {
            return false;
        }
        if (mFittingModes != other.mFittingModes) {
            return false;
        }
        if (mOrientations != other.mOrientations) {
            return false;
        }
        if (!Arrays.equals(mDefaults, other.mDefaults)) {
            return false;
        }
        if (mDefaultMargins == null) {
            if (other.mDefaultMargins != null) {
                return false;
            }
        } else if (!mDefaultMargins.equals(other.mDefaultMargins)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("PrinterInfo{");
        builder.append(mId).append(", \"");
        builder.append(mLabel);
        builder.append("\"}");
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

    private void writeInputTrays(Parcel parcel) {
        if (mInputTrays == null) {
            parcel.writeInt(0);
            return;
        }
        final int inputTrayCount = mInputTrays.size();
        parcel.writeInt(inputTrayCount);
        for (int i = 0; i < inputTrayCount; i++) {
            mInputTrays.get(i).writeToParcel(parcel);
        }
    }

    private List<Tray> readInputTrays(Parcel parcel) {
        final int inputTrayCount = parcel.readInt();
        if (inputTrayCount <= 0) {
            return null;
        }
        List<Tray> inputTrays = new ArrayList<Tray>(inputTrayCount);
        for (int i = 0; i < inputTrayCount; i++) {
            inputTrays.add(Tray.createFromParcel(parcel));
        }
        return inputTrays;
    }

    private void writeOutputTrays(Parcel parcel) {
        if (mOutputTrays == null) {
            parcel.writeInt(0);
            return;
        }
        final int outputTrayCount = mOutputTrays.size();
        parcel.writeInt(outputTrayCount);
        for (int i = 0; i < outputTrayCount; i++) {
            mOutputTrays.get(i).writeToParcel(parcel);
        }
    }

    private List<Tray> readOutputTrays(Parcel parcel) {
        final int outputTrayCount = parcel.readInt();
        if (outputTrayCount <= 0) {
            return null;
        }
        List<Tray> outputTrays = new ArrayList<Tray>(outputTrayCount);
        for (int i = 0; i < outputTrayCount; i++) {
            outputTrays.add(Tray.createFromParcel(parcel));
        }
        return outputTrays;
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
     * Builder for creating of a {@link PrinterInfo}. This class is responsible
     * to enforce that all required attributes have at least one default value.
     * In other words, this class creates only well-formed {@link PrinterInfo}s.
     * <p>
     * Look at the individual methods for a reference whether a property is
     * required or if it is optional.
     * </p>
     */
    public static final class Builder {
        private final PrinterInfo mPrototype;

        /**
         * Creates a new instance.
         *
         * @param printerId The printer id. Cannot be null.
         * @param label The human readable printer label. Cannot be null or empty.
         *
         * @throws IllegalArgumentException If the printer id is null.
         * @throws IllegalArgumentException If the label is empty.
         */
        public Builder(PrinterId printerId, CharSequence label) {
            if (printerId == null) {
                throw new IllegalArgumentException("printerId cannot be null.");
            }
            if (TextUtils.isEmpty(label)) {
                throw new IllegalArgumentException("label cannot be empty.");
            }
            mPrototype = new PrinterInfo();
            mPrototype.mLabel = label;
            mPrototype.mId = printerId;
        }

        /**
         * Sets the printer status.
         * <p>
         * <strong>Required:</strong> Yes
         * </p>
         *
         * @param status The status.
         * @return This builder.
         */
        public Builder setStatus(int status) {
            mPrototype.mStatus = status;
            return this;
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
        public Builder addMediaSize(MediaSize mediaSize, boolean isDefault) {
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
        public Builder addResolution(Resolution resolution, boolean isDefault) {
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
         * Sets the minimal margins.
         * <p>
         * <strong>Required:</strong> No
         * </p>
         *
         * @param margins The margins.
         * @param defaultMargins The default margins.
         * @return This builder.
         *
         * @see PrintAttributes.Margins
         */
        public Builder setMinMargins(Margins margins, Margins defaultMargins) {
            if (margins.getLeftMils() > defaultMargins.getLeftMils()
                    || margins.getTopMils() > defaultMargins.getTopMils()
                    || margins.getRightMils() < defaultMargins.getRightMils()
                    || margins.getBottomMils() < defaultMargins.getBottomMils()) {
                throw new IllegalArgumentException("Default margins"
                    + " cannot be outside of the min margins.");
            }
            mPrototype.mMinMargins = margins;
            mPrototype.mDefaultMargins = defaultMargins;
            return this;
        }

        /**
         * Adds an input tray.
         * <p>
         * <strong>Required:</strong> No
         * </p>
         *
         * @param inputTray A tray.
         * @param isDefault Whether this is the default.
         * @return This builder.
         *
         * @throws IllegalArgumentException If set as default and there
         *     is already a default.
         *
         * @see PrintAttributes.Tray
         */
        public Builder addInputTray(Tray inputTray, boolean isDefault) {
            if (mPrototype.mInputTrays == null) {
                mPrototype.mInputTrays = new ArrayList<Tray>();
            }
            final int insertionIndex = mPrototype.mInputTrays.size();
            mPrototype.mInputTrays.add(inputTray);
            if (isDefault) {
                throwIfDefaultAlreadySpecified(PROPERTY_INPUT_TRAY);
                mPrototype.mDefaults[PROPERTY_INPUT_TRAY] = insertionIndex;
            }
            return this;
        }

        /**
         * Adds an output tray.
         * <p>
         * <strong>Required:</strong> No
         * </p>
         *
         * @param outputTray A tray.
         * @param isDefault Whether this is the default.
         * @return This builder.
         *
         * @throws IllegalArgumentException If set as default and there
         *     is already a default.
         *
         * @see PrintAttributes.Tray
         */
        public Builder addOutputTray(Tray outputTray, boolean isDefault) {
            if (mPrototype.mOutputTrays == null) {
                mPrototype.mOutputTrays = new ArrayList<Tray>();
            }
            final int insertionIndex = mPrototype.mOutputTrays.size();
            mPrototype.mOutputTrays.add(outputTray);
            if (isDefault) {
                throwIfDefaultAlreadySpecified(PROPERTY_OUTPUT_TRAY);
                mPrototype.mDefaults[PROPERTY_OUTPUT_TRAY] = insertionIndex;
            }
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
         *
         * @throws IllegalArgumentException If color modes contains an invalid
         *         mode bit or if the default color mode is invalid.
         *
         * @see PrintAttributes#COLOR_MODE_COLOR
         * @see PrintAttributes#COLOR_MODE_MONOCHROME
         */
        public Builder setColorModes(int colorModes, int defaultColorMode) {
            int currentModes = colorModes;
            while (currentModes > 0) {
                final int currentMode = (1 << Integer.numberOfTrailingZeros(currentModes));
                currentModes &= ~currentMode;
                PrintAttributes.enforceValidColorMode(currentMode);
            }
            if ((colorModes & defaultColorMode) == 0) {
                throw new IllegalArgumentException("Default color mode not in color modes.");
            }
            PrintAttributes.enforceValidColorMode(colorModes);
            mPrototype.mColorModes = colorModes;
            mPrototype.mDefaults[PROPERTY_COLOR_MODE] = defaultColorMode;
            return this;
        }

        /**
         * Set the duplex modes.
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
        public Builder setDuplexModes(int duplexModes, int defaultDuplexMode) {
            int currentModes = duplexModes;
            while (currentModes > 0) {
                final int currentMode = (1 << Integer.numberOfTrailingZeros(currentModes));
                currentModes &= ~currentMode;
                PrintAttributes.enforceValidDuplexMode(currentMode);
            }
            if ((duplexModes & defaultDuplexMode) == 0) {
                throw new IllegalArgumentException("Default duplex mode not in duplex modes.");
            }
            PrintAttributes.enforceValidDuplexMode(defaultDuplexMode);
            mPrototype.mDuplexModes = duplexModes;
            mPrototype.mDefaults[PROPERTY_DUPLEX_MODE] = defaultDuplexMode;
            return this;
        }

        /**
         * Sets the fitting modes.
         * <p>
         * <strong>Required:</strong> No
         * </p>
         *
         * @param fittingModes The fitting mode bit mask.
         * @param defaultFittingMode The default fitting mode.
         * @return This builder.
         *
         * @throws IllegalArgumentException If fitting modes contains an invalid
         *         mode bit or if the default fitting mode is invalid.
         *
         * @see PrintAttributes#FITTING_MODE_NONE
         * @see PrintAttributes#FITTING_MODE_FIT_TO_PAGE
         */
        public Builder setFittingModes(int fittingModes, int defaultFittingMode) {
            int currentModes = fittingModes;
            while (currentModes > 0) {
                final int currentMode = (1 << Integer.numberOfTrailingZeros(currentModes));
                currentModes &= ~currentMode;
                PrintAttributes.enfoceValidFittingMode(currentMode);
            }
            if ((fittingModes & defaultFittingMode) == 0) {
                throw new IllegalArgumentException("Default fitting mode not in fiting modes.");
            }
            PrintAttributes.enfoceValidFittingMode(defaultFittingMode);
            mPrototype.mFittingModes = fittingModes;
            mPrototype.mDefaults[PROPERTY_FITTING_MODE] = defaultFittingMode;
            return this;
        }

        /**
         * Sets the orientations.
         * <p>
         * <strong>Required:</strong> Yes
         * </p>
         *
         * @param orientations The orientation bit mask.
         * @param defaultOrientation The default orientation.
         * @return This builder.
         *
         * @throws IllegalArgumentException If orientations contains an invalid
         *         mode bit or if the default orientation is invalid.
         *
         * @see PrintAttributes#ORIENTATION_PORTRAIT
         * @see PrintAttributes#ORIENTATION_LANDSCAPE
         */
        public Builder setOrientations(int orientations, int defaultOrientation) {
            int currentOrientaions = orientations;
            while (currentOrientaions > 0) {
                final int currentOrnt = (1 << Integer.numberOfTrailingZeros(currentOrientaions));
                currentOrientaions &= ~currentOrnt;
                PrintAttributes.enforceValidOrientation(currentOrnt);
            }
            if ((orientations & defaultOrientation) == 0) {
                throw new IllegalArgumentException("Default orientation not in orientations.");
            }
            PrintAttributes.enforceValidOrientation(defaultOrientation);
            mPrototype.mOrientations = orientations;
            mPrototype.mDefaults[PROPERTY_ORIENTATION] = defaultOrientation;
            return this;
        }

        /**
         * Crates a new {@link PrinterInfo}.
         *
         * @return A new {@link PrinterInfo}.
         */
        public PrinterInfo create() {
            return new PrinterInfo(mPrototype);
        }

        private void throwIfDefaultAlreadySpecified(int propertyIndex) {
            if (mPrototype.mDefaults[propertyIndex] != DEFAULT_UNDEFINED) {
                throw new IllegalArgumentException("Default already specified.");
            }
        }
    }

    public static final Parcelable.Creator<PrinterInfo> CREATOR =
            new Parcelable.Creator<PrinterInfo>() {
        @Override
        public PrinterInfo createFromParcel(Parcel parcel) {
            return new PrinterInfo(parcel);
        }

        @Override
        public PrinterInfo[] newArray(int size) {
            return new PrinterInfo[size];
        }
    };
}
