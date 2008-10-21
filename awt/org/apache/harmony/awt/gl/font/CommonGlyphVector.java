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

import java.awt.Font;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphJustificationInfo;
import java.awt.font.GlyphMetrics;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

import org.apache.harmony.awt.internal.nls.Messages;

/**
 * GlyphVector implementation
 */
public class CommonGlyphVector extends GlyphVector {

    // array of transforms of glyphs in GlyphVector
    protected AffineTransform[] glsTransforms;

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

    /**
     * Creates new CommonGlyphVector object from the specified parameters.
     * 
     * @param chars an array of chars
     * @param frc FontRenderContext object
     * @param fnt Font object
     * @param flags layout flags
     */
    @SuppressWarnings("deprecation")
    public CommonGlyphVector(char[] chars, FontRenderContext frc, Font fnt,
            int flags) {
        int len = chars.length;

        this.font = fnt;
        this.transform = fnt.getTransform();
        this.peer = (FontPeerImpl) fnt.getPeer();

        gvShapes = new Shape[len];

        // !! As pointed in API documentation for the 
        // getGlyphPosisitions(int index,int numEntries, float[] positionReturn) 
        // and getGlyphPosition(int index) methods, if the index is equals to 
        // the number of glyphs the position after the last glyph must be 
        // returned, thus there are n+1 positions and last (n+1) position 
        // points to the end of GlyphVector.

        logicalPositions = new float[(len+1)<<1];
        visualPositions = new float[(len+1)<<1];
        defaultPositions = new float[(len+1)<<1];

        glsTransforms = new AffineTransform[len];

        this.charVector = chars;
        this.vectorFRC = frc;
        //LineMetricsImpl lmImpl = (LineMetricsImpl)peer.getLineMetrics();

        LineMetricsImpl lmImpl = (LineMetricsImpl)fnt.getLineMetrics(String.valueOf(chars), frc);

        this.ascent = lmImpl.getAscent();
        this.height = lmImpl.getHeight();
        this.leading = lmImpl.getLeading();
        this.descent = lmImpl.getDescent();
        this.layoutFlags = flags;

        if ((flags & Font.LAYOUT_RIGHT_TO_LEFT) != 0){
            char vector[] = new char[len];
            for(int i=0; i < len; i++){
                vector[i] = chars[len-i-1];
            }
            this.vector = peer.getGlyphs(vector);

        } else {
            this.vector = peer.getGlyphs(chars);
        }

        this.glsTransforms = new AffineTransform[len];

        setDefaultPositions();
        performDefaultLayout();
    }

    /**
     * Creates new CommonGlyphVector object from the specified parameters. 
     * Layout flags set to default.
     * 
     * @param chars an array of chars
     * @param frc FontRenderContext object
     * @param fnt Font object
     */
    public CommonGlyphVector(char[] chars, FontRenderContext frc, Font fnt) {
        this(chars, frc, fnt, 0);
    }

    /**
     * Creates new CommonGlyphVector object from the specified parameters. 
     * Layout flags set to default.
     * 
     * @param str specified string
     * @param frc FontRenderContext object
     * @param fnt Font object
     */
    public CommonGlyphVector(String str, FontRenderContext frc, Font fnt) {
        this(str.toCharArray(), frc, fnt, 0);
    }

    /**
     * Creates new CommonGlyphVector object from the specified parameters.
     * 
     * @param str specified string
     * @param frc FontRenderContext object
     * @param fnt Font object
     * @param flags layout flags
     */
    public CommonGlyphVector(String str, FontRenderContext frc, Font fnt, int flags) {
        this(str.toCharArray(), frc, fnt, flags);
    }

    /**
     * Set array of logical positions of the glyphs to
     * default with their default advances and height.
     */
    void setDefaultPositions(){
        int len = getNumGlyphs();

        // First [x,y] is set into [0,0] position
        // for this reason start index is 1
        for (int i=1; i <= len; i++ ){
                int idx = i << 1;
                float advanceX = vector[i-1].getGlyphPointMetrics().getAdvanceX();
                float advanceY = vector[i-1].getGlyphPointMetrics().getAdvanceY();

                defaultPositions[idx] = defaultPositions[idx-2] + advanceX;
                defaultPositions[idx+1] = defaultPositions[idx-1] + advanceY;

        }
        transform.transform(defaultPositions, 0, logicalPositions, 0, getNumGlyphs()+1);

    }

    /**
     * Returnes the pixel bounds of this GlyphVector rendered at the 
     * specified x,y location with the given FontRenderContext.
     *  
     * @param frc a FontRenderContext that is used
     * @param x specified x coordinate value
     * @param y specified y coordinate value
     * @return a Rectangle that bounds pixels of this GlyphVector
     */
    @Override
    public Rectangle getPixelBounds(FontRenderContext frc, float x, float y) {

        double xM, yM, xm, ym;

        double minX = 0;
        double minY = 0;
        double maxX = 0;
        double maxY = 0;

        for (int i = 0; i < this.getNumGlyphs(); i++) {
            Rectangle glyphBounds = this.getGlyphPixelBounds(i, frc, 0, 0);
            xm = glyphBounds.getMinX();
            ym = glyphBounds.getMinY();
            xM = glyphBounds.getMaxX();
            yM = glyphBounds.getMaxY();

            if (i == 0) {
                minX = xm;
                minY = ym;
                maxX = xM;
                maxY = yM;
            }

            if (minX > xm) {
                minX = xm;
            }
            if (minY > ym) {
                minY = ym;
            }
            if (maxX < xM) {
                maxX = xM;
            }
            if (maxY < yM) {
                maxY = yM;
            }
        }
        return new Rectangle((int)(minX + x), (int)(minY + y), (int)(maxX - minX), (int)(maxY - minY));

    }

    /**
     * Returns the visual bounds of this GlyphVector.
     * The visual bounds is the bounds of the total outline of 
     * this GlyphVector.
     * @return a Rectangle2D that id the visual bounds of this GlyphVector
     */
    @Override
    public Rectangle2D getVisualBounds() {
        float xM, yM, xm, ym;
        float minX = 0;
        float minY = 0;
        float maxX = 0;
        float maxY = 0;
        boolean firstIteration = true;

        for (int i = 0; i < this.getNumGlyphs(); i++) {
            Rectangle2D bounds = this.getGlyphVisualBounds(i).getBounds2D();
            if (bounds.getWidth() == 0){
                continue;
            }
            xm = (float)bounds.getX();
            ym = (float)bounds.getY();

            xM = (float)(xm + bounds.getWidth());

            yM = ym + (float) bounds.getHeight();

            if (firstIteration) {
                minX = xm;
                minY = ym;
                maxX = xM;
                maxY = yM;
                firstIteration = false;
            } else {
                if (minX > xm) {
                    minX = xm;
                }
                if (minY > ym) {
                    minY = ym;
                }
                if (maxX < xM) {
                    maxX = xM;
                }
                if (maxY < yM) {
                    maxY = yM;
                }

            }
        }

        return (this.getNumGlyphs() != 0) ? new Rectangle2D.Float(minX, minY,
                (maxX - minX), (maxY - minY)) : null;
    }

    /**
     * Sets new position to the specified glyph.
     */
    @Override
    public void setGlyphPosition(int glyphIndex, Point2D newPos) {
        if ((glyphIndex > vector.length) || (glyphIndex < 0)) {
            // awt.43=glyphIndex is out of vector's limits
            throw new IndexOutOfBoundsException(Messages.getString("awt.43")); //$NON-NLS-1$
        }
        float x = (float)newPos.getX();
        float y = (float)newPos.getY();
        int index = glyphIndex << 1;

        if ((x != visualPositions[index]) || (y != visualPositions[index + 1])){
            visualPositions[index] = x;
            visualPositions[index+1] = y;
            layoutFlags = layoutFlags | FLAG_HAS_POSITION_ADJUSTMENTS;
        }

    }

    /**
     * Returns the position of the specified glyph relative to the origin of
     * this GlyphVector
     * @return a Point2D that the origin of the glyph with specified index
     */
    @Override
    public Point2D getGlyphPosition(int glyphIndex) {
        if ((glyphIndex > vector.length) || (glyphIndex < 0)) {
            // awt.43=glyphIndex is out of vector's limits
            throw new IndexOutOfBoundsException(Messages.getString("awt.43")); //$NON-NLS-1$
        }
        int index = glyphIndex << 1;
        Point2D pos = new Point2D.Float(visualPositions[index], visualPositions[index+1]);

        // For last position we don't have to transform !!
        if(glyphIndex==vector.length){
            return pos;
        }

        AffineTransform at = getGlyphTransform(glyphIndex);
        if ((at == null) || (at.isIdentity())){
            return pos;
        }

        pos.setLocation(pos.getX() + at.getTranslateX(), pos.getY() + at.getTranslateY());

        return pos;
    }

    /**
     * Sets new transform to the specified glyph.
     * 
     * @param glyphIndex specified index of the glyph
     * @param trans AffineTransform of the glyph with specified index
     */
    @Override
    public void setGlyphTransform(int glyphIndex, AffineTransform trans) {
        if ((glyphIndex >= vector.length) || (glyphIndex < 0)) {
            // awt.43=glyphIndex is out of vector's limits
            throw new IndexOutOfBoundsException(Messages.getString("awt.43")); //$NON-NLS-1$
        }

        if ((trans == null) || (trans.isIdentity())) {
            glsTransforms[glyphIndex] = null;
        } else {
            glsTransforms[glyphIndex] = new AffineTransform(trans);
            layoutFlags = layoutFlags | FLAG_HAS_TRANSFORMS;
        }
    }

    /**
     * Returns the affine transform of the specified glyph.
     * 
     * @param glyphIndex specified index of the glyph
     * @return an AffineTransform of the glyph with specified index
     */
    @Override
    public AffineTransform getGlyphTransform(int glyphIndex) {
        if ((glyphIndex >= this.vector.length) || (glyphIndex < 0)) {
            // awt.43=glyphIndex is out of vector's limits
            throw new IndexOutOfBoundsException(Messages.getString("awt.43")); //$NON-NLS-1$
        }
        return this.glsTransforms[glyphIndex];
    }

    /**
     * Returns the metrics of the specified glyph.
     * 
     * @param glyphIndex specified index of the glyph
     */
    @Override
    public GlyphMetrics getGlyphMetrics(int glyphIndex) {

        if ((glyphIndex < 0) || ((glyphIndex) >= this.getNumGlyphs())) {
            // awt.43=glyphIndex is out of vector's limits
            throw new IndexOutOfBoundsException(Messages.getString("awt.43")); //$NON-NLS-1$
        }
        // TODO: is there a sence in GlyphMetrics
        // if certain glyph or Font has a transform??
        return this.vector[glyphIndex].getGlyphMetrics();
    }

    /**
     * Returns a justification information for the glyph with specified glyph 
     * index.
     * @param glyphIndex index of a glyph which GlyphJustificationInfo is to be 
     * received   
     * @return a GlyphJustificationInfo object that contains glyph justification 
     * properties of the specified glyph
     */
    @Override
    public GlyphJustificationInfo getGlyphJustificationInfo(int glyphIndex) {
        // TODO : Find out the source of Justification info
        if (true) {
            throw new RuntimeException("Method is not implemented"); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Returns the FontRenderContext parameter of this GlyphVector.
     */
    @Override
    public FontRenderContext getFontRenderContext() {
        return this.vectorFRC;
    }

    /**
     * Returns the visual bounds of the specified glyph.
     * 
     * @param glyphIndex specified index of the glyph
     */
    @Override
    public Shape getGlyphVisualBounds(int glyphIndex) {
        if ((glyphIndex < 0) || (glyphIndex >= this.getNumGlyphs())) {
            // awt.43=glyphIndex is out of vector's limits
            throw new IndexOutOfBoundsException(Messages.getString("awt.43")); //$NON-NLS-1$
        }

        int idx  = glyphIndex << 1;

        AffineTransform fontTransform = this.transform;
        double xOffs = fontTransform.getTranslateX();
        double yOffs = fontTransform.getTranslateY();

        if (vector[glyphIndex].getWidth() == 0){
            return new Rectangle2D.Float((float)xOffs, (float)yOffs, 0, 0);
        }

        AffineTransform at = AffineTransform.getTranslateInstance(xOffs, yOffs);
        AffineTransform glyphTransform = getGlyphTransform(glyphIndex);

        if (transform.isIdentity() && ((glyphTransform == null) || glyphTransform.isIdentity())){
            Rectangle2D blackBox = vector[glyphIndex].getGlyphMetrics().getBounds2D();
            at.translate(visualPositions[idx], visualPositions[idx+1]);
            return(at.createTransformedShape(blackBox));
        }

        GeneralPath shape = (GeneralPath)this.getGlyphOutline(glyphIndex);
        shape.transform(at);
        return shape.getBounds2D();
    }

    /**
     * Returnes the pixel bounds of the specified glyph within GlyphVector 
     * rendered at the specified x,y location.
     *  
     * @param glyphIndex index of the glyph
     * @param frc a FontRenderContext that is used
     * @param x specified x coordinate value
     * @param y specified y coordinate value
     * @return a Rectangle that bounds pixels of the specified glyph
     */
    @Override
    public Rectangle getGlyphPixelBounds(int glyphIndex, FontRenderContext frc,
            float x, float y) {
        // TODO : need to be implemented with FontRenderContext
        if ((glyphIndex < 0) || (glyphIndex >= this.getNumGlyphs())) {
            // awt.43=glyphIndex is out of vector's limits
            throw new IndexOutOfBoundsException(Messages.getString("awt.43")); //$NON-NLS-1$
        }

        int idx  = glyphIndex << 1;

        if (vector[glyphIndex].getWidth() == 0){
            AffineTransform fontTransform = this.transform;
            double xOffs = x + visualPositions[idx] + fontTransform.getTranslateX();
            double yOffs = y + visualPositions[idx+1] + fontTransform.getTranslateY();
            return new Rectangle((int)xOffs, (int)yOffs, 0, 0);
        }

        GeneralPath shape = (GeneralPath)this.getGlyphOutline(glyphIndex);

        AffineTransform at = AffineTransform.getTranslateInstance(x, y);

        if (frc != null){
            at.concatenate(frc.getTransform());
        }

        shape.transform(at);

        Rectangle bounds = shape.getBounds();
        return new Rectangle((int)bounds.getX(), (int)bounds.getY(),
                            (int)bounds.getWidth()-1, (int)bounds.getHeight()-1);
        }

    /**
     * Returns a Shape that encloses specified glyph.
     * 
     * @param glyphIndex specified index of the glyph
     */
    @Override
    public Shape getGlyphOutline(int glyphIndex) {
        if ((glyphIndex < 0) || (glyphIndex >= this.getNumGlyphs())) {
            // awt.43=glyphIndex is out of vector's limits
            throw new IndexOutOfBoundsException(Messages.getString("awt.43")); //$NON-NLS-1$
        }

        if (gvShapes[glyphIndex] == null) {
            gvShapes[glyphIndex] = vector[glyphIndex].getShape();
        }

        GeneralPath gp = (GeneralPath)((GeneralPath)gvShapes[glyphIndex]).clone();

        /* Applying GlyphVector font transform */
        AffineTransform at = (AffineTransform)this.transform.clone();

        /* Applying Glyph transform */
        AffineTransform glyphAT = getGlyphTransform(glyphIndex);
        if (glyphAT != null){
            at.preConcatenate(glyphAT);
        }

        int idx  = glyphIndex << 1;

        gp.transform(at);
        gp.transform(AffineTransform.getTranslateInstance(visualPositions[idx], visualPositions[idx+1]));
        return gp;
    }


    /**
     * Returns a Shape that is the outline representation of this GlyphVector 
     * rendered at the specified x,y coordinates.
     * 
     * @param x specified x coordinate value
     * @param y specified y coordinate value
     * @return a Shape object that is the outline of this GlyphVector
     * at the specified coordinates.
     */
    @Override
    public Shape getOutline(float x, float y) {
        GeneralPath gp = new GeneralPath(GeneralPath.WIND_EVEN_ODD);
        for (int i = 0; i < this.vector.length; i++) {
            GeneralPath outline = (GeneralPath)getGlyphOutline(i);

            /* Applying translation to actual visual bounds */
            outline.transform(AffineTransform.getTranslateInstance(x, y));
            gp.append(outline, false);
        }

        return gp;
    }

    /**
     * Returns a Shape that is the outline representation of this GlyphVector.
     * 
     * @return a Shape object that is the outline of this GlyphVector
     */
    @Override
    public Shape getOutline() {
        return this.getOutline(0, 0);
    }

    /**
     * Returns an array of glyphcodes for the specified glyphs.
     * 
     * @param beginGlyphIndex the start index
     * @param numEntries the number of glyph codes to get
     * @param codeReturn the array that receives glyph codes' values
     * @return an array that receives glyph codes' values
     */
    @Override
    public int[] getGlyphCodes(int beginGlyphIndex, int numEntries,
            int[] codeReturn) {

        if ((beginGlyphIndex < 0) || ((numEntries + beginGlyphIndex) > this.getNumGlyphs())) {
            // awt.44=beginGlyphIndex is out of vector's range
            throw new IndexOutOfBoundsException(Messages.getString("awt.44")); //$NON-NLS-1$
        }

        if (numEntries < 0) {
            // awt.45=numEntries is out of vector's range
            throw new IllegalArgumentException(Messages.getString("awt.45")); //$NON-NLS-1$
        }

        if (codeReturn == null) {
            codeReturn = new int[numEntries];
        }

        for (int i = beginGlyphIndex; i < beginGlyphIndex + numEntries; i++) {
            codeReturn[i-beginGlyphIndex] = this.vector[i].getGlyphCode();
        }

        return codeReturn;
    }

    /**
     * Returns an array of numEntries character indices for the specified glyphs.
     * 
     * @param beginGlyphIndex the start index
     * @param numEntries the number of glyph codes to get
     * @param codeReturn the array that receives glyph codes' values
     * @return an array that receives glyph char indices
     */
    @Override
    public int[] getGlyphCharIndices(int beginGlyphIndex, int numEntries,
            int[] codeReturn) {
        if ((beginGlyphIndex < 0) || (beginGlyphIndex >= this.getNumGlyphs())) {
            // awt.44=beginGlyphIndex is out of vector's range
            throw new IllegalArgumentException(Messages.getString("awt.44")); //$NON-NLS-1$
        }

        if ((numEntries < 0)
                || ((numEntries + beginGlyphIndex) > this.getNumGlyphs())) {
            // awt.45=numEntries is out of vector's range
            throw new IllegalArgumentException(Messages.getString("awt.45")); //$NON-NLS-1$
        }

        if (codeReturn == null) {
            codeReturn = new int[numEntries];
        }

        for (int i = 0; i < numEntries; i++) {
            codeReturn[i] = this.getGlyphCharIndex(i + beginGlyphIndex);
        }
        return codeReturn;
    }

    /**
     * Returns an array of numEntries glyphs positions from beginGlyphIndex
     * glyph in Glyph Vector.
     * 
     * @param beginGlyphIndex the start index
     * @param numEntries the number of glyph codes to get
     * @param positionReturn the array that receives glyphs' positions
     * @return an array of floats that receives glyph char indices
     */
    @Override
    public float[] getGlyphPositions(int beginGlyphIndex, int numEntries,
            float[] positionReturn) {

        int len = (this.getNumGlyphs()+1) << 1;
        beginGlyphIndex *= 2;
        numEntries *= 2;

        if ((beginGlyphIndex < 0) || ((numEntries + beginGlyphIndex) > len)) {
            // awt.44=beginGlyphIndex is out of vector's range
            throw new IndexOutOfBoundsException(Messages.getString("awt.44")); //$NON-NLS-1$
        }

        if (numEntries < 0) {
            // awt.45=numEntries is out of vector's range
            throw new IllegalArgumentException(Messages.getString("awt.45")); //$NON-NLS-1$
        }

        if (positionReturn == null) {
            positionReturn = new float[numEntries];
        }

        System.arraycopy(visualPositions, beginGlyphIndex, positionReturn, 0, numEntries);

        return positionReturn;
    }

    /**
     * Set numEntries elements of the visualPositions array from beginGlyphIndex
     * of numEntries glyphs positions from beginGlyphIndex glyph in Glyph Vector.
     * 
     * @param beginGlyphIndex the start index
     * @param numEntries the number of glyph codes to get
     * @param setPositions the array of positions to set
     */
    public void setGlyphPositions(int beginGlyphIndex, int numEntries,
            float[] setPositions) {

        int len = (this.getNumGlyphs()+1) << 1;
        beginGlyphIndex *= 2;
        numEntries *= 2;

        if ((beginGlyphIndex < 0) || ((numEntries + beginGlyphIndex) > len)) {
            // awt.44=beginGlyphIndex is out of vector's range
            throw new IndexOutOfBoundsException(Messages.getString("awt.44")); //$NON-NLS-1$
        }

        if (numEntries < 0) {
            // awt.45=numEntries is out of vector's range
            throw new IllegalArgumentException(Messages.getString("awt.45")); //$NON-NLS-1$
        }

        System.arraycopy(setPositions, 0, visualPositions, beginGlyphIndex, numEntries);
        layoutFlags = layoutFlags & FLAG_HAS_POSITION_ADJUSTMENTS;

    }

    /**
     * Set elements of the visualPositions array.
     * 
     * @param setPositions the array of positions to set
     */
    public void setGlyphPositions(float[] setPositions) {

        int len = (this.getNumGlyphs()+1) << 1;
        if (len != setPositions.length){
            // awt.46=length of setPositions array differs from the length of positions array
            throw new IllegalArgumentException(Messages.getString("awt.46")); //$NON-NLS-1$
        }

        System.arraycopy(setPositions, 0, visualPositions, 0, len);
        layoutFlags = layoutFlags & FLAG_HAS_POSITION_ADJUSTMENTS;

    }


    /**
     * Returns glyph code of the specified glyph.
     * 
     * @param glyphIndex specified index of the glyph
     */
    @Override
    public int getGlyphCode(int glyphIndex) {
        if (glyphIndex >= this.vector.length || glyphIndex < 0) {
            // awt.43=glyphIndex is out of vector's limits
            throw new IndexOutOfBoundsException(Messages.getString("awt.43")); //$NON-NLS-1$
        }
        return this.vector[glyphIndex].getGlyphCode();
    }

    /**
     * Returns character index of the specified glyph.
     * 
     * @param glyphIndex specified index of the glyph
     */
    @Override
    public int getGlyphCharIndex(int glyphIndex) {

        if ((glyphIndex < 0) || (glyphIndex >= this.getNumGlyphs())) {
            // awt.43=glyphIndex is out of vector's limits
            throw new IllegalArgumentException(Messages.getString("awt.43")); //$NON-NLS-1$
        }

        if ((this.layoutFlags & Font.LAYOUT_RIGHT_TO_LEFT) != 0) {
            return this.charVector.length - glyphIndex - 1;
        }

        return glyphIndex;
    }

    /**
     * Returns a character value of the specified glyph.
     * 
     * @param glyphIndex specified index of the glyph
     */
    public char getGlyphChar(int glyphIndex) {

        if ((glyphIndex < 0) || (glyphIndex >= this.getNumGlyphs())) {
            // awt.43=glyphIndex is out of vector's limits
            throw new IllegalArgumentException(Messages.getString("awt.43")); //$NON-NLS-1$
        }
        return this.charVector[glyphIndex];
    }

    /**
     * Assigns default positions to each glyph in this GlyphVector.
     */
    @Override
    public void performDefaultLayout() {

        System.arraycopy(logicalPositions, 0, visualPositions, 0, logicalPositions.length);

        // Set position changes flag to zero
        clearLayoutFlags(GlyphVector.FLAG_HAS_POSITION_ADJUSTMENTS);
    }

    /**
     * Returns the number of glyphs in this Glyph Vector
     */
    @Override
    public int getNumGlyphs() {
        return vector.length;
    }

    /**
     * Returns the logical bounds of this GlyphVector
     */
    @Override
    public Rectangle2D getLogicalBounds(){
        // XXX: for transforms where an angle between basis vectors is not 90 degrees
        // Rectanlge2D class doesn't fit as Logical bounds. For this reason we use
        // only non-transformed bounds!!

        float x = visualPositions[0];
        float width = visualPositions[visualPositions.length-2];

        double scaleY =  transform.getScaleY();

        Rectangle2D bounds = new Rectangle2D.Float(x, (float)((-this.ascent-this.leading)*scaleY), width, (float)(this.height*scaleY));
        return bounds;
    }


    /**
     * Checks whether given GlyphVector equals to this GlyphVector.
     * @param glyphVector GlyphVector object to compare
     */
    @Override
    public boolean equals(GlyphVector glyphVector){
        if (glyphVector == this){
            return true;
        }

        if (glyphVector != null) {

            if (!(glyphVector.getFontRenderContext().equals(this.vectorFRC) &&
                      glyphVector.getFont().equals(this.font))){
                return false;
            }

            try {
                boolean eq = true;
                for (int i = 0; i < getNumGlyphs(); i++) {

                    int idx = i*2;
                    eq = (((CommonGlyphVector)glyphVector).visualPositions[idx] == this.visualPositions[idx]) &&
                        (((CommonGlyphVector)glyphVector).visualPositions[idx+1] == this.visualPositions[idx+1]) &&
                        (glyphVector.getGlyphCharIndex(i) == this.getGlyphCharIndex(i));

                    if (eq){
                        AffineTransform trans = glyphVector.getGlyphTransform(i);
                        if (trans == null){
                            eq = (this.glsTransforms[i] == null);
                        }else{
                            eq = this.glsTransforms[i].equals(trans);
                        }
                    }

                    if (!eq){
                        return false;
                    }
                }

                return  eq;
            } catch (ClassCastException e) {
            }
        }

        return false;
    }


    /**
     * Returns flags describing the state of the GlyphVector.
     */
    @Override
    public int getLayoutFlags() {
        return layoutFlags;
    }

    /**
     * Returns char with the specified index.
     * 
     * @param index specified index of the char
     * 
     */
    public char getChar(int index) {
        return this.charVector[index];

    }

    /**
     * Clear desired flags in layout flags describing the state. 
     * 
     * @param clearFlags flags mask to clear 
     */
    
    private void clearLayoutFlags(int clearFlags){
        layoutFlags &= ~clearFlags;
    }

    /**
     * Returns the logical bounds of the specified glyph within this CommonGlyphVector.
     * 
     * @param glyphIndex index of the glyph to get it's logical bounds
     * @return logical bounds of the specified glyph
     */
    @Override
    public Shape getGlyphLogicalBounds(int glyphIndex){
        if ((glyphIndex < 0) || (glyphIndex >= this.getNumGlyphs())){
            // awt.43=glyphIndex is out of vector's limits
            throw new IndexOutOfBoundsException(Messages.getString("awt.43")); //$NON-NLS-1$
        }
        Glyph glyph = this.vector[glyphIndex];

        float x0 = visualPositions[glyphIndex*2];
        float y0 = visualPositions[glyphIndex*2+1];
        float advanceX = glyph.getGlyphPointMetrics().getAdvanceX();

        GeneralPath gp = new GeneralPath();
        gp.moveTo(0, -ascent - leading);
        gp.lineTo(advanceX ,-ascent - leading);
        gp.lineTo(advanceX, descent);
        gp.lineTo(0, descent);
        gp.lineTo(0, -ascent - leading);
        gp.closePath();

        /* Applying GlyphVector font transform */
        AffineTransform at = (AffineTransform)this.transform.clone();

        /* Applying Glyph transform */
        AffineTransform glyphTransform = getGlyphTransform(glyphIndex);
        if (glyphTransform != null){
            at.concatenate(glyphTransform);
        }

        /* Applying translation to actual visual bounds */
        at.preConcatenate(AffineTransform.getTranslateInstance(x0, y0));
        gp.transform(at);
        return gp;
    }

    /**
     * Returns the Font parameter of this GlyphVector
     */
    @Override
    public Font getFont(){
        return this.font;
    }


}