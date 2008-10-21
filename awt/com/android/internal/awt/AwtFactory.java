/*
 * Copyright 2007, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at 
 *
 *     http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License.
 */

package com.android.internal.awt;

import java.awt.Graphics2D;
import java.awt.Toolkit;

import org.apache.harmony.awt.wtk.GraphicsFactory;

import android.graphics.Canvas;
import android.graphics.Paint;

public class AwtFactory {
    
    private static GraphicsFactory gf;
    
    /**
     * Use this method to get acces to AWT drawing primitives and to
     * render into the surface area of a Android widget. Origin and 
     * clip of the returned graphics object are the same as in the
     * corresponding Android widget. 
     * 
     * @param c Canvas of the android widget to draw into
     * @param p The default drawing parameters such as font, 
     * stroke, foreground and background colors, etc.
     * @return The AWT Graphics object that makes all AWT 
     * drawing primitives available in the androind world.
     */
    public static Graphics2D getAwtGraphics(Canvas c, Paint p) {
        // AWT?? TODO: test it!
        if (null == gf) {
            Toolkit tk = Toolkit.getDefaultToolkit();
            gf = tk.getGraphicsFactory();
        }
        return gf.getGraphics2D(c, p);
    }

}
