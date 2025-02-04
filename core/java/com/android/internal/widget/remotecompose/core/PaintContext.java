/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.core;

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.widget.remotecompose.core.operations.paint.PaintBundle;

/** Specify an abstract paint context used by RemoteCompose commands to draw */
public abstract class PaintContext {
    public static final int TEXT_MEASURE_MONOSPACE_WIDTH = 0x01;
    public static final int TEXT_MEASURE_FONT_HEIGHT = 0x02;
    public static final int TEXT_MEASURE_SPACES = 0x04;
    public static final int TEXT_COMPLEX = 0x08;

    protected @NonNull RemoteContext mContext;
    private boolean mNeedsRepaint = false;

    @NonNull
    public RemoteContext getContext() {
        return mContext;
    }

    /**
     * Returns true if the needsRepaint flag is set
     *
     * @return true if the document asks to be repainted
     */
    public boolean doesNeedsRepaint() {
        return mNeedsRepaint;
    }

    /** Clear the needsRepaint flag */
    public void clearNeedsRepaint() {
        mNeedsRepaint = false;
    }

    public PaintContext(@NonNull RemoteContext context) {
        this.mContext = context;
    }

    public void setContext(@NonNull RemoteContext context) {
        this.mContext = context;
    }

    /** convenience function to call matrixSave() */
    public void save() {
        matrixSave();
    }

    /** convenience function to call matrixRestore() */
    public void restore() {
        matrixRestore();
    }

    /** convenience function to call matrixSave() */
    public void saveLayer(float x, float y, float width, float height) {
        // TODO
        matrixSave();
    }

    /**
     * Draw a bitmap
     *
     * @param imageId
     * @param srcLeft
     * @param srcTop
     * @param srcRight
     * @param srcBottom
     * @param dstLeft
     * @param dstTop
     * @param dstRight
     * @param dstBottom
     * @param cdId
     */
    public abstract void drawBitmap(
            int imageId,
            int srcLeft,
            int srcTop,
            int srcRight,
            int srcBottom,
            int dstLeft,
            int dstTop,
            int dstRight,
            int dstBottom,
            int cdId);

    /**
     * scale the following commands
     *
     * @param scaleX horizontal scale factor
     * @param scaleY vertical scale factor
     */
    public abstract void scale(float scaleX, float scaleY);

    /**
     * Rotate the following commands
     *
     * @param translateX horizontal translation
     * @param translateY vertical translation
     */
    public abstract void translate(float translateX, float translateY);

    /**
     * Draw an arc
     *
     * @param left
     * @param top
     * @param right
     * @param bottom
     * @param startAngle
     * @param sweepAngle
     */
    public abstract void drawArc(
            float left, float top, float right, float bottom, float startAngle, float sweepAngle);

    /**
     * Draw a sector
     *
     * @param left
     * @param top
     * @param right
     * @param bottom
     * @param startAngle
     * @param sweepAngle
     */
    public abstract void drawSector(
            float left, float top, float right, float bottom, float startAngle, float sweepAngle);

    /**
     * Draw a bitmap
     *
     * @param id
     * @param left
     * @param top
     * @param right
     * @param bottom
     */
    public abstract void drawBitmap(int id, float left, float top, float right, float bottom);

    /**
     * Draw a circle
     *
     * @param centerX
     * @param centerY
     * @param radius
     */
    public abstract void drawCircle(float centerX, float centerY, float radius);

    /**
     * Draw a line
     *
     * @param x1
     * @param y1
     * @param x2
     * @param y2
     */
    public abstract void drawLine(float x1, float y1, float x2, float y2);

    /**
     * Draw an oval
     *
     * @param left
     * @param top
     * @param right
     * @param bottom
     */
    public abstract void drawOval(float left, float top, float right, float bottom);

    /**
     * Draw a path
     *
     * @param id the path id
     * @param start starting point of the path where we start drawing it
     * @param end ending point of the path where we stop drawing it
     */
    public abstract void drawPath(int id, float start, float end);

    /**
     * Draw a rectangle
     *
     * @param left left coordinate of the rectangle
     * @param top top coordinate of the rectangle
     * @param right right coordinate of the rectangle
     * @param bottom bottom coordinate of the rectangle
     */
    public abstract void drawRect(float left, float top, float right, float bottom);

    /** this caches the paint to a paint stack */
    public abstract void savePaint();

    /** This restores the paint form the paint stack */
    public abstract void restorePaint();

    /**
     * draw a round rect
     *
     * @param left left coordinate of the rectangle
     * @param top top coordinate of the rectangle
     * @param right right coordinate of the rectangle
     * @param bottom bottom coordinate of the rectangle
     * @param radiusX horizontal radius of the rounded corner
     * @param radiusY vertical radius of the rounded corner
     */
    public abstract void drawRoundRect(
            float left, float top, float right, float bottom, float radiusX, float radiusY);

    /**
     * Draw the text glyphs on the provided path
     *
     * @param textId id of the text
     * @param pathId id of the path
     * @param hOffset horizontal offset
     * @param vOffset vertical offset
     */
    public abstract void drawTextOnPath(int textId, int pathId, float hOffset, float vOffset);

    /**
     * Return the dimensions (left, top, right, bottom). Relative to a drawTextRun x=0, y=0;
     *
     * @param textId
     * @param start
     * @param end if end is -1 it means the whole string
     * @param flags how to measure:
     *     <ul>
     *       <li>TEXT_MEASURE_MONOSPACE_WIDTH - measure as a monospace font
     *       <li>TEXT_MEASURE_FULL_HEIGHT - measure bounds of the given string using the max ascend
     *           and descent of the font (not just of the measured text).
     *       <li>TEXT_MEASURE_SPACES - make sure to include leading/trailing spaces in the measure
     *       <li>TEXT_MEASURE_COMPLEX - complex text
     *     </ul>
     *
     * @param bounds the bounds (left, top, right, bottom)
     */
    public abstract void getTextBounds(
            int textId, int start, int end, int flags, @NonNull float[] bounds);

    /**
     * Compute complex text layout
     *
     * @param textId
     * @param start
     * @param end if end is -1 it means the whole string
     * @param alignment draw the text aligned start/center/end in the available space if > text
     *     length
     * @param overflow overflow behavior when text length > max width
     * @param maxLines maximum number of lines to display
     * @param maxWidth maximum width to layout the text
     * @param flags how to measure:
     *     <ul>
     *       <li>TEXT_MEASURE_MONOSPACE_WIDTH - measure as a monospace font
     *       <li>TEXT_MEASURE_FULL_HEIGHT - measure bounds of the given string using the max ascend
     *           and descent of the font (not just of the measured text).
     *       <li>TEXT_MEASURE_SPACES - make sure to include leading/trailing spaces in the measure
     *       <li>TEXT_MEASURE_COMPLEX - complex text
     *     </ul>
     *
     * @return an instance of a ComputedTextLayout (typically if complex text drawing is used)
     */
    public abstract Platform.ComputedTextLayout layoutComplexText(
            int textId,
            int start,
            int end,
            int alignment,
            int overflow,
            int maxLines,
            float maxWidth,
            int flags);

    /**
     * Draw a text starting ast x,y
     *
     * @param textId reference to the text
     * @param start
     * @param end
     * @param contextStart
     * @param contextEnd
     * @param x
     * @param y
     * @param rtl
     */
    public abstract void drawTextRun(
            int textId,
            int start,
            int end,
            int contextStart,
            int contextEnd,
            float x,
            float y,
            boolean rtl);

    /**
     * Draw a complex text (multilines, etc.)
     *
     * @param computedTextLayout pre-computed text layout
     */
    public abstract void drawComplexText(Platform.ComputedTextLayout computedTextLayout);

    /**
     * Draw an interpolation between two paths
     *
     * @param path1Id
     * @param path2Id
     * @param tween 0.0 = is path1 1.0 is path2
     * @param start
     * @param stop
     */
    public abstract void drawTweenPath(
            int path1Id, int path2Id, float tween, float start, float stop);

    /**
     * Interpolate between two path and return the resulting path
     *
     * @param out the interpolated path
     * @param path1 start path
     * @param path2 end path
     * @param tween interpolation value from 0 (start path) to 1 (end path)
     */
    public abstract void tweenPath(int out, int path1, int path2, float tween);

    /**
     * This applies changes to the current paint
     *
     * @param mPaintData the list of changes
     */
    public abstract void applyPaint(@NonNull PaintBundle mPaintData);

    /**
     * Scale the rendering by scaleX and saleY (1.0 = no scale). Scaling is done about
     * centerX,centerY.
     *
     * @param scaleX
     * @param scaleY
     * @param centerX
     * @param centerY
     */
    public abstract void matrixScale(float scaleX, float scaleY, float centerX, float centerY);

    /**
     * Translate the rendering
     *
     * @param translateX
     * @param translateY
     */
    public abstract void matrixTranslate(float translateX, float translateY);

    /**
     * Skew the rendering
     *
     * @param skewX
     * @param skewY
     */
    public abstract void matrixSkew(float skewX, float skewY);

    /**
     * Rotate the rendering. Note rotates are cumulative.
     *
     * @param rotate angle to rotate
     * @param pivotX x-coordinate about which to rotate
     * @param pivotY y-coordinate about which to rotate
     */
    public abstract void matrixRotate(float rotate, float pivotX, float pivotY);

    /** Save the current state of the transform */
    public abstract void matrixSave();

    /** Restore the previously saved state of the transform */
    public abstract void matrixRestore();

    /**
     * Set the clip to a rectangle. Drawing outside the current clip region will have no effect
     *
     * @param left
     * @param top
     * @param right
     * @param bottom
     */
    public abstract void clipRect(float left, float top, float right, float bottom);

    /**
     * Clip based on a path.
     *
     * @param pathId
     * @param regionOp
     */
    public abstract void clipPath(int pathId, int regionOp);

    /**
     * Clip based ona round rect
     *
     * @param width
     * @param height
     * @param topStart
     * @param topEnd
     * @param bottomStart
     * @param bottomEnd
     */
    public abstract void roundedClipRect(
            float width,
            float height,
            float topStart,
            float topEnd,
            float bottomStart,
            float bottomEnd);

    /** Reset the paint */
    public abstract void reset();

    /**
     * Returns true if the context is in debug mode
     *
     * @return true if in debug mode, false otherwise
     */
    public boolean isDebug() {
        return mContext.isDebug();
    }

    /**
     * Returns true if layout animations are enabled
     *
     * @return true if animations are enabled, false otherwise
     */
    public boolean isAnimationEnabled() {
        return mContext.isAnimationEnabled();
    }

    /**
     * Utility function to log comments
     *
     * @param content the content to log
     */
    public void log(@NonNull String content) {
        System.out.println("[LOG] " + content);
    }

    /** Indicates the document needs to be repainted */
    public void needsRepaint() {
        mNeedsRepaint = true;
    }

    /**
     * Starts a graphics layer
     *
     * @param w
     * @param h
     */
    public abstract void startGraphicsLayer(int w, int h);

    /**
     * Starts a graphics layer
     *
     * @param scaleX
     * @param scaleY
     * @param rotationX
     * @param rotationY
     * @param rotationZ
     * @param shadowElevation
     * @param transformOriginX
     * @param transformOriginY
     * @param alpha
     * @param renderEffectId
     */
    public abstract void setGraphicsLayer(
            float scaleX,
            float scaleY,
            float rotationX,
            float rotationY,
            float rotationZ,
            float shadowElevation,
            float transformOriginX,
            float transformOriginY,
            float alpha,
            int renderEffectId);

    /** Ends a graphics layer */
    public abstract void endGraphicsLayer();

    public boolean isVisualDebug() {
        return mContext.isVisualDebug();
    }

    /**
     * Returns a String from an id
     *
     * @param textID
     * @return the string if found
     */
    public abstract @Nullable String getText(int textID);
}
