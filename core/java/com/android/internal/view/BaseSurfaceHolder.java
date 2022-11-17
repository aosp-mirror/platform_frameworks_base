/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.internal.view;

import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantLock;

public abstract class BaseSurfaceHolder implements SurfaceHolder {
    private static final String TAG = "BaseSurfaceHolder";
    static final boolean DEBUG = false;

    public final ArrayList<SurfaceHolder.Callback> mCallbacks
            = new ArrayList<SurfaceHolder.Callback>();
    SurfaceHolder.Callback[] mGottenCallbacks;
    boolean mHaveGottenCallbacks;
    
    public final ReentrantLock mSurfaceLock = new ReentrantLock();
    public Surface mSurface = new Surface();

    int mRequestedWidth = -1;
    int mRequestedHeight = -1;
    /** @hide */
    protected int mRequestedFormat = PixelFormat.OPAQUE;
    int mRequestedType = -1;

    long mLastLockTime = 0;
    
    int mType = -1;
    final Rect mSurfaceFrame = new Rect();
    Rect mTmpDirty;
    
    public abstract void onUpdateSurface();
    public abstract void onRelayoutContainer();
    public abstract boolean onAllowLockCanvas();
    
    public int getRequestedWidth() {
        return mRequestedWidth;
    }
    
    public int getRequestedHeight() {
        return mRequestedHeight;
    }
    
    public int getRequestedFormat() {
        return mRequestedFormat;
    }
    
    public int getRequestedType() {
        return mRequestedType;
    }
    
    public void addCallback(Callback callback) {
        synchronized (mCallbacks) {
            // This is a linear search, but in practice we'll 
            // have only a couple callbacks, so it doesn't matter.
            if (mCallbacks.contains(callback) == false) {      
                mCallbacks.add(callback);
            }
        }
    }

    public void removeCallback(Callback callback) {
        synchronized (mCallbacks) {
            mCallbacks.remove(callback);
        }
    }

    public SurfaceHolder.Callback[] getCallbacks() {
        if (mHaveGottenCallbacks) {
            return mGottenCallbacks;
        }
        
        synchronized (mCallbacks) {
            final int N = mCallbacks.size();
            if (N > 0) {
                if (mGottenCallbacks == null || mGottenCallbacks.length != N) {
                    mGottenCallbacks = new SurfaceHolder.Callback[N];
                }
                mCallbacks.toArray(mGottenCallbacks);
            } else {
                mGottenCallbacks = null;
            }
            mHaveGottenCallbacks = true;
        }
        
        return mGottenCallbacks;
    }
    
    public void ungetCallbacks() {
        mHaveGottenCallbacks = false;
    }
    
    public void setFixedSize(int width, int height) {
        if (mRequestedWidth != width || mRequestedHeight != height) {
            mRequestedWidth = width;
            mRequestedHeight = height;
            onRelayoutContainer();
        }
    }

    public void setSizeFromLayout() {
        if (mRequestedWidth != -1 || mRequestedHeight != -1) {
            mRequestedWidth = mRequestedHeight = -1;
            onRelayoutContainer();
        }
    }

    public void setFormat(int format) {
        if (mRequestedFormat != format) {
            mRequestedFormat = format;
            onUpdateSurface();
        }
    }

    public void setType(int type) {
        switch (type) {
        case SURFACE_TYPE_HARDWARE:
        case SURFACE_TYPE_GPU:
            // these are deprecated, treat as "NORMAL"
            type = SURFACE_TYPE_NORMAL;
            break;
        }
        switch (type) {
        case SURFACE_TYPE_NORMAL:
        case SURFACE_TYPE_PUSH_BUFFERS:
            if (mRequestedType != type) {
                mRequestedType = type;
                onUpdateSurface();
            }
            break;
        }
    }

    @Override
    public Canvas lockCanvas() {
        return internalLockCanvas(null, false);
    }

    @Override
    public Canvas lockCanvas(Rect dirty) {
        return internalLockCanvas(dirty, false);
    }

    @Override
    public Canvas lockHardwareCanvas() {
        return internalLockCanvas(null, true);
    }

    private final Canvas internalLockCanvas(Rect dirty, boolean hardware) {
        if (mType == SURFACE_TYPE_PUSH_BUFFERS) {
            throw new BadSurfaceTypeException(
                    "Surface type is SURFACE_TYPE_PUSH_BUFFERS");
        }
        mSurfaceLock.lock();

        if (DEBUG) Log.i(TAG, "Locking canvas..,");

        Canvas c = null;
        if (onAllowLockCanvas()) {
            if (dirty == null) {
                if (mTmpDirty == null) {
                    mTmpDirty = new Rect();
                }
                mTmpDirty.set(mSurfaceFrame);
                dirty = mTmpDirty;
            }

            try {
                if (hardware) {
                    c = mSurface.lockHardwareCanvas();
                } else {
                    c = mSurface.lockCanvas(dirty);
                }
            } catch (Exception e) {
                Log.e(TAG, "Exception locking surface", e);
            }
        }

        if (DEBUG) Log.i(TAG, "Returned canvas: " + c);
        if (c != null) {
            mLastLockTime = SystemClock.uptimeMillis();
            return c;
        }
        
        // If the Surface is not ready to be drawn, then return null,
        // but throttle calls to this function so it isn't called more
        // than every 100ms.
        long now = SystemClock.uptimeMillis();
        long nextTime = mLastLockTime + 100;
        if (nextTime > now) {
            try {
                Thread.sleep(nextTime-now);
            } catch (InterruptedException e) {
            }
            now = SystemClock.uptimeMillis();
        }
        mLastLockTime = now;
        mSurfaceLock.unlock();
        
        return null;
    }

    public void unlockCanvasAndPost(Canvas canvas) {
        mSurface.unlockCanvasAndPost(canvas);
        mSurfaceLock.unlock();
    }

    public Surface getSurface() {
        return mSurface;
    }

    public Rect getSurfaceFrame() {
        return mSurfaceFrame;
    }

    public void setSurfaceFrameSize(int width, int height) {
        mSurfaceFrame.top = 0;
        mSurfaceFrame.left = 0;
        mSurfaceFrame.right = width;
        mSurfaceFrame.bottom = height;
    }
}
