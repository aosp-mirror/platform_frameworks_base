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
 * Delegate implementing the native methods of android.graphics.DiscretePathEffect
 *
 * Through the layoutlib_create tool, the original native methods of DiscretePathEffect have been
 * replaced by calls to methods of the same name in this delegate class.
 *
 * This class behaves like the original native implementation, but in Java, keeping previously
 * native data into its own objects and mapping them to int that are sent back and forth between
 * it and the original DiscretePathEffect class.
 *
 * Because this extends {@link PathEffect_Delegate}, there's no need to use a {@link DelegateManager},
 * as all the Shader classes will be added to the manager owned by {@link PathEffect_Delegate}.
 *
 * @see PathEffect_Delegate
 *
 */
public class DiscretePathEffect_Delegate extends PathEffect_Delegate {

    // ---- delegate data ----

    // ---- Public Helper methods ----

    @Override
    public Stroke getStroke(Paint_Delegate paint) {
        // FIXME
        return null;
    }

    @Override
    public boolean isSupported() {
        return false;
    }

    @Override
    public String getSupportMessage() {
        return "Discrete Path Effects are not supported in Layout Preview mode.";
    }

    // ---- native methods ----

    @LayoutlibDelegate
    /*package*/ static long nativeCreate(float length, float deviation) {
        DiscretePathEffect_Delegate newDelegate = new DiscretePathEffect_Delegate();
        return sManager.addNewDelegate(newDelegate);
    }

    // ---- Private delegate/helper methods ----
}
