/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.graphics.fonts;

import android.graphics.Typeface;

import dalvik.annotation.optimization.CriticalNative;
import dalvik.annotation.optimization.FastNative;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Read native font objects.
 *
 * @hide
 */
public class NativeFont {

    /**
     * Represents native font object.
     */
    public static final class Font {
        private final File mFile;
        private final int mIndex;
        private final FontVariationAxis[] mAxes;
        private final FontStyle mStyle;

        public Font(File file, int index, FontVariationAxis[] axes, FontStyle style) {
            mFile = file;
            mIndex = index;
            mAxes = axes;
            mStyle = style;
        }

        public File getFile() {
            return mFile;
        }

        public FontVariationAxis[] getAxes() {
            return mAxes;
        }

        public FontStyle getStyle() {
            return mStyle;
        }

        public int getIndex() {
            return mIndex;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Font font = (Font) o;
            return mIndex == font.mIndex && mFile.equals(font.mFile)
                    && Arrays.equals(mAxes, font.mAxes) && mStyle.equals(font.mStyle);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(mFile, mIndex, mStyle);
            result = 31 * result + Arrays.hashCode(mAxes);
            return result;
        }
    }

    /**
     * Represents native font family object.
     */
    public static final class Family {
        private final List<Font> mFonts;
        private final String mLocale;

        public Family(List<Font> fonts, String locale) {
            mFonts = fonts;
            mLocale = locale;
        }

        public List<Font> getFonts() {
            return mFonts;
        }

        public String getLocale() {
            return mLocale;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Family family = (Family) o;
            return mFonts.equals(family.mFonts) && mLocale.equals(family.mLocale);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mFonts, mLocale);
        }
    }

    /**
     * Get underlying font families from Typeface
     *
     * @param typeface a typeface
     * @return list of family
     */
    public static List<Family> readTypeface(Typeface typeface) {
        int familyCount = nGetFamilyCount(typeface.native_instance);
        List<Family> result = new ArrayList<>(familyCount);
        for (int i = 0; i < familyCount; ++i) {
            result.add(readNativeFamily(nGetFamily(typeface.native_instance, i)));
        }
        return result;
    }

    /**
     * Read family object from native pointer
     *
     * @param familyPtr a font family pointer
     * @return a family
     */
    public static Family readNativeFamily(long familyPtr) {
        int fontCount = nGetFontCount(familyPtr);
        List<Font> result = new ArrayList<>(fontCount);
        for (int i = 0; i < fontCount; ++i) {
            result.add(readNativeFont(nGetFont(familyPtr, i)));
        }
        String localeList = nGetLocaleList(familyPtr);
        return new Family(result, localeList);
    }

    /**
     * Read font object from native pointer.
     *
     * @param ptr a font pointer
     * @return a font
     */
    public static Font readNativeFont(long ptr) {
        long packed = nGetFontInfo(ptr);
        int weight = (int) (packed & 0x0000_0000_0000_FFFFL);
        boolean italic = (packed & 0x0000_0000_0001_0000L) != 0;
        int ttcIndex = (int) ((packed & 0x0000_FFFF_0000_0000L) >> 32);
        int axisCount = (int) ((packed & 0xFFFF_0000_0000_0000L) >> 48);
        FontVariationAxis[] axes = new FontVariationAxis[axisCount];
        char[] charBuffer = new char[4];
        for (int i = 0; i < axisCount; ++i) {
            long packedAxis = nGetAxisInfo(ptr, i);
            float value = Float.intBitsToFloat((int) (packedAxis & 0x0000_0000_FFFF_FFFFL));
            charBuffer[0] = (char) ((packedAxis & 0xFF00_0000_0000_0000L) >> 56);
            charBuffer[1] = (char) ((packedAxis & 0x00FF_0000_0000_0000L) >> 48);
            charBuffer[2] = (char) ((packedAxis & 0x0000_FF00_0000_0000L) >> 40);
            charBuffer[3] = (char) ((packedAxis & 0x0000_00FF_0000_0000L) >> 32);
            axes[i] = new FontVariationAxis(new String(charBuffer), value);
        }
        String path = nGetFontPath(ptr);
        File file = (path == null) ? null : new File(path);
        FontStyle style = new FontStyle(weight,
                italic ? FontStyle.FONT_SLANT_ITALIC : FontStyle.FONT_SLANT_UPRIGHT);

        return new Font(file, ttcIndex, axes, style);
    }

    @CriticalNative
    private static native int nGetFamilyCount(long ptr);

    @CriticalNative
    private static native long nGetFamily(long ptr, int index);

    @FastNative
    private static native String nGetLocaleList(long familyPtr);

    @CriticalNative
    private static native long nGetFont(long familyPtr, int fontIndex);

    @CriticalNative
    private static native int nGetFontCount(long familyPtr);

    @CriticalNative
    private static native long nGetFontInfo(long fontPtr);

    @CriticalNative
    private static native long nGetAxisInfo(long fontPtr, int i);

    @FastNative
    private static native String nGetFontPath(long fontPtr);
}
