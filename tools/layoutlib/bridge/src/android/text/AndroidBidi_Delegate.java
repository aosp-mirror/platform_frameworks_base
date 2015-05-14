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

package android.text;

import com.android.ide.common.rendering.api.LayoutLog;
import com.android.layoutlib.bridge.Bridge;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.icu.text.Bidi;

/**
 * Delegate used to provide new implementation for the native methods of {@link AndroidBidi}
 *
 * Through the layoutlib_create tool, the original  methods of AndroidBidi have been replaced
 * by calls to methods of the same name in this delegate class.
 *
 */
public class AndroidBidi_Delegate {

    @LayoutlibDelegate
    /*package*/ static int runBidi(int dir, char[] chars, byte[] charInfo, int count,
            boolean haveInfo) {

        switch (dir) {
        case 0: // Layout.DIR_REQUEST_LTR
            dir = Bidi.LTR;
            break;
        case 1: // Layout.DIR_REQUEST_RTL
            dir = Bidi.RTL;
            break;
        case -1: // Layout.DIR_REQUEST_DEFAULT_RTL
            dir = Bidi.LEVEL_DEFAULT_RTL;
            break;
        case -2: // Layout.DIR_REQUEST_DEFAULT_LTR
            dir = Bidi.LEVEL_DEFAULT_LTR;
            break;
        default:
            // Invalid code. Log error, assume LEVEL_DEFAULT_LTR and continue.
            Bridge.getLog().error(LayoutLog.TAG_BROKEN, "Invalid direction flag", null);
            dir = Bidi.LEVEL_DEFAULT_LTR;
        }
        Bidi bidi = new Bidi(chars, 0, null, 0, count, dir);
        if (charInfo != null) {
            for (int i = 0; i < count; ++i)
            charInfo[i] = bidi.getLevelAt(i);
        }
        return bidi.getParaLevel();
    }
}
