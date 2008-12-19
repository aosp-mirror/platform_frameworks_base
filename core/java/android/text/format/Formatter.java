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

package android.text.format;

import android.content.Context;

/**
 * Utility class to aid in formatting common values that are not covered
 * by the standard java.util.Formatter.
 */
public final class Formatter {

    /**
     * Formats a content size to be in the form of bytes, kilobytes, megabytes, etc
     * 
     * @param context Context to use to load the localized units
     * @param number size value to be formated
     * @return formated string with the number
     */
    public static String formatFileSize(Context context, long number) {
        if (context == null) {
            return "";
        }

        float result = number;
        int suffix = com.android.internal.R.string.byteShort;
        if (result > 900) {
            suffix = com.android.internal.R.string.kilobyteShort;
            result = result / 1024;
        }
        if (result > 900) {
            suffix = com.android.internal.R.string.megabyteShort;
            result = result / 1024;
        }
        if (result > 900) {
            suffix = com.android.internal.R.string.gigabyteShort;
            result = result / 1024;
        }
        if (result > 900) {
            suffix = com.android.internal.R.string.terabyteShort;
            result = result / 1024;
        }
        if (result > 900) {
            suffix = com.android.internal.R.string.petabyteShort;
            result = result / 1024;
        }
        if (result < 100) {
            return String.format("%.2f%s", result, context.getText(suffix).toString());
        }
        return String.format("%.0f%s", result, context.getText(suffix).toString());
    }
    
    /**
     * Returns a string in the canonical IP format ###.###.###.### from a packed integer containing
     * the IP address.  The IP address is expected to be in little-endian format (LSB first). That
     * is, 0x01020304 will return "4.3.2.1".
     * 
     * @param addr the IP address as a packed integer with LSB first.
     * @return string with canonical IP address format.
     */
    public static String formatIpAddress(int addr) {
        StringBuffer buf = new StringBuffer();
        buf.append(addr  & 0xff).append('.').
            append((addr >>>= 8) & 0xff).append('.').
            append((addr >>>= 8) & 0xff).append('.').
            append((addr >>>= 8) & 0xff);
        return buf.toString();
    }
}
