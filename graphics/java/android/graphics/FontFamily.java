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
import android.text.FontConfig;
import android.util.Log;
import dalvik.annotation.optimization.CriticalNative;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.List;

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

    public FontFamily(String lang, String variant) {
        int varEnum = 0;
        if ("compact".equals(variant)) {
            varEnum = 1;
        } else if ("elegant".equals(variant)) {
            varEnum = 2;
        }
        mBuilderPtr = nInitBuilder(lang, varEnum);
    }

    public void freeze() {
        if (mBuilderPtr == 0) {
            throw new IllegalStateException("This FontFamily is already frozen");
        }
        mNativePtr = nCreateFamily(mBuilderPtr);
        mBuilderPtr = 0;
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

    public boolean addFont(String path, int ttcIndex) {
        if (mBuilderPtr == 0) {
            throw new IllegalStateException("Unable to call addFont after freezing.");
        }
        try (FileInputStream file = new FileInputStream(path)) {
            FileChannel fileChannel = file.getChannel();
            long fontSize = fileChannel.size();
            ByteBuffer fontBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fontSize);
            return nAddFont(mBuilderPtr, fontBuffer, ttcIndex);
        } catch (IOException e) {
            Log.e(TAG, "Error mapping font file " + path);
            return false;
        }
    }

    public boolean addFontWeightStyle(ByteBuffer font, int ttcIndex, List<FontConfig.Axis> axes,
            int weight, boolean style) {
        if (mBuilderPtr == 0) {
            throw new IllegalStateException("Unable to call addFontWeightStyle after freezing.");
        }
        return nAddFontWeightStyle(mBuilderPtr, font, ttcIndex, axes, weight, style);
    }

    public boolean addFontFromAssetManager(AssetManager mgr, String path, int cookie,
            boolean isAsset) {
        if (mBuilderPtr == 0) {
            throw new IllegalStateException("Unable to call addFontFromAsset after freezing.");
        }
        return nAddFontFromAssetManager(mBuilderPtr, mgr, path, cookie, isAsset);
    }

    private static native long nInitBuilder(String lang, int variant);

    @CriticalNative
    private static native long nCreateFamily(long mBuilderPtr);

    @CriticalNative
    private static native void nAbort(long mBuilderPtr);

    @CriticalNative
    private static native void nUnrefFamily(long nativePtr);
    private static native boolean nAddFont(long builderPtr, ByteBuffer font, int ttcIndex);
    private static native boolean nAddFontWeightStyle(long builderPtr, ByteBuffer font,
            int ttcIndex, List<FontConfig.Axis> listOfAxis,
            int weight, boolean isItalic);
    private static native boolean nAddFontFromAssetManager(long builderPtr, AssetManager mgr,
            String path, int cookie, boolean isAsset);
}
