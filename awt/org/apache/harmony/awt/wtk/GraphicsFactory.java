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
 * @author Pavel Dolgov, Alexey A. Petrenko, Oleg V. Khaschansky
 * @version $Revision$
 */
package org.apache.harmony.awt.wtk;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.peer.FontPeer;
import org.apache.harmony.awt.gl.MultiRectArea;
import org.apache.harmony.awt.gl.font.FontManager;

import android.graphics.Canvas;
import android.graphics.Paint;


/**
 * GraphicsFactory interface defines methods for Graphics2D 
 * and font stuff instances factories.
 */
public interface GraphicsFactory {
    static final FontMetrics cacheFM[] =  new FontMetrics[10];
    
    /**
     * This method creates Graphics2D instance for specified native window.
     *  
     * @param win Native window to draw
     * @param translateX Translation along X axis
     * @param translateY Translation along Y axis
     * @param clip Clipping area for a new Graphics2D instance
     * @return New Graphics2D instance for specified native window
     * @deprecated
     */
    @Deprecated
    Graphics2D getGraphics2D(NativeWindow win, int translateX, int translateY, MultiRectArea clip);

    /**
     * This method creates Graphics2D instance for specified native window.
     *  
     * @param win Native window to draw
     * @param translateX Translation along X axis
     * @param translateY Translation along Y axis
     * @param width Width of drawing area
     * @param height Height of drawing area
     * @return New Graphics2D instance for specified native window
     */
    Graphics2D getGraphics2D(NativeWindow win, int translateX, int translateY, int width, int height);
    // ???AWT: not standard harmony
    Graphics2D getGraphics2D(Canvas c, Paint p);
    
    /**
     * Creates instance of GraphicsEnvironment for specified WindowFactory
     *  
     * @param wf WindowFactory
     * @return New instance of GraphicsEnvironment
     */
    GraphicsEnvironment createGraphicsEnvironment(WindowFactory wf);
    
    // Font methods
    FontMetrics getFontMetrics(Font font);
    FontManager getFontManager();
    FontPeer getFontPeer(Font font);
    Font embedFont(String fontFilePath);
}
