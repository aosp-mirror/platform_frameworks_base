/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.accessorydisplay.sink;

import com.android.accessorydisplay.common.Protocol;
import com.android.accessorydisplay.common.Service;
import com.android.accessorydisplay.common.Transport;

import android.content.Context;
import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.IOException;
import java.nio.ByteBuffer;

public class DisplaySinkService extends Service implements SurfaceHolder.Callback {
    private final ByteBuffer mBuffer = ByteBuffer.allocate(12);
    private final Handler mTransportHandler;
    private final int mDensityDpi;

    private SurfaceView mSurfaceView;

    // These fields are guarded by the following lock.
    // This is to ensure that the surface lifecycle is respected.  Although decoding
    // happens on the transport thread, we are not allowed to access the surface after
    // it is destroyed by the UI thread so we need to stop the codec immediately.
    private final Object mSurfaceAndCodecLock = new Object();
    private Surface mSurface;
    private int mSurfaceWidth;
    private int mSurfaceHeight;
    private MediaCodec mCodec;
    private ByteBuffer[] mCodecInputBuffers;
    private BufferInfo mCodecBufferInfo;

    public DisplaySinkService(Context context, Transport transport, int densityDpi) {
        super(context, transport, Protocol.DisplaySinkService.ID);
        mTransportHandler = transport.getHandler();
        mDensityDpi = densityDpi;
    }

    public void setSurfaceView(final SurfaceView surfaceView) {
        if (mSurfaceView != surfaceView) {
            final SurfaceView oldSurfaceView = mSurfaceView;
            mSurfaceView = surfaceView;

            if (oldSurfaceView != null) {
                oldSurfaceView.post(new Runnable() {
                    @Override
                    public void run() {
                        final SurfaceHolder holder = oldSurfaceView.getHolder();
                        holder.removeCallback(DisplaySinkService.this);
                        updateSurfaceFromUi(null);
                    }
                });
            }

            if (surfaceView != null) {
                surfaceView.post(new Runnable() {
                    @Override
                    public void run() {
                        final SurfaceHolder holder = surfaceView.getHolder();
                        holder.addCallback(DisplaySinkService.this);
                        updateSurfaceFromUi(holder);
                    }
                });
            }
        }
    }

    @Override
    public void onMessageReceived(int service, int what, ByteBuffer content) {
        switch (what) {
            case Protocol.DisplaySinkService.MSG_QUERY: {
                getLogger().log("Received MSG_QUERY.");
                sendSinkStatus();
                break;
            }

            case Protocol.DisplaySinkService.MSG_CONTENT: {
                decode(content);
                break;
            }
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // Ignore.  Wait for surface changed event that follows.
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        updateSurfaceFromUi(holder);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        updateSurfaceFromUi(null);
    }

    private void updateSurfaceFromUi(SurfaceHolder holder) {
        Surface surface = null;
        int width = 0, height = 0;
        if (holder != null && !holder.isCreating()) {
            surface = holder.getSurface();
            if (surface.isValid()) {
                final Rect frame = holder.getSurfaceFrame();
                width = frame.width();
                height = frame.height();
            } else {
                surface = null;
            }
        }

        synchronized (mSurfaceAndCodecLock) {
            if (mSurface == surface &&  mSurfaceWidth == width && mSurfaceHeight == height) {
                return;
            }

            mSurface = surface;
            mSurfaceWidth = width;
            mSurfaceHeight = height;

            if (mCodec != null) {
                mCodec.stop();
                mCodec = null;
                mCodecInputBuffers = null;
                mCodecBufferInfo = null;
            }

            if (mSurface != null) {
                MediaFormat format = MediaFormat.createVideoFormat(
                        "video/avc", mSurfaceWidth, mSurfaceHeight);
                try {
                    mCodec = MediaCodec.createDecoderByType("video/avc");
                } catch (IOException e) {
                    throw new RuntimeException(
                            "failed to create video/avc decoder", e);
                }
                mCodec.configure(format, mSurface, null, 0);
                mCodec.start();
                mCodecBufferInfo = new BufferInfo();
            }

            mTransportHandler.post(new Runnable() {
                @Override
                public void run() {
                    sendSinkStatus();
                }
            });
        }
    }

    private void decode(ByteBuffer content) {
        if (content == null) {
            return;
        }
        synchronized (mSurfaceAndCodecLock) {
            if (mCodec == null) {
                return;
            }

            while (content.hasRemaining()) {
                if (!provideCodecInputLocked(content)) {
                    getLogger().log("Dropping content because there are no available buffers.");
                    return;
                }

                consumeCodecOutputLocked();
            }
        }
    }

    private boolean provideCodecInputLocked(ByteBuffer content) {
        final int index = mCodec.dequeueInputBuffer(0);
        if (index < 0) {
            return false;
        }
        if (mCodecInputBuffers == null) {
            mCodecInputBuffers = mCodec.getInputBuffers();
        }
        final ByteBuffer buffer = mCodecInputBuffers[index];
        final int capacity = buffer.capacity();
        buffer.clear();
        if (content.remaining() <= capacity) {
            buffer.put(content);
        } else {
            final int limit = content.limit();
            content.limit(content.position() + capacity);
            buffer.put(content);
            content.limit(limit);
        }
        buffer.flip();
        mCodec.queueInputBuffer(index, 0, buffer.limit(), 0, 0);
        return true;
    }

    private void consumeCodecOutputLocked() {
        for (;;) {
            final int index = mCodec.dequeueOutputBuffer(mCodecBufferInfo, 0);
            if (index >= 0) {
                mCodec.releaseOutputBuffer(index, true /*render*/);
            } else if (index != MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED
                    && index != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                break;
            }
        }
    }

    private void sendSinkStatus() {
        synchronized (mSurfaceAndCodecLock) {
            if (mCodec != null) {
                mBuffer.clear();
                mBuffer.putInt(mSurfaceWidth);
                mBuffer.putInt(mSurfaceHeight);
                mBuffer.putInt(mDensityDpi);
                mBuffer.flip();
                getTransport().sendMessage(Protocol.DisplaySourceService.ID,
                        Protocol.DisplaySourceService.MSG_SINK_AVAILABLE, mBuffer);
            } else {
                getTransport().sendMessage(Protocol.DisplaySourceService.ID,
                        Protocol.DisplaySourceService.MSG_SINK_NOT_AVAILABLE, null);
            }
        }
    }
}
