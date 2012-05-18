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

/**
 * This transition tracks changes to the visibility of target views in the
 * start and end scenes. Visibility is determined not just by the
 * {@link View#setVisibility(int)} state of views, but also whether
 * views exist in the current view hierarchy. The class is intended to be a
 * utility for subclasses such as {@link Fade}, which use this visibility
 * information to determine the specific animations to run when visibility
 * changes occur. Subclasses should implement one or more of the methods
 * {@link #preAppear(ViewGroup, View, int, View, int)},
 * {@link #preDisappear(ViewGroup, View, int, View, int)},
 * {@link #appear(ViewGroup, View, int, View, int)}, and
 * {@link #disappear(ViewGroup, View, int, View, int)}.
 */
public abstract class Visibility extends Transition {

    private static final String PROPNAME_VISIBILITY = "android:visibility:visibility";
    private static final String PROPNAME_PARENT = "android:visibility:parent";

    @Override
    protected void captureValues(TransitionValues values, boolean start) {
        int visibility = values.view.getVisibility();
        values.values.put(PROPNAME_VISIBILITY, visibility);
        values.values.put(PROPNAME_PARENT, values.view.getParent());
    }

    @Override
    protected boolean prePlay(ViewGroup sceneRoot, TransitionValues startValues,
            TransitionValues endValues) {
        boolean visibilityChange = false;
        boolean fadeIn = false;
        int startVisibility, endVisibility;
        View startParent, endParent;
        if (startValues != null) {
            startVisibility = (Integer) startValues.values.get(PROPNAME_VISIBILITY);
            startParent = (View) startValues.values.get(PROPNAME_PARENT);
        } else {
            startVisibility = -1;
            startParent = null;
        }
        if (endValues != null) {
            endVisibility = (Integer) endValues.values.get(PROPNAME_VISIBILITY);
            endParent = (View) endValues.values.get(PROPNAME_PARENT);
        } else {
            endVisibility = -1;
            endParent = null;
        }
        boolean existenceChange = false;
        if (startValues != null && endValues != null) {
            if (startVisibility == endVisibility && startParent == endParent) {
                return false;
            } else {
                if (startVisibility != endVisibility) {
                    if (startVisibility == View.VISIBLE) {
                        fadeIn = false;
                        visibilityChange = true;
                    } else if (endVisibility == View.VISIBLE) {
                        fadeIn = true;
                        visibilityChange = true;
                    }
                    // no visibilityChange if going between INVISIBLE and GONE
                } else if (startParent != endParent) {
                    existenceChange = true;
                    if (endParent == null) {
                        fadeIn = false;
                        visibilityChange = true;
                    } else if (startParent == null) {
                        fadeIn = true;
                        visibilityChange = true;
                    }
                }
            }
        }
        if (startValues == null) {
            existenceChange = true;
            fadeIn = true;
            visibilityChange = true;
        } else if (endValues == null) {
            existenceChange = true;
            fadeIn = false;
            visibilityChange = true;
        }
        if (visibilityChange) {
            if (fadeIn) {
                return preAppear(sceneRoot, existenceChange ? null : startValues.view,
                        startVisibility, endValues.view, endVisibility);
            } else {
                return preDisappear(sceneRoot, startValues.view, startVisibility,
                        existenceChange ? null : endValues.view, endVisibility);
            }
        } else {
            return false;
        }
    }

    @Override
    protected Animator play(ViewGroup sceneRoot, TransitionValues startValues,
            TransitionValues endValues) {
        boolean visibilityChange = false;
        boolean fadeIn = false;
        int startVisibility, endVisibility;
        View startParent, endParent;
        if (startValues != null) {
            startVisibility = (Integer) startValues.values.get(PROPNAME_VISIBILITY);
            startParent = (View) startValues.values.get(PROPNAME_PARENT);
        } else {
            startVisibility = -1;
            startParent = null;
        }
        if (endValues != null) {
            endVisibility = (Integer) endValues.values.get(PROPNAME_VISIBILITY);
            endParent = (View) endValues.values.get(PROPNAME_PARENT);
        } else {
            endVisibility = -1;
            endParent = null;
        }
        boolean existenceChange = false;
        if (startValues != null && endValues != null) {
            if (startVisibility == endVisibility && startParent == endParent) {
                return null;
            } else {
                if (startVisibility != endVisibility) {
                    if (startVisibility == View.VISIBLE) {
                        fadeIn = false;
                        visibilityChange = true;
                    } else if (endVisibility == View.VISIBLE) {
                        fadeIn = true;
                        visibilityChange = true;
                    }
                    // no visibilityChange if going between INVISIBLE and GONE
                } else if (startParent != endParent) {
                    existenceChange = true;
                    if (endParent == null) {
                        fadeIn = false;
                        visibilityChange = true;
                    } else if (startParent == null) {
                        fadeIn = true;
                        visibilityChange = true;
                    }
                }
            }
        }
        if (startValues == null) {
            existenceChange = true;
            fadeIn = true;
            visibilityChange = true;
        } else if (endValues == null) {
            existenceChange = true;
            fadeIn = false;
            visibilityChange = true;
        }
        if (visibilityChange) {
            if (fadeIn) {
                return appear(sceneRoot, existenceChange ? null : startValues.view, startVisibility,
                        endValues.view, endVisibility);
            } else {
                return disappear(sceneRoot, startValues.view, startVisibility,
                        existenceChange ? null : endValues.view, endVisibility);
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
     * @param startView
     * @param startVisibility
     * @param endView
     * @param endVisibility
     * @return
     */
    protected boolean preAppear(ViewGroup sceneRoot, View startView, int startVisibility,
            View endView, int endVisibility) {
        return true;
    }

    /**
     * The default implementation of this method does nothing. Subclasses
     * should override if they need to set up anything prior to the
     * transition starting.
     * @param sceneRoot
     * @param startView
     * @param startVisibility
     * @param endView
     * @param endVisibility
     * @return
     */
    protected boolean preDisappear(ViewGroup sceneRoot, View startView, int startVisibility,
            View endView, int endVisibility) {
        return true;
    }

    /**
     * The default implementation of this method does nothing. Subclasses
     * should override if they need to do anything when target objects
     * appear during the scene change.
     * @param sceneRoot
     * @param startView
     * @param startVisibility
     * @param endView
     * @param endVisibility
     */
    protected Animator appear(ViewGroup sceneRoot, View startView, int startVisibility,
            View endView, int endVisibility) { return null; }

    /**
     * The default implementation of this method does nothing. Subclasses
     * should override if they need to do anything when target objects
     * disappear during the scene change.
     * @param sceneRoot
     * @param startView
     * @param startVisibility
     * @param endView
     * @param endVisibility
     */
    protected Animator disappear(ViewGroup sceneRoot, View startView, int startVisibility,
            View endView, int endVisibility) { return null; }

}
