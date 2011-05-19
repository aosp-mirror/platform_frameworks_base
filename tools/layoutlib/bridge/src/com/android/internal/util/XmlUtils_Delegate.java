/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.internal.util;

import com.android.tools.layoutlib.annotations.LayoutlibDelegate;


/**
 * Delegate used to provide new implementation of a select few methods of {@link XmlUtils}
 *
 * Through the layoutlib_create tool, the original  methods of XmlUtils have been replaced
 * by calls to methods of the same name in this delegate class.
 *
 */
public class XmlUtils_Delegate {

    @LayoutlibDelegate
    /*package*/ static final int convertValueToInt(CharSequence charSeq, int defaultValue) {
        if (null == charSeq)
            return defaultValue;

        String nm = charSeq.toString();

        // This code is copied from the original implementation. The issue is that
        // The Dalvik libraries are able to handle Integer.parse("XXXXXXXX", 16) where XXXXXXX
        // is > 80000000 but the Java VM cannot.

        int sign = 1;
        int index = 0;
        int len = nm.length();
        int base = 10;

        if ('-' == nm.charAt(0)) {
            sign = -1;
            index++;
        }

        if ('0' == nm.charAt(index)) {
            //  Quick check for a zero by itself
            if (index == (len - 1))
                return 0;

            char c = nm.charAt(index + 1);

            if ('x' == c || 'X' == c) {
                index += 2;
                base = 16;
            } else {
                index++;
                base = 8;
            }
        }
        else if ('#' == nm.charAt(index)) {
            index++;
            base = 16;
        }

        return ((int)Long.parseLong(nm.substring(index), base)) * sign;
    }
}
