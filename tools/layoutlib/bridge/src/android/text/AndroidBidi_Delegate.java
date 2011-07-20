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

import com.android.tools.layoutlib.annotations.LayoutlibDelegate;


/**
 * Delegate used to provide new implementation for the native methods of {@link AndroidBidi}
 *
 * Through the layoutlib_create tool, the original  methods of AndroidBidi have been replaced
 * by calls to methods of the same name in this delegate class.
 *
 */
public class AndroidBidi_Delegate {

    @LayoutlibDelegate
    /*package*/ static int runBidi(int dir, char[] chs, byte[] chInfo, int n, boolean haveInfo) {
        // return the equivalent of Layout.DIR_LEFT_TO_RIGHT
        // TODO: actually figure the direction.
        return 0;
    }
}
