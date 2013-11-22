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

import android.graphics.Rect;
import android.util.Pools.SynchronizedPool;

/**
 * An implementation of a GL canvas that records drawing operations.
 * This is intended for use with a DisplayList. This class keeps a list of all the Paint and
 * Bitmap objects that it draws, preventing the backing memory of Bitmaps from being freed while
 * the DisplayList is still holding a native reference to the memory.
 */
class GLES20RecordingCanvas extends GLES20Canvas {
    // The recording canvas pool should be large enough to handle a deeply nested
    // view hierarchy because display lists are generated recursively.
    private static final int POOL_LIMIT = 25;

    private static final SynchronizedPool<GLES20RecordingCanvas> sPool =
            new SynchronizedPool<GLES20RecordingCanvas>(POOL_LIMIT);

    private GLES20DisplayList mDisplayList;

    private GLES20RecordingCanvas() {
        super(true, true);
    }

    static GLES20RecordingCanvas obtain(GLES20DisplayList displayList) {
        GLES20RecordingCanvas canvas = sPool.acquire();
        if (canvas == null) {
            canvas = new GLES20RecordingCanvas();
        }
        canvas.mDisplayList = displayList;
        return canvas;
    }

    void recycle() {
        mDisplayList = null;
        resetDisplayListRenderer();
        sPool.release(this);
    }

    void start() {
        mDisplayList.clearReferences();
    }

    int end(int nativeDisplayList) {
        return getDisplayList(nativeDisplayList);
    }

    @Override
    public int drawDisplayList(DisplayList displayList, Rect dirty, int flags) {
        int status = super.drawDisplayList(displayList, dirty, flags);
        mDisplayList.getChildDisplayLists().add(displayList);
        return status;
    }
}
