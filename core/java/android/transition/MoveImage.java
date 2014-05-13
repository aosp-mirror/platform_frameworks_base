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
package android.transition;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.RectEvaluator;
import android.animation.TypeEvaluator;
import android.animation.ValueAnimator;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.FloatMath;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroupOverlay;
import android.view.ViewParent;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.Map;

/**
 * Transitions ImageViews, including size, scaleType, and matrix. The ImageView drawable
 * must remain the same between both start and end states, but the
 * {@link ImageView#setScaleType(android.widget.ImageView.ScaleType)} may
 * differ.
 */
public class MoveImage extends Transition {
    private static final String TAG = "MoveImage";
    private static final String PROPNAME_MATRIX = "android:moveImage:matrix";
    private static final String PROPNAME_BOUNDS = "android:moveImage:bounds";
    private static final String PROPNAME_CLIP = "android:moveImage:clip";
    private static final String PROPNAME_DRAWABLE = "android:moveImage:drawable";

    private int[] mTempLoc = new int[2];

    private static final String[] sTransitionProperties = {
            PROPNAME_MATRIX,
            PROPNAME_BOUNDS,
            PROPNAME_CLIP,
            PROPNAME_DRAWABLE,
    };

    private void captureValues(TransitionValues transitionValues) {
        View view = transitionValues.view;
        if (!(view instanceof ImageView) || view.getVisibility() != View.VISIBLE) {
            return;
        }
        ImageView imageView = (ImageView) view;
        Drawable drawable = imageView.getDrawable();
        if (drawable == null) {
            return;
        }
        Map<String, Object> values = transitionValues.values;
        values.put(PROPNAME_DRAWABLE, drawable);

        ViewGroup parent = (ViewGroup) view.getParent();
        parent.getLocationInWindow(mTempLoc);
        int paddingLeft = view.getPaddingLeft();
        int paddingTop = view.getPaddingTop();
        int paddingRight = view.getPaddingRight();
        int paddingBottom = view.getPaddingBottom();
        int left = mTempLoc[0] + paddingLeft + view.getLeft() + Math.round(view.getTranslationX());
        int top = mTempLoc[1] + paddingTop + view.getTop() + Math.round(view.getTranslationY());
        int right = left + view.getWidth() - paddingRight - paddingLeft;
        int bottom = top + view.getHeight() - paddingTop - paddingBottom;

        Rect bounds = new Rect(left, top, right, bottom);
        values.put(PROPNAME_BOUNDS, bounds);
        Matrix matrix = getMatrix(imageView);
        values.put(PROPNAME_MATRIX, matrix);
        values.put(PROPNAME_CLIP, findClip(imageView));
    }

    @Override
    public void captureStartValues(TransitionValues transitionValues) {
        captureValues(transitionValues);
    }

    @Override
    public void captureEndValues(TransitionValues transitionValues) {
        captureValues(transitionValues);
    }

    @Override
    public String[] getTransitionProperties() {
        return sTransitionProperties;
    }

    /**
     * Creates an Animator for ImageViews moving, changing dimensions, and/or changing
     * {@link android.widget.ImageView.ScaleType}.
     * @param sceneRoot The root of the transition hierarchy.
     * @param startValues The values for a specific target in the start scene.
     * @param endValues The values for the target in the end scene.
     * @return An Animator to move an ImageView or null if the View is not an ImageView,
     * the Drawable changed, the View is not VISIBLE, or there was no change.
     */
    @Override
    public Animator createAnimator(ViewGroup sceneRoot, TransitionValues startValues,
            TransitionValues endValues) {
        if (startValues == null || endValues == null
                || startValues.values.get(PROPNAME_BOUNDS) == null
                || endValues.values.get(PROPNAME_BOUNDS) == null
                || startValues.values.get(PROPNAME_DRAWABLE)
                        != endValues.values.get(PROPNAME_DRAWABLE)) {
            return null;
        }
        ArrayList<PropertyValuesHolder> changes = new ArrayList<PropertyValuesHolder>();

        Matrix startMatrix = (Matrix) startValues.values.get(PROPNAME_MATRIX);
        Matrix endMatrix = (Matrix) endValues.values.get(PROPNAME_MATRIX);

        if (startMatrix != null && !startMatrix.equals(endMatrix)) {
            changes.add(PropertyValuesHolder.ofObject(MatrixClippedDrawable.MATRIX_PROPERTY,
                    new MatrixEvaluator(), startMatrix, endMatrix));
        }

        sceneRoot.getLocationInWindow(mTempLoc);
        int rootX = mTempLoc[0];
        int rootY = mTempLoc[1];
        final ImageView imageView = (ImageView) endValues.view;

        Drawable drawable = imageView.getDrawable();

        Rect startBounds = new Rect((Rect) startValues.values.get(PROPNAME_BOUNDS));
        Rect endBounds = new Rect((Rect) endValues.values.get(PROPNAME_BOUNDS));
        startBounds.offset(-rootX, -rootY);
        endBounds.offset(-rootX, -rootY);

        if (!startBounds.equals(endBounds)) {
            changes.add(PropertyValuesHolder.ofObject("bounds", new RectEvaluator(new Rect()),
                    startBounds, endBounds));
        }

        Rect startClip = (Rect) startValues.values.get(PROPNAME_CLIP);
        Rect endClip = (Rect) endValues.values.get(PROPNAME_CLIP);
        if (startClip != null || endClip != null) {
            startClip = nonNullClip(startClip, sceneRoot, rootX, rootY);
            endClip = nonNullClip(endClip, sceneRoot, rootX, rootY);

            expandClip(startBounds, startMatrix, startClip, endClip);
            expandClip(endBounds, endMatrix, endClip, startClip);
            boolean clipped = !startClip.contains(startBounds) || !endClip.contains(endBounds);
            if (!clipped) {
                startClip = null;
            } else if (!startClip.equals(endClip)) {
                changes.add(PropertyValuesHolder.ofObject(MatrixClippedDrawable.CLIP_PROPERTY,
                        new RectEvaluator(), startClip, endClip));
            }
        }

        if (changes.isEmpty()) {
            return null;
        }

        drawable = drawable.getConstantState().newDrawable();
        final MatrixClippedDrawable matrixClippedDrawable = new MatrixClippedDrawable(drawable);
        final ImageView overlayImage = new ImageView(imageView.getContext());
        final ViewGroupOverlay overlay = sceneRoot.getOverlay();
        overlay.add(overlayImage);
        overlayImage.setLeft(0);
        overlayImage.setTop(0);
        overlayImage.setRight(sceneRoot.getWidth());
        overlayImage.setBottom(sceneRoot.getBottom());
        overlayImage.setScaleType(ImageView.ScaleType.MATRIX);
        overlayImage.setImageDrawable(matrixClippedDrawable);
        matrixClippedDrawable.setMatrix(startMatrix);
        matrixClippedDrawable.setBounds(startBounds);
        matrixClippedDrawable.setClipRect(startClip);

        imageView.setVisibility(View.INVISIBLE);
        ObjectAnimator animator = ObjectAnimator.ofPropertyValuesHolder(matrixClippedDrawable,
                changes.toArray(new PropertyValuesHolder[changes.size()]));

        AnimatorListenerAdapter listener = new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                imageView.setVisibility(View.VISIBLE);
                overlay.remove(overlayImage);
            }

            @Override
            public void onAnimationPause(Animator animation) {
                imageView.setVisibility(View.VISIBLE);
                overlayImage.setVisibility(View.INVISIBLE);
            }

            @Override
            public void onAnimationResume(Animator animation) {
                imageView.setVisibility(View.INVISIBLE);
                overlayImage.setVisibility(View.VISIBLE);
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                onAnimationEnd(animation);
            }
        };

        animator.addListener(listener);
        animator.addPauseListener(listener);

        return animator;
    }

    private static Rect nonNullClip(Rect clip, ViewGroup sceneRoot, int rootX, int rootY) {
        if (clip != null) {
            clip = new Rect(clip);
            clip.offset(-rootX, -rootY);
        } else {
            clip = new Rect(0, 0, sceneRoot.getWidth(), sceneRoot.getHeight());
        }
        return clip;
    }

    private static void expandClip(Rect bounds, Matrix matrix, Rect clip, Rect otherClip) {
        RectF boundsF = new RectF(bounds);
        if (matrix != null) {
            matrix.mapRect(boundsF);
        }
        clip.left = expandMinDimension(boundsF.left, clip.left, otherClip.left);
        clip.top = expandMinDimension(boundsF.top, clip.top, otherClip.top);
        clip.right = expandMaxDimension(boundsF.right, clip.right, otherClip.right);
        clip.bottom = expandMaxDimension(boundsF.bottom, clip.bottom, otherClip.bottom);
    }

    private static int expandMinDimension(float boundsDimension, int clipDimension,
            int otherClipDimension) {
        if (clipDimension > boundsDimension) {
            // Already clipped in that dimension, return the clipped value
            return clipDimension;
        }
        return Math.min(clipDimension, otherClipDimension);
    }

    private static int expandMaxDimension(float boundsDimension, int clipDimension,
            int otherClipDimension) {
        return -expandMinDimension(-boundsDimension, -clipDimension, -otherClipDimension);
    }

    private static Matrix getMatrix(ImageView imageView) {
        Drawable drawable = imageView.getDrawable();
        int drawableWidth = drawable.getIntrinsicWidth();
        int drawableHeight = drawable.getIntrinsicHeight();
        ImageView.ScaleType scaleType = imageView.getScaleType();
        Matrix matrix;
        if (drawableWidth <= 0 || drawableHeight <= 0) {
            matrix = null;
        } else if (scaleType == ImageView.ScaleType.FIT_XY) {
            matrix = new Matrix();
            float scaleX = imageView.getWidth();
            scaleX /= drawableWidth;
            float scaleY = imageView.getHeight();
            scaleY /= drawableHeight;
            matrix.setScale(scaleX, scaleY);
        } else {
            matrix = new Matrix(imageView.getImageMatrix());
        }
        return matrix;
    }

    private Rect findClip(ImageView imageView) {
        if (imageView.getCropToPadding()) {
            Rect clip = getClip(imageView);
            clip.left += imageView.getPaddingLeft();
            clip.right -= imageView.getPaddingRight();
            clip.top += imageView.getPaddingTop();
            clip.bottom -= imageView.getPaddingBottom();
            return clip;
        } else {
            View view = imageView;
            ViewParent viewParent;
            while ((viewParent = view.getParent()) instanceof ViewGroup) {
                ViewGroup viewGroup = (ViewGroup) viewParent;
                if (viewGroup.getClipChildren()) {
                    Rect clip = getClip(view);
                    return clip;
                }
                view = viewGroup;
            }
        }
        return null;
    }

    private Rect getClip(View clipView) {
        Rect clipBounds = clipView.getClipBounds();
        if (clipBounds == null) {
            clipBounds = new Rect(clipView.getLeft(), clipView.getTop(),
                    clipView.getRight(), clipView.getBottom());
        }

        ViewParent parent = clipView.getParent();
        if (parent instanceof ViewGroup) {
            ViewGroup parentViewGroup = (ViewGroup) parent;
            parentViewGroup.getLocationInWindow(mTempLoc);
            clipBounds.offset(mTempLoc[0], mTempLoc[1]);
        }

        return clipBounds;
    }

    @Override
    public Transition clone() {
        MoveImage clone = (MoveImage) super.clone();
        clone.mTempLoc = new int[2];
        return clone;
    }

    private static class MatrixEvaluator implements TypeEvaluator<Matrix> {
        static final Matrix sIdentity = new Matrix();
        float[] mTempStartValues = new float[9];
        float[] mTempEndValues = new float[9];
        Matrix mTempMatrix = new Matrix();

        @Override
        public Matrix evaluate(float fraction, Matrix startValue, Matrix endValue) {
            if (startValue == null && endValue == null) {
                return null;
            }
            if (startValue == null) {
                startValue = sIdentity;
            } else if (endValue == null) {
                endValue = sIdentity;
            }
            startValue.getValues(mTempStartValues);
            endValue.getValues(mTempEndValues);
            for (int i = 0; i < 9; i++) {
                float diff = mTempEndValues[i] - mTempStartValues[i];
                mTempEndValues[i] = mTempStartValues[i] + (fraction * diff);
            }
            mTempMatrix.setValues(mTempEndValues);
            return mTempMatrix;
        }
    }
}
