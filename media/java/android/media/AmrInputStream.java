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


/**
 * AmrInputStream
 * @hide
 */
public final class AmrInputStream extends InputStream
{    
    static {
        System.loadLibrary("media_jni");
    }
    
    private final static String TAG = "AmrInputStream";
    
    // frame is 20 msec at 8.000 khz
    private final static int SAMPLES_PER_FRAME = 8000 * 20 / 1000;
    
    // pcm input stream
    private InputStream mInputStream;
    
    // native handle
    private long mGae;
    
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
        mGae = GsmAmrEncoderNew();
        GsmAmrEncoderInitialize(mGae);
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
        if (mGae == 0) throw new IllegalStateException("not open");
        
        // local buffer of amr encoded audio empty
        if (mBufOut >= mBufIn) {
            // reset the buffer
            mBufOut = 0;
            mBufIn = 0;
            
            // fetch a 20 msec frame of pcm
            for (int i = 0; i < SAMPLES_PER_FRAME * 2; ) {
                int n = mInputStream.read(mBuf, i, SAMPLES_PER_FRAME * 2 - i);
                if (n == -1) return -1;
                i += n;
            }
            
            // encode it
            mBufIn = GsmAmrEncoderEncode(mGae, mBuf, 0, mBuf, 0);
        }
        
        // return encoded audio to user
        if (length > mBufIn - mBufOut) length = mBufIn - mBufOut;
        System.arraycopy(mBuf, mBufOut, b, offset, length);
        mBufOut += length;
        
        return length;
    }

    @Override
    public void close() throws IOException {
        try {
            if (mInputStream != null) mInputStream.close();
        } finally {
            mInputStream = null;
            try {
                if (mGae != 0) GsmAmrEncoderCleanup(mGae);
            } finally {
                try {
                    if (mGae != 0) GsmAmrEncoderDelete(mGae);
                } finally {
                    mGae = 0;
                }
            }
        }
    }

    @Override
    protected void finalize() throws Throwable {
        if (mGae != 0) {
            close();
            throw new IllegalStateException("someone forgot to close AmrInputStream");
        }
    }
    
    //
    // AudioRecord JNI interface
    //
    private static native long GsmAmrEncoderNew();
    private static native void GsmAmrEncoderInitialize(long gae);
    private static native int GsmAmrEncoderEncode(long gae,
            byte[] pcm, int pcmOffset, byte[] amr, int amrOffset) throws IOException;
    private static native void GsmAmrEncoderCleanup(long gae);
    private static native void GsmAmrEncoderDelete(long gae);

}
