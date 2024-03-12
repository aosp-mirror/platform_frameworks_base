/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.surfaceflinger;

import static android.view.SurfaceControl.BUFFER_TRANSFORM_IDENTITY;
import static android.view.SurfaceControl.BUFFER_TRANSFORM_ROTATE_270;
import static android.view.SurfaceControl.BUFFER_TRANSFORM_ROTATE_90;

import android.annotation.ColorInt;
import android.graphics.Canvas;
import android.graphics.GraphicBuffer;
import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import android.hardware.SyncFence;
import android.view.SurfaceControl;

import java.util.concurrent.ArrayBlockingQueue;

/**
 * Allocates n amount of buffers to a SurfaceControl using a Queue implementation. Executes a
 * releaseCallback so a buffer can be safely re-used.
 *
 * @hide
 */
public class BufferFlinger {
    private final int mTransformHint;
    ArrayBlockingQueue<GraphicBuffer> mBufferQ;

    public BufferFlinger(int numOfBuffers, @ColorInt int color, int bufferTransformHint) {
        mTransformHint = bufferTransformHint;
        mBufferQ = new ArrayBlockingQueue<>(numOfBuffers);

        while (numOfBuffers > 0) {
            GraphicBuffer buffer = GraphicBuffer.create(500, 500,
                    PixelFormat.RGBA_8888,
                    GraphicBuffer.USAGE_HW_TEXTURE | GraphicBuffer.USAGE_HW_COMPOSER
                            | GraphicBuffer.USAGE_SW_WRITE_RARELY);

            Canvas canvas = buffer.lockCanvas();
            canvas.drawColor(color);
            buffer.unlockCanvasAndPost(canvas);

            mBufferQ.add(buffer);
            numOfBuffers--;
        }
    }

    public void addBuffer(SurfaceControl.Transaction t, SurfaceControl surfaceControl) {
        try {
            final GraphicBuffer buffer = mBufferQ.take();
            int transform = BUFFER_TRANSFORM_IDENTITY;
            if (mTransformHint == BUFFER_TRANSFORM_ROTATE_90) {
                transform = BUFFER_TRANSFORM_ROTATE_270;
            } else if (mTransformHint == BUFFER_TRANSFORM_ROTATE_270) {
                transform = BUFFER_TRANSFORM_ROTATE_90;
            }
            t.setBufferTransform(surfaceControl, transform);
            t.setBuffer(
                    surfaceControl,
                    HardwareBuffer.createFromGraphicBuffer(buffer),
                    null,
                    (SyncFence fence) -> releaseCallback(fence, buffer));
        } catch (InterruptedException ignore) {
        }
    }

    public void releaseCallback(SyncFence fence, GraphicBuffer buffer) {
        if (fence != null) {
            fence.awaitForever();
        }
        mBufferQ.add(buffer);
    }

    public void freeBuffers() {
        for (GraphicBuffer buffer : mBufferQ) {
            buffer.destroy();
        }
    }
}
