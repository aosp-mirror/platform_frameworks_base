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

import com.android.ide.common.rendering.api.LayoutLog;
import com.android.layoutlib.bridge.Bridge;

import android.graphics.Bitmap_Delegate;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint_Delegate;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.Region_Delegate;
import android.graphics.Shader_Delegate;
import android.graphics.Xfermode_Delegate;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
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
 * Handling of layers (created with {@link Canvas#saveLayer(RectF, Paint, int)}) is handled through
 * a list of Graphics2D for each layers. The class actually maintains a list of {@link Layer}
 * for each layer. Doing a save() will duplicate this list so that each graphics2D object
 * ({@link Layer#getGraphics()}) is configured only for the new snapshot.
 */
public class GcSnapshot {

    private final GcSnapshot mPrevious;
    private final int mFlags;

    /** list of layers. The first item in the list is always the  */
    private final ArrayList<Layer> mLayers = new ArrayList<Layer>();

    /** temp transform in case transformation are set before a Graphics2D exists */
    private AffineTransform mTransform = null;
    /** temp clip in case clipping is set before a Graphics2D exists */
    private Area mClip = null;

    // local layer data
    /** a local layer created with {@link Canvas#saveLayer(RectF, Paint, int)}.
     * If this is null, this does not mean there's no layer, just that the snapshot is not the
     * one that created the layer.
     * @see #getLayerSnapshot()
     */
    private final Layer mLocalLayer;
    private final Paint_Delegate mLocalLayerPaint;
    private final Rect mLayerBounds;

    public interface Drawable {
        void draw(Graphics2D graphics, Paint_Delegate paint);
    }

    /**
     * Class containing information about a layer.
     *
     * This contains graphics, bitmap and layer information.
     */
    private static class Layer {
        private final Graphics2D mGraphics;
        private final Bitmap_Delegate mBitmap;
        private final BufferedImage mImage;
        /** the flags that were used to configure the layer. This is never changed, and passed
         * as is when {@link #makeCopy()} is called */
        private final int mFlags;
        /** the original content of the layer when the next object was created. This is not
         * passed in {@link #makeCopy()} and instead is recreated when a new layer is added
         * (depending on its flags) */
        private BufferedImage mOriginalCopy;

        /**
         * Creates a layer with a graphics and a bitmap. This is only used to create
         * the base layer.
         *
         * @param graphics the graphics
         * @param bitmap the bitmap
         */
        Layer(Graphics2D graphics, Bitmap_Delegate bitmap) {
            mGraphics = graphics;
            mBitmap = bitmap;
            mImage = mBitmap.getImage();
            mFlags = 0;
        }

        /**
         * Creates a layer with a graphics and an image. If the image belongs to a
         * {@link Bitmap_Delegate} (case of the base layer), then
         * {@link Layer#Layer(Graphics2D, Bitmap_Delegate)} should be used.
         *
         * @param graphics the graphics the new graphics for this layer
         * @param image the image the image from which the graphics came
         * @param flags the flags that were used to save this layer
         */
        Layer(Graphics2D graphics, BufferedImage image, int flags) {
            mGraphics = graphics;
            mBitmap = null;
            mImage = image;
            mFlags = flags;
        }

        /** The Graphics2D, guaranteed to be non null */
        Graphics2D getGraphics() {
            return mGraphics;
        }

        /** The BufferedImage, guaranteed to be non null */
        BufferedImage getImage() {
            return mImage;
        }

        /** Returns the layer save flags. This is only valid for additional layers.
         * For the base layer this will always return 0;
         * For a given layer, all further copies of this {@link Layer} object in new snapshots
         * will always return the same value.
         */
        int getFlags() {
            return mFlags;
        }

        Layer makeCopy() {
            if (mBitmap != null) {
                return new Layer((Graphics2D) mGraphics.create(), mBitmap);
            }

            return new Layer((Graphics2D) mGraphics.create(), mImage, mFlags);
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

        /**
         * Sets the clip for the graphics2D object associated with the layer.
         * This should be used over the normal Graphics2D setClip method.
         *
         * @param clipShape the shape to use a the clip shape.
         */
        void setClip(Shape clipShape) {
            // because setClip is only guaranteed to work with rectangle shape,
            // first reset the clip to max and then intersect the current (empty)
            // clip with the shap.
            mGraphics.setClip(null);
            mGraphics.clip(clipShape);
        }

        /**
         * Clips the layer with the given shape. This performs an intersect between the current
         * clip shape and the given shape.
         * @param shape the new clip shape.
         */
        public void clip(Shape shape) {
            mGraphics.clip(shape);
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
        mLayerBounds = null;
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

            // transform the layerBounds with the current transform and stores it into a int rect
            RectF rect2 = new RectF();
            mapRect(matrix, rect2, layerBounds);
            mLayerBounds = new Rect();
            rect2.round(mLayerBounds);

            // get the base layer (always at index 0)
            Layer baseLayer = mLayers.get(0);

            // create the image for the layer
            BufferedImage layerImage = new BufferedImage(
                    baseLayer.getImage().getWidth(),
                    baseLayer.getImage().getHeight(),
                    (mFlags & Canvas.HAS_ALPHA_LAYER_SAVE_FLAG) != 0 ?
                            BufferedImage.TYPE_INT_ARGB :
                                BufferedImage.TYPE_INT_RGB);

            // create a graphics for it so that drawing can be done.
            Graphics2D layerGraphics = layerImage.createGraphics();

            // because this layer inherits the current context for transform and clip,
            // set them to one from the base layer.
            AffineTransform currentMtx = baseLayer.getGraphics().getTransform();
            layerGraphics.setTransform(currentMtx);

            // create a new layer for this new layer and add it to the list at the end.
            mLayers.add(mLocalLayer = new Layer(layerGraphics, layerImage, flags));

            // set the clip on it.
            Shape currentClip = baseLayer.getGraphics().getClip();
            mLocalLayer.setClip(currentClip);

            // if the drawing is not clipped to the local layer only, we save the current content
            // of all other layers. We are only interested in the part that will actually
            // be drawn, so we create as small bitmaps as we can.
            // This is so that we can erase the drawing that goes in the layers below that will
            // be coming from the layer itself.
            if ((mFlags & Canvas.CLIP_TO_LAYER_SAVE_FLAG) == 0) {
                int w = mLayerBounds.width();
                int h = mLayerBounds.height();
                for (int i = 0 ; i < mLayers.size() - 1 ; i++) {
                    Layer layer = mLayers.get(i);
                    BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D graphics = image.createGraphics();
                    graphics.drawImage(layer.getImage(),
                            0, 0, w, h,
                            mLayerBounds.left, mLayerBounds.top,
                                    mLayerBounds.right, mLayerBounds.bottom,
                            null);
                    graphics.dispose();
                    layer.setOriginalCopy(image);
                }
            }
        } else {
            mLocalLayer = null;
            mLayerBounds = null;
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
        // create a new Layer for the bitmap. This will be the base layer.
        Graphics2D graphics2D = bitmap.getImage().createGraphics();
        Layer baseLayer = new Layer(graphics2D, bitmap);

        // Set the current transform and clip which can either come from mTransform/mClip if they
        // were set when there was no bitmap/layers or from the current base layers if there is
        // one already.

        graphics2D.setTransform(getTransform());
        // reset mTransform in case there was one.
        mTransform = null;

        baseLayer.setClip(getClip());
        // reset mClip in case there was one.
        mClip = null;

        // replace whatever current layers we have with this.
        mLayers.clear();
        mLayers.add(baseLayer);

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

    public boolean clip(Shape shape, int regionOp) {
        // Simple case of intersect with existing layers.
        // Because Graphics2D#setClip works a bit peculiarly, we optimize
        // the case of clipping by intersection, as it's supported natively.
        if (regionOp == Region.Op.INTERSECT.nativeInt && mLayers.size() > 0) {
            for (Layer layer : mLayers) {
                layer.clip(shape);
            }

            Shape currentClip = getClip();
            return currentClip != null && currentClip.getBounds().isEmpty() == false;
        }

        Area area = null;

        if (regionOp == Region.Op.REPLACE.nativeInt) {
            area = new Area(shape);
        } else {
            area = Region_Delegate.combineShapes(getClip(), shape, regionOp);
        }

        assert area != null;

        if (mLayers.size() > 0) {
            if (area != null) {
                for (Layer layer : mLayers) {
                    layer.setClip(area);
                }
            }

            Shape currentClip = getClip();
            return currentClip != null && currentClip.getBounds().isEmpty() == false;
        } else {
            if (area != null) {
                mClip = area;
            } else {
                mClip = new Area();
            }

            return mClip.getBounds().isEmpty() == false;
        }
    }

    public boolean clipRect(float left, float top, float right, float bottom, int regionOp) {
        return clip(new Rectangle2D.Float(left, top, right - left, bottom - top), regionOp);
    }

    /**
     * Returns the current clip, or null if none have been setup.
     */
    public Shape getClip() {
        if (mLayers.size() > 0) {
            // they all have the same clip
            return mLayers.get(0).getGraphics().getClip();
        } else {
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
        // the current snapshot may not have a mLocalLayer (ie it was created on save() instead
        // of saveLayer(), but that doesn't mean there's no layer.
        // mLayers however saves all the information we need (flags).
        if (mLayers.size() == 1) {
            // no layer, only base layer. easy case.
            drawInLayer(mLayers.get(0), drawable, paint, compositeOnly, forceSrcMode);
        } else {
            // draw in all the layers until the layer save flags tells us to stop (ie drawing
            // in that layer is limited to the layer itself.
            int flags;
            int i = mLayers.size() - 1;

            do {
                Layer layer = mLayers.get(i);

                drawInLayer(layer, drawable, paint, compositeOnly, forceSrcMode);

                // then go to previous layer, only if there are any left, and its flags
                // doesn't restrict drawing to the layer itself.
                i--;
                flags = layer.getFlags();
            } while (i >= 0 && (flags & Canvas.CLIP_TO_LAYER_SAVE_FLAG) == 0);
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
            if (mLocalLayer != null) {
                // prepare to blit the layers in which we have draw, in the layer beneath
                // them, starting with the top one (which is the current local layer).
                int i = mLayers.size() - 1;
                int flags;
                do {
                    Layer dstLayer = mLayers.get(i - 1);

                    restoreLayer(dstLayer);

                    flags = dstLayer.getFlags();
                    i--;
                } while (i > 0 && (flags & Canvas.CLIP_TO_LAYER_SAVE_FLAG) == 0);
            }

            // if this snapshot does not save everything, then set the previous snapshot
            // to this snapshot content

            // didn't save the matrix? set the current matrix on the previous snapshot
            if ((mFlags & Canvas.MATRIX_SAVE_FLAG) == 0) {
                AffineTransform mtx = getTransform();
                for (Layer layer : mPrevious.mLayers) {
                    layer.getGraphics().setTransform(mtx);
                }
            }

            // didn't save the clip? set the current clip on the previous snapshot
            if ((mFlags & Canvas.CLIP_SAVE_FLAG) == 0) {
                Shape clip = getClip();
                for (Layer layer : mPrevious.mLayers) {
                    layer.setClip(clip);
                }
            }
        }

        for (Layer layer : mLayers) {
            layer.getGraphics().dispose();
        }

        return mPrevious;
    }

    private void restoreLayer(Layer dstLayer) {

        Graphics2D baseGfx = dstLayer.getImage().createGraphics();

        // if the layer contains an original copy this means the flags
        // didn't restrict drawing to the local layer and we need to make sure the
        // layer bounds in the layer beneath didn't receive any drawing.
        // so we use the originalCopy to erase the new drawings in there.
        BufferedImage originalCopy = dstLayer.getOriginalCopy();
        if (originalCopy != null) {
            Graphics2D g = (Graphics2D) baseGfx.create();
            g.setComposite(AlphaComposite.Src);

            g.drawImage(originalCopy,
                    mLayerBounds.left, mLayerBounds.top, mLayerBounds.right, mLayerBounds.bottom,
                    0, 0, mLayerBounds.width(), mLayerBounds.height(),
                    null);
            g.dispose();
        }

        // now draw put the content of the local layer onto the layer,
        // using the paint information
        Graphics2D g = createCustomGraphics(baseGfx, mLocalLayerPaint,
                true /*alphaOnly*/, false /*forceSrcMode*/);

        g.drawImage(mLocalLayer.getImage(),
                mLayerBounds.left, mLayerBounds.top, mLayerBounds.right, mLayerBounds.bottom,
                mLayerBounds.left, mLayerBounds.top, mLayerBounds.right, mLayerBounds.bottom,
                null);
        g.dispose();

        baseGfx.dispose();
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
            Shader_Delegate shaderDelegate = paint.getShader();
            if (shaderDelegate != null) {
                if (shaderDelegate.isSupported()) {
                    java.awt.Paint shaderPaint = shaderDelegate.getJavaPaint();
                    assert shaderPaint != null;
                    if (shaderPaint != null) {
                        g.setPaint(shaderPaint);
                        customShader = true;
                    }
                } else {
                    Bridge.getLog().fidelityWarning(LayoutLog.TAG_SHADER,
                            shaderDelegate.getSupportMessage(),
                            null /*throwable*/, null /*data*/);
                }
            }

            // if no shader, use the paint color
            if (customShader == false) {
                g.setColor(new Color(paint.getColor(), true /*hasAlpha*/));
            }

            // set the stroke
            g.setStroke(paint.getJavaStroke());
        }

        // the alpha for the composite. Always opaque if the normal paint color is used since
        // it contains the alpha
        int alpha = (compositeOnly || customShader) ? paint.getAlpha() : 0xFF;

        if (forceSrcMode) {
            g.setComposite(AlphaComposite.getInstance(
                    AlphaComposite.SRC, (float) alpha / 255.f));
        } else {
            boolean customXfermode = false;
            Xfermode_Delegate xfermodeDelegate = paint.getXfermode();
            if (xfermodeDelegate != null) {
                if (xfermodeDelegate.isSupported()) {
                    Composite composite = xfermodeDelegate.getComposite(alpha);
                    assert composite != null;
                    if (composite != null) {
                        g.setComposite(composite);
                        customXfermode = true;
                    }
                } else {
                    Bridge.getLog().fidelityWarning(LayoutLog.TAG_XFERMODE,
                            xfermodeDelegate.getSupportMessage(),
                            null /*throwable*/, null /*data*/);
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
