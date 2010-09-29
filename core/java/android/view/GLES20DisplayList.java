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

/**
 * An implementation of display list for OpenGL ES 2.0.
 */
class GLES20DisplayList extends DisplayList {
    private GLES20Canvas mCanvas;

    private boolean mStarted = false;
    private boolean mRecorded = false;

    int mNativeDisplayList;

    @Override
    HardwareCanvas start() {
        if (mStarted) {
            throw new IllegalStateException("Recording has already started");
        }

        destroyCanvas();

        mCanvas = new GLES20Canvas(true, true);
        mStarted = true;
        mRecorded = false;

        return mCanvas;
    }

    private void destroyCanvas() {
        if (mCanvas != null) {
            mCanvas.destroyDisplayList(mNativeDisplayList);
            mCanvas.destroy();

            mCanvas = null;
            mNativeDisplayList = 0;
        }
    }

    @Override
    void end() {
        if (mCanvas != null) {
            mStarted = false;
            mRecorded = true;

            mNativeDisplayList = mCanvas.getDisplayList();
        }
    }

    @Override
    void destroy() {
        destroyCanvas();
    }

    @Override
    boolean isReady() {
        return !mStarted && mRecorded;
    }
}
