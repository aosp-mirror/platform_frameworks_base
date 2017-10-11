/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.view;

import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.content.Context;
import android.content.res.Resources;

public class PointerIcon_Delegate {

    @LayoutlibDelegate
    /*package*/ static void loadResource(PointerIcon icon, Context context, Resources resources,
            int resourceId) {
        // HACK: This bypasses the problem of having an enum resolved as a resourceId.
        // PointerIcon would not be displayed by layoutlib anyway, so we always return the null
        // icon.
    }
}
