/*
 * Copyright (C) 2007 The Android Open Source Project
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

package org.apache.harmony.awt.gl.font;

import com.android.internal.awt.AndroidGraphics2D;

import java.awt.Font;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphJustificationInfo;
import java.awt.font.GlyphMetrics;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

import android.util.Log;
import android.graphics.Path;

public class AndroidGlyphVector extends GlyphVector {

    // array of chars defined in constructor
    public char[] charVector;

    // array of Glyph objects, that describe information about glyphs
    public Glyph[] vector;

    // array of default positions of glyphs in GlyphVector
    // without applying GlyphVector's transform
    float[] defaultPositions;

    // array of logical positions of glyphs in GlyphVector

    float[] logicalPositions;

    // array of visual (real) positions of glyphs in GlyphVector
    public float[] visualPositions;

    // FontRenderContext for this vector.
    protected FontRenderContext vectorFRC;

    // layout flags mask
    protected int layoutFlags = 0;

    // array of cached glyph outlines 
    protected Shape[] gvShapes;

    FontPeerImpl peer;

    // font corresponding to the GlyphVector 
    Font font;

    // ascent of the font
    float ascent;

    // height of the font
    float height;
    
    // leading of the font
    float leading;
    
    // descent of the font
    float descent;

    // transform of the GlyphVector
    AffineTransform transform;

    @SuppressWarnings("deprecation")
    public AndroidGlyphVector(char[] chars, FontRenderContext frc, Font fnt,
            int flags) {
        int len = chars.length;
        this.font = fnt;
        LineMetricsImpl lmImpl = (LineMetricsImpl)fnt.getLineMetrics(String.valueOf(chars), frc);     	
        this.ascent = lmImpl.getAscent();
        this.height = lmImpl.getHeight();
        this.leading = lmImpl.getLeading();
        this.descent = lmImpl.getDescent();
        this.charVector = chars;
        this.vectorFRC = frc;
    }

    public AndroidGlyphVector(char[] chars, FontRenderContext frc, Font fnt) {
        this(chars, frc, fnt, 0);
    }

    public AndroidGlyphVector(String str, FontRenderContext frc, Font fnt) {
        this(str.toCharArray(), frc, fnt, 0);
    }

    public AndroidGlyphVector(String str, FontRenderContext frc, Font fnt, int flags) {
        this(str.toCharArray(), frc, fnt, flags);
    }

	@Override
	public boolean equals(GlyphVector glyphVector) {
		return false;
	}

	public char[] getGlyphs() {
		return this.charVector;
	}
	
	@Override
	public Font getFont() {
		return this.font;
	}

	@Override
	public FontRenderContext getFontRenderContext() {
		return this.vectorFRC;
	}

	@Override
	public int getGlyphCode(int glyphIndex) {
		return charVector[glyphIndex];
	}

	@Override
	public int[] getGlyphCodes(int beginGlyphIndex, int numEntries,
			int[] codeReturn) {
		throw new RuntimeException("Not implemented!");
	}

	@Override
	public GlyphJustificationInfo getGlyphJustificationInfo(int glyphIndex) {
		throw new RuntimeException("Not implemented!");
	}

	@Override
	public Shape getGlyphLogicalBounds(int glyphIndex) {
		throw new RuntimeException("Not implemented!");
	}

	@Override
	public GlyphMetrics getGlyphMetrics(int glyphIndex) {
		throw new RuntimeException("Not implemented!");
	}

	public Path getAndroidGlyphOutline(int glyphIndex) {
		AndroidGraphics2D g = AndroidGraphics2D.getInstance();
        Path path = new Path();
        char tmp[] = new char[1];
        tmp[0] = charVector[glyphIndex];
        ((AndroidGraphics2D)g).getAndroidPaint().getTextPath(new String(tmp), 0, 1, 0, 0, path);
        return path;
	}
	
	@Override
	public Shape getGlyphOutline(int glyphIndex) {
		throw new RuntimeException("Not implemented!");
	}

	@Override
	public Point2D getGlyphPosition(int glyphIndex) {
		throw new RuntimeException("Not implemented!");
	}

	@Override
	public float[] getGlyphPositions(int beginGlyphIndex, int numEntries,
			float[] positionReturn) {
		throw new RuntimeException("Not implemented!");
	}

	@Override
	public AffineTransform getGlyphTransform(int glyphIndex) {
		throw new RuntimeException("Not implemented!");
	}

	@Override
	public Shape getGlyphVisualBounds(int glyphIndex) {
		throw new RuntimeException("Not implemented!");
	}

	@Override
	public Rectangle2D getLogicalBounds() {
		throw new RuntimeException("Not implemented!");
	}

	@Override
	public int getNumGlyphs() {
		return charVector.length;
	}

	@Override
	public Shape getOutline(float x, float y) {
		throw new RuntimeException("Not implemented!");
	}

	@Override
	public Shape getOutline() {
		throw new RuntimeException("Not implemented!");
	}

	public Path getAndroidOutline() {
		AndroidGraphics2D g = AndroidGraphics2D.getInstance();
        Path path = new Path();
        ((AndroidGraphics2D)g).getAndroidPaint().getTextPath(new String(charVector), 0, charVector.length, 0, 0, path);
        return path;
	}

	@Override
	public Rectangle2D getVisualBounds() {
		throw new RuntimeException("Not implemented!");
	}

	@Override
	public void performDefaultLayout() {
		throw new RuntimeException("Not implemented!");
	}

	@Override
	public void setGlyphPosition(int glyphIndex, Point2D newPos) {
		throw new RuntimeException("Not implemented!");
	}

	@Override
	public void setGlyphTransform(int glyphIndex, AffineTransform trans) {
		throw new RuntimeException("Not implemented!");
	}

}
