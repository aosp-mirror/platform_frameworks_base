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
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import java.awt.Graphics2D;

/**
 * Delegate implementing the native methods of android.graphics.ColorFilter
 *
 * Through the layoutlib_create tool, the original native methods of ColorFilter have been replaced
 * by calls to methods of the same name in this delegate class.
 *
 * This class behaves like the original native implementation, but in Java, keeping previously
 * native data into its own objects and mapping them to int that are sent back and forth between
 * it and the original ColorFilter class.
 *
 * This also serve as a base class for all ColorFilter delegate classes.
 *
 * @see DelegateManager
 *
 */
public abstract class ColorFilter_Delegate {

    // ---- delegate manager ----
    protected static final DelegateManager<ColorFilter_Delegate> sManager =
            new DelegateManager<ColorFilter_Delegate>(ColorFilter_Delegate.class);

    // ---- delegate helper data ----

    // ---- delegate data ----

    // ---- Public Helper methods ----

    public static ColorFilter_Delegate getDelegate(long nativeShader) {
        return sManager.getDelegate(nativeShader);
    }

    public abstract String getSupportMessage();

    public boolean isSupported() {
        return false;
    }

    public void applyFilter(Graphics2D g, int width, int height) {
        // This should never be called directly. If supported, the sub class should override this.
        assert false;
    }

    // ---- native methods ----

    @LayoutlibDelegate
    /*package*/ static void destroyFilter(long native_instance) {
        sManager.removeJavaReferenceFor(native_instance);
    }

    // ---- Private delegate/helper methods ----
}
