/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.view;

import com.android.annotations.NonNull;
import com.android.layoutlib.bridge.android.BridgeContext;
import com.android.resources.Density;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap_Delegate;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Path_Delegate;
import android.graphics.Rect;
import android.graphics.Region.Op;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.animation.Transformation;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * Delegate used to provide new implementation of a select few methods of {@link ViewGroup}
 * <p/>
 * Through the layoutlib_create tool, the original  methods of ViewGroup have been replaced by calls
 * to methods of the same name in this delegate class.
 */
public class ViewGroup_Delegate {

    /**
     * Overrides the original drawChild call in ViewGroup to draw the shadow.
     */
    @LayoutlibDelegate
    /*package*/ static boolean drawChild(ViewGroup thisVG, Canvas canvas, View child,
            long drawingTime) {
        boolean retVal = thisVG.drawChild_Original(canvas, child, drawingTime);
        if (child.getZ() > thisVG.getZ()) {
            ViewOutlineProvider outlineProvider = child.getOutlineProvider();
            Outline outline = new Outline();
            outlineProvider.getOutline(child, outline);

            if (outline.mPath != null || (outline.mRect != null && !outline.mRect.isEmpty())) {
                int restoreTo = transformCanvas(thisVG, canvas, child);
                drawShadow(thisVG, canvas, child, outline);
                canvas.restoreToCount(restoreTo);
            }
        }
        return retVal;
    }

    private static void drawShadow(ViewGroup parent, Canvas canvas, View child,
            Outline outline) {
        BufferedImage shadow = null;
        int x = 0;
        if (outline.mRect != null) {
            Shadow s = getRectShadow(parent, canvas, child, outline);
            shadow = s.mShadow;
            x = -s.mShadowWidth;
        } else if (outline.mPath != null) {
            shadow = getPathShadow(child, outline, canvas);
        }
        if (shadow == null) {
            return;
        }
        Bitmap bitmap = Bitmap_Delegate.createBitmap(shadow, false,
                Density.getEnum(canvas.getDensity()));
        Rect clipBounds = canvas.getClipBounds();
        Rect newBounds = new Rect(clipBounds);
        newBounds.left = newBounds.left + x;
        canvas.clipRect(newBounds, Op.REPLACE);
        canvas.drawBitmap(bitmap, x, 0, null);
        canvas.clipRect(clipBounds, Op.REPLACE);
    }

    private static Shadow getRectShadow(ViewGroup parent, Canvas canvas, View child,
            Outline outline) {
        BufferedImage shadow;
        Rect clipBounds = canvas.getClipBounds();
        if (clipBounds.isEmpty()) {
            return null;
        }
        float height = child.getZ() - parent.getZ();
        // Draw large shadow if difference in z index is more than 10dp
        float largeShadowThreshold = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f,
                getMetrics(child));
        boolean largeShadow = height > largeShadowThreshold;
        int shadowSize = largeShadow ? ShadowPainter.SHADOW_SIZE : ShadowPainter.SMALL_SHADOW_SIZE;
        shadow = new BufferedImage(clipBounds.width() + shadowSize, clipBounds.height(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = shadow.createGraphics();
        Rect rect = outline.mRect;
        if (largeShadow) {
            ShadowPainter.drawRectangleShadow(graphics,
                    rect.left + shadowSize, rect.top, rect.width(), rect.height());
        } else {
            ShadowPainter.drawSmallRectangleShadow(graphics,
                    rect.left + shadowSize, rect.top, rect.width(), rect.height());
        }
        graphics.dispose();
        return new Shadow(shadow, shadowSize);
    }

    @NonNull
    private static DisplayMetrics getMetrics(View view) {
        Context context = view.getContext();
        while (context instanceof ContextThemeWrapper) {
            context = ((ContextThemeWrapper) context).getBaseContext();
        }
        if (context instanceof BridgeContext) {
            return ((BridgeContext) context).getMetrics();
        }
        throw new RuntimeException("View " + view.getClass().getName() + " not created with the " +
                "right context");
    }

    private static BufferedImage getPathShadow(View child, Outline outline, Canvas canvas) {
        Rect clipBounds = canvas.getClipBounds();
        BufferedImage image = new BufferedImage(clipBounds.width(), clipBounds.height(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.draw(Path_Delegate.getDelegate(outline.mPath.mNativePath).getJavaShape());
        graphics.dispose();
        return ShadowPainter.createDropShadow(image, ((int) child.getZ()));
    }

    // Copied from android.view.View#draw(Canvas, ViewGroup, long) and removed code paths
    // which were never taken. Ideally, we should hook up the shadow code in the same method so
    // that we don't have to transform the canvas twice.
    private static int transformCanvas(ViewGroup thisVG, Canvas canvas, View child) {
        final int restoreTo = canvas.save();
        final boolean childHasIdentityMatrix = child.hasIdentityMatrix();
        int flags = thisVG.mGroupFlags;
        Transformation transformToApply = null;
        boolean concatMatrix = false;
        if ((flags & ViewGroup.FLAG_SUPPORT_STATIC_TRANSFORMATIONS) != 0) {
            final Transformation t = thisVG.getChildTransformation();
            final boolean hasTransform = thisVG.getChildStaticTransformation(child, t);
            if (hasTransform) {
                final int transformType = t.getTransformationType();
                transformToApply = transformType != Transformation.TYPE_IDENTITY ? t : null;
                concatMatrix = (transformType & Transformation.TYPE_MATRIX) != 0;
            }
        }
        concatMatrix |= childHasIdentityMatrix;

        child.computeScroll();
        int sx = child.mScrollX;
        int sy = child.mScrollY;

        canvas.translate(child.mLeft - sx, child.mTop - sy);
        float alpha = child.getAlpha() * child.getTransitionAlpha();

        if (transformToApply != null || alpha < 1 || !childHasIdentityMatrix) {
            if (transformToApply != null || !childHasIdentityMatrix) {
                int transX = -sx;
                int transY = -sy;

                if (transformToApply != null) {
                    if (concatMatrix) {
                        // Undo the scroll translation, apply the transformation matrix,
                        // then redo the scroll translate to get the correct result.
                        canvas.translate(-transX, -transY);
                        canvas.concat(transformToApply.getMatrix());
                        canvas.translate(transX, transY);
                    }
                    if (!childHasIdentityMatrix) {
                        canvas.translate(-transX, -transY);
                        canvas.concat(child.getMatrix());
                        canvas.translate(transX, transY);
                    }
                }

            }
        }
        return restoreTo;
    }

    private static class Shadow {
        public BufferedImage mShadow;
        public int mShadowWidth;

        public Shadow(BufferedImage shadow, int shadowWidth) {
            mShadow = shadow;
            mShadowWidth = shadowWidth;
        }

    }
}
