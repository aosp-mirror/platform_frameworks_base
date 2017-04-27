/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package android.app;

import android.graphics.Color;
import android.os.Parcel;
import android.os.Parcelable;

import android.util.Pair;

import java.util.ArrayList;
import java.util.List;

/**
 * A class containing information about the colors of a wallpaper.
 */
public final class WallpaperColors implements Parcelable {

    private static final float BRIGHT_LUMINANCE = 0.9f;
    private final List<Pair<Color, Integer>> mColors;
    private final boolean mSupportsDarkText;

    public WallpaperColors(Parcel parcel) {
        mColors = new ArrayList<>();
        int count = parcel.readInt();
        for (int i=0; i < count; i++) {
            Color color = Color.valueOf(parcel.readInt());
            int weight = parcel.readInt();
            mColors.add(new Pair<>(color, weight));
        }
        mSupportsDarkText = parcel.readBoolean();
    }

    /**
     * Wallpaper color details containing a list of colors and their weights,
     * as if it were an histogram.
     * This list can be extracted from a bitmap by the Palette API.
     *
     * Dark text support will be calculated internally based on the histogram.
     *
     * @param colors list of pairs where each pair contains a color
     *               and number of occurrences/influence.
     */
    public WallpaperColors(List<Pair<Color, Integer>> colors) {
        this(colors, calculateDarkTextSupport(colors));
    }

    /**
     * Wallpaper color details containing a list of colors and their weights,
     * as if it were an histogram.
     * Explicit dark text support.
     *
     * @param colors list of pairs where each pair contains a color
     *               and number of occurrences/influence.
     * @param supportsDarkText can have dark text on top or not
     */
    public WallpaperColors(List<Pair<Color, Integer>> colors, boolean supportsDarkText) {
        if (colors == null)
            colors = new ArrayList<>();
        mColors = colors;
        mSupportsDarkText = supportsDarkText;
    }

    public static final Creator<WallpaperColors> CREATOR = new Creator<WallpaperColors>() {
        @Override
        public WallpaperColors createFromParcel(Parcel in) {
            return new WallpaperColors(in);
        }

        @Override
        public WallpaperColors[] newArray(int size) {
            return new WallpaperColors[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        int count = mColors.size();
        dest.writeInt(count);
        for (Pair<Color, Integer> color : mColors) {
            dest.writeInt(color.first.toArgb());
            dest.writeInt(color.second);
        }
        dest.writeBoolean(mSupportsDarkText);
    }

    /**
     * List of colors with their occurrences. The bigger the int, the more relevant the color.
     * @return list of colors paired with their weights.
     */
    public List<Pair<Color, Integer>> getColors() {
        return mColors;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        WallpaperColors other = (WallpaperColors) o;
        return mColors.equals(other.mColors) && mSupportsDarkText == other.mSupportsDarkText;
    }

    @Override
    public int hashCode() {
        return 31 * mColors.hashCode() + (mSupportsDarkText ? 1 : 0);
    }

    /**
     * Whether or not dark text is legible on top of this wallpaper.
     *
     * @return true if dark text is supported
     */
    public boolean supportsDarkText() {
        return mSupportsDarkText;
    }

    private static boolean calculateDarkTextSupport(List<Pair<Color, Integer>> colors) {
        if (colors == null) {
            return false;
        }

        Pair<Color, Integer> mainColor = null;

        for (Pair<Color, Integer> color : colors) {
            if (mainColor == null) {
                mainColor = color;
            } else if (color.second > mainColor.second) {
                mainColor = color;
            }
        }
        return mainColor != null &&
                mainColor.first.luminance() > BRIGHT_LUMINANCE;
    }
}
