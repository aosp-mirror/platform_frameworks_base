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
 * @author Ilya S. Okomin
 * @version $Revision$
 */
package org.apache.harmony.awt.gl;

import java.awt.Graphics2D;
import java.awt.font.GlyphVector;

public abstract class TextRenderer {
    
    /**
     * Draws string on specified Graphics at desired position.
     * 
     * @param g specified Graphics2D object
     * @param str String object to draw
     * @param x start X position to draw
     * @param y start Y position to draw
     */
    public abstract void drawString(Graphics2D g, String str, float x, float y);

    /**
     * Draws string on specified Graphics at desired position.
     * 
     * @param g specified Graphics2D object
     * @param str String object to draw
     * @param x start X position to draw
     * @param y start Y position to draw
     */    
    public void drawString(Graphics2D g, String str, int x, int y){
        drawString(g, str, (float)x, (float)y);
    }

    /**
     * Draws GlyphVector on specified Graphics at desired position.
     * 
     * @param g specified Graphics2D object
     * @param glyphVector GlyphVector object to draw
     * @param x start X position to draw
     * @param y start Y position to draw
     */
    public abstract void drawGlyphVector(Graphics2D g, GlyphVector glyphVector, float x, float y);
}
