/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.animation;

import android.annotation.Nullable;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver.OnDrawListener;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * A {@link UIComponent} wrapping a {@link View}. After being attached to the transition leash, this
 * class will draw the content of the {@link View} directly into the leash, and the actual View will
 * be changed to INVISIBLE in its view tree. This allows the {@link View} to transform in the
 * full-screen size leash without being constrained by the view tree's boundary or inheriting its
 * parent's alpha and transformation.
 *
 * @hide
 */
public class ViewUIComponent implements UIComponent {
    private static final String TAG = "ViewUIComponent";
    private static final boolean DEBUG = Build.IS_USERDEBUG || Log.isLoggable(TAG, Log.DEBUG);
    private final Path mClippingPath = new Path();
    private final Outline mClippingOutline = new Outline();

    private final OnDrawListener mOnDrawListener = this::postDraw;
    private final View mView;

    @Nullable private SurfaceControl mSurfaceControl;
    @Nullable private Surface mSurface;
    @Nullable private Rect mViewBoundsOverride;
    private boolean mVisibleOverride;
    private boolean mDirty;

    public ViewUIComponent(View view) {
        mView = view;
    }

    /**
     * @return the view wrapped by this UI component.
     * @hide
     */
    public View getView() {
        return mView;
    }

    @Override
    public float getAlpha() {
        return mView.getAlpha();
    }

    @Override
    public boolean isVisible() {
        return isAttachedToLeash() ? mVisibleOverride : mView.getVisibility() == View.VISIBLE;
    }

    @Override
    public Rect getBounds() {
        if (isAttachedToLeash() && mViewBoundsOverride != null) {
            return mViewBoundsOverride;
        }
        return getRealBounds();
    }

    @Override
    public Transaction newTransaction() {
        return new Transaction();
    }

    private void attachToTransitionLeash(SurfaceControl transitionLeash, int w, int h) {
        logD("attachToTransitionLeash");
        // Remember current visibility.
        mVisibleOverride = mView.getVisibility() == View.VISIBLE;

        // Create the surface
        mSurfaceControl =
                new SurfaceControl.Builder().setName("ViewUIComponent").setBufferSize(w, h).build();
        mSurface = new Surface(mSurfaceControl);

        // Attach surface to transition leash
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        t.reparent(mSurfaceControl, transitionLeash).show(mSurfaceControl);

        // Make sure view draw triggers surface draw.
        mView.getViewTreeObserver().addOnDrawListener(mOnDrawListener);

        // Make the view invisible AFTER the surface is shown.
        t.addTransactionCommittedListener(
                        mView::post,
                        () -> {
                            logD("Surface attached!");
                            forceDraw();
                            mView.setVisibility(View.INVISIBLE);
                        })
                .apply();
    }

    private void detachFromTransitionLeash(Executor executor, Runnable onDone) {
        logD("detachFromTransitionLeash");
        Surface s = mSurface;
        SurfaceControl sc = mSurfaceControl;
        mSurface = null;
        mSurfaceControl = null;
        mView.getViewTreeObserver().removeOnDrawListener(mOnDrawListener);
        // Restore view visibility
        mView.setVisibility(mVisibleOverride ? View.VISIBLE : View.INVISIBLE);
        mView.invalidate();
        // Clean up surfaces.
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        t.reparent(sc, null)
                .addTransactionCommittedListener(
                        mView::post,
                        () -> {
                            s.release();
                            sc.release();
                            executor.execute(onDone);
                        });
        // Apply transaction AFTER the view is drawn.
        mView.getRootSurfaceControl().applyTransactionOnDraw(t);
    }

    @Override
    public String toString() {
        return "ViewUIComponent{"
                + "alpha="
                + getAlpha()
                + ", visible="
                + isVisible()
                + ", bounds="
                + getBounds()
                + ", attached="
                + isAttachedToLeash()
                + "}";
    }

    private void draw() {
        if (!mDirty) {
            // No need to draw. This is probably a duplicate call.
            logD("draw: skipped - clean");
            return;
        }
        mDirty = false;
        if (!isAttachedToLeash()) {
            // Not attached.
            logD("draw: skipped - not attached");
            return;
        }
        ViewGroup.LayoutParams params = mView.getLayoutParams();
        if (params == null || params.width == 0 || params.height == 0) {
            // layout pass didn't happen.
            logD("draw: skipped - no layout");
            return;
        }
        Canvas canvas = mSurface.lockHardwareCanvas();
        // Clear the canvas first.
        canvas.drawColor(0, PorterDuff.Mode.CLEAR);
        if (mVisibleOverride) {
            Rect realBounds = getRealBounds();
            Rect renderBounds = getBounds();
            canvas.translate(renderBounds.left, renderBounds.top);
            canvas.scale(
                    (float) renderBounds.width() / realBounds.width(),
                    (float) renderBounds.height() / realBounds.height());

            if (mView.getClipToOutline()) {
                mView.getOutlineProvider().getOutline(mView, mClippingOutline);
                mClippingPath.reset();
                RectF rect = new RectF(0, 0, mView.getWidth(), mView.getHeight());
                final float cornerRadius = mClippingOutline.getRadius();
                mClippingPath.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW);
                mClippingPath.close();
                canvas.clipPath(mClippingPath);
            }

            canvas.saveLayerAlpha(null, (int) (255 * mView.getAlpha()));
            mView.draw(canvas);
            canvas.restore();
        }
        mSurface.unlockCanvasAndPost(canvas);
        logD("draw: done");
    }

    private void forceDraw() {
        mDirty = true;
        draw();
    }

    private Rect getRealBounds() {
        Rect output = new Rect();
        mView.getBoundsOnScreen(output);
        return output;
    }

    private boolean isAttachedToLeash() {
        return mSurfaceControl != null && mSurface != null;
    }

    private void logD(String msg) {
        if (DEBUG) {
            Log.d(TAG, msg);
        }
    }

    private void setVisible(boolean visible) {
        logD("setVisibility: " + visible);
        if (isAttachedToLeash()) {
            mVisibleOverride = visible;
            postDraw();
        } else {
            mView.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private void setBounds(Rect bounds) {
        logD("setBounds: " + bounds);
        mViewBoundsOverride = bounds;
        if (isAttachedToLeash()) {
            postDraw();
        } else {
            Log.w(TAG, "setBounds: not attached to leash!");
        }
    }

    private void setAlpha(float alpha) {
        logD("setAlpha: " + alpha);
        mView.setAlpha(alpha);
        if (isAttachedToLeash()) {
            postDraw();
        }
    }

    private void postDraw() {
        if (mDirty) {
            return;
        }
        mDirty = true;
        mView.post(this::draw);
    }

    /** @hide */
    public static class Transaction implements UIComponent.Transaction<ViewUIComponent> {
        private final List<Runnable> mChanges = new ArrayList<>();

        @Override
        public Transaction setAlpha(ViewUIComponent ui, float alpha) {
            mChanges.add(() -> ui.mView.post(() -> ui.setAlpha(alpha)));
            return this;
        }

        @Override
        public Transaction setVisible(ViewUIComponent ui, boolean visible) {
            mChanges.add(() -> ui.mView.post(() -> ui.setVisible(visible)));
            return this;
        }

        @Override
        public Transaction setBounds(ViewUIComponent ui, Rect bounds) {
            mChanges.add(() -> ui.mView.post(() -> ui.setBounds(bounds)));
            return this;
        }

        @Override
        public Transaction attachToTransitionLeash(
                ViewUIComponent ui, SurfaceControl transitionLeash, int w, int h) {
            mChanges.add(
                    () -> ui.mView.post(() -> ui.attachToTransitionLeash(transitionLeash, w, h)));
            return this;
        }

        @Override
        public Transaction detachFromTransitionLeash(
                ViewUIComponent ui, Executor executor, Runnable onDone) {
            mChanges.add(() -> ui.mView.post(() -> ui.detachFromTransitionLeash(executor, onDone)));
            return this;
        }

        @Override
        public void commit() {
            mChanges.forEach(Runnable::run);
            mChanges.clear();
        }
    }
}
