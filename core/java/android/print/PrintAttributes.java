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
import android.content.res.Resources.NotFoundException;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.R;

/**
 * This class represents the attributes of a print job.
 */
public final class PrintAttributes implements Parcelable {

    /** Color mode: Monochrome color scheme, e.g. one color is used. */
    public static final int COLOR_MODE_MONOCHROME = 1 << 0;
    /** Color mode: Color color scheme, e.g. many colors are used. */
    public static final int COLOR_MODE_COLOR = 1 << 1;

    private static final int VALID_COLOR_MODES =
            COLOR_MODE_MONOCHROME | COLOR_MODE_COLOR;

    private MediaSize mMediaSize;
    private Resolution mResolution;
    private Margins mMargins;

    private int mColorMode;

    PrintAttributes() {
        /* hide constructor */
    }

    private PrintAttributes(Parcel parcel) {
        mMediaSize = (parcel.readInt() ==  1) ? MediaSize.createFromParcel(parcel) : null;
        mResolution = (parcel.readInt() ==  1) ? Resolution.createFromParcel(parcel) : null;
        mMargins = (parcel.readInt() ==  1) ? Margins.createFromParcel(parcel) : null;
        mColorMode = parcel.readInt();
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
        parcel.writeInt(mColorMode);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + mColorMode;
        result = prime * result + ((mMargins == null) ? 0 : mMargins.hashCode());
        result = prime * result + ((mMediaSize == null) ? 0 : mMediaSize.hashCode());
        result = prime * result + ((mResolution == null) ? 0 : mResolution.hashCode());
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
        PrintAttributes other = (PrintAttributes) obj;
        if (mColorMode != other.mColorMode) {
            return false;
        }
        if (mMargins == null) {
            if (other.mMargins != null) {
                return false;
            }
        } else if (!mMargins.equals(other.mMargins)) {
            return false;
        }
        if (mMediaSize == null) {
            if (other.mMediaSize != null) {
                return false;
            }
        } else if (!mMediaSize.equals(other.mMediaSize)) {
            return false;
        }
        if (mResolution == null) {
            if (other.mResolution != null) {
                return false;
            }
        } else if (!mResolution.equals(other.mResolution)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("PrintAttributes{");
        builder.append("mediaSize: ").append(mMediaSize);
        builder.append(", resolution: ").append(mResolution);
        builder.append(", margins: ").append(mMargins);
        builder.append(", colorMode: ").append(colorModeToString(mColorMode));
        builder.append("}");
        return builder.toString();
    }

    /** hide */
    public void clear() {
        mMediaSize = null;
        mResolution = null;
        mMargins = null;
        mColorMode = 0;
    }

    /**
     * @hide
     */
    public void copyFrom(PrintAttributes other) {
        mMediaSize = other.mMediaSize;
        mResolution = other.mResolution;
        mMargins = other.mMargins;
        mColorMode = other.mColorMode;
    }

    /**
     * This class specifies a supported media size.
     */
    public static final class MediaSize {
        private static final String LOG_TAG = "MediaSize";

        // TODO: Verify media sizes and add more standard ones.

        // ISO sizes

        /** ISO A0 media size: 841mm x 1189mm (33.11" x 46.81") */
        public static final MediaSize ISO_A0 =
                new MediaSize("ISO_A0", "android", R.string.mediaSize_iso_a0, 33110, 46810);
        /** ISO A1 media size: 594mm x 841mm (23.39" x 33.11") */
        public static final MediaSize ISO_A1 =
                new MediaSize("ISO_A1", "android", R.string.mediaSize_iso_a1, 23390, 33110);
        /** ISO A2 media size: 420mm x 594mm (16.54" x 23.39") */
        public static final MediaSize ISO_A2 =
                new MediaSize("ISO_A2", "android", R.string.mediaSize_iso_a2, 16540, 23390);
        /** ISO A3 media size: 297mm x 420mm (11.69" x 16.54") */
        public static final MediaSize ISO_A3 =
                new MediaSize("ISO_A3", "android", R.string.mediaSize_iso_a3, 11690, 16540);
        /** ISO A4 media size: 210mm x 297mm (8.27" x 11.69") */
        public static final MediaSize ISO_A4 =
                new MediaSize("ISO_A4", "android", R.string.mediaSize_iso_a4, 8270, 11690);
        /** ISO A5 media size: 148mm x 210mm (5.83" x 8.27") */
        public static final MediaSize ISO_A5 =
                new MediaSize("ISO_A5", "android", R.string.mediaSize_iso_a5, 5830, 8270);
        /** ISO A6 media size: 105mm x 148mm (4.13" x 5.83") */
        public static final MediaSize ISO_A6 =
                new MediaSize("ISO_A6", "android", R.string.mediaSize_iso_a6, 4130, 5830);
        /** ISO A7 media size: 74mm x 105mm (2.91" x 4.13") */
        public static final MediaSize ISO_A7 =
                new MediaSize("ISO_A7", "android", R.string.mediaSize_iso_a7, 2910, 4130);
        /** ISO A8 media size: 52mm x 74mm (2.05" x 2.91") */
        public static final MediaSize ISO_A8 =
                new MediaSize("ISO_A8", "android", R.string.mediaSize_iso_a8, 2050, 2910);
        /** ISO A9 media size: 37mm x 52mm (1.46" x 2.05") */
        public static final MediaSize ISO_A9 =
                new MediaSize("ISO_A9", "android", R.string.mediaSize_iso_a9, 1460, 2050);
        /** ISO A10 media size: 26mm x 37mm (1.02" x 1.46") */
        public static final MediaSize ISO_A10 =
                new MediaSize("ISO_A10", "android", R.string.mediaSize_iso_a10, 1020, 1460);

        /** ISO B0 media size: 1000mm x 1414mm (39.37" x 55.67") */
        public static final MediaSize ISO_B0 =
                new MediaSize("ISO_B0", "android", R.string.mediaSize_iso_b0, 39370, 55670);
        /** ISO B1 media size: 707mm x 1000mm (27.83" x 39.37") */
        public static final MediaSize ISO_B1 =
                new MediaSize("ISO_B1", "android", R.string.mediaSize_iso_b1, 27830, 39370);
        /** ISO B2 media size: 500mm x 707mm (19.69" x 27.83") */
        public static final MediaSize ISO_B2 =
                new MediaSize("ISO_B2", "android", R.string.mediaSize_iso_b2, 19690, 27830);
        /** ISO B3 media size: 353mm x 500mm (13.90" x 19.69") */
        public static final MediaSize ISO_B3 =
                new MediaSize("ISO_B3", "android", R.string.mediaSize_iso_b3, 13900, 19690);
        /** ISO B4 media size: 250mm x 353mm (9.84" x 13.90") */
        public static final MediaSize ISO_B4 =
                new MediaSize("ISO_B4", "android", R.string.mediaSize_iso_b4, 9840, 13900);
        /** ISO B5 media size: 176mm x 250mm (6.93" x 9.84") */
        public static final MediaSize ISO_B5 =
                new MediaSize("ISO_B5", "android", R.string.mediaSize_iso_b5, 6930, 9840);
        /** ISO B6 media size: 125mm x 176mm (4.92" x 6.93") */
        public static final MediaSize ISO_B6 =
                new MediaSize("ISO_B6", "android", R.string.mediaSize_iso_b6, 4920, 6930);
        /** ISO B7 media size: 88mm x 125mm (3.46" x 4.92") */
        public static final MediaSize ISO_B7 =
                new MediaSize("ISO_B7", "android", R.string.mediaSize_iso_b7, 3460, 4920);
        /** ISO B8 media size: 62mm x 88mm (2.44" x 3.46") */
        public static final MediaSize ISO_B8 =
                new MediaSize("ISO_B8", "android", R.string.mediaSize_iso_b8, 2440, 3460);
        /** ISO B9 media size: 44mm x 62mm (1.73" x 2.44") */
        public static final MediaSize ISO_B9 =
                new MediaSize("ISO_B9", "android", R.string.mediaSize_iso_b9, 1730, 2440);
        /** ISO B10 media size: 31mm x 44mm (1.22" x 1.73") */
        public static final MediaSize ISO_B10 =
                new MediaSize("ISO_B10", "android", R.string.mediaSize_iso_b10, 1220, 1730);

        /** ISO C0 media size: 917mm x 1297mm (36.10" x 51.06") */
        public static final MediaSize ISO_C0 =
                new MediaSize("ISO_C0", "android", R.string.mediaSize_iso_c0, 36100, 51060);
        /** ISO C1 media size: 648mm x 917mm (25.51" x 36.10") */
        public static final MediaSize ISO_C1 =
                new MediaSize("ISO_C1", "android", R.string.mediaSize_iso_c1, 25510, 36100);
        /** ISO C2 media size: 458mm x 648mm (18.03" x 25.51") */
        public static final MediaSize ISO_C2 =
                new MediaSize("ISO_C2", "android", R.string.mediaSize_iso_c2, 18030, 25510);
        /** ISO C3 media size: 324mm x 458mm (12.76" x 18.03") */
        public static final MediaSize ISO_C3 =
                new MediaSize("ISO_C3", "android", R.string.mediaSize_iso_c3, 12760, 18030);
        /** ISO C4 media size: 229mm x 324mm (9.02" x 12.76") */
        public static final MediaSize ISO_C4 =
                new MediaSize("ISO_C4", "android", R.string.mediaSize_iso_c4, 9020, 12760);
        /** ISO C5 media size: 162mm x 229mm (6.38" x 9.02") */
        public static final MediaSize ISO_C5 =
                new MediaSize("ISO_C5", "android", R.string.mediaSize_iso_c5, 6380, 9020);
        /** ISO C6 media size: 114mm x 162mm (4.49" x 6.38") */
        public static final MediaSize ISO_C6 =
                new MediaSize("ISO_C6", "android", R.string.mediaSize_iso_c6, 4490, 6380);
        /** ISO C7 media size: 81mm x 114mm (3.19" x 4.49") */
        public static final MediaSize ISO_C7 =
                new MediaSize("ISO_C7", "android", R.string.mediaSize_iso_c7, 3190, 4490);
        /** ISO C8 media size: 57mm x 81mm (2.24" x 3.19") */
        public static final MediaSize ISO_C8 =
                new MediaSize("ISO_C8", "android", R.string.mediaSize_iso_c8, 2240, 3190);
        /** ISO C9 media size: 40mm x 57mm (1.57" x 2.24") */
        public static final MediaSize ISO_C9 =
                new MediaSize("ISO_C9", "android", R.string.mediaSize_iso_c9, 1570, 2240);
        /** ISO C10 media size: 28mm x 40mm (1.10" x 1.57") */
        public static final MediaSize ISO_C10 =
                new MediaSize("ISO_C10", "android", R.string.mediaSize_iso_c10, 1100, 1570);

        // North America

        /** North America Letter media size: 8.5" x 11" */
        public static final MediaSize NA_LETTER =
                new MediaSize("NA_LETTER", "android", R.string.mediaSize_na_letter, 8500, 11000);
        /** North America Government-Letter media size: 8.0" x 10.5" */
        public static final MediaSize NA_GOVT_LETTER =
                new MediaSize("NA_GOVT_LETTER", "android",
                        R.string.mediaSize_na_gvrnmt_letter, 8000, 10500);
        /** North America Legal media size: 8.5" x 14" */
        public static final MediaSize NA_LEGAL =
                new MediaSize("NA_LEGAL", "android", R.string.mediaSize_na_legal, 8500, 14000);
        /** North America Junior Legal media size: 8.0" x 5.0" */
        public static final MediaSize NA_JUNIOR_LEGAL =
                new MediaSize("NA_JUNIOR_LEGAL", "android",
                        R.string.mediaSize_na_junior_legal, 8000, 5000);
        /** North America Ledger media size: 17" x 11" */
        public static final MediaSize NA_LEDGER =
                new MediaSize("NA_LEDGER", "android", R.string.mediaSize_na_ledger, 17000, 11000);
        /** North America Tabloid media size: 11" x 17" */
        public static final MediaSize NA_TBLOID =
                new MediaSize("NA_TABLOID", "android",
                        R.string.mediaSize_na_tabloid, 11000, 17000);

        private final String mId;
        /**@hide */
        public final String mLabel;
        /**@hide */
        public final String mPackageName;
        /**@hide */
        public final int mLabelResId;
        private final int mWidthMils;
        private final int mHeightMils;

        /**
         * Creates a new instance. This is the preferred constructor since
         * it enables the media size label to be shown in a localized fashion
         * on a locale change.
         *
         * @param id The unique media size id.
         * @param packageName The name of the creating package.
         * @param labelResId The resource if of a human readable label.
         * @param widthMils The width in mils (thousands of an inch).
         * @param heightMils The height in mils (thousands of an inch).
         *
         * @throws IllegalArgumentException If the id is empty.
         * @throws IllegalArgumentException If the label is empty.
         * @throws IllegalArgumentException If the widthMils is less than or equal to zero.
         * @throws IllegalArgumentException If the heightMils is less than or equal to zero.
         *
         * @hide
         */
        public MediaSize(String id, String packageName, int labelResId,
                int widthMils, int heightMils) {
            if (TextUtils.isEmpty(id)) {
                throw new IllegalArgumentException("id cannot be empty.");
            }
            if (TextUtils.isEmpty(packageName)) {
                throw new IllegalArgumentException("packageName cannot be empty.");
            }
            if (labelResId <= 0) {
                throw new IllegalArgumentException("labelResId must be greater than zero.");
            }
            if (widthMils <= 0) {
                throw new IllegalArgumentException("widthMils "
                        + "cannot be less than or equal to zero.");
            }
            if (heightMils <= 0) {
                throw new IllegalArgumentException("heightMils "
                       + "cannot be less than or euqual to zero.");
            }
            mPackageName = packageName;
            mId = id;
            mLabelResId = labelResId;
            mWidthMils = widthMils;
            mHeightMils = heightMils;
            mLabel = null;
        }

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
        public MediaSize(String id, String label, int widthMils, int heightMils) {
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
            mLabelResId = 0;
            mPackageName = null;
        }

        /** @hide */
        public MediaSize(String id, String label, String packageName,
                int widthMils, int heightMils, int labelResId) {
            mPackageName = packageName;
            mId = id;
            mLabelResId = labelResId;
            mWidthMils = widthMils;
            mHeightMils = heightMils;
            mLabel = label;
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
         * @param packageManager The package manager for loading the label.
         * @return The human readable label.
         */
        public String getLabel(PackageManager packageManager) {
            if (!TextUtils.isEmpty(mPackageName) && mLabelResId > 0) {
                try {
                    return packageManager.getResourcesForApplication(
                            mPackageName).getString(mLabelResId);
                } catch (NotFoundException nfe) {
                    Log.w(LOG_TAG, "Could not load resouce" + mLabelResId
                            + " from package " + mPackageName);
                } catch (NameNotFoundException nnfee) {
                    Log.w(LOG_TAG, "Could not load resouce" + mLabelResId
                            + " from package " + mPackageName);
                }
            }
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

        /**
         * Gets whether this media size is in portrait which is the
         * height is greater or equal to the width.
         *
         * @return True if the media size is in portrait, false if
         * it is in landscape.
         */
        public boolean isPortrait() {
            return mHeightMils >= mWidthMils;
        }

        /**
         * Returns a new media size in a portrait orientation
         * which is the height is the greater dimension.
         *
         * @return New instance in landscape orientation.
         */
        public MediaSize asPortrait() {
            return new MediaSize(mId, mLabel, mPackageName,
                    Math.min(mWidthMils, mHeightMils),
                    Math.max(mWidthMils, mHeightMils),
                    mLabelResId);
        }

        /**
         * Returns a new media size in a landscape orientation
         * which is the height is the lesser dimension.
         *
         * @return New instance in landscape orientation.
         */
        public MediaSize asLandscape() {
            return new MediaSize(mId, mLabel, mPackageName,
                    Math.max(mWidthMils, mHeightMils),
                    Math.min(mWidthMils, mHeightMils),
                    mLabelResId);
        }

        void writeToParcel(Parcel parcel) {
            parcel.writeString(mId);
            parcel.writeString(mLabel);
            parcel.writeString(mPackageName);
            parcel.writeInt(mWidthMils);
            parcel.writeInt(mHeightMils);
            parcel.writeInt(mLabelResId);
        }

        static MediaSize createFromParcel(Parcel parcel) {
            return new MediaSize(
                    parcel.readString(),
                    parcel.readString(),
                    parcel.readString(),
                    parcel.readInt(),
                    parcel.readInt(),
                    parcel.readInt());
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + mWidthMils;
            result = prime * result + mHeightMils;
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
            MediaSize other = (MediaSize) obj;
            if (mWidthMils != other.mWidthMils) {
                return false;
            }
            if (mHeightMils != other.mHeightMils) {
                return false;
            }
            return true;
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append("MediaSize{");
            builder.append("id: ").append(mId);
            builder.append(", label: ").append(mLabel);
            builder.append(", packageName: ").append(mPackageName);
            builder.append(", heightMils: ").append(mHeightMils);
            builder.append(", widthMils: ").append(mWidthMils);
            builder.append(", labelResId: ").append(mLabelResId);
            builder.append("}");
            return builder.toString();
        }
    }

    /**
     * This class specifies a supported resolution in dpi (dots per inch).
     */
    public static final class Resolution {
        private static final String LOG_TAG = "Resolution";

        private final String mId;
        /**@hide */
        public final String mLabel;
        /**@hide */
        public final String mPackageName;
        /**@hide */
        public final int mLabelResId;
        private final int mHorizontalDpi;
        private final int mVerticalDpi;

        /**
         * Creates a new instance. This is the preferred constructor since
         * it enables the resolution label to be shown in a localized fashion
         * on a locale change.
         *
         * @param id The unique resolution id.
         * @param packageName The name of the creating package.
         * @param labelResId The resource id of a human readable label.
         * @param horizontalDpi The horizontal resolution in dpi.
         * @param verticalDpi The vertical resolution in dpi.
         *
         * @throws IllegalArgumentException If the id is empty.
         * @throws IllegalArgumentException If the label is empty.
         * @throws IllegalArgumentException If the horizontalDpi is less than or equal to zero.
         * @throws IllegalArgumentException If the verticalDpi is less than or equal to zero.
         *
         * @hide
         */
        public Resolution(String id, String packageName, int labelResId,
                int horizontalDpi, int verticalDpi) {
            if (TextUtils.isEmpty(id)) {
                throw new IllegalArgumentException("id cannot be empty.");
            }
            if (TextUtils.isEmpty(packageName)) {
                throw new IllegalArgumentException("packageName cannot be empty.");
            }
            if (labelResId <= 0) {
                throw new IllegalArgumentException("labelResId must be greater than zero.");
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
            mPackageName = packageName;
            mLabelResId = labelResId;
            mHorizontalDpi = horizontalDpi;
            mVerticalDpi = verticalDpi;
            mLabel = null;
        }

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
        public Resolution(String id, String label, int horizontalDpi, int verticalDpi) {
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
            mPackageName = null;
            mLabelResId = 0;
        }

        /** @hide */
        public Resolution(String id, String label, String packageName,
                int horizontalDpi, int verticalDpi, int labelResId) {
            mId = id;
            mPackageName = packageName;
            mLabelResId = labelResId;
            mHorizontalDpi = horizontalDpi;
            mVerticalDpi = verticalDpi;
            mLabel = label;
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
         * @param packageManager The package manager for loading the label.
         * @return The human readable label.
         */
        public String getLabel(PackageManager packageManager) {
            if (!TextUtils.isEmpty(mPackageName) && mLabelResId > 0) {
                try {
                    return packageManager.getResourcesForApplication(
                            mPackageName).getString(mLabelResId);
                } catch (NotFoundException nfe) {
                    Log.w(LOG_TAG, "Could not load resouce" + mLabelResId
                            + " from package " + mPackageName);
                } catch (NameNotFoundException nnfee) {
                    Log.w(LOG_TAG, "Could not load resouce" + mLabelResId
                            + " from package " + mPackageName);
                }
            }
            return mLabel;
        }

        /**
         * Gets the vertical resolution in dpi.
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
            parcel.writeString(mLabel);
            parcel.writeString(mPackageName);
            parcel.writeInt(mHorizontalDpi);
            parcel.writeInt(mVerticalDpi);
            parcel.writeInt(mLabelResId);
        }

        static Resolution createFromParcel(Parcel parcel) {
            return new Resolution(
                    parcel.readString(),
                    parcel.readString(),
                    parcel.readString(),
                    parcel.readInt(),
                    parcel.readInt(),
                    parcel.readInt());
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + mHorizontalDpi;
            result = prime * result + mVerticalDpi;
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
            Resolution other = (Resolution) obj;
            if (mHorizontalDpi != other.mHorizontalDpi) {
                return false;
            }
            if (mVerticalDpi != other.mVerticalDpi) {
                return false;
            }
            return true;
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append("Resolution{");
            builder.append("id: ").append(mId);
            builder.append(", label: ").append(mLabel);
            builder.append(", packageName: ").append(mPackageName);
            builder.append(", horizontalDpi: ").append(mHorizontalDpi);
            builder.append(", verticalDpi: ").append(mVerticalDpi);
            builder.append(", labelResId: ").append(mLabelResId);
            builder.append("}");
            return builder.toString();
        }
    }

    /**
     * This class specifies content margins.
     */
    public static final class Margins {
        public static final Margins NO_MARGINS = new Margins(0,  0,  0,  0);

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
         */
        public Margins(int leftMils, int topMils, int rightMils, int bottomMils) {
            if (leftMils > rightMils) {
                throw new IllegalArgumentException("leftMils cannot be less than rightMils.");
            }
            if (topMils > bottomMils) {
                throw new IllegalArgumentException("topMils cannot be less than bottomMils.");
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
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + mBottomMils;
            result = prime * result + mLeftMils;
            result = prime * result + mRightMils;
            result = prime * result + mTopMils;
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
            Margins other = (Margins) obj;
            if (mBottomMils != other.mBottomMils) {
                return false;
            }
            if (mLeftMils != other.mLeftMils) {
                return false;
            }
            if (mRightMils != other.mRightMils) {
                return false;
            }
            if (mTopMils != other.mTopMils) {
                return false;
            }
            return true;
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

    static String colorModeToString(int colorMode) {
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

    static void enforceValidColorMode(int colorMode) {
        if ((colorMode & VALID_COLOR_MODES) == 0 && Integer.bitCount(colorMode) == 1) {
            throw new IllegalArgumentException("invalid color mode: " + colorMode);
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
