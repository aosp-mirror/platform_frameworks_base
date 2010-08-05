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

package android.net.rtp;

import java.util.HashMap;
import java.util.Map;

/**
 */
/** @hide */
public class AudioGroup {
    public static final int MODE_ON_HOLD = 0;
    public static final int MODE_MUTED = 1;
    public static final int MODE_NORMAL = 2;
    public static final int MODE_EC_ENABLED = 3;

    private final Map<AudioStream, Integer> mStreams;
    private int mMode = MODE_ON_HOLD;

    private int mNative;
    static {
        System.loadLibrary("rtp_jni");
    }

    public AudioGroup() {
        mStreams = new HashMap<AudioStream, Integer>();
    }

    public int getMode() {
        return mMode;
    }

    public synchronized native void setMode(int mode);

    synchronized void add(AudioStream stream, AudioCodec codec, int codecType, int dtmfType) {
        if (!mStreams.containsKey(stream)) {
            try {
                int id = add(stream.getMode(), stream.dup(),
                        stream.getRemoteAddress().getHostAddress(), stream.getRemotePort(),
                        codec.name, codec.sampleRate, codec.sampleCount, codecType, dtmfType);
                mStreams.put(stream, id);
            } catch (NullPointerException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private native int add(int mode, int socket, String remoteAddress, int remotePort,
            String codecName, int sampleRate, int sampleCount, int codecType, int dtmfType);

    synchronized void remove(AudioStream stream) {
        Integer id = mStreams.remove(stream);
        if (id != null) {
            remove(id);
        }
    }

    private native void remove(int id);

    /**
     * Sends a DTMF digit to every {@link AudioStream} in this group. Currently
     * only event {@code 0} to {@code 15} are supported.
     *
     * @throws IllegalArgumentException if the event is invalid.
     */
    public native synchronized void sendDtmf(int event);

    public synchronized void reset() {
        remove(-1);
    }

    @Override
    protected void finalize() throws Throwable {
        reset();
        super.finalize();
    }
}
