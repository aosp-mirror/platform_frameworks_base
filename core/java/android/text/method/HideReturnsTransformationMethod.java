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

package android.text.method;

import android.annotation.UnsupportedAppUsage;
import android.os.Build;

/**
 * This transformation method causes any carriage return characters (\r)
 * to be hidden by displaying them as zero-width non-breaking space
 * characters (\uFEFF).
 */
public class HideReturnsTransformationMethod
extends ReplacementTransformationMethod {
    private static char[] ORIGINAL = new char[] { '\r' };
    private static char[] REPLACEMENT = new char[] { '\uFEFF' };

    /**
     * The character to be replaced is \r.
     */
    protected char[] getOriginal() {
        return ORIGINAL;
    }

    /**
     * The character that \r is replaced with is \uFEFF.
     */
    protected char[] getReplacement() {
        return REPLACEMENT;
    }

    public static HideReturnsTransformationMethod getInstance() {
        if (sInstance != null)
            return sInstance;

        sInstance = new HideReturnsTransformationMethod();
        return sInstance;
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private static HideReturnsTransformationMethod sInstance;
}
