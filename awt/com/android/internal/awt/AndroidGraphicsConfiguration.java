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

import com.android.internal.awt.AndroidGraphics2D;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.VolatileImage;

import android.graphics.Canvas;

public class AndroidGraphicsConfiguration extends GraphicsConfiguration {

    @Override
    public BufferedImage createCompatibleImage(int width, int height) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public BufferedImage createCompatibleImage(int width, int height,
            int transparency) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public VolatileImage createCompatibleVolatileImage(int width, int height) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public VolatileImage createCompatibleVolatileImage(int width, int height,
            int transparency) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Rectangle getBounds() {
        Canvas c = AndroidGraphics2D.getAndroidCanvas();
        if(c != null)
            return new Rectangle(0, 0, c.getWidth(), c.getHeight());
        return null;
    }

    @Override
    public ColorModel getColorModel() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public ColorModel getColorModel(int transparency) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public AffineTransform getDefaultTransform() {
        return new AffineTransform();
    }

    @Override
    public GraphicsDevice getDevice() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public AffineTransform getNormalizingTransform() {
        // TODO Auto-generated method stub
        return null;
    }

}
