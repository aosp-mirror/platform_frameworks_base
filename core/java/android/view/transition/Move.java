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
import android.animation.PropertyValuesHolder;
import android.animation.RectEvaluator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.view.View;
import android.view.ViewGroup;

import java.util.HashMap;

/**
 * This transition captures the layout bounds of target views before and after
 * the scene change and animates those changes during the transition.
 */
public class Move extends Transition {

    private static final String PROPNAME_BOUNDS = "android:move:bounds";
    private static final String PROPNAME_PARENT = "android:move:parent";
    private static final String PROPNAME_WINDOW_X = "android:move:windowX";
    private static final String PROPNAME_WINDOW_Y = "android:move:windowY";
    int[] tempLocation = new int[2];
    boolean mResizeClip = false;
    boolean mReparent = false;

    private static RectEvaluator sRectEvaluator = new RectEvaluator();

    public void setResizeClip(boolean resizeClip) {
        mResizeClip = resizeClip;
    }

    /**
     * Setting this flag tells Move to track the before/after parent
     * of every view using this transition. The flag is not enabled by
     * default because it requires the parent instances to be the same
     * in the two scenes or else all parents must use ids to allow
     * the transition to determine which parents are the same.
     *
     * @param reparent true if the transition should track the parent
     * container of target views and animate parent changes.
     */
    public void setReparent(boolean reparent) {
        mReparent = reparent;
    }

    @Override
    protected void captureValues(TransitionValues values, boolean start) {
        View view = values.view;
        values.values.put(PROPNAME_BOUNDS, new Rect(view.getLeft(), view.getTop(),
                view.getRight(), view.getBottom()));
        values.values.put(PROPNAME_PARENT, values.view.getParent());
        values.view.getLocationInWindow(tempLocation);
        values.values.put(PROPNAME_WINDOW_X, tempLocation[0]);
        values.values.put(PROPNAME_WINDOW_Y, tempLocation[1]);
    }

    @Override
    protected Animator play(final ViewGroup sceneRoot, TransitionValues startValues,
            TransitionValues endValues) {
        if (startValues == null || endValues == null) {
            return null;
        }
        final View view = endValues.view;
        if (view.getParent() == null) {
            // TODO: Might want to make it possible to Move an disappearing view.
            // This workaround is here because if a parallel Fade is not running on the view
            // Then it won't get added to the hierarchy and the animator below will not fire,
            // causing the transition to not end
            return null;
        }
        // TODO: need to handle non-VG case?
        ViewGroup startParent = (ViewGroup) startValues.values.get(PROPNAME_PARENT);
        ViewGroup endParent = (ViewGroup) endValues.values.get(PROPNAME_PARENT);
        if (startParent == null || endParent == null) {
            return null;
        }
        boolean parentsEqual = (startParent == endParent) ||
                (startParent.getId() == endParent.getId());
        if (!mReparent || parentsEqual) {
            // Common case - view belongs to the same layout before/after. Just animate its bounds
            Rect startBounds = (Rect) startValues.values.get(PROPNAME_BOUNDS);
            Rect endBounds = (Rect) endValues.values.get(PROPNAME_BOUNDS);
            int startLeft = startBounds.left;
            int endLeft = endBounds.left;
            int startTop = startBounds.top;
            int endTop = endBounds.top;
            int startRight = startBounds.right;
            int endRight = endBounds.right;
            int startBottom = startBounds.bottom;
            int endBottom = endBounds.bottom;
            int startWidth = startRight - startLeft;
            int startHeight = startBottom - startTop;
            int endWidth = endRight - endLeft;
            int endHeight = endBottom - endTop;
            int numChanges = 0;
            if (startWidth != 0 && startHeight != 0 && endWidth != 0 && endHeight != 0) {
                if (startLeft != endLeft) ++numChanges;
                if (startTop != endTop) ++numChanges;
                if (startRight != endRight) ++numChanges;
                if (startBottom != endBottom) ++numChanges;
            }
            if (numChanges > 0) {
                if (!mResizeClip) {
                    PropertyValuesHolder pvh[] = new PropertyValuesHolder[numChanges];
                    int pvhIndex = 0;
                    if (startLeft != endLeft) {
                        pvh[pvhIndex++] = PropertyValuesHolder.ofInt("left", startLeft, endLeft);
                    }
                    if (startTop != endTop) {
                        pvh[pvhIndex++] = PropertyValuesHolder.ofInt("top", startTop, endTop);
                    }
                    if (startRight != endRight) {
                        pvh[pvhIndex++] = PropertyValuesHolder.ofInt("right",
                                startRight, endRight);
                    }
                    if (startBottom != endBottom) {
                        pvh[pvhIndex++] = PropertyValuesHolder.ofInt("bottom",
                                startBottom, endBottom);
                    }
                    ObjectAnimator anim = ObjectAnimator.ofPropertyValuesHolder(view, pvh);
                    if (view.getParent() instanceof ViewGroup) {
                        final ViewGroup parent = (ViewGroup) view.getParent();
                        parent.suppressLayout(true);
                        anim.addListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                parent.suppressLayout(false);
                            }
                        });
                    }
                    return anim;
                } else {
                    // Animate location with translationX/Y and size with clip bounds
                    float transXDelta = endLeft - startLeft;
                    float transYDelta = endTop - startTop;
                    int widthDelta = endWidth - startWidth;
                    int heightDelta = endHeight - startHeight;
                    numChanges = 0;
                    if (transXDelta != 0) numChanges++;
                    if (transYDelta != 0) numChanges++;
                    if (widthDelta != 0 || heightDelta != 0) numChanges++;
                    PropertyValuesHolder pvh[] = new PropertyValuesHolder[numChanges];
                    int pvhIndex = 0;
                    if (transXDelta != 0) {
                        pvh[pvhIndex++] = PropertyValuesHolder.ofFloat("translationX",
                                view.getTranslationX(), 0);
                    }
                    if (transYDelta != 0) {
                        pvh[pvhIndex++] = PropertyValuesHolder.ofFloat("translationY",
                                view.getTranslationY(), 0);
                    }
                    if (widthDelta != 0 || heightDelta != 0) {
                        Rect tempStartBounds = new Rect(0, 0, startWidth, startHeight);
                        Rect tempEndBounds = new Rect(0, 0, endWidth, endHeight);
                        pvh[pvhIndex++] = PropertyValuesHolder.ofObject("clipBounds",
                                sRectEvaluator, tempStartBounds, tempEndBounds);
                    }
                    ObjectAnimator anim = ObjectAnimator.ofPropertyValuesHolder(view, pvh);
                    if (view.getParent() instanceof ViewGroup) {
                        final ViewGroup parent = (ViewGroup) view.getParent();
                        parent.suppressLayout(true);
                        anim.addListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                parent.suppressLayout(false);
                            }
                        });
                    }
                    anim.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            view.setClipBounds(null);
                        }
                    });
                    return anim;
                }
            }
        } else {
            return (ObjectAnimator) endValues.values.get("drawableAnim");
        }
        return null;
    }

    @Override
    protected boolean prePlay(final ViewGroup sceneRoot, TransitionValues startValues,
            TransitionValues endValues) {
        if (startValues == null || endValues == null) {
            return false;
        }
        HashMap<String, Object> startParentVals = startValues.values;
        HashMap<String, Object> endParentVals = endValues.values;
        ViewGroup startParent = (ViewGroup) startParentVals.get(PROPNAME_PARENT);
        ViewGroup endParent = (ViewGroup) endParentVals.get(PROPNAME_PARENT);
        if (startParent == null || endParent == null) {
            return false;
        }
        final View view = endValues.view;
        boolean parentsEqual = (startParent == endParent) ||
                (startParent.getId() == endParent.getId());
        // TODO: Might want reparenting to be separate/subclass transition, or at least
        // triggered by a property on Move. Otherwise, we're forcing the requirement that
        // all parents in layouts have IDs to avoid layout-inflation resulting in a side-effect
        // of reparenting the views.
        if (!mReparent || parentsEqual) {
            Rect startBounds = (Rect) startValues.values.get(PROPNAME_BOUNDS);
            Rect endBounds = (Rect) endValues.values.get(PROPNAME_BOUNDS);
            int startLeft = startBounds.left;
            int endLeft = endBounds.left;
            int startTop = startBounds.top;
            int endTop = endBounds.top;
            int startRight = startBounds.right;
            int endRight = endBounds.right;
            int startBottom = startBounds.bottom;
            int endBottom = endBounds.bottom;
            int startWidth = startRight - startLeft;
            int startHeight = startBottom - startTop;
            int endWidth = endRight - endLeft;
            int endHeight = endBottom - endTop;
            int numChanges = 0;
            if (startWidth != 0 && startHeight != 0 && endWidth != 0 && endHeight != 0) {
                if (startLeft != endLeft) ++numChanges;
                if (startTop != endTop) ++numChanges;
                if (startRight != endRight) ++numChanges;
                if (startBottom != endBottom) ++numChanges;
            }
            if (numChanges > 0) {
                if (!mResizeClip) {
                    if (startLeft != endLeft) view.setLeft(startLeft);
                    if (startTop != endTop) view.setTop(startTop);
                    if (startRight != endRight) view.setRight(startRight);
                    if (startBottom != endBottom) view.setBottom(startBottom);
                } else {
                    if (startWidth != endWidth) view.setRight(endLeft +
                            Math.max(startWidth, endWidth));
                    if (startHeight != endHeight) view.setBottom(endTop +
                            Math.max(startHeight, endHeight));
                    // TODO: don't clobber TX/TY
                    if (startLeft != endLeft) view.setTranslationX(startLeft - endLeft);
                    if (startTop != endTop) view.setTranslationY(startTop - endTop);
                }
                return true;
            }
        } else {
            int startX = (Integer) startValues.values.get(PROPNAME_WINDOW_X);
            int startY = (Integer) startValues.values.get(PROPNAME_WINDOW_Y);
            int endX = (Integer) endValues.values.get(PROPNAME_WINDOW_X);
            int endY = (Integer) endValues.values.get(PROPNAME_WINDOW_Y);
            // TODO: also handle size changes: check bounds and animate size changes
            if (startX != endX || startY != endY) {
                sceneRoot.getLocationInWindow(tempLocation);
                Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(),
                        Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                view.draw(canvas);
                final BitmapDrawable drawable = new BitmapDrawable(bitmap);
                view.setVisibility(View.INVISIBLE);
                sceneRoot.getOverlay().add(drawable);
                Rect startBounds = new Rect(startX - tempLocation[0], startY - tempLocation[1],
                        startX - tempLocation[0] + view.getWidth(),
                        startY - tempLocation[1] + view.getHeight());
                Rect endBounds = new Rect(endX - tempLocation[0], endY - tempLocation[1],
                        endX - tempLocation[0] + view.getWidth(),
                        endY - tempLocation[1] + view.getHeight());
                ObjectAnimator anim = ObjectAnimator.ofObject(drawable, "bounds",
                        sRectEvaluator, startBounds, endBounds);
                anim.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        sceneRoot.getOverlay().remove(drawable);
                        view.setVisibility(View.VISIBLE);
                    }
                });
                endParentVals.put("drawableAnim", anim);
                return true;
            }
        }
        return false;
    }
}
