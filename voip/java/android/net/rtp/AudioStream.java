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

import java.net.InetAddress;
import java.net.SocketException;

/**
 * AudioStream represents a RTP stream carrying audio payloads.
 */
/** @hide */
public class AudioStream extends RtpStream {
    private AudioCodec mCodec;
    private int mCodecType = -1;
    private int mDtmfType = -1;
    private AudioGroup mGroup;

    /**
     * Creates an AudioStream on the given local address. Note that the local
     * port is assigned automatically to conform with RFC 3550.
     *
     * @param address The network address of the local host to bind to.
     * @throws SocketException if the address cannot be bound or a problem
     *     occurs during binding.
     */
    public AudioStream(InetAddress address) throws SocketException {
        super(address);
    }

    /**
     * Returns {@code true} if the stream already joined an {@link AudioGroup}.
     */
    @Override
    public final boolean isBusy() {
        return mGroup != null;
    }

    /**
     * Returns the joined {@link AudioGroup}.
     */
    public AudioGroup getAudioGroup() {
        return mGroup;
    }

    /**
     * Joins an {@link AudioGroup}. Each stream can join only one group at a
     * time. The group can be changed by passing a different one or removed
     * by calling this method with {@code null}.
     *
     * @param group The AudioGroup to join or {@code null} to leave.
     * @throws IllegalStateException if the stream is not properly configured.
     * @see AudioGroup
     */
    public void join(AudioGroup group) {
        if (mGroup == group) {
            return;
        }
        if (mGroup != null) {
            mGroup.remove(this);
            mGroup = null;
        }
        if (group != null) {
            group.add(this, mCodec, mCodecType, mDtmfType);
            mGroup = group;
        }
    }

    /**
     * Sets the {@link AudioCodec} and its RTP payload type. According to RFC
     * 3551, the type must be in the range of 0 and 127, where 96 and above are
     * dynamic types. For codecs with static mappings (non-negative
     * {@link AudioCodec#defaultType}), assigning a different non-dynamic type
     * is disallowed.
     *
     * @param codec The AudioCodec to be used.
     * @param type The RTP payload type.
     * @throws IllegalArgumentException if the type is invalid or used by DTMF.
     * @throws IllegalStateException if the stream is busy.
     */
    public void setCodec(AudioCodec codec, int type) {
        if (isBusy()) {
            throw new IllegalStateException("Busy");
        }
        if (type < 0 || type > 127 || (type != codec.defaultType && type < 96)) {
            throw new IllegalArgumentException("Invalid type");
        }
        if (type == mDtmfType) {
            throw new IllegalArgumentException("The type is used by DTMF");
        }
        mCodec = codec;
        mCodecType = type;
    }

    /**
     * Sets the RTP payload type for dual-tone multi-frequency (DTMF) digits.
     * The primary usage is to send digits to the remote gateway to perform
     * certain tasks, such as second-stage dialing. According to RFC 2833, the
     * RTP payload type for DTMF is assigned dynamically, so it must be in the
     * range of 96 and 127. One can use {@code -1} to disable DTMF and free up
     * the previous assigned value. This method cannot be called when the stream
     * already joined an {@link AudioGroup}.
     *
     * @param type The RTP payload type to be used or {@code -1} to disable it.
     * @throws IllegalArgumentException if the type is invalid or used by codec.
     * @throws IllegalStateException if the stream is busy.
     * @see AudioGroup#sendDtmf(int)
     */
    public void setDtmfType(int type) {
        if (isBusy()) {
            throw new IllegalStateException("Busy");
        }
        if (type != -1) {
            if (type < 96 || type > 127) {
                throw new IllegalArgumentException("Invalid type");
            }
            if (type == mCodecType) {
                throw new IllegalArgumentException("The type is used by codec");
            }
        }
        mDtmfType = type;
    }
}
