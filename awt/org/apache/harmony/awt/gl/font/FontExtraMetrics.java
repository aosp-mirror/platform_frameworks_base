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
 * 
 */
package org.apache.harmony.awt.gl.font;

/**
 * Extra font metrics: sub/superscripts sizes, offsets, average char width.
 */
public class FontExtraMetrics {
    
    /* !! Subscript/superscript metrics are undefined for Type1. As a possible 
     * solution we can use values for Type1, that are proportionate to TrueType
     * ones:
     *  SubscriptSizeX == 0.7 * fontSize
     *  SubscriptSizeY == 0.65 * fontSize
     *  SubscriptOffsetX == 0;
     *  SubscriptOffsetY == 0.15 * fontSize;
     *  SuperscriptSizeX == 0.7 * fontSize
     *  SuperscriptSizeY == 0.65 * fontSize
     *  SuperscriptOffsetX == 0;
     *  SuperscriptOffsetY == 0.45 * fontSize
     *  
     */
    
    /*
     * The average width of characters in the font.
     */
    private float lAverageCharWidth;
    
    /*
     * Horizontal size for subscripts.
     */
    private float lSubscriptSizeX;

    /*
     * Vertical size for subscripts.
     */
    private float lSubscriptSizeY; 
    
    /*
     * Horizontal offset for subscripts, the offset from the character origin 
     * to the origin of the subscript character.
     */
    private float lSubscriptOffsetX; 

    /*
     * Vertical offset for subscripts, the offset from the character origin 
     * to the origin of the subscript character.
     */
    private float lSubscriptOffsetY;
    
    /*
     * Horizontal size for superscripts.
     */
    private float lSuperscriptSizeX; 

    /*
     * Vertical size for superscripts.
     */
    private float lSuperscriptSizeY;
    
    /*
     * Horizontal offset for superscripts, the offset from the character 
     * base line to the base line of the superscript character.
     */
    private float lSuperscriptOffsetX;

    /*
     * Vertical offset for superscripts, the offset from the character 
     * base line to the base line of the superscript character.
     */
    private float lSuperscriptOffsetY;
    
    public FontExtraMetrics(){
        // default constructor
    }

    public FontExtraMetrics(float[] metrics){
        lAverageCharWidth = metrics[0];
        lSubscriptSizeX = metrics[1];
        lSubscriptSizeY = metrics[2];
        lSubscriptOffsetX = metrics[3];
        lSubscriptOffsetY = metrics[4];
        lSuperscriptSizeX = metrics[5];
        lSuperscriptSizeY = metrics[6];
        lSuperscriptOffsetX = metrics[7];
        lSuperscriptOffsetY = metrics[8];
    }

    public float getAverageCharWidth(){
        return lAverageCharWidth;
    }
    
    public float getSubscriptSizeX(){
        return lSubscriptSizeX;
    }

    public float getSubscriptSizeY(){
        return lSubscriptSizeY;
    }

    public float getSubscriptOffsetX(){
        return lSubscriptOffsetX;
    }

    public float getSubscriptOffsetY(){
        return lSubscriptOffsetY;
    }

    public float getSuperscriptSizeX(){
        return lSuperscriptSizeX;
    }

    public float getSuperscriptSizeY(){
        return lSuperscriptSizeY;
    }

    public float getSuperscriptOffsetX(){
        return lSuperscriptOffsetX;
    }

    public float getSuperscriptOffsetY(){
        return lSuperscriptOffsetY;
    }
    
    
}
