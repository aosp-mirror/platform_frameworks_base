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

/**
 * A family of typefaces with different styles.
 *
 * @hide
 */
public class FontFamily {
    /**
     * @hide
     */
    public long mNativePtr;

    public FontFamily() {
        mNativePtr = nCreateFamily(null, 0);
        if (mNativePtr == 0) {
            throw new IllegalStateException("error creating native FontFamily");
        }
    }

    public FontFamily(String lang, String variant) {
        int varEnum = 0;
        if ("compact".equals(variant)) {
            varEnum = 1;
        } else if ("elegant".equals(variant)) {
            varEnum = 2;
        }
        mNativePtr = nCreateFamily(lang, varEnum);
        if (mNativePtr == 0) {
            throw new IllegalStateException("error creating native FontFamily");
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            nUnrefFamily(mNativePtr);
        } finally {
            super.finalize();
        }
    }

    public boolean addFont(String path) {
        return nAddFont(mNativePtr, path);
    }

    public boolean addFontWeightStyle(String path, int weight, boolean style) {
        return nAddFontWeightStyle(mNativePtr, path, weight, style);
    }

    public boolean addFontFromAsset(AssetManager mgr, String path) {
        return nAddFontFromAsset(mNativePtr, mgr, path);
    }

    private static native long nCreateFamily(String lang, int variant);
    private static native void nUnrefFamily(long nativePtr);
    private static native boolean nAddFont(long nativeFamily, String path);
    private static native boolean nAddFontWeightStyle(long nativeFamily, String path,
            int weight, boolean isItalic);
    private static native boolean nAddFontFromAsset(long nativeFamily, AssetManager mgr,
            String path);
}
