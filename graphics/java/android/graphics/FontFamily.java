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

import java.io.File;

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
        mNativePtr = nCreateFamily();
        mNativePtr = nCreateFamily();
        if (mNativePtr == 0) {
            throw new RuntimeException();
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

    public boolean addFont(File path) {
        return nAddFont(mNativePtr, path.getAbsolutePath());
    }

    static native long nCreateFamily();
    static native void nUnrefFamily(long nativePtr);
    static native boolean nAddFont(long nativeFamily, String path);
}
