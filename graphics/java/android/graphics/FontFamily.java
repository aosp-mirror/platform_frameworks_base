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
import android.compat.annotation.UnsupportedAppUsage;
import android.content.res.AssetManager;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontVariationAxis;
import android.os.Build;
import android.text.TextUtils;

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
 *
 * @deprecated Use {@link android.graphics.fonts.FontFamily} instead.
 */
@Deprecated
public class FontFamily {

    private static String TAG = "FontFamily";

    private static final NativeAllocationRegistry sBuilderRegistry =
            NativeAllocationRegistry.createMalloced(
            FontFamily.class.getClassLoader(), nGetBuilderReleaseFunc());

    private @Nullable Runnable mNativeBuilderCleaner;

    private static final NativeAllocationRegistry sFamilyRegistry =
            NativeAllocationRegistry.createMalloced(
            FontFamily.class.getClassLoader(), nGetFamilyReleaseFunc());

    /**
     * @hide
     *
     * This cannot be deleted because it's in use by AndroidX.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q,
            publicAlternatives = "Use {@link android.graphics.fonts.FontFamily} instead.")
    public long mNativePtr;

    // Points native font family builder. Must be zero after freezing this family.
    private long mBuilderPtr;

    /**
     * This cannot be deleted because it's in use by AndroidX.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q,
            publicAlternatives = "Use {@link android.graphics.fonts.FontFamily} instead.")
    public FontFamily() {
        mBuilderPtr = nInitBuilder(null, 0);
        mNativeBuilderCleaner = sBuilderRegistry.registerNativeAllocation(this, mBuilderPtr);
    }

    /**
     * This cannot be deleted because it's in use by AndroidX.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q,
            publicAlternatives = "Use {@link android.graphics.fonts.FontFamily} instead.")
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
     *
     * This cannot be deleted because it's in use by AndroidX.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q,
            publicAlternatives = "Use {@link android.graphics.fonts.FontFamily} instead.")
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

    /**
     * This cannot be deleted because it's in use by AndroidX.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q,
            publicAlternatives = "Use {@link android.graphics.fonts.FontFamily} instead.")
    public void abortCreation() {
        if (mBuilderPtr == 0) {
            throw new IllegalStateException("This FontFamily is already frozen or abandoned");
        }
        mNativeBuilderCleaner.run();
        mBuilderPtr = 0;
    }

    /**
     * This cannot be deleted because it's in use by AndroidX.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q,
            publicAlternatives = "Use {@link android.graphics.fonts.FontFamily} instead.")
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
            return false;
        }
    }

    /**
     * This cannot be deleted because it's in use by AndroidX.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q,
            publicAlternatives = "Use {@link android.graphics.fonts.FontFamily} instead.")
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
     *
     * This cannot be deleted because it's in use by AndroidX.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q,
            publicAlternatives = "Use {@link android.graphics.fonts.FontFamily} instead.")
    public boolean addFontFromAssetManager(AssetManager mgr, String path, int cookie,
            boolean isAsset, int ttcIndex, int weight, int isItalic,
            FontVariationAxis[] axes) {
        if (mBuilderPtr == 0) {
            throw new IllegalStateException("Unable to call addFontFromAsset after freezing.");
        }

        try {
            ByteBuffer buffer =  Font.Builder.createBuffer(mgr, path, isAsset, cookie);
            return addFontFromBuffer(buffer, ttcIndex, axes, weight, isItalic);
        } catch (IOException e) {
            return false;
        }
    }

    private static native long nInitBuilder(String langs, int variant);

    @CriticalNative
    private static native long nCreateFamily(long mBuilderPtr);

    @CriticalNative
    private static native long nGetBuilderReleaseFunc();

    @CriticalNative
    private static native long nGetFamilyReleaseFunc();
    // By passing -1 to weight argument, the weight value is resolved by OS/2 table in the font.
    // By passing -1 to italic argument, the italic value is resolved by OS/2 table in the font.
    private static native boolean nAddFont(long builderPtr, ByteBuffer font, int ttcIndex,
            int weight, int isItalic);
    private static native boolean nAddFontWeightStyle(long builderPtr, ByteBuffer font,
            int ttcIndex, int weight, int isItalic);

    // The added axis values are only valid for the next nAddFont* method call.
    @CriticalNative
    private static native void nAddAxisValue(long builderPtr, int tag, float value);
}
