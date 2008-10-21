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


import com.android.internal.awt.AndroidGraphics2D;
import com.android.internal.awt.AndroidGraphicsFactory;

import java.awt.Graphics2D;
import java.awt.Toolkit;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.peer.FontPeer;

import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;
import java.util.ArrayList;
import java.util.Locale;

import org.apache.harmony.awt.internal.nls.Messages;

import android.graphics.Paint;

/**
 * Abstract class for platform dependent peer implementation of the Font class.
 */
public abstract class FontPeerImpl implements FontPeer{

    // ascent of this font peer (in pixels)
    int ascent;

    // descent of this font peer (in pixels)
    int descent;

    // leading of this font peer (in pixels) 
    int leading;

    // logical maximum advance of this font peer (in pixels)
    int maxAdvance;

    // the height of this font peer
    float height;

    // the style of this font peer
    int style;

    // the point size of this font peer (in pixels)
    int size;

    // the logical hight of this font peer (in pixels)
    int logicalHeight;

    // the name of this font peer
    String name;

    // family name of this font peer
    String fontFamilyName;

    // the Face name of this font peer
    String faceName;

    // bounds rectanlge of the largest character in this font peer
    Rectangle2D maxCharBounds;

    // italic angle value of this font peer
    float italicAngle = 0.0f;

    // the number of glyphs supported by this font peer
    int numGlyphs = 0;

    // native font handle
    long pFont;

    // cached line metrics object
    LineMetricsImpl nlm;

    // the postscript name of this font peer
    String psName = null;

    /**
     * Default glyph index, that is used, when the desired glyph
     * is unsupported in this Font.
     */
    public char defaultChar = (char)0xFFFF;

    /**
     * Uniform LineMetrics flag, that is false for CompositeFont.  
     * Default value is true.
     */
    boolean uniformLM = true;

    /**
     * Flag of the type of this Font that is indicate is the Font
     * has TrueType or Type1 type. Default value is FONT_TYPE_UNDEF. 
     */
    int fontType = FontManager.FONT_TYPE_UNDEF;

    /**
     * Flag if this Font was created from stream, 
     * this parameter used in finilize method.
     */ 
    private boolean createdFromStream = false;  
    
    // temorary Font file name, if this FontPeerImpl was created from InputStream 
    private String tempFontFileName = null;     
    
    // cached FontExtraMetrics object related to this font peer
    FontExtraMetrics extraMetrix = null;

    public abstract FontExtraMetrics getExtraMetrics();
    
    /**
     * Returns LineMetrics object with specified parameters
     * @param str specified String
     * @param frc specified render context
     * @param at specified affine transform
     * @return
     */
    public abstract LineMetrics getLineMetrics(String str, FontRenderContext frc, AffineTransform at);

    /**
     * Returns postscript name of the font.  
     */
    public abstract String getPSName();
    
	//private Graphics2D g = ((AndroidGraphicsFactory)Toolkit.getDefaultToolkit().getGraphicsFactory()).getGraphics2D();
    //private Graphics2D g = AndroidGraphics2D.getInstance();

    /**
     * Set postscript name of the font to the specified parameter.  
     */
    public void setPSName(String name){
        this.psName = name;
    }
    
    /**
     * Returns code of the missing glyph. 
     */
    public abstract int getMissingGlyphCode();

    /**
     * Returns Glyph representation of the given char.
     * @param ch specified char
     */
    public abstract Glyph getGlyph(char ch);

    /**
     * Disposes nesessary resources.
     */
    public abstract void dispose();

    /**
     * Returns Glyph represeting missing char. 
     */
    public abstract Glyph getDefaultGlyph();

    /**
     * Returns true if this FontPeerImpl can display the specified char
     */
    public abstract boolean canDisplay(char c);

    /**
     * Returns family name of the font in specified locale settings.
     * @param l specified Locale
     */
    public String getFamily(Locale l){
        return this.getFamily();
    }

    /**
     * Sets family name of the font in specified locale settings.
     */
    public void setFamily(String familyName){
        this.fontFamilyName = familyName;
    }

    /**
     * Returns face name of the font in specified locale settings.
     * @param l specified Locale
     */
    public String getFontName(Locale l){
        return this.getFontName();
    }

    /**
     * Sets font name of the font in specified locale settings.
     */
    public void setFontName(String fontName){
        this.faceName = fontName;
    }

    /**
     * Returns true, if this font peer was created from InputStream, false otherwise.
     * In case of creating fonts from InputStream some font peer implementations 
     * may need to free temporary resources.
     */
    public boolean isCreatedFromStream(){
        return this.createdFromStream;
    }

    /**
     * Sets createdFromStream flag to the specified parameter.
     * If parameter is true it means font peer was created from InputStream.
     * 
     * @param value true, if font peer was created from InputStream 
     */
    public void setCreatedFromStream(boolean value){
        this.createdFromStream = value;
    }

    /**
     * Returns font file name of this font.
     */
    public String getTempFontFileName(){
        return this.tempFontFileName;
    }

    /**
     * Sets font file name of this font to the specified one.
     * @param value String representing font file name
     */
    public void setFontFileName(String value){
        this.tempFontFileName = value;
    }

    /**
     * Returns the advance width of the specified char of this FontPeerImpl.
     * Note, if glyph is absent in the font's glyphset - returned value 
     * is the advance of the deafualt glyph. For escape-chars returned 
     * width value is 0.
     * 
     * @param ch the char which width is to be returned
     * @return the advance width of the specified char of this FontPeerImpl
     */
    public int charWidth(char ch) {
    	Paint p;
    	AndroidGraphics2D g = AndroidGraphics2D.getInstance();
    	if(g == null) {
    		throw new RuntimeException("AndroidGraphics2D not instantiated!");
    	}
   		p = ((AndroidGraphics2D)g).getAndroidPaint();
   		char[] ca = {ch};
   		float[] fa = new float[1];
   		p.getTextWidths(ca, 0, 1, fa);
   		return (int)fa[0];
    }

    /**
     * Returns the advance width of the specified char of this FontPeerImpl.
     * 
     * @param ind the char which width is to be returned
     * @return the advance width of the specified char of this FontPeerImpl
     */
    public int charWidth(int ind) {
        return charWidth((char)ind);
    }

    /**
     * Returns an array of Glyphs that represent characters from the specified 
     * Unicode range.
     * 
     * @param uFirst start position in Unicode range
     * @param uLast end position in Unicode range
     * @return
     */
    public Glyph[] getGlyphs(char uFirst, char uLast) {

        char i = uFirst;
        int len = uLast - uFirst;
        ArrayList<Glyph> lst = new ArrayList<Glyph>(len);

        if (size < 0) {
            // awt.09=min range bound value is greater than max range bound
            throw new IllegalArgumentException(Messages.getString("awt.09")); //$NON-NLS-1$
        }

        while (i < uLast) {
            lst.add(this.getGlyph(i));
        }

        return (Glyph[]) lst.toArray();
    }

    /**
     * Returns an array of Glyphs representing given array of chars.
     * 
     * @param chars specified array of chars
     */
    public Glyph[] getGlyphs(char[] chars) {
        if (chars == null){
            return null;
        }

        Glyph[] result = new Glyph[chars.length];

        for (int i = 0; i < chars.length; i++) {
            result[i] = this.getGlyph(chars[i]);
        }
        return result;
    }

    /**
     * Returns an array of Glyphs representing given string.
     * 
     * @param str specified string
     */
    public Glyph[] getGlyphs(String str) {
        char[] chars = str.toCharArray();
        return this.getGlyphs(chars);
    }

    /**
     * Returns family name of this FontPeerImpl.
     */
    public String getFamily() {
        return fontFamilyName;
    }

    /**
     * Returns face name of this FontPeerImpl.
     */
    public String getFontName() {
        if (this.fontType == FontManager.FONT_TYPE_T1){
            return this.fontFamilyName;
        }

        return faceName;
    }

    /**
     * Returns height of this font peer in pixels. 
     */
    public int getLogicalHeight() {
        return logicalHeight;
    }

    /**
     * Sets height of this font peer in pixels to the given value.
     * 
     * @param newHeight new height in pixels value
     */
    public void setLogicalHeight(int newHeight) {
        logicalHeight = newHeight;
    }

    /**
     * Returns font size. 
     */
    public int getSize() {
        return size;
    }

    /**
     * Returns font style. 
     */
    public int getStyle() {
        return style;
    }

    /**
     * Returns font name. 
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the bounds of the largest char in this FontPeerImpl in 
     * specified render context.
     * 
     * @param frc specified FontRenderContext
     */
    public Rectangle2D getMaxCharBounds(FontRenderContext frc) {
        return maxCharBounds;
    }

    /**
     * Returns the number of glyphs in this FontPeerImpl.
     */
    public int getNumGlyphs() {
        return  numGlyphs;
    }

    /**
     * Returns tangens of the italic angle of this FontPeerImpl.
     * If the FontPeerImpl has TrueType font type, italic angle value can be 
     * calculated as (CharSlopeRun / CharSlopeRise) in terms of GDI.
     */
    public float getItalicAngle() {
        return italicAngle;
    }

    /**
     * Returns height of this font peer. 
     */
    public float getHeight(){
        return height;
    }

    /**
     * Returns cached LineMetrics object of this font peer. 
     */
    public LineMetrics getLineMetrics(){
        return nlm;
    }

    /**
     * Returns native font handle of this font peer. 
     */
    public long getFontHandle(){
        return pFont;
    }

    /**
     * Returns ascent of this font peer. 
     */
    public int getAscent(){
    	Paint p;
    	AndroidGraphics2D g = AndroidGraphics2D.getInstance();
    	if(g == null) {
    		throw new RuntimeException("AndroidGraphics2D not instantiated!");
    	}
   		p = ((AndroidGraphics2D)g).getAndroidPaint();
   		return (int)p.ascent();
        //return ascent;
    }

    /**
     * Returns descent of this font peer. 
     */
    public int getDescent(){
        return descent;
    }

    /**
     * Returns leading of this font peer. 
     */
    public int getLeading(){
        return leading;
    }

    /**
     * Returns true if this font peer has uniform line metrics. 
     */
    public boolean hasUniformLineMetrics(){
        return uniformLM;
    }

    /**
     * Returns type of this font.
     *  
     * @return one of constant font type values. 
     */    
    public int getFontType(){
        return fontType;
    }

    /**
     * Sets new font type to the font object.
     * 
     * @param newType new type value
     */
    public void setFontType(int newType){
        if (newType == FontManager.FONT_TYPE_T1 || newType == FontManager.FONT_TYPE_TT){
            fontType = newType;
        }
    }

    /**
     * Sets new font type to the font object.
     * 
     * @param newType new type value
     */
    @Override
    protected void finalize() throws Throwable {
      super.finalize();
      
      dispose();
    }

}
