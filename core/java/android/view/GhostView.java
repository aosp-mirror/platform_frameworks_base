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
package android.view;

import android.annotation.UnsupportedAppUsage;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.os.Build;
import android.widget.FrameLayout;

import java.util.ArrayList;

/**
 * This view draws another View in an Overlay without changing the parent. It will not be drawn
 * by its parent because its visibility is set to INVISIBLE, but will be drawn
 * here using its render node. When the GhostView is set to INVISIBLE, the View it is
 * shadowing will become VISIBLE and when the GhostView becomes VISIBLE, the shadowed
 * view becomes INVISIBLE.
 * @hide
 */
public class GhostView extends View {
    private final View mView;
    private int mReferences;
    private boolean mBeingMoved;

    private GhostView(View view) {
        super(view.getContext());
        mView = view;
        mView.mGhostView = this;
        final ViewGroup parent = (ViewGroup) mView.getParent();
        mView.setTransitionVisibility(View.INVISIBLE);
        parent.invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (canvas instanceof RecordingCanvas) {
            RecordingCanvas dlCanvas = (RecordingCanvas) canvas;
            mView.mRecreateDisplayList = true;
            RenderNode renderNode = mView.updateDisplayListIfDirty();
            if (renderNode.hasDisplayList()) {
                dlCanvas.insertReorderBarrier(); // enable shadow for this rendernode
                dlCanvas.drawRenderNode(renderNode);
                dlCanvas.insertInorderBarrier(); // re-disable reordering/shadows
            }
        }
    }

    public void setMatrix(Matrix matrix) {
        mRenderNode.setAnimationMatrix(matrix);
    }

    @Override
    public void setVisibility(@Visibility int visibility) {
        super.setVisibility(visibility);
        if (mView.mGhostView == this) {
            int inverseVisibility = (visibility == View.VISIBLE) ? View.INVISIBLE : View.VISIBLE;
            mView.setTransitionVisibility(inverseVisibility);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (!mBeingMoved) {
            mView.setTransitionVisibility(View.VISIBLE);
            mView.mGhostView = null;
            final ViewGroup parent = (ViewGroup) mView.getParent();
            if (parent != null) {
                parent.invalidate();
            }
        }
    }

    public static void calculateMatrix(View view, ViewGroup host, Matrix matrix) {
        ViewGroup parent = (ViewGroup) view.getParent();
        matrix.reset();
        parent.transformMatrixToGlobal(matrix);
        matrix.preTranslate(-parent.getScrollX(), -parent.getScrollY());
        host.transformMatrixToLocal(matrix);
    }

    @UnsupportedAppUsage
    public static GhostView addGhost(View view, ViewGroup viewGroup, Matrix matrix) {
        if (!(view.getParent() instanceof ViewGroup)) {
            throw new IllegalArgumentException("Ghosted views must be parented by a ViewGroup");
        }
        ViewGroupOverlay overlay = viewGroup.getOverlay();
        ViewOverlay.OverlayViewGroup overlayViewGroup = overlay.mOverlayViewGroup;
        GhostView ghostView = view.mGhostView;
        int previousRefCount = 0;
        if (ghostView != null) {
            View oldParent = (View) ghostView.getParent();
            ViewGroup oldGrandParent = (ViewGroup) oldParent.getParent();
            if (oldGrandParent != overlayViewGroup) {
                previousRefCount = ghostView.mReferences;
                oldGrandParent.removeView(oldParent);
                ghostView = null;
            }
        }
        if (ghostView == null) {
            if (matrix == null) {
                matrix = new Matrix();
                calculateMatrix(view, viewGroup, matrix);
            }
            ghostView = new GhostView(view);
            ghostView.setMatrix(matrix);
            FrameLayout parent = new FrameLayout(view.getContext());
            parent.setClipChildren(false);
            copySize(viewGroup, parent);
            copySize(viewGroup, ghostView);
            parent.addView(ghostView);
            ArrayList<View> tempViews = new ArrayList<View>();
            int firstGhost = moveGhostViewsToTop(overlay.mOverlayViewGroup, tempViews);
            insertIntoOverlay(overlay.mOverlayViewGroup, parent, ghostView, tempViews, firstGhost);
            ghostView.mReferences = previousRefCount;
        } else if (matrix != null) {
            ghostView.setMatrix(matrix);
        }
        ghostView.mReferences++;
        return ghostView;
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public static GhostView addGhost(View view, ViewGroup viewGroup) {
        return addGhost(view, viewGroup, null);
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public static void removeGhost(View view) {
        GhostView ghostView = view.mGhostView;
        if (ghostView != null) {
            ghostView.mReferences--;
            if (ghostView.mReferences == 0) {
                ViewGroup parent = (ViewGroup) ghostView.getParent();
                ViewGroup grandParent = (ViewGroup) parent.getParent();
                grandParent.removeView(parent);
            }
        }
    }

    public static GhostView getGhost(View view) {
        return view.mGhostView;
    }

    private static void copySize(View from, View to) {
        to.setLeft(0);
        to.setTop(0);
        to.setRight(from.getWidth());
        to.setBottom(from.getHeight());
    }

    /**
     * Move the GhostViews to the end so that they are on top of other views and it is easier
     * to do binary search for the correct location for the GhostViews in insertIntoOverlay.
     *
     * @return The index of the first GhostView or -1 if no GhostView is in the ViewGroup
     */
    private static int moveGhostViewsToTop(ViewGroup viewGroup, ArrayList<View> tempViews) {
        final int numChildren = viewGroup.getChildCount();
        if (numChildren == 0) {
            return -1;
        } else if (isGhostWrapper(viewGroup.getChildAt(numChildren - 1))) {
            // GhostViews are already at the end
            int firstGhost = numChildren - 1;
            for (int i = numChildren - 2; i >= 0; i--) {
                if (!isGhostWrapper(viewGroup.getChildAt(i))) {
                    break;
                }
                firstGhost = i;
            }
            return firstGhost;
        }

        // Remove all GhostViews from the middle
        for (int i = numChildren - 2; i >= 0; i--) {
            View child = viewGroup.getChildAt(i);
            if (isGhostWrapper(child)) {
                tempViews.add(child);
                GhostView ghostView = (GhostView)((ViewGroup)child).getChildAt(0);
                ghostView.mBeingMoved = true;
                viewGroup.removeViewAt(i);
                ghostView.mBeingMoved = false;
            }
        }

        final int firstGhost;
        if (tempViews.isEmpty()) {
            firstGhost = -1;
        } else {
            firstGhost = viewGroup.getChildCount();
            // Add the GhostViews to the end
            for (int i = tempViews.size() - 1; i >= 0; i--) {
                viewGroup.addView(tempViews.get(i));
            }
            tempViews.clear();
        }
        return firstGhost;
    }

    /**
     * Inserts a GhostView into the overlay's ViewGroup in the order in which they
     * should be displayed by the UI.
     */
    private static void insertIntoOverlay(ViewGroup viewGroup, ViewGroup wrapper,
            GhostView ghostView, ArrayList<View> tempParents, int firstGhost) {
        if (firstGhost == -1) {
            viewGroup.addView(wrapper);
        } else {
            ArrayList<View> viewParents = new ArrayList<View>();
            getParents(ghostView.mView, viewParents);

            int index = getInsertIndex(viewGroup, viewParents, tempParents, firstGhost);
            if (index < 0 || index >= viewGroup.getChildCount()) {
                viewGroup.addView(wrapper);
            } else {
                viewGroup.addView(wrapper, index);
            }
        }
    }

    /**
     * Find the index into the overlay to insert the GhostView based on the order that the
     * views should be drawn. This keeps GhostViews layered in the same order
     * that they are ordered in the UI.
     */
    private static int getInsertIndex(ViewGroup overlayViewGroup, ArrayList<View> viewParents,
            ArrayList<View> tempParents, int firstGhost) {
        int low = firstGhost;
        int high = overlayViewGroup.getChildCount() - 1;

        while (low <= high) {
            int mid = (low + high) / 2;
            ViewGroup wrapper = (ViewGroup) overlayViewGroup.getChildAt(mid);
            GhostView midView = (GhostView) wrapper.getChildAt(0);
            getParents(midView.mView, tempParents);
            if (isOnTop(viewParents, tempParents)) {
                low = mid + 1;
            } else {
                high = mid - 1;
            }
            tempParents.clear();
        }

        return low;
    }

    /**
     * Returns true if view is a GhostView's FrameLayout wrapper.
     */
    private static boolean isGhostWrapper(View view) {
        if (view instanceof FrameLayout) {
            FrameLayout frameLayout = (FrameLayout) view;
            if (frameLayout.getChildCount() == 1) {
                View child = frameLayout.getChildAt(0);
                return child instanceof GhostView;
            }
        }
        return false;
    }

    /**
     * Returns true if viewParents is from a View that is on top of the comparedWith's view.
     * The ArrayLists contain the ancestors of views in order from top most grandparent, to
     * the view itself, in order. The goal is to find the first matching parent and then
     * compare the draw order of the siblings.
     */
    private static boolean isOnTop(ArrayList<View> viewParents, ArrayList<View> comparedWith) {
        if (viewParents.isEmpty() || comparedWith.isEmpty() ||
                viewParents.get(0) != comparedWith.get(0)) {
            // Not the same decorView -- arbitrary ordering
            return true;
        }
        int depth = Math.min(viewParents.size(), comparedWith.size());
        for (int i = 1; i < depth; i++) {
            View viewParent = viewParents.get(i);
            View comparedWithParent = comparedWith.get(i);

            if (viewParent != comparedWithParent) {
                // i - 1 is the same parent, but these are different children.
                return isOnTop(viewParent, comparedWithParent);
            }
        }

        // one of these is the parent of the other
        boolean isComparedWithTheParent = (comparedWith.size() == depth);
        return isComparedWithTheParent;
    }

    /**
     * Adds all the parents, grandparents, etc. of view to parents.
     */
    private static void getParents(View view, ArrayList<View> parents) {
        ViewParent parent = view.getParent();
        if (parent != null && parent instanceof ViewGroup) {
            getParents((View) parent, parents);
        }
        parents.add(view);
    }

    /**
     * Returns true if view would be drawn on top of comparedWith or false otherwise.
     * view and comparedWith are siblings with the same parent. This uses the logic
     * that dispatchDraw uses to determine which View should be drawn first.
     */
    private static boolean isOnTop(View view, View comparedWith) {
        ViewGroup parent = (ViewGroup) view.getParent();

        final int childrenCount = parent.getChildCount();
        final ArrayList<View> preorderedList = parent.buildOrderedChildList();
        final boolean customOrder = preorderedList == null
                && parent.isChildrenDrawingOrderEnabled();

        // This default value shouldn't be used because both view and comparedWith
        // should be in the list. If there is an error, then just return an arbitrary
        // view is on top.
        boolean isOnTop = true;
        for (int i = 0; i < childrenCount; i++) {
            int childIndex = customOrder ? parent.getChildDrawingOrder(childrenCount, i) : i;
            final View child = (preorderedList == null)
                    ? parent.getChildAt(childIndex) : preorderedList.get(childIndex);
            if (child == view) {
                isOnTop = false;
                break;
            } else if (child == comparedWith) {
                isOnTop = true;
                break;
            }
        }

        if (preorderedList != null) {
            preorderedList.clear();
        }
        return isOnTop;
    }
}
