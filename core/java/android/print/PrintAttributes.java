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

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources.NotFoundException;
import android.os.Parcel;
import android.os.Parcelable;
import android.service.print.PrintAttributesProto;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Map;

/**
 * This class represents the attributes of a print job. These attributes
 * describe how the printed content should be laid out. For example, the
 * print attributes may state that the content should be laid out on a
 * letter size with 300 DPI (dots per inch) resolution, have a margin of
 * 10 mills (thousand of an inch) on all sides, and be black and white.
 */
public final class PrintAttributes implements Parcelable {
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "COLOR_MODE_" }, value = {
            COLOR_MODE_MONOCHROME,
            COLOR_MODE_COLOR
    })
    @interface ColorMode {
    }
    /** Color mode: Monochrome color scheme, for example one color is used. */
    public static final int COLOR_MODE_MONOCHROME = PrintAttributesProto.COLOR_MODE_MONOCHROME;
    /** Color mode: Color color scheme, for example many colors are used. */
    public static final int COLOR_MODE_COLOR = PrintAttributesProto.COLOR_MODE_COLOR;

    private static final int VALID_COLOR_MODES =
            COLOR_MODE_MONOCHROME | COLOR_MODE_COLOR;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "DUPLEX_MODE_" }, value = {
            DUPLEX_MODE_NONE,
            DUPLEX_MODE_LONG_EDGE,
            DUPLEX_MODE_SHORT_EDGE
    })
    @interface DuplexMode {
    }
    /** Duplex mode: No duplexing. */
    public static final int DUPLEX_MODE_NONE = PrintAttributesProto.DUPLEX_MODE_NONE;
    /** Duplex mode: Pages are turned sideways along the long edge - like a book. */
    public static final int DUPLEX_MODE_LONG_EDGE = PrintAttributesProto.DUPLEX_MODE_LONG_EDGE;
    /** Duplex mode: Pages are turned upwards along the short edge - like a notpad. */
    public static final int DUPLEX_MODE_SHORT_EDGE = PrintAttributesProto.DUPLEX_MODE_SHORT_EDGE;

    private static final int VALID_DUPLEX_MODES =
            DUPLEX_MODE_NONE | DUPLEX_MODE_LONG_EDGE | DUPLEX_MODE_SHORT_EDGE;

    private @Nullable MediaSize mMediaSize;
    private @Nullable Resolution mResolution;
    private @Nullable Margins mMinMargins;

    private @IntRange(from = 0) int mColorMode;
    private @IntRange(from = 0) int mDuplexMode;

    PrintAttributes() {
        /* hide constructor */
    }

    private PrintAttributes(@NonNull Parcel parcel) {
        mMediaSize = (parcel.readInt() == 1) ? MediaSize.createFromParcel(parcel) : null;
        mResolution = (parcel.readInt() == 1) ? Resolution.createFromParcel(parcel) : null;
        mMinMargins = (parcel.readInt() == 1) ? Margins.createFromParcel(parcel) : null;
        mColorMode = parcel.readInt();
        if (mColorMode != 0) {
            enforceValidColorMode(mColorMode);
        }
        mDuplexMode = parcel.readInt();
        if (mDuplexMode != 0) {
            enforceValidDuplexMode(mDuplexMode);
        }
    }

    /**
     * Gets the media size.
     *
     * @return The media size or <code>null</code> if not set.
     */
    public @Nullable MediaSize getMediaSize() {
        return mMediaSize;
    }

    /**
     * Sets the media size.
     *
     * @param mediaSize The media size.
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
    public @Nullable Resolution getResolution() {
        return mResolution;
    }

    /**
     * Sets the resolution.
     *
     * @param resolution The resolution.
     *
     * @hide
     */
    public void setResolution(Resolution resolution) {
        mResolution = resolution;
    }

    /**
     * Gets the minimal margins. If the content does not fit
     * these margins it will be clipped.
     * <p>
     * <strong>These margins are physically imposed by the printer and they
     * are <em>not</em> rotated, i.e. they are the same for both portrait and
     * landscape. For example, a printer may not be able to print in a stripe
     * on both left and right sides of the page.
     * </strong>
     * </p>
     *
     * @return The margins or <code>null</code> if not set.
     */
    public @Nullable Margins getMinMargins() {
        return mMinMargins;
    }

    /**
     * Sets the minimal margins. If the content does not fit
     * these margins it will be clipped.
     * <p>
     * <strong>These margins are physically imposed by the printer and they
     * are <em>not</em> rotated, i.e. they are the same for both portrait and
     * landscape. For example, a printer may not be able to print in a stripe
     * on both left and right sides of the page.
     * </strong>
     * </p>
     *
     * @param margins The margins.
     *
     * @hide
     */
    public void setMinMargins(Margins margins) {
        mMinMargins = margins;
    }

    /**
     * Gets the color mode.
     *
     * @return The color mode or zero if not set.
     *
     * @see #COLOR_MODE_COLOR
     * @see #COLOR_MODE_MONOCHROME
     */
    public @IntRange(from = 0) int getColorMode() {
        return mColorMode;
    }

    /**
     * Sets the color mode.
     *
     * @param colorMode The color mode.
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
     * Gets whether this print attributes are in portrait orientation,
     * which is the media size is in portrait and all orientation dependent
     * attributes such as resolution and margins are properly adjusted.
     *
     * @return Whether this print attributes are in portrait.
     *
     * @hide
     */
    public boolean isPortrait() {
        return mMediaSize.isPortrait();
    }

    /**
     * Gets the duplex mode.
     *
     * @return The duplex mode or zero if not set.
     *
     * @see #DUPLEX_MODE_NONE
     * @see #DUPLEX_MODE_LONG_EDGE
     * @see #DUPLEX_MODE_SHORT_EDGE
     */
    public @IntRange(from = 0) int getDuplexMode() {
        return mDuplexMode;
    }

    /**
     * Sets the duplex mode.
     *
     * @param duplexMode The duplex mode.
     *
     * @see #DUPLEX_MODE_NONE
     * @see #DUPLEX_MODE_LONG_EDGE
     * @see #DUPLEX_MODE_SHORT_EDGE
     *
     * @hide
     */
    public void setDuplexMode(int duplexMode) {
        enforceValidDuplexMode(duplexMode);
        mDuplexMode = duplexMode;
    }

    /**
     * Gets a new print attributes instance which is in portrait orientation,
     * which is the media size is in portrait and all orientation dependent
     * attributes such as resolution and margins are properly adjusted.
     *
     * @return New instance in portrait orientation if this one is in
     * landscape, otherwise this instance.
     *
     * @hide
     */
    public PrintAttributes asPortrait() {
        if (isPortrait()) {
            return this;
        }

        PrintAttributes attributes = new PrintAttributes();

        // Rotate the media size.
        attributes.setMediaSize(getMediaSize().asPortrait());

        // Rotate the resolution.
        Resolution oldResolution = getResolution();
        Resolution newResolution = new Resolution(
                oldResolution.getId(),
                oldResolution.getLabel(),
                oldResolution.getVerticalDpi(),
                oldResolution.getHorizontalDpi());
        attributes.setResolution(newResolution);

        // Do not rotate the physical margins.
        attributes.setMinMargins(getMinMargins());

        attributes.setColorMode(getColorMode());
        attributes.setDuplexMode(getDuplexMode());

        return attributes;
    }

    /**
     * Gets a new print attributes instance which is in landscape orientation,
     * which is the media size is in landscape and all orientation dependent
     * attributes such as resolution and margins are properly adjusted.
     *
     * @return New instance in landscape orientation if this one is in
     * portrait, otherwise this instance.
     *
     * @hide
     */
    public PrintAttributes asLandscape() {
        if (!isPortrait()) {
            return this;
        }

        PrintAttributes attributes = new PrintAttributes();

        // Rotate the media size.
        attributes.setMediaSize(getMediaSize().asLandscape());

        // Rotate the resolution.
        Resolution oldResolution = getResolution();
        Resolution newResolution = new Resolution(
                oldResolution.getId(),
                oldResolution.getLabel(),
                oldResolution.getVerticalDpi(),
                oldResolution.getHorizontalDpi());
        attributes.setResolution(newResolution);

        // Do not rotate the physical margins.
        attributes.setMinMargins(getMinMargins());

        attributes.setColorMode(getColorMode());
        attributes.setDuplexMode(getDuplexMode());

        return attributes;
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
        if (mMinMargins != null) {
            parcel.writeInt(1);
            mMinMargins.writeToParcel(parcel);
        } else {
            parcel.writeInt(0);
        }
        parcel.writeInt(mColorMode);
        parcel.writeInt(mDuplexMode);
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
        result = prime * result + mDuplexMode;
        result = prime * result + ((mMinMargins == null) ? 0 : mMinMargins.hashCode());
        result = prime * result + ((mMediaSize == null) ? 0 : mMediaSize.hashCode());
        result = prime * result + ((mResolution == null) ? 0 : mResolution.hashCode());
        return result;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
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
        if (mDuplexMode != other.mDuplexMode) {
            return false;
        }
        if (mMinMargins == null) {
            if (other.mMinMargins != null) {
                return false;
            }
        } else if (!mMinMargins.equals(other.mMinMargins)) {
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
        if (mMediaSize != null) {
            builder.append(", orientation: ").append(mMediaSize.isPortrait()
                    ? "portrait" : "landscape");
        } else {
            builder.append(", orientation: ").append("null");
        }
        builder.append(", resolution: ").append(mResolution);
        builder.append(", minMargins: ").append(mMinMargins);
        builder.append(", colorMode: ").append(colorModeToString(mColorMode));
        builder.append(", duplexMode: ").append(duplexModeToString(mDuplexMode));
        builder.append("}");
        return builder.toString();
    }

    /** @hide */
    public void clear() {
        mMediaSize = null;
        mResolution = null;
        mMinMargins = null;
        mColorMode = 0;
        mDuplexMode = 0;
    }

    /**
     * @hide
     */
    public void copyFrom(PrintAttributes other) {
        mMediaSize = other.mMediaSize;
        mResolution = other.mResolution;
        mMinMargins = other.mMinMargins;
        mColorMode = other.mColorMode;
        mDuplexMode = other.mDuplexMode;
    }

    /**
     * This class specifies a supported media size. Media size is the
     * dimension of the media on which the content is printed. For
     * example, the {@link #NA_LETTER} media size designates a page
     * with size 8.5" x 11".
     */
    public static final class MediaSize {
        private static final String LOG_TAG = "MediaSize";

        private static final Map<String, MediaSize> sIdToMediaSizeMap =
                new ArrayMap<>();

        /**
         * Unknown media size in portrait mode.
         * <p>
         * <strong>Note: </strong>This is for specifying orientation without media
         * size. You should not use the dimensions reported by this instance.
         * </p>
         */
        public static final MediaSize UNKNOWN_PORTRAIT =
                new MediaSize("UNKNOWN_PORTRAIT", "android",
                        R.string.mediasize_unknown_portrait, 1, Integer.MAX_VALUE);

        /**
         * Unknown media size in landscape mode.
         * <p>
         * <strong>Note: </strong>This is for specifying orientation without media
         * size. You should not use the dimensions reported by this instance.
         * </p>
         */
        public static final MediaSize UNKNOWN_LANDSCAPE =
                new MediaSize("UNKNOWN_LANDSCAPE", "android",
                        R.string.mediasize_unknown_landscape, Integer.MAX_VALUE, 1);

        // ISO sizes

        /** ISO A0 media size: 841mm x 1189mm (33.11" x 46.81") */
        public static final MediaSize ISO_A0 =
                new MediaSize("ISO_A0", "android", R.string.mediasize_iso_a0, 33110, 46810);
        /** ISO A1 media size: 594mm x 841mm (23.39" x 33.11") */
        public static final MediaSize ISO_A1 =
                new MediaSize("ISO_A1", "android", R.string.mediasize_iso_a1, 23390, 33110);
        /** ISO A2 media size: 420mm x 594mm (16.54" x 23.39") */
        public static final MediaSize ISO_A2 =
                new MediaSize("ISO_A2", "android", R.string.mediasize_iso_a2, 16540, 23390);
        /** ISO A3 media size: 297mm x 420mm (11.69" x 16.54") */
        public static final MediaSize ISO_A3 =
                new MediaSize("ISO_A3", "android", R.string.mediasize_iso_a3, 11690, 16540);
        /** ISO A4 media size: 210mm x 297mm (8.27" x 11.69") */
        public static final MediaSize ISO_A4 =
                new MediaSize("ISO_A4", "android", R.string.mediasize_iso_a4, 8270, 11690);
        /** ISO A5 media size: 148mm x 210mm (5.83" x 8.27") */
        public static final MediaSize ISO_A5 =
                new MediaSize("ISO_A5", "android", R.string.mediasize_iso_a5, 5830, 8270);
        /** ISO A6 media size: 105mm x 148mm (4.13" x 5.83") */
        public static final MediaSize ISO_A6 =
                new MediaSize("ISO_A6", "android", R.string.mediasize_iso_a6, 4130, 5830);
        /** ISO A7 media size: 74mm x 105mm (2.91" x 4.13") */
        public static final MediaSize ISO_A7 =
                new MediaSize("ISO_A7", "android", R.string.mediasize_iso_a7, 2910, 4130);
        /** ISO A8 media size: 52mm x 74mm (2.05" x 2.91") */
        public static final MediaSize ISO_A8 =
                new MediaSize("ISO_A8", "android", R.string.mediasize_iso_a8, 2050, 2910);
        /** ISO A9 media size: 37mm x 52mm (1.46" x 2.05") */
        public static final MediaSize ISO_A9 =
                new MediaSize("ISO_A9", "android", R.string.mediasize_iso_a9, 1460, 2050);
        /** ISO A10 media size: 26mm x 37mm (1.02" x 1.46") */
        public static final MediaSize ISO_A10 =
                new MediaSize("ISO_A10", "android", R.string.mediasize_iso_a10, 1020, 1460);

        /** ISO B0 media size: 1000mm x 1414mm (39.37" x 55.67") */
        public static final MediaSize ISO_B0 =
                new MediaSize("ISO_B0", "android", R.string.mediasize_iso_b0, 39370, 55670);
        /** ISO B1 media size: 707mm x 1000mm (27.83" x 39.37") */
        public static final MediaSize ISO_B1 =
                new MediaSize("ISO_B1", "android", R.string.mediasize_iso_b1, 27830, 39370);
        /** ISO B2 media size: 500mm x 707mm (19.69" x 27.83") */
        public static final MediaSize ISO_B2 =
                new MediaSize("ISO_B2", "android", R.string.mediasize_iso_b2, 19690, 27830);
        /** ISO B3 media size: 353mm x 500mm (13.90" x 19.69") */
        public static final MediaSize ISO_B3 =
                new MediaSize("ISO_B3", "android", R.string.mediasize_iso_b3, 13900, 19690);
        /** ISO B4 media size: 250mm x 353mm (9.84" x 13.90") */
        public static final MediaSize ISO_B4 =
                new MediaSize("ISO_B4", "android", R.string.mediasize_iso_b4, 9840, 13900);
        /** ISO B5 media size: 176mm x 250mm (6.93" x 9.84") */
        public static final MediaSize ISO_B5 =
                new MediaSize("ISO_B5", "android", R.string.mediasize_iso_b5, 6930, 9840);
        /** ISO B6 media size: 125mm x 176mm (4.92" x 6.93") */
        public static final MediaSize ISO_B6 =
                new MediaSize("ISO_B6", "android", R.string.mediasize_iso_b6, 4920, 6930);
        /** ISO B7 media size: 88mm x 125mm (3.46" x 4.92") */
        public static final MediaSize ISO_B7 =
                new MediaSize("ISO_B7", "android", R.string.mediasize_iso_b7, 3460, 4920);
        /** ISO B8 media size: 62mm x 88mm (2.44" x 3.46") */
        public static final MediaSize ISO_B8 =
                new MediaSize("ISO_B8", "android", R.string.mediasize_iso_b8, 2440, 3460);
        /** ISO B9 media size: 44mm x 62mm (1.73" x 2.44") */
        public static final MediaSize ISO_B9 =
                new MediaSize("ISO_B9", "android", R.string.mediasize_iso_b9, 1730, 2440);
        /** ISO B10 media size: 31mm x 44mm (1.22" x 1.73") */
        public static final MediaSize ISO_B10 =
                new MediaSize("ISO_B10", "android", R.string.mediasize_iso_b10, 1220, 1730);

        /** ISO C0 media size: 917mm x 1297mm (36.10" x 51.06") */
        public static final MediaSize ISO_C0 =
                new MediaSize("ISO_C0", "android", R.string.mediasize_iso_c0, 36100, 51060);
        /** ISO C1 media size: 648mm x 917mm (25.51" x 36.10") */
        public static final MediaSize ISO_C1 =
                new MediaSize("ISO_C1", "android", R.string.mediasize_iso_c1, 25510, 36100);
        /** ISO C2 media size: 458mm x 648mm (18.03" x 25.51") */
        public static final MediaSize ISO_C2 =
                new MediaSize("ISO_C2", "android", R.string.mediasize_iso_c2, 18030, 25510);
        /** ISO C3 media size: 324mm x 458mm (12.76" x 18.03") */
        public static final MediaSize ISO_C3 =
                new MediaSize("ISO_C3", "android", R.string.mediasize_iso_c3, 12760, 18030);
        /** ISO C4 media size: 229mm x 324mm (9.02" x 12.76") */
        public static final MediaSize ISO_C4 =
                new MediaSize("ISO_C4", "android", R.string.mediasize_iso_c4, 9020, 12760);
        /** ISO C5 media size: 162mm x 229mm (6.38" x 9.02") */
        public static final MediaSize ISO_C5 =
                new MediaSize("ISO_C5", "android", R.string.mediasize_iso_c5, 6380, 9020);
        /** ISO C6 media size: 114mm x 162mm (4.49" x 6.38") */
        public static final MediaSize ISO_C6 =
                new MediaSize("ISO_C6", "android", R.string.mediasize_iso_c6, 4490, 6380);
        /** ISO C7 media size: 81mm x 114mm (3.19" x 4.49") */
        public static final MediaSize ISO_C7 =
                new MediaSize("ISO_C7", "android", R.string.mediasize_iso_c7, 3190, 4490);
        /** ISO C8 media size: 57mm x 81mm (2.24" x 3.19") */
        public static final MediaSize ISO_C8 =
                new MediaSize("ISO_C8", "android", R.string.mediasize_iso_c8, 2240, 3190);
        /** ISO C9 media size: 40mm x 57mm (1.57" x 2.24") */
        public static final MediaSize ISO_C9 =
                new MediaSize("ISO_C9", "android", R.string.mediasize_iso_c9, 1570, 2240);
        /** ISO C10 media size: 28mm x 40mm (1.10" x 1.57") */
        public static final MediaSize ISO_C10 =
                new MediaSize("ISO_C10", "android", R.string.mediasize_iso_c10, 1100, 1570);

        // North America

        /** North America Letter media size: 8.5" x 11" (279mm x 216mm) */
        public static final MediaSize NA_LETTER =
                new MediaSize("NA_LETTER", "android", R.string.mediasize_na_letter, 8500, 11000);
        /** North America Government-Letter media size: 8.0" x 10.5" (203mm x 267mm) */
        public static final MediaSize NA_GOVT_LETTER =
                new MediaSize("NA_GOVT_LETTER", "android",
                        R.string.mediasize_na_gvrnmt_letter, 8000, 10500);
        /** North America Legal media size: 8.5" x 14" (216mm x 356mm) */
        public static final MediaSize NA_LEGAL =
                new MediaSize("NA_LEGAL", "android", R.string.mediasize_na_legal, 8500, 14000);
        /** North America Junior Legal media size: 8.0" x 5.0" (203mm × 127mm) */
        public static final MediaSize NA_JUNIOR_LEGAL =
                new MediaSize("NA_JUNIOR_LEGAL", "android",
                        R.string.mediasize_na_junior_legal, 8000, 5000);
        /** North America Ledger media size: 17" x 11" (432mm × 279mm) */
        public static final MediaSize NA_LEDGER =
                new MediaSize("NA_LEDGER", "android", R.string.mediasize_na_ledger, 17000, 11000);
        /** North America Tabloid media size: 11" x 17" (279mm × 432mm) */
        public static final MediaSize NA_TABLOID =
                new MediaSize("NA_TABLOID", "android",
                        R.string.mediasize_na_tabloid, 11000, 17000);
        /** North America Index Card 3x5 media size: 3" x 5" (76mm x 127mm) */
        public static final MediaSize NA_INDEX_3X5 =
                new MediaSize("NA_INDEX_3X5", "android",
                        R.string.mediasize_na_index_3x5, 3000, 5000);
        /** North America Index Card 4x6 media size: 4" x 6" (102mm x 152mm) */
        public static final MediaSize NA_INDEX_4X6 =
                new MediaSize("NA_INDEX_4X6", "android",
                        R.string.mediasize_na_index_4x6, 4000, 6000);
        /** North America Index Card 5x8 media size: 5" x 8" (127mm x 203mm) */
        public static final MediaSize NA_INDEX_5X8 =
                new MediaSize("NA_INDEX_5X8", "android",
                        R.string.mediasize_na_index_5x8, 5000, 8000);
        /** North America Monarch media size: 7.25" x 10.5" (184mm x 267mm) */
        public static final MediaSize NA_MONARCH =
                new MediaSize("NA_MONARCH", "android",
                        R.string.mediasize_na_monarch, 7250, 10500);
        /** North America Quarto media size: 8" x 10" (203mm x 254mm) */
        public static final MediaSize NA_QUARTO =
                new MediaSize("NA_QUARTO", "android",
                        R.string.mediasize_na_quarto, 8000, 10000);
        /** North America Foolscap media size: 8" x 13" (203mm x 330mm) */
        public static final MediaSize NA_FOOLSCAP =
                new MediaSize("NA_FOOLSCAP", "android",
                        R.string.mediasize_na_foolscap, 8000, 13000);
        /** North America ANSI C media size: 17" x 22" (432mm x 559mm) */
        public static final @NonNull MediaSize ANSI_C =
                new MediaSize("ANSI_C", "android",
                        R.string.mediasize_na_ansi_c, 17000, 22000);
        /** North America ANSI D media size: 22" x 34" (559mm x 864mm) */
        public static final @NonNull MediaSize ANSI_D =
                new MediaSize("ANSI_D", "android",
                        R.string.mediasize_na_ansi_d, 22000, 34000);
        /** North America ANSI E media size: 34" x 44" (864mm x 1118mm) */
        public static final @NonNull MediaSize ANSI_E =
                new MediaSize("ANSI_E", "android",
                        R.string.mediasize_na_ansi_e, 34000, 44000);
        /** North America ANSI F media size: 28" x 40" (711mm x 1016mm) */
        public static final @NonNull MediaSize ANSI_F =
                new MediaSize("ANSI_F", "android",
                        R.string.mediasize_na_ansi_f, 28000, 40000);
        /** North America Arch A media size: 9" x 12" (229mm x 305mm) */
        public static final @NonNull MediaSize NA_ARCH_A =
                new MediaSize("NA_ARCH_A", "android",
                        R.string.mediasize_na_arch_a, 9000, 12000);
        /** North America Arch B media size: 12" x 18" (305mm x 457mm) */
        public static final @NonNull MediaSize NA_ARCH_B =
                new MediaSize("NA_ARCH_B", "android",
                        R.string.mediasize_na_arch_b, 12000, 18000);
        /** North America Arch C media size: 18" x 24" (457mm x 610mm) */
        public static final @NonNull MediaSize NA_ARCH_C =
                new MediaSize("NA_ARCH_C", "android",
                        R.string.mediasize_na_arch_c, 18000, 24000);
        /** North America Arch D media size: 24" x 36" (610mm x 914mm) */
        public static final @NonNull MediaSize NA_ARCH_D =
                new MediaSize("NA_ARCH_D", "android",
                        R.string.mediasize_na_arch_d, 24000, 36000);
        /** North America Arch E media size: 36" x 48" (914mm x 1219mm) */
        public static final @NonNull MediaSize NA_ARCH_E =
                new MediaSize("NA_ARCH_E", "android",
                        R.string.mediasize_na_arch_e, 36000, 48000);
        /** North America Arch E1 media size: 30" x 42" (762mm x 1067mm) */
        public static final @NonNull MediaSize NA_ARCH_E1 =
                new MediaSize("NA_ARCH_E1", "android",
                        R.string.mediasize_na_arch_e1, 30000, 42000);
        /** North America Super B media size: 13" x 19" (330mm x 483mm) */
        public static final @NonNull MediaSize NA_SUPER_B =
                new MediaSize("NA_SUPER_B", "android",
                        R.string.mediasize_na_super_b, 13000, 19000);

        // Chinese

        /** Chinese ROC 8K media size: 270mm x 390mm (10.629" x 15.3543") */
        public static final MediaSize ROC_8K =
                new MediaSize("ROC_8K", "android",
                        R.string.mediasize_chinese_roc_8k, 10629, 15354);
        /** Chinese ROC 16K media size: 195mm x 270mm (7.677" x 10.629") */
        public static final MediaSize ROC_16K =
                new MediaSize("ROC_16K", "android",
                        R.string.mediasize_chinese_roc_16k, 7677, 10629);

        /** Chinese PRC 1 media size: 102mm x 165mm (4.015" x 6.496") */
        public static final MediaSize PRC_1 =
                new MediaSize("PRC_1", "android",
                        R.string.mediasize_chinese_prc_1, 4015, 6496);
        /** Chinese PRC 2 media size: 102mm x 176mm (4.015" x 6.929") */
        public static final MediaSize PRC_2 =
                new MediaSize("PRC_2", "android",
                        R.string.mediasize_chinese_prc_2, 4015, 6929);
        /** Chinese PRC 3 media size: 125mm x 176mm (4.921" x 6.929") */
        public static final MediaSize PRC_3 =
                new MediaSize("PRC_3", "android",
                        R.string.mediasize_chinese_prc_3, 4921, 6929);
        /** Chinese PRC 4 media size: 110mm x 208mm (4.330" x 8.189") */
        public static final MediaSize PRC_4 =
                new MediaSize("PRC_4", "android",
                        R.string.mediasize_chinese_prc_4, 4330, 8189);
        /** Chinese PRC 5 media size: 110mm x 220mm (4.330" x 8.661") */
        public static final MediaSize PRC_5 =
                new MediaSize("PRC_5", "android",
                        R.string.mediasize_chinese_prc_5, 4330, 8661);
        /** Chinese PRC 6 media size: 120mm x 320mm (4.724" x 12.599") */
        public static final MediaSize PRC_6 =
                new MediaSize("PRC_6", "android",
                        R.string.mediasize_chinese_prc_6, 4724, 12599);
        /** Chinese PRC 7 media size: 160mm x 230mm (6.299" x 9.055") */
        public static final MediaSize PRC_7 =
                new MediaSize("PRC_7", "android",
                        R.string.mediasize_chinese_prc_7, 6299, 9055);
        /** Chinese PRC 8 media size: 120mm x 309mm (4.724" x 12.165") */
        public static final MediaSize PRC_8 =
                new MediaSize("PRC_8", "android",
                        R.string.mediasize_chinese_prc_8, 4724, 12165);
        /** Chinese PRC 9 media size: 229mm x 324mm (9.016" x 12.756") */
        public static final MediaSize PRC_9 =
                new MediaSize("PRC_9", "android",
                        R.string.mediasize_chinese_prc_9, 9016, 12756);
        /** Chinese PRC 10 media size: 324mm x 458mm (12.756" x 18.032") */
        public static final MediaSize PRC_10 =
                new MediaSize("PRC_10", "android",
                        R.string.mediasize_chinese_prc_10, 12756, 18032);

        /** Chinese PRC 16k media size: 146mm x 215mm (5.749" x 8.465") */
        public static final MediaSize PRC_16K =
                new MediaSize("PRC_16K", "android",
                        R.string.mediasize_chinese_prc_16k, 5749, 8465);
        /** Chinese Pa Kai media size: 267mm x 389mm (10.512" x 15.315") */
        public static final MediaSize OM_PA_KAI =
                new MediaSize("OM_PA_KAI", "android",
                        R.string.mediasize_chinese_om_pa_kai, 10512, 15315);
        /** Chinese Dai Pa Kai media size: 275mm x 395mm (10.827" x 15.551") */
        public static final MediaSize OM_DAI_PA_KAI =
                new MediaSize("OM_DAI_PA_KAI", "android",
                        R.string.mediasize_chinese_om_dai_pa_kai, 10827, 15551);
        /** Chinese Jurro Ku Kai media size: 198mm x 275mm (7.796" x 10.827") */
        public static final MediaSize OM_JUURO_KU_KAI =
                new MediaSize("OM_JUURO_KU_KAI", "android",
                        R.string.mediasize_chinese_om_jurro_ku_kai, 7796, 10827);

        // Japanese

        /** Japanese JIS B10 media size: 32mm x 45mm (1.259" x 1.772") */
        public static final MediaSize JIS_B10 =
                new MediaSize("JIS_B10", "android",
                        R.string.mediasize_japanese_jis_b10, 1259, 1772);
        /** Japanese JIS B9 media size: 45mm x 64mm (1.772" x 2.52") */
        public static final MediaSize JIS_B9 =
                new MediaSize("JIS_B9", "android",
                        R.string.mediasize_japanese_jis_b9, 1772, 2520);
        /** Japanese JIS B8 media size: 64mm x 91mm (2.52" x 3.583") */
        public static final MediaSize JIS_B8 =
                new MediaSize("JIS_B8", "android",
                        R.string.mediasize_japanese_jis_b8, 2520, 3583);
        /** Japanese JIS B7 media size: 91mm x 128mm (3.583" x 5.049") */
        public static final MediaSize JIS_B7 =
                new MediaSize("JIS_B7", "android",
                        R.string.mediasize_japanese_jis_b7, 3583, 5049);
        /** Japanese JIS B6 media size: 128mm x 182mm (5.049" x 7.165") */
        public static final MediaSize JIS_B6 =
                new MediaSize("JIS_B6", "android",
                        R.string.mediasize_japanese_jis_b6, 5049, 7165);
        /** Japanese JIS B5 media size: 182mm x 257mm (7.165" x 10.118") */
        public static final MediaSize JIS_B5 =
                new MediaSize("JIS_B5", "android",
                        R.string.mediasize_japanese_jis_b5, 7165, 10118);
        /** Japanese JIS B4 media size: 257mm x 364mm (10.118" x 14.331") */
        public static final MediaSize JIS_B4 =
                new MediaSize("JIS_B4", "android",
                        R.string.mediasize_japanese_jis_b4, 10118, 14331);
        /** Japanese JIS B3 media size: 364mm x 515mm (14.331" x 20.276") */
        public static final MediaSize JIS_B3 =
                new MediaSize("JIS_B3", "android",
                        R.string.mediasize_japanese_jis_b3, 14331, 20276);
        /** Japanese JIS B2 media size: 515mm x 728mm (20.276" x 28.661") */
        public static final MediaSize JIS_B2 =
                new MediaSize("JIS_B2", "android",
                        R.string.mediasize_japanese_jis_b2, 20276, 28661);
        /** Japanese JIS B1 media size: 728mm x 1030mm (28.661" x 40.551") */
        public static final MediaSize JIS_B1 =
                new MediaSize("JIS_B1", "android",
                        R.string.mediasize_japanese_jis_b1, 28661, 40551);
        /** Japanese JIS B0 media size: 1030mm x 1456mm (40.551" x 57.323") */
        public static final MediaSize JIS_B0 =
                new MediaSize("JIS_B0", "android",
                        R.string.mediasize_japanese_jis_b0, 40551, 57323);

        /** Japanese JIS Exec media size: 216mm x 330mm (8.504" x 12.992") */
        public static final MediaSize JIS_EXEC =
                new MediaSize("JIS_EXEC", "android",
                        R.string.mediasize_japanese_jis_exec, 8504, 12992);

        /** Japanese Chou4 media size: 90mm x 205mm (3.543" x 8.071") */
        public static final MediaSize JPN_CHOU4 =
                new MediaSize("JPN_CHOU4", "android",
                        R.string.mediasize_japanese_chou4, 3543, 8071);
        /** Japanese Chou3 media size: 120mm x 235mm (4.724" x 9.252") */
        public static final MediaSize JPN_CHOU3 =
                new MediaSize("JPN_CHOU3", "android",
                        R.string.mediasize_japanese_chou3, 4724, 9252);
        /** Japanese Chou2 media size: 111.1mm x 146mm (4.374" x 5.748") */
        public static final MediaSize JPN_CHOU2 =
                new MediaSize("JPN_CHOU2", "android",
                        R.string.mediasize_japanese_chou2, 4374, 5748);

        /** Japanese Hagaki media size: 100mm x 148mm (3.937" x 5.827") */
        public static final MediaSize JPN_HAGAKI =
                new MediaSize("JPN_HAGAKI", "android",
                        R.string.mediasize_japanese_hagaki, 3937, 5827);
        /** Japanese Oufuku media size: 148mm x 200mm (5.827" x 7.874") */
        public static final MediaSize JPN_OUFUKU =
                new MediaSize("JPN_OUFUKU", "android",
                        R.string.mediasize_japanese_oufuku, 5827, 7874);

        /** Japanese Kahu media size: 240mm x 322.1mm (9.449" x 12.681") */
        public static final MediaSize JPN_KAHU =
                new MediaSize("JPN_KAHU", "android",
                        R.string.mediasize_japanese_kahu, 9449, 12681);
        /** Japanese Kaku2 media size: 240mm x 332mm (9.449" x 13.071") */
        public static final MediaSize JPN_KAKU2 =
                new MediaSize("JPN_KAKU2", "android",
                        R.string.mediasize_japanese_kaku2, 9449, 13071);

        /** Japanese You4 media size: 105mm x 235mm (4.134" x 9.252") */
        public static final MediaSize JPN_YOU4 =
                new MediaSize("JPN_YOU4", "android",
                        R.string.mediasize_japanese_you4, 4134, 9252);
        /** Japanese Photo L media size: 89mm x 127mm (3.5 x 5") */
        public static final @NonNull MediaSize JPN_OE_PHOTO_L =
                new MediaSize("JPN_OE_PHOTO_L", "android",
                        R.string.mediasize_japanese_l, 3500, 5000);

        private final @NonNull String mId;
        /**@hide */
        public final @NonNull String mLabel;
        /**@hide */
        public final @Nullable String mPackageName;
        /**@hide */
        public final @StringRes int mLabelResId;
        private final @IntRange(from = 1) int mWidthMils;
        private final @IntRange(from = 1) int mHeightMils;

        /**
         * Creates a new instance.
         *
         * @param id The unique media size id.
         * @param packageName The name of the creating package.
         * @param labelResId The resource if of a human readable label.
         * @param widthMils The width in mils (thousandths of an inch).
         * @param heightMils The height in mils (thousandths of an inch).
         *
         * @throws IllegalArgumentException If the id is empty or the label
         * is empty or the widthMils is less than or equal to zero or the
         * heightMils is less than or equal to zero.
         *
         * @hide
         */
        public MediaSize(String id, String packageName, int labelResId,
                int widthMils, int heightMils) {
            this(id, null, packageName, widthMils, heightMils, labelResId);

            // Build this mapping only for predefined media sizes.
            sIdToMediaSizeMap.put(mId, this);
        }

        /**
         * Creates a new instance.
         *
         * @param id The unique media size id. It is unique amongst other media sizes
         *        supported by the printer.
         * @param label The <strong>localized</strong> human readable label.
         * @param widthMils The width in mils (thousandths of an inch).
         * @param heightMils The height in mils (thousandths of an inch).
         *
         * @throws IllegalArgumentException If the id is empty or the label is empty
         * or the widthMils is less than or equal to zero or the heightMils is less
         * than or equal to zero.
         */
        public MediaSize(@NonNull String id, @NonNull String label,
                @IntRange(from = 1) int widthMils, @IntRange(from = 1) int heightMils) {
            this(id, label, null, widthMils, heightMils, 0);
        }

        /**
         * Get the Id of all predefined media sizes beside the {@link #UNKNOWN_PORTRAIT} and
         * {@link #UNKNOWN_LANDSCAPE}.
         *
         * @return List of all predefined media sizes
         *
         * @hide
         */
        public static @NonNull ArraySet<MediaSize> getAllPredefinedSizes() {
            ArraySet<MediaSize> definedMediaSizes = new ArraySet<>(sIdToMediaSizeMap.values());

            definedMediaSizes.remove(UNKNOWN_PORTRAIT);
            definedMediaSizes.remove(UNKNOWN_LANDSCAPE);

            return definedMediaSizes;
        }

        /**
         * Creates a new instance.
         *
         * @param id The unique media size id. It is unique amongst other media sizes
         *        supported by the printer.
         * @param label The <strong>localized</strong> human readable label.
         * @param packageName The name of the creating package.
         * @param widthMils The width in mils (thousandths of an inch).
         * @param heightMils The height in mils (thousandths of an inch).
         * @param labelResId The resource if of a human readable label.
         *
         * @throws IllegalArgumentException If the id is empty or the label is unset
         * or the widthMils is less than or equal to zero or the heightMils is less
         * than or equal to zero.
         *
         * @hide
         */
        public MediaSize(String id, String label, String packageName, int widthMils, int heightMils,
                int labelResId) {
            mPackageName = packageName;
            mId = Preconditions.checkStringNotEmpty(id, "id cannot be empty.");
            mLabelResId = labelResId;
            mWidthMils = Preconditions.checkArgumentPositive(widthMils, "widthMils cannot be " +
                    "less than or equal to zero.");
            mHeightMils = Preconditions.checkArgumentPositive(heightMils, "heightMils cannot be " +
                    "less than or equal to zero.");
            mLabel = label;

            // The label has to be either a string ot a StringRes
            Preconditions.checkArgument(!TextUtils.isEmpty(label) !=
                    (!TextUtils.isEmpty(packageName) && labelResId != 0), "label cannot be empty.");
        }

        /**
         * Gets the unique media size id. It is unique amongst other media sizes
         * supported by the printer.
         * <p>
         * This id is defined by the client that generated the media size
         * instance and should not be interpreted by other parties.
         * </p>
         *
         * @return The unique media size id.
         */
        public @NonNull String getId() {
            return mId;
        }

        /**
         * Gets the human readable media size label.
         *
         * @param packageManager The package manager for loading the label.
         * @return The human readable label.
         */
        public @NonNull String getLabel(@NonNull PackageManager packageManager) {
            if (!TextUtils.isEmpty(mPackageName) && mLabelResId > 0) {
                try {
                    return packageManager.getResourcesForApplication(
                            mPackageName).getString(mLabelResId);
                } catch (NotFoundException | NameNotFoundException e) {
                    Log.w(LOG_TAG, "Could not load resouce" + mLabelResId
                            + " from package " + mPackageName);
                }
            }
            return mLabel;
        }

        /**
         * Gets the media width in mils (thousandths of an inch).
         *
         * @return The media width.
         */
        public @IntRange(from = 1) int getWidthMils() {
            return mWidthMils;
        }

        /**
         * Gets the media height in mils (thousandths of an inch).
         *
         * @return The media height.
         */
        public @IntRange(from = 1) int getHeightMils() {
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
         * Returns a new media size instance in a portrait orientation,
         * which is the height is the greater dimension.
         *
         * @return New instance in landscape orientation if this one
         * is in landscape, otherwise this instance.
         */
        public @NonNull MediaSize asPortrait() {
            if (isPortrait()) {
                return this;
            }
            return new MediaSize(mId, mLabel, mPackageName,
                    Math.min(mWidthMils, mHeightMils),
                    Math.max(mWidthMils, mHeightMils),
                    mLabelResId);
        }

        /**
         * Returns a new media size instance in a landscape orientation,
         * which is the height is the lesser dimension.
         *
         * @return New instance in landscape orientation if this one
         * is in portrait, otherwise this instance.
         */
        public @NonNull MediaSize asLandscape() {
            if (!isPortrait()) {
                return this;
            }
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
        public boolean equals(@Nullable Object obj) {
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

        /**
         * Gets a standard media size given its id.
         *
         * @param id The media size id.
         * @return The media size for the given id or null.
         *
         * @hide
         */
        public static MediaSize getStandardMediaSizeById(String id) {
            return sIdToMediaSizeMap.get(id);
        }
    }

    /**
     * This class specifies a supported resolution in DPI (dots per inch).
     * Resolution defines how many points with different color can be placed
     * on one inch in horizontal or vertical direction of the target media.
     * For example, a printer with 600 DPI can produce higher quality images
     * the one with 300 DPI resolution.
     */
    public static final class Resolution {
        private final @NonNull String mId;
        private final @NonNull String mLabel;
        private final @IntRange(from = 1) int mHorizontalDpi;
        private final @IntRange(from = 1) int mVerticalDpi;

        /**
         * Creates a new instance.
         *
         * @param id The unique resolution id. It is unique amongst other resolutions
         *        supported by the printer.
         * @param label The <strong>localized</strong> human readable label.
         * @param horizontalDpi The horizontal resolution in DPI (dots per inch).
         * @param verticalDpi The vertical resolution in DPI (dots per inch).
         *
         * @throws IllegalArgumentException If the id is empty or the label is empty
         * or the horizontalDpi is less than or equal to zero or the verticalDpi is
         * less than or equal to zero.
         */
        public Resolution(@NonNull String id, @NonNull String label,
                @IntRange(from = 1) int horizontalDpi, @IntRange(from = 1) int verticalDpi) {
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
         * Gets the unique resolution id. It is unique amongst other resolutions
         * supported by the printer.
         * <p>
         * This id is defined by the client that generated the resolution
         * instance and should not be interpreted by other parties.
         * </p>
         *
         * @return The unique resolution id.
         */
        public @NonNull String getId() {
            return mId;
        }

        /**
         * Gets the resolution human readable label.
         *
         * @return The human readable label.
         */
        public @NonNull String getLabel() {
            return mLabel;
        }

        /**
         * Gets the horizontal resolution in DPI (dots per inch).
         *
         * @return The horizontal resolution.
         */
        public @IntRange(from = 1) int getHorizontalDpi() {
            return mHorizontalDpi;
        }

        /**
         * Gets the vertical resolution in DPI (dots per inch).
         *
         * @return The vertical resolution.
         */
        public @IntRange(from = 1) int getVerticalDpi() {
            return mVerticalDpi;
        }

        void writeToParcel(Parcel parcel) {
            parcel.writeString(mId);
            parcel.writeString(mLabel);
            parcel.writeInt(mHorizontalDpi);
            parcel.writeInt(mVerticalDpi);
        }

        static Resolution createFromParcel(Parcel parcel) {
            return new Resolution(
                    parcel.readString(),
                    parcel.readString(),
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
        public boolean equals(@Nullable Object obj) {
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
            builder.append(", horizontalDpi: ").append(mHorizontalDpi);
            builder.append(", verticalDpi: ").append(mVerticalDpi);
            builder.append("}");
            return builder.toString();
        }
    }

    /**
     * This class specifies content margins. Margins define the white space
     * around the content where the left margin defines the amount of white
     * space on the left of the content and so on.
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
         * @param leftMils The left margin in mils (thousandths of an inch).
         * @param topMils The top margin in mils (thousandths of an inch).
         * @param rightMils The right margin in mils (thousandths of an inch).
         * @param bottomMils The bottom margin in mils (thousandths of an inch).
         */
        public Margins(int leftMils, int topMils, int rightMils, int bottomMils) {
            mTopMils = topMils;
            mLeftMils = leftMils;
            mRightMils = rightMils;
            mBottomMils = bottomMils;
        }

        /**
         * Gets the left margin in mils (thousandths of an inch).
         *
         * @return The left margin.
         */
        public int getLeftMils() {
            return mLeftMils;
        }

        /**
         * Gets the top margin in mils (thousandths of an inch).
         *
         * @return The top margin.
         */
        public int getTopMils() {
            return mTopMils;
        }

        /**
         * Gets the right margin in mils (thousandths of an inch).
         *
         * @return The right margin.
         */
        public int getRightMils() {
            return mRightMils;
        }

        /**
         * Gets the bottom margin in mils (thousandths of an inch).
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
        public boolean equals(@Nullable Object obj) {
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
            default: {
                return "COLOR_MODE_UNKNOWN";
            }
        }
    }

    static String duplexModeToString(int duplexMode) {
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
            default: {
                return "DUPLEX_MODE_UNKNOWN";
            }
        }
    }

    static void enforceValidColorMode(int colorMode) {
        if ((colorMode & VALID_COLOR_MODES) == 0 || Integer.bitCount(colorMode) != 1) {
            throw new IllegalArgumentException("invalid color mode: " + colorMode);
        }
    }

    static void enforceValidDuplexMode(int duplexMode) {
        if ((duplexMode & VALID_DUPLEX_MODES) == 0 || Integer.bitCount(duplexMode) != 1) {
            throw new IllegalArgumentException("invalid duplex mode: " + duplexMode);
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
        public @NonNull Builder setMediaSize(@NonNull MediaSize mediaSize) {
            mAttributes.setMediaSize(mediaSize);
            return this;
        }

        /**
         * Sets the resolution.
         *
         * @param resolution The resolution.
         * @return This builder.
         */
        public @NonNull Builder setResolution(@NonNull Resolution resolution) {
            mAttributes.setResolution(resolution);
            return this;
        }

        /**
         * Sets the minimal margins. If the content does not fit
         * these margins it will be clipped.
         *
         * @param margins The margins.
         * @return This builder.
         */
        public @NonNull Builder setMinMargins(@NonNull Margins margins) {
            mAttributes.setMinMargins(margins);
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
        public @NonNull Builder setColorMode(@ColorMode int colorMode) {
            mAttributes.setColorMode(colorMode);
            return this;
        }

        /**
         * Sets the duplex mode.
         *
         * @param duplexMode A valid duplex mode or zero.
         * @return This builder.
         *
         * @see PrintAttributes#DUPLEX_MODE_NONE
         * @see PrintAttributes#DUPLEX_MODE_LONG_EDGE
         * @see PrintAttributes#DUPLEX_MODE_SHORT_EDGE
         */
        public @NonNull Builder setDuplexMode(@DuplexMode int duplexMode) {
            mAttributes.setDuplexMode(duplexMode);
            return this;
        }

        /**
         * Creates a new {@link PrintAttributes} instance.
         *
         * @return The new instance.
         */
        public @NonNull PrintAttributes build() {
            return mAttributes;
        }
    }

    public static final @android.annotation.NonNull Parcelable.Creator<PrintAttributes> CREATOR =
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
