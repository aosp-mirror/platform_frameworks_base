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
package org.apache.harmony.awt.gl.font;

import java.awt.Shape;
import java.awt.font.GlyphJustificationInfo;
import java.awt.font.GlyphMetrics;
import java.awt.image.BufferedImage;

public abstract class Glyph{

    // character of the glyph
    char glChar;
    
    // precise glyph metrics
    GlyphMetrics glMetrics;
    
    // glyph metrics in pixels
    GlyphMetrics glPointMetrics;
    
    //  glyph code of this Glyph
    int glCode;
    
    // justification info of this glyph
    GlyphJustificationInfo glJustInfo;
    
    // native font handle of the font corresponding to this glyph
    long pFont;
    
    // size of the font corresponding to this glyph
    int fontSize;
    
    // bitmap representation of the glyph
    byte[] bitmap = null;
    
    // Buffered image representation of the glyph
    BufferedImage image;
    
    // shape that representing the outline of this glyph
    Shape glOutline = null;

    /**
     * image bitmap parameters
     */
    
    //  top side bearing
    public int bmp_top = 0;
    
    // left side bearing
    public int bmp_left = 0;

    // number of bytes in row
    public int bmp_pitch;
    
    // number of rows
    public int bmp_rows;
    
    // width of the row
    public int bmp_width;

    /**
     *  Retruns handle to Native Font object
     */
    public long getPFont(){
        return this.pFont;
    }

    /**
     *  Retruns char value of this glyph object
     */
    public char getChar(){
        return glChar;
    }

    /**
     *  Retruns precise width of this glyph object
     */
    public int getWidth(){
        return Math.round((float)glMetrics.getBounds2D().getWidth());
    }

    /**
     *  Retruns precise height of this glyph object
     */
    public int getHeight(){
        return Math.round((float)glMetrics.getBounds2D().getHeight());
    }

    /**
     *  Retruns glyph code of this glyph object
     */
    public int getGlyphCode(){
        return glCode;
    }

    /**
     *  Retruns GlyphMetrics of this glyph object with precise metrics.
     */
    public GlyphMetrics getGlyphMetrics(){
        return glMetrics;
    }

    /**
     *  Retruns GlyphMetrics of this glyph object in pixels.
     */
    public GlyphMetrics getGlyphPointMetrics(){
        return glPointMetrics;
    }

    /**
     *  Retruns GlyphJustificationInfo of this glyph object
     */
    public GlyphJustificationInfo getGlyphJustificationInfo(){
        return glJustInfo;
    }

    /**
     *  Sets JustificationInfo of this glyph object
     * 
     * @param newJustInfo GlyphJustificationInfo object to set to the Glyph object 
     */
    public void setGlyphJustificationInfo(GlyphJustificationInfo newJustInfo){
        this.glJustInfo = newJustInfo;
    }

    /**
     * Returns an int array of 3 elements, so-called ABC structure that contains 
     * the width of the character:
     * 1st element = left side bearing of the glyph
     * 2nd element = width of the glyph
     * 3d element = right side bearing of the glyph 
     */
    public int[] getABC(){
        int[] abc = new int[3];
        abc[0] = (int)glMetrics.getLSB();
        abc[1] = (int)glMetrics.getBounds2D().getWidth();
        abc[2] = (int)glMetrics.getRSB();

        return abc;
    }

    /**
     * Sets BufferedImage representation of this glyph to the specified parameter.
     * 
     * @param newImage new BufferedImage object to be set as BufferedImage 
     * representation.
     */
    public void setImage(BufferedImage newImage){
        this.image = newImage;
    }

    /**
     * Returns true if this Glyph and specified object are equal.
     */
    @Override
    public boolean equals(Object obj){
         if (obj == this) {
            return true;
        }

        if (obj != null) {
          try {
            Glyph gl = (Glyph)obj;

            return  ((this.getChar() == gl.getChar())
              && (this.getGlyphMetrics().equals(gl.getGlyphMetrics()))
              && (this.getGlyphCode() == gl.getGlyphCode()));
          } catch (ClassCastException e) {
          }
        }

        return false;
    }

    /**
     * Returns height of the glyph in points. 
     */
    public int getPointHeight(){
        return (int)glPointMetrics.getBounds2D().getHeight();
    }

    /**
     * Returns width of the glyph in points. 
     */
    public int getPointWidth(){
        return (int)glPointMetrics.getBounds2D().getWidth();
    }

    public Shape getShape(){
        if (glOutline == null){
            glOutline = initOutline(this.glChar);
        }
        return glOutline;
    }

    /**
     * Sets BufferedImage representation of this glyph.
     */
    public BufferedImage getImage(){
        //!! Implementation classes must override this method
        return null;
    }

    /**
     *  Returns array of bytes, representing image of this glyph
     */
    public abstract byte[] getBitmap();

    /**
     * Returns shape that represents outline of the specified character. 
     * 
     * @param c specified character
     */
    public abstract Shape initOutline(char c);

}


