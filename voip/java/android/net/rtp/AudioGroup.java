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
 * An AudioGroup acts as a router connected to the speaker, the microphone, and
 * {@link AudioStream}s. Its pipeline has four steps. First, for each
 * AudioStream not in {@link RtpStream#MODE_SEND_ONLY}, decodes its incoming
 * packets and stores in its buffer. Then, if the microphone is enabled,
 * processes the recorded audio and stores in its buffer. Third, if the speaker
 * is enabled, mixes and playbacks buffers of all AudioStreams. Finally, for
 * each AudioStream not in {@link RtpStream#MODE_RECEIVE_ONLY}, mixes all other
 * buffers and sends back the encoded packets. An AudioGroup does nothing if
 * there is no AudioStream in it.
 *
 * <p>Few things must be noticed before using these classes. The performance is
 * highly related to the system load and the network bandwidth. Usually a
 * simpler {@link AudioCodec} costs fewer CPU cycles but requires more network
 * bandwidth, and vise versa. Using two AudioStreams at the same time not only
 * doubles the load but also the bandwidth. The condition varies from one device
 * to another, and developers must choose the right combination in order to get
 * the best result.
 *
 * <p>It is sometimes useful to keep multiple AudioGroups at the same time. For
 * example, a Voice over IP (VoIP) application might want to put a conference
 * call on hold in order to make a new call but still allow people in the
 * previous call to talk to each other. This can be done easily using two
 * AudioGroups, but there are some limitations. Since the speaker and the
 * microphone are shared globally, only one AudioGroup is allowed to run in
 * modes other than {@link #MODE_ON_HOLD}. In addition, before adding an
 * AudioStream into an AudioGroup, one should always put all other AudioGroups
 * into {@link #MODE_ON_HOLD}. That will make sure the audio driver correctly
 * initialized.
 * @hide
 */
public class AudioGroup {
    /**
     * This mode is similar to {@link #MODE_NORMAL} except the speaker and
     * the microphone are disabled.
     */
    public static final int MODE_ON_HOLD = 0;

    /**
     * This mode is similar to {@link #MODE_NORMAL} except the microphone is
     * muted.
     */
    public static final int MODE_MUTED = 1;

    /**
     * This mode indicates that the speaker, the microphone, and all
     * {@link AudioStream}s in the group are enabled. First, the packets
     * received from the streams are decoded and mixed with the audio recorded
     * from the microphone. Then, the results are played back to the speaker,
     * encoded and sent back to each stream.
     */
    public static final int MODE_NORMAL = 2;

    /**
     * This mode is similar to {@link #MODE_NORMAL} except the echo suppression
     * is enabled. It should be only used when the speaker phone is on.
     */
    public static final int MODE_ECHO_SUPPRESSION = 3;

    private final Map<AudioStream, Integer> mStreams;
    private int mMode = MODE_ON_HOLD;

    private int mNative;
    static {
        System.loadLibrary("rtp_jni");
    }

    /**
     * Creates an empty AudioGroup.
     */
    public AudioGroup() {
        mStreams = new HashMap<AudioStream, Integer>();
    }

    /**
     * Returns the current mode.
     */
    public int getMode() {
        return mMode;
    }

    /**
     * Changes the current mode. It must be one of {@link #MODE_ON_HOLD},
     * {@link #MODE_MUTED}, {@link #MODE_NORMAL}, and
     * {@link #MODE_ECHO_SUPPRESSION}.
     *
     * @param mode The mode to change to.
     * @throws IllegalArgumentException if the mode is invalid.
     */
    public synchronized native void setMode(int mode);

    private native void add(int mode, int socket, String remoteAddress,
            int remotePort, String codecSpec, int dtmfType);

    synchronized void add(AudioStream stream, AudioCodec codec, int dtmfType) {
        if (!mStreams.containsKey(stream)) {
            try {
                int socket = stream.dup();
                String codecSpec = String.format("%d %s %s", codec.type,
                        codec.rtpmap, codec.fmtp);
                add(stream.getMode(), socket,
                        stream.getRemoteAddress().getHostAddress(),
                        stream.getRemotePort(), codecSpec, dtmfType);
                mStreams.put(stream, socket);
            } catch (NullPointerException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private native void remove(int socket);

    synchronized void remove(AudioStream stream) {
        Integer socket = mStreams.remove(stream);
        if (socket != null) {
            remove(socket);
        }
    }

    /**
     * Sends a DTMF digit to every {@link AudioStream} in this group. Currently
     * only event {@code 0} to {@code 15} are supported.
     *
     * @throws IllegalArgumentException if the event is invalid.
     */
    public native synchronized void sendDtmf(int event);

    /**
     * Removes every {@link AudioStream} in this group.
     */
    public synchronized void clear() {
        remove(-1);
    }

    @Override
    protected void finalize() throws Throwable {
        clear();
        super.finalize();
    }
}
