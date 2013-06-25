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

import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import com.android.internal.R;

/**
 * This class represents the attributes of a print job.
 */
public final class PrintAttributes implements Parcelable {
    /** Duplex mode: No duplexing */
    public static final int DUPLEX_MODE_NONE = 1 << 0;
    /** Duplex mode: Turn a page along its long edge, e.g. like a book */
    public static final int DUPLEX_MODE_LONG_EDGE = 1 << 1;
    /** Duplex mode: Turn a page along its short edge, e.g. like a notepad */
    public static final int DUPLEX_MODE_SHORT_EDGE = 1 << 2;


    /** Orientation: Portrait page orientation. */
    public static final int ORIENTATION_PORTRAIT = 1 << 0;
    /** Orientation: Landscape page orientation. */
    public static final int ORIENTATION_LANDSCAPE = 1 << 1;


    /** Color mode: Monochrome color scheme, e.g. one color is used. */
    public static final int COLOR_MODE_MONOCHROME = 1 << 0;
    /** Color mode: Color color scheme, e.g. many colors are used. */
    public static final int COLOR_MODE_COLOR = 1 << 1;


    /** Fitting mode: No fitting. */
    public static final int FITTING_MODE_NONE = 0x00000001;
    /** Fitting mode: Fit the content to the page. */
    public static final int FITTING_MODE_FIT_TO_PAGE = 0x00000002;


    private static final int VALID_DUPLEX_MODES =
            DUPLEX_MODE_NONE | DUPLEX_MODE_LONG_EDGE | DUPLEX_MODE_SHORT_EDGE;

    private static final int VALID_COLOR_MODES =
            COLOR_MODE_MONOCHROME | COLOR_MODE_COLOR;

    private static final int VALID_FITTING_MODES =
            FITTING_MODE_NONE | FITTING_MODE_FIT_TO_PAGE;

    private static final int VALID_ORIENTATIONS =
            ORIENTATION_PORTRAIT | ORIENTATION_LANDSCAPE;

    private MediaSize mMediaSize;
    private Resolution mResolution;
    private Margins mMargins;
    private Tray mInputTray;
    private Tray mOutputTray;

    private int mDuplexMode;
    private int mColorMode;
    private int mFittingMode;
    private int mOrientation;
    private int mCopies;

    PrintAttributes() {
        /* hide constructor */
    }

    private PrintAttributes(Parcel parcel) {
        mMediaSize = (parcel.readInt() ==  1) ? MediaSize.createFromParcel(parcel) : null;
        mResolution = (parcel.readInt() ==  1) ? Resolution.createFromParcel(parcel) : null;
        mMargins = (parcel.readInt() ==  1) ? Margins.createFromParcel(parcel) : null;
        mInputTray = (parcel.readInt() ==  1) ? Tray.createFromParcel(parcel) : null;
        mOutputTray = (parcel.readInt() ==  1) ? Tray.createFromParcel(parcel) : null;
        mDuplexMode = parcel.readInt();
        mColorMode = parcel.readInt();
        mFittingMode = parcel.readInt();
        mOrientation = parcel.readInt();
        mCopies = parcel.readInt();
    }

    /**
     * Gets the media size.
     *
     * @return The media size or <code>null</code> if not set.
     */
    public MediaSize getMediaSize() {
        return mMediaSize;
    }

    /**
     * Sets the media size.
     *
     * @param The media size.
     *
     * @hide
     */
    public void setMediaSize(MediaSize mediaSize) {
        mMediaSize = mediaSize;
    }

    /**
     * Gets the resolution.
     *
     * @return The resolution or <code>null</code> if not set.
     */
    public Resolution getResolution() {
        return mResolution;
    }

    /**
     * Sets the resolution.
     *
     * @param The resolution.
     *
     * @hide
     */
    public void setResolution(Resolution resolution) {
        mResolution = resolution;
    }

    /**
     * Gets the margins.
     *
     * @return The margins or <code>null</code> if not set.
     */
    public Margins getMargins() {
        return mMargins;
    }

    /**
     * Sets the margins.
     *
     * @param The margins.
     *
     * @hide
     */
    public void setMargins(Margins margins) {
        mMargins = margins;
    }

    /**
     * Sets the input tray.
     *
     * @return The input tray or <code>null</code> if not set.
     */
    public Tray getInputTray() {
        return mInputTray;
    }

    /**
     * Gets the input tray.
     *
     * @param The input tray.
     *
     * @hide
     */
    public void setInputTray(Tray inputTray) {
        mInputTray = inputTray;
    }

    /**
     * Gets the output tray.
     *
     * @return The output tray or <code>null</code> if not set.
     */
    public Tray getOutputTray() {
        return mOutputTray;
    }

    /**
     * Sets the output tray.
     *
     * @param The output tray.
     *
     * @hide
     */
    public void setOutputTray(Tray outputTray) {
        mOutputTray = outputTray;
    }

    /**
     * Gets the duplex mode.
     *
     * @return The duplex mode or zero if not set.
     *
     * @see #DUPLEX_MODE_NONE
     * @see #DUPLEX_MODE_SHORT_EDGE
     * @see #DUPLEX_MODE_LONG_EDGE
     */
    public int getDuplexMode() {
        return mDuplexMode;
    }

    /**
     * Sets the duplex mode.
     *
     * @param The duplex mode.
     *
     * @hide
     */
    public void setDuplexMode(int duplexMode) {
        enforceValidDuplexMode(duplexMode);
        mDuplexMode = duplexMode;
    }

    /**
     * Gets the color mode.
     *
     * @return The color mode or zero if not set.
     *
     * @see #COLOR_MODE_COLOR
     * @see #COLOR_MODE_MONOCHROME
     */
    public int getColorMode() {
        return mColorMode;
    }

    /**
     * Sets the color mode.
     *
     * @param The color mode.
     *
     * @see #COLOR_MODE_MONOCHROME
     * @see #COLOR_MODE_COLOR
     *
     * @hide
     */
    public void setColorMode(int colorMode) {
        enforceValidColorMode(colorMode);
        mColorMode = colorMode;
    }

    /**
     * Gets the fitting mode.
     *
     * @return The fitting mode or zero if not set.
     *
     * @see #FITTING_MODE_NONE
     * @see #FITTING_MODE_FIT_TO_PAGE
     */
    public int getFittingMode() {
        return mFittingMode;
    }

    /**
     * Sets the fitting mode.
     *
     * @param The fitting mode.
     *
     * @see #FITTING_MODE_NONE
     * @see #FITTING_MODE_FIT_TO_PAGE
     *
     * @hide
     */
    public void setFittingMode(int fittingMode) {
        enfoceValidFittingMode(fittingMode);
        mFittingMode = fittingMode;
    }

    /**
     * Gets the orientation.
     *
     * @return The orientation or zero if not set.
     *
     * @see #ORIENTATION_PORTRAIT
     * @see #ORIENTATION_LANDSCAPE
     */
    public int getOrientation() {
        return mOrientation;
    }

    /**
     * Sets the orientation.
     *
     * @param The orientation.
     *
     * @see #ORIENTATION_PORTRAIT
     * @see #ORIENTATION_LANDSCAPE
     *
     * @hide
     */
    public void setOrientation(int orientation) {
        enforceValidOrientation(orientation);
        mOrientation = orientation;
    }

    /**
     * Gets the number of copies.
     *
     * @return The number of copies or zero if not set.
     */
    public int getCopies() {
        return mCopies;
    }

    /**
     * Sets the number of copies.
     *
     * @param copyCount The number of copies.
     *
     * @hide
     */
    public void setCopies(int copyCount) {
        if (copyCount < 1) {
            throw new IllegalArgumentException("Copies must be more than one.");
        }
        mCopies = copyCount;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        if (mMediaSize != null) {
            parcel.writeInt(1);
            mMediaSize.writeToParcel(parcel);
        } else {
            parcel.writeInt(0);
        }
        if (mResolution != null) {
            parcel.writeInt(1);
            mResolution.writeToParcel(parcel);
        } else {
            parcel.writeInt(0);
        }
        if (mMargins != null) {
            parcel.writeInt(1);
            mMargins.writeToParcel(parcel);
        } else {
            parcel.writeInt(0);
        }
        if (mInputTray != null) {
            parcel.writeInt(1);
            mInputTray.writeToParcel(parcel);
        } else {
            parcel.writeInt(0);
        }
        if (mOutputTray != null) {
            parcel.writeInt(1);
            mOutputTray.writeToParcel(parcel);
        } else {
            parcel.writeInt(0);
        }
        parcel.writeInt(mDuplexMode);
        parcel.writeInt(mColorMode);
        parcel.writeInt(mFittingMode);
        parcel.writeInt(mOrientation);
        parcel.writeInt(mCopies);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** hide */
    public void clear() {
        mMediaSize = null;
        mResolution = null;
        mMargins = null;
        mInputTray = null;
        mOutputTray = null;
        mDuplexMode = 0;
        mColorMode = 0;
        mFittingMode = 0;
        mOrientation = 0;
        mCopies = 0;
    }

    /**
     * This class specifies a supported media size.
     */
    public static final class MediaSize {

        // TODO: Verify media sizes and add more standard ones.

        // ISO sizes

        /**
         * ISO A0 media size: 841mm x 1189mm (33.11" x 46.81")
         *
         * @see #createMediaSize(PackageManager, int)
         */
        public static final int ISO_A0 = 1;

        /**
         * ISO A1 media size: 594mm x 841mm (23.39" x 33.11")
         *
         * @see #createMediaSize(PackageManager, int)
         */
        public static final int ISO_A1 = 2;

        /**
         *
         *ISO A2 media size: 420mm x 594mm (16.54" x 23.39")
         *
         * @see #createMediaSize(PackageManager, int)
         */
        public static final int ISO_A2 = 3;

        /**
         * ISO A3 media size: 297mm x 420mm (11.69" x 16.54")
         *
         * @see #createMediaSize(PackageManager, int)
         */
        public static final int ISO_A3 = 4;

        /**
         * ISO A4 media size: 210mm x 297mm (8.27" x 11.69")
         *
         * @see #createMediaSize(PackageManager, int)
         */
        public static final int ISO_A4 = 5;

        /**
         * ISO A5 media size: 148mm x 210mm (5.83" x 8.27")
         *
         * @see #createMediaSize(PackageManager, int)
         */
        public static final int ISO_A5 = 6;

        /**
         * ISO A6 media size: 105mm x 148mm (4.13" x 5.83")
         *
         * @see #createMediaSize(PackageManager, int)
         */
        public static final int ISO_A6 = 7;

        /**
         * ISO A7 media size: 74mm x 105mm (2.91" x 4.13")
         *
         * @see #createMediaSize(PackageManager, int)
         */
        public static final int ISO_A7 = 8;

        /**
         * ISO A8 media size: 52mm x 74mm (2.05" x 2.91")
         *
         * @see #createMediaSize(PackageManager, int)
         */
        public static final int ISO_A8 = 9;

        /**
         * ISO A9 media size: 37mm x 52mm (1.46" x 2.05")
         *
         * @see #createMediaSize(PackageManager, int)
         */
        public static final int ISO_A9 = 10;

        /**
         * ISO A10 media size: 26mm x 37mm (1.02" x 1.46")
         *
         * @see #createMediaSize(PackageManager, int)
         */
        public static final int ISO_A10 = 11;


        /**
         * ISO B0 media size: 1000mm x 1414mm (39.37" x 55.67")
         *
         * @see #createMediaSize(PackageManager, int)
         */
        public static final int ISO_B0 = 100;

        /**
         * ISO B1 media size: 707mm x 1000mm (27.83" x 39.37")
         *
         * @see #createMediaSize(PackageManager, int)
         */
        public static final int ISO_B1 = 101;

        /**
         * ISO B2 media size: 500mm x 707mm (19.69" x 27.83")
         *
         * @see #createMediaSize(PackageManager, int)
         */
        public static final int ISO_B2 = 102;

        /**
         * ISO B3 media size: 353mm x 500mm (13.90" x 19.69")
         *
         * @see #createMediaSize(PackageManager, int)
         */
        public static final int ISO_B3 = 103;

        /**
         * ISO B4 media size: 250mm x 353mm (9.84" x 13.90")
         *
         * @see #createMediaSize(PackageManager, int)
         */
        public static final int ISO_B4 = 104;

        /**
         * ISO B5 media size: 176mm x 250mm (6.93" x 9.84")
         *
         * @see #createMediaSize(PackageManager, int)
         */
        public static final int ISO_B5 = 105;

        /**
         * ISO B6 media size: 125mm x 176mm (4.92" x 6.93")
         *
         * @see #createMediaSize(PackageManager, int)
         */
        public static final int ISO_B6 = 106;

        /**
         * ISO B7 media size: 88mm x 125mm (3.46" x 4.92")
         *
         * @see #createMediaSize(PackageManager, int)
         */
        public static final int ISO_B7 = 107;

        /** ISO B8 media size: 62mm x 88mm (2.44" x 3.46")
         *
         * @see #createMediaSize(PackageManager, int)
         */
        public static final int ISO_B8 = 108;

        /**
         * ISO B9 media size: 44mm x 62mm (1.73" x 2.44")
         *
         * @see #createMediaSize(PackageManager, int)
         */
        public static final int ISO_B9 = 109;

        /**
         * ISO B10 media size: 31mm x 44mm (1.22" x 1.73")
         *
         * @see #createMediaSize(PackageManager, int)
         */
        public static final int ISO_B10 = 110;


        /**
         * ISO C0 media size: 917mm x 1297mm (36.10" x 51.06")
         *
         * @see #createMediaSize(PackageManager, int)
         */
        public static final int ISO_C0 = 200;

        /**
         * ISO C1 media size: 648mm x 917mm (25.51" x 36.10")
         *
         * @see #createMediaSize(PackageManager, int)
         */

        public static final int ISO_C1 = 201;
        /**
         * ISO C2 media size: 458mm x 648mm (18.03" x 25.51")
         *
         * @see #createMediaSize(PackageManager, int)
         */
        public static final int ISO_C2 = 202;

        /**
         * ISO C3 media size: 324mm x 458mm (12.76" x 18.03")
         *
         * @see #createMediaSize(PackageManager, int)
         */
        public static final int ISO_C3 = 203;

        /**
         * ISO C4 media size: 229mm x 324mm (9.02" x 12.76")
         *
         * @see #createMediaSize(PackageManager, int)
         */
        public static final int ISO_C4 = 204;

        /**
         * ISO C5 media size: 162mm x 229mm (6.38" x 9.02")
         *
         * @see #createMediaSize(PackageManager, int)
         */
        public static final int ISO_C5 = 205;

        /**
         * ISO C6 media size: 114mm x 162mm (4.49" x 6.38")
         *
         * @see #createMediaSize(PackageManager, int)
         */
        public static final int ISO_C6 = 206;

        /**
         * ISO C7 media size: 81mm x 114mm (3.19" x 4.49")
         *
         * @see #createMediaSize(PackageManager, int)
         */
        public static final int ISO_C7 = 207;

        /**
         * ISO C8 media size: 57mm x 81mm (2.24" x 3.19")
         *
         * @see #createMediaSize(PackageManager, int)
         */
        public static final int ISO_C8 = 208;

        /**
         * ISO C9 media size: 40mm x 57mm (1.57" x 2.24")
         *
         * @see #createMediaSize(PackageManager, int)
         */
        public static final int ISO_C9 = 209;

        /**
         * ISO C10 media size: 28mm x 40mm (1.10" x 1.57")
         *
         * @see #createMediaSize(PackageManager, int)
         */
        public static final int ISO_C10 = 210;


        // North America

        /**
         * North America Letter media size: 8.5" x 11"
         *
         * @see #createMediaSize(PackageManager, int)
         */
        public static final int NA_LETTER = 300;

        /**
         * North America Government-Letter media size: 8.0" x 10.5"
         *
         * @see #createMediaSize(PackageManager, int)
         */
        public static final int NA_GOVT_LETTER = 301;

        /**
         * North America Legal media size: 8.5" x 14"
         *
         * @see #createMediaSize(PackageManager, int)
         */
        public static final int NA_LEGAL = 302;

        /**
         * North America Junior Legal media size: 8.0" x 5.0"
         *
         * @see #createMediaSize(PackageManager, int)
         */
        public static final int NA_JUNIOR_LEGAL = 303;

        /**
         * North America Ledger media size: 17" x 11"
         *
         * @see #createMediaSize(PackageManager, int)
         */
        public static final int NA_LEDGER = 304;

        /**
         * North America Tabloid media size: 11" x 17"
         *
         * @see #createMediaSize(PackageManager, int)
         */
        public static final int NA_TBLOID = 305;

        /**
         * Creates a standard media size with a localized label.
         *
         * @param pm Package manager used to load the label.
         * @param mediaSize Media size constant.
         * @return A {@link MediaSize} instance with a localized label.
         */
        public static MediaSize createMediaSize(PackageManager pm, int mediaSize) {
            final Resources resources;
            try {
                resources = pm.getResourcesForApplication("android");
            } catch (NameNotFoundException nnfe) {
                return null;
            }
            switch (mediaSize) {
                case ISO_A0: {
                    return new MediaSize("ISO_A0", resources
                            .getString(R.string.mediaSize_iso_a0), 33110, 46810);
                }
                case ISO_A1: {
                    return new MediaSize("ISO_A1", resources
                            .getString(R.string.mediaSize_iso_a1), 23390, 33110);
                }
                case ISO_A2: {
                    return new MediaSize("ISO_A2", resources
                            .getString(R.string.mediaSize_iso_a2), 16540, 23390);
                }
                case ISO_A3: {
                    return new MediaSize("ISO_A3", resources
                            .getString(R.string.mediaSize_iso_a3), 11690, 16540);
                }
                case ISO_A4: {
                    return new MediaSize("ISO_A4", resources
                            .getString(R.string.mediaSize_iso_a4), 8270, 11690);
                }
                case ISO_A5: {
                    return new MediaSize("ISO_A5", resources
                            .getString(R.string.mediaSize_iso_a5), 5830, 8270);
                }
                case ISO_A6: {
                    return new MediaSize("ISO_A6", resources
                            .getString(R.string.mediaSize_iso_a6), 4130, 5830);
                }
                case ISO_A7: {
                    return new MediaSize("ISO_A7", resources
                            .getString(R.string.mediaSize_iso_a7), 2910, 4130);
                }
                case ISO_A8: {
                    return new MediaSize("ISO_A8", resources
                            .getString(R.string.mediaSize_iso_a8), 2050, 2910);
                }
                case ISO_A9: {
                    return new MediaSize("ISO_A9", resources
                            .getString(R.string.mediaSize_iso_a9), 1460, 2050);
                }
                case ISO_A10: {
                    return new MediaSize("ISO_A10", resources
                            .getString(R.string.mediaSize_iso_a10), 1020, 1460);
                }
                case ISO_B0: {
                    return new MediaSize("ISO_B0", resources
                            .getString(R.string.mediaSize_iso_b0), 39370, 55670);
                }
                case ISO_B1: {
                    return new MediaSize("ISO_B1", resources
                            .getString(R.string.mediaSize_iso_b1), 27830, 39370);
                }
                case ISO_B2: {
                    return new MediaSize("ISO_B2", resources
                            .getString(R.string.mediaSize_iso_b2), 19690, 27830);
                }
                case ISO_B3: {
                    return new MediaSize("ISO_B3", resources
                            .getString(R.string.mediaSize_iso_b3), 13900, 19690);
                }
                case ISO_B4: {
                    return new MediaSize("ISO_B4", resources
                            .getString(R.string.mediaSize_iso_b4), 9840, 13900);
                }
                case ISO_B5: {
                    return new MediaSize("ISO_B5", resources
                            .getString(R.string.mediaSize_iso_b5), 6930, 9840);
                }
                case ISO_B6: {
                    return new MediaSize("ISO_B6", resources
                            .getString(R.string.mediaSize_iso_b6), 4920, 6930);
                }
                case ISO_B7: {
                    return new MediaSize("ISO_B7", resources
                            .getString(R.string.mediaSize_iso_b7), 3460, 4920);
                }
                case ISO_B8: {
                    return new MediaSize("ISO_B8", resources
                            .getString(R.string.mediaSize_iso_b8), 2440, 3460);
                }
                case ISO_B9: {
                    return new MediaSize("ISO_B9", resources
                            .getString(R.string.mediaSize_iso_b9), 1730, 2440);
                }
                case ISO_B10: {
                    return new MediaSize("ISO_B10", resources
                            .getString(R.string.mediaSize_iso_b10), 1220, 1730);
                }
                case ISO_C0: {
                    return new MediaSize("ISO_C0", resources
                            .getString(R.string.mediaSize_iso_c0), 36100, 51060);
                }
                case ISO_C1: {
                    return new MediaSize("ISO_C1", resources
                            .getString(R.string.mediaSize_iso_c1), 25510, 36100);
                }
                case ISO_C2: {
                    return new MediaSize("ISO_C2", resources
                            .getString(R.string.mediaSize_iso_c2), 18030, 25510);
                }
                case ISO_C3: {
                    return new MediaSize("ISO_C3", resources
                            .getString(R.string.mediaSize_iso_c3), 12760, 18030);
                }
                case ISO_C4: {
                    return new MediaSize("ISO_C4", resources
                            .getString(R.string.mediaSize_iso_c4), 9020, 12760);
                }
                case ISO_C5: {
                    return new MediaSize("ISO_C5", resources
                            .getString(R.string.mediaSize_iso_c5), 6380, 9020);
                }
                case ISO_C6: {
                    return new MediaSize("ISO_C6", resources
                            .getString(R.string.mediaSize_iso_c6), 4490, 6380);
                }
                case ISO_C7: {
                    return new MediaSize("ISO_C7", resources
                            .getString(R.string.mediaSize_iso_c7), 3190, 4490);
                }
                case ISO_C8: {
                    return new MediaSize("ISO_C8", resources
                            .getString(R.string.mediaSize_iso_c8), 2240, 3190);
                }
                case ISO_C9: {
                    return new MediaSize("ISO_C9", resources
                            .getString(R.string.mediaSize_iso_c9), 1570, 2240);
                }
                case ISO_C10: {
                    return new MediaSize("ISO_C10", resources
                            .getString(R.string.mediaSize_iso_c10), 1100, 1570);
                }
                case NA_LETTER: {
                    return new MediaSize("NA_LETTER", resources
                            .getString(R.string.mediaSize_na_letter), 8500, 11000);
                }
                case NA_GOVT_LETTER: {
                    return new MediaSize("NA_GOVT_LETTER", resources
                            .getString(R.string.mediaSize_na_gvrnmt_letter), 8000, 10500);
                }
                case NA_LEGAL: {
                    return new MediaSize("NA_LEGAL", resources
                            .getString(R.string.mediaSize_na_legal), 8500, 14000);
                }
                case NA_JUNIOR_LEGAL: {
                    return new MediaSize("NA_JUNIOR_LEGAL", resources
                            .getString(R.string.mediaSize_na_junior_legal), 8000, 5000);
                }
                case NA_LEDGER: {
                    return new MediaSize("NA_LEDGER", resources
                            .getString(R.string.mediaSize_na_ledger), 17000, 11000);
                }
                case NA_TBLOID: {
                    return new MediaSize("NA_TABLOID", resources
                            .getString(R.string.mediaSize_na_tabloid), 11000, 17000);
                }
                default: {
                    throw new IllegalArgumentException("Unknown media size.");
                }
            }
        }

        private final String mId;
        private final CharSequence mLabel;
        private final int mWidthMils;
        private final int mHeightMils;

        /**
         * Creates a new instance.
         *
         * @param id The unique media size id.
         * @param label The <strong>internationalized</strong> human readable label.
         * @param widthMils The width in mils (thousands of an inch).
         * @param heightMils The height in mils (thousands of an inch).
         *
         * @throws IllegalArgumentException If the id is empty.
         * @throws IllegalArgumentException If the label is empty.
         * @throws IllegalArgumentException If the widthMils is less than or equal to zero.
         * @throws IllegalArgumentException If the heightMils is less than or equal to zero.
         */
        public MediaSize(String id, CharSequence label, int widthMils, int heightMils) {
            if (TextUtils.isEmpty(id)) {
                throw new IllegalArgumentException("id cannot be empty.");
            }
            if (TextUtils.isEmpty(label)) {
                throw new IllegalArgumentException("label cannot be empty.");
            }
            if (widthMils <= 0) {
                throw new IllegalArgumentException("widthMils "
                        + "cannot be less than or equal to zero.");
            }
            if (heightMils <= 0) {
                throw new IllegalArgumentException("heightMils "
                       + "cannot be less than or euqual to zero.");
            }
            mId = id;
            mLabel = label;
            mWidthMils = widthMils;
            mHeightMils = heightMils;
        }

        /**
         * Gets the unique media size id.
         *
         * @return The unique media size id.
         */
        public String getId() {
            return mId;
        }

        /**
         * Gets the human readable media size label.
         *
         * @return The human readable label.
         */
        public CharSequence getLabel() {
            return mLabel;
        }

        /**
         * Gets the media width in mils (thousands of an inch).
         *
         * @return The media width.
         */
        public int getWidthMils() {
            return mWidthMils;
        }

        /**
         * Gets the media height in mils (thousands of an inch).
         *
         * @return The media height.
         */
        public int getHeightMils() {
            return mHeightMils;
        }

        void writeToParcel(Parcel parcel) {
            parcel.writeString(mId);
            parcel.writeCharSequence(mLabel);
            parcel.writeInt(mWidthMils);
            parcel.writeInt(mHeightMils);
        }

        static MediaSize createFromParcel(Parcel parcel) {
            return new MediaSize(
                    parcel.readString(),
                    parcel.readCharSequence(),
                    parcel.readInt(),
                    parcel.readInt());
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append("MediaSize{");
            builder.append("id: ").append(mId);
            builder.append(", label: ").append(mLabel);
            builder.append(", heightMils: ").append(mHeightMils);
            builder.append(", widthMils: ").append(mWidthMils);
            builder.append("}");
            return builder.toString();
        }
    }

    /**
     * This class specifies a supported resolution in dpi (dots per inch).
     */
    public static final class Resolution {
        private final String mId;
        private final CharSequence mLabel;
        private final int mHorizontalDpi;
        private final int mVerticalDpi;

        /**
         * Creates a new instance.
         *
         * @param id The unique resolution id.
         * @param label The <strong>internationalized</strong> human readable label.
         * @param horizontalDpi The horizontal resolution in dpi.
         * @param verticalDpi The vertical resolution in dpi.
         *
         * @throws IllegalArgumentException If the id is empty.
         * @throws IllegalArgumentException If the label is empty.
         * @throws IllegalArgumentException If the horizontalDpi is less than or equal to zero.
         * @throws IllegalArgumentException If the verticalDpi is less than or equal to zero.
         */
        public Resolution(String id, CharSequence label, int horizontalDpi, int verticalDpi) {
            if (TextUtils.isEmpty(id)) {
                throw new IllegalArgumentException("id cannot be empty.");
            }
            if (TextUtils.isEmpty(label)) {
                throw new IllegalArgumentException("label cannot be empty.");
            }
            if (horizontalDpi <= 0) {
                throw new IllegalArgumentException("horizontalDpi "
                        + "cannot be less than or equal to zero.");
            }
            if (verticalDpi <= 0) {
                throw new IllegalArgumentException("verticalDpi"
                       + " cannot be less than or equal to zero.");
            }
            mId = id;
            mLabel = label;
            mHorizontalDpi = horizontalDpi;
            mVerticalDpi = verticalDpi;
        }

        /**
         * Gets the unique resolution id.
         *
         * @return The unique resolution id.
         */
        public String getId() {
            return mId;
        }

        /**
         * Gets the resolution human readable label.
         *
         * @return The human readable label.
         */
        public CharSequence getLabel(PackageManager packageManager) {
            return mLabel;
        }

        /**
         * Gets the horizontal resolution in dpi.
         *
         * @return The horizontal resolution.
         */
        public int getHorizontalDpi() {
            return mHorizontalDpi;
        }

        /**
         * Gets the vertical resolution in dpi.
         *
         * @return The vertical resolution.
         */
        public int getVerticalDpi() {
            return mVerticalDpi;
        }

        void writeToParcel(Parcel parcel) {
            parcel.writeString(mId);
            parcel.writeCharSequence(mLabel);
            parcel.writeInt(mHorizontalDpi);
            parcel.writeInt(mVerticalDpi);
        }

        static Resolution createFromParcel(Parcel parcel) {
            return new Resolution(
                    parcel.readString(),
                    parcel.readCharSequence(),
                    parcel.readInt(),
                    parcel.readInt());
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append("Resolution{");
            builder.append("id: ").append(mId);
            builder.append(", label: ").append(mLabel);
            builder.append(", horizontalDpi: ").append(mHorizontalDpi);
            builder.append(", verticalDpi: ").append(mVerticalDpi);
            builder.append("}");
            return builder.toString();
        }
    }

    /**
     * This class specifies content margins.
     */
    public static final class Margins {
        private final int mLeftMils;
        private final int mTopMils;
        private final int mRightMils;
        private final int mBottomMils;

        /**
         * Creates a new instance.
         *
         * @param leftMils The left margin in mils (thousands of an inch).
         * @param topMils The top margin in mils (thousands of an inch).
         * @param rightMils The right margin in mils (thousands of an inch).
         * @param bottomMils The bottom margin in mils (thousands of an inch).
         *
         * @throws IllegalArgumentException If the leftMils is less than zero.
         * @throws IllegalArgumentException If the topMils is less than zero.
         * @throws IllegalArgumentException If the rightMils is less than zero.
         * @throws IllegalArgumentException If the bottomMils is less than zero.
         */
        public Margins(int leftMils, int topMils, int rightMils, int bottomMils) {
            if (leftMils < 0) {
                throw new IllegalArgumentException("leftMils cannot be less than zero.");
            }
            if (topMils < 0) {
                throw new IllegalArgumentException("topMils cannot be less than zero.");
            }
            if (rightMils < 0) {
                throw new IllegalArgumentException("rightMils cannot be less than zero.");
            }
            if (bottomMils < 0) {
                throw new IllegalArgumentException("bottomMils cannot be less than zero.");
            }
            mTopMils = topMils;
            mLeftMils = leftMils;
            mRightMils = rightMils;
            mBottomMils = bottomMils;
        }

        /**
         * Gets the left margin in mils (thousands of an inch).
         *
         * @return The left margin.
         */
        public int getLeftMils() {
            return mLeftMils;
        }

        /**
         * Gets the top margin in mils (thousands of an inch).
         *
         * @return The top margin.
         */
        public int getTopMils() {
            return mTopMils;
        }

        /**
         * Gets the right margin in mils (thousands of an inch).
         *
         * @return The right margin.
         */
        public int getRightMils() {
            return mRightMils;
        }

        /**
         * Gets the bottom margin in mils (thousands of an inch).
         *
         * @return The bottom margin.
         */
        public int getBottomMils() {
            return mBottomMils;
        }

        void writeToParcel(Parcel parcel) {
            parcel.writeInt(mLeftMils);
            parcel.writeInt(mTopMils);
            parcel.writeInt(mRightMils);
            parcel.writeInt(mBottomMils);
        }

        static Margins createFromParcel(Parcel parcel) {
            return new Margins(
                    parcel.readInt(),
                    parcel.readInt(),
                    parcel.readInt(),
                    parcel.readInt());
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append("Margins{");
            builder.append("leftMils: ").append(mLeftMils);
            builder.append(", topMils: ").append(mTopMils);
            builder.append(", rightMils: ").append(mRightMils);
            builder.append(", bottomMils: ").append(mBottomMils);
            builder.append("}");
            return builder.toString();
        }
    }

    /**
     * Represents a printer tray.
     */
    public static final class Tray {
        private final String mId;
        private final CharSequence mLabel;

        /**
         * Creates a new instance.
         *
         * @param id The unique tray id.
         * @param label The <strong>internationalized</strong> human readable label.
         *
         * @throws IllegalArgumentException If the id is empty.
         * @throws IllegalArgumentException If the label is empty.
         */
        public Tray(String id, CharSequence label) {
            if (TextUtils.isEmpty(id)) {
                throw new IllegalArgumentException("id cannot be empty.");
            }
            if (TextUtils.isEmpty(label)) {
                throw new IllegalArgumentException("label cannot be empty.");
            }
            mId = id;
            mLabel = label;
        }

        /**
         * Gets the unique tray id.
         *
         * @return The unique tray id.
         */
        public String getId() {
            return mId;
        }

        /**
         * Gets the tray human readable label.
         *
         * @return The human readable label.
         */
        public CharSequence getLabel(PackageManager packageManager) {
            return mLabel;
        }

        void writeToParcel(Parcel parcel) {
            parcel.writeString(mId);
            parcel.writeCharSequence(mLabel);
        }

        static Tray createFromParcel(Parcel parcel) {
            return new Tray(
                    parcel.readString(),
                    parcel.readCharSequence());
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append("Tray{");
            builder.append("id: ").append(mId);
            builder.append("id: ").append(mId);
            builder.append(", label: ").append(mLabel);
            builder.append("}");
            return builder.toString();
        }
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("PrintAttributes{");
        builder.append("mediaSize: ").append(mMediaSize);
        builder.append(", resolution: ").append(mResolution);
        builder.append(", margins: ").append(mMargins);
        builder.append(", duplexMode: ").append(duplexModeToString(mDuplexMode));
        builder.append(", colorMode: ").append(colorModeToString(mColorMode));
        builder.append(", fittingMode: ").append(fittingModeToString(mFittingMode));
        builder.append(", orientation: ").append(orientationToString(mOrientation));
        builder.append(", copies: ").append(mCopies);
        return builder.toString();
    }

    private static String duplexModeToString(int duplexMode) {
        switch (duplexMode) {
            case DUPLEX_MODE_NONE: {
                return "DUPLEX_MODE_NONE";
            }
            case DUPLEX_MODE_LONG_EDGE: {
                return "DUPLEX_MODE_LONG_EDGE";
            }
            case DUPLEX_MODE_SHORT_EDGE: {
                return "DUPLEX_MODE_SHORT_EDGE";
            }
            default:
                return "DUPLEX_MODE_UNKNOWN";
        }
    }

    private static String colorModeToString(int colorMode) {
        switch (colorMode) {
            case COLOR_MODE_MONOCHROME: {
                return "COLOR_MODE_MONOCHROME";
            }
            case COLOR_MODE_COLOR: {
                return "COLOR_MODE_COLOR";
            }
            default:
                return "COLOR_MODE_UNKNOWN";
        }
    }

    private static String orientationToString(int orientation) {
        switch (orientation) {
            case ORIENTATION_PORTRAIT: {
                return "ORIENTATION_PORTRAIT";
            }
            case ORIENTATION_LANDSCAPE: {
                return "ORIENTATION_LANDSCAPE";
            }
            default:
                return "ORIENTATION_UNKNOWN";
        }
    }

    private static String fittingModeToString(int fittingMode) {
        switch (fittingMode) {
            case FITTING_MODE_NONE: {
                return "FITTING_MODE_NONE";
            }
            case FITTING_MODE_FIT_TO_PAGE: {
                return "FITTING_MODE_FIT_TO_PAGE";
            }
            default:
                return "FITTING_MODE_UNKNOWN";
        }
    }

    static void enforceValidDuplexMode(int duplexMode) {
        if ((duplexMode & VALID_DUPLEX_MODES) == 0) {
            throw new IllegalArgumentException("invalid duplex mode: " + duplexMode);
        }
    }

    static void enforceValidColorMode(int colorMode) {
        if ((colorMode & VALID_COLOR_MODES) == 0) {
            throw new IllegalArgumentException("invalid color mode: " + colorMode);
        }
    }

    static void enfoceValidFittingMode(int fittingMode) {
        if ((fittingMode & VALID_FITTING_MODES) == 0) {
            throw new IllegalArgumentException("invalid fitting mode: " + fittingMode);
        }
    }

    static void enforceValidOrientation(int orientation) {
        if ((orientation & VALID_ORIENTATIONS) == 0) {
            throw new IllegalArgumentException("invalid orientation: " + orientation);
        }
    }

    /**
     * Builder for creating {@link PrintAttributes}.
     */
    public static final class Builder {
        private final PrintAttributes mAttributes = new PrintAttributes();

        /**
         * Sets the media size.
         *
         * @param mediaSize The media size.
         * @return This builder.
         */
        public Builder setMediaSize(MediaSize mediaSize) {
            mAttributes.setMediaSize(mediaSize);
            return this;
        }

        /**
         * Sets the resolution.
         *
         * @param resolution The resolution.
         * @return This builder.
         */
        public Builder setResolution(Resolution resolution) {
            mAttributes.setResolution(resolution);
            return this;
        }

        /**
         * Sets the margins.
         *
         * @param margins The margins.
         * @return This builder.
         */
        public Builder setMargins(Margins margins) {
            mAttributes.setMargins(margins);
            return this;
        }

        /**
         * Sets the input tray.
         *
         * @param inputTray The tray.
         * @return This builder.
         */
        public Builder setInputTray(Tray inputTray) {
            mAttributes.setInputTray(inputTray);
            return this;
        }

        /**
         * Sets the output tray.
         *
         * @param outputTray The tray.
         * @return This builder.
         */
        public Builder setOutputTray(Tray outputTray) {
            mAttributes.setOutputTray(outputTray);
            return this;
        }

        /**
         * Sets the duplex mode.
         *
         * @param duplexMode A valid duplex mode or zero.
         * @return This builder.
         *
         * @see PrintAttributes#DUPLEX_MODE_NONE
         * @see PrintAttributes#DUPLEX_MODE_SHORT_EDGE
         * @see PrintAttributes#DUPLEX_MODE_LONG_EDGE
         */
        public Builder setDuplexMode(int duplexMode) {
            if (Integer.bitCount(duplexMode) != 1) {
                throw new IllegalArgumentException("can specify at most one duplexMode bit.");
            }
            mAttributes.setDuplexMode(duplexMode);
            return this;
        }

        /**
         * Sets the color mode.
         *
         * @param colorMode A valid color mode or zero.
         * @return This builder.
         *
         * @see PrintAttributes#COLOR_MODE_MONOCHROME
         * @see PrintAttributes#COLOR_MODE_COLOR
         */
        public Builder setColorMode(int colorMode) {
            if (Integer.bitCount(colorMode) > 1) {
                throw new IllegalArgumentException("can specify at most one colorMode bit.");
            }
            mAttributes.setColorMode(colorMode);
            return this;
        }

        /**
         * Sets the fitting mode.
         *
         * @param fittingMode A valid fitting mode or zero.
         * @return This builder.
         *
         * @see PrintAttributes#FITTING_MODE_NONE
         * @see PrintAttributes#FITTING_MODE_FIT_TO_PAGE
         */
        public Builder setFittingMode(int fittingMode) {
            if (Integer.bitCount(fittingMode) > 1) {
                throw new IllegalArgumentException("can specify at most one fittingMode bit.");
            }
            mAttributes.setFittingMode(fittingMode);
            return this;
        }

        /**
         * Sets the orientation.
         *
         * @param orientation A valid orientation or zero.
         * @return This builder.
         *
         * @see PrintAttributes#ORIENTATION_PORTRAIT
         * @see PrintAttributes#ORIENTATION_LANDSCAPE
         */
        public Builder setOrientation(int orientation) {
            if (Integer.bitCount(orientation) > 1) {
                throw new IllegalArgumentException("can specify at most one orientation bit.");
            }
            mAttributes.setOrientation(orientation);
            return this;
        }

        /**
         * Sets the number of copies.
         *
         * @param copyCount A greater or equal to zero copy count.
         * @return This builder.
         */
        public Builder setCopyCount(int copyCount) {
            mAttributes.setCopies(copyCount);
            return this;
        }

        /**
         * Creates a new {@link PrintAttributes} instance.
         *
         * @return The new instance.
         */
        public PrintAttributes create() {
            return mAttributes;
        }
    }

    public static final Parcelable.Creator<PrintAttributes> CREATOR =
            new Creator<PrintAttributes>() {
        @Override
        public PrintAttributes createFromParcel(Parcel parcel) {
            return new PrintAttributes(parcel);
        }

        @Override
        public PrintAttributes[] newArray(int size) {
            return new PrintAttributes[size];
        }
    };
}
