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

import android.annotation.Nullable;
import android.content.res.AssetManager;
import android.graphics.fonts.FontVariationAxis;
import android.text.TextUtils;
import android.util.Log;

import dalvik.annotation.optimization.CriticalNative;

import libcore.util.NativeAllocationRegistry;

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

    private static final NativeAllocationRegistry sBuilderRegistry = new NativeAllocationRegistry(
            FontFamily.class.getClassLoader(), nGetBuilderReleaseFunc(), 64);

    private @Nullable Runnable mNativeBuilderCleaner;

    private static final NativeAllocationRegistry sFamilyRegistry = new NativeAllocationRegistry(
            FontFamily.class.getClassLoader(), nGetFamilyReleaseFunc(), 64);

    /**
     * @hide
     */
    public long mNativePtr;

    // Points native font family builder. Must be zero after freezing this family.
    private long mBuilderPtr;

    public FontFamily() {
        mBuilderPtr = nInitBuilder(null, 0);
        mNativeBuilderCleaner = sBuilderRegistry.registerNativeAllocation(this, mBuilderPtr);
    }

    public FontFamily(@Nullable String[] langs, int variant) {
        final String langsString;
        if (langs == null || langs.length == 0) {
            langsString = null;
        } else if (langs.length == 1) {
            langsString = langs[0];
        } else {
            langsString = TextUtils.join(",", langs);
        }
        mBuilderPtr = nInitBuilder(langsString, variant);
        mNativeBuilderCleaner = sBuilderRegistry.registerNativeAllocation(this, mBuilderPtr);
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
        mNativeBuilderCleaner.run();
        mBuilderPtr = 0;
        if (mNativePtr != 0) {
            sFamilyRegistry.registerNativeAllocation(this, mNativePtr);
        }
        return mNativePtr != 0;
    }

    public void abortCreation() {
        if (mBuilderPtr == 0) {
            throw new IllegalStateException("This FontFamily is already frozen or abandoned");
        }
        mNativeBuilderCleaner.run();
        mBuilderPtr = 0;
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

    // TODO: Remove once internal user stop using private API.
    private static boolean nAddFont(long builderPtr, ByteBuffer font, int ttcIndex) {
        return nAddFont(builderPtr, font, ttcIndex, -1, -1);
    }

    private static native long nInitBuilder(String langs, int variant);

    @CriticalNative
    private static native long nCreateFamily(long mBuilderPtr);

    @CriticalNative
    private static native long nGetBuilderReleaseFunc();

    @CriticalNative
    private static native long nGetFamilyReleaseFunc();
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
