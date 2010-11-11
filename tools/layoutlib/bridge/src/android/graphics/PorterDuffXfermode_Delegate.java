/*
 * Copyright (C) 2010 The Android Open Source Project
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

import com.android.layoutlib.bridge.impl.DelegateManager;

/**
 * Delegate implementing the native methods of android.graphics.PorterDuffXfermode
 *
 * Through the layoutlib_create tool, the original native methods of PorterDuffXfermode have been
 * replaced by calls to methods of the same name in this delegate class.
 *
 * This class behaves like the original native implementation, but in Java, keeping previously
 * native data into its own objects and mapping them to int that are sent back and forth between
 * it and the original PorterDuffXfermode class.
 *
 * Because this extends {@link Xfermode_Delegate}, there's no need to use a
 * {@link DelegateManager}, as all the PathEffect classes will be added to the manager owned by
 * {@link Xfermode_Delegate}.
 *
 */
public class PorterDuffXfermode_Delegate extends Xfermode_Delegate {

    // ---- delegate data ----

    private final int mMode;

    // ---- Public Helper methods ----

    public int getMode() {
        return mMode;
    }

    // ---- native methods ----

    /*package*/ static int nativeCreateXfermode(int mode) {
        PorterDuffXfermode_Delegate newDelegate = new PorterDuffXfermode_Delegate(mode);
        return sManager.addDelegate(newDelegate);
    }

    // ---- Private delegate/helper methods ----

    private PorterDuffXfermode_Delegate(int mode) {
        mMode = mode;
    }

}
