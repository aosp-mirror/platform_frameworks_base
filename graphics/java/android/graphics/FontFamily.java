/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.graphics;

import android.content.res.AssetManager;
import android.graphics.fonts.FontVariationAxis;
import android.text.FontConfig;
import android.util.Log;
import dalvik.annotation.optimization.CriticalNative;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * A family of typefaces with different styles.
 *
 * @hide
 */
public class FontFamily {

    private static String TAG = "FontFamily";

    /**
     * @hide
     */
    public long mNativePtr;

    // Points native font family builder. Must be zero after freezing this family.
    private long mBuilderPtr;

    public FontFamily() {
        mBuilderPtr = nInitBuilder(null, 0);
    }

    public FontFamily(String lang, int variant) {
        mBuilderPtr = nInitBuilder(lang, variant);
    }

    /**
     * Finalize the FontFamily creation.
     *
     * @return boolean returns false if some error happens in native code, e.g. broken font file is
     *                 passed, etc.
     */
    public boolean freeze() {
        if (mBuilderPtr == 0) {
            throw new IllegalStateException("This FontFamily is already frozen");
        }
        mNativePtr = nCreateFamily(mBuilderPtr);
        mBuilderPtr = 0;
        return mNativePtr != 0;
    }

    public void abortCreation() {
        if (mBuilderPtr == 0) {
            throw new IllegalStateException("This FontFamily is already frozen or abandoned");
        }
        nAbort(mBuilderPtr);
        mBuilderPtr = 0;
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mNativePtr != 0) {
                nUnrefFamily(mNativePtr);
            }
            if (mBuilderPtr != 0) {
                nAbort(mBuilderPtr);
            }
        } finally {
            super.finalize();
        }
    }

    public boolean addFont(String path, int ttcIndex, FontVariationAxis[] axes, int weight,
            int italic) {
        if (mBuilderPtr == 0) {
            throw new IllegalStateException("Unable to call addFont after freezing.");
        }
        try (FileInputStream file = new FileInputStream(path)) {
            FileChannel fileChannel = file.getChannel();
            long fontSize = fileChannel.size();
            ByteBuffer fontBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fontSize);
            if (axes != null) {
                for (FontVariationAxis axis : axes) {
                    nAddAxisValue(mBuilderPtr, axis.getOpenTypeTagValue(), axis.getStyleValue());
                }
            }
            return nAddFont(mBuilderPtr, fontBuffer, ttcIndex, weight, italic);
        } catch (IOException e) {
            Log.e(TAG, "Error mapping font file " + path);
            return false;
        }
    }

    public boolean addFontFromBuffer(ByteBuffer font, int ttcIndex, FontVariationAxis[] axes,
            int weight, int italic) {
        if (mBuilderPtr == 0) {
            throw new IllegalStateException("Unable to call addFontWeightStyle after freezing.");
        }
        if (axes != null) {
            for (FontVariationAxis axis : axes) {
                nAddAxisValue(mBuilderPtr, axis.getOpenTypeTagValue(), axis.getStyleValue());
            }
        }
        return nAddFontWeightStyle(mBuilderPtr, font, ttcIndex, weight, italic);
    }

    /**
     * @param mgr The AssetManager to use for this context.
     * @param path The path to the font file to load.
     * @param cookie If available, the resource cookie given by Resources.
     * @param isAsset {@code true} if this is from the assets/ folder, {@code false} if from
     *            resources
     * @param weight The weight of the font. If 0 is given, the weight and italic will be resolved
     *            using the OS/2 table in the font.
     * @param isItalic Whether this font is italic. If the weight is set to 0, this will be resolved
     *            using the OS/2 table in the font.
     * @return
     */
    public boolean addFontFromAssetManager(AssetManager mgr, String path, int cookie,
            boolean isAsset, int ttcIndex, int weight, int isItalic,
            FontVariationAxis[] axes) {
        if (mBuilderPtr == 0) {
            throw new IllegalStateException("Unable to call addFontFromAsset after freezing.");
        }
        if (axes != null) {
            for (FontVariationAxis axis : axes) {
                nAddAxisValue(mBuilderPtr, axis.getOpenTypeTagValue(), axis.getStyleValue());
            }
        }
        return nAddFontFromAssetManager(mBuilderPtr, mgr, path, cookie, isAsset, ttcIndex, weight,
                isItalic);
    }

    /**
     * Allow creating unsupported FontFamily.
     *
     * For compatibility reasons, we still need to create a FontFamily object even if Minikin failed
     * to find any usable 'cmap' table for some reasons, e.g. broken 'cmap' table, no 'cmap' table
     * encoded with Unicode code points, etc. Without calling this method, the freeze() method will
     * return null if Minikin fails to find any usable 'cmap' table. By calling this method, the
     * freeze() won't fail and will create an empty FontFamily. This empty FontFamily is placed at
     * the top of the fallback chain but is never used. if we don't create this empty FontFamily
     * and put it at top, bad things (performance regressions, unexpected glyph selection) will
     * happen.
     */
    public void allowUnsupportedFont() {
        if (mBuilderPtr == 0) {
            throw new IllegalStateException("Unable to allow unsupported font.");
        }
        nAllowUnsupportedFont(mBuilderPtr);
    }

    // TODO: Remove once internal user stop using private API.
    private static boolean nAddFont(long builderPtr, ByteBuffer font, int ttcIndex) {
        return nAddFont(builderPtr, font, ttcIndex, -1, -1);
    }

    private static native long nInitBuilder(String lang, int variant);

    @CriticalNative
    private static native long nCreateFamily(long mBuilderPtr);

    @CriticalNative
    private static native void nAllowUnsupportedFont(long builderPtr);

    @CriticalNative
    private static native void nAbort(long mBuilderPtr);

    @CriticalNative
    private static native void nUnrefFamily(long nativePtr);
    // By passing -1 to weigth argument, the weight value is resolved by OS/2 table in the font.
    // By passing -1 to italic argument, the italic value is resolved by OS/2 table in the font.
    private static native boolean nAddFont(long builderPtr, ByteBuffer font, int ttcIndex,
            int weight, int isItalic);
    private static native boolean nAddFontWeightStyle(long builderPtr, ByteBuffer font,
            int ttcIndex, int weight, int isItalic);
    private static native boolean nAddFontFromAssetManager(long builderPtr, AssetManager mgr,
            String path, int cookie, boolean isAsset, int ttcIndex, int weight, int isItalic);

    // The added axis values are only valid for the next nAddFont* method call.
    @CriticalNative
    private static native void nAddAxisValue(long builderPtr, int tag, float value);
}
