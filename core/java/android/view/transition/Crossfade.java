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
import android.util.ArrayMap;
import android.util.Log;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

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

    @Override
    protected boolean prePlay(ViewGroup sceneRoot, TransitionValues startValues,
            TransitionValues endValues) {
        if (startValues == null || endValues == null) {
            return false;
        }
        final View view = startValues.view;
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
            view.getOverlay().add(endDrawable);
            view.getOverlay().add(startDrawable);
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
        if (Transition.DBG) {
            Log.d(LOG_TAG, "Crossfade: created anim " + anim + " for start, end values " +
                    startValues + ", " + endValues);
        }
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                view.getOverlay().remove(startDrawable);
                view.getOverlay().remove(endDrawable);
            }
        });
        AnimatorSet set = new AnimatorSet();
        set.playTogether(anim);
        if (!startBounds.equals(endBounds)) {
            if (Transition.DBG) {
                Log.d(LOG_TAG, "animating from startBounds to endBounds: " +
                        startBounds + ", " + endBounds);
            }
            Animator anim2 = ObjectAnimator.ofObject(startDrawable, "bounds",
                    sRectEvaluator, startBounds, endBounds);
            Animator anim3 = ObjectAnimator.ofObject(endDrawable, "bounds",
                    sRectEvaluator, startBounds, endBounds);
            set.playTogether(anim2);
            set.playTogether(anim3);
        }
        return set;
    }

    @Override
    protected void captureValues(TransitionValues values, boolean start) {
        View view = values.view;
        values.values.put(PROPNAME_BOUNDS, new Rect(0, 0,
                view.getWidth(), view.getHeight()));

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
        drawable.setBounds(view.getLeft(), view.getTop(), view.getRight(), view.getBottom());
        values.values.put(PROPNAME_DRAWABLE, drawable);
    }

}
