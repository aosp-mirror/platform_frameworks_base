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

import static android.view.SurfaceControl.HIDDEN;

import android.graphics.Point;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Process;
import android.view.InputChannel;
import android.view.InputEventReceiver;
import android.view.InputWindowHandle;
import android.view.SurfaceControl;
import android.view.WindowManager;

import com.android.server.UiThread;

import java.util.function.Supplier;

/**
 * Manages a set of {@link SurfaceControl}s to draw a black letterbox between an
 * outer rect and an inner rect.
 */
public class Letterbox {

    private static final Rect EMPTY_RECT = new Rect();
    private static final Point ZERO_POINT = new Point(0, 0);

    private final Supplier<SurfaceControl.Builder> mFactory;
    private final Rect mOuter = new Rect();
    private final Rect mInner = new Rect();
    private final LetterboxSurface mTop = new LetterboxSurface("top");
    private final LetterboxSurface mLeft = new LetterboxSurface("left");
    private final LetterboxSurface mBottom = new LetterboxSurface("bottom");
    private final LetterboxSurface mRight = new LetterboxSurface("right");
    private final LetterboxSurface[] mSurfaces = { mLeft, mTop, mRight, mBottom };

    /**
     * Constructs a Letterbox.
     *
     * @param surfaceControlFactory a factory for creating the managed {@link SurfaceControl}s
     */
    public Letterbox(Supplier<SurfaceControl.Builder> surfaceControlFactory) {
        mFactory = surfaceControlFactory;
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

        mTop.layout(outer.left, outer.top, inner.right, inner.top, surfaceOrigin);
        mLeft.layout(outer.left, inner.top, inner.left, outer.bottom, surfaceOrigin);
        mBottom.layout(inner.left, inner.bottom, outer.right, outer.bottom, surfaceOrigin);
        mRight.layout(inner.right, outer.top, outer.right, inner.bottom, surfaceOrigin);
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

    /**
     * Returns true if any part of the letterbox overlaps with the given {@code rect}.
     */
    public boolean isOverlappingWith(Rect rect) {
        for (LetterboxSurface surface : mSurfaces) {
            if (surface.isOverlappingWith(rect)) {
                return true;
            }
        }
        return false;
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
    }

    /** Returns whether a call to {@link #applySurfaceChanges} would change the surface. */
    public boolean needsApplySurfaceChanges() {
        for (LetterboxSurface surface : mSurfaces) {
            if (surface.needsApplySurfaceChanges()) {
                return true;
            }
        }
        return false;
    }

    public void applySurfaceChanges(SurfaceControl.Transaction t) {
        for (LetterboxSurface surface : mSurfaces) {
            surface.applySurfaceChanges(t);
        }
    }

    /** Enables touches to slide into other neighboring surfaces. */
    void attachInput(WindowState win) {
        for (LetterboxSurface surface : mSurfaces) {
            surface.attachInput(win);
        }
    }

    void onMovedToDisplay(int displayId) {
        for (LetterboxSurface surface : mSurfaces) {
            if (surface.mInputInterceptor != null) {
                surface.mInputInterceptor.mWindowHandle.displayId = displayId;
            }
        }
    }

    private static class InputInterceptor {
        final InputChannel mServerChannel;
        final InputChannel mClientChannel;
        final InputWindowHandle mWindowHandle;
        final InputEventReceiver mInputEventReceiver;
        final WindowManagerService mWmService;
        final Binder mToken = new Binder();

        InputInterceptor(String namePrefix, WindowState win) {
            mWmService = win.mWmService;
            final String name = namePrefix + (win.mAppToken != null ? win.mAppToken : win);
            final InputChannel[] channels = InputChannel.openInputChannelPair(name);
            mServerChannel = channels[0];
            mClientChannel = channels[1];
            mInputEventReceiver = new SimpleInputReceiver(mClientChannel);

            mWmService.mInputManager.registerInputChannel(mServerChannel, mToken);

            mWindowHandle = new InputWindowHandle(null /* inputApplicationHandle */,
                    null /* clientWindow */, win.getDisplayId());
            mWindowHandle.name = name;
            mWindowHandle.token = mToken;
            mWindowHandle.layoutParamsFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                    | WindowManager.LayoutParams.FLAG_SLIPPERY;
            mWindowHandle.layoutParamsType = WindowManager.LayoutParams.TYPE_INPUT_CONSUMER;
            mWindowHandle.dispatchingTimeoutNanos =
                    WindowManagerService.DEFAULT_INPUT_DISPATCHING_TIMEOUT_NANOS;
            mWindowHandle.visible = true;
            mWindowHandle.ownerPid = Process.myPid();
            mWindowHandle.ownerUid = Process.myUid();
            mWindowHandle.scaleFactor = 1.0f;
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

        void dispose() {
            mWmService.mInputManager.unregisterInputChannel(mServerChannel);
            mInputEventReceiver.dispose();
            mServerChannel.dispose();
            mClientChannel.dispose();
        }

        private static class SimpleInputReceiver extends InputEventReceiver {
            SimpleInputReceiver(InputChannel inputChannel) {
                super(inputChannel, UiThread.getHandler().getLooper());
            }
        }
    }

    private class LetterboxSurface {

        private final String mType;
        private SurfaceControl mSurface;

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

        private void createSurface() {
            mSurface = mFactory.get().setName("Letterbox - " + mType)
                    .setFlags(HIDDEN).setColorLayer().build();
            mSurface.setLayer(-1);
            mSurface.setColor(new float[]{0, 0, 0});
            mSurface.setColorSpaceAgnostic(true);
        }

        void attachInput(WindowState win) {
            if (mInputInterceptor != null) {
                mInputInterceptor.dispose();
            }
            mInputInterceptor = new InputInterceptor("Letterbox_" + mType + "_", win);
        }

        public void remove() {
            if (mSurface != null) {
                mSurface.remove();
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

        /**
         * Returns if the given {@code rect} overlaps with this letterbox piece.
         * @param rect the area to check for overlap in global coordinates
         */
        public boolean isOverlappingWith(Rect rect) {
            if (mLayoutFrameGlobal.isEmpty()) {
                return false;
            }
            return Rect.intersects(rect, mLayoutFrameGlobal);
        }

        public void applySurfaceChanges(SurfaceControl.Transaction t) {
            if (mSurfaceFrameRelative.equals(mLayoutFrameRelative)) {
                // Nothing changed.
                return;
            }
            mSurfaceFrameRelative.set(mLayoutFrameRelative);
            if (!mSurfaceFrameRelative.isEmpty()) {
                if (mSurface == null) {
                    createSurface();
                }
                t.setPosition(mSurface, mSurfaceFrameRelative.left, mSurfaceFrameRelative.top);
                t.setWindowCrop(mSurface, mSurfaceFrameRelative.width(),
                        mSurfaceFrameRelative.height());
                t.show(mSurface);
            } else if (mSurface != null) {
                t.hide(mSurface);
            }
            if (mSurface != null && mInputInterceptor != null) {
                mInputInterceptor.updateTouchableRegion(mSurfaceFrameRelative);
                t.setInputWindowInfo(mSurface, mInputInterceptor.mWindowHandle);
            }
        }

        public boolean needsApplySurfaceChanges() {
            return !mSurfaceFrameRelative.equals(mLayoutFrameRelative);
        }
    }
}
