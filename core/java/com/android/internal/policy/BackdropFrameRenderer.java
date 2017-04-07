/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.internal.policy;

import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.view.Choreographer;
import android.view.DisplayListCanvas;
import android.view.RenderNode;
import android.view.ThreadedRenderer;

/**
 * The thread which draws a fill in background while the app is resizing in areas where the app
 * content draw is lagging behind the resize operation.
 * It starts with the creation and it ends once someone calls destroy().
 * Any size changes can be passed by a call to setTargetRect will passed to the thread and
 * executed via the Choreographer.
 * @hide
 */
public class BackdropFrameRenderer extends Thread implements Choreographer.FrameCallback {

    private DecorView mDecorView;

    // This is containing the last requested size by a resize command. Note that this size might
    // or might not have been applied to the output already.
    private final Rect mTargetRect = new Rect();

    // The render nodes for the multi threaded renderer.
    private ThreadedRenderer mRenderer;
    private RenderNode mFrameAndBackdropNode;
    private RenderNode mSystemBarBackgroundNode;

    private final Rect mOldTargetRect = new Rect();
    private final Rect mNewTargetRect = new Rect();

    private Choreographer mChoreographer;

    // Cached size values from the last render for the case that the view hierarchy is gone
    // during a configuration change.
    private int mLastContentWidth;
    private int mLastContentHeight;
    private int mLastCaptionHeight;
    private int mLastXOffset;
    private int mLastYOffset;

    // Whether to report when next frame is drawn or not.
    private boolean mReportNextDraw;

    private Drawable mCaptionBackgroundDrawable;
    private Drawable mUserCaptionBackgroundDrawable;
    private Drawable mResizingBackgroundDrawable;
    private ColorDrawable mStatusBarColor;
    private ColorDrawable mNavigationBarColor;
    private boolean mOldFullscreen;
    private boolean mFullscreen;
    private final int mResizeMode;
    private final Rect mOldSystemInsets = new Rect();
    private final Rect mOldStableInsets = new Rect();
    private final Rect mSystemInsets = new Rect();
    private final Rect mStableInsets = new Rect();
    private final Rect mTmpRect = new Rect();

    public BackdropFrameRenderer(DecorView decorView, ThreadedRenderer renderer, Rect initialBounds,
            Drawable resizingBackgroundDrawable, Drawable captionBackgroundDrawable,
            Drawable userCaptionBackgroundDrawable, int statusBarColor, int navigationBarColor,
            boolean fullscreen, Rect systemInsets, Rect stableInsets, int resizeMode) {
        setName("ResizeFrame");

        mRenderer = renderer;
        onResourcesLoaded(decorView, resizingBackgroundDrawable, captionBackgroundDrawable,
                userCaptionBackgroundDrawable, statusBarColor, navigationBarColor);

        // Create a render node for the content and frame backdrop
        // which can be resized independently from the content.
        mFrameAndBackdropNode = RenderNode.create("FrameAndBackdropNode", null);

        mRenderer.addRenderNode(mFrameAndBackdropNode, true);

        // Set the initial bounds and draw once so that we do not get a broken frame.
        mTargetRect.set(initialBounds);
        mFullscreen = fullscreen;
        mOldFullscreen = fullscreen;
        mSystemInsets.set(systemInsets);
        mStableInsets.set(stableInsets);
        mOldSystemInsets.set(systemInsets);
        mOldStableInsets.set(stableInsets);
        mResizeMode = resizeMode;

        // Kick off our draw thread.
        start();
    }

    void onResourcesLoaded(DecorView decorView, Drawable resizingBackgroundDrawable,
            Drawable captionBackgroundDrawableDrawable, Drawable userCaptionBackgroundDrawable,
            int statusBarColor, int navigationBarColor) {
        mDecorView = decorView;
        mResizingBackgroundDrawable = resizingBackgroundDrawable != null
                        && resizingBackgroundDrawable.getConstantState() != null
                ? resizingBackgroundDrawable.getConstantState().newDrawable()
                : null;
        mCaptionBackgroundDrawable = captionBackgroundDrawableDrawable != null
                        && captionBackgroundDrawableDrawable.getConstantState() != null
                ? captionBackgroundDrawableDrawable.getConstantState().newDrawable()
                : null;
        mUserCaptionBackgroundDrawable = userCaptionBackgroundDrawable != null
                        && userCaptionBackgroundDrawable.getConstantState() != null
                ? userCaptionBackgroundDrawable.getConstantState().newDrawable()
                : null;
        if (mCaptionBackgroundDrawable == null) {
            mCaptionBackgroundDrawable = mResizingBackgroundDrawable;
        }
        if (statusBarColor != 0) {
            mStatusBarColor = new ColorDrawable(statusBarColor);
            addSystemBarNodeIfNeeded();
        } else {
            mStatusBarColor = null;
        }
        if (navigationBarColor != 0) {
            mNavigationBarColor = new ColorDrawable(navigationBarColor);
            addSystemBarNodeIfNeeded();
        } else {
            mNavigationBarColor = null;
        }
    }

    private void addSystemBarNodeIfNeeded() {
        if (mSystemBarBackgroundNode != null) {
            return;
        }
        mSystemBarBackgroundNode = RenderNode.create("SystemBarBackgroundNode", null);
        mRenderer.addRenderNode(mSystemBarBackgroundNode, false);
    }

    /**
     * Call this function asynchronously when the window size has been changed or when the insets
     * have changed or whether window switched between a fullscreen or non-fullscreen layout.
     * The change will be picked up once per frame and the frame will be re-rendered accordingly.
     *
     * @param newTargetBounds The new target bounds.
     * @param fullscreen Whether the window is currently drawing in fullscreen.
     * @param systemInsets The current visible system insets for the window.
     * @param stableInsets The stable insets for the window.
     */
    public void setTargetRect(Rect newTargetBounds, boolean fullscreen, Rect systemInsets,
            Rect stableInsets) {
        synchronized (this) {
            mFullscreen = fullscreen;
            mTargetRect.set(newTargetBounds);
            mSystemInsets.set(systemInsets);
            mStableInsets.set(stableInsets);
            // Notify of a bounds change.
            pingRenderLocked(false /* drawImmediate */);
        }
    }

    /**
     * The window got replaced due to a configuration change.
     */
    public void onConfigurationChange() {
        synchronized (this) {
            if (mRenderer != null) {
                // Enforce a window redraw.
                mOldTargetRect.set(0, 0, 0, 0);
                pingRenderLocked(false /* drawImmediate */);
            }
        }
    }

    /**
     * All resources of the renderer will be released. This function can be called from the
     * the UI thread as well as the renderer thread.
     */
    public void releaseRenderer() {
        synchronized (this) {
            if (mRenderer != null) {
                // Invalidate the current content bounds.
                mRenderer.setContentDrawBounds(0, 0, 0, 0);

                // Remove the render node again
                // (see comment above - better to do that only once).
                mRenderer.removeRenderNode(mFrameAndBackdropNode);
                if (mSystemBarBackgroundNode != null) {
                    mRenderer.removeRenderNode(mSystemBarBackgroundNode);
                }

                mRenderer = null;

                // Exit the renderer loop.
                pingRenderLocked(false /* drawImmediate */);
            }
        }
    }

    @Override
    public void run() {
        try {
            Looper.prepare();
            synchronized (this) {
                mChoreographer = Choreographer.getInstance();
            }
            Looper.loop();
        } finally {
            releaseRenderer();
        }
        synchronized (this) {
            // Make sure no more messages are being sent.
            mChoreographer = null;
            Choreographer.releaseInstance();
        }
    }

    /**
     * The implementation of the FrameCallback.
     * @param frameTimeNanos The time in nanoseconds when the frame started being rendered,
     * in the {@link System#nanoTime()} timebase.  Divide this value by {@code 1000000}
     */
    @Override
    public void doFrame(long frameTimeNanos) {
        synchronized (this) {
            if (mRenderer == null) {
                reportDrawIfNeeded();
                // Tell the looper to stop. We are done.
                Looper.myLooper().quit();
                return;
            }
            doFrameUncheckedLocked();
        }
    }

    private void doFrameUncheckedLocked() {
        mNewTargetRect.set(mTargetRect);
        if (!mNewTargetRect.equals(mOldTargetRect)
                || mOldFullscreen != mFullscreen
                || !mStableInsets.equals(mOldStableInsets)
                || !mSystemInsets.equals(mOldSystemInsets)
                || mReportNextDraw) {
            mOldFullscreen = mFullscreen;
            mOldTargetRect.set(mNewTargetRect);
            mOldSystemInsets.set(mSystemInsets);
            mOldStableInsets.set(mStableInsets);
            redrawLocked(mNewTargetRect, mFullscreen, mSystemInsets, mStableInsets);
        }
    }

    /**
     * The content is about to be drawn and we got the location of where it will be shown.
     * If a "redrawLocked" call has already been processed, we will re-issue the call
     * if the previous call was ignored since the size was unknown.
     * @param xOffset The x offset where the content is drawn to.
     * @param yOffset The y offset where the content is drawn to.
     * @param xSize The width size of the content. This should not be 0.
     * @param ySize The height of the content.
     * @return true if a frame should be requested after the content is drawn; false otherwise.
     */
    public boolean onContentDrawn(int xOffset, int yOffset, int xSize, int ySize) {
        synchronized (this) {
            final boolean firstCall = mLastContentWidth == 0;
            // The current content buffer is drawn here.
            mLastContentWidth = xSize;
            mLastContentHeight = ySize - mLastCaptionHeight;
            mLastXOffset = xOffset;
            mLastYOffset = yOffset;

            // Inform the renderer of the content's new bounds
            mRenderer.setContentDrawBounds(
                    mLastXOffset,
                    mLastYOffset,
                    mLastXOffset + mLastContentWidth,
                    mLastYOffset + mLastCaptionHeight + mLastContentHeight);

            // If this was the first call and redrawLocked got already called prior
            // to us, we should re-issue a redrawLocked now.
            return firstCall
                    && (mLastCaptionHeight != 0 || !mDecorView.isShowingCaption());
        }
    }

    public void onRequestDraw(boolean reportNextDraw) {
        synchronized (this) {
            mReportNextDraw = reportNextDraw;
            mOldTargetRect.set(0, 0, 0, 0);
            pingRenderLocked(true /* drawImmediate */);
        }
    }

    /**
     * Redraws the background, the caption and the system inset backgrounds if something changed.
     *
     * @param newBounds The window bounds which needs to be drawn.
     * @param fullscreen Whether the window is currently drawing in fullscreen.
     * @param systemInsets The current visible system insets for the window.
     * @param stableInsets The stable insets for the window.
     */
    private void redrawLocked(Rect newBounds, boolean fullscreen, Rect systemInsets,
            Rect stableInsets) {

        // While a configuration change is taking place the view hierarchy might become
        // inaccessible. For that case we remember the previous metrics to avoid flashes.
        // Note that even when there is no visible caption, the caption child will exist.
        final int captionHeight = mDecorView.getCaptionHeight();

        // The caption height will probably never dynamically change while we are resizing.
        // Once set to something other then 0 it should be kept that way.
        if (captionHeight != 0) {
            // Remember the height of the caption.
            mLastCaptionHeight = captionHeight;
        }

        // Make sure that the other thread has already prepared the render draw calls for the
        // content. If any size is 0, we have to wait for it to be drawn first.
        if ((mLastCaptionHeight == 0 && mDecorView.isShowingCaption()) ||
                mLastContentWidth == 0 || mLastContentHeight == 0) {
            return;
        }

        // Since the surface is spanning the entire screen, we have to add the start offset of
        // the bounds to get to the surface location.
        final int left = mLastXOffset + newBounds.left;
        final int top = mLastYOffset + newBounds.top;
        final int width = newBounds.width();
        final int height = newBounds.height();

        mFrameAndBackdropNode.setLeftTopRightBottom(left, top, left + width, top + height);

        // Draw the caption and content backdrops in to our render node.
        DisplayListCanvas canvas = mFrameAndBackdropNode.start(width, height);
        final Drawable drawable = mUserCaptionBackgroundDrawable != null
                ? mUserCaptionBackgroundDrawable : mCaptionBackgroundDrawable;

        if (drawable != null) {
            drawable.setBounds(0, 0, left + width, top + mLastCaptionHeight);
            drawable.draw(canvas);
        }

        // The backdrop: clear everything with the background. Clipping is done elsewhere.
        if (mResizingBackgroundDrawable != null) {
            mResizingBackgroundDrawable.setBounds(0, mLastCaptionHeight, left + width, top + height);
            mResizingBackgroundDrawable.draw(canvas);
        }
        mFrameAndBackdropNode.end(canvas);

        drawColorViews(left, top, width, height, fullscreen, systemInsets, stableInsets);

        // We need to render the node explicitly
        mRenderer.drawRenderNode(mFrameAndBackdropNode);

        reportDrawIfNeeded();
    }

    private void drawColorViews(int left, int top, int width, int height,
            boolean fullscreen, Rect systemInsets, Rect stableInsets) {
        if (mSystemBarBackgroundNode == null) {
            return;
        }
        DisplayListCanvas canvas = mSystemBarBackgroundNode.start(width, height);
        mSystemBarBackgroundNode.setLeftTopRightBottom(left, top, left + width, top + height);
        final int topInset = DecorView.getColorViewTopInset(mStableInsets.top, mSystemInsets.top);
        if (mStatusBarColor != null) {
            mStatusBarColor.setBounds(0, 0, left + width, topInset);
            mStatusBarColor.draw(canvas);
        }

        // We only want to draw the navigation bar if our window is currently fullscreen because we
        // don't want the navigation bar background be moving around when resizing in docked mode.
        // However, we need it for the transitions into/out of docked mode.
        if (mNavigationBarColor != null && fullscreen) {
            DecorView.getNavigationBarRect(width, height, stableInsets, systemInsets, mTmpRect);
            mNavigationBarColor.setBounds(mTmpRect);
            mNavigationBarColor.draw(canvas);
        }
        mSystemBarBackgroundNode.end(canvas);
        mRenderer.drawRenderNode(mSystemBarBackgroundNode);
    }

    /** Notify view root that a frame has been drawn by us, if it has requested so. */
    private void reportDrawIfNeeded() {
        if (mReportNextDraw) {
            if (mDecorView.isAttachedToWindow()) {
                mDecorView.getViewRootImpl().reportDrawFinish();
            }
            mReportNextDraw = false;
        }
    }

    /**
     * Sends a message to the renderer to wake up and perform the next action which can be
     * either the next rendering or the self destruction if mRenderer is null.
     * Note: This call must be synchronized.
     *
     * @param drawImmediate if we should draw immediately instead of scheduling a frame
     */
    private void pingRenderLocked(boolean drawImmediate) {
        if (mChoreographer != null && !drawImmediate) {
            mChoreographer.postFrameCallback(this);
        } else {
            doFrameUncheckedLocked();
        }
    }

    void setUserCaptionBackgroundDrawable(Drawable userCaptionBackgroundDrawable) {
        mUserCaptionBackgroundDrawable = userCaptionBackgroundDrawable;
    }
}
