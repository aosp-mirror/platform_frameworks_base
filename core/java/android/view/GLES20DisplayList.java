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

import java.lang.ref.WeakReference;

/**
 * An implementation of display list for OpenGL ES 2.0.
 */
class GLES20DisplayList extends DisplayList {
    private GLES20Canvas mCanvas;

    private boolean mStarted = false;
    private boolean mRecorded = false;
    private boolean mValid = false;

    int mNativeDisplayList;
    WeakReference<View> hostView;

    // The native display list will be destroyed when this object dies.
    // DO NOT overwrite this reference once it is set.
    @SuppressWarnings("unused")
    private DisplayListFinalizer mFinalizer;

    public GLES20DisplayList(View view) {
        hostView = new WeakReference<View>(view);
    }

    @Override
    HardwareCanvas start() {
        if (mStarted) {
            throw new IllegalStateException("Recording has already started");
        }

        if (mCanvas != null) {
            ((GLES20RecordingCanvas) mCanvas).reset();
        } else {
            mCanvas = new GLES20RecordingCanvas(true);
        }
        mStarted = true;
        mRecorded = false;
        mValid = true;

        return mCanvas;
    }

    @Override
    void invalidate() {
        mStarted = false;
        mRecorded = false;
        mValid = false;
    }

    @Override
    boolean isValid() {
        return mValid;
    }

    @Override
    void end() {
        if (mCanvas != null) {
            mStarted = false;
            mRecorded = true;

            mNativeDisplayList = mCanvas.getDisplayList();
            mFinalizer = DisplayListFinalizer.getFinalizer(mFinalizer, mNativeDisplayList);
        }
    }

    @Override
    boolean isReady() {
        return !mStarted && mRecorded;
    }

    private static class DisplayListFinalizer {
        int mNativeDisplayList;

        // Factory method returns new instance if old one is null, or old instance
        // otherwise, destroying native display list along the way as necessary
        static DisplayListFinalizer getFinalizer(DisplayListFinalizer oldFinalizer,
                int nativeDisplayList) {
            if (oldFinalizer == null) {
                return new DisplayListFinalizer(nativeDisplayList);
            }
            oldFinalizer.replaceNativeObject(nativeDisplayList);
            return oldFinalizer;
        }

        private DisplayListFinalizer(int nativeDisplayList) {
            mNativeDisplayList = nativeDisplayList;
        }

        private void replaceNativeObject(int newNativeDisplayList) {
            if (mNativeDisplayList != 0 && mNativeDisplayList != newNativeDisplayList) {
                GLES20Canvas.destroyDisplayList(mNativeDisplayList);
            }
            mNativeDisplayList = newNativeDisplayList;
        }

        @Override
        protected void finalize() throws Throwable {
            try {
                replaceNativeObject(0);
            } finally {
                super.finalize();
            }
        }
    }
}
