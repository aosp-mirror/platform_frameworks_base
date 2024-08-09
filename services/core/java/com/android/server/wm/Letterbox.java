/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wm;

import static android.os.InputConstants.DEFAULT_DISPATCHING_TIMEOUT_MILLIS;
import static android.view.SurfaceControl.HIDDEN;
import static android.window.TaskConstants.TASK_CHILD_LAYER_LETTERBOX_BACKGROUND;

import android.annotation.NonNull;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.InputConfig;
import android.view.GestureDetector;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputWindowHandle;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.WindowManager;

import com.android.server.UiThread;

import java.util.function.Supplier;

/**
 * Manages a set of {@link SurfaceControl}s to draw a black letterbox between an
 * outer rect and an inner rect.
 */
public class Letterbox {

    static final Rect EMPTY_RECT = new Rect();
    private static final Point ZERO_POINT = new Point(0, 0);

    private final Supplier<SurfaceControl.Builder> mSurfaceControlFactory;
    private final Supplier<SurfaceControl.Transaction> mTransactionFactory;
    private final Supplier<SurfaceControl> mParentSurfaceSupplier;

    private final Rect mOuter = new Rect();
    private final Rect mInner = new Rect();
    private final LetterboxSurface mTop = new LetterboxSurface("top");
    private final LetterboxSurface mLeft = new LetterboxSurface("left");
    private final LetterboxSurface mBottom = new LetterboxSurface("bottom");
    private final LetterboxSurface mRight = new LetterboxSurface("right");
    // One surface that fills the whole window is used over multiple surfaces to:
    // - Prevents wallpaper from peeking through near rounded corners.
    // - For "blurred wallpaper" background, to avoid having visible border between surfaces.
    // One surface approach isn't always preferred over multiple surfaces due to rendering cost
    // for overlaping an app window and letterbox surfaces.
    private final LetterboxSurface mFullWindowSurface = new LetterboxSurface("fullWindow");
    private final LetterboxSurface[] mSurfaces = { mLeft, mTop, mRight, mBottom };
    @NonNull
    private final AppCompatReachabilityPolicy mAppCompatReachabilityPolicy;
    @NonNull
    private final AppCompatLetterboxOverrides mAppCompatLetterboxOverrides;

    /**
     * Constructs a Letterbox.
     *
     * @param surfaceControlFactory a factory for creating the managed {@link SurfaceControl}s
     */
    public Letterbox(Supplier<SurfaceControl.Builder> surfaceControlFactory,
            Supplier<SurfaceControl.Transaction> transactionFactory,
            @NonNull AppCompatReachabilityPolicy appCompatReachabilityPolicy,
            @NonNull AppCompatLetterboxOverrides appCompatLetterboxOverrides,
            Supplier<SurfaceControl> parentSurface) {
        mSurfaceControlFactory = surfaceControlFactory;
        mTransactionFactory = transactionFactory;
        mAppCompatReachabilityPolicy = appCompatReachabilityPolicy;
        mAppCompatLetterboxOverrides = appCompatLetterboxOverrides;
        mParentSurfaceSupplier = parentSurface;
    }

    /**
     * Lays out the letterbox, such that the area between the outer and inner
     * frames will be covered by black color surfaces.
     *
     * The caller must use {@link #applySurfaceChanges} to apply the new layout to the surface.
     * @param outer the outer frame of the letterbox (this frame will be black, except the area
     *              that intersects with the {code inner} frame), in global coordinates
     * @param inner the inner frame of the letterbox (this frame will be clear), in global
     *              coordinates
     * @param surfaceOrigin the origin of the surface factory in global coordinates
     */
    public void layout(Rect outer, Rect inner, Point surfaceOrigin) {
        mOuter.set(outer);
        mInner.set(inner);

        mTop.layout(outer.left, outer.top, outer.right, inner.top, surfaceOrigin);
        mLeft.layout(outer.left, outer.top, inner.left, outer.bottom, surfaceOrigin);
        mBottom.layout(outer.left, inner.bottom, outer.right, outer.bottom, surfaceOrigin);
        mRight.layout(inner.right, outer.top, outer.right, outer.bottom, surfaceOrigin);
        mFullWindowSurface.layout(outer.left, outer.top, outer.right, outer.bottom, surfaceOrigin);
    }

    /**
     * Gets the insets between the outer and inner rects.
     */
    public Rect getInsets() {
        return new Rect(
                mLeft.getWidth(),
                mTop.getHeight(),
                mRight.getWidth(),
                mBottom.getHeight());
    }

    /** @return The frame that used to place the content. */
    Rect getInnerFrame() {
        return mInner;
    }

    /** @return The frame that contains the inner frame and the insets. */
    Rect getOuterFrame() {
        return mOuter;
    }

    /**
     * Returns {@code true} if the letterbox does not overlap with the bar, or the letterbox can
     * fully cover the window frame.
     *
     * @param rect The area of the window frame.
     */
    boolean notIntersectsOrFullyContains(Rect rect) {
        int emptyCount = 0;
        int noOverlappingCount = 0;
        for (LetterboxSurface surface : mSurfaces) {
            final Rect surfaceRect = surface.mLayoutFrameGlobal;
            if (surfaceRect.isEmpty()) {
                // empty letterbox
                emptyCount++;
            } else if (!Rect.intersects(surfaceRect, rect)) {
                // no overlapping
                noOverlappingCount++;
            } else if (surfaceRect.contains(rect)) {
                // overlapping and covered
                return true;
            }
        }
        return (emptyCount + noOverlappingCount) == mSurfaces.length;
    }

    /**
     * Hides the letterbox.
     *
     * The caller must use {@link #applySurfaceChanges} to apply the new layout to the surface.
     */
    public void hide() {
        layout(EMPTY_RECT, EMPTY_RECT, ZERO_POINT);
    }

    /**
     * Destroys the managed {@link SurfaceControl}s.
     */
    public void destroy() {
        mOuter.setEmpty();
        mInner.setEmpty();

        for (LetterboxSurface surface : mSurfaces) {
            surface.remove();
        }
        mFullWindowSurface.remove();
    }

    /** Returns whether a call to {@link #applySurfaceChanges} would change the surface. */
    public boolean needsApplySurfaceChanges() {
        if (useFullWindowSurface()) {
            return mFullWindowSurface.needsApplySurfaceChanges();
        }
        for (LetterboxSurface surface : mSurfaces) {
            if (surface.needsApplySurfaceChanges()) {
                return true;
            }
        }
        return false;
    }

    /** Applies surface changes such as colour, window crop, position and input info. */
    public void applySurfaceChanges(@NonNull SurfaceControl.Transaction t,
            @NonNull SurfaceControl.Transaction inputT) {
        if (useFullWindowSurface()) {
            mFullWindowSurface.applySurfaceChanges(t, inputT);

            for (LetterboxSurface surface : mSurfaces) {
                surface.remove();
            }
        } else {
            for (LetterboxSurface surface : mSurfaces) {
                surface.applySurfaceChanges(t, inputT);
            }

            mFullWindowSurface.remove();
        }
    }

    /** Enables touches to slide into other neighboring surfaces. */
    void attachInput(WindowState win) {
        if (useFullWindowSurface()) {
            mFullWindowSurface.attachInput(win);
        } else {
            for (LetterboxSurface surface : mSurfaces) {
                surface.attachInput(win);
            }
        }
    }

    void onMovedToDisplay(int displayId) {
        for (LetterboxSurface surface : mSurfaces) {
            if (surface.mInputInterceptor != null) {
                surface.mInputInterceptor.mWindowHandle.displayId = displayId;
            }
        }
        if (mFullWindowSurface.mInputInterceptor != null) {
            mFullWindowSurface.mInputInterceptor.mWindowHandle.displayId = displayId;
        }
    }

    /**
     * Returns {@code true} when using {@link #mFullWindowSurface} instead of {@link mSurfaces}.
     */
    private boolean useFullWindowSurface() {
        return mAppCompatLetterboxOverrides.shouldLetterboxHaveRoundedCorners()
                || mAppCompatLetterboxOverrides.hasWallpaperBackgroundForLetterbox();
    }

    private final class TapEventReceiver extends InputEventReceiver {

        private final GestureDetector mDoubleTapDetector;
        private final DoubleTapListener mDoubleTapListener;

        TapEventReceiver(InputChannel inputChannel, WindowManagerService wmService,
                Handler uiHandler) {
            super(inputChannel, uiHandler.getLooper());
            mDoubleTapListener = new DoubleTapListener(wmService);
            mDoubleTapDetector = new GestureDetector(wmService.mContext, mDoubleTapListener,
                    uiHandler);
        }

        @Override
        public void onInputEvent(InputEvent event) {
            final MotionEvent motionEvent = (MotionEvent) event;
            finishInputEvent(event, mDoubleTapDetector.onTouchEvent(motionEvent));
        }
    }

    private class DoubleTapListener extends GestureDetector.SimpleOnGestureListener {
        private final WindowManagerService mWmService;

        private DoubleTapListener(WindowManagerService wmService) {
            mWmService = wmService;
        }

        @Override
        public boolean onDoubleTapEvent(MotionEvent e) {
            synchronized (mWmService.mGlobalLock) {
                // This check prevents late events to be handled in case the Letterbox has been
                // already destroyed and so mOuter.isEmpty() is true.
                if (!mOuter.isEmpty() && e.getAction() == MotionEvent.ACTION_UP) {
                    mAppCompatReachabilityPolicy.handleDoubleTap((int) e.getRawX(),
                            (int) e.getRawY());
                    return true;
                }
                return false;
            }
        }
    }

    private final class InputInterceptor implements Runnable {

        private final InputChannel mClientChannel;
        private final InputWindowHandle mWindowHandle;
        private final InputEventReceiver mInputEventReceiver;
        private final WindowManagerService mWmService;
        private final IBinder mToken;
        private final Handler mHandler;

        InputInterceptor(String namePrefix, WindowState win) {
            mWmService = win.mWmService;
            mHandler = UiThread.getHandler();
            final String name = namePrefix + (win.mActivityRecord != null ? win.mActivityRecord : win);
            mClientChannel = mWmService.mInputManager.createInputChannel(name);
            mInputEventReceiver = new TapEventReceiver(mClientChannel, mWmService, mHandler);

            mToken = mClientChannel.getToken();

            mWindowHandle = new InputWindowHandle(null /* inputApplicationHandle */,
                    win.getDisplayId());
            mWindowHandle.name = name;
            mWindowHandle.token = mToken;
            mWindowHandle.layoutParamsType = WindowManager.LayoutParams.TYPE_INPUT_CONSUMER;
            mWindowHandle.dispatchingTimeoutMillis = DEFAULT_DISPATCHING_TIMEOUT_MILLIS;
            mWindowHandle.ownerPid = WindowManagerService.MY_PID;
            mWindowHandle.ownerUid = WindowManagerService.MY_UID;
            mWindowHandle.scaleFactor = 1.0f;
            mWindowHandle.inputConfig = InputConfig.NOT_FOCUSABLE | InputConfig.SLIPPERY;
        }

        void updateTouchableRegion(Rect frame) {
            if (frame.isEmpty()) {
                // Use null token to indicate the surface doesn't need to receive input event (see
                // the usage of Layer.hasInput in SurfaceFlinger), so InputDispatcher won't keep the
                // unnecessary records.
                mWindowHandle.token = null;
                return;
            }
            mWindowHandle.token = mToken;
            mWindowHandle.touchableRegion.set(frame);
            mWindowHandle.touchableRegion.translate(-frame.left, -frame.top);
        }

        @Override
        public void run() {
            mInputEventReceiver.dispose();
            mClientChannel.dispose();
        }

        void dispose() {
            mWmService.mInputManager.removeInputChannel(mToken);
            // Perform dispose on the same thread that dispatches input event
            mHandler.post(this);
        }
    }

    private class LetterboxSurface {

        private final String mType;
        private SurfaceControl mSurface;
        private Color mColor;
        private boolean mHasWallpaperBackground;
        private SurfaceControl mParentSurface;

        private final Rect mSurfaceFrameRelative = new Rect();
        private final Rect mLayoutFrameGlobal = new Rect();
        private final Rect mLayoutFrameRelative = new Rect();

        private InputInterceptor mInputInterceptor;

        public LetterboxSurface(String type) {
            mType = type;
        }

        public void layout(int left, int top, int right, int bottom, Point surfaceOrigin) {
            mLayoutFrameGlobal.set(left, top, right, bottom);
            mLayoutFrameRelative.set(mLayoutFrameGlobal);
            mLayoutFrameRelative.offset(-surfaceOrigin.x, -surfaceOrigin.y);
        }

        private void createSurface(SurfaceControl.Transaction t) {
            mSurface = mSurfaceControlFactory.get()
                    .setName("Letterbox - " + mType)
                    .setFlags(HIDDEN)
                    .setColorLayer()
                    .setCallsite("LetterboxSurface.createSurface")
                    .build();

            t.setLayer(mSurface, TASK_CHILD_LAYER_LETTERBOX_BACKGROUND)
                    .setColorSpaceAgnostic(mSurface, true);
        }

        void attachInput(WindowState win) {
            if (mInputInterceptor != null) {
                mInputInterceptor.dispose();
            }
            mInputInterceptor = new InputInterceptor("Letterbox_" + mType + "_", win);
        }

        boolean isRemoved() {
            return mSurface != null || mInputInterceptor != null;
        }

        public void remove() {
            if (mSurface != null) {
                mTransactionFactory.get().remove(mSurface).apply();
                mSurface = null;
            }
            if (mInputInterceptor != null) {
                mInputInterceptor.dispose();
                mInputInterceptor = null;
            }
        }

        public int getWidth() {
            return Math.max(0, mLayoutFrameGlobal.width());
        }

        public int getHeight() {
            return Math.max(0, mLayoutFrameGlobal.height());
        }

        public void applySurfaceChanges(@NonNull SurfaceControl.Transaction t,
                @NonNull SurfaceControl.Transaction inputT) {
            if (!needsApplySurfaceChanges()) {
                // Nothing changed.
                return;
            }
            mSurfaceFrameRelative.set(mLayoutFrameRelative);
            if (!mSurfaceFrameRelative.isEmpty()) {
                if (mSurface == null) {
                    createSurface(t);
                }

                mColor = mAppCompatLetterboxOverrides.getLetterboxBackgroundColor();
                mParentSurface = mParentSurfaceSupplier.get();
                t.setColor(mSurface, getRgbColorArray());
                t.setPosition(mSurface, mSurfaceFrameRelative.left, mSurfaceFrameRelative.top);
                t.setWindowCrop(mSurface, mSurfaceFrameRelative.width(),
                        mSurfaceFrameRelative.height());
                t.reparent(mSurface, mParentSurface);

                mHasWallpaperBackground = mAppCompatLetterboxOverrides
                        .hasWallpaperBackgroundForLetterbox();
                updateAlphaAndBlur(t);

                t.show(mSurface);
            } else if (mSurface != null) {
                t.hide(mSurface);
            }
            if (mSurface != null && mInputInterceptor != null) {
                mInputInterceptor.updateTouchableRegion(mSurfaceFrameRelative);
                inputT.setInputWindowInfo(mSurface, mInputInterceptor.mWindowHandle);
            }
        }

        private void updateAlphaAndBlur(SurfaceControl.Transaction t) {
            if (!mHasWallpaperBackground) {
                // Opaque
                t.setAlpha(mSurface, 1.0f);
                // Removing pre-exesting blur
                t.setBackgroundBlurRadius(mSurface, 0);
                return;
            }
            final float alpha = mAppCompatLetterboxOverrides.getLetterboxWallpaperDarkScrimAlpha();
            t.setAlpha(mSurface, alpha);

            // Translucent dark scrim can be shown without blur.
            final int blurRadiusPx = mAppCompatLetterboxOverrides
                    .getLetterboxWallpaperBlurRadiusPx();
            if (blurRadiusPx <= 0) {
                // Removing pre-exesting blur
                t.setBackgroundBlurRadius(mSurface, 0);
                return;
            }

            t.setBackgroundBlurRadius(mSurface, blurRadiusPx);
        }

        private float[] getRgbColorArray() {
            final float[] rgbTmpFloat = new float[3];
            rgbTmpFloat[0] = mColor.red();
            rgbTmpFloat[1] = mColor.green();
            rgbTmpFloat[2] = mColor.blue();
            return rgbTmpFloat;
        }

        public boolean needsApplySurfaceChanges() {
            return !mSurfaceFrameRelative.equals(mLayoutFrameRelative)
                    // If mSurfaceFrameRelative is empty then mHasWallpaperBackground, mColor,
                    // and mParentSurface may never be updated in applySurfaceChanges but this
                    // doesn't mean that update is needed.
                    || !mSurfaceFrameRelative.isEmpty()
                    && (mAppCompatLetterboxOverrides.hasWallpaperBackgroundForLetterbox()
                        != mHasWallpaperBackground
                    || !mAppCompatLetterboxOverrides.getLetterboxBackgroundColor().equals(mColor)
                    || mParentSurfaceSupplier.get() != mParentSurface);
        }
    }
}
