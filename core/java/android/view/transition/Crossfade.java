/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.view.transition;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.RectEvaluator;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOverlay;

import java.util.Map;

/**
 * This transition captures bitmap representations of target views before and
 * after the scene change and fades between them.
 *
 * <p>Note: This transition is not compatible with {@link TextureView}
 * or {@link SurfaceView}.</p>
 */
public class Crossfade extends Transition {
    // TODO: Add a hook that lets a Transition call user code to query whether it should run on
    // a given target view. This would save bitmap comparisons in this transition, for example.

    private static final String LOG_TAG = "Crossfade";

    private static final String PROPNAME_BITMAP = "android:crossfade:bitmap";
    private static final String PROPNAME_DRAWABLE = "android:crossfade:drawable";
    private static final String PROPNAME_BOUNDS = "android:crossfade:bounds";

    private static RectEvaluator sRectEvaluator = new RectEvaluator();

    private int mFadeBehavior = FADE_BEHAVIOR_REVEAL;
    private int mResizeBehavior = RESIZE_BEHAVIOR_SCALE;

    /**
     * Flag specifying that the fading animation should cross-fade
     * between the old and new representation of all affected target
     * views. This means that the old representation will fade out
     * while the new one fades in. This effect may work well on views
     * without solid backgrounds, such as TextViews.
     *
     * @see #setFadeBehavior(int)
     */
    public static final int FADE_BEHAVIOR_CROSSFADE = 0;
    /**
     * Flag specifying that the fading animation should reveal the
     * new representation of all affected target views. This means
     * that the old representation will fade out, gradually
     * revealing the new representation, which remains opaque
     * the whole time. This effect may work well on views
     * with solid backgrounds, such as ImageViews.
     *
     * @see #setFadeBehavior(int)
     */
    public static final int FADE_BEHAVIOR_REVEAL = 1;

    /**
     * Flag specifying that the transition should not animate any
     * changes in size between the old and new target views.
     * This means that no scaling will take place as a result of
     * this transition
     *
     * @see #setResizeBehavior(int)
     */
    public static final int RESIZE_BEHAVIOR_NONE = 0;
    /**
     * Flag specifying that the transition should animate any
     * changes in size between the old and new target views.
     * This means that the animation will scale the start/end
     * representations of affected views from the starting size
     * to the ending size over the course of the animation.
     * This effect may work well on images, but is not recommended
     * for text.
     *
     * @see #setResizeBehavior(int)
     */
    public static final int RESIZE_BEHAVIOR_SCALE = 1;

    // TODO: Add fade/resize behaviors to xml resources

    /**
     * Sets the type of fading animation that will be run, one of
     * {@link #FADE_BEHAVIOR_CROSSFADE} and {@link #FADE_BEHAVIOR_REVEAL}.
     *
     * @param fadeBehavior The type of fading animation to use when this
     * transition is run.
     */
    public void setFadeBehavior(int fadeBehavior) {
        if (fadeBehavior >= FADE_BEHAVIOR_CROSSFADE && fadeBehavior <= FADE_BEHAVIOR_REVEAL) {
            mFadeBehavior = fadeBehavior;
        }
    }

    public int getFadeBehavior() {
        return mFadeBehavior;
    }

    /**
     * Sets the type of resizing behavior that will be used during the
     * transition animation, one of {@link #RESIZE_BEHAVIOR_NONE} and
     * {@link #RESIZE_BEHAVIOR_SCALE}.
     *
     * @param resizeBehavior The type of resizing behavior to use when this
     * transition is run.
     */
    public void setResizeBehavior(int resizeBehavior) {
        if (resizeBehavior >= RESIZE_BEHAVIOR_NONE && resizeBehavior <= RESIZE_BEHAVIOR_SCALE) {
            mResizeBehavior = resizeBehavior;
        }
    }

    public int getResizeBehavior() {
        return mResizeBehavior;
    }

    @Override
    protected boolean setup(ViewGroup sceneRoot, TransitionValues startValues,
            TransitionValues endValues) {
        if (startValues == null || endValues == null) {
            return false;
        }
        final View view = endValues.view;
        Map<String, Object> startVals = startValues.values;
        Map<String, Object> endVals = endValues.values;
        Bitmap startBitmap = (Bitmap) startVals.get(PROPNAME_BITMAP);
        Bitmap endBitmap = (Bitmap) endVals.get(PROPNAME_BITMAP);
        Drawable startDrawable = (Drawable) startVals.get(PROPNAME_DRAWABLE);
        Drawable endDrawable = (Drawable) endVals.get(PROPNAME_DRAWABLE);
        if (Transition.DBG) {
            Log.d(LOG_TAG, "StartBitmap.sameAs(endBitmap) = " + startBitmap.sameAs(endBitmap) +
                    " for start, end: " + startBitmap + ", " + endBitmap);
        }
        if (startDrawable != null && endDrawable != null && !startBitmap.sameAs(endBitmap)) {
            ViewOverlay overlay = (mFadeBehavior == FADE_BEHAVIOR_REVEAL) ?
                    view.getOverlay() : ((ViewGroup) view.getParent()).getOverlay();
            if (mFadeBehavior == FADE_BEHAVIOR_REVEAL) {
                overlay.add(endDrawable);
            }
            overlay.add(startDrawable);
            return true;
        } else {
            return false;
        }
    }

    @Override
    protected Animator play(ViewGroup sceneRoot, TransitionValues startValues,
            TransitionValues endValues) {
        if (startValues == null || endValues == null) {
            return null;
        }
        Map<String, Object> startVals = startValues.values;
        Map<String, Object> endVals = endValues.values;

        final View view = endValues.view;
        Rect startBounds = (Rect) startVals.get(PROPNAME_BOUNDS);
        Rect endBounds = (Rect) endVals.get(PROPNAME_BOUNDS);
        final BitmapDrawable startDrawable = (BitmapDrawable) startVals.get(PROPNAME_DRAWABLE);
        final BitmapDrawable endDrawable = (BitmapDrawable) endVals.get(PROPNAME_DRAWABLE);

        // The transition works by placing the end drawable under the start drawable and
        // gradually fading out the start drawable. So it's not really a cross-fade, but rather
        // a reveal of the end scene over time. Also, animate the bounds of both drawables
        // to mimic the change in the size of the view itself between scenes.
        ObjectAnimator anim = ObjectAnimator.ofInt(startDrawable, "alpha", 0);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                // TODO: some way to auto-invalidate views based on drawable changes? callbacks?
                view.invalidate(startDrawable.getBounds());
            }
        });
        ObjectAnimator anim1 = null;
        if (mFadeBehavior == FADE_BEHAVIOR_CROSSFADE) {
            anim1 = ObjectAnimator.ofFloat(view, View.ALPHA, 0, 1);
        }
        if (Transition.DBG) {
            Log.d(LOG_TAG, "Crossfade: created anim " + anim + " for start, end values " +
                    startValues + ", " + endValues);
        }
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                ViewOverlay overlay = (mFadeBehavior == FADE_BEHAVIOR_REVEAL) ?
                        view.getOverlay() : ((ViewGroup) view.getParent()).getOverlay();
                overlay.remove(startDrawable);
                if (mFadeBehavior == FADE_BEHAVIOR_REVEAL) {
                    overlay.remove(endDrawable);
                }
            }
        });
        AnimatorSet set = new AnimatorSet();
        set.playTogether(anim);
        if (anim1 != null) {
            set.playTogether(anim1);
        }
        if (mResizeBehavior == RESIZE_BEHAVIOR_SCALE && !startBounds.equals(endBounds)) {
            if (Transition.DBG) {
                Log.d(LOG_TAG, "animating from startBounds to endBounds: " +
                        startBounds + ", " + endBounds);
            }
            Animator anim2 = ObjectAnimator.ofObject(startDrawable, "bounds",
                    sRectEvaluator, startBounds, endBounds);
            set.playTogether(anim2);
            if (mResizeBehavior == RESIZE_BEHAVIOR_SCALE) {
                // TODO: How to handle resizing with a CROSSFADE (vs. REVEAL) effect
                // when we are animating the view directly?
                Animator anim3 = ObjectAnimator.ofObject(endDrawable, "bounds",
                        sRectEvaluator, startBounds, endBounds);
                set.playTogether(anim3);
            }
        }
        return set;
    }

    @Override
    protected void captureValues(TransitionValues values, boolean start) {
        View view = values.view;
        Rect bounds = new Rect(0, 0, view.getWidth(), view.getHeight());
        if (mFadeBehavior == FADE_BEHAVIOR_CROSSFADE) {
            bounds.offset(view.getLeft(), view.getTop());
        }
        values.values.put(PROPNAME_BOUNDS, bounds);

        if (Transition.DBG) {
            Log.d(LOG_TAG, "Captured bounds " + values.values.get(PROPNAME_BOUNDS) + ": start = " +
                    start);
        }
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(),
                Bitmap.Config.ARGB_8888);
        if (view instanceof TextureView) {
            bitmap = ((TextureView) view).getBitmap();
        } else {
            Canvas c = new Canvas(bitmap);
            view.draw(c);
        }
        values.values.put(PROPNAME_BITMAP, bitmap);
        // TODO: I don't have resources, can't call the non-deprecated method?
        BitmapDrawable drawable = new BitmapDrawable(bitmap);
        // TODO: lrtb will be wrong if the view has transXY set
        drawable.setBounds(bounds);
        values.values.put(PROPNAME_DRAWABLE, drawable);
    }

}
