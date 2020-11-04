/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.screenshot;

import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.HardwareRenderer;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.os.RemoteException;
import android.view.IScrollCaptureCallbacks;
import android.view.IScrollCaptureConnection;
import android.view.Surface;

/**
 * An IScrollCaptureConnection which returns a sequence of solid filled rectangles in the
 * locations requested, in alternating colors.
 */
class FakeScrollCaptureConnection extends IScrollCaptureConnection.Stub {
    private final int[] mColors = {Color.RED, Color.GREEN, Color.BLUE};
    private IScrollCaptureCallbacks mCallbacks;
    private Surface mSurface;
    private Paint mPaint;
    private int mNextColor;
    private HwuiContext mHwuiContext;

    FakeScrollCaptureConnection(IScrollCaptureCallbacks cb) {
        mCallbacks = cb;
    }

    @Override
    public void startCapture(Surface surface) {
        mSurface = surface;
        mHwuiContext = new HwuiContext(false, surface);
        mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPaint.setStyle(Paint.Style.FILL);
        try {
            mCallbacks.onCaptureStarted();
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    @Override
    public void requestImage(Rect rect) {
        Canvas canvas = mHwuiContext.lockCanvas(rect.width(), rect.height());
        mPaint.setColor(mColors[mNextColor]);
        canvas.drawRect(rect, mPaint);
        mNextColor = (mNextColor++) % mColors.length;
        long frameNumber = mSurface.getNextFrameNumber();
        mHwuiContext.unlockAndPost(canvas);
        try {
            mCallbacks.onCaptureBufferSent(frameNumber, rect);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    @Override
    public void endCapture() {
        try {
            mCallbacks.onConnectionClosed();
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        } finally {
            mHwuiContext.destroy();
            mSurface = null;
            mCallbacks = null;
        }
    }

    // From android.view.Surface, but issues render requests synchronously with waitForPresent(true)
    private static final class HwuiContext {
        private final RenderNode mRenderNode;
        private final HardwareRenderer mHardwareRenderer;
        private RecordingCanvas mCanvas;
        private final boolean mIsWideColorGamut;

        HwuiContext(boolean isWideColorGamut, Surface surface) {
            mRenderNode = RenderNode.create("HwuiCanvas", null);
            mRenderNode.setClipToBounds(false);
            mRenderNode.setForceDarkAllowed(false);
            mIsWideColorGamut = isWideColorGamut;

            mHardwareRenderer = new HardwareRenderer();
            mHardwareRenderer.setContentRoot(mRenderNode);
            mHardwareRenderer.setSurface(surface, true);
            mHardwareRenderer.setColorMode(
                    isWideColorGamut
                            ? ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
                            : ActivityInfo.COLOR_MODE_DEFAULT);
            mHardwareRenderer.setLightSourceAlpha(0.0f, 0.0f);
            mHardwareRenderer.setLightSourceGeometry(0.0f, 0.0f, 0.0f, 0.0f);
        }

        Canvas lockCanvas(int width, int height) {
            if (mCanvas != null) {
                throw new IllegalStateException("Surface was already locked!");
            }
            mCanvas = mRenderNode.beginRecording(width, height);
            return mCanvas;
        }

        void unlockAndPost(Canvas canvas) {
            if (canvas != mCanvas) {
                throw new IllegalArgumentException("canvas object must be the same instance that "
                        + "was previously returned by lockCanvas");
            }
            mRenderNode.endRecording();
            mCanvas = null;
            mHardwareRenderer.createRenderRequest()
                    .setVsyncTime(System.nanoTime())
                    .setWaitForPresent(true) // sync!
                    .syncAndDraw();
        }

        void destroy() {
            mHardwareRenderer.destroy();
        }

        boolean isWideColorGamut() {
            return mIsWideColorGamut;
        }
    }
}
