/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.compat.annotation.UnsupportedAppUsage;

/**
 * @hide
 */
public class TableMaskFilter extends MaskFilter {

    public TableMaskFilter(byte[] table) {
        if (table.length < 256) {
            throw new RuntimeException("table.length must be >= 256");
        }
        native_instance = nativeNewTable(table);
    }
    
    private TableMaskFilter(long ni) {
        native_instance = ni;
    }
    
    @UnsupportedAppUsage
    public static TableMaskFilter CreateClipTable(int min, int max) {
        return new TableMaskFilter(nativeNewClip(min, max));
    }
    
    public static TableMaskFilter CreateGammaTable(float gamma) {
        return new TableMaskFilter(nativeNewGamma(gamma));
    }

    private static native long nativeNewTable(byte[] table);
    private static native long nativeNewClip(int min, int max);
    private static native long nativeNewGamma(float gamma);
}
