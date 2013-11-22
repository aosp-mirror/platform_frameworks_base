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

package android.transition;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

/**
 * This transition tracks changes to the visibility of target views in the
 * start and end scenes and fades views in or out when they become visible
 * or non-visible. Visibility is determined by both the
 * {@link View#setVisibility(int)} state of the view as well as whether it
 * is parented in the current view hierarchy.
 *
 * <p>The ability of this transition to fade out a particular view, and the
 * way that that fading operation takes place, is based on
 * the situation of the view in the view hierarchy. For example, if a view was
 * simply removed from its parent, then the view will be added into a {@link
 * android.view.ViewGroupOverlay} while fading. If a visible view is
 * changed to be {@link View#GONE} or {@link View#INVISIBLE}, then the
 * visibility will be changed to {@link View#VISIBLE} for the duration of
 * the animation. However, if a view is in a hierarchy which is also altering
 * its visibility, the situation can be more complicated. In general, if a
 * view that is no longer in the hierarchy in the end scene still has a
 * parent (so its parent hierarchy was removed, but it was not removed from
 * its parent), then it will be left alone to avoid side-effects from
 * improperly removing it from its parent. The only exception to this is if
 * the previous {@link Scene} was
 * {@link Scene#getSceneForLayout(android.view.ViewGroup, int, android.content.Context)
 * created from a layout resource file}, then it is considered safe to un-parent
 * the starting scene view in order to fade it out.</p>
 *
 * <p>A Fade transition can be described in a resource file by using the
 * tag <code>fade</code>, along with the standard
 * attributes of {@link android.R.styleable#Fade} and
 * {@link android.R.styleable#Transition}.</p>

 */
public class Fade extends Visibility {

    private static boolean DBG = Transition.DBG && false;

    private static final String LOG_TAG = "Fade";
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
        final ObjectAnimator anim = ObjectAnimator.ofFloat(view, "transitionAlpha", startAlpha,
                endAlpha);
        if (DBG) {
            Log.d(LOG_TAG, "Created animator " + anim);
        }
        if (listener != null) {
            anim.addListener(listener);
            anim.addPauseListener(listener);
        }
        return anim;
    }

    private void captureValues(TransitionValues transitionValues) {
        int[] loc = new int[2];
        transitionValues.view.getLocationOnScreen(loc);
        transitionValues.values.put(PROPNAME_SCREEN_X, loc[0]);
        transitionValues.values.put(PROPNAME_SCREEN_Y, loc[1]);
    }

    @Override
    public void captureStartValues(TransitionValues transitionValues) {
        super.captureStartValues(transitionValues);
        captureValues(transitionValues);
    }

    @Override
    public Animator onAppear(ViewGroup sceneRoot,
            TransitionValues startValues, int startVisibility,
            TransitionValues endValues, int endVisibility) {
        if ((mFadingMode & IN) != IN || endValues == null) {
            return null;
        }
        final View endView = endValues.view;
        if (DBG) {
            View startView = (startValues != null) ? startValues.view : null;
            Log.d(LOG_TAG, "Fade.onAppear: startView, startVis, endView, endVis = " +
                    startView + ", " + startVisibility + ", " + endView + ", " + endVisibility);
        }
        endView.setTransitionAlpha(0);
        TransitionListener transitionListener = new TransitionListenerAdapter() {
            boolean mCanceled = false;
            float mPausedAlpha;

            @Override
            public void onTransitionCancel(Transition transition) {
                endView.setTransitionAlpha(1);
                mCanceled = true;
            }

            @Override
            public void onTransitionEnd(Transition transition) {
                if (!mCanceled) {
                    endView.setTransitionAlpha(1);
                }
            }

            @Override
            public void onTransitionPause(Transition transition) {
                mPausedAlpha = endView.getTransitionAlpha();
                endView.setTransitionAlpha(1);
            }

            @Override
            public void onTransitionResume(Transition transition) {
                endView.setTransitionAlpha(mPausedAlpha);
            }
        };
        addListener(transitionListener);
        return createAnimation(endView, 0, 1, null);
    }

    @Override
    public Animator onDisappear(ViewGroup sceneRoot,
            TransitionValues startValues, int startVisibility,
            TransitionValues endValues, int endVisibility) {
        if ((mFadingMode & OUT) != OUT) {
            return null;
        }
        View view = null;
        View startView = (startValues != null) ? startValues.view : null;
        View endView = (endValues != null) ? endValues.view : null;
        if (DBG) {
            Log.d(LOG_TAG, "Fade.onDisappear: startView, startVis, endView, endVis = " +
                        startView + ", " + startVisibility + ", " + endView + ", " + endVisibility);
        }
        View overlayView = null;
        View viewToKeep = null;
        if (endView == null || endView.getParent() == null) {
            if (endView != null) {
                // endView was removed from its parent - add it to the overlay
                view = overlayView = endView;
            } else if (startView != null) {
                // endView does not exist. Use startView only under certain
                // conditions, because placing a view in an overlay necessitates
                // it being removed from its current parent
                if (startView.getParent() == null) {
                    // no parent - safe to use
                    view = overlayView = startView;
                } else if (startView.getParent() instanceof View &&
                        startView.getParent().getParent() == null) {
                    View startParent = (View) startView.getParent();
                    int id = startParent.getId();
                    if (id != View.NO_ID && sceneRoot.findViewById(id) != null && mCanRemoveViews) {
                        // no parent, but its parent is unparented  but the parent
                        // hierarchy has been replaced by a new hierarchy with the same id
                        // and it is safe to un-parent startView
                        view = overlayView = startView;
                    }
                }
            }
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
            final float startAlpha = 1;
            float endAlpha = 0;
            final View finalView = view;
            final View finalOverlayView = overlayView;
            final View finalViewToKeep = viewToKeep;
            final ViewGroup finalSceneRoot = sceneRoot;
            final AnimatorListenerAdapter endListener = new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    finalView.setTransitionAlpha(startAlpha);
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
            final float startAlpha = 1;
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
                    mPausedAlpha = finalView.getTransitionAlpha();
                    finalView.setTransitionAlpha(startAlpha);
                }

                @Override
                public void onAnimationResume(Animator animation) {
                    if (finalViewToKeep != null && !mCanceled) {
                        finalViewToKeep.setVisibility(View.VISIBLE);
                    }
                    finalView.setTransitionAlpha(mPausedAlpha);
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                    mCanceled = true;
                    if (mPausedAlpha >= 0) {
                        finalView.setTransitionAlpha(mPausedAlpha);
                    }
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    if (!mCanceled) {
                        finalView.setTransitionAlpha(startAlpha);
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