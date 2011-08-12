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

package android.view;

import com.android.layoutlib.bridge.android.BridgeWindowManager;
import com.android.layoutlib.bridge.impl.RenderAction;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.os.RemoteException;

/**
 * Delegate used to provide new implementation of a select few methods of {@link Display}
 *
 * Through the layoutlib_create tool, the original  methods of Display have been replaced
 * by calls to methods of the same name in this delegate class.
 *
 */
public class Display_Delegate {

    // ---- Overridden methods ----

    @LayoutlibDelegate
    public static IWindowManager getWindowManager() {
        return RenderAction.getCurrentContext().getIWindowManager();
    }

    // ---- Native methods ----

    @LayoutlibDelegate
    /*package*/ static int getDisplayCount() {
        return 1;
    }

    @LayoutlibDelegate
    /** @hide special for when we are faking the screen size. */
    /*package*/ static int getRawWidth(Display theDisplay) {
        // same as real since we're not faking compatibility mode.
        return RenderAction.getCurrentContext().getIWindowManager().getMetrics().widthPixels;
    }

    @LayoutlibDelegate
    /** @hide special for when we are faking the screen size. */
    /*package*/ static int getRawHeight(Display theDisplay) {
        // same as real since we're not faking compatibility mode.
        return RenderAction.getCurrentContext().getIWindowManager().getMetrics().heightPixels;
    }

    @LayoutlibDelegate
    /*package*/ static int getOrientation(Display theDisplay) {
        try {
            // always dynamically query for the current window manager
            return getWindowManager().getRotation();
        } catch (RemoteException e) {
            // this will never been thrown since this is not a true RPC.
        }

        return Surface.ROTATION_0;
    }

    @LayoutlibDelegate
    /*package*/ static void nativeClassInit() {
        // not needed for now.
    }

    @LayoutlibDelegate
    /*package*/ static void init(Display theDisplay, int display) {
        // always dynamically query for the current window manager
        BridgeWindowManager wm = RenderAction.getCurrentContext().getIWindowManager();
        theDisplay.mDensity = wm.getMetrics().density;
        theDisplay.mDpiX = wm.getMetrics().xdpi;
        theDisplay.mDpiY = wm.getMetrics().ydpi;
    }
}
