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

import com.android.internal.R;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
/**
 * This transition tracks changes to the visibility of target views in the
 * start and end scenes. Visibility is determined not just by the
 * {@link View#setVisibility(int)} state of views, but also whether
 * views exist in the current view hierarchy. The class is intended to be a
 * utility for subclasses such as {@link Fade}, which use this visibility
 * information to determine the specific animations to run when visibility
 * changes occur. Subclasses should implement one or both of the methods
 * {@link #onAppear(ViewGroup, TransitionValues, int, TransitionValues, int)},
 * {@link #onDisappear(ViewGroup, TransitionValues, int, TransitionValues, int)} or
 * {@link #onAppear(ViewGroup, View, TransitionValues, TransitionValues)},
 * {@link #onDisappear(ViewGroup, View, TransitionValues, TransitionValues)}.
 */
public abstract class Visibility extends Transition {

    static final String PROPNAME_VISIBILITY = "android:visibility:visibility";
    private static final String PROPNAME_PARENT = "android:visibility:parent";
    private static final String PROPNAME_SCREEN_LOCATION = "android:visibility:screenLocation";

    /**
     * Mode used in {@link #setMode(int)} to make the transition
     * operate on targets that are appearing. Maybe be combined with
     * {@link #MODE_OUT} to target Visibility changes both in and out.
     */
    public static final int MODE_IN = 0x1;

    /**
     * Mode used in {@link #setMode(int)} to make the transition
     * operate on targets that are disappearing. Maybe be combined with
     * {@link #MODE_IN} to target Visibility changes both in and out.
     */
    public static final int MODE_OUT = 0x2;

    private static final String[] sTransitionProperties = {
            PROPNAME_VISIBILITY,
    };

    private static class VisibilityInfo {
        boolean visibilityChange;
        boolean fadeIn;
        int startVisibility;
        int endVisibility;
        ViewGroup startParent;
        ViewGroup endParent;
    }

    private int mMode = MODE_IN | MODE_OUT;

    private int mForcedStartVisibility = -1;
    private int mForcedEndVisibility = -1;

    public Visibility() {}

    public Visibility(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.VisibilityTransition);
        int mode = a.getInt(R.styleable.VisibilityTransition_visibilityMode, 0);
        a.recycle();
        if (mode != 0) {
            setMode(mode);
        }
    }

    /**
     * Changes the transition to support appearing and/or disappearing Views, depending
     * on <code>mode</code>.
     *
     * @param mode The behavior supported by this transition, a combination of
     *             {@link #MODE_IN} and {@link #MODE_OUT}.
     * @attr ref android.R.styleable#VisibilityTransition_visibilityMode
     */
    public void setMode(int mode) {
        if ((mode & ~(MODE_IN | MODE_OUT)) != 0) {
            throw new IllegalArgumentException("Only MODE_IN and MODE_OUT flags are allowed");
        }
        mMode = mode;
    }

    /**
     * Returns whether appearing and/or disappearing Views are supported.
     *
     * Returns whether appearing and/or disappearing Views are supported. A combination of
     *         {@link #MODE_IN} and {@link #MODE_OUT}.
     * @attr ref android.R.styleable#VisibilityTransition_visibilityMode
     */
    public int getMode() {
        return mMode;
    }

    @Override
    public String[] getTransitionProperties() {
        return sTransitionProperties;
    }

    private void captureValues(TransitionValues transitionValues, int forcedVisibility) {
        int visibility;
        if (forcedVisibility != -1) {
            visibility = forcedVisibility;
        } else {
            visibility = transitionValues.view.getVisibility();
        }
        transitionValues.values.put(PROPNAME_VISIBILITY, visibility);
        transitionValues.values.put(PROPNAME_PARENT, transitionValues.view.getParent());
        int[] loc = new int[2];
        transitionValues.view.getLocationOnScreen(loc);
        transitionValues.values.put(PROPNAME_SCREEN_LOCATION, loc);
    }

    @Override
    public void captureStartValues(TransitionValues transitionValues) {
        captureValues(transitionValues, mForcedStartVisibility);
    }

    @Override
    public void captureEndValues(TransitionValues transitionValues) {
        captureValues(transitionValues, mForcedEndVisibility);
    }

    /** @hide */
    @Override
    public void forceVisibility(int visibility, boolean isStartValue) {
        if (isStartValue) {
            mForcedStartVisibility = visibility;
        } else {
            mForcedEndVisibility = visibility;
        }
    }

    /**
     * Returns whether the view is 'visible' according to the given values
     * object. This is determined by testing the same properties in the values
     * object that are used to determine whether the object is appearing or
     * disappearing in the {@link
     * Transition#createAnimator(ViewGroup, TransitionValues, TransitionValues)}
     * method. This method can be called by, for example, subclasses that want
     * to know whether the object is visible in the same way that Visibility
     * determines it for the actual animation.
     *
     * @param values The TransitionValues object that holds the information by
     * which visibility is determined.
     * @return True if the view reference by <code>values</code> is visible,
     * false otherwise.
     */
    public boolean isVisible(TransitionValues values) {
        if (values == null) {
            return false;
        }
        int visibility = (Integer) values.values.get(PROPNAME_VISIBILITY);
        View parent = (View) values.values.get(PROPNAME_PARENT);

        return visibility == View.VISIBLE && parent != null;
    }

    private VisibilityInfo getVisibilityChangeInfo(TransitionValues startValues,
            TransitionValues endValues) {
        final VisibilityInfo visInfo = new VisibilityInfo();
        visInfo.visibilityChange = false;
        visInfo.fadeIn = false;
        if (startValues != null && startValues.values.containsKey(PROPNAME_VISIBILITY)) {
            visInfo.startVisibility = (Integer) startValues.values.get(PROPNAME_VISIBILITY);
            visInfo.startParent = (ViewGroup) startValues.values.get(PROPNAME_PARENT);
        } else {
            visInfo.startVisibility = -1;
            visInfo.startParent = null;
        }
        if (endValues != null && endValues.values.containsKey(PROPNAME_VISIBILITY)) {
            visInfo.endVisibility = (Integer) endValues.values.get(PROPNAME_VISIBILITY);
            visInfo.endParent = (ViewGroup) endValues.values.get(PROPNAME_PARENT);
        } else {
            visInfo.endVisibility = -1;
            visInfo.endParent = null;
        }
        if (startValues != null && endValues != null) {
            if (visInfo.startVisibility == visInfo.endVisibility &&
                    visInfo.startParent == visInfo.endParent) {
                return visInfo;
            } else {
                if (visInfo.startVisibility != visInfo.endVisibility) {
                    if (visInfo.startVisibility == View.VISIBLE) {
                        visInfo.fadeIn = false;
                        visInfo.visibilityChange = true;
                    } else if (visInfo.endVisibility == View.VISIBLE) {
                        visInfo.fadeIn = true;
                        visInfo.visibilityChange = true;
                    }
                    // no visibilityChange if going between INVISIBLE and GONE
                } else if (visInfo.startParent != visInfo.endParent) {
                    if (visInfo.endParent == null) {
                        visInfo.fadeIn = false;
                        visInfo.visibilityChange = true;
                    } else if (visInfo.startParent == null) {
                        visInfo.fadeIn = true;
                        visInfo.visibilityChange = true;
                    }
                }
            }
        }
        if (startValues == null) {
            visInfo.fadeIn = true;
            visInfo.visibilityChange = true;
        } else if (endValues == null) {
            visInfo.fadeIn = false;
            visInfo.visibilityChange = true;
        }
        return visInfo;
    }

    @Override
    public Animator createAnimator(ViewGroup sceneRoot, TransitionValues startValues,
            TransitionValues endValues) {
        VisibilityInfo visInfo = getVisibilityChangeInfo(startValues, endValues);
        if (visInfo.visibilityChange
                && (visInfo.startParent != null || visInfo.endParent != null)) {
            if (visInfo.fadeIn) {
                return onAppear(sceneRoot, startValues, visInfo.startVisibility,
                        endValues, visInfo.endVisibility);
            } else {
                return onDisappear(sceneRoot, startValues, visInfo.startVisibility,
                        endValues, visInfo.endVisibility
                );
            }
        }
        return null;
    }

    /**
     * The default implementation of this method calls
     * {@link #onAppear(ViewGroup, View, TransitionValues, TransitionValues)}.
     * Subclasses should override this method or
     * {@link #onAppear(ViewGroup, View, TransitionValues, TransitionValues)}.
     * if they need to create an Animator when targets appear.
     * The method should only be called by the Visibility class; it is
     * not intended to be called from external classes.
     *
     * @param sceneRoot The root of the transition hierarchy
     * @param startValues The target values in the start scene
     * @param startVisibility The target visibility in the start scene
     * @param endValues The target values in the end scene
     * @param endVisibility The target visibility in the end scene
     * @return An Animator to be started at the appropriate time in the
     * overall transition for this scene change. A null value means no animation
     * should be run.
     */
    public Animator onAppear(ViewGroup sceneRoot,
            TransitionValues startValues, int startVisibility,
            TransitionValues endValues, int endVisibility) {
        if ((mMode & MODE_IN) != MODE_IN || endValues == null) {
            return null;
        }
        return onAppear(sceneRoot, endValues.view, startValues, endValues);
    }

    /**
     * The default implementation of this method returns a null Animator. Subclasses should
     * override this method to make targets appear with the desired transition. The
     * method should only be called from
     * {@link #onAppear(ViewGroup, TransitionValues, int, TransitionValues, int)}.
     *
     * @param sceneRoot The root of the transition hierarchy
     * @param view The View to make appear. This will be in the target scene's View hierarchy and
     *             will be VISIBLE.
     * @param startValues The target values in the start scene
     * @param endValues The target values in the end scene
     * @return An Animator to be started at the appropriate time in the
     * overall transition for this scene change. A null value means no animation
     * should be run.
     */
    public Animator onAppear(ViewGroup sceneRoot, View view, TransitionValues startValues,
            TransitionValues endValues) {
        return null;
    }

    /**
     * Subclasses should override this method or
     * {@link #onDisappear(ViewGroup, View, TransitionValues, TransitionValues)}
     * if they need to create an Animator when targets disappear.
     * The method should only be called by the Visibility class; it is
     * not intended to be called from external classes.
     * <p>
     * The default implementation of this method attempts to find a View to use to call
     * {@link #onDisappear(ViewGroup, View, TransitionValues, TransitionValues)},
     * based on the situation of the View in the View hierarchy. For example,
     * if a View was simply removed from its parent, then the View will be added
     * into a {@link android.view.ViewGroupOverlay} and passed as the <code>view</code>
     * parameter in {@link #onDisappear(ViewGroup, View, TransitionValues, TransitionValues)}.
     * If a visible View is changed to be {@link View#GONE} or {@link View#INVISIBLE},
     * then it can be used as the <code>view</code> and the visibility will be changed
     * to {@link View#VISIBLE} for the duration of the animation. However, if a View
     * is in a hierarchy which is also altering its visibility, the situation can be
     * more complicated. In general, if a view that is no longer in the hierarchy in
     * the end scene still has a parent (so its parent hierarchy was removed, but it
     * was not removed from its parent), then it will be left alone to avoid side-effects from
     * improperly removing it from its parent. The only exception to this is if
     * the previous {@link Scene} was {@link Scene#getSceneForLayout(ViewGroup, int,
     * android.content.Context) created from a layout resource file}, then it is considered
     * safe to un-parent the starting scene view in order to make it disappear.</p>
     *
     * @param sceneRoot The root of the transition hierarchy
     * @param startValues The target values in the start scene
     * @param startVisibility The target visibility in the start scene
     * @param endValues The target values in the end scene
     * @param endVisibility The target visibility in the end scene
     * @return An Animator to be started at the appropriate time in the
     * overall transition for this scene change. A null value means no animation
     * should be run.
     */
    public Animator onDisappear(ViewGroup sceneRoot,
            TransitionValues startValues, int startVisibility,
            TransitionValues endValues, int endVisibility) {
        if ((mMode & MODE_OUT) != MODE_OUT) {
            return null;
        }

        View startView = (startValues != null) ? startValues.view : null;
        View endView = (endValues != null) ? endValues.view : null;
        View overlayView = null;
        View viewToKeep = null;
        if (endView == null || endView.getParent() == null) {
            if (endView != null) {
                // endView was removed from its parent - add it to the overlay
                overlayView = endView;
            } else if (startView != null) {
                // endView does not exist. Use startView only under certain
                // conditions, because placing a view in an overlay necessitates
                // it being removed from its current parent
                if (startView.getParent() == null) {
                    // no parent - safe to use
                    overlayView = startView;
                } else if (startView.getParent() instanceof View) {
                    View startParent = (View) startView.getParent();
                    if (!isValidTarget(startParent)) {
                        if (startView.isAttachedToWindow()) {
                            overlayView = copyViewImage(startView);
                        } else {
                            overlayView = startView;
                        }
                    } else if (startParent.getParent() == null) {
                        int id = startParent.getId();
                        if (id != View.NO_ID && sceneRoot.findViewById(id) != null
                                && mCanRemoveViews) {
                            // no parent, but its parent is unparented  but the parent
                            // hierarchy has been replaced by a new hierarchy with the same id
                            // and it is safe to un-parent startView
                            overlayView = startView;
                        }
                    }
                }
            }
        } else {
            // visibility change
            if (endVisibility == View.INVISIBLE) {
                viewToKeep = endView;
            } else {
                // Becoming GONE
                if (startView == endView) {
                    viewToKeep = endView;
                } else {
                    overlayView = startView;
                }
            }
        }
        final int finalVisibility = endVisibility;
        final ViewGroup finalSceneRoot = sceneRoot;

        if (overlayView != null) {
            // TODO: Need to do this for general case of adding to overlay
            int[] screenLoc = (int[]) startValues.values.get(PROPNAME_SCREEN_LOCATION);
            int screenX = screenLoc[0];
            int screenY = screenLoc[1];
            int[] loc = new int[2];
            sceneRoot.getLocationOnScreen(loc);
            overlayView.offsetLeftAndRight((screenX - loc[0]) - overlayView.getLeft());
            overlayView.offsetTopAndBottom((screenY - loc[1]) - overlayView.getTop());
            sceneRoot.getOverlay().add(overlayView);
            Animator animator = onDisappear(sceneRoot, overlayView, startValues, endValues);
            if (animator == null) {
                sceneRoot.getOverlay().remove(overlayView);
            } else {
                final View finalOverlayView = overlayView;
                animator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        finalSceneRoot.getOverlay().remove(finalOverlayView);
                    }
                });
            }
            return animator;
        }

        if (viewToKeep != null) {
            int originalVisibility = -1;
            final boolean isForcedVisibility = mForcedStartVisibility != -1 ||
                    mForcedEndVisibility != -1;
            if (!isForcedVisibility) {
                originalVisibility = viewToKeep.getVisibility();
                viewToKeep.setVisibility(View.VISIBLE);
            }
            Animator animator = onDisappear(sceneRoot, viewToKeep, startValues, endValues);
            if (animator != null) {
                final View finalViewToKeep = viewToKeep;
                animator.addListener(new AnimatorListenerAdapter() {
                    boolean mCanceled = false;

                    @Override
                    public void onAnimationPause(Animator animation) {
                        if (!mCanceled && !isForcedVisibility) {
                            finalViewToKeep.setVisibility(finalVisibility);
                        }
                    }

                    @Override
                    public void onAnimationResume(Animator animation) {
                        if (!mCanceled && !isForcedVisibility) {
                            finalViewToKeep.setVisibility(View.VISIBLE);
                        }
                    }

                    @Override
                    public void onAnimationCancel(Animator animation) {
                        mCanceled = true;
                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (!mCanceled) {
                            if (isForcedVisibility) {
                                finalViewToKeep.setTransitionAlpha(0);
                            } else {
                                finalViewToKeep.setVisibility(finalVisibility);
                            }
                        }
                    }
                });
            } else if (!isForcedVisibility) {
                viewToKeep.setVisibility(originalVisibility);
            }
            return animator;
        }
        return null;
    }

    private View copyViewImage(View view) {
        int width = view.getWidth();
        int height = view.getHeight();
        if (width <= 0 || height <= 0) {
            return null;
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        view.draw(canvas);
        final BitmapDrawable drawable = new BitmapDrawable(bitmap);

        View overlayView = new View(view.getContext());
        overlayView.setBackground(drawable);
        int widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY);
        overlayView.measure(widthSpec, heightSpec);
        overlayView.layout(0, 0, width, height);
        return overlayView;
    }

    @Override
    boolean areValuesChanged(TransitionValues oldValues, TransitionValues newValues) {
        VisibilityInfo changeInfo = getVisibilityChangeInfo(oldValues, newValues);
        if (oldValues == null && newValues == null) {
            return false;
        }
        return changeInfo.visibilityChange && (changeInfo.startVisibility == View.VISIBLE ||
            changeInfo.endVisibility == View.VISIBLE);
    }

    /**
     * The default implementation of this method returns a null Animator. Subclasses should
     * override this method to make targets disappear with the desired transition. The
     * method should only be called from
     * {@link #onDisappear(ViewGroup, TransitionValues, int, TransitionValues, int)}.
     *
     * @param sceneRoot The root of the transition hierarchy
     * @param view The View to make disappear. This will be in the target scene's View
     *             hierarchy or in an {@link android.view.ViewGroupOverlay} and will be
     *             VISIBLE.
     * @param startValues The target values in the start scene
     * @param endValues The target values in the end scene
     * @return An Animator to be started at the appropriate time in the
     * overall transition for this scene change. A null value means no animation
     * should be run.
     */
    public Animator onDisappear(ViewGroup sceneRoot, View view, TransitionValues startValues,
            TransitionValues endValues) {
        return null;
    }
}
