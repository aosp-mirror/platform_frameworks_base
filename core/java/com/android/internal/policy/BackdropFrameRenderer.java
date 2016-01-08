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
import android.view.View;

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

    public BackdropFrameRenderer(DecorView decorView, ThreadedRenderer renderer, Rect initialBounds,
            Drawable resizingBackgroundDrawable, Drawable captionBackgroundDrawable,
            Drawable userCaptionBackgroundDrawable, int statusBarColor) {
        setName("ResizeFrame");

        mRenderer = renderer;
        onResourcesLoaded(decorView, resizingBackgroundDrawable, captionBackgroundDrawable,
                userCaptionBackgroundDrawable, statusBarColor);

        // Create a render node for the content and frame backdrop
        // which can be resized independently from the content.
        mFrameAndBackdropNode = RenderNode.create("FrameAndBackdropNode", null);

        mRenderer.addRenderNode(mFrameAndBackdropNode, true);

        // Set the initial bounds and draw once so that we do not get a broken frame.
        mTargetRect.set(initialBounds);
        synchronized (this) {
            changeWindowSizeLocked(initialBounds);
        }

        // Kick off our draw thread.
        start();
    }

    void onResourcesLoaded(DecorView decorView, Drawable resizingBackgroundDrawable,
            Drawable captionBackgroundDrawableDrawable, Drawable userCaptionBackgroundDrawable,
            int statusBarColor) {
        mDecorView = decorView;
        mResizingBackgroundDrawable = resizingBackgroundDrawable;
        mCaptionBackgroundDrawable = captionBackgroundDrawableDrawable;
        mUserCaptionBackgroundDrawable = userCaptionBackgroundDrawable;
        if (mCaptionBackgroundDrawable == null) {
            mCaptionBackgroundDrawable = mResizingBackgroundDrawable;
        }
        if (statusBarColor != 0) {
            mStatusBarColor = new ColorDrawable(statusBarColor);
            addSystemBarNodeIfNeeded();
        } else {
            mStatusBarColor = null;
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
     * Call this function asynchronously when the window size has been changed. The change will
     * be picked up once per frame and the frame will be re-rendered accordingly.
     * @param newTargetBounds The new target bounds.
     */
    public void setTargetRect(Rect newTargetBounds) {
        synchronized (this) {
            mTargetRect.set(newTargetBounds);
            // Notify of a bounds change.
            pingRenderLocked();
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
                pingRenderLocked();
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
                pingRenderLocked();
            }
        }
    }

    @Override
    public void run() {
        try {
            Looper.prepare();
            synchronized (this) {
                mChoreographer = Choreographer.getInstance();

                // Draw at least once.
                mChoreographer.postFrameCallback(this);
            }
            Looper.loop();
        } finally {
            releaseRenderer();
        }
        synchronized (this) {
            // Make sure no more messages are being sent.
            mChoreographer = null;
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
            mNewTargetRect.set(mTargetRect);
            if (!mNewTargetRect.equals(mOldTargetRect) || mReportNextDraw) {
                mOldTargetRect.set(mNewTargetRect);
                changeWindowSizeLocked(mNewTargetRect);
            }
        }
    }

    /**
     * The content is about to be drawn and we got the location of where it will be shown.
     * If a "changeWindowSizeLocked" call has already been processed, we will re-issue the call
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

            mRenderer.setContentDrawBounds(
                    mLastXOffset,
                    mLastYOffset,
                    mLastXOffset + mLastContentWidth,
                    mLastYOffset + mLastCaptionHeight + mLastContentHeight);
            // If this was the first call and changeWindowSizeLocked got already called prior
            // to us, we should re-issue a changeWindowSizeLocked now.
            return firstCall
                    && (mLastCaptionHeight != 0 || !mDecorView.isShowingCaption());
        }
    }

    public void onRequestDraw(boolean reportNextDraw) {
        synchronized (this) {
            mReportNextDraw = reportNextDraw;
            mOldTargetRect.set(0, 0, 0, 0);
            pingRenderLocked();
        }
    }

    /**
     * Resizing the frame to fit the new window size.
     * @param newBounds The window bounds which needs to be drawn.
     */
    private void changeWindowSizeLocked(Rect newBounds) {

        // While a configuration change is taking place the view hierarchy might become
        // inaccessible. For that case we remember the previous metrics to avoid flashes.
        // Note that even when there is no visible caption, the caption child will exist.
        final int captionHeight = mDecorView.getCaptionHeight();
        final int statusBarHeight = mDecorView.getStatusBarHeight();

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
        drawable.setBounds(0, 0, left + width, top + mLastCaptionHeight);
        drawable.draw(canvas);

        // The backdrop: clear everything with the background. Clipping is done elsewhere.
        mResizingBackgroundDrawable.setBounds(0, mLastCaptionHeight, left + width, top + height);
        mResizingBackgroundDrawable.draw(canvas);
        mFrameAndBackdropNode.end(canvas);

        if (mSystemBarBackgroundNode != null && mStatusBarColor != null) {
            canvas = mSystemBarBackgroundNode.start(width, height);
            mSystemBarBackgroundNode.setLeftTopRightBottom(left, top, left + width, top + height);
            mStatusBarColor.setBounds(0, 0, left + width, statusBarHeight);
            mStatusBarColor.draw(canvas);
            mSystemBarBackgroundNode.end(canvas);
            mRenderer.drawRenderNode(mSystemBarBackgroundNode);
        }

        // We need to render the node explicitly
        mRenderer.drawRenderNode(mFrameAndBackdropNode);

        reportDrawIfNeeded();
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
     */
    private void pingRenderLocked() {
        if (mChoreographer != null) {
            mChoreographer.postFrameCallback(this);
        }
    }

    void setUserCaptionBackgroundDrawable(Drawable userCaptionBackgroundDrawable) {
        mUserCaptionBackgroundDrawable = userCaptionBackgroundDrawable;
    }
}
