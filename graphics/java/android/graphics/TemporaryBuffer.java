/*
 * Copyright (C) 2007 The Android Open Source Project
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

import com.android.internal.util.ArrayUtils;

/**
 * @hide
 */
public class TemporaryBuffer {
    public static char[] obtain(int len) {
        char[] buf;

        synchronized (TemporaryBuffer.class) {
            buf = sTemp;
            sTemp = null;
        }

        if (buf == null || buf.length < len) {
            buf = new char[ArrayUtils.idealCharArraySize(len)];
        }

        return buf;
    }

    public static void recycle(char[] temp) {
        if (temp.length > 1000) return;

        synchronized (TemporaryBuffer.class) {
            sTemp = temp;
        }
    }

    private static char[] sTemp = null;
}
