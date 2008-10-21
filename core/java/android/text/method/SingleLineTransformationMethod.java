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

import android.graphics.Rect;
import android.text.Editable;
import android.text.GetChars;
import android.text.Spannable;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.TextUtils;
import android.view.View;

/**
 * This transformation method causes any newline characters (\n) to be
 * displayed as spaces instead of causing line breaks.
 */
public class SingleLineTransformationMethod
extends ReplacementTransformationMethod {
    private static char[] ORIGINAL = new char[] { '\n' };
    private static char[] REPLACEMENT = new char[] { ' ' };

    /**
     * The character to be replaced is \n.
     */
    protected char[] getOriginal() {
        return ORIGINAL;
    }

    /**
     * The character \n is replaced with is space.
     */
    protected char[] getReplacement() {
        return REPLACEMENT;
    }

    public static SingleLineTransformationMethod getInstance() {
        if (sInstance != null)
            return sInstance;

        sInstance = new SingleLineTransformationMethod();
        return sInstance;
    }

    private static SingleLineTransformationMethod sInstance;
}
