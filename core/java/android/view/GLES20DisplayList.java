/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.graphics.Bitmap;

import java.util.ArrayList;

/**
 * An implementation of display list for OpenGL ES 2.0.
 */
class GLES20DisplayList extends DisplayList {
    // These lists ensure that any Bitmaps recorded by a DisplayList are kept alive as long
    // as the DisplayList is alive.  The Bitmaps are populated by the GLES20RecordingCanvas.
    final ArrayList<Bitmap> mBitmaps = new ArrayList<Bitmap>(5);

    private GLES20RecordingCanvas mCanvas;
    private boolean mValid;

    // Used for debugging
    private final String mName;

    // The native display list will be destroyed when this object dies.
    // DO NOT overwrite this reference once it is set.
    private DisplayListFinalizer mFinalizer;

    GLES20DisplayList(String name) {
        mName = name;
    }

    int getNativeDisplayList() {
        if (!mValid || mFinalizer == null) {
            throw new IllegalStateException("The display list is not valid.");
        }
        return mFinalizer.mNativeDisplayList;
    }

    @Override
    public HardwareCanvas start() {
        if (mCanvas != null) {
            throw new IllegalStateException("Recording has already started");
        }

        mValid = false;
        mCanvas = GLES20RecordingCanvas.obtain(this);
        mCanvas.start();
        return mCanvas;
    }

    @Override
    public void invalidate() {
        if (mCanvas != null) {
            mCanvas.recycle();
            mCanvas = null;
        }
        mValid = false;
    }

    @Override
    public boolean isValid() {
        return mValid;
    }

    @Override
    public void end() {
        if (mCanvas != null) {
            if (mFinalizer != null) {
                mCanvas.end(mFinalizer.mNativeDisplayList);
            } else {
                mFinalizer = new DisplayListFinalizer(mCanvas.end(0));
                GLES20Canvas.setDisplayListName(mFinalizer.mNativeDisplayList, mName);
            }
            mCanvas.recycle();
            mCanvas = null;
            mValid = true;
        }
    }

    @Override
    public int getSize() {
        if (mFinalizer == null) return 0;
        return GLES20Canvas.getDisplayListSize(mFinalizer.mNativeDisplayList);
    }

    private static class DisplayListFinalizer {
        final int mNativeDisplayList;

        public DisplayListFinalizer(int nativeDisplayList) {
            mNativeDisplayList = nativeDisplayList;
        }

        @Override
        protected void finalize() throws Throwable {
            try {
                GLES20Canvas.destroyDisplayList(mNativeDisplayList);
            } finally {
                super.finalize();
            }
        }
    }
}
