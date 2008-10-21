/*
 * Copyright 2007, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at 
 *
 *     http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License.
 */

package com.android.internal.awt;

import com.android.internal.awt.AndroidGraphicsConfiguration;
import com.android.internal.graphics.NativeUtils;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.GeneralPath;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.PathIterator;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.awt.image.BufferedImageOp;
import java.awt.image.DataBuffer;
import java.awt.image.DirectColorModel;
import java.awt.image.ImageObserver;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.SinglePixelPackedSampleModel;
import java.awt.image.WritableRaster;
import java.awt.image.renderable.RenderableImage;
import java.text.AttributedCharacterIterator;
import java.util.Map;

import org.apache.harmony.awt.gl.ImageSurface;
import org.apache.harmony.awt.gl.MultiRectArea;
import org.apache.harmony.awt.gl.Surface;
import org.apache.harmony.awt.gl.font.AndroidGlyphVector;
import org.apache.harmony.awt.gl.font.FontMetricsImpl;
import org.apache.harmony.awt.gl.image.OffscreenImage;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;

import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.Typeface;
import android.graphics.PixelXorXfermode;
import android.view.Display;
import android.view.WindowManager;
import android.content.Context;

public class AndroidGraphics2D extends Graphics2D {
    
    private int displayWidth, displayHeight;

    protected Surface dstSurf = null;
    protected MultiRectArea clip = null;

    protected Composite composite = AlphaComposite.SrcOver;
    protected AffineTransform transform = new AffineTransform();

    private static AndroidGraphics2D mAg;
    private static Canvas mC;

    // Android Paint
    public static Paint mP;

    private static java.awt.Font mFnt;

    // Cached Matrix
    public static Matrix mM;
    private static FontMetrics mFm;
    private static RenderingHints mRh;
    private static Color mBc;

    private Area mCurrClip;
    
    public final static double RAD_360 = Math.PI / 180 * 360;
    
    // Image drawing
    private AndroidJavaBlitter blitter;
    private DirectColorModel cm;
    private SinglePixelPackedSampleModel sm;
    private WritableRaster wr;


    public static AndroidGraphics2D getInstance() {
        if (mAg == null) {
            throw new RuntimeException("AndroidGraphics2D not instantiated!");
        }
        return mAg;
    }

    public static AndroidGraphics2D getInstance(Context ctx, Canvas c, Paint p) {
        if (c == null || ctx == null) {
            throw new RuntimeException(
                    "Illegal argument, Canvas cannot be null!");
        }
        mAg = new AndroidGraphics2D(ctx, c, p);
        return mAg;
    }

    private AndroidGraphics2D(Context ctx, Canvas c, Paint p) {
        super();
        mC = c;
        mP = p;
        mM = new Matrix();
        mM.reset();
        mM = mC.getMatrix();
        Rect r = mC.getClipBounds();
        int cl[] = {-1, r.top, r.left, -2, r.top, r.right, -2, r.bottom, r.right, -2, r.bottom, r.left};
        mCurrClip = new Area(createShape(cl));
        if(ctx != null) {
            WindowManager wm = (WindowManager)ctx.getSystemService(Context.WINDOW_SERVICE);
            Display d = wm.getDefaultDisplay();
            displayWidth = d.getWidth();
            displayHeight = d.getHeight();
        }
        blitter = new AndroidJavaBlitter(c);
        cm = new DirectColorModel(32, 0xff0000, 0xff00, 0xff, 0xff000000);
        sm = new SinglePixelPackedSampleModel(
                DataBuffer.TYPE_INT, displayWidth, displayHeight, cm.getMasks());
        wr = Raster.createWritableRaster(sm, null);
        dstSurf = new ImageSurface(cm, wr);       
    }

    @Override
    public void addRenderingHints(Map<?, ?> hints) {
        if (mRh == null) {
            mRh = (RenderingHints) hints;
        }
        mRh.add((RenderingHints) hints);
    }

    public float[] getMatrix() {
        float[] f = new float[9];
        mC.getMatrix().getValues(f);
        return f;
    }

    /**
     * 
     * @return a Matrix in Android format
     */
    public float[] getInverseMatrix() {
        AffineTransform af = new AffineTransform(createAWTMatrix(getMatrix()));
        try {
            af = af.createInverse();
        } catch (NoninvertibleTransformException e) {
        }
        return createMatrix(af);
    }

    private Path getPath(Shape s) {
        Path path = new Path();
        PathIterator pi = s.getPathIterator(null);
        while (pi.isDone() == false) {
            getCurrentSegment(pi, path);
            pi.next();
        }
        return path;
    }

    private void getCurrentSegment(PathIterator pi, Path path) {
        float[] coordinates = new float[6];
        int type = pi.currentSegment(coordinates);
        switch (type) {
        case PathIterator.SEG_MOVETO:
            path.moveTo(coordinates[0], coordinates[1]);
            break;
        case PathIterator.SEG_LINETO:
            path.lineTo(coordinates[0], coordinates[1]);
            break;
        case PathIterator.SEG_QUADTO:
            path.quadTo(coordinates[0], coordinates[1], coordinates[2],
                    coordinates[3]);
            break;
        case PathIterator.SEG_CUBICTO:
            path.cubicTo(coordinates[0], coordinates[1], coordinates[2],
                    coordinates[3], coordinates[4], coordinates[5]);
            break;
        case PathIterator.SEG_CLOSE:
            path.close();
            break;
        default:
            break;
        }
    }
    
    private Shape createShape(int[] arr) {
        Shape s = new GeneralPath();
        for(int i = 0; i < arr.length; i++) {
            int type = arr[i];    
            switch (type) {
            case -1:
                //MOVETO
                ((GeneralPath)s).moveTo(arr[++i], arr[++i]);
                break;
            case -2:
                //LINETO
                ((GeneralPath)s).lineTo(arr[++i], arr[++i]);
                break;
            case -3:
                //QUADTO
                ((GeneralPath)s).quadTo(arr[++i], arr[++i], arr[++i],
                        arr[++i]);
                break;
            case -4:
                //CUBICTO
                ((GeneralPath)s).curveTo(arr[++i], arr[++i], arr[++i],
                        arr[++i], arr[++i], arr[++i]);
                break;
            case -5:
                //CLOSE
                return s;
            default:
                break;
            }
        }
        return s;
    }
    /*
    public int[] getPixels() {
        return mC.getPixels();
    }*/

    public static float getRadian(float degree) {
        return (float) ((Math.PI / 180) * degree);
    }
    
    private Shape getShape() {
        return null;
    }

    public static float getDegree(float radian) {
        return (float) ((180 / Math.PI) * radian);
    }

    /*
     * Degree in radian
     */
    public static float getEllipsisX(float degree, float princAxis) {
        return (float) Math.cos(degree) * princAxis;
    }

    public static float getEllipsisY(float degree, float conAxis) {
        return (float) Math.sin(degree) * conAxis;
    }

    @Override
    public void clip(Shape s) {
        mC.clipPath(getPath(s));
    }

    public void setCanvas(Canvas c) {
        mC = c;
    }

    @Override
    public void draw(Shape s) {
        if (mP == null) {
            mP = new Paint();
        }
        Paint.Style tmp = mP.getStyle();
        mP.setStyle(Paint.Style.STROKE);
        mC.drawPath(getPath(s), mP);
        mP.setStyle(tmp);
    }
/*
    private ArrayList getSegments(Shape s) {
        ArrayList arr = new ArrayList();
        PathIterator pi = s.getPathIterator(null);
        while (pi.isDone() == false) {
            getCurrentSegment(pi, arr);
            pi.next();
        }
        return arr;
    }

    private void getCurrentSegment(PathIterator pi, ArrayList arr) {
        float[] coordinates = new float[6];
        int type = pi.currentSegment(coordinates);
        switch (type) {
        case PathIterator.SEG_MOVETO:
            arr.add(new Integer(-1));
            break;
        case PathIterator.SEG_LINETO:
            arr.add(new Integer(-2));
            break;
        case PathIterator.SEG_QUADTO:
            arr.add(new Integer(-3));
            break;
        case PathIterator.SEG_CUBICTO:
            arr.add(new Integer(-4));
            break;
        case PathIterator.SEG_CLOSE:
            arr.add(new Integer(-5));
            break;
        default:
            break;
        }
    }
*/
    /*
     * Convenience method, not standard AWT
     */
    public void draw(Path s) {
        if (mP == null) {
            mP = new Paint();
        }
        Paint.Style tmp = mP.getStyle();
        mP.setStyle(Paint.Style.STROKE);
        s.transform(mM);
        mC.drawPath(s, mP);
        mP.setStyle(tmp);
    }

    @Override
    public void drawGlyphVector(GlyphVector g, float x, float y) {
        // TODO draw at x, y
        // draw(g.getOutline());
        /*
        Matrix matrix = new Matrix();
        matrix.setTranslate(x, y);
        Path pth = getPath(g.getOutline());
        pth.transform(matrix);
        draw(pth);
        */
        Path path = new Path();
        char[] c = ((AndroidGlyphVector)g).getGlyphs();
        mP.getTextPath(c, 0, c.length, x, y, path);
        mC.drawPath(path, mP);
    }

    @Override
    public void drawRenderableImage(RenderableImage img, AffineTransform xform) {
        throw new RuntimeException("Not implemented!");
    }

    @Override
    public void drawRenderedImage(RenderedImage img, AffineTransform xform) {
        throw new RuntimeException("Not implemented!");
    }

    @Override
    public void drawString(AttributedCharacterIterator iterator, float x,
            float y) {
        throw new RuntimeException("AttributedCharacterIterator not supported!");

    }

    @Override
    public void drawString(AttributedCharacterIterator iterator, int x, int y) {
        throw new RuntimeException("AttributedCharacterIterator not supported!");

    }

    @Override
    public void drawString(String s, float x, float y) {
            if (mP == null) {
                mP = new Paint();
            }
            Paint.Style tmp = mP.getStyle();

            mP.setStyle(Paint.Style.FILL);
            Path pth = new Path();
            mP.getTextPath(s, 0, s.length(), x, y, pth);
            mC.drawPath(pth, mP);
            mP.setStyle(tmp);
    }

    @Override
    public void drawString(String str, int x, int y) {
            if (mP == null) {
                mP = new Paint();
            }
            Paint.Style tmp = mP.getStyle();
            mP.setStrokeWidth(0);

            mC.drawText(str.toCharArray(), 0, str.toCharArray().length, x, y,
                    mP);
            mP.setStyle(tmp);
    }

    @Override
    public void fill(Shape s) {
            if (mP == null) {
                mP = new Paint();
            }
            Paint.Style tmp = mP.getStyle();
            mP.setStyle(Paint.Style.FILL);
            mC.drawPath(getPath(s), mP);
            mP.setStyle(tmp);
    }

    @Override
    public Color getBackground() {
        return mBc;
    }

    @Override
    public Composite getComposite() {
        throw new RuntimeException("Composite not implemented!");
    }

    @Override
    public GraphicsConfiguration getDeviceConfiguration() {
        return new AndroidGraphicsConfiguration();
    }

    @Override
    public FontRenderContext getFontRenderContext() {
        return new FontRenderContext(getTransform(), mP.isAntiAlias(), true);
    }

    @Override
    public java.awt.Paint getPaint() {
        throw new RuntimeException("AWT Paint not implemented in Android!");
    }

    public static Canvas getAndroidCanvas() {
        return mC;
    }
    
    public static Paint getAndroidPaint() {
        return mP;
    }

    @Override
    public RenderingHints getRenderingHints() {
        return mRh;
    }

    @Override
    public Stroke getStroke() {
        if (mP != null) {
            return new BasicStroke(mP.getStrokeWidth(), mP.getStrokeCap()
                    .ordinal(), mP.getStrokeJoin().ordinal());
        }
        return null;
    }

    @Override
    public AffineTransform getTransform() {
        return new AffineTransform(createAWTMatrix(getMatrix()));
    }

    @Override
    public boolean hit(Rectangle rect, Shape s, boolean onStroke) {
        // ???AWT TODO check if on stroke
        return s.intersects(rect.getX(), rect.getY(), rect.getWidth(), rect
                .getHeight());
    }

    @Override
    public void rotate(double theta) {
        mM.preRotate((float) AndroidGraphics2D
                .getDegree((float) (RAD_360 - theta)));
        mC.concat(mM);
    }

    @Override
    public void rotate(double theta, double x, double y) {
        mM.preRotate((float) AndroidGraphics2D.getDegree((float) theta),
                (float) x, (float) y);
        mC.concat(mM);
    }

    @Override
    public void scale(double sx, double sy) {
        mM.setScale((float) sx, (float) sy);
        mC.concat(mM);
    }

    @Override
    public void setBackground(Color color) {
        mBc = color;
        mC.clipRect(new Rect(0, 0, mC.getWidth(), mC.getHeight()));
        // TODO don't limit to current clip
        mC.drawARGB(color.getAlpha(), color.getRed(), color.getGreen(), color
                .getBlue());
    }

    @Override
    public void setComposite(Composite comp) {
        throw new RuntimeException("Composite not implemented!");
    }

    public void setSpaint(Paint paint) {
        mP = paint;
    }

    @Override
    public void setPaint(java.awt.Paint paint) {
        setColor((Color)paint);
    }

    @Override
    public Object getRenderingHint(RenderingHints.Key key) {
        if (mRh == null) {
            return null;
        }
        return mRh.get(key);
    }

    @Override
    public void setRenderingHint(RenderingHints.Key hintKey, Object hintValue) {
        if (mRh == null) {
            mRh = new RenderingHints(hintKey, hintValue);
        } else {
            mRh.put(hintKey, hintValue);
        }
        applyHints();
    }

    @Override
    public void setRenderingHints(Map<?, ?> hints) {
        mRh = (RenderingHints) hints;
        applyHints();
    }

    private void applyHints() {
        Object o;

        // TODO do something like this:
        /*
         * Set s = mRh.keySet(); Iterator it = s.iterator(); while(it.hasNext()) {
         * o = it.next(); }
         */

        // /////////////////////////////////////////////////////////////////////
        // not supported in skia
        /*
         * o = mRh.get(RenderingHints.KEY_ALPHA_INTERPOLATION); if
         * (o.equals(RenderingHints.VALUE_ALPHA_INTERPOLATION_DEFAULT)) { } else
         * if (o.equals(RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY)) { }
         * else if (o.equals(RenderingHints.VALUE_ALPHA_INTERPOLATION_SPEED)) { }
         * 
         * o = mRh.get(RenderingHints.KEY_COLOR_RENDERING); if
         * (o.equals(RenderingHints.VALUE_COLOR_RENDER_DEFAULT)) { } else if
         * (o.equals(RenderingHints.VALUE_COLOR_RENDER_QUALITY)) { } else if
         * (o.equals(RenderingHints.VALUE_COLOR_RENDER_SPEED)) { }
         * 
         * o = mRh.get(RenderingHints.KEY_DITHERING); if
         * (o.equals(RenderingHints.VALUE_DITHER_DEFAULT)) { } else if
         * (o.equals(RenderingHints.VALUE_DITHER_DISABLE)) { } else if
         * (o.equals(RenderingHints.VALUE_DITHER_ENABLE)) { }
         * 
         * o = mRh.get(RenderingHints.KEY_FRACTIONALMETRICS); if
         * (o.equals(RenderingHints.VALUE_FRACTIONALMETRICS_DEFAULT)) { } else
         * if (o.equals(RenderingHints.VALUE_FRACTIONALMETRICS_OFF)) { } else if
         * (o.equals(RenderingHints.VALUE_FRACTIONALMETRICS_ON)) { }
         * 
         * o = mRh.get(RenderingHints.KEY_INTERPOLATION); if
         * (o.equals(RenderingHints.VALUE_INTERPOLATION_BICUBIC)) { } else if
         * (o.equals(RenderingHints.VALUE_INTERPOLATION_BILINEAR)) { } else if
         * (o .equals(RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)) { }
         * 
         * o = mRh.get(RenderingHints.KEY_RENDERING); if
         * (o.equals(RenderingHints.VALUE_RENDER_DEFAULT)) { } else if
         * (o.equals(RenderingHints.VALUE_RENDER_QUALITY)) { } else if
         * (o.equals(RenderingHints.VALUE_RENDER_SPEED)) { }
         * 
         * o = mRh.get(RenderingHints.KEY_STROKE_CONTROL); if
         * (o.equals(RenderingHints.VALUE_STROKE_DEFAULT)) { } else if
         * (o.equals(RenderingHints.VALUE_STROKE_NORMALIZE)) { } else if
         * (o.equals(RenderingHints.VALUE_STROKE_PURE)) { }
         */

        o = mRh.get(RenderingHints.KEY_ANTIALIASING);
        if (o != null) {
            if (o.equals(RenderingHints.VALUE_ANTIALIAS_DEFAULT)) {
                mP.setAntiAlias(false);
            } else if (o.equals(RenderingHints.VALUE_ANTIALIAS_OFF)) {
                mP.setAntiAlias(false);
            } else if (o.equals(RenderingHints.VALUE_ANTIALIAS_ON)) {
                mP.setAntiAlias(true);
            }
        }

        o = mRh.get(RenderingHints.KEY_TEXT_ANTIALIASING);
        if (o != null) {
            if (o.equals(RenderingHints.VALUE_TEXT_ANTIALIAS_DEFAULT)) {
                mP.setAntiAlias(false);
            } else if (o.equals(RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)) {
                mP.setAntiAlias(false);
            } else if (o.equals(RenderingHints.VALUE_TEXT_ANTIALIAS_ON)) {
                mP.setAntiAlias(true);
            }
        }
    }

    @Override
    public void setStroke(Stroke s) {
        if (mP == null) {
            mP = new Paint();
        }
        BasicStroke bs = (BasicStroke) s;
        mP.setStyle(Paint.Style.STROKE);
        mP.setStrokeWidth(bs.getLineWidth());

        int cap = bs.getEndCap();
        if (cap == 0) {
            mP.setStrokeCap(Paint.Cap.BUTT);
        } else if (cap == 1) {
            mP.setStrokeCap(Paint.Cap.ROUND);
        } else if (cap == 2) {
            mP.setStrokeCap(Paint.Cap.SQUARE);
        }

        int join = bs.getLineJoin();
        if (join == 0) {
            mP.setStrokeJoin(Paint.Join.MITER);
        } else if (join == 1) {
            mP.setStrokeJoin(Paint.Join.ROUND);
        } else if (join == 2) {
            mP.setStrokeJoin(Paint.Join.BEVEL);
        }
    }

    public static float[] createMatrix(AffineTransform Tx) {
        double[] at = new double[9];
        Tx.getMatrix(at);
        float[] f = new float[at.length];
        f[0] = (float) at[0];
        f[1] = (float) at[2];
        f[2] = (float) at[4];
        f[3] = (float) at[1];
        f[4] = (float) at[3];
        f[5] = (float) at[5];
        f[6] = 0;
        f[7] = 0;
        f[8] = 1;
        return f;
    }

    private float[] createAWTMatrix(float[] matrix) {
        float[] at = new float[9];
        at[0] = matrix[0];
        at[1] = matrix[3];
        at[2] = matrix[1];
        at[3] = matrix[4];
        at[4] = matrix[2];
        at[5] = matrix[5];
        at[6] = 0;
        at[7] = 0;
        at[8] = 1;
        return at;
    }

    public static Matrix createMatrixObj(AffineTransform Tx) {
        Matrix m = new Matrix();
        m.reset();
        m.setValues(createMatrix(Tx));
        return m;
    }

    @Override
    public void setTransform(AffineTransform Tx) {
        mM.reset();
        /*
         * if(Tx.isIdentity()) { mM = new Matrix(); }
         */
        mM.setValues(createMatrix(Tx));
        Matrix m = new Matrix();
        m.setValues(getInverseMatrix());
        mC.concat(m);
        mC.concat(mM);
    }

    @Override
    public void shear(double shx, double shy) {
        mM.setSkew((float) shx, (float) shy);
        mC.concat(mM);
    }

    @Override
    public void transform(AffineTransform Tx) {
        Matrix m = new Matrix();
        m.setValues(createMatrix(Tx));
        mC.concat(m);
    }

    @Override
    public void translate(double tx, double ty) {
        mM.setTranslate((float) tx, (float) ty);
        mC.concat(mM);
    }

    @Override
    public void translate(int x, int y) {
        mM.setTranslate((float) x, (float) y);
        mC.concat(mM);
    }

    @Override
    public void clearRect(int x, int y, int width, int height) {
        mC.clipRect(x, y, x + width, y + height);
        if (mBc != null) {
            mC.drawARGB(mBc.getAlpha(), mBc.getBlue(), mBc.getGreen(), mBc
                    .getRed());
        } else {
            mC.drawARGB(0xff, 0xff, 0xff, 0xff);
        }
    }

    @Override
    public void clipRect(int x, int y, int width, int height) {
        int cl[] = {-1, x, y, -2, x, y + width, -2, x + height, y + width, -2, x + height, y};
        Shape shp = createShape(cl);
        mCurrClip.intersect(new Area(shp));
        mC.clipRect(new Rect(x, y, x + width, y + height), Region.Op.INTERSECT);
    }

    @Override
    public void copyArea(int sx, int sy, int width, int height, int dx, int dy) {
        copyArea(mC, sx, sy, width + dx, height + dy, dx, dy);
    }

    @Override
    public Graphics create() {
        return this;
    }

    @Override
    public void dispose() {
            mC = null;
            mP = null;
    }

    @Override
    public void drawArc(int x, int y, int width, int height, int sa, int ea) {
            if (mP == null) {
                mP = new Paint();
            }
            mP.setStrokeWidth(0);
            mC.drawArc(new RectF(x, y, x + width, y + height), 360 - (ea + sa),
                       ea, true, mP);
    }

    
    // ???AWT: only used for debuging, delete in final version
    public void drawBitmap(Bitmap bm, float x, float y, Paint p) {
        mC.drawBitmap(bm, x, y, null);
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
        if (mP == null) {
            mP = new Paint();
        }
            mC.drawLine(x1, y1, x2, y2, mP);
    }

    @Override
    public void drawOval(int x, int y, int width, int height) {
            if (mP == null) {
                mP = new Paint();
            }
            mP.setStyle(Paint.Style.STROKE);
            mC.drawOval(new RectF(x, y, x + width, y + height), mP);
    }

    @Override
    public void drawPolygon(int[] xpoints, int[] ypoints, int npoints) {
            if (mP == null) {
                mP = new Paint();
            }
            mC.drawLine(xpoints[npoints - 1], ypoints[npoints - 1], xpoints[0],
                    ypoints[0], mP);
            for (int i = 0; i < npoints - 1; i++) {
                mC.drawLine(xpoints[i], ypoints[i], xpoints[i + 1],
                        ypoints[i + 1], mP);
            }
    }

    @Override
    public void drawPolyline(int[] xpoints, int[] ypoints, int npoints) {
        for (int i = 0; i < npoints - 1; i++) {
            drawLine(xpoints[i], ypoints[i], xpoints[i + 1], ypoints[i + 1]);
        }

    }

    @Override
    public void drawRoundRect(int x, int y, int width, int height,
            int arcWidth, int arcHeight) {
            if (mP == null) {
                mP = new Paint();
            }
            mC.drawRoundRect(new RectF(x, y, width, height), arcWidth,
                    arcHeight, mP);
    }

    @Override
    public void fillArc(int x, int y, int width, int height, int sa, int ea) {
            if (mP == null) {
                mP = new Paint();
            }
            
            Paint.Style tmp = mP.getStyle();
            mP.setStyle(Paint.Style.FILL_AND_STROKE);
            mC.drawArc(new RectF(x, y, x + width, y + height), 360 - (sa + ea),
                    ea, true, mP);
            
            mP.setStyle(tmp);
    }

    @Override
    public void fillOval(int x, int y, int width, int height) {
            if (mP == null) {
                mP = new Paint();
            }
            Paint.Style tmp = mP.getStyle();
            mP.setStyle(Paint.Style.FILL);
            mC.drawOval(new RectF(x, y, x + width, y + height), mP);
            mP.setStyle(tmp);
    }

    @Override
    public void fillPolygon(int[] xpoints, int[] ypoints, int npoints) {
            if (mP == null) {
                mP = new Paint();
            }
            Paint.Style tmp = mP.getStyle();
            mC.save(Canvas.CLIP_SAVE_FLAG);

            mP.setStyle(Paint.Style.FILL);

            GeneralPath filledPolygon = new GeneralPath(
                    GeneralPath.WIND_EVEN_ODD, npoints);
            filledPolygon.moveTo(xpoints[0], ypoints[0]);
            for (int index = 1; index < xpoints.length; index++) {
                filledPolygon.lineTo(xpoints[index], ypoints[index]);
            }
            filledPolygon.closePath();
            Path path = getPath(filledPolygon);
            mC.clipPath(path);
            mC.drawPath(path, mP);

            mP.setStyle(tmp);
            mC.restore();
    }

    @Override
    public void fillRect(int x, int y, int width, int height) {
            if (mP == null) {
                mP = new Paint();
            }
            Paint.Style tmp = mP.getStyle();
            mP.setStyle(Paint.Style.FILL);
            mC.drawRect(new Rect(x, y, x + width, y + height), mP);
            mP.setStyle(tmp);
    }

    @Override
    public void drawRect(int x, int y, int width, int height) {
        int[] xpoints = { x, x, x + width, x + width };
        int[] ypoints = { y, y + height, y + height, y };
        drawPolygon(xpoints, ypoints, 4);
    }

    @Override
    public void fillRoundRect(int x, int y, int width, int height,
            int arcWidth, int arcHeight) {
            if (mP == null) {
                mP = new Paint();
            }
            mP.setStyle(Paint.Style.FILL);
            mC.drawRoundRect(new RectF(x, y, x + width, y + height), arcWidth,
                    arcHeight, mP);
    }

    @Override
    public Shape getClip() {
        return mCurrClip;
    }

    @Override
    public Rectangle getClipBounds() {
            Rect r = mC.getClipBounds();
            return new Rectangle(r.left, r.top, r.width(), r.height());
    }

    @Override
    public Color getColor() {
        if (mP != null) {
            return new Color(mP.getColor());
        }
        return null;
    }

    @Override
    public Font getFont() {
        return mFnt;
    }

    @Override
    public FontMetrics getFontMetrics(Font font) {
        mFm = new FontMetricsImpl(font);
        return mFm;
    }

    @Override
    public void setClip(int x, int y, int width, int height) {
        int cl[] = {-1, x, y, -2, x, y + width, -2, x + height, y + width, -2, x + height, y};
        mCurrClip = new Area(createShape(cl));
        mC.clipRect(x, y, x + width, y + height, Region.Op.REPLACE);

    }

    @Override
    public void setClip(Shape clip) {
        mCurrClip = new Area(clip);
        mC.clipPath(getPath(clip), Region.Op.REPLACE);
    }

    @Override
    public void setColor(Color c) {
        if (mP == null) {
            mP = new Paint();
        }
        mP.setColor(c.getRGB());
    }

    /**
     * Font mapping:
     * 
     * Family:
     * 
     * Android         AWT
     * -------------------------------------
     * serif           Serif / TimesRoman
     * sans-serif      SansSerif / Helvetica
     * monospace       Monospaced / Courier
     * 
     * Style:
     * 
     * Android            AWT
     * -------------------------------------
     * normal          Plain
     * bold            bold
     * italic          italic
     * 
     */
    @Override
    public void setFont(Font font) {
        if (font == null) {
            return;
        }
        if (mP == null) {
            mP = new Paint();
        }

        mFnt = font;
        Typeface tf = null;
        int sty = font.getStyle();
        String nam = font.getName();
        String aF = "";
        if (nam != null) {
            if (nam.equalsIgnoreCase("Serif")
                    || nam.equalsIgnoreCase("TimesRoman")) {
                aF = "serif";
            } else if (nam.equalsIgnoreCase("SansSerif")
                    || nam.equalsIgnoreCase("Helvetica")) {
                aF = "sans-serif";
            } else if (nam.equalsIgnoreCase("Monospaced")
                    || nam.equalsIgnoreCase("Courier")) {
                aF = "monospace";
            }
        }

        switch (sty) {
        case Font.PLAIN:
            tf = Typeface.create(aF, Typeface.NORMAL);
            break;
        case Font.BOLD:
            tf = Typeface.create(aF, Typeface.BOLD);
            break;
        case Font.ITALIC:
            tf = Typeface.create(aF, Typeface.ITALIC);
            break;
        case Font.BOLD | Font.ITALIC:
            tf = Typeface.create(aF, Typeface.BOLD_ITALIC);
            break;
        default:
            tf = Typeface.DEFAULT;
        }

        mP.setTextSize(font.getSize());
        mP.setTypeface(tf);
    }

    @Override
    public void drawBytes(byte[] data, int offset, int length, int x, int y) {
        drawString(new String(data, offset, length), x, y);
    }
    
    @Override
    public void drawPolygon(Polygon p) {
        drawPolygon(p.xpoints, p.ypoints, p.npoints);
    }

    @Override
    public void fillPolygon(Polygon p) {
        fillPolygon(p.xpoints, p.ypoints, p.npoints);
    }
    
    @Override
    public Rectangle getClipBounds(Rectangle r) {
        Shape clip = getClip();
        if (clip != null) {
            Rectangle b = clip.getBounds();
            r.x = b.x;
            r.y = b.y;
            r.width = b.width;
            r.height = b.height;
        }
        return r;
    }
    
    @Override
    public boolean hitClip(int x, int y, int width, int height) {
        return getClipBounds().intersects(new Rectangle(x, y, width, height));
    }
    
    @Override
    public void drawChars(char[] data, int offset, int length, int x, int y) {
        mC.drawText(data, offset, length, x, y, mP);
    }
    
    @Override
    public void setPaintMode() {
        if (mP == null) {
            mP = new Paint();
        }
        mP.setXfermode(null);
    }

    @Override
    public void setXORMode(Color color) {
        if (mP == null) {
            mP = new Paint();
        }
        mP.setXfermode(new PixelXorXfermode(color.getRGB()));
    }
    
    @Override
    public void fill3DRect(int x, int y, int width, int height, boolean raised) {
        Color color = getColor();
        Color colorUp, colorDown;
        if (raised) {
            colorUp = color.brighter();
            colorDown = color.darker();
            setColor(color);
        } else {
            colorUp = color.darker();
            colorDown = color.brighter();
            setColor(colorUp);
        }

        width--;
        height--;
        fillRect(x+1, y+1, width-1, height-1);

        setColor(colorUp);
        fillRect(x, y, width, 1);
        fillRect(x, y+1, 1, height);

        setColor(colorDown);
        fillRect(x+width, y, 1, height);
        fillRect(x+1, y+height, width, 1);
    }
    
    @Override
    public void draw3DRect(int x, int y, int width, int height, boolean raised) {
        Color color = getColor();
        Color colorUp, colorDown;
        if (raised) {
            colorUp = color.brighter();
            colorDown = color.darker();
        } else {
            colorUp = color.darker();
            colorDown = color.brighter();
        }

        setColor(colorUp);
        fillRect(x, y, width, 1);
        fillRect(x, y+1, 1, height);

        setColor(colorDown);
        fillRect(x+width, y, 1, height);
        fillRect(x+1, y+height, width, 1);
    }

    public void copyArea(Canvas canvas, int sx, int sy, int width, int height, int dx, int dy) {
        sx += getTransform().getTranslateX();
        sy += getTransform().getTranslateY();

        NativeUtils.nativeScrollRect(canvas,
                new Rect(sx, sy, sx + width, sy + height),
                dx, dy);
    }
}
