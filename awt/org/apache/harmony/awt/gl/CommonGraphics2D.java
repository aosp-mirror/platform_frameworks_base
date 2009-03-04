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
package org.apache.harmony.awt.gl;


import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Paint;
import java.awt.PaintContext;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.Toolkit;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.image.AffineTransformOp;
import java.awt.image.ImageObserver;
import java.awt.image.BufferedImage;
import java.awt.image.BufferedImageOp;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.WritableRaster;
import java.awt.image.renderable.RenderableImage;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.PathIterator;
import java.awt.geom.RoundRectangle2D;
import java.text.AttributedCharacterIterator;
import java.util.Map;

import org.apache.harmony.awt.gl.Surface;
import org.apache.harmony.awt.gl.image.OffscreenImage;
import org.apache.harmony.awt.gl.render.Blitter;
import org.apache.harmony.awt.gl.render.JavaArcRasterizer;
import org.apache.harmony.awt.gl.render.JavaLineRasterizer;
import org.apache.harmony.awt.gl.render.JavaShapeRasterizer;
import org.apache.harmony.awt.gl.render.JavaTextRenderer;
import org.apache.harmony.awt.gl.render.NullBlitter;

/*
 * List of abstract methods to implement in subclusses
 * Graphics.copyArea(int x, int y, int width, int height, int dx, int dy)
 * Graphics.create()
 * Graphics2D.getDeviceConfiguration()
 * CommonGraphics2D.fillMultiRectAreaColor(MultiRectArea mra);
 * CommonGraphics2D.fillMultiRectAreaPaint(MultiRectArea mra);
 */

/**
 * CommonGraphics2D class is a super class for all system-dependent
 * implementations. It implements major part of Graphics and Graphics2D
 * abstract methods.
 * <h2>CommonGraphics2D Class Internals</h2>
 * <h3>Line and Shape Rasterizers</h3>
 * <p>
 * The CommonGraphics2D class splits all shapes into a set of rectangles 
 * to unify the drawing process for different operating systems and architectures. 
 * For this purpose Java 2D* uses the JavaShapeRasterizer and the JavaLineRasterizer 
 * classes from the org.apache.harmony.awt.gl.render package. The JavaShapeRasterizer 
 * class splits an object implementing a Shape interface into a set of rectangles and 
 * produces a MultiRectArea object. The JavaLineRasterizer class makes line drawing 
 * more accurate and processes lines with strokes, which are instances of the BasicStroke 
 * class.
 * </p>
 * <p>
 * To port the shape drawing to another platform you just need to override 
 * rectangle-drawing methods. However, if your operating system has functions to draw 
 * particular shapes, you can optimize your subclass of the CommonGraphics2D class by 
 * using this functionality in overridden methods.
 * </p>

 * <h3>Blitters</h3>
 * <p>
 * Blitter classes draw images on the display or buffered images. All blitters inherit 
 * the org.apache.harmony.awt.gl.render.Blitter interface.
 * </p>
 * <p>Blitters are divided into:
 * <ul>
 * <li>Native blitters for simple types of images, which the underlying native library 
 * can draw.</li> 
 * <li>Java* blitters for those types of images, which the underlying native library 
 * cannot handle.</li>
 * </ul></p>
 * <p>
 * DRL Java 2D* also uses blitters to fill the shapes and the user-defined subclasses 
 * of the java.awt.Paint class with paints, which the system does not support.
 * </p>
 *
 *<h3>Text Renderers</h3>
 *<p>
 *Text renderers draw strings and glyph vectors. All text renderers are subclasses 
 *of the org.apache.harmony.awt.gl.TextRenderer class.
 *</p>
 *
 */
public abstract class CommonGraphics2D extends Graphics2D {
    protected Surface dstSurf = null;
    protected Blitter blitter = NullBlitter.getInstance();
    protected RenderingHints hints = new RenderingHints(null);

    // Clipping things
    protected MultiRectArea clip = null;

    protected Paint paint = Color.WHITE;
    protected Color fgColor = Color.WHITE;
    protected Color bgColor = Color.BLACK;

    protected Composite composite = AlphaComposite.SrcOver;

    protected Stroke stroke = new BasicStroke();

    //TODO: Think more about FontRenderContext
    protected FontRenderContext frc = new FontRenderContext(null, false, false);

    protected JavaShapeRasterizer jsr = new JavaShapeRasterizer();

    protected Font font = new Font("Dialog", Font.PLAIN, 12);; //$NON-NLS-1$

    protected TextRenderer jtr = JavaTextRenderer.inst;

    // Current graphics transform
    protected AffineTransform transform = new AffineTransform();
    protected double[] matrix = new double[6];

    // Original user->device translation as transform and point
    //public AffineTransform origTransform = new AffineTransform();
    public Point origPoint = new Point(0, 0);


    // Print debug output or not
    protected static final boolean debugOutput = "1".equals(System.getProperty("g2d.debug")); //$NON-NLS-1$ //$NON-NLS-2$

    // Constructors
    protected CommonGraphics2D() {
    }

    protected CommonGraphics2D(int tx, int ty) {
        this(tx, ty, null);
    }

    protected CommonGraphics2D(int tx, int ty, MultiRectArea clip) {
        setTransform(AffineTransform.getTranslateInstance(tx, ty));
        //origTransform = AffineTransform.getTranslateInstance(tx, ty);
        origPoint = new Point(tx, ty);
        setClip(clip);
    }

    // Public methods
    @Override
    public void addRenderingHints(Map<?,?> hints) {
        this.hints.putAll(hints);
    }

    @Override
    public void clearRect(int x, int y, int width, int height) {
        Color c = getColor();
        Paint p = getPaint();
        setColor(getBackground());
        fillRect(x, y, width, height);
        setColor(c);
        setPaint(p);
        if (debugOutput) {
            System.err.println("CommonGraphics2D.clearRect("+x+", "+y+", "+width+", "+height+")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        }
    }

    @Override
    public void clipRect(int x, int y, int width, int height) {
        clip(new Rectangle(x, y, width, height));
    }


    @Override
    public void clip(Shape s) {
        if (s == null) {
            clip = null;
            return;
        }

        MultiRectArea mra = null;
        if (s instanceof MultiRectArea) {
            mra = new MultiRectArea((MultiRectArea)s);
            mra.translate((int)transform.getTranslateX(), (int)transform.getTranslateY());
        } else {
            int type = transform.getType();
            if(s instanceof Rectangle && (type & (AffineTransform.TYPE_IDENTITY |
                AffineTransform.TYPE_TRANSLATION)) != 0){
                    mra = new MultiRectArea((Rectangle)s);
                    if(type == AffineTransform.TYPE_TRANSLATION){
                        mra.translate((int)transform.getTranslateX(), (int)transform.getTranslateY());
                    }
            } else {
                s = transform.createTransformedShape(s);
                mra = jsr.rasterize(s, 0.5);
            }
        }

        if (clip == null) {
            setTransformedClip(mra);
        } else {
            clip.intersect(mra);
            setTransformedClip(clip);
        }
    }

    @Override
    public void dispose() {
        // Do nothing for Java only classes
    }




    /***************************************************************************
     *
     *  Draw methods
     *
     ***************************************************************************/

    @Override
    public void draw(Shape s) {
        if (stroke instanceof BasicStroke && ((BasicStroke)stroke).getLineWidth() <= 1) {
            //TODO: Think about drawing the shape in one fillMultiRectArea call
            BasicStroke bstroke = (BasicStroke)stroke;
            JavaLineRasterizer.LineDasher ld = (bstroke.getDashArray() == null)?null:new JavaLineRasterizer.LineDasher(bstroke.getDashArray(), bstroke.getDashPhase());
            PathIterator pi = s.getPathIterator(transform, 0.5);
            float []points = new float[6];
            int x1 = Integer.MIN_VALUE;
            int y1 = Integer.MIN_VALUE;
            int cx1 = Integer.MIN_VALUE;
            int cy1 = Integer.MIN_VALUE;
            while (!pi.isDone()) {
                switch (pi.currentSegment(points)) {
                    case PathIterator.SEG_MOVETO:
                        x1 = (int)Math.floor(points[0]);
                        y1 = (int)Math.floor(points[1]);
                        cx1 = x1;
                        cy1 = y1;
                        break;
                    case PathIterator.SEG_LINETO:
                        int x2 = (int)Math.floor(points[0]);
                        int y2 = (int)Math.floor(points[1]);
                        fillMultiRectArea(JavaLineRasterizer.rasterize(x1, y1, x2, y2, null, ld, false));
                        x1 = x2;
                        y1 = y2;
                        break;
                    case PathIterator.SEG_CLOSE:
                        x2 = cx1;
                        y2 = cy1;
                        fillMultiRectArea(JavaLineRasterizer.rasterize(x1, y1, x2, y2, null, ld, false));
                        x1 = x2;
                        y1 = y2;
                        break;
                }
                pi.next();
            }
        } else {
            s = stroke.createStrokedShape(s);
            s = transform.createTransformedShape(s);
            fillMultiRectArea(jsr.rasterize(s, 0.5));
        }
    }

    @Override
    public void drawArc(int x, int y, int width, int height, int sa, int ea) {
        if (stroke instanceof BasicStroke && ((BasicStroke)stroke).getLineWidth() <= 1 &&
                ((BasicStroke)stroke).getDashArray() == null && 
                (transform.isIdentity() || transform.getType() == AffineTransform.TYPE_TRANSLATION)) {
            Point p = new Point(x, y);
            transform.transform(p, p);
            MultiRectArea mra = JavaArcRasterizer.rasterize(x, y, width, height, sa, ea, clip);
            fillMultiRectArea(mra);
            return;
        }
        draw(new Arc2D.Float(x, y, width, height, sa, ea, Arc2D.OPEN));
    }


    @Override
    public boolean drawImage(Image image, int x, int y, Color bgcolor,
            ImageObserver imageObserver) {

        if(image == null) {
            return true;
        }

        boolean done = false;
        boolean somebits = false;
        Surface srcSurf = null;
        if(image instanceof OffscreenImage){
            OffscreenImage oi = (OffscreenImage) image;
            if((oi.getState() & ImageObserver.ERROR) != 0) {
                return false;
            }
            done = oi.prepareImage(imageObserver);
            somebits = (oi.getState() & ImageObserver.SOMEBITS) != 0;
            srcSurf = oi.getImageSurface();
        }else{
            done = true;
            srcSurf = Surface.getImageSurface(image);
        }

        if(done || somebits) {
            int w = srcSurf.getWidth();
            int h = srcSurf.getHeight();
            blitter.blit(0, 0, srcSurf, x, y, dstSurf, w, h, (AffineTransform) transform.clone(),
                    composite, bgcolor, clip);
        }
        return done;
    }

    @Override
    public boolean drawImage(Image image, int x, int y, ImageObserver imageObserver) {
        return drawImage(image, x, y, null, imageObserver);
    }

    @Override
    public boolean drawImage(Image image, int x, int y, int width, int height,
            Color bgcolor, ImageObserver imageObserver) {

        if(image == null) {
            return true;
        }
        if(width == 0 || height == 0) {
            return true;
        }

        boolean done = false;
        boolean somebits = false;
        Surface srcSurf = null;

        if(image instanceof OffscreenImage){
            OffscreenImage oi = (OffscreenImage) image;
            if((oi.getState() & ImageObserver.ERROR) != 0) {
                return false;
            }
            done = oi.prepareImage(imageObserver);
            somebits = (oi.getState() & ImageObserver.SOMEBITS) != 0;
            srcSurf = oi.getImageSurface();
        }else{
            done = true;
            srcSurf = Surface.getImageSurface(image);
        }

        if(done || somebits) {
            int w = srcSurf.getWidth();
            int h = srcSurf.getHeight();
            if(w == width && h == height){
                blitter.blit(0, 0, srcSurf, x, y, dstSurf, w, h,
                        (AffineTransform) transform.clone(),
                        composite, bgcolor, clip);
            }else{
                AffineTransform xform = new AffineTransform();
                xform.setToScale((float)width / w, (float)height / h);
                blitter.blit(0, 0, srcSurf, x, y, dstSurf, w, h,
                        (AffineTransform) transform.clone(),
                        xform, composite, bgcolor, clip);
            }
        }
        return done;
    }

    @Override
    public boolean drawImage(Image image, int x, int y, int width, int height,
            ImageObserver imageObserver) {
        return drawImage(image, x, y, width, height, null, imageObserver);
    }

    @Override
    public boolean drawImage(Image image, int dx1, int dy1, int dx2, int dy2,
            int sx1, int sy1, int sx2, int sy2, Color bgcolor,
            ImageObserver imageObserver) {

        if(image == null) {
            return true;
        }
        if(dx1 == dx2 || dy1 == dy2 || sx1 == sx2 || sy1 == sy2) {
            return true;
        }

        boolean done = false;
        boolean somebits = false;
        Surface srcSurf = null;
        if(image instanceof OffscreenImage){
            OffscreenImage oi = (OffscreenImage) image;
            if((oi.getState() & ImageObserver.ERROR) != 0) {
                return false;
            }
            done = oi.prepareImage(imageObserver);
            somebits = (oi.getState() & ImageObserver.SOMEBITS) != 0;
            srcSurf = oi.getImageSurface();
        }else{
            done = true;
            srcSurf = Surface.getImageSurface(image);
        }

        if(done || somebits) {

            int dstX = dx1;
            int dstY = dy1;
            int srcX = sx1;
            int srcY = sy1;

            int dstW = dx2 - dx1;
            int dstH = dy2 - dy1;
            int srcW = sx2 - sx1;
            int srcH = sy2 - sy1;

            if(srcW == dstW && srcH == dstH){
                blitter.blit(srcX, srcY, srcSurf, dstX, dstY, dstSurf, srcW, srcH,
                        (AffineTransform) transform.clone(),
                        composite, bgcolor, clip);
            }else{
                AffineTransform xform = new AffineTransform();
                xform.setToScale((float)dstW / srcW, (float)dstH / srcH);
                blitter.blit(srcX, srcY, srcSurf, dstX, dstY, dstSurf, srcW, srcH,
                        (AffineTransform) transform.clone(),
                        xform, composite, bgcolor, clip);
            }
        }
        return done;
    }

    @Override
    public boolean drawImage(Image image, int dx1, int dy1, int dx2, int dy2,
            int sx1, int sy1, int sx2, int sy2, ImageObserver imageObserver) {

        return drawImage(image, dx1, dy1, dx2, dy2, sx1, sy1, sx2, sy2, null,
                imageObserver);
     }

    @Override
    public void drawImage(BufferedImage bufImage, BufferedImageOp op,
            int x, int y) {

        if(bufImage == null) {
            return;
        }

        if(op == null) {
            drawImage(bufImage, x, y, null);
        } else if(op instanceof AffineTransformOp){
            AffineTransformOp atop = (AffineTransformOp) op;
            AffineTransform xform = atop.getTransform();
            Surface srcSurf = Surface.getImageSurface(bufImage);
            int w = srcSurf.getWidth();
            int h = srcSurf.getHeight();
            blitter.blit(0, 0, srcSurf, x, y, dstSurf, w, h,
                    (AffineTransform) transform.clone(), xform,
                    composite, null, clip);
        } else {
            bufImage = op.filter(bufImage, null);
            Surface srcSurf = Surface.getImageSurface(bufImage);
            int w = srcSurf.getWidth();
            int h = srcSurf.getHeight();
            blitter.blit(0, 0, srcSurf, x, y, dstSurf, w, h,
                    (AffineTransform) transform.clone(),
                    composite, null, clip);
        }
    }

    @Override
    public boolean drawImage(Image image, AffineTransform trans,
            ImageObserver imageObserver) {

        if(image == null) {
            return true;
        }
        if(trans == null || trans.isIdentity()) {
            return drawImage(image, 0, 0, imageObserver);
        }

        boolean done = false;
        boolean somebits = false;
        Surface srcSurf = null;
        if(image instanceof OffscreenImage){
            OffscreenImage oi = (OffscreenImage) image;
            if((oi.getState() & ImageObserver.ERROR) != 0) {
                return false;
            }
            done = oi.prepareImage(imageObserver);
            somebits = (oi.getState() & ImageObserver.SOMEBITS) != 0;
            srcSurf = oi.getImageSurface();
        }else{
            done = true;
            srcSurf = Surface.getImageSurface(image);
        }

        if(done || somebits) {
            int w = srcSurf.getWidth();
            int h = srcSurf.getHeight();
            AffineTransform xform = (AffineTransform) transform.clone();
            xform.concatenate(trans);
            blitter.blit(0, 0, srcSurf, 0, 0, dstSurf, w, h, xform, composite,
                    null, clip);
        }
        return done;
    }

    @Override
    public void drawLine(int x1, int y1, int x2, int y2) {
        if (debugOutput) {
            System.err.println("CommonGraphics2D.drawLine("+x1+", "+y1+", "+x2+", "+y2+")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        }

        if (stroke instanceof BasicStroke && ((BasicStroke)stroke).getLineWidth() <= 1) {
            BasicStroke bstroke = (BasicStroke)stroke;
            Point p1 = new Point(x1, y1);
            Point p2 = new Point(x2, y2);
            transform.transform(p1, p1);
            transform.transform(p2, p2);
            JavaLineRasterizer.LineDasher ld = (bstroke.getDashArray() == null)?null:new JavaLineRasterizer.LineDasher(bstroke.getDashArray(), bstroke.getDashPhase());
            MultiRectArea mra = JavaLineRasterizer.rasterize(p1.x, p1.y, p2.x, p2.y, null, ld, false);
            fillMultiRectArea(mra);
            return;
        }
        draw(new Line2D.Float(x1, y1, x2, y2));
    }

    @Override
    public void drawOval(int x, int y, int width, int height) {
        if (stroke instanceof BasicStroke && ((BasicStroke)stroke).getLineWidth() <= 1 &&
                ((BasicStroke)stroke).getDashArray() == null && 
                (transform.isIdentity() || transform.getType() == AffineTransform.TYPE_TRANSLATION)) {
            Point p = new Point(x, y);
            transform.transform(p, p);
            MultiRectArea mra = JavaArcRasterizer.rasterize(x, y, width, height, 0, 360, clip);
            fillMultiRectArea(mra);
            return;
        }
        draw(new Ellipse2D.Float(x, y, width, height));
    }

    @Override
    public void drawPolygon(int[] xpoints, int[] ypoints, int npoints) {
        draw(new Polygon(xpoints, ypoints, npoints));
    }

    @Override
    public void drawPolygon(Polygon polygon) {
        draw(polygon);
    }

    @Override
    public void drawPolyline(int[] xpoints, int[] ypoints, int npoints) {
        for (int i = 0; i < npoints-1; i++) {
            drawLine(xpoints[i], ypoints[i], xpoints[i+1], ypoints[i+1]);
        }
    }

    @Override
    public void drawRenderableImage(RenderableImage img, AffineTransform xform) {
        if (img == null) {
            return;
        }

        double scaleX = xform.getScaleX();
        double scaleY = xform.getScaleY();
        if (scaleX == 1 && scaleY == 1) {
            drawRenderedImage(img.createDefaultRendering(), xform);
        } else {
            int width = (int)Math.round(img.getWidth()*scaleX);
            int height = (int)Math.round(img.getHeight()*scaleY);
            xform = (AffineTransform)xform.clone();
            xform.scale(1, 1);
            drawRenderedImage(img.createScaledRendering(width, height, null), xform);
        }
    }

    @Override
    public void drawRenderedImage(RenderedImage rimg, AffineTransform xform) {
        if (rimg == null) {
            return;
        }

        Image img = null;

        if (rimg instanceof Image) {
            img = (Image)rimg;
        } else {
            //TODO: Create new class to provide Image interface for RenderedImage or rewrite this method
            img = new BufferedImage(rimg.getColorModel(), rimg.copyData(null), false, null);
        }

        drawImage(img, xform, null);
    }

    @Override
    public void drawRoundRect(int x, int y, int width, int height, int arcWidth, int arcHeight) {
        if (debugOutput) {
            System.err.println("CommonGraphics2D.drawRoundRect("+x+", "+y+", "+width+", "+height+","+arcWidth+", "+arcHeight+")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        }

        draw(new RoundRectangle2D.Float(x, y, width, height, arcWidth, arcHeight));
    }





    /***************************************************************************
     *
     *  String methods
     *
     ***************************************************************************/

    @Override
    public void drawString(AttributedCharacterIterator iterator, float x, float y) {
        GlyphVector gv = font.createGlyphVector(frc, iterator);
        drawGlyphVector(gv, x, y);
    }

    @Override
    public void drawString(AttributedCharacterIterator iterator, int x, int y) {
        drawString(iterator, (float)x, (float)y);
    }

    @Override
    public void drawString(String str, int x, int y) {
        drawString(str, (float)x, (float)y);
    }

    @Override
    public void drawString(String str, float x, float y) {
        if (debugOutput) {
            System.err.println("CommonGraphics2D.drawString("+str+", "+x+", "+y+")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }

        AffineTransform at = (AffineTransform)this.getTransform().clone();
        AffineTransform fontTransform = font.getTransform();
        at.concatenate(fontTransform);

        double[] matrix = new double[6];
        if (!at.isIdentity()){

            int atType = at.getType();
            at.getMatrix(matrix);

            // TYPE_TRANSLATION
            if (atType == AffineTransform.TYPE_TRANSLATION){
                jtr.drawString(this, str,
                        (float)(x+fontTransform.getTranslateX()),
                        (float)(y+fontTransform.getTranslateY()));
                return;
            }
            // TODO: we use slow type of drawing strings when Font object
            // in Graphics has transforms, we just fill outlines. New textrenderer
            // is to be implemented.
            Shape sh = font.createGlyphVector(this.getFontRenderContext(), str).getOutline(x, y);
            this.fill(sh);

        } else {
            jtr.drawString(this, str, x, y);
        }

    }

    @Override
    public void drawGlyphVector(GlyphVector gv, float x, float y) {

        AffineTransform at = gv.getFont().getTransform();

        double[] matrix = new double[6];
        if ((at != null) && (!at.isIdentity())){

            int atType = at.getType();
            at.getMatrix(matrix);

            // TYPE_TRANSLATION
            if ((atType == AffineTransform.TYPE_TRANSLATION) &&
                ((gv.getLayoutFlags() & GlyphVector.FLAG_HAS_TRANSFORMS) == 0)){
                jtr.drawGlyphVector(this, gv, (int)(x+matrix[4]), (int)(y+matrix[5]));
                return;
            }
        } else {
            if (((gv.getLayoutFlags() & GlyphVector.FLAG_HAS_TRANSFORMS) == 0)){
                jtr.drawGlyphVector(this, gv, x, y);
                return;
            }
        }

        // TODO: we use slow type of drawing strings when Font object
        // in Graphics has transforms, we just fill outlines. New textrenderer
        // is to be implemented.

        Shape sh = gv.getOutline(x, y);
        this.fill(sh);

        }




    /***************************************************************************
     *
     *  Fill methods
     *
     ***************************************************************************/

    @Override
    public void fill(Shape s) {
        s = transform.createTransformedShape(s);
        MultiRectArea mra = jsr.rasterize(s, 0.5);
        fillMultiRectArea(mra);
    }

    @Override
    public void fillArc(int x, int y, int width, int height, int sa, int ea) {
        fill(new Arc2D.Float(x, y, width, height, sa, ea, Arc2D.PIE));
    }

    @Override
    public void fillOval(int x, int y, int width, int height) {
        fill(new Ellipse2D.Float(x, y, width, height));
    }

    @Override
    public void fillPolygon(int[] xpoints, int[] ypoints, int npoints) {
        fill(new Polygon(xpoints, ypoints, npoints));
    }

    @Override
    public void fillPolygon(Polygon polygon) {
        fill(polygon);
    }

    @Override
    public void fillRect(int x, int y, int width, int height) {
        if (debugOutput) {
            System.err.println("CommonGraphics2D.fillRect("+x+", "+y+", "+width+", "+height+")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        }

        fill(new Rectangle(x, y, width, height));
    }

    @Override
    public void fillRoundRect(int x, int y, int width, int height, int arcWidth, int arcHeight) {
        if (debugOutput) {
            System.err.println("CommonGraphics2D.fillRoundRect("+x+", "+y+", "+width+", "+height+","+arcWidth+", "+arcHeight+")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        }

        fill(new RoundRectangle2D.Float(x, y, width, height, arcWidth, arcHeight));
    }




    /***************************************************************************
     *
     *  Get methods
     *
     ***************************************************************************/

    @Override
    public Color getBackground() {
        return bgColor;
    }

    @Override
    public Shape getClip() {
        if (clip == null) {
            return null;
        }

        MultiRectArea res = new MultiRectArea(clip);
        res.translate(-Math.round((float)transform.getTranslateX()), -Math.round((float)transform.getTranslateY()));
        return res;
    }

    @Override
    public Rectangle getClipBounds() {
        if (clip == null) {
            return null;
        }

        Rectangle res = (Rectangle) clip.getBounds().clone();
        res.translate(-Math.round((float)transform.getTranslateX()), -Math.round((float)transform.getTranslateY()));
        return res;
    }

    @Override
    public Color getColor() {
        return fgColor;
    }

    @Override
    public Composite getComposite() {
        return composite;
    }

    @Override
    public Font getFont() {
        return font;
    }

    @SuppressWarnings("deprecation")
    @Override
    public FontMetrics getFontMetrics(Font font) {
        return Toolkit.getDefaultToolkit().getFontMetrics(font);
    }

    @Override
    public FontRenderContext getFontRenderContext() {
        return frc;
    }

    @Override
    public Paint getPaint() {
        return paint;
    }

    @Override
    public Object getRenderingHint(RenderingHints.Key key) {
        return hints.get(key);
    }

    @Override
    public RenderingHints getRenderingHints() {
        return hints;
    }

    @Override
    public Stroke getStroke() {
        return stroke;
    }

    @Override
    public AffineTransform getTransform() {
        return (AffineTransform)transform.clone();
    }

    @Override
    public boolean hit(Rectangle rect, Shape s, boolean onStroke) {
        //TODO: Implement method....
        return false;
    }




    /***************************************************************************
     *
     *  Transformation methods
     *
     ***************************************************************************/

    @Override
    public void rotate(double theta) {
        transform.rotate(theta);
        transform.getMatrix(matrix);
    }

    @Override
    public void rotate(double theta, double x, double y) {
        transform.rotate(theta, x, y);
        transform.getMatrix(matrix);
    }

    @Override
    public void scale(double sx, double sy) {
        transform.scale(sx, sy);
        transform.getMatrix(matrix);
    }

    @Override
    public void shear(double shx, double shy) {
        transform.shear(shx, shy);
        transform.getMatrix(matrix);
    }

    @Override
    public void transform(AffineTransform at) {
        transform.concatenate(at);
        transform.getMatrix(matrix);
    }

    @Override
    public void translate(double tx, double ty) {
        if (debugOutput) {
            System.err.println("CommonGraphics2D.translate("+tx+", "+ty+")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }

        transform.translate(tx, ty);
        transform.getMatrix(matrix);
    }

    @Override
    public void translate(int tx, int ty) {
        if (debugOutput) {
            System.err.println("CommonGraphics2D.translate("+tx+", "+ty+")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }

        transform.translate(tx, ty);
        transform.getMatrix(matrix);
    }




    /***************************************************************************
     *
     *  Set methods
     *
     ***************************************************************************/

    @Override
    public void setBackground(Color color) {
        bgColor = color;
    }

    @Override
    public void setClip(int x, int y, int width, int height) {
        setClip(new Rectangle(x, y, width, height));
    }

    @Override
    public void setClip(Shape s) {
        if (s == null) {
            setTransformedClip(null);
            if (debugOutput) {
                System.err.println("CommonGraphics2D.setClip(null)"); //$NON-NLS-1$
            }
            return;
        }

        if (debugOutput) {
            System.err.println("CommonGraphics2D.setClip("+s.getBounds()+")"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        if (s instanceof MultiRectArea) {
            MultiRectArea nclip = new MultiRectArea((MultiRectArea)s);
            nclip.translate(Math.round((float)transform.getTranslateX()), Math.round((float)transform.getTranslateY()));
            setTransformedClip(nclip);
        } else {
            int type = transform.getType();
            if(s instanceof Rectangle && (type & (AffineTransform.TYPE_IDENTITY |
                AffineTransform.TYPE_TRANSLATION)) != 0){
                    MultiRectArea nclip = new MultiRectArea((Rectangle)s);
                    if(type == AffineTransform.TYPE_TRANSLATION){
                        nclip.translate((int)transform.getTranslateX(), (int)transform.getTranslateY());
                    }
                    setTransformedClip(nclip);
            } else {
                s = transform.createTransformedShape(s);
                setTransformedClip(jsr.rasterize(s, 0.5));
            }
        }
    }

    @Override
    public void setColor(Color color) {
        if (color != null) {
            fgColor = color;
            paint = color;
        }
    }

    @Override
    public void setComposite(Composite composite) {
        this.composite = composite;
    }

    @Override
    public void setFont(Font font) {
        this.font = font;
    }

    @Override
    public void setPaint(Paint paint) {
        if (paint == null)
            return;
            
        this.paint = paint;
        if (paint instanceof Color) {
            fgColor = (Color)paint;
        }
    }

    @Override
    public void setPaintMode() {
        composite = AlphaComposite.SrcOver;
    }

    @Override
    public void setRenderingHint(RenderingHints.Key key, Object value) {
        hints.put(key, value);
    }

    @Override
    public void setRenderingHints(Map<?,?> hints) {
        this.hints.clear();
        this.hints.putAll(hints);
    }

    @Override
    public void setStroke(Stroke stroke) {
        this.stroke = stroke;
    }

    @Override
    public void setTransform(AffineTransform transform) {
        this.transform = transform;

        transform.getMatrix(matrix);
    }

    @Override
    public void setXORMode(Color color) {
        composite = new XORComposite(color);
    }


    // Protected methods
    protected void setTransformedClip(MultiRectArea clip) {
        this.clip = clip;
    }

    /**
     * This method fills the given MultiRectArea with current paint.
     * It calls fillMultiRectAreaColor and fillMultiRectAreaPaint 
     * methods depending on the type of current paint.
     * @param mra MultiRectArea to fill
     */
    protected void fillMultiRectArea(MultiRectArea mra) {
        if (clip != null) {
            mra.intersect(clip);
        }

        // Return if all stuff is clipped
        if (mra.rect[0] < 5) {
            return;
        }

        if (debugOutput) {
            System.err.println("CommonGraphics2D.fillMultiRectArea("+mra+")"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        if (paint instanceof Color){
            fillMultiRectAreaColor(mra);
        }else{
            fillMultiRectAreaPaint(mra);
        }
    }

    /**
     * This method fills the given MultiRectArea with solid color.
     * @param mra MultiRectArea to fill
     */
    protected void fillMultiRectAreaColor(MultiRectArea mra) {
        fillMultiRectAreaPaint(mra);
    }

    /**
     * This method fills the given MultiRectArea with any paint.
     * @param mra MultiRectArea to fill
     */
    protected void fillMultiRectAreaPaint(MultiRectArea mra) {
        Rectangle rec = mra.getBounds();
        int x = rec.x;
        int y = rec.y;
        int w = rec.width;
        int h = rec.height;
        if(w <= 0 || h <= 0) {
            return;
        }
        PaintContext pc = paint.createContext(null, rec, rec, transform, hints);
        Raster r = pc.getRaster(x, y, w, h);
        WritableRaster wr;
        if(r instanceof WritableRaster){
            wr = (WritableRaster) r;
        }else{
            wr = r.createCompatibleWritableRaster();
            wr.setRect(r);
        }
        Surface srcSurf = new ImageSurface(pc.getColorModel(), wr);
        blitter.blit(0, 0, srcSurf, x, y, dstSurf, w, h,
                composite, null, mra);
        srcSurf.dispose();
    }

    /**
     * Copies graphics class fields. 
     * Used in create method
     * 
     * @param copy Graphics class to copy
     */
    protected void copyInternalFields(CommonGraphics2D copy) {
        if (clip == null) {
            copy.setTransformedClip(null);
        } else {
            copy.setTransformedClip(new MultiRectArea(clip));
        }
        copy.setBackground(bgColor);
        copy.setColor(fgColor);
        copy.setPaint(paint);
        copy.setComposite(composite);
        copy.setStroke(stroke);
        copy.setFont(font);
        copy.setTransform(new AffineTransform(transform));
        //copy.origTransform = new AffineTransform(origTransform);
        copy.origPoint = new Point(origPoint);
    }
}