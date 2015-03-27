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

import com.android.resources.Density;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.graphics.Bitmap;
import android.graphics.Bitmap_Delegate;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Path_Delegate;
import android.graphics.Rect;
import android.graphics.Region.Op;
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
        if (child.getZ() > thisVG.getZ()) {
            ViewOutlineProvider outlineProvider = child.getOutlineProvider();
            Outline outline = new Outline();
            outlineProvider.getOutline(child, outline);
            if (outline.mPath == null && outline.mRect == null) {
                // Sometimes, the bounds of the background drawable are not set until View.draw()
                // is called. So, we set the bounds manually and try to get the outline again.
                child.getBackground().setBounds(0, 0, child.mRight - child.mLeft,
                        child.mBottom - child.mTop);
                outlineProvider.getOutline(child, outline);
            }
            if (outline.mPath != null || (outline.mRect != null && !outline.mRect.isEmpty())) {
                int restoreTo = transformCanvas(thisVG, canvas, child);
                drawShadow(thisVG, canvas, child, outline);
                canvas.restoreToCount(restoreTo);
            }
        }
        return thisVG.drawChild_Original(canvas, child, drawingTime);
    }

    private static void drawShadow(ViewGroup parent, Canvas canvas, View child,
            Outline outline) {
        float elevation = getElevation(child, parent);
        if(outline.mRect != null) {
            RectShadowPainter.paintShadow(outline, elevation, canvas);
            return;
        }
        BufferedImage shadow = null;
        if (outline.mPath != null) {
            shadow = getPathShadow(outline, canvas, elevation);
        }
        if (shadow == null) {
            return;
        }
        Bitmap bitmap = Bitmap_Delegate.createBitmap(shadow, false,
                Density.getEnum(canvas.getDensity()));
        Rect clipBounds = canvas.getClipBounds();
        Rect newBounds = new Rect(clipBounds);
        newBounds.inset((int)-elevation, (int)-elevation);
        canvas.clipRect(newBounds, Op.REPLACE);
        canvas.drawBitmap(bitmap, 0, 0, null);
        canvas.clipRect(clipBounds, Op.REPLACE);
    }

    private static float getElevation(View child, ViewGroup parent) {
        return child.getZ() - parent.getZ();
    }

    private static BufferedImage getPathShadow(Outline outline, Canvas canvas, float elevation) {
        Rect clipBounds = canvas.getClipBounds();
        if (clipBounds.isEmpty()) {
          return null;
        }
        BufferedImage image = new BufferedImage(clipBounds.width(), clipBounds.height(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.draw(Path_Delegate.getDelegate(outline.mPath.mNativePath).getJavaShape());
        graphics.dispose();
        return ShadowPainter.createDropShadow(image, (int) elevation);
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
}
