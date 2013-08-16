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
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

/**
 * This transition tracks changes to the visibility of target views in the
 * start and end scenes and fades views in or out when they become visible
 * or non-visible. Visibility is determined by both the
 * {@link View#setVisibility(int)} state of the view as well as whether it
 * is parented in the current view hierarchy.
 */
public class Fade extends Visibility {

    private static boolean DBG = Transition.DBG && false;

    private static final String LOG_TAG = "Fade";
    private static final String PROPNAME_ALPHA = "android:fade:alpha";
    private static final String PROPNAME_SCREEN_X = "android:fade:screenX";
    private static final String PROPNAME_SCREEN_Y = "android:fade:screenY";

    /**
     * Fading mode used in {@link #Fade(int)} to make the transition
     * operate on targets that are appearing. Maybe be combined with
     * {@link #OUT} to fade both in and out.
     */
    public static final int IN = 0x1;
    /**
     * Fading mode used in {@link #Fade(int)} to make the transition
     * operate on targets that are disappearing. Maybe be combined with
     * {@link #IN} to fade both in and out.
     */
    public static final int OUT = 0x2;

    private int mFadingMode;

    /**
     * Constructs a Fade transition that will fade targets in and out.
     */
    public Fade() {
        this(IN | OUT);
    }

    /**
     * Constructs a Fade transition that will fade targets in
     * and/or out, according to the value of fadingMode.
     *
     * @param fadingMode The behavior of this transition, a combination of
     * {@link #IN} and {@link #OUT}.
     */
    public Fade(int fadingMode) {
        mFadingMode = fadingMode;
    }

    /**
     * Utility method to handle creating and running the Animator.
     */
    private Animator createAnimation(View view, float startAlpha, float endAlpha,
            AnimatorListenerAdapter listener) {
        if (startAlpha == endAlpha) {
            // run listener if we're noop'ing the animation, to get the end-state results now
            if (listener != null) {
                listener.onAnimationEnd(null);
            }
            return null;
        }
        final ObjectAnimator anim = ObjectAnimator.ofFloat(view, "alpha", startAlpha, endAlpha);
        if (listener != null) {
            anim.addListener(listener);
            anim.addPauseListener(listener);
        }
        return anim;
    }

    @Override
    protected void captureValues(TransitionValues values, boolean start) {
        super.captureValues(values, start);
        float alpha = values.view.getAlpha();
        values.values.put(PROPNAME_ALPHA, alpha);
        int[] loc = new int[2];
        values.view.getLocationOnScreen(loc);
        values.values.put(PROPNAME_SCREEN_X, loc[0]);
        values.values.put(PROPNAME_SCREEN_Y, loc[1]);
    }

    @Override
    protected Animator play(ViewGroup sceneRoot, TransitionValues startValues,
            TransitionValues endValues) {
        Animator animator = super.play(sceneRoot, startValues, endValues);
        if (animator == null && startValues != null && endValues != null) {
            boolean endVisible = isVisible(endValues);
            final View endView = endValues.view;
            float endAlpha = endView.getAlpha();
            float startAlpha = (Float) startValues.values.get(PROPNAME_ALPHA);
            if ((endVisible && startAlpha < endAlpha && (mFadingMode & Fade.IN) != 0) ||
                    (!endVisible && startAlpha > endAlpha && (mFadingMode & Fade.OUT) != 0)) {
                animator = createAnimation(endView, startAlpha, endAlpha, null);
            }
        }
        return animator;
    }

    @Override
    protected Animator appear(ViewGroup sceneRoot,
            TransitionValues startValues, int startVisibility,
            TransitionValues endValues, int endVisibility) {
        if ((mFadingMode & IN) != IN || endValues == null) {
            return null;
        }
        final View endView = endValues.view;
        // if alpha < 1, just fade it in from the current value
        if (endView.getAlpha() == 1.0f) {
            endView.setAlpha(0);
        }
        return createAnimation(endView, endView.getAlpha(), 1, null);
    }

    @Override
    protected Animator disappear(ViewGroup sceneRoot,
            TransitionValues startValues, int startVisibility,
            TransitionValues endValues, int endVisibility) {
        if ((mFadingMode & OUT) != OUT) {
            return null;
        }
        View view;
        View startView = (startValues != null) ? startValues.view : null;
        View endView = (endValues != null) ? endValues.view : null;
        if (DBG) {
            Log.d(LOG_TAG, "Fade.predisappear: startView, startVis, endView, endVis = " +
                        startView + ", " + startVisibility + ", " + endView + ", " + endVisibility);
        }
        View overlayView = null;
        View viewToKeep = null;
        if (endView == null || endView.getParent() == null) {
            // view was removed: add the start view to the Overlay
            view = startView;
            overlayView = view;
        } else {
            // visibility change
            if (endVisibility == View.INVISIBLE) {
                view = endView;
                viewToKeep = view;
            } else {
                // Becoming GONE
                if (startView == endView) {
                    view = endView;
                    viewToKeep = view;
                } else {
                    view = startView;
                    overlayView = view;
                }
            }
        }
        final int finalVisibility = endVisibility;
        // TODO: add automatic facility to Visibility superclass for keeping views around
        if (overlayView != null) {
            // TODO: Need to do this for general case of adding to overlay
            int screenX = (Integer) startValues.values.get(PROPNAME_SCREEN_X);
            int screenY = (Integer) startValues.values.get(PROPNAME_SCREEN_Y);
            int[] loc = new int[2];
            sceneRoot.getLocationOnScreen(loc);
            overlayView.offsetLeftAndRight((screenX - loc[0]) - overlayView.getLeft());
            overlayView.offsetTopAndBottom((screenY - loc[1]) - overlayView.getTop());
            sceneRoot.getOverlay().add(overlayView);
            // TODO: add automatic facility to Visibility superclass for keeping views around
            final float startAlpha = view.getAlpha();
            float endAlpha = 0;
            final View finalView = view;
            final View finalOverlayView = overlayView;
            final View finalViewToKeep = viewToKeep;
            final ViewGroup finalSceneRoot = sceneRoot;
            final AnimatorListenerAdapter endListener = new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    finalView.setAlpha(startAlpha);
                    // TODO: restore view offset from overlay repositioning
                    if (finalViewToKeep != null) {
                        finalViewToKeep.setVisibility(finalVisibility);
                    }
                    if (finalOverlayView != null) {
                        finalSceneRoot.getOverlay().remove(finalOverlayView);
                    }
                }

                @Override
                public void onAnimationPause(Animator animation) {
                    if (finalOverlayView != null) {
                        finalSceneRoot.getOverlay().remove(finalOverlayView);
                    }
                }

                @Override
                public void onAnimationResume(Animator animation) {
                    if (finalOverlayView != null) {
                        finalSceneRoot.getOverlay().add(finalOverlayView);
                    }
                }
            };
            return createAnimation(view, startAlpha, endAlpha, endListener);
        }
        if (viewToKeep != null) {
            // TODO: find a different way to do this, like just changing the view to be
            // VISIBLE for the duration of the transition
            viewToKeep.setVisibility((View.VISIBLE));
            // TODO: add automatic facility to Visibility superclass for keeping views around
            final float startAlpha = view.getAlpha();
            float endAlpha = 0;
            final View finalView = view;
            final View finalOverlayView = overlayView;
            final View finalViewToKeep = viewToKeep;
            final ViewGroup finalSceneRoot = sceneRoot;
            final AnimatorListenerAdapter endListener = new AnimatorListenerAdapter() {
                boolean mCanceled = false;
                float mPausedAlpha = -1;

                @Override
                public void onAnimationPause(Animator animation) {
                    if (finalViewToKeep != null && !mCanceled) {
                        finalViewToKeep.setVisibility(finalVisibility);
                    }
                    mPausedAlpha = finalView.getAlpha();
                    finalView.setAlpha(startAlpha);
                }

                @Override
                public void onAnimationResume(Animator animation) {
                    if (finalViewToKeep != null && !mCanceled) {
                        finalViewToKeep.setVisibility(View.VISIBLE);
                    }
                    finalView.setAlpha(mPausedAlpha);
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                    mCanceled = true;
                    if (mPausedAlpha >= 0) {
                        finalView.setAlpha(mPausedAlpha);
                    }
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    if (!mCanceled) {
                        finalView.setAlpha(startAlpha);
                    }
                    // TODO: restore view offset from overlay repositioning
                    if (finalViewToKeep != null && !mCanceled) {
                        finalViewToKeep.setVisibility(finalVisibility);
                    }
                    if (finalOverlayView != null) {
                        finalSceneRoot.getOverlay().remove(finalOverlayView);
                    }
                }
            };
            return createAnimation(view, startAlpha, endAlpha, endListener);
        }
        return null;
    }

}