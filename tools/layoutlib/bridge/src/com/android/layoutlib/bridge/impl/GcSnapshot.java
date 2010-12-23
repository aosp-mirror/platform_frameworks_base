/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.layoutlib.bridge.impl;

import com.android.layoutlib.bridge.Bridge;

import android.graphics.Bitmap_Delegate;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint_Delegate;
import android.graphics.PathEffect_Delegate;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.Shader_Delegate;
import android.graphics.Xfermode_Delegate;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;

/**
 * Class representing a graphics context snapshot, as well as a context stack as a linked list.
 * <p>
 * This is based on top of {@link Graphics2D} but can operate independently if none are available
 * yet when setting transforms and clip information.
 * <p>
 * This allows for drawing through {@link #draw(Drawable, Paint_Delegate)} and
 * {@link #draw(Drawable, Paint_Delegate)}
 *
 */
public class GcSnapshot {

    private final GcSnapshot mPrevious;
    private final int mFlags;

    /** list of layers. */
    private final ArrayList<Layer> mLayers = new ArrayList<Layer>();

    /** temp transform in case transformation are set before a Graphics2D exists */
    private AffineTransform mTransform = null;
    /** temp clip in case clipping is set before a Graphics2D exists */
    private Area mClip = null;

    // local layer data
    private final Layer mLocalLayer;
    private final Paint_Delegate mLocalLayerPaint;
    private Rect mLocalLayerRegion;

    public interface Drawable {
        void draw(Graphics2D graphics, Paint_Delegate paint);
    }

    /**
     * class containing information about a layer.
     */
    private static class Layer {
        private final Graphics2D mGraphics;
        private final Bitmap_Delegate mBitmap;
        private final BufferedImage mImage;
        private BufferedImage mOriginalCopy;

        /**
         * Creates a layer with a graphics and a bitmap.
         *
         * @param graphics the graphics
         * @param bitmap the bitmap
         */
        Layer(Graphics2D graphics, Bitmap_Delegate bitmap) {
            mGraphics = graphics;
            mBitmap = bitmap;
            mImage = mBitmap.getImage();
        }

        /**
         * Creates a layer with a graphics and an image. If the image belongs to a
         * {@link Bitmap_Delegate}, then {@link Layer#Layer(Graphics2D, Bitmap_Delegate)} should
         * be used.
         *
         * @param graphics the graphics
         * @param image the image
         */
        Layer(Graphics2D graphics, BufferedImage image) {
            mGraphics = graphics;
            mBitmap = null;
            mImage = image;
        }

        /** The Graphics2D, guaranteed to be non null */
        Graphics2D getGraphics() {
            return mGraphics;
        }

        /** The BufferedImage, guaranteed to be non null */
        BufferedImage getImage() {
            return mImage;
        }

        Layer makeCopy() {
            if (mBitmap != null) {
                return new Layer((Graphics2D) mGraphics.create(), mBitmap);
            }

            return new Layer((Graphics2D) mGraphics.create(), mImage);
        }

        /** sets an optional copy of the original content to be used during restore */
        void setOriginalCopy(BufferedImage image) {
            mOriginalCopy = image;
        }

        BufferedImage getOriginalCopy() {
            return mOriginalCopy;
        }

        void change() {
            if (mBitmap != null) {
                mBitmap.change();
            }
        }
    }

    /**
     * Creates the root snapshot associating it with a given bitmap.
     * <p>
     * If <var>bitmap</var> is null, then {@link GcSnapshot#setBitmap(Bitmap_Delegate)} must be
     * called before the snapshot can be used to draw. Transform and clip operations are permitted
     * before.
     *
     * @param image the image to associate to the snapshot or null.
     * @return the root snapshot
     */
    public static GcSnapshot createDefaultSnapshot(Bitmap_Delegate bitmap) {
        GcSnapshot snapshot = new GcSnapshot();
        if (bitmap != null) {
            snapshot.setBitmap(bitmap);
        }

        return snapshot;
    }

    /**
     * Saves the current state according to the given flags and returns the new current snapshot.
     * <p/>
     * This is the equivalent of {@link Canvas#save(int)}
     *
     * @param flags the save flags.
     * @return the new snapshot
     *
     * @see Canvas#save(int)
     */
    public GcSnapshot save(int flags) {
        return new GcSnapshot(this, null /*layerbounds*/, null /*paint*/, flags);
    }

    /**
     * Saves the current state and creates a new layer, and returns the new current snapshot.
     * <p/>
     * This is the equivalent of {@link Canvas#saveLayer(RectF, Paint, int)}
     *
     * @param layerBounds the layer bounds
     * @param paint the Paint information used to blit the layer back into the layers underneath
     *          upon restore
     * @param flags the save flags.
     * @return the new snapshot
     *
     * @see Canvas#saveLayer(RectF, Paint, int)
     */
    public GcSnapshot saveLayer(RectF layerBounds, Paint_Delegate paint, int flags) {
        return new GcSnapshot(this, layerBounds, paint, flags);
    }

    /**
     * Creates the root snapshot.
     * {@link #setGraphics2D(Graphics2D)} will have to be called on it when possible.
     */
    private GcSnapshot() {
        mPrevious = null;
        mFlags = 0;
        mLocalLayer = null;
        mLocalLayerPaint = null;
    }

    /**
     * Creates a new {@link GcSnapshot} on top of another one, with a layer data to be restored
     * into the main graphics when {@link #restore()} is called.
     *
     * @param previous the previous snapshot head.
     * @param layerBounds the region of the layer. Optional, if null, this is a normal save()
     * @param paint the Paint information used to blit the layer back into the layers underneath
     *          upon restore
     * @param flags the flags regarding what should be saved.
     */
    private GcSnapshot(GcSnapshot previous, RectF layerBounds, Paint_Delegate paint, int flags) {
        assert previous != null;
        mPrevious = previous;
        mFlags = flags;

        // make a copy of the current layers before adding the new one.
        // This keeps the same BufferedImage reference but creates new Graphics2D for this
        // snapshot.
        // It does not copy whatever original copy the layers have, as they will be done
        // only if the new layer doesn't clip drawing to itself.
        for (Layer layer : mPrevious.mLayers) {
            mLayers.add(layer.makeCopy());
        }

        if (layerBounds != null) {
            // get the current transform
            AffineTransform matrix = mLayers.get(0).getGraphics().getTransform();

            // transform the layerBounds and puts it into a int rect
            RectF rect2 = new RectF();
            mapRect(matrix, rect2, layerBounds);
            mLocalLayerRegion = new Rect();
            rect2.round(mLocalLayerRegion);

            // get the base layer (always at index 0)
            Layer baseLayer = mLayers.get(0);

            // create the image for the layer
            BufferedImage layerImage = new BufferedImage(
                    baseLayer.getImage().getWidth(),
                    baseLayer.getImage().getHeight(),
                    BufferedImage.TYPE_INT_ARGB);

            // create a graphics for it so that drawing can be done.
            Graphics2D layerGraphics = layerImage.createGraphics();

            // because this layer inherits the current context for transform and clip,
            // set them to one from the base layer.
            AffineTransform currentMtx = baseLayer.getGraphics().getTransform();
            layerGraphics.setTransform(currentMtx);

            Shape currentClip = baseLayer.getGraphics().getClip();
            layerGraphics.setClip(currentClip);

            // create a new layer for this new layer and add it to the list at the end.
            mLayers.add(mLocalLayer = new Layer(layerGraphics, layerImage));

            // if the drawing is not clipped to the local layer only, we save the current content
            // of all other layers. We are only interested in the part that will actually
            // be drawn, so we create as small bitmaps as we can.
            // This is so that we can erase the drawing that goes in the layers below that will
            // be coming from the layer itself.
            if ((mFlags & Canvas.CLIP_TO_LAYER_SAVE_FLAG) == 0) {
                int w = mLocalLayerRegion.width();
                int h = mLocalLayerRegion.height();
                for (int i = 0 ; i < mLayers.size() - 1 ; i++) {
                    Layer layer = mLayers.get(i);
                    BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D graphics = image.createGraphics();
                    graphics.drawImage(layer.getImage(),
                            0, 0, w, h,
                            mLocalLayerRegion.left, mLocalLayerRegion.top,
                                    mLocalLayerRegion.right, mLocalLayerRegion.bottom,
                            null);
                    graphics.dispose();
                    layer.setOriginalCopy(image);
                }
            }
        } else {
            mLocalLayer = null;
        }

        mLocalLayerPaint  = paint;
    }

    public void dispose() {
        for (Layer layer : mLayers) {
            layer.getGraphics().dispose();
        }

        if (mPrevious != null) {
            mPrevious.dispose();
        }
    }

    /**
     * Restores the top {@link GcSnapshot}, and returns the next one.
     */
    public GcSnapshot restore() {
        return doRestore();
    }

    /**
     * Restores the {@link GcSnapshot} to <var>saveCount</var>.
     * @param saveCount the saveCount or -1 to only restore 1.
     *
     * @return the new head of the Gc snapshot stack.
     */
    public GcSnapshot restoreTo(int saveCount) {
        return doRestoreTo(size(), saveCount);
    }

    public int size() {
        if (mPrevious != null) {
            return mPrevious.size() + 1;
        }

        return 1;
    }

    /**
     * Link the snapshot to a Bitmap_Delegate.
     * <p/>
     * This is only for the case where the snapshot was created with a null image when calling
     * {@link #createDefaultSnapshot(Bitmap_Delegate)}, and is therefore not yet linked to
     * a previous snapshot.
     * <p/>
     * If any transform or clip information was set before, they are put into the Graphics object.
     * @param bitmap the bitmap to link to.
     */
    public void setBitmap(Bitmap_Delegate bitmap) {
        assert mLayers.size() == 0;
        Graphics2D graphics2D = bitmap.getImage().createGraphics();
        mLayers.add(new Layer(graphics2D, bitmap));
        if (mTransform != null) {
            graphics2D.setTransform(mTransform);
            mTransform = null;
        }

        if (mClip != null) {
            graphics2D.setClip(mClip);
            mClip = null;
        }
    }

    public void translate(float dx, float dy) {
        if (mLayers.size() > 0) {
            for (Layer layer : mLayers) {
                layer.getGraphics().translate(dx, dy);
            }
        } else {
            if (mTransform == null) {
                mTransform = new AffineTransform();
            }
            mTransform.translate(dx, dy);
        }
    }

    public void rotate(double radians) {
        if (mLayers.size() > 0) {
            for (Layer layer : mLayers) {
                layer.getGraphics().rotate(radians);
            }
        } else {
            if (mTransform == null) {
                mTransform = new AffineTransform();
            }
            mTransform.rotate(radians);
        }
    }

    public void scale(float sx, float sy) {
        if (mLayers.size() > 0) {
            for (Layer layer : mLayers) {
                layer.getGraphics().scale(sx, sy);
            }
        } else {
            if (mTransform == null) {
                mTransform = new AffineTransform();
            }
            mTransform.scale(sx, sy);
        }
    }

    public AffineTransform getTransform() {
        if (mLayers.size() > 0) {
            // all graphics2D in the list have the same transform
            return mLayers.get(0).getGraphics().getTransform();
        } else {
            if (mTransform == null) {
                mTransform = new AffineTransform();
            }
            return mTransform;
        }
    }

    public void setTransform(AffineTransform transform) {
        if (mLayers.size() > 0) {
            for (Layer layer : mLayers) {
                layer.getGraphics().setTransform(transform);
            }
        } else {
            if (mTransform == null) {
                mTransform = new AffineTransform();
            }
            mTransform.setTransform(transform);
        }
    }

    public boolean clipRect(float left, float top, float right, float bottom, int regionOp) {
        if (mLayers.size() > 0) {
            Shape clip = null;
            if (regionOp == Region.Op.DIFFERENCE.nativeInt) {
                Area newClip = new Area(getClip());
                newClip.subtract(new Area(
                        new Rectangle2D.Float(left, top, right - left, bottom - top)));
                clip = newClip;

            } else if (regionOp == Region.Op.INTERSECT.nativeInt) {
                for (Layer layer : mLayers) {
                    layer.getGraphics().clipRect(
                            (int) left, (int) top, (int) (right - left), (int) (bottom - top));
                }

            } else if (regionOp == Region.Op.UNION.nativeInt) {
                Area newClip = new Area(getClip());
                newClip.add(new Area(
                        new Rectangle2D.Float(left, top, right - left, bottom - top)));
                clip = newClip;

            } else if (regionOp == Region.Op.XOR.nativeInt) {
                Area newClip = new Area(getClip());
                newClip.exclusiveOr(new Area(
                        new Rectangle2D.Float(left, top, right - left, bottom - top)));
                clip = newClip;

            } else if (regionOp == Region.Op.REVERSE_DIFFERENCE.nativeInt) {
                Area newClip = new Area(
                        new Rectangle2D.Float(left, top, right - left, bottom - top));
                newClip.subtract(new Area(getClip()));
                clip = newClip;

            } else if (regionOp == Region.Op.REPLACE.nativeInt) {
                for (Layer layer : mLayers) {
                    layer.getGraphics().setClip(
                            (int) left, (int) top, (int) (right - left), (int) (bottom - top));
                }
            }

            if (clip != null) {
                for (Layer layer : mLayers) {
                    layer.getGraphics().setClip(clip);
                }
            }

            return getClip().getBounds().isEmpty() == false;
        } else {
            if (mClip == null) {
                mClip = new Area();
            }

            if (regionOp == Region.Op.DIFFERENCE.nativeInt) {
                //FIXME
            } else if (regionOp == Region.Op.DIFFERENCE.nativeInt) {
            } else if (regionOp == Region.Op.INTERSECT.nativeInt) {
            } else if (regionOp == Region.Op.UNION.nativeInt) {
            } else if (regionOp == Region.Op.XOR.nativeInt) {
            } else if (regionOp == Region.Op.REVERSE_DIFFERENCE.nativeInt) {
            } else if (regionOp == Region.Op.REPLACE.nativeInt) {
            }

            return mClip.getBounds().isEmpty() == false;
        }
    }

    public Shape getClip() {
        if (mLayers.size() > 0) {
            // they all have the same clip
            return mLayers.get(0).getGraphics().getClip();
        } else {
            if (mClip == null) {
                mClip = new Area();
            }
            return mClip;
        }
    }

    private GcSnapshot doRestoreTo(int size, int saveCount) {
        if (size <= saveCount) {
            return this;
        }

        // restore the current one first.
        GcSnapshot previous = doRestore();

        if (size == saveCount + 1) { // this was the only one that needed restore.
            return previous;
        } else {
            return previous.doRestoreTo(size - 1, saveCount);
        }
    }

    /**
     * Executes the Drawable's draw method, with a null paint delegate.
     * <p/>
     * Note that the method can be called several times if there are more than one active layer.
     * @param drawable
     */
    public void draw(Drawable drawable) {
        draw(drawable, null, false /*compositeOnly*/, false /*forceSrcMode*/);
    }

    /**
     * Executes the Drawable's draw method.
     * <p/>
     * Note that the method can be called several times if there are more than one active layer.
     * @param drawable
     * @param paint
     * @param compositeOnly whether the paint is used for composite only. This is typically
     *          the case for bitmaps.
     * @param forceSrcMode if true, this overrides the composite to be SRC
     */
    public void draw(Drawable drawable, Paint_Delegate paint, boolean compositeOnly,
            boolean forceSrcMode) {
        // if we clip to the layer, then we only draw in the layer
        if (mLocalLayer != null && (mFlags & Canvas.CLIP_TO_LAYER_SAVE_FLAG) != 0) {
            drawInLayer(mLocalLayer, drawable, paint, compositeOnly, forceSrcMode);
        } else {
            // draw in all the layers
            for (Layer layer : mLayers) {
                drawInLayer(layer, drawable, paint, compositeOnly, forceSrcMode);
            }
        }
    }

    private void drawInLayer(Layer layer, Drawable drawable, Paint_Delegate paint,
            boolean compositeOnly, boolean forceSrcMode) {
        Graphics2D originalGraphics = layer.getGraphics();
        // get a Graphics2D object configured with the drawing parameters.
        Graphics2D configuredGraphics2D =
            paint != null ?
                    createCustomGraphics(originalGraphics, paint, compositeOnly, forceSrcMode) :
                        (Graphics2D) originalGraphics.create();

        try {
            drawable.draw(configuredGraphics2D, paint);
            layer.change();
        } finally {
            // dispose Graphics2D object
            configuredGraphics2D.dispose();
        }
    }

    private GcSnapshot doRestore() {
        if (mPrevious != null) {
            boolean forceAllSave = false;
            if (mLocalLayer != null) {
                forceAllSave = true; // layers always save both clip and transform

                // prepare to draw the current layer in the previous layers, ie all layers but
                // the last one, since the last one is the local layer
                for (int i = 0 ; i < mLayers.size() - 1 ; i++) {
                    Layer layer = mLayers.get(i);

                    Graphics2D baseGfx = layer.getImage().createGraphics();

                    // if the layer contains an original copy this means the flags
                    // didn't restrict drawing to the local layer and we need to make sure the
                    // layer bounds in the layer beneath didn't receive any drawing.
                    // so we use the originalCopy to erase the new drawings in there.
                    BufferedImage originalCopy = layer.getOriginalCopy();
                    if (originalCopy != null) {
                        Graphics2D g = (Graphics2D) baseGfx.create();
                        g.setComposite(AlphaComposite.Src);

                        g.drawImage(originalCopy,
                                mLocalLayerRegion.left, mLocalLayerRegion.top,
                                        mLocalLayerRegion.right, mLocalLayerRegion.bottom,
                                0, 0, mLocalLayerRegion.width(), mLocalLayerRegion.height(),
                                null);
                        g.dispose();
                    }

                    // now draw put the content of the local layer onto the layer, using the paint
                    // information
                    Graphics2D g = createCustomGraphics(baseGfx, mLocalLayerPaint,
                            true /*alphaOnly*/, false /*forceSrcMode*/);

                    g.drawImage(mLocalLayer.getImage(),
                            mLocalLayerRegion.left, mLocalLayerRegion.top,
                                    mLocalLayerRegion.right, mLocalLayerRegion.bottom,
                            mLocalLayerRegion.left, mLocalLayerRegion.top,
                                    mLocalLayerRegion.right, mLocalLayerRegion.bottom,
                            null);
                    g.dispose();

                    baseGfx.dispose();
                }
            }

            // if this snapshot does not save everything, then set the previous snapshot
            // to this snapshot content

            // didn't save the matrix? set the current matrix on the previous snapshot
            if (forceAllSave == false && (mFlags & Canvas.MATRIX_SAVE_FLAG) == 0) {
                AffineTransform mtx = getTransform();
                for (Layer layer : mPrevious.mLayers) {
                    layer.getGraphics().setTransform(mtx);
                }
            }

            // didn't save the clip? set the current clip on the previous snapshot
            if (forceAllSave == false && (mFlags & Canvas.CLIP_SAVE_FLAG) == 0) {
                Shape clip = getClip();
                for (Layer layer : mPrevious.mLayers) {
                    layer.getGraphics().setClip(clip);
                }
            }
        }

        for (Layer layer : mLayers) {
            layer.getGraphics().dispose();
        }

        return mPrevious;
    }

    /**
     * Creates a new {@link Graphics2D} based on the {@link Paint} parameters.
     * <p/>The object must be disposed ({@link Graphics2D#dispose()}) after being used.
     */
    private Graphics2D createCustomGraphics(Graphics2D original, Paint_Delegate paint,
            boolean compositeOnly, boolean forceSrcMode) {
        // make new one graphics
        Graphics2D g = (Graphics2D) original.create();

        // configure it

        if (paint.isAntiAliased()) {
            g.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        }

        boolean customShader = false;

        // get the shader first, as it'll replace the color if it can be used it.
        if (compositeOnly == false) {
            int nativeShader = paint.getShader();
            if (nativeShader > 0) {
                Shader_Delegate shaderDelegate = Shader_Delegate.getDelegate(nativeShader);
                assert shaderDelegate != null;
                if (shaderDelegate != null) {
                    if (shaderDelegate.isSupported()) {
                        java.awt.Paint shaderPaint = shaderDelegate.getJavaPaint();
                        assert shaderPaint != null;
                        if (shaderPaint != null) {
                            g.setPaint(shaderPaint);
                            customShader = true;
                        }
                    } else {
                        Bridge.getLog().fidelityWarning(null,
                                shaderDelegate.getSupportMessage(),
                                null);
                    }
                }
            }

            // if no shader, use the paint color
            if (customShader == false) {
                g.setColor(new Color(paint.getColor(), true /*hasAlpha*/));
            }

            boolean customStroke = false;
            int pathEffect = paint.getPathEffect();
            if (pathEffect > 0) {
                PathEffect_Delegate effectDelegate = PathEffect_Delegate.getDelegate(pathEffect);
                assert effectDelegate != null;
                if (effectDelegate != null) {
                    if (effectDelegate.isSupported()) {
                        Stroke stroke = effectDelegate.getStroke(paint);
                        assert stroke != null;
                        if (stroke != null) {
                            g.setStroke(stroke);
                            customStroke = true;
                        }
                    } else {
                        Bridge.getLog().fidelityWarning(null,
                                effectDelegate.getSupportMessage(),
                                null);
                    }
                }
            }

            // if no custom stroke as been set, set the default one.
            if (customStroke == false) {
                g.setStroke(new BasicStroke(
                        paint.getStrokeWidth(),
                        paint.getJavaCap(),
                        paint.getJavaJoin(),
                        paint.getJavaStrokeMiter()));
            }
        }

        // the alpha for the composite. Always opaque if the normal paint color is used since
        // it contains the alpha
        int alpha = (compositeOnly || customShader) ? paint.getAlpha() : 0xFF;

        if (forceSrcMode) {
            g.setComposite(AlphaComposite.getInstance(
                    AlphaComposite.SRC, (float) alpha / 255.f));
        } else {
            boolean customXfermode = false;
            int xfermode = paint.getXfermode();
            if (xfermode > 0) {
                Xfermode_Delegate xfermodeDelegate = Xfermode_Delegate.getDelegate(xfermode);
                assert xfermodeDelegate != null;
                if (xfermodeDelegate != null) {
                    if (xfermodeDelegate.isSupported()) {
                        Composite composite = xfermodeDelegate.getComposite(alpha);
                        assert composite != null;
                        if (composite != null) {
                            g.setComposite(composite);
                            customXfermode = true;
                        }
                    } else {
                        Bridge.getLog().fidelityWarning(null,
                                xfermodeDelegate.getSupportMessage(),
                                null);
                    }
                }
            }

            // if there was no custom xfermode, but we have alpha (due to a shader and a non
            // opaque alpha channel in the paint color), then we create an AlphaComposite anyway
            // that will handle the alpha.
            if (customXfermode == false && alpha != 0xFF) {
                g.setComposite(AlphaComposite.getInstance(
                        AlphaComposite.SRC_OVER, (float) alpha / 255.f));
            }
        }

        return g;
    }

    private void mapRect(AffineTransform matrix, RectF dst, RectF src) {
        // array with 4 corners
        float[] corners = new float[] {
                src.left, src.top,
                src.right, src.top,
                src.right, src.bottom,
                src.left, src.bottom,
        };

        // apply the transform to them.
        matrix.transform(corners, 0, corners, 0, 4);

        // now put the result in the rect. We take the min/max of Xs and min/max of Ys
        dst.left = Math.min(Math.min(corners[0], corners[2]), Math.min(corners[4], corners[6]));
        dst.right = Math.max(Math.max(corners[0], corners[2]), Math.max(corners[4], corners[6]));

        dst.top = Math.min(Math.min(corners[1], corners[3]), Math.min(corners[5], corners[7]));
        dst.bottom = Math.max(Math.max(corners[1], corners[3]), Math.max(corners[5], corners[7]));
    }

}
