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

import java.awt.Stroke;

/**
 * Delegate implementing the native methods of android.graphics.PathEffect
 *
 * Through the layoutlib_create tool, the original native methods of PathEffect have been replaced
 * by calls to methods of the same name in this delegate class.
 *
 * This class behaves like the original native implementation, but in Java, keeping previously
 * native data into its own objects and mapping them to int that are sent back and forth between
 * it and the original PathEffect class.
 *
 * This also serve as a base class for all PathEffect delegate classes.
 *
 * @see DelegateManager
 *
 */
public abstract class PathEffect_Delegate {

    // ---- delegate manager ----
    protected static final DelegateManager<PathEffect_Delegate> sManager =
            new DelegateManager<PathEffect_Delegate>(PathEffect_Delegate.class);

    // ---- delegate helper data ----

    // ---- delegate data ----

    // ---- Public Helper methods ----

    public static PathEffect_Delegate getDelegate(long nativeShader) {
        return sManager.getDelegate(nativeShader);
    }

    public abstract Stroke getStroke(Paint_Delegate paint);
    public abstract boolean isSupported();
    public abstract String getSupportMessage();


    // ---- native methods ----

    @LayoutlibDelegate
    /*package*/ static void nativeDestructor(long native_patheffect) {
        sManager.removeJavaReferenceFor(native_patheffect);
    }

    // ---- Private delegate/helper methods ----

}
