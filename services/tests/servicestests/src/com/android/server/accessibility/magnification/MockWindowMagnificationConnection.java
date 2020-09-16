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

package com.android.server.accessibility.magnification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.Display;
import android.view.accessibility.IRemoteMagnificationAnimationCallback;
import android.view.accessibility.IWindowMagnificationConnection;
import android.view.accessibility.IWindowMagnificationConnectionCallback;

/**
 * Mocks the basic logic of window magnification in System UI. We assume the screen size is
 * unlimited, so source bounds is always on the center of the mirror window bounds.
 */
class MockWindowMagnificationConnection {

    public static final int TEST_DISPLAY = Display.DEFAULT_DISPLAY;
    private final IWindowMagnificationConnection mConnection;
    private final Binder mBinder;
    private final boolean mSuspendCallback;
    private boolean mHasPendingCallback = false;
    private boolean mWindowMagnificationEnabled = false;
    private IBinder.DeathRecipient mDeathRecipient;
    private IWindowMagnificationConnectionCallback mIMirrorWindowCallback;

    private Rect mMirrorWindowFrame = new Rect(0, 0, 500, 500);
    private float mScale = 2.0f;
    private Rect mSourceBounds = new Rect();
    private IRemoteMagnificationAnimationCallback mAnimationCallback;

    MockWindowMagnificationConnection() throws RemoteException {
        this(false);
    }

    MockWindowMagnificationConnection(boolean suspendCallback) throws RemoteException {
        mSuspendCallback = suspendCallback;
        mConnection = mock(IWindowMagnificationConnection.class);
        mBinder = mock(Binder.class);
        when(mConnection.asBinder()).thenReturn(mBinder);
        doAnswer((invocation) -> {
            mIMirrorWindowCallback = invocation.getArgument(0);
            return null;
        }).when(mConnection).setConnectionCallback(
                any(IWindowMagnificationConnectionCallback.class));

        doAnswer((invocation) -> {
            mDeathRecipient = invocation.getArgument(0);
            return null;
        }).when(mBinder).linkToDeath(
                any(IBinder.DeathRecipient.class), eq(0));
        stubConnection();
    }

    private void stubConnection() throws RemoteException {
        stubEnableWindowMagnification();
        stubDisableWindowMagnification();
    }

    private void stubEnableWindowMagnification() throws RemoteException {
        doAnswer((invocation) -> {
            final int displayId = invocation.getArgument(0);
            if (displayId != TEST_DISPLAY) {
                throw new IllegalArgumentException("only support default display :" + displayId);
            }
            mWindowMagnificationEnabled = true;
            final float scale = invocation.getArgument(1);
            mScale = Float.isNaN(scale) ? mScale : scale;
            computeMirrorWindowFrame(invocation.getArgument(2), invocation.getArgument(3));
            setAnimationCallback(invocation.getArgument(4));
            computeSourceBounds();
            mHasPendingCallback = true;
            if (!mSuspendCallback) {
                invokeCallbacksInternal(true);
            }
            return null;
        }).when(mConnection).enableWindowMagnification(anyInt(), anyFloat(), anyFloat(), anyFloat(),
                nullable(IRemoteMagnificationAnimationCallback.class));
    }

    private void stubDisableWindowMagnification() throws RemoteException {
        doAnswer((invocation) -> {
            final int displayId = invocation.getArgument(0);
            if (displayId != TEST_DISPLAY) {
                throw new IllegalArgumentException("only support default display :" + displayId);
            }
            setAnimationCallback(invocation.getArgument(1));
            mHasPendingCallback = true;
            if (!mSuspendCallback) {
                invokeCallbacksInternal(true);
            }
            return null;
        }).when(mConnection).disableWindowMagnification(anyInt(),
                nullable(IRemoteMagnificationAnimationCallback.class));
    }

    private void computeSourceBounds() {
        final int halfWidth = mMirrorWindowFrame.width() / 2;
        final int halfHeight = mMirrorWindowFrame.height() / 2;
        final int left = mMirrorWindowFrame.left + (halfWidth - (int) (halfWidth / mScale));
        final int right = mMirrorWindowFrame.right - (halfWidth - (int) (halfWidth / mScale));
        final int top = mMirrorWindowFrame.top + (halfHeight - (int) (halfHeight / mScale));
        final int bottom = mMirrorWindowFrame.bottom - (halfHeight - (int) (halfHeight / mScale));
        mSourceBounds.set(left, top, right, bottom);
    }

    private void computeMirrorWindowFrame(float centerX, float centerY) {
        final float offsetX = Float.isNaN(centerX) ? 0
                : centerX - mMirrorWindowFrame.exactCenterX();
        final float offsetY = Float.isNaN(centerY) ? 0
                : centerY - mMirrorWindowFrame.exactCenterY();
        mMirrorWindowFrame.offset((int) offsetX, (int) offsetY);
    }

    private void sendAnimationEndCallbackIfNeeded(boolean success) throws RemoteException {
        if (mAnimationCallback != null) {
            mAnimationCallback.onResult(success);
            mAnimationCallback = null;
        }
    }

    IWindowMagnificationConnection getConnection() {
        return mConnection;
    }

    Binder asBinder() {
        return mBinder;
    }

    IBinder.DeathRecipient getDeathRecipient() {
        return mDeathRecipient;
    }

    IWindowMagnificationConnectionCallback getConnectionCallback() {
        return mIMirrorWindowCallback;
    }

    Rect getMirrorWindowFrame() {
        return new Rect(mMirrorWindowFrame);
    }

    void invokeCallbacks() throws RemoteException {
        if (!mSuspendCallback) {
            throw new IllegalStateException("Invoke callbacks automatically");
        }
        invokeCallbacksInternal(true);
    }

    private void invokeCallbacksInternal(boolean success) throws RemoteException {
        if (!mHasPendingCallback) {
            throw new IllegalStateException("There is no any pending callbacks");
        }
        if (mWindowMagnificationEnabled && mIMirrorWindowCallback != null) {
            mIMirrorWindowCallback.onWindowMagnifierBoundsChanged(TEST_DISPLAY,
                    mMirrorWindowFrame);
            mIMirrorWindowCallback.onSourceBoundsChanged(TEST_DISPLAY,
                    mSourceBounds);
        }
        sendAnimationEndCallbackIfNeeded(success);
        mHasPendingCallback = false;
    }

    private void setAnimationCallback(
            @Nullable IRemoteMagnificationAnimationCallback animationCallback)
            throws RemoteException {
        if (mAnimationCallback != null) {
            invokeCallbacksInternal(false);
        }
        mAnimationCallback = animationCallback;
    }
}

