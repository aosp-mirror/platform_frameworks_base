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

import java.awt.Font;
import java.awt.FontMetrics;
//import java.awt.Paint;
import java.awt.geom.AffineTransform;

import android.graphics.Paint;

/**
 * FontMetrics implementation
 */

public class FontMetricsImpl extends FontMetrics {

	private static final long serialVersionUID = 844695615201925138L;

	// ascent of the font
	private int ascent;

	// descent of the font
	private int descent;

	// leading of the font
	private int leading;

	// maximum ascent of the font
	private int maxAscent;

	// maximum descent of the font
	private int maxDescent;

	// maximum advance of the font
	private int maxAdvance;

	// array of char advance widths
	private int[] widths = new int[256];

	// font peer corresponding to this FontPeerImpl
	private transient FontPeerImpl peer;

	// X scale parameter of the font transform
	private float scaleX = 1;

	public AndroidGraphics2D mSg;

	private Font mFn;

	// Y scale parameter of the font transform
	private float scaleY = 1;

	/**
	 * Creates new FontMericsImpl object described by the specified Font.
	 * 
	 * @param fnt
	 *            the specified Font object
	 */
	public FontMetricsImpl(Font fnt) {
		super(fnt);
		this.mFn = fnt;
		
		mSg = AndroidGraphics2D.getInstance();
		Paint p = mSg.getAndroidPaint();
		
		this.ascent = (int)-p.ascent();
		this.descent = (int)p.descent();
		this.leading = p.getFontMetricsInt().leading;
		
		AffineTransform at = fnt.getTransform();
		if (!at.isIdentity()) {
			scaleX = (float) at.getScaleX();
			scaleY = (float) at.getScaleY();
		}
				
	    /*
	     * metrics[5] - strikethrough thickness<p>
	     * -metrics[6] - strikethrough offset<p>
	     * metrics[7] - maximum char width<p>
	     * metrics[8] - ascent in pixels<p>
	     * metrics[9] - descent in pixles<p>
	     * metrics[10] - external leading in pixels<p>
	     * metrics[11] - underline thickness in pixels<p>
	     * -metrics[12] - underline offset in pixels<p>
	     * metrics[13] - strikethrough thickness in pixels<p>
	     * -metrics[14] - strikethrough offset in pixels<p>
	     * metrics[15] - maximum char width in pixels<p>

	     * @param _baselineData an array of 3 elements with baseline offsets metrics<p>
	     * _baselineData[0] - roman baseline offset<p> 
	     * _baselineData[1] - center baseline offset<p>
	     * _baselineData[2] - hanging baseline offset<p>
	     */
	}


	/**
	 * Initialize the array of the first 256 chars' advance widths of the Font
	 * describing this FontMetricsImpl object.
	 */
	private void initWidths() {

		this.widths = new int[256];
		for (int chr = 0; chr < 256; chr++) {
			widths[chr] = (int) (getFontPeer().charWidth((char) chr) * scaleX);
		}

	}

	/**
	 * Returns the ascent of the Font describing this FontMetricsImpl object.
	 */
	@Override
	public int getAscent() {
		return this.ascent;
	}

	/**
	 * Returns the descent of the Font describing this FontMetricsImpl object.
	 */
	@Override
	public int getDescent() {
		return this.descent;
	}

	/**
	 * Returns the leading of the Font describing this FontMetricsImpl object.
	 */
	@Override
	public int getLeading() {
		return this.leading;
	}

	/**
	 * Returns the advance width of the specified char of the Font describing
	 * this FontMetricsImpl object.
	 * 
	 * @param ch
	 *            the char which width is to be returned
	 * @return the advance width of the specified char of the Font describing
	 *         this FontMetricsImpl object
	 */
	@Override
	public int charWidth(int ch) {
		if (ch < 256) {
			return widths[ch];
		}

		return getFontPeer().charWidth((char) ch);
	}

	/**
	 * Returns the advance width of the specified char of the Font describing
	 * this FontMetricsImpl object.
	 * 
	 * @param ch
	 *            the char which width is to be returned
	 * @return the advance width of the specified char of the Font describing
	 *         this FontMetricsImpl object
	 */
	@Override
	public int charWidth(char ch) {
		if (ch < 256) {
			return widths[ch];
		}
		return (int) (getFontPeer().charWidth(ch) * scaleX);
	}

	/**
	 * Returns the maximum advance of the Font describing this FontMetricsImpl
	 * object.
	 */
	@Override
	public int getMaxAdvance() {
		return this.maxAdvance;
	}

	/**
	 * Returns the maximum ascent of the Font describing this FontMetricsImpl
	 * object.
	 */
	@Override
	public int getMaxAscent() {
		return this.maxAscent;
	}

	/**
	 * Returns the maximum descent of the Font describing this FontMetricsImpl
	 * object.
	 */
	@SuppressWarnings("deprecation")
	@Deprecated
	@Override
	public int getMaxDecent() {
		return this.maxDescent;
	}

	/**
	 * Returns the maximum descent of the Font describing this FontMetricsImpl
	 * object.
	 */
	@Override
	public int getMaxDescent() {
		return this.maxDescent;
	}

	/**
	 * Returns the advance widths of the first 256 characters in the Font
	 * describing this FontMetricsImpl object.
	 */
	@Override
	public int[] getWidths() {
		return this.widths;
	}

	/**
	 * Returns the total advance width of the specified string in the metrics of
	 * the Font describing this FontMetricsImpl object.
	 * 
	 * @param str
	 *            the String which width is to be measured
	 * @return the total advance width of the specified string in the metrics of
	 *         the Font describing this FontMetricsImpl object
	 */
	@Override
	public int stringWidth(String str) {

		int width = 0;
		char chr;

		for (int i = 0; i < str.length(); i++) {
			chr = str.charAt(i);
			width += charWidth(chr);
		}
		return width;

		/*
		 * float res = 0; int ln = str.length(); char[] c = new char[ln]; float[] f =
		 * new float[ln]; str.getChars(0, ln, c, 0); mSg.getPaint().getTextWidths(c, 0,
		 * ln, f);
		 * 
		 * for(int i = 0; i < f.length; i++) { res += f[i]; } return (int)res;
		 */
	}

	/**
	 * Returns FontPeer implementation of the Font describing this
	 * FontMetricsImpl object.
	 * 
	 * @return a FontPeer object, that is the platform dependent FontPeer
	 *         implementation for the Font describing this FontMetricsImpl
	 *         object.
	 */
	@SuppressWarnings("deprecation")
	public FontPeerImpl getFontPeer() {
		if (peer == null) {
			peer = (FontPeerImpl) font.getPeer();
		}
		return peer;
	}
}
