/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.media;

import java.io.InputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import android.media.MediaCodec.BufferInfo;
import android.util.Log;


/**
 * AmrInputStream
 * @hide
 */
public final class AmrInputStream extends InputStream {
    private final static String TAG = "AmrInputStream";
    
    // frame is 20 msec at 8.000 khz
    private final static int SAMPLES_PER_FRAME = 8000 * 20 / 1000;

    MediaCodec mCodec;
    BufferInfo mInfo;
    boolean mSawOutputEOS;
    boolean mSawInputEOS;

    // pcm input stream
    private InputStream mInputStream;

    // result amr stream
    private final byte[] mBuf = new byte[SAMPLES_PER_FRAME * 2];
    private int mBufIn = 0;
    private int mBufOut = 0;

    // helper for bytewise read()
    private byte[] mOneByte = new byte[1];

    /**
     * Create a new AmrInputStream, which converts 16 bit PCM to AMR
     * @param inputStream InputStream containing 16 bit PCM.
     */
    public AmrInputStream(InputStream inputStream) {
        mInputStream = inputStream;

        MediaFormat format  = new MediaFormat();
        format.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_AMR_NB);
        format.setInteger(MediaFormat.KEY_SAMPLE_RATE, 8000);
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 12200);

        MediaCodecList mcl = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        String name = mcl.findEncoderForFormat(format);
        if (name != null) {
            try {
                mCodec = MediaCodec.createByCodecName(name);
                mCodec.configure(format,
                        null /* surface */,
                        null /* crypto */,
                        MediaCodec.CONFIGURE_FLAG_ENCODE);
                mCodec.start();
            } catch (IOException e) {
                if (mCodec != null) {
                    mCodec.release();
                }
                mCodec = null;
            }
        }
        mInfo = new BufferInfo();
    }

    @Override
    public int read() throws IOException {
        int rtn = read(mOneByte, 0, 1);
        return rtn == 1 ? (0xff & mOneByte[0]) : -1;
    }

    @Override
    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    @Override
    public int read(byte[] b, int offset, int length) throws IOException {
        if (mCodec == null) {
            throw new IllegalStateException("not open");
        }

        if (mBufOut >= mBufIn && !mSawOutputEOS) {
            // no data left in buffer, refill it
            mBufOut = 0;
            mBufIn = 0;

            // first push as much data into the encoder as possible
            while (!mSawInputEOS) {
                int index = mCodec.dequeueInputBuffer(0);
                if (index < 0) {
                    // no input buffer currently available
                    break;
                } else {
                    int numRead;
                    for (numRead = 0; numRead < SAMPLES_PER_FRAME * 2; ) {
                        int n = mInputStream.read(mBuf, numRead, SAMPLES_PER_FRAME * 2 - numRead);
                        if (n == -1) {
                            mSawInputEOS = true;
                            break;
                        }
                        numRead += n;
                    }
                    ByteBuffer buf = mCodec.getInputBuffer(index);
                    buf.put(mBuf, 0, numRead);
                    mCodec.queueInputBuffer(index,
                            0 /* offset */,
                            numRead,
                            0 /* presentationTimeUs */,
                            mSawInputEOS ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0 /* flags */);
                }
            }

            // now read encoded data from the encoder (blocking, since we just filled up the
            // encoder's input with data it should be able to output at least one buffer)
            while (true) {
                int index = mCodec.dequeueOutputBuffer(mInfo, -1);
                if (index >= 0) {
                    mBufIn = mInfo.size;
                    ByteBuffer out = mCodec.getOutputBuffer(index);
                    out.get(mBuf, 0 /* offset */, mBufIn /* length */);
                    mCodec.releaseOutputBuffer(index,  false /* render */);
                    if ((mInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        mSawOutputEOS = true;
                    }
                    break;
                }
            }
        }

        if (mBufOut < mBufIn) {
            // there is data in the buffer
            if (length > mBufIn - mBufOut) {
                length = mBufIn - mBufOut;
            }
            System.arraycopy(mBuf, mBufOut, b, offset, length);
            mBufOut += length;
            return length;
        }

        if (mSawInputEOS && mSawOutputEOS) {
            // no more data available in buffer, codec or input stream
            return -1;
        }

        // caller should try again
        return 0;
    }

    @Override
    public void close() throws IOException {
        try {
            if (mInputStream != null) {
                mInputStream.close();
            }
        } finally {
            mInputStream = null;
            try {
                if (mCodec != null) {
                    mCodec.release();
                }
            } finally {
                mCodec = null;
            }
        }
    }

    @Override
    protected void finalize() throws Throwable {
        if (mCodec != null) {
            Log.w(TAG, "AmrInputStream wasn't closed");
            mCodec.release();
        }
    }
}
