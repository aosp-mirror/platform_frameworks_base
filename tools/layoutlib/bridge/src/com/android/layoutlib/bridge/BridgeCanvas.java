/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.layoutlib.bridge;

import com.android.layoutlib.api.ILayoutLog;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DrawFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Picture;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.Shader;
import android.graphics.Xfermode;
import android.graphics.Paint.Align;
import android.graphics.Paint.Style;
import android.graphics.Region.Op;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Stack;

import javax.microedition.khronos.opengles.GL;

/**
 * Re-implementation of the Canvas, 100% in java on top of a BufferedImage.
 */
public class BridgeCanvas extends Canvas {
    
    private BufferedImage mBufferedImage;
    private final Stack<Graphics2D> mGraphicsStack = new Stack<Graphics2D>();
    private final ILayoutLog mLogger;

    public BridgeCanvas(int width, int height, ILayoutLog logger) {
        mLogger = logger;
        mBufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        mGraphicsStack.push(mBufferedImage.createGraphics());
    }
    
    public BridgeCanvas(int width, int height) {
        this(width, height, null /* logger*/);
    }
    
    public BufferedImage getImage() {
        return mBufferedImage;
    }
    
    Graphics2D getGraphics2d() {
        return mGraphicsStack.peek();
    }
    
    void dispose() {
        while (mGraphicsStack.size() > 0) {
            mGraphicsStack.pop().dispose();
        }
    }
    
    /**
     * Creates a new {@link Graphics2D} based on the {@link Paint} parameters.
     * <p/>The object must be disposed ({@link Graphics2D#dispose()}) after being used.
     */
    private Graphics2D getNewGraphics(Paint paint, Graphics2D g) {
        // make new one
        g = (Graphics2D)g.create();
        g.setColor(new Color(paint.getColor()));
        int alpha = paint.getAlpha();
        float falpha = alpha / 255.f;
        
        Xfermode xfermode = paint.getXfermode();
        if (xfermode instanceof PorterDuffXfermode) {
            PorterDuff.Mode mode = ((PorterDuffXfermode)xfermode).getMode();
            
            setModeInGraphics(mode, g, falpha);
        } else {
            if (mLogger != null && xfermode != null) {
                mLogger.warning(String.format(
                        "Xfermode '%1$s' is not supported in the Layout Editor.",
                        xfermode.getClass().getCanonicalName()));
            }
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, falpha));
        }
        
        Shader shader = paint.getShader();
        if (shader instanceof LinearGradient) {
            g.setPaint(((LinearGradient)shader).getPaint());
        } else {
            if (mLogger != null && shader != null) {
                mLogger.warning(String.format(
                        "Shader '%1$s' is not supported in the Layout Editor.",
                        shader.getClass().getCanonicalName()));
            }
        }
        
        return g;
    }
    
    private void setModeInGraphics(PorterDuff.Mode mode, Graphics2D g, float falpha) {
        switch (mode) {
            case CLEAR:
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.CLEAR, falpha));
                break;
            case DARKEN:
                break;
            case DST:
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.DST, falpha));
                break;
            case DST_ATOP:
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.DST_ATOP, falpha));
                break;
            case DST_IN:
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.DST_IN, falpha));
                break;
            case DST_OUT:
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.DST_OUT, falpha));
                break;
            case DST_OVER:
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.DST_OVER, falpha));
                break;
            case LIGHTEN:
                break;
            case MULTIPLY:
                break;
            case SCREEN:
                break;
            case SRC:
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC, falpha));
                break;
            case SRC_ATOP:
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_ATOP, falpha));
                break;
            case SRC_IN:
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_IN, falpha));
                break;
            case SRC_OUT:
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OUT, falpha));
                break;
            case SRC_OVER:
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, falpha));
                break;
            case XOR:
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.XOR, falpha));
                break;
        }
    }
    
    // --------------------
    
    @Override
    public void finalize() throws Throwable {
        // pass
    }
    
    /* (non-Javadoc)
     * @see android.graphics.Canvas#translate(float, float)
     */
    @Override
    public void translate(float dx, float dy) {
        getGraphics2d().translate(dx, dy);
    }
    
    /* (non-Javadoc)
     * @see android.graphics.Canvas#save()
     */
    @Override
    public int save() {
        Graphics2D g = (Graphics2D)getGraphics2d().create();
        mGraphicsStack.push(g);
        
        return mGraphicsStack.size() - 1;
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#save(int)
     */
    @Override
    public int save(int saveFlags) {
        // For now we ignore saveFlags
        return save();
    }
    
    /* (non-Javadoc)
     * @see android.graphics.Canvas#restore()
     */
    @Override
    public void restore() {
        mGraphicsStack.pop();
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#restoreToCount(int)
     */
    @Override
    public void restoreToCount(int saveCount) {
        while (mGraphicsStack.size() > saveCount) {
            mGraphicsStack.pop();
        }
    }
    
    /* (non-Javadoc)
     * @see android.graphics.Canvas#getSaveCount()
     */
    @Override
    public int getSaveCount() {
        return mGraphicsStack.size() - 1;
    }
    
    
    /* (non-Javadoc)
     * @see android.graphics.Canvas#clipRect(float, float, float, float, android.graphics.Region.Op)
     */
    @Override
    public boolean clipRect(float left, float top, float right, float bottom, Op op) {
        return clipRect(left, top, right, bottom);
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#clipRect(float, float, float, float)
     */
    @Override
    public boolean clipRect(float left, float top, float right, float bottom) {
        getGraphics2d().clipRect((int)left, (int)top, (int)(right-left), (int)(bottom-top));
        return true;
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#clipRect(int, int, int, int)
     */
    @Override
    public boolean clipRect(int left, int top, int right, int bottom) {
        getGraphics2d().clipRect(left, top, right-left, bottom-top);
        return true;
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#clipRect(android.graphics.Rect, android.graphics.Region.Op)
     */
    @Override
    public boolean clipRect(Rect rect, Op op) {
        return clipRect(rect.left, rect.top, rect.right, rect.bottom);
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#clipRect(android.graphics.Rect)
     */
    @Override
    public boolean clipRect(Rect rect) {
        return clipRect(rect.left, rect.top, rect.right, rect.bottom);
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#clipRect(android.graphics.RectF, android.graphics.Region.Op)
     */
    @Override
    public boolean clipRect(RectF rect, Op op) {
        return clipRect(rect.left, rect.top, rect.right, rect.bottom);
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#clipRect(android.graphics.RectF)
     */
    @Override
    public boolean clipRect(RectF rect) {
        return clipRect(rect.left, rect.top, rect.right, rect.bottom);
    }
    
    @Override
    public boolean quickReject(RectF rect, EdgeType type) {
        return false;
    }

    @Override
    public boolean quickReject(Path path, EdgeType type) {
        return false;
    }

    @Override
    public boolean quickReject(float left, float top, float right, float bottom,
                               EdgeType type) {
        return false;
    }

    /**
     * Retrieve the clip bounds, returning true if they are non-empty.
     *
     * @param bounds Return the clip bounds here. If it is null, ignore it but
     *               still return true if the current clip is non-empty.
     * @return true if the current clip is non-empty.
     */
    @Override
    public boolean getClipBounds(Rect bounds) {
        Rectangle rect = getGraphics2d().getClipBounds();
        if (rect != null) {
            bounds.left = rect.x;
            bounds.top = rect.y;
            bounds.right = rect.x + rect.width;
            bounds.bottom = rect.y + rect.height;
            return true;
        }
        return false;
    }
    
    /* (non-Javadoc)
     * @see android.graphics.Canvas#drawColor(int, android.graphics.PorterDuff.Mode)
     */
    @Override
    public void drawColor(int color, PorterDuff.Mode mode) {
        Graphics2D g = getGraphics2d();
        
        // save old color
        Color c = g.getColor();
        
        Composite composite = g.getComposite();
        
        // get the alpha from the color
        int alpha = color >>> 24;
        float falpha = alpha / 255.f;
        
        setModeInGraphics(mode, g, falpha);
        
        g.setColor(new Color(color));
        
        getGraphics2d().fillRect(0, 0, getWidth(), getHeight());
        
        g.setComposite(composite);
        
        // restore color
        g.setColor(c);
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#drawColor(int)
     */
    @Override
    public void drawColor(int color) {
        drawColor(color, PorterDuff.Mode.SRC_OVER);
    }
    
    /* (non-Javadoc)
     * @see android.graphics.Canvas#drawARGB(int, int, int, int)
     */
    @Override
    public void drawARGB(int a, int r, int g, int b) {
        drawColor(a << 24 | r << 16 | g << 8 | b, PorterDuff.Mode.SRC_OVER);
    }
    
    /* (non-Javadoc)
     * @see android.graphics.Canvas#drawRGB(int, int, int)
     */
    @Override
    public void drawRGB(int r, int g, int b) {
        drawColor(0xFF << 24 | r << 16 | g << 8 | b, PorterDuff.Mode.SRC_OVER);
    }

    
    /* (non-Javadoc)
     * @see android.graphics.Canvas#getWidth()
     */
    @Override
    public int getWidth() {
        return mBufferedImage.getWidth();
    }
    
    /* (non-Javadoc)
     * @see android.graphics.Canvas#getHeight()
     */
    @Override
    public int getHeight() {
        return mBufferedImage.getHeight();
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#drawPaint(android.graphics.Paint)
     */
    @Override
    public void drawPaint(Paint paint) {
        drawColor(paint.getColor());
    }
    
    /* (non-Javadoc)
     * @see android.graphics.Canvas#drawBitmap(android.graphics.Bitmap, float, float, android.graphics.Paint)
     */
    @Override
    public void drawBitmap(Bitmap bitmap, float left, float top, Paint paint) {
        drawBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(),
                (int)left, (int)top,
                (int)left+bitmap.getWidth(), (int)top+bitmap.getHeight(), paint);
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#drawBitmap(android.graphics.Bitmap, android.graphics.Matrix, android.graphics.Paint)
     */
    @Override
    public void drawBitmap(Bitmap bitmap, Matrix matrix, Paint paint) {
        throw new UnsupportedOperationException();
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#drawBitmap(android.graphics.Bitmap, android.graphics.Rect, android.graphics.Rect, android.graphics.Paint)
     */
    @Override
    public void drawBitmap(Bitmap bitmap, Rect src, Rect dst, Paint paint) {
        if (src == null) {
            drawBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(),
                    dst.left, dst.top, dst.right, dst.bottom, paint);
        } else {
            drawBitmap(bitmap, src.left, src.top, src.width(), src.height(),
                    dst.left, dst.top, dst.right, dst.bottom, paint);
        }
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#drawBitmap(android.graphics.Bitmap, android.graphics.Rect, android.graphics.RectF, android.graphics.Paint)
     */
    @Override
    public void drawBitmap(Bitmap bitmap, Rect src, RectF dst, Paint paint) {
        if (src == null) {
            drawBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(),
                    (int)dst.left, (int)dst.top, (int)dst.right, (int)dst.bottom, paint);
        } else {
            drawBitmap(bitmap, src.left, src.top, src.width(), src.height(),
                    (int)dst.left, (int)dst.top, (int)dst.right, (int)dst.bottom, paint);
        }
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#drawBitmap(int[], int, int, int, int, int, int, boolean, android.graphics.Paint)
     */
    @Override
    public void drawBitmap(int[] colors, int offset, int stride, int x, int y, int width,
            int height, boolean hasAlpha, Paint paint) {
        throw new UnsupportedOperationException();
    }
    
    private void drawBitmap(Bitmap bitmap, int sleft, int stop, int sright, int sbottom, int dleft,
            int dtop, int dright, int dbottom, Paint paint) {
        BufferedImage image = bitmap.getImage();
        
        Graphics2D g = getGraphics2d();
        
        Composite c = null;
        
        if (paint.isFilterBitmap()) {
            g = (Graphics2D)g.create();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        }
        
        if (paint.getAlpha() != 0xFF) {
            c = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
                    paint.getAlpha()/255.f));
        }
        
        g.drawImage(image, dleft, dtop, dright, dbottom,
                sleft, stop, sright, sbottom, null);

        if (paint.isFilterBitmap()) {
            g.dispose();
        }
        
        if (c != null) {
            g.setComposite(c);
        }
    }
    
    /* (non-Javadoc)
     * @see android.graphics.Canvas#rotate(float, float, float)
     */
    @Override
    public void rotate(float degrees, float px, float py) {
        if (degrees != 0) {
            Graphics2D g = getGraphics2d();
            g.translate(px, py);
            g.rotate(Math.toRadians(degrees));
            g.translate(-px, -py);
        }
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#rotate(float)
     */
    @Override
    public void rotate(float degrees) {
        getGraphics2d().rotate(Math.toRadians(degrees));
    }
    
    /* (non-Javadoc)
     * @see android.graphics.Canvas#scale(float, float, float, float)
     */
    @Override
    public void scale(float sx, float sy, float px, float py) {
        Graphics2D g = getGraphics2d();
        g.translate(px, py);
        g.scale(sx, sy);
        g.translate(-px, -py);
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#scale(float, float)
     */
    @Override
    public void scale(float sx, float sy) {
        getGraphics2d().scale(sx, sy);
    }
    
    /* (non-Javadoc)
     * @see android.graphics.Canvas#drawText(char[], int, int, float, float, android.graphics.Paint)
     */
    @Override
    public void drawText(char[] text, int index, int count, float x, float y, Paint paint) {
        Graphics2D g = getGraphics2d();
        
        g = (Graphics2D)g.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        g.setFont(paint.getFont());
        
        // set the color. because this only handles RGB we have to handle the alpha separately
        g.setColor(new Color(paint.getColor()));
        int alpha = paint.getAlpha();
        float falpha = alpha / 255.f;
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, falpha));

        // Paint.TextAlign indicates how the text is positioned relative to X.
        // LEFT is the default and there's nothing to do.
        if (paint.getTextAlign() != Align.LEFT) {
            float m = paint.measureText(text, index, count);
            if (paint.getTextAlign() == Align.CENTER) {
                x -= m / 2;
            } else if (paint.getTextAlign() == Align.RIGHT) {
                x -= m;
            }
        }
        
        g.drawChars(text, index, count, (int)x, (int)y);
        
        g.dispose();
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#drawText(java.lang.CharSequence, int, int, float, float, android.graphics.Paint)
     */
    @Override
    public void drawText(CharSequence text, int start, int end, float x, float y, Paint paint) {
        drawText(text.toString().toCharArray(), start, end - start, x, y, paint);
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#drawText(java.lang.String, float, float, android.graphics.Paint)
     */
    @Override
    public void drawText(String text, float x, float y, Paint paint) {
        drawText(text.toCharArray(), 0, text.length(), x, y, paint);
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#drawText(java.lang.String, int, int, float, float, android.graphics.Paint)
     */
    @Override
    public void drawText(String text, int start, int end, float x, float y, Paint paint) {
        drawText(text.toCharArray(), start, end - start, x, y, paint);
    }
    
    /* (non-Javadoc)
     * @see android.graphics.Canvas#drawRect(android.graphics.RectF, android.graphics.Paint)
     */
    @Override
    public void drawRect(RectF rect, Paint paint) {
        doDrawRect((int)rect.left, (int)rect.top, (int)rect.width(), (int)rect.height(), paint);
    }
    
    /* (non-Javadoc)
     * @see android.graphics.Canvas#drawRect(float, float, float, float, android.graphics.Paint)
     */
    @Override
    public void drawRect(float left, float top, float right, float bottom, Paint paint) {
        doDrawRect((int)left, (int)top, (int)(right-left), (int)(bottom-top), paint);
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#drawRect(android.graphics.Rect, android.graphics.Paint)
     */
    @Override
    public void drawRect(Rect r, Paint paint) {
        doDrawRect(r.left, r.top, r.width(), r.height(), paint);
    }

    private final void doDrawRect(int left, int top, int width, int height, Paint paint) {
        // get current graphisc
        Graphics2D g = getGraphics2d();
        
        g = getNewGraphics(paint, g);

        Style style = paint.getStyle();
        
        // draw
        if (style == Style.FILL || style == Style.FILL_AND_STROKE) {
            g.fillRect(left, top, width, height);
        }

        if (style == Style.STROKE || style == Style.FILL_AND_STROKE) {
            g.drawRect(left, top, width, height);
        }

        // dispose Graphics2D object
        g.dispose();
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#drawRoundRect(android.graphics.RectF, float, float, android.graphics.Paint)
     */
    @Override
    public void drawRoundRect(RectF rect, float rx, float ry, Paint paint) {
        // get current graphisc
        Graphics2D g = getGraphics2d();
        
        g = getNewGraphics(paint, g);

        Style style = paint.getStyle();
        
        // draw
        
        int arcWidth = (int)(rx * 2);
        int arcHeight = (int)(ry * 2);
        
        if (style == Style.FILL || style == Style.FILL_AND_STROKE) {
            g.fillRoundRect((int)rect.left, (int)rect.top, (int)rect.width(), (int)rect.height(),
                    arcWidth, arcHeight);
        }

        if (style == Style.STROKE || style == Style.FILL_AND_STROKE) {
            g.drawRoundRect((int)rect.left, (int)rect.top, (int)rect.width(), (int)rect.height(),
                    arcWidth, arcHeight);
        }

        // dispose Graphics2D object
        g.dispose();
    }


    /* (non-Javadoc)
     * @see android.graphics.Canvas#drawLine(float, float, float, float, android.graphics.Paint)
     */
    @Override
    public void drawLine(float startX, float startY, float stopX, float stopY, Paint paint) {
        // get current graphisc
        Graphics2D g = getGraphics2d();
        
        g = getNewGraphics(paint, g);

        g.drawLine((int)startX, (int)startY, (int)stopX, (int)stopY);

        // dispose Graphics2D object
        g.dispose();
    }
    
    /* (non-Javadoc)
     * @see android.graphics.Canvas#drawLines(float[], int, int, android.graphics.Paint)
     */
    @Override
    public void drawLines(float[] pts, int offset, int count, Paint paint) {
        // get current graphisc
        Graphics2D g = getGraphics2d();
        
        g = getNewGraphics(paint, g);

        for (int i = 0 ; i < count ; i += 4) {
            g.drawLine((int)pts[i + offset], (int)pts[i + offset + 1],
                    (int)pts[i + offset + 2], (int)pts[i + offset + 3]);
        }

        // dispose Graphics2D object
        g.dispose();
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#drawLines(float[], android.graphics.Paint)
     */
    @Override
    public void drawLines(float[] pts, Paint paint) {
        drawLines(pts, 0, pts.length, paint);
    }
    
    /* (non-Javadoc)
     * @see android.graphics.Canvas#drawCircle(float, float, float, android.graphics.Paint)
     */
    @Override
    public void drawCircle(float cx, float cy, float radius, Paint paint) {
        // get current graphisc
        Graphics2D g = getGraphics2d();
        
        g = getNewGraphics(paint, g);

        Style style = paint.getStyle();
        
        int size = (int)(radius * 2);

        // draw
        if (style == Style.FILL || style == Style.FILL_AND_STROKE) {
            g.fillOval((int)(cx - radius), (int)(cy - radius), size, size);
        }

        if (style == Style.STROKE || style == Style.FILL_AND_STROKE) {
            g.drawOval((int)(cx - radius), (int)(cy - radius), size, size);
        }

        // dispose Graphics2D object
        g.dispose();
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#drawOval(android.graphics.RectF, android.graphics.Paint)
     */
    @Override
    public void drawOval(RectF oval, Paint paint) {
        // get current graphics
        Graphics2D g = getGraphics2d();
        
        g = getNewGraphics(paint, g);

        Style style = paint.getStyle();
        
        // draw
        if (style == Style.FILL || style == Style.FILL_AND_STROKE) {
            g.fillOval((int)oval.left, (int)oval.top, (int)oval.width(), (int)oval.height());
        }

        if (style == Style.STROKE || style == Style.FILL_AND_STROKE) {
            g.drawOval((int)oval.left, (int)oval.top, (int)oval.width(), (int)oval.height());
        }

        // dispose Graphics2D object
        g.dispose();
    }
    
    /* (non-Javadoc)
     * @see android.graphics.Canvas#drawPath(android.graphics.Path, android.graphics.Paint)
     */
    @Override
    public void drawPath(Path path, Paint paint) {
        // get current graphics
        Graphics2D g = getGraphics2d();
        
        g = getNewGraphics(paint, g);

        Style style = paint.getStyle();
        
        // draw
        if (style == Style.FILL || style == Style.FILL_AND_STROKE) {
            g.fill(path.getAwtShape());
        }

        if (style == Style.STROKE || style == Style.FILL_AND_STROKE) {
            g.draw(path.getAwtShape());
        }

        // dispose Graphics2D object
        g.dispose();
    }
    
    /* (non-Javadoc)
     * @see android.graphics.Canvas#setMatrix(android.graphics.Matrix)
     */
    @Override
    public void setMatrix(Matrix matrix) {
        // since SetMatrix *replaces* all the other transformation, we have to restore/save
        restore();
        save();

        // get the new current graphics
        Graphics2D g = getGraphics2d();
        
        // and apply the matrix
        g.setTransform(matrix.getTransform());
        
        if (mLogger != null && matrix.hasPerspective()) {
            mLogger.warning("android.graphics.Canvas#setMatrix(android.graphics.Matrix) only supports affine transformations in the Layout Editor.");
        }
    }

    // --------------------

    /* (non-Javadoc)
     * @see android.graphics.Canvas#clipPath(android.graphics.Path, android.graphics.Region.Op)
     */
    @Override
    public boolean clipPath(Path path, Op op) {
        // TODO Auto-generated method stub
        return super.clipPath(path, op);
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#clipPath(android.graphics.Path)
     */
    @Override
    public boolean clipPath(Path path) {
        // TODO Auto-generated method stub
        return super.clipPath(path);
    }


    /* (non-Javadoc)
     * @see android.graphics.Canvas#clipRegion(android.graphics.Region, android.graphics.Region.Op)
     */
    @Override
    public boolean clipRegion(Region region, Op op) {
        // TODO Auto-generated method stub
        return super.clipRegion(region, op);
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#clipRegion(android.graphics.Region)
     */
    @Override
    public boolean clipRegion(Region region) {
        // TODO Auto-generated method stub
        return super.clipRegion(region);
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#concat(android.graphics.Matrix)
     */
    @Override
    public void concat(Matrix matrix) {
        // TODO Auto-generated method stub
        super.concat(matrix);
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#drawArc(android.graphics.RectF, float, float, boolean, android.graphics.Paint)
     */
    @Override
    public void drawArc(RectF oval, float startAngle, float sweepAngle, boolean useCenter,
            Paint paint) {
        // TODO Auto-generated method stub
        super.drawArc(oval, startAngle, sweepAngle, useCenter, paint);
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#drawBitmapMesh(android.graphics.Bitmap, int, int, float[], int, int[], int, android.graphics.Paint)
     */
    @Override
    public void drawBitmapMesh(Bitmap bitmap, int meshWidth, int meshHeight, float[] verts,
            int vertOffset, int[] colors, int colorOffset, Paint paint) {
        // TODO Auto-generated method stub
        super.drawBitmapMesh(bitmap, meshWidth, meshHeight, verts, vertOffset, colors, colorOffset, paint);
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#drawPicture(android.graphics.Picture, android.graphics.Rect)
     */
    @Override
    public void drawPicture(Picture picture, Rect dst) {
        // TODO Auto-generated method stub
        super.drawPicture(picture, dst);
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#drawPicture(android.graphics.Picture, android.graphics.RectF)
     */
    @Override
    public void drawPicture(Picture picture, RectF dst) {
        // TODO Auto-generated method stub
        super.drawPicture(picture, dst);
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#drawPicture(android.graphics.Picture)
     */
    @Override
    public void drawPicture(Picture picture) {
        // TODO Auto-generated method stub
        super.drawPicture(picture);
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#drawPoint(float, float, android.graphics.Paint)
     */
    @Override
    public void drawPoint(float x, float y, Paint paint) {
        // TODO Auto-generated method stub
        super.drawPoint(x, y, paint);
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#drawPoints(float[], int, int, android.graphics.Paint)
     */
    @Override
    public void drawPoints(float[] pts, int offset, int count, Paint paint) {
        // TODO Auto-generated method stub
        super.drawPoints(pts, offset, count, paint);
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#drawPoints(float[], android.graphics.Paint)
     */
    @Override
    public void drawPoints(float[] pts, Paint paint) {
        // TODO Auto-generated method stub
        super.drawPoints(pts, paint);
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#drawPosText(char[], int, int, float[], android.graphics.Paint)
     */
    @Override
    public void drawPosText(char[] text, int index, int count, float[] pos, Paint paint) {
        // TODO Auto-generated method stub
        super.drawPosText(text, index, count, pos, paint);
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#drawPosText(java.lang.String, float[], android.graphics.Paint)
     */
    @Override
    public void drawPosText(String text, float[] pos, Paint paint) {
        // TODO Auto-generated method stub
        super.drawPosText(text, pos, paint);
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#drawTextOnPath(char[], int, int, android.graphics.Path, float, float, android.graphics.Paint)
     */
    @Override
    public void drawTextOnPath(char[] text, int index, int count, Path path, float offset,
            float offset2, Paint paint) {
        // TODO Auto-generated method stub
        super.drawTextOnPath(text, index, count, path, offset, offset2, paint);
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#drawTextOnPath(java.lang.String, android.graphics.Path, float, float, android.graphics.Paint)
     */
    @Override
    public void drawTextOnPath(String text, Path path, float offset, float offset2, Paint paint) {
        // TODO Auto-generated method stub
        super.drawTextOnPath(text, path, offset, offset2, paint);
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#drawVertices(android.graphics.Canvas.VertexMode, int, float[], int, float[], int, int[], int, short[], int, int, android.graphics.Paint)
     */
    @Override
    public void drawVertices(VertexMode mode, int vertexCount, float[] verts, int vertOffset,
            float[] texs, int texOffset, int[] colors, int colorOffset, short[] indices,
            int indexOffset, int indexCount, Paint paint) {
        // TODO Auto-generated method stub
        super.drawVertices(mode, vertexCount, verts, vertOffset, texs, texOffset, colors, colorOffset,
                indices, indexOffset, indexCount, paint);
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#getDrawFilter()
     */
    @Override
    public DrawFilter getDrawFilter() {
        // TODO Auto-generated method stub
        return super.getDrawFilter();
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#getGL()
     */
    @Override
    public GL getGL() {
        // TODO Auto-generated method stub
        return super.getGL();
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#getMatrix()
     */
    @Override
    public Matrix getMatrix() {
        // TODO Auto-generated method stub
        return super.getMatrix();
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#getMatrix(android.graphics.Matrix)
     */
    @Override
    public void getMatrix(Matrix ctm) {
        // TODO Auto-generated method stub
        super.getMatrix(ctm);
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#isOpaque()
     */
    @Override
    public boolean isOpaque() {
        // TODO Auto-generated method stub
        return super.isOpaque();
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#saveLayer(float, float, float, float, android.graphics.Paint, int)
     */
    @Override
    public int saveLayer(float left, float top, float right, float bottom, Paint paint,
            int saveFlags) {
        // TODO Auto-generated method stub
        return super.saveLayer(left, top, right, bottom, paint, saveFlags);
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#saveLayer(android.graphics.RectF, android.graphics.Paint, int)
     */
    @Override
    public int saveLayer(RectF bounds, Paint paint, int saveFlags) {
        // TODO Auto-generated method stub
        return super.saveLayer(bounds, paint, saveFlags);
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#saveLayerAlpha(float, float, float, float, int, int)
     */
    @Override
    public int saveLayerAlpha(float left, float top, float right, float bottom, int alpha,
            int saveFlags) {
        // TODO Auto-generated method stub
        return super.saveLayerAlpha(left, top, right, bottom, alpha, saveFlags);
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#saveLayerAlpha(android.graphics.RectF, int, int)
     */
    @Override
    public int saveLayerAlpha(RectF bounds, int alpha, int saveFlags) {
        // TODO Auto-generated method stub
        return super.saveLayerAlpha(bounds, alpha, saveFlags);
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#setBitmap(android.graphics.Bitmap)
     */
    @Override
    public void setBitmap(Bitmap bitmap) {
        // TODO Auto-generated method stub
        super.setBitmap(bitmap);
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#setDrawFilter(android.graphics.DrawFilter)
     */
    @Override
    public void setDrawFilter(DrawFilter filter) {
        // TODO Auto-generated method stub
        super.setDrawFilter(filter);
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#setViewport(int, int)
     */
    @Override
    public void setViewport(int width, int height) {
        // TODO Auto-generated method stub
        super.setViewport(width, height);
    }

    /* (non-Javadoc)
     * @see android.graphics.Canvas#skew(float, float)
     */
    @Override
    public void skew(float sx, float sy) {
        // TODO Auto-generated method stub
        super.skew(sx, sy);
    }



}
