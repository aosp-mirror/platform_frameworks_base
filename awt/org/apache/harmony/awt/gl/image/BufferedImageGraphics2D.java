/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
/**
 * @author Alexey A. Petrenko
 * @version $Revision$
 */
package org.apache.harmony.awt.gl.image;

import java.awt.Graphics;
import java.awt.GraphicsConfiguration;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.WritableRaster;

import org.apache.harmony.awt.gl.CommonGraphics2D;
import org.apache.harmony.awt.gl.Surface;
import org.apache.harmony.awt.gl.render.JavaBlitter;
import org.apache.harmony.awt.gl.render.NativeImageBlitter;

/**
 * BufferedImageGraphics2D is implementation of CommonGraphics2D for
 * drawing on buffered images. 
 */
public class BufferedImageGraphics2D extends CommonGraphics2D {
    private BufferedImage bi = null;
    private Rectangle bounds = null;

    public BufferedImageGraphics2D(BufferedImage bi) {
        super();
        this.bi = bi;
        this.bounds = new Rectangle(0, 0, bi.getWidth(), bi.getHeight());
        clip(bounds);
        dstSurf = Surface.getImageSurface(bi);
        if(dstSurf.isNativeDrawable()){
            blitter = NativeImageBlitter.getInstance();
        }else{
            blitter = JavaBlitter.getInstance();
        }
    }

    @Override
    public void copyArea(int x, int y, int width, int height, int dx, int dy) {
    }

    @Override
    public Graphics create() {
        BufferedImageGraphics2D res = new BufferedImageGraphics2D(bi);
        copyInternalFields(res);
        return res;
    }

    @Override
    public GraphicsConfiguration getDeviceConfiguration() {
        return null;
    }

    public ColorModel getColorModel() {
        return bi.getColorModel();
    }

    public WritableRaster getWritableRaster() {
        return bi.getRaster();
    }
}