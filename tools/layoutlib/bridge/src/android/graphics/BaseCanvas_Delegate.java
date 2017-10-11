/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.graphics;

import com.android.ide.common.rendering.api.LayoutLog;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.impl.DelegateManager;
import com.android.layoutlib.bridge.impl.GcSnapshot;
import com.android.layoutlib.bridge.impl.PorterDuffUtility;
import com.android.ninepatch.NinePatchChunk;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.annotation.Nullable;
import android.text.TextUtils;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;

public class BaseCanvas_Delegate {
    // ---- delegate manager ----
    protected static DelegateManager<BaseCanvas_Delegate> sManager =
            new DelegateManager<>(BaseCanvas_Delegate.class);

    // ---- delegate helper data ----
    private final static boolean[] sBoolOut = new boolean[1];


    // ---- delegate data ----
    protected Bitmap_Delegate mBitmap;
    protected GcSnapshot mSnapshot;

    // ---- Public Helper methods ----

    protected BaseCanvas_Delegate(Bitmap_Delegate bitmap) {
        mSnapshot = GcSnapshot.createDefaultSnapshot(mBitmap = bitmap);
    }

    protected BaseCanvas_Delegate() {
        mSnapshot = GcSnapshot.createDefaultSnapshot(null /*image*/);
    }

    /**
     * Disposes of the {@link Graphics2D} stack.
     */
    protected void dispose() {
        mSnapshot.dispose();
    }

    /**
     * Returns the current {@link Graphics2D} used to draw.
     */
    public GcSnapshot getSnapshot() {
        return mSnapshot;
    }

    // ---- native methods ----

    @LayoutlibDelegate
    /*package*/ static void nDrawBitmap(long nativeCanvas, Bitmap bitmap, float left, float top,
            long nativePaintOrZero, int canvasDensity, int screenDensity, int bitmapDensity) {
        // get the delegate from the native int.
        Bitmap_Delegate bitmapDelegate = Bitmap_Delegate.getDelegate(bitmap);
        if (bitmapDelegate == null) {
            return;
        }

        BufferedImage image = bitmapDelegate.getImage();
        float right = left + image.getWidth();
        float bottom = top + image.getHeight();

        drawBitmap(nativeCanvas, bitmapDelegate, nativePaintOrZero,
                0, 0, image.getWidth(), image.getHeight(),
                (int)left, (int)top, (int)right, (int)bottom);
    }

    @LayoutlibDelegate
    /*package*/ static void nDrawBitmap(long nativeCanvas, Bitmap bitmap, float srcLeft, float srcTop,
            float srcRight, float srcBottom, float dstLeft, float dstTop, float dstRight,
            float dstBottom, long nativePaintOrZero, int screenDensity, int bitmapDensity) {
        // get the delegate from the native int.
        Bitmap_Delegate bitmapDelegate = Bitmap_Delegate.getDelegate(bitmap);
        if (bitmapDelegate == null) {
            return;
        }

        drawBitmap(nativeCanvas, bitmapDelegate, nativePaintOrZero, (int) srcLeft, (int) srcTop,
                (int) srcRight, (int) srcBottom, (int) dstLeft, (int) dstTop, (int) dstRight,
                (int) dstBottom);
    }

    @LayoutlibDelegate
    /*package*/ static void nDrawBitmap(long nativeCanvas, int[] colors, int offset, int stride,
            final float x, final float y, int width, int height, boolean hasAlpha,
            long nativePaintOrZero) {
        // create a temp BufferedImage containing the content.
        final BufferedImage image = new BufferedImage(width, height,
                hasAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, width, height, colors, offset, stride);

        draw(nativeCanvas, nativePaintOrZero, true /*compositeOnly*/, false /*forceSrcMode*/,
                (graphics, paint) -> {
                    if (paint != null && paint.isFilterBitmap()) {
                        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    }

                    graphics.drawImage(image, (int) x, (int) y, null);
                });
    }

    @LayoutlibDelegate
    /*package*/ static void nDrawColor(long nativeCanvas, final int color, final int mode) {
        // get the delegate from the native int.
        BaseCanvas_Delegate canvasDelegate = sManager.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            return;
        }

        final int w = canvasDelegate.mBitmap.getImage().getWidth();
        final int h = canvasDelegate.mBitmap.getImage().getHeight();
        draw(nativeCanvas, (graphics, paint) -> {
            // reset its transform just in case
            graphics.setTransform(new AffineTransform());

            // set the color
            graphics.setColor(new java.awt.Color(color, true /*alpha*/));

            Composite composite = PorterDuffUtility.getComposite(
                    PorterDuffUtility.getPorterDuffMode(mode), 0xFF);
            if (composite != null) {
                graphics.setComposite(composite);
            }

            graphics.fillRect(0, 0, w, h);
        });
    }

    @LayoutlibDelegate
    /*package*/ static void nDrawPaint(long nativeCanvas, long paint) {
        // FIXME
        Bridge.getLog().fidelityWarning(LayoutLog.TAG_UNSUPPORTED,
                "Canvas.drawPaint is not supported.", null, null /*data*/);
    }

    @LayoutlibDelegate
    /*package*/ static void nDrawPoint(long nativeCanvas, float x, float y,
            long nativePaint) {
        // TODO: need to support the attribute (e.g. stroke width) of paint
        draw(nativeCanvas, nativePaint, false /*compositeOnly*/, false /*forceSrcMode*/,
                (graphics, paintDelegate) -> graphics.fillRect((int)x, (int)y, 1, 1));
    }

    @LayoutlibDelegate
    /*package*/ static void nDrawPoints(long nativeCanvas, float[] pts, int offset, int count,
            long nativePaint) {
        if (offset < 0 || count < 0 || offset + count > pts.length) {
            throw new IllegalArgumentException("Invalid argument set");
        }
        // ignore the last point if the count is odd (It means it is not paired).
        count = (count >> 1) << 1;
        for (int i = offset; i < offset + count; i += 2) {
            nDrawPoint(nativeCanvas, pts[i], pts[i + 1], nativePaint);
        }
    }

    @LayoutlibDelegate
    /*package*/ static void nDrawLine(long nativeCanvas,
            final float startX, final float startY, final float stopX, final float stopY,
            long paint) {
        draw(nativeCanvas, paint, false /*compositeOnly*/, false /*forceSrcMode*/,
                (graphics, paintDelegate) -> graphics.drawLine((int)startX, (int)startY, (int)stopX, (int)stopY));
    }

    @LayoutlibDelegate
    /*package*/ static void nDrawLines(long nativeCanvas,
            final float[] pts, final int offset, final int count,
            long nativePaint) {
        draw(nativeCanvas, nativePaint, false /*compositeOnly*/,
                false /*forceSrcMode*/, (graphics, paintDelegate) -> {
                    for (int i = 0; i < count; i += 4) {
                        graphics.drawLine((int) pts[i + offset], (int) pts[i + offset + 1],
                                (int) pts[i + offset + 2], (int) pts[i + offset + 3]);
                    }
                });
    }

    @LayoutlibDelegate
    /*package*/ static void nDrawRect(long nativeCanvas,
            final float left, final float top, final float right, final float bottom, long paint) {

        draw(nativeCanvas, paint, false /*compositeOnly*/, false /*forceSrcMode*/,
                (graphics, paintDelegate) -> {
                    int style = paintDelegate.getStyle();

                    // draw
                    if (style == Paint.Style.FILL.nativeInt ||
                            style == Paint.Style.FILL_AND_STROKE.nativeInt) {
                        graphics.fillRect((int)left, (int)top,
                                (int)(right-left), (int)(bottom-top));
                    }

                    if (style == Paint.Style.STROKE.nativeInt ||
                            style == Paint.Style.FILL_AND_STROKE.nativeInt) {
                        graphics.drawRect((int)left, (int)top,
                                (int)(right-left), (int)(bottom-top));
                    }
                });
    }

    @LayoutlibDelegate
    /*package*/ static void nDrawOval(long nativeCanvas, final float left,
            final float top, final float right, final float bottom, long paint) {
        if (right > left && bottom > top) {
            draw(nativeCanvas, paint, false /*compositeOnly*/, false /*forceSrcMode*/,
                    (graphics, paintDelegate) -> {
                        int style = paintDelegate.getStyle();

                        // draw
                        if (style == Paint.Style.FILL.nativeInt ||
                                style == Paint.Style.FILL_AND_STROKE.nativeInt) {
                            graphics.fillOval((int)left, (int)top,
                                    (int)(right - left), (int)(bottom - top));
                        }

                        if (style == Paint.Style.STROKE.nativeInt ||
                                style == Paint.Style.FILL_AND_STROKE.nativeInt) {
                            graphics.drawOval((int)left, (int)top,
                                    (int)(right - left), (int)(bottom - top));
                        }
                    });
        }
    }

    @LayoutlibDelegate
    /*package*/ static void nDrawCircle(long nativeCanvas,
            float cx, float cy, float radius, long paint) {
        nDrawOval(nativeCanvas,
                cx - radius, cy - radius, cx + radius, cy + radius,
                paint);
    }

    @LayoutlibDelegate
    /*package*/ static void nDrawArc(long nativeCanvas,
            final float left, final float top, final float right, final float bottom,
            final float startAngle, final float sweep,
            final boolean useCenter, long paint) {
        if (right > left && bottom > top) {
            draw(nativeCanvas, paint, false /*compositeOnly*/, false /*forceSrcMode*/,
                    (graphics, paintDelegate) -> {
                        int style = paintDelegate.getStyle();

                        Arc2D.Float arc = new Arc2D.Float(
                                left, top, right - left, bottom - top,
                                -startAngle, -sweep,
                                useCenter ? Arc2D.PIE : Arc2D.OPEN);

                        // draw
                        if (style == Paint.Style.FILL.nativeInt ||
                                style == Paint.Style.FILL_AND_STROKE.nativeInt) {
                            graphics.fill(arc);
                        }

                        if (style == Paint.Style.STROKE.nativeInt ||
                                style == Paint.Style.FILL_AND_STROKE.nativeInt) {
                            graphics.draw(arc);
                        }
                    });
        }
    }

    @LayoutlibDelegate
    /*package*/ static void nDrawRoundRect(long nativeCanvas,
            final float left, final float top, final float right, final float bottom,
            final float rx, final float ry, long paint) {
        draw(nativeCanvas, paint, false /*compositeOnly*/, false /*forceSrcMode*/,
                (graphics, paintDelegate) -> {
                    int style = paintDelegate.getStyle();

                    // draw
                    if (style == Paint.Style.FILL.nativeInt ||
                            style == Paint.Style.FILL_AND_STROKE.nativeInt) {
                        graphics.fillRoundRect(
                                (int)left, (int)top,
                                (int)(right - left), (int)(bottom - top),
                                2 * (int)rx, 2 * (int)ry);
                    }

                    if (style == Paint.Style.STROKE.nativeInt ||
                            style == Paint.Style.FILL_AND_STROKE.nativeInt) {
                        graphics.drawRoundRect(
                                (int)left, (int)top,
                                (int)(right - left), (int)(bottom - top),
                                2 * (int)rx, 2 * (int)ry);
                    }
                });
    }

    @LayoutlibDelegate
    public static void nDrawPath(long nativeCanvas, long path, long paint) {
        final Path_Delegate pathDelegate = Path_Delegate.getDelegate(path);
        if (pathDelegate == null) {
            return;
        }

        draw(nativeCanvas, paint, false /*compositeOnly*/, false /*forceSrcMode*/,
                (graphics, paintDelegate) -> {
                    Shape shape = pathDelegate.getJavaShape();
                    Rectangle2D bounds = shape.getBounds2D();
                    if (bounds.isEmpty()) {
                        // Apple JRE 1.6 doesn't like drawing empty shapes.
                        // http://b.android.com/178278

                        if (pathDelegate.isEmpty()) {
                            // This means that the path doesn't have any lines or curves so
                            // nothing to draw.
                            return;
                        }

                        // The stroke width is not consider for the size of the bounds so,
                        // for example, a horizontal line, would be considered as an empty
                        // rectangle.
                        // If the strokeWidth is not 0, we use it to consider the size of the
                        // path as well.
                        float strokeWidth = paintDelegate.getStrokeWidth();
                        if (strokeWidth <= 0.0f) {
                            return;
                        }
                        bounds.setRect(bounds.getX(), bounds.getY(),
                                Math.max(strokeWidth, bounds.getWidth()),
                                Math.max(strokeWidth, bounds.getHeight()));
                    }

                    int style = paintDelegate.getStyle();

                    if (style == Paint.Style.FILL.nativeInt ||
                            style == Paint.Style.FILL_AND_STROKE.nativeInt) {
                        graphics.fill(shape);
                    }

                    if (style == Paint.Style.STROKE.nativeInt ||
                            style == Paint.Style.FILL_AND_STROKE.nativeInt) {
                        graphics.draw(shape);
                    }
                });
    }

    @LayoutlibDelegate
    /*package*/ static void nDrawRegion(long nativeCanvas, long nativeRegion,
            long nativePaint) {
        // FIXME
        Bridge.getLog().fidelityWarning(LayoutLog.TAG_UNSUPPORTED,
                "Some canvas paths may not be drawn", null, null);
    }

    @LayoutlibDelegate
    /*package*/ static void nDrawNinePatch(long nativeCanvas, long nativeBitmap, long ninePatch,
            final float dstLeft, final float dstTop, final float dstRight, final float dstBottom,
            long nativePaintOrZero, final int screenDensity, final int bitmapDensity) {

        // get the delegate from the native int.
        final Bitmap_Delegate bitmapDelegate = Bitmap_Delegate.getDelegate(nativeBitmap);
        if (bitmapDelegate == null) {
            return;
        }

        byte[] c = NinePatch_Delegate.getChunk(ninePatch);
        if (c == null) {
            // not a 9-patch?
            BufferedImage image = bitmapDelegate.getImage();
            drawBitmap(nativeCanvas, bitmapDelegate, nativePaintOrZero, 0, 0, image.getWidth(),
                    image.getHeight(), (int) dstLeft, (int) dstTop, (int) dstRight,
                    (int) dstBottom);
            return;
        }

        final NinePatchChunk chunkObject = NinePatch_Delegate.getChunk(c);
        assert chunkObject != null;
        if (chunkObject == null) {
            return;
        }

        Canvas_Delegate canvasDelegate = Canvas_Delegate.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            return;
        }

        // this one can be null
        Paint_Delegate paintDelegate = Paint_Delegate.getDelegate(nativePaintOrZero);

        canvasDelegate.getSnapshot().draw(new GcSnapshot.Drawable() {
            @Override
            public void draw(Graphics2D graphics, Paint_Delegate paint) {
                chunkObject.draw(bitmapDelegate.getImage(), graphics, (int) dstLeft, (int) dstTop,
                        (int) (dstRight - dstLeft), (int) (dstBottom - dstTop), screenDensity,
                        bitmapDensity);
            }
        }, paintDelegate, true, false);

    }

    @LayoutlibDelegate
    /*package*/ static void nDrawBitmapMatrix(long nCanvas, Bitmap bitmap,
            long nMatrix, long nPaint) {
        // get the delegate from the native int.
        BaseCanvas_Delegate canvasDelegate = sManager.getDelegate(nCanvas);
        if (canvasDelegate == null) {
            return;
        }

        // get the delegate from the native int, which can be null
        Paint_Delegate paintDelegate = Paint_Delegate.getDelegate(nPaint);

        // get the delegate from the native int.
        Bitmap_Delegate bitmapDelegate = Bitmap_Delegate.getDelegate(bitmap);
        if (bitmapDelegate == null) {
            return;
        }

        final BufferedImage image = getImageToDraw(bitmapDelegate, paintDelegate, sBoolOut);

        Matrix_Delegate matrixDelegate = Matrix_Delegate.getDelegate(nMatrix);
        if (matrixDelegate == null) {
            return;
        }

        final AffineTransform mtx = matrixDelegate.getAffineTransform();

        canvasDelegate.getSnapshot().draw((graphics, paint) -> {
            if (paint != null && paint.isFilterBitmap()) {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            }

            //FIXME add support for canvas, screen and bitmap densities.
            graphics.drawImage(image, mtx, null);
        }, paintDelegate, true /*compositeOnly*/, false /*forceSrcMode*/);
    }

    @LayoutlibDelegate
    /*package*/ static void nDrawBitmapMesh(long nCanvas, Bitmap bitmap,
            int meshWidth, int meshHeight, float[] verts, int vertOffset, int[] colors,
            int colorOffset, long nPaint) {
        // FIXME
        Bridge.getLog().fidelityWarning(LayoutLog.TAG_UNSUPPORTED,
                "Canvas.drawBitmapMesh is not supported.", null, null /*data*/);
    }

    @LayoutlibDelegate
    /*package*/ static void nDrawVertices(long nCanvas, int mode, int n,
            float[] verts, int vertOffset,
            float[] texs, int texOffset,
            int[] colors, int colorOffset,
            short[] indices, int indexOffset,
            int indexCount, long nPaint) {
        // FIXME
        Bridge.getLog().fidelityWarning(LayoutLog.TAG_UNSUPPORTED,
                "Canvas.drawVertices is not supported.", null, null /*data*/);
    }

    @LayoutlibDelegate
    /*package*/ static void nDrawText(long nativeCanvas, char[] text, int index, int count,
            float startX, float startY, int flags, long paint, long typeface) {
        drawText(nativeCanvas, text, index, count, startX, startY, (flags & 1) != 0,
                paint, typeface);
    }

    @LayoutlibDelegate
    /*package*/ static void nDrawText(long nativeCanvas, String text,
            int start, int end, float x, float y, final int flags, long paint,
            long typeface) {
        int count = end - start;
        char[] buffer = TemporaryBuffer.obtain(count);
        TextUtils.getChars(text, start, end, buffer, 0);

        nDrawText(nativeCanvas, buffer, 0, count, x, y, flags, paint, typeface);
    }

    @LayoutlibDelegate
    /*package*/ static void nDrawTextRun(long nativeCanvas, String text,
            int start, int end, int contextStart, int contextEnd,
            float x, float y, boolean isRtl, long paint, long typeface) {
        int count = end - start;
        char[] buffer = TemporaryBuffer.obtain(count);
        TextUtils.getChars(text, start, end, buffer, 0);

        drawText(nativeCanvas, buffer, 0, count, x, y, isRtl, paint, typeface);
    }

    @LayoutlibDelegate
    /*package*/ static void nDrawTextRun(long nativeCanvas, char[] text,
            int start, int count, int contextStart, int contextCount,
            float x, float y, boolean isRtl, long paint, long typeface) {
        drawText(nativeCanvas, text, start, count, x, y, isRtl, paint, typeface);
    }

    @LayoutlibDelegate
    /*package*/ static void nDrawTextOnPath(long nativeCanvas,
            char[] text, int index,
            int count, long path,
            float hOffset,
            float vOffset, int bidiFlags,
            long paint, long typeface) {
        // FIXME
        Bridge.getLog().fidelityWarning(LayoutLog.TAG_UNSUPPORTED,
                "Canvas.drawTextOnPath is not supported.", null, null /*data*/);
    }

    @LayoutlibDelegate
    /*package*/ static void nDrawTextOnPath(long nativeCanvas,
            String text, long path,
            float hOffset,
            float vOffset,
            int bidiFlags, long paint,
            long typeface) {
        // FIXME
        Bridge.getLog().fidelityWarning(LayoutLog.TAG_UNSUPPORTED,
                "Canvas.drawTextOnPath is not supported.", null, null /*data*/);
    }

    // ---- Private delegate/helper methods ----

    /**
     * Executes a {@link GcSnapshot.Drawable} with a given canvas and paint.
     * <p>Note that the drawable may actually be executed several times if there are
     * layers involved (see {@link #saveLayer(RectF, Paint_Delegate, int)}.
     */
    private static void draw(long nCanvas, long nPaint, boolean compositeOnly, boolean forceSrcMode,
            GcSnapshot.Drawable drawable) {
        // get the delegate from the native int.
        BaseCanvas_Delegate canvasDelegate = sManager.getDelegate(nCanvas);
        if (canvasDelegate == null) {
            return;
        }

        // get the paint which can be null if nPaint is 0;
        Paint_Delegate paintDelegate = Paint_Delegate.getDelegate(nPaint);

        canvasDelegate.getSnapshot().draw(drawable, paintDelegate, compositeOnly, forceSrcMode);
    }

    /**
     * Executes a {@link GcSnapshot.Drawable} with a given canvas. No paint object will be provided
     * to {@link GcSnapshot.Drawable#draw(Graphics2D, Paint_Delegate)}.
     * <p>Note that the drawable may actually be executed several times if there are
     * layers involved (see {@link #saveLayer(RectF, Paint_Delegate, int)}.
     */
    private static void draw(long nCanvas, GcSnapshot.Drawable drawable) {
        // get the delegate from the native int.
        BaseCanvas_Delegate canvasDelegate = sManager.getDelegate(nCanvas);
        if (canvasDelegate == null) {
            return;
        }

        canvasDelegate.mSnapshot.draw(drawable);
    }

    private static void drawText(long nativeCanvas, final char[] text, final int index,
            final int count, final float startX, final float startY, final boolean isRtl,
            long paint, final long typeface) {

        draw(nativeCanvas, paint, false /*compositeOnly*/, false /*forceSrcMode*/,
                (graphics, paintDelegate) -> {
                    // WARNING: the logic in this method is similar to Paint_Delegate.measureText.
                    // Any change to this method should be reflected in Paint.measureText

                    // assert that the typeface passed is actually the one stored in paint.
                    assert (typeface == paintDelegate.mNativeTypeface);

                    // Paint.TextAlign indicates how the text is positioned relative to X.
                    // LEFT is the default and there's nothing to do.
                    float x = startX;
                    int limit = index + count;
                    if (paintDelegate.getTextAlign() != Paint.Align.LEFT.nativeInt) {
                        RectF bounds =
                                paintDelegate.measureText(text, index, count, null, 0, isRtl);
                        float m = bounds.right - bounds.left;
                        if (paintDelegate.getTextAlign() == Paint.Align.CENTER.nativeInt) {
                            x -= m / 2;
                        } else if (paintDelegate.getTextAlign() == Paint.Align.RIGHT.nativeInt) {
                            x -= m;
                        }
                    }

                    new BidiRenderer(graphics, paintDelegate, text).setRenderLocation(x,
                            startY).renderText(index, limit, isRtl, null, 0, true);
                });
    }

    private static void drawBitmap(long nativeCanvas, Bitmap_Delegate bitmap,
            long nativePaintOrZero, final int sleft, final int stop, final int sright,
            final int sbottom, final int dleft, final int dtop, final int dright,
            final int dbottom) {
        // get the delegate from the native int.
        BaseCanvas_Delegate canvasDelegate = sManager.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            return;
        }

        // get the paint, which could be null if the int is 0
        Paint_Delegate paintDelegate = Paint_Delegate.getDelegate(nativePaintOrZero);

        final BufferedImage image = getImageToDraw(bitmap, paintDelegate, sBoolOut);

        draw(nativeCanvas, nativePaintOrZero, true /*compositeOnly*/, sBoolOut[0],
                (graphics, paint) -> {
                    if (paint != null && paint.isFilterBitmap()) {
                        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    }

                    //FIXME add support for canvas, screen and bitmap densities.
                    graphics.drawImage(image, dleft, dtop, dright, dbottom, sleft, stop, sright,
                            sbottom, null);
                });
    }

    /**
     * Returns a BufferedImage ready for drawing, based on the bitmap and paint delegate.
     * The image returns, through a 1-size boolean array, whether the drawing code should
     * use a SRC composite no matter what the paint says.
     *
     * @param bitmap the bitmap
     * @param paint the paint that will be used to draw
     * @param forceSrcMode whether the composite will have to be SRC
     * @return the image to draw
     */
    private static BufferedImage getImageToDraw(Bitmap_Delegate bitmap, Paint_Delegate paint,
            boolean[] forceSrcMode) {
        BufferedImage image = bitmap.getImage();
        forceSrcMode[0] = false;

        // if the bitmap config is alpha_8, then we erase all color value from it
        // before drawing it or apply the texture from the shader if present.
        if (bitmap.getConfig() == Bitmap.Config.ALPHA_8) {
            Shader_Delegate shader = paint.getShader();
            java.awt.Paint javaPaint = null;
            if (shader instanceof BitmapShader_Delegate) {
                javaPaint = shader.getJavaPaint();
            }

            fixAlpha8Bitmap(image, javaPaint);
        } else if (!bitmap.hasAlpha()) {
            // hasAlpha is merely a rendering hint. There can in fact be alpha values
            // in the bitmap but it should be ignored at drawing time.
            // There is two ways to do this:
            // - override the composite to be SRC. This can only be used if the composite
            //   was going to be SRC or SRC_OVER in the first place
            // - Create a different bitmap to draw in which all the alpha channel values is set
            //   to 0xFF.
            if (paint != null) {
                PorterDuff.Mode mode = PorterDuff.intToMode(paint.getPorterDuffMode());

                forceSrcMode[0] = mode == PorterDuff.Mode.SRC_OVER || mode == PorterDuff.Mode.SRC;
            }

            // if we can't force SRC mode, then create a temp bitmap of TYPE_RGB
            if (!forceSrcMode[0]) {
                image = Bitmap_Delegate.createCopy(image, BufferedImage.TYPE_INT_RGB, 0xFF);
            }
        }

        return image;
    }

    /**
     * This method will apply the correct color to the passed "only alpha" image. Colors on the
     * passed image will be destroyed.
     * If the passed javaPaint is null, the color will be set to 0. If a paint is passed, it will
     * be used to obtain the color that will be applied.
     * <p/>
     * This will destroy the passed image color channel.
     */
    private static void fixAlpha8Bitmap(final BufferedImage image,
            @Nullable java.awt.Paint javaPaint) {
        int w = image.getWidth();
        int h = image.getHeight();

        DataBuffer texture = null;
        if (javaPaint != null) {
            PaintContext context = javaPaint.createContext(ColorModel.getRGBdefault(), null, null,
                    new AffineTransform(), null);
            texture = context.getRaster(0, 0, w, h).getDataBuffer();
        }

        int[] argb = new int[w * h];
        image.getRGB(0, 0, image.getWidth(), image.getHeight(), argb, 0, image.getWidth());

        final int length = argb.length;
        for (int i = 0; i < length; i++) {
            argb[i] &= 0xFF000000;
            if (texture != null) {
                argb[i] |= texture.getElem(i) & 0x00FFFFFF;
            }
        }

        image.setRGB(0, 0, w, h, argb, 0, w);
    }

    protected int save(int saveFlags) {
        // get the current save count
        int count = mSnapshot.size();

        mSnapshot = mSnapshot.save(saveFlags);

        // return the old save count
        return count;
    }

    protected int saveLayerAlpha(RectF rect, int alpha, int saveFlags) {
        Paint_Delegate paint = new Paint_Delegate();
        paint.setAlpha(alpha);
        return saveLayer(rect, paint, saveFlags);
    }

    protected int saveLayer(RectF rect, Paint_Delegate paint, int saveFlags) {
        // get the current save count
        int count = mSnapshot.size();

        mSnapshot = mSnapshot.saveLayer(rect, paint, saveFlags);

        // return the old save count
        return count;
    }

    /**
     * Restores the {@link GcSnapshot} to <var>saveCount</var>
     * @param saveCount the saveCount
     */
    protected void restoreTo(int saveCount) {
        mSnapshot = mSnapshot.restoreTo(saveCount);
    }

    /**
     * Restores the top {@link GcSnapshot}
     */
    protected void restore() {
        mSnapshot = mSnapshot.restore();
    }

    protected boolean clipRect(float left, float top, float right, float bottom, int regionOp) {
        return mSnapshot.clipRect(left, top, right, bottom, regionOp);
    }
}
