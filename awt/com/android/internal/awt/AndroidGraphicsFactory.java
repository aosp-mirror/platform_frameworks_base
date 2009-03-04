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

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.peer.FontPeer;

import org.apache.harmony.awt.gl.MultiRectArea;
import org.apache.harmony.awt.gl.font.AndroidFont;
import org.apache.harmony.awt.gl.font.FontManager;
import org.apache.harmony.awt.gl.font.FontMetricsImpl;
import org.apache.harmony.awt.gl.font.AndroidFontManager;
import org.apache.harmony.awt.wtk.NativeWindow;
import org.apache.harmony.awt.wtk.WindowFactory;
import org.apache.harmony.awt.gl.CommonGraphics2DFactory;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.content.Context;

public class AndroidGraphicsFactory extends CommonGraphics2DFactory {
    
    public GraphicsEnvironment createGraphicsEnvironment(WindowFactory wf) {
        // TODO Auto-generated method stub
        return null;
    }

    public Font embedFont(String fontFilePath) {
        // TODO Auto-generated method stub
        return null;
    }

    public FontManager getFontManager() {
        return AndroidFontManager.inst;
    }

    public FontMetrics getFontMetrics(Font font) {
        return new FontMetricsImpl(font);
    }

    public FontPeer getFontPeer(Font font) {
        //return getFontManager().getFontPeer(font.getName(), font.getStyle(), font.getSize());
        return new AndroidFont(font.getName(), font.getStyle(), font.getSize());
    }

    public Graphics2D getGraphics2D(NativeWindow win, int translateX,
            int translateY, MultiRectArea clip) {
        // TODO Auto-generated method stub
        return null;
    }

    public Graphics2D getGraphics2D(NativeWindow win, int translateX,
            int translateY, int width, int height) {
        // TODO Auto-generated method stub
        return null;
    }

    public Graphics2D getGraphics2D(Context ctx, Canvas c, Paint p) {
        return AndroidGraphics2D.getInstance(ctx, c, p);
    }

    public Graphics2D getGraphics2D(Canvas c, Paint p) {
        throw new RuntimeException("Not supported!");
    }

    public Graphics2D getGraphics2D() {
        return AndroidGraphics2D.getInstance();
    }

}
