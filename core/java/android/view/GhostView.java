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

import android.graphics.Canvas;
import android.graphics.Matrix;

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

    private GhostView(View view, ViewGroup host, Matrix matrix) {
        super(view.getContext());
        mView = view;
        mView.mGhostView = this;
        mRenderNode.setAnimationMatrix(matrix);
        final ViewGroup parent = (ViewGroup) mView.getParent();
        setLeft(0);
        setTop(0);
        setRight(host.getWidth());
        setBottom(host.getHeight());
        setGhostedVisibility(View.INVISIBLE);
        parent.mRecreateDisplayList = true;
        parent.getDisplayList();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (canvas instanceof HardwareCanvas) {
            HardwareCanvas hwCanvas = (HardwareCanvas) canvas;
            mView.mRecreateDisplayList = true;
            RenderNode renderNode = mView.getDisplayList();
            if (renderNode.isValid()) {
                hwCanvas.drawRenderNode(renderNode);
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
            setGhostedVisibility(inverseVisibility);
        }
    }

    private void setGhostedVisibility(int visibility) {
        mView.mViewFlags = (mView.mViewFlags & ~View.VISIBILITY_MASK) | visibility;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        setGhostedVisibility(View.VISIBLE);
        mView.mGhostView = null;
        final ViewGroup parent = (ViewGroup) mView.getParent();
        if (parent != null) {
            parent.mRecreateDisplayList = true;
            parent.getDisplayList();
        }
    }

    public static void calculateMatrix(View view, ViewGroup host, Matrix matrix) {
        ViewGroup parent = (ViewGroup) view.getParent();
        matrix.reset();
        parent.transformMatrixToGlobal(matrix);
        matrix.preTranslate(-parent.getScrollX(), -parent.getScrollY());
        host.transformMatrixToLocal(matrix);
    }

    public static GhostView addGhost(View view, ViewGroup viewGroup, Matrix matrix) {
        if (!(view.getParent() instanceof ViewGroup)) {
            throw new IllegalArgumentException("Ghosted views must be parented by a ViewGroup");
        }
        ViewGroupOverlay overlay = viewGroup.getOverlay();
        ViewOverlay.OverlayViewGroup overlayViewGroup = overlay.mOverlayViewGroup;
        GhostView ghostView = view.mGhostView;
        if (ghostView != null) {
            ViewGroup oldParent = (ViewGroup) ghostView.getParent();
            if (oldParent != overlayViewGroup) {
                oldParent.removeView(ghostView);
                ghostView = null;
            }
        }
        if (ghostView == null) {
            if (matrix == null) {
                matrix = new Matrix();
                calculateMatrix(view, viewGroup, matrix);
            }
            ghostView = new GhostView(view, (ViewGroup) overlayViewGroup.mHostView, matrix);
            overlay.add(ghostView);
        }
        return ghostView;
    }

    public static GhostView addGhost(View view, ViewGroup viewGroup) {
        return addGhost(view, viewGroup, null);
    }

    public static void removeGhost(View view) {
        GhostView ghostView = view.mGhostView;
        if (ghostView != null) {
            ViewGroup parent = (ViewGroup) ghostView.getParent();
            parent.removeView(ghostView);
        }
    }

    public static GhostView getGhost(View view) {
        return view.mGhostView;
    }
}
