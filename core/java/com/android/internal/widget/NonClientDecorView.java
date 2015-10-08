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
 * limitations under the License.
 */

package com.android.internal.widget;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Looper;
import android.os.RemoteException;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.DisplayListCanvas;
import android.view.MotionEvent;
import android.view.RenderNode;
import android.view.ThreadedRenderer;
import android.view.View;
import android.widget.LinearLayout;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowCallbacks;
import android.util.Log;
import android.util.TypedValue;

import com.android.internal.R;
import com.android.internal.policy.PhoneWindow;

/**
 * This class represents the special screen elements to control a window on free form
 * environment. All thse screen elements are added in the "non client area" which is the area of
 * the window which is handled by the OS and not the application.
 * As such this class handles the following things:
 * <ul>
 * <li>The caption, containing the system buttons like maximize, close and such as well as
 * allowing the user to drag the window around.</li>
 * <li>The shadow - which is changing dependent on the window focus.</li>
 * <li>The border around the client area (if there is one).</li>
 * <li>The resize handles which allow to resize the window.</li>
 * </ul>
 * After creating the view, the function
 * {@link #setPhoneWindow(PhoneWindow owner, boolean windowHasShadow)} needs to be called to make
 * the connection to it's owning PhoneWindow.
 * Note: At this time the application can change various attributes of the DecorView which
 * will break things (in settle/unexpected ways):
 * <ul>
 * <li>setElevation</li>
 * <li>setOutlineProvider</li>
 * <li>setSurfaceFormat</li>
 * <li>..</li>
 * </ul>
 * This will be mitigated once b/22527834 will be addressed.
 */
public class NonClientDecorView extends LinearLayout
        implements View.OnClickListener, View.OnTouchListener, WindowCallbacks {
    private final static String TAG = "NonClientDecorView";
    // The height of a window which has focus in DIP.
    private final int DECOR_SHADOW_FOCUSED_HEIGHT_IN_DIP = 20;
    // The height of a window which has not in DIP.
    private final int DECOR_SHADOW_UNFOCUSED_HEIGHT_IN_DIP = 5;
    private PhoneWindow mOwner = null;
    private boolean mWindowHasShadow = false;
    private boolean mShowDecor = false;
    // True when this object is listening for window size changes.
    private boolean mAttachedCallbacksToRootViewImpl = false;

    // True if the window is being dragged.
    private boolean mDragging = false;

    // True when the left mouse button got released while dragging.
    private boolean mLeftMouseButtonReleased;

    // True if this window is resizable (which is currently only true when the decor is shown).
    public boolean mVisible = false;

    // The current focus state of the window for updating the window elevation.
    private boolean mWindowHasFocus = true;

    // Cludge to address b/22668382: Set the shadow size to the maximum so that the layer
    // size calculation takes the shadow size into account. We set the elevation currently
    // to max until the first layout command has been executed.
    private boolean mAllowUpdateElevation = false;

    // The resize frame renderer.
    private ResizeFrameThread mFrameRendererThread = null;

    public NonClientDecorView(Context context) {
        super(context);
    }

    public NonClientDecorView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public NonClientDecorView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!mAttachedCallbacksToRootViewImpl) {
            // If there is no window callback installed there was no window set before. Set it now.
            // Note that our ViewRootImpl object will not change.
            getViewRootImpl().addWindowCallbacks(this);
            mAttachedCallbacksToRootViewImpl = true;
        } else if (mFrameRendererThread != null) {
            // We are resizing and this call happened due to a configuration change. Tell the
            // renderer about it.
            mFrameRendererThread.onConfigurationChange();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAttachedCallbacksToRootViewImpl) {
            getViewRootImpl().removeWindowCallbacks(this);
            mAttachedCallbacksToRootViewImpl = false;
        }
    }

    public void setPhoneWindow(PhoneWindow owner, boolean showDecor, boolean windowHasShadow) {
        mOwner = owner;
        mWindowHasShadow = windowHasShadow;
        mShowDecor = showDecor;
        updateCaptionVisibility();
        if (mWindowHasShadow) {
            initializeElevation();
        }
        // By changing the outline provider to BOUNDS, the window can remove its
        // background without removing the shadow.
        mOwner.getDecorView().setOutlineProvider(ViewOutlineProvider.BOUNDS);

        findViewById(R.id.maximize_window).setOnClickListener(this);
        findViewById(R.id.close_window).setOnClickListener(this);
    }

    @Override
    public boolean onTouch(View v, MotionEvent e) {
        // Note: There are no mixed events. When a new device gets used (e.g. 1. Mouse, 2. touch)
        // the old input device events get cancelled first. So no need to remember the kind of
        // input device we are listening to.
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!mShowDecor) {
                    // When there is no decor we should not react to anything.
                    return false;
                }
                // A drag action is started if we aren't dragging already and the starting event is
                // either a left mouse button or any other input device.
                if (!mDragging &&
                        (e.getToolType(e.getActionIndex()) != MotionEvent.TOOL_TYPE_MOUSE ||
                                (e.getButtonState() & MotionEvent.BUTTON_PRIMARY) != 0)) {
                    mDragging = true;
                    mLeftMouseButtonReleased = false;
                    startMovingTask(e.getRawX(), e.getRawY());
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (mDragging && !mLeftMouseButtonReleased) {
                    if (e.getToolType(e.getActionIndex()) == MotionEvent.TOOL_TYPE_MOUSE &&
                            (e.getButtonState() & MotionEvent.BUTTON_PRIMARY) == 0) {
                        // There is no separate mouse button up call and if the user mixes mouse
                        // button drag actions, we stop dragging once he releases the button.
                        mLeftMouseButtonReleased = true;
                        break;
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!mDragging) {
                    break;
                }
                // Abort the ongoing dragging.
                mDragging = false;
                return true;
        }
        return mDragging;
    }

    /**
     * The phone window configuration has changed and the decor needs to be updated.
     * @param showDecor True if the decor should be shown.
     * @param windowHasShadow True when the window should show a shadow.
     **/
    public void phoneWindowUpdated(boolean showDecor, boolean windowHasShadow) {
        mShowDecor = showDecor;
        updateCaptionVisibility();
        if (windowHasShadow != mWindowHasShadow) {
            mWindowHasShadow = windowHasShadow;
            initializeElevation();
        }
    }

    @Override
    public void onClick(View view) {
        if (view.getId() == R.id.maximize_window) {
            maximizeWindow();
        } else if (view.getId() == R.id.close_window) {
            mOwner.dispatchOnWindowDismissed(true /*finishTask*/);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        mWindowHasFocus = hasWindowFocus;
        updateElevation();
        super.onWindowFocusChanged(hasWindowFocus);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        // If the application changed its SystemUI metrics, we might also have to adapt
        // our shadow elevation.
        updateElevation();
        mAllowUpdateElevation = true;

        super.onLayout(changed, left, top, right, bottom);
    }

    @Override
    public void addView(View child, int index, ViewGroup.LayoutParams params) {
        // Make sure that we never get more then one client area in our view.
        if (index >= 2 || getChildCount() >= 2) {
            throw new IllegalStateException("NonClientDecorView can only handle 1 client view");
        }
        super.addView(child, index, params);
    }

    /**
     * Determine if the workspace is entirely covered by the window.
     * @return Returns true when the window is filling the entire screen/workspace.
     **/
    private boolean isFillingScreen() {
        return (0 != ((getWindowSystemUiVisibility() | getSystemUiVisibility()) &
                (View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_IMMERSIVE | View.SYSTEM_UI_FLAG_LOW_PROFILE)));
    }

    /**
     * Updates the visibility of the caption.
     **/
    private void updateCaptionVisibility() {
        // Don't show the decor if the window has e.g. entered full screen.
        boolean invisible = isFillingScreen() || !mShowDecor;
        View caption = getChildAt(0);
        caption.setVisibility(invisible ? GONE : VISIBLE);
        caption.setOnTouchListener(this);
        mVisible = !invisible;
    }

    /**
     * The elevation gets set for the first time and the framework needs to be informed that
     * the surface layer gets created with the shadow size in mind.
     **/
    private void initializeElevation() {
        // TODO(skuhne): Call setMaxElevation here accordingly after b/22668382 got fixed.
        mAllowUpdateElevation = false;
        if (mWindowHasShadow) {
            updateElevation();
        } else {
            mOwner.setElevation(0);
        }
    }

    /**
     * The shadow height gets controlled by the focus to visualize highlighted windows.
     * Note: This will overwrite application elevation properties.
     * Note: Windows which have (temporarily) changed their attributes to cover the SystemUI
     *       will get no shadow as they are expected to be "full screen".
     **/
    private void updateElevation() {
        float elevation = 0;
        // Do not use a shadow when we are in resizing mode (mRenderer not null) since the shadow
        // is bound to the content size and not the target size.
        if (mWindowHasShadow && mFrameRendererThread == null) {
            boolean fill = isFillingScreen();
            elevation = fill ? 0 :
                    (mWindowHasFocus ? DECOR_SHADOW_FOCUSED_HEIGHT_IN_DIP :
                            DECOR_SHADOW_UNFOCUSED_HEIGHT_IN_DIP);
            // TODO(skuhne): Remove this if clause once b/22668382 got fixed.
            if (!mAllowUpdateElevation && !fill) {
                elevation = DECOR_SHADOW_FOCUSED_HEIGHT_IN_DIP;
            }
            // Convert the DP elevation into physical pixels.
            elevation = dipToPx(elevation);
        }
        // Don't change the elevation if it didn't change since it can require some time.
        if (mOwner.getDecorView().getElevation() != elevation) {
            mOwner.setElevation(elevation);
        }
    }

    /**
     * Converts a DIP measure into physical pixels.
     * @param dip The dip value.
     * @return Returns the number of pixels.
     */
    private float dipToPx(float dip) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dip,
                getResources().getDisplayMetrics());
    }

    /**
     * Maximize the window by moving it to the maximized workspace stack.
     **/
    private void maximizeWindow() {
        Window.WindowControllerCallback callback = mOwner.getWindowControllerCallback();
        if (callback != null) {
            try {
                callback.changeWindowStack(
                        android.app.ActivityManager.FULLSCREEN_WORKSPACE_STACK_ID);
            } catch (RemoteException ex) {
                Log.e(TAG, "Cannot change task workspace.");
            }
        }
    }

    @Override
    public void onWindowDragResizeStart(Rect initialBounds) {
        if (mOwner.isDestroyed()) {
            // If the owner's window is gone, we should not be able to come here anymore.
            releaseResources();
            return;
        }
        if (mFrameRendererThread != null) {
            return;
        }
        final ThreadedRenderer renderer =
                (ThreadedRenderer) mOwner.getDecorView().getHardwareRenderer();
        if (renderer != null) {
            mFrameRendererThread = new ResizeFrameThread(renderer, initialBounds);
            // Get rid of the shadow while we are resizing. Shadow drawing takes considerable time.
            // If we want to get the shadow shown while resizing, we would need to elevate a new
            // element which owns the caption and has the elevation.
            updateElevation();
        }
    }

    @Override
    public void onContentDraw(int xOffset, int yOffset, int xSize, int ySize) {
        if (mFrameRendererThread != null) {
            mFrameRendererThread.onContentDraw(xOffset, yOffset, xSize, ySize);
        }
    }

    @Override
    public void onWindowDragResizeEnd() {
        releaseThreadedRenderer();
    }

    @Override
    public void onWindowSizeIsChanging(Rect newBounds) {
        if (mFrameRendererThread != null) {
            mFrameRendererThread.setTargetRect(newBounds);
        }
    }

    /**
     * Release the renderer thread which is usually done when the user stops resizing.
     */
    private void releaseThreadedRenderer() {
        if (mFrameRendererThread != null) {
            mFrameRendererThread.releaseRenderer();
            mFrameRendererThread = null;
            // Bring the shadow back.
            updateElevation();
        }
    }

    /**
     * Called when the parent window is destroyed to release all resources. Note that this will also
     * destroy the renderer thread.
     */
    private void releaseResources() {
        releaseThreadedRenderer();
    }

    /**
     * The thread which draws the chrome while we are resizing.
     * It starts with the creation and it ends once someone calls destroy().
     * Any size changes can be passed by a call to setTargetRect will passed to the thread and
     * executed via the Choreographer.
     */
    private class ResizeFrameThread extends Thread implements Choreographer.FrameCallback {
        // This is containing the last requested size by a resize command. Note that this size might
        // or might not have been applied to the output already.
        private final Rect mTargetRect = new Rect();

        // The render nodes for the multi threaded renderer.
        private ThreadedRenderer mRenderer;
        private RenderNode mFrameNode;
        private RenderNode mBackdropNode;

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

        ResizeFrameThread(ThreadedRenderer renderer, Rect initialBounds) {
            setName("ResizeFrame");
            mRenderer = renderer;

            // Create the render nodes for our frame and backdrop which can be resized independently
            // from the content.
            mFrameNode = RenderNode.create("FrameNode", null);
            mBackdropNode = RenderNode.create("BackdropNode", null);

            mRenderer.addRenderNode(mFrameNode, false);
            mRenderer.addRenderNode(mBackdropNode, true);

            // Set the initial bounds and draw once so that we do not get a broken frame.
            mTargetRect.set(initialBounds);
            synchronized (this) {
                changeWindowSizeLocked(initialBounds);
            }

            // Kick off our draw thread.
            start();
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

                    // Remove the render nodes again
                    // (see comment above - better to do that only once).
                    mRenderer.removeRenderNode(mFrameNode);
                    mRenderer.removeRenderNode(mBackdropNode);

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
                mChoreographer = Choreographer.getInstance();
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
                    // Tell the looper to stop. We are done.
                    Looper.myLooper().quit();
                    return;
                }
                mNewTargetRect.set(mTargetRect);
                if (!mNewTargetRect.equals(mOldTargetRect)) {
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
         */
        public void onContentDraw(int xOffset, int yOffset, int xSize, int ySize) {
            synchronized (this) {
                final boolean firstCall = mLastContentWidth == 0;
                // The current content buffer is drawn here.
                mLastContentWidth = xSize;
                mLastContentHeight = ySize - mLastCaptionHeight;
                mLastXOffset = xOffset;
                mLastYOffset = yOffset;

                mRenderer.setContentDrawBounds(
                        mLastXOffset,
                        mLastYOffset + mLastCaptionHeight,
                        mLastXOffset + mLastContentWidth,
                        mLastYOffset + mLastCaptionHeight + mLastContentHeight);
                // If this was the first call and changeWindowSizeLocked got already called prior
                // to us, we should re-issue a changeWindowSizeLocked now.
                if (firstCall && (mLastCaptionHeight != 0 || !mShowDecor)) {
                    mOldTargetRect.set(0, 0, 0, 0);
                    pingRenderLocked();
                }
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
            View caption = getChildAt(0);
            if (caption != null) {
                final int captionHeight = caption.getHeight();
                // The caption height will probably never dynamically change while we are resizing.
                // Once set to something other then 0 it should be kept that way.
                if (captionHeight != 0) {
                    // Remember the height of the caption.
                    mLastCaptionHeight = captionHeight;
                }
            }
            // Make sure that the other thread has already prepared the render draw calls for the
            // content. If any size is 0, we have to wait for it to be drawn first.
            if ((mLastCaptionHeight == 0 && mShowDecor) ||
                    mLastContentWidth == 0 || mLastContentHeight == 0) {
                return;
            }
            // Since the surface is spanning the entire screen, we have to add the start offset of
            // the bounds to get to the surface location.
            final int left = mLastXOffset + newBounds.left;
            final int top = mLastYOffset + newBounds.top;
            final int width = newBounds.width();
            final int height = newBounds.height();

            // Produce the draw calls.
            // TODO(skuhne): Create a separate caption view which draws this. If the shadow should
            // be resized while the window resizes, this hierarchy needs to have the elevation.
            // That said - it is probably no good idea to draw the shadow every time since it costs
            // a considerable time which we should rather spend for resizing the content and it does
            // barely show while the entire screen is moving.
            mFrameNode.setLeftTopRightBottom(left, top, left + width, top + mLastCaptionHeight);
            DisplayListCanvas canvas = mFrameNode.start(width, height);
            canvas.drawColor(Color.BLACK);
            mFrameNode.end(canvas);

            mBackdropNode.setLeftTopRightBottom(left, top + mLastCaptionHeight, left + width,
                    top + height);

            // The backdrop: clear everything with the background. Clipping is done elsewhere.
            canvas = mBackdropNode.start(width, height - mLastCaptionHeight);
            // TODO(skuhne): mOwner.getDecorView().mBackgroundFallback.draw(..) - or similar.
            // Note: This might not work (calculator for example uses a transparent background).
            canvas.drawColor(0xff808080);
            mBackdropNode.end(canvas);

            // We need to render both rendered nodes explicitly.
            mRenderer.drawRenderNode(mFrameNode);
            mRenderer.drawRenderNode(mBackdropNode);
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
    }
}
