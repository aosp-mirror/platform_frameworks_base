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
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

/**
 * This transition tracks changes to the visibility of target views in the
 * start and end scenes. Visibility is determined not just by the
 * {@link View#setVisibility(int)} state of views, but also whether
 * views exist in the current view hierarchy. The class is intended to be a
 * utility for subclasses such as {@link Fade}, which use this visibility
 * information to determine the specific animations to run when visibility
 * changes occur. Subclasses should implement one or more of the methods
 * {@link #setupAppear(ViewGroup, TransitionValues, int, TransitionValues, int)},
 * {@link #setupDisappear(ViewGroup, TransitionValues, int, TransitionValues, int)},
 * {@link #appear(ViewGroup, TransitionValues, int, TransitionValues, int)}, and
 * {@link #disappear(ViewGroup, TransitionValues, int, TransitionValues, int)}.
 */
public abstract class Visibility extends Transition {

    private static final String PROPNAME_VISIBILITY = "android:visibility:visibility";
    private static final String PROPNAME_PARENT = "android:visibility:parent";

    private static class VisibilityInfo {
        boolean visibilityChange;
        boolean fadeIn;
        int startVisibility;
        int endVisibility;
        View startParent;
        View endParent;
    }

    // Temporary structure, used in calculating state in setup() and play()
    private VisibilityInfo mTmpVisibilityInfo = new VisibilityInfo();

    @Override
    protected void captureValues(TransitionValues values, boolean start) {
        int visibility = values.view.getVisibility();
        values.values.put(PROPNAME_VISIBILITY, visibility);
        values.values.put(PROPNAME_PARENT, values.view.getParent());
    }

    private boolean isHierarchyVisibilityChanging(ViewGroup sceneRoot, ViewGroup view) {

        if (view == sceneRoot) {
            return false;
        }
        TransitionValues startValues = getTransitionValues(view, true);
        TransitionValues endValues = getTransitionValues(view, false);

        if (startValues == null || endValues == null) {
            return true;
        }
        int startVisibility = (Integer) startValues.values.get(PROPNAME_VISIBILITY);
        View startParent = (View) startValues.values.get(PROPNAME_PARENT);
        int endVisibility = (Integer) endValues.values.get(PROPNAME_VISIBILITY);
        View endParent = (View) endValues.values.get(PROPNAME_PARENT);
        if (startVisibility != endVisibility || startParent != endParent) {
            return true;
        }

        ViewParent parent = view.getParent();
        if (parent instanceof ViewGroup && parent != sceneRoot) {
            return isHierarchyVisibilityChanging(sceneRoot, (ViewGroup) parent);
        }
        return false;
    }

    private VisibilityInfo getVisibilityChangeInfo(TransitionValues startValues,
            TransitionValues endValues) {
        final VisibilityInfo visInfo = mTmpVisibilityInfo;
        visInfo.visibilityChange = false;
        visInfo.fadeIn = false;
        if (startValues != null) {
            visInfo.startVisibility = (Integer) startValues.values.get(PROPNAME_VISIBILITY);
            visInfo.startParent = (View) startValues.values.get(PROPNAME_PARENT);
        } else {
            visInfo.startVisibility = -1;
            visInfo.startParent = null;
        }
        if (endValues != null) {
            visInfo.endVisibility = (Integer) endValues.values.get(PROPNAME_VISIBILITY);
            visInfo.endParent = (View) endValues.values.get(PROPNAME_PARENT);
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
    protected boolean setup(ViewGroup sceneRoot, TransitionValues startValues,
            TransitionValues endValues) {
        VisibilityInfo visInfo = getVisibilityChangeInfo(startValues, endValues);
        // Ensure not in parent hierarchy that's also becoming visible/invisible
        if (visInfo.visibilityChange) {
            ViewGroup parent = (ViewGroup) ((visInfo.endParent != null) ?
                    visInfo.endParent : visInfo.startParent);
            if (parent != null) {
                if (!isHierarchyVisibilityChanging(sceneRoot, parent)) {
                    if (visInfo.fadeIn) {
                        return setupAppear(sceneRoot, startValues, visInfo.startVisibility,
                                endValues, visInfo.endVisibility);
                    } else {
                        return setupDisappear(sceneRoot, startValues, visInfo.startVisibility,
                                endValues, visInfo.endVisibility
                        );
                    }
                }
            }
        }
        return false;
    }

    @Override
    protected Animator play(ViewGroup sceneRoot, TransitionValues startValues,
            TransitionValues endValues) {
        VisibilityInfo visInfo = getVisibilityChangeInfo(startValues, endValues);
        if (visInfo.visibilityChange) {
            if (visInfo.fadeIn) {
                return appear(sceneRoot, startValues, visInfo.startVisibility,
                        endValues, visInfo.endVisibility);
            } else {
                return disappear(sceneRoot, startValues, visInfo.startVisibility,
                        endValues, visInfo.endVisibility);
            }
        }
        return null;
    }

    /**
     * The default implementation of this method does nothing. Subclasses
     * should override if they need to set up anything prior to the
     * transition starting.
     *
     * @param sceneRoot
     * @param startValues
     * @param startVisibility
     * @param endValues
     * @param endVisibility
     * @return
     */
    protected boolean setupAppear(ViewGroup sceneRoot,
            TransitionValues startValues, int startVisibility,
            TransitionValues endValues, int endVisibility) {
        return true;
    }

    /**
     * The default implementation of this method does nothing. Subclasses
     * should override if they need to set up anything prior to the
     * transition starting.
     *
     * @param sceneRoot
     * @param startValues
     * @param startVisibility
     * @param endValues
     * @param endVisibility
     * @return
     */
    protected boolean setupDisappear(ViewGroup sceneRoot,
            TransitionValues startValues, int startVisibility,
            TransitionValues endValues, int endVisibility) {
        return true;
    }

    /**
     * The default implementation of this method does nothing. Subclasses
     * should override if they need to do anything when target objects
     * appear during the scene change.
     * @param sceneRoot
     * @param startValues
     * @param startVisibility
     * @param endValues
     * @param endVisibility
     */
    protected Animator appear(ViewGroup sceneRoot,
            TransitionValues startValues, int startVisibility,
            TransitionValues endValues, int endVisibility) {
        return null;
    }

    /**
     * The default implementation of this method does nothing. Subclasses
     * should override if they need to do anything when target objects
     * disappear during the scene change.
     * @param sceneRoot
     * @param startValues
     * @param startVisibility
     * @param endValues
     * @param endVisibility
     */
    protected Animator disappear(ViewGroup sceneRoot,
            TransitionValues startValues, int startVisibility,
            TransitionValues endValues, int endVisibility) {
        return null;
    }

}
