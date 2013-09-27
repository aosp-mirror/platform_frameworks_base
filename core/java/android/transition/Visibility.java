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
 * {@link #onDisappear(ViewGroup, TransitionValues, int, TransitionValues, int)},
 */
public abstract class Visibility extends Transition {

    private static final String PROPNAME_VISIBILITY = "android:visibility:visibility";
    private static final String PROPNAME_PARENT = "android:visibility:parent";
    private static final String[] sTransitionProperties = {
            PROPNAME_VISIBILITY,
            PROPNAME_PARENT,
    };

    private static class VisibilityInfo {
        boolean visibilityChange;
        boolean fadeIn;
        int startVisibility;
        int endVisibility;
        ViewGroup startParent;
        ViewGroup endParent;
    }

    @Override
    public String[] getTransitionProperties() {
        return sTransitionProperties;
    }

    private void captureValues(TransitionValues transitionValues) {
        int visibility = transitionValues.view.getVisibility();
        transitionValues.values.put(PROPNAME_VISIBILITY, visibility);
        transitionValues.values.put(PROPNAME_PARENT, transitionValues.view.getParent());
    }

    @Override
    public void captureStartValues(TransitionValues transitionValues) {
        captureValues(transitionValues);
    }

    @Override
    public void captureEndValues(TransitionValues transitionValues) {
        captureValues(transitionValues);
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
        if (startValues != null) {
            visInfo.startVisibility = (Integer) startValues.values.get(PROPNAME_VISIBILITY);
            visInfo.startParent = (ViewGroup) startValues.values.get(PROPNAME_PARENT);
        } else {
            visInfo.startVisibility = -1;
            visInfo.startParent = null;
        }
        if (endValues != null) {
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
        if (visInfo.visibilityChange) {
            // Only transition views that are either targets of this transition
            // or whose parent hierarchies remain stable between scenes
            boolean isTarget = false;
            if (mTargets.size() > 0 || mTargetIds.size() > 0) {
                View startView = startValues != null ? startValues.view : null;
                View endView = endValues != null ? endValues.view : null;
                int startId = startView != null ? startView.getId() : -1;
                int endId = endView != null ? endView.getId() : -1;
                isTarget = isValidTarget(startView, startId) || isValidTarget(endView, endId);
            }
            if (isTarget || ((visInfo.startParent != null || visInfo.endParent != null))) {
                if (visInfo.fadeIn) {
                    return onAppear(sceneRoot, startValues, visInfo.startVisibility,
                            endValues, visInfo.endVisibility);
                } else {
                    return onDisappear(sceneRoot, startValues, visInfo.startVisibility,
                            endValues, visInfo.endVisibility
                    );
                }
            }
        }
        return null;
    }

    /**
     * The default implementation of this method does nothing. Subclasses
     * should override if they need to create an Animator when targets appear.
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
        return null;
    }

    /**
     * The default implementation of this method does nothing. Subclasses
     * should override if they need to create an Animator when targets disappear.
     * The method should only be called by the Visibility class; it is
     * not intended to be called from external classes.
     *
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
        return null;
    }
}
