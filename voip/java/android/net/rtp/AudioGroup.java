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

import android.media.AudioManager;

import java.util.HashMap;
import java.util.Map;

/**
 * An AudioGroup is an audio hub for the speaker, the microphone, and
 * {@link AudioStream}s. Each of these components can be logically turned on
 * or off by calling {@link #setMode(int)} or {@link RtpStream#setMode(int)}.
 * The AudioGroup will go through these components and process them one by one
 * within its execution loop. The loop consists of four steps. First, for each
 * AudioStream not in {@link RtpStream#MODE_SEND_ONLY}, decodes its incoming
 * packets and stores in its buffer. Then, if the microphone is enabled,
 * processes the recorded audio and stores in its buffer. Third, if the speaker
 * is enabled, mixes all AudioStream buffers and plays back. Finally, for each
 * AudioStream not in {@link RtpStream#MODE_RECEIVE_ONLY}, mixes all other
 * buffers and sends back the encoded packets. An AudioGroup does nothing if
 * there is no AudioStream in it.
 *
 * <p>Few things must be noticed before using these classes. The performance is
 * highly related to the system load and the network bandwidth. Usually a
 * simpler {@link AudioCodec} costs fewer CPU cycles but requires more network
 * bandwidth, and vise versa. Using two AudioStreams at the same time doubles
 * not only the load but also the bandwidth. The condition varies from one
 * device to another, and developers should choose the right combination in
 * order to get the best result.</p>
 *
 * <p>It is sometimes useful to keep multiple AudioGroups at the same time. For
 * example, a Voice over IP (VoIP) application might want to put a conference
 * call on hold in order to make a new call but still allow people in the
 * conference call talking to each other. This can be done easily using two
 * AudioGroups, but there are some limitations. Since the speaker and the
 * microphone are globally shared resources, only one AudioGroup at a time is
 * allowed to run in a mode other than {@link #MODE_ON_HOLD}. The others will
 * be unable to acquire these resources and fail silently.</p>
 *
 * <p class="note">Using this class requires
 * {@link android.Manifest.permission#RECORD_AUDIO} permission. Developers
 * should set the audio mode to {@link AudioManager#MODE_IN_COMMUNICATION}
 * using {@link AudioManager#setMode(int)} and change it back when none of
 * the AudioGroups is in use.</p>
 *
 * @see AudioStream
 */
public class AudioGroup {
    /**
     * This mode is similar to {@link #MODE_NORMAL} except the speaker and
     * the microphone are both disabled.
     */
    public static final int MODE_ON_HOLD = 0;

    /**
     * This mode is similar to {@link #MODE_NORMAL} except the microphone is
     * disabled.
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

    private static final int MODE_LAST = 3;

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
     * Returns the {@link AudioStream}s in this group.
     */
    public AudioStream[] getStreams() {
        synchronized (this) {
            return mStreams.keySet().toArray(new AudioStream[mStreams.size()]);
        }
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
    public void setMode(int mode) {
        if (mode < 0 || mode > MODE_LAST) {
            throw new IllegalArgumentException("Invalid mode");
        }
        synchronized (this) {
            nativeSetMode(mode);
            mMode = mode;
        }
    }

    private native void nativeSetMode(int mode);

    // Package-private method used by AudioStream.join().
    synchronized void add(AudioStream stream) {
        if (!mStreams.containsKey(stream)) {
            try {
                AudioCodec codec = stream.getCodec();
                String codecSpec = String.format("%d %s %s", codec.type,
                        codec.rtpmap, codec.fmtp);
                int id = nativeAdd(stream.getMode(), stream.getSocket(),
                        stream.getRemoteAddress().getHostAddress(),
                        stream.getRemotePort(), codecSpec, stream.getDtmfType());
                mStreams.put(stream, id);
            } catch (NullPointerException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private native int nativeAdd(int mode, int socket, String remoteAddress,
            int remotePort, String codecSpec, int dtmfType);

    // Package-private method used by AudioStream.join().
    synchronized void remove(AudioStream stream) {
        Integer id = mStreams.remove(stream);
        if (id != null) {
            nativeRemove(id);
        }
    }

    private native void nativeRemove(int id);

    /**
     * Sends a DTMF digit to every {@link AudioStream} in this group. Currently
     * only event {@code 0} to {@code 15} are supported.
     *
     * @throws IllegalArgumentException if the event is invalid.
     */
    public void sendDtmf(int event) {
        if (event < 0 || event > 15) {
            throw new IllegalArgumentException("Invalid event");
        }
        synchronized (this) {
            nativeSendDtmf(event);
        }
    }

    private native void nativeSendDtmf(int event);

    /**
     * Removes every {@link AudioStream} in this group.
     */
    public void clear() {
        for (AudioStream stream : getStreams()) {
            stream.join(null);
        }
    }

    @Override
    protected void finalize() throws Throwable {
        nativeRemove(0);
        super.finalize();
    }
}
