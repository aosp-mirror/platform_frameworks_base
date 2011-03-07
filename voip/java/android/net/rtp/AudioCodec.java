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

import java.util.Arrays;

/**
 * This class defines a collection of audio codecs to be used with
 * {@link AudioStream}s. Their parameters are designed to be exchanged using
 * Session Description Protocol (SDP). Most of the values listed here can be
 * found in RFC 3551, while others are described in separated standards.
 *
 * <p>Few simple configurations are defined as public static instances for the
 * convenience of direct uses. More complicated ones could be obtained using
 * {@link #getCodec(int, String, String)}. For example, one can use the
 * following snippet to create a mode-1-only AMR codec.</p>
 * <pre>
 * AudioCodec codec = AudioCodec.getCodec(100, "AMR/8000", "mode-set=1");
 * </pre>
 *
 * @see AudioStream
 */
public class AudioCodec {
    /**
     * The RTP payload type of the encoding.
     */
    public final int type;

    /**
     * The encoding parameters to be used in the corresponding SDP attribute.
     */
    public final String rtpmap;

    /**
     * The format parameters to be used in the corresponding SDP attribute.
     */
    public final String fmtp;

    /**
     * G.711 u-law audio codec.
     */
    public static final AudioCodec PCMU = new AudioCodec(0, "PCMU/8000", null);

    /**
     * G.711 a-law audio codec.
     */
    public static final AudioCodec PCMA = new AudioCodec(8, "PCMA/8000", null);

    /**
     * GSM Full-Rate audio codec, also known as GSM-FR, GSM 06.10, GSM, or
     * simply FR.
     */
    public static final AudioCodec GSM = new AudioCodec(3, "GSM/8000", null);

    /**
     * GSM Enhanced Full-Rate audio codec, also known as GSM-EFR, GSM 06.60, or
     * simply EFR.
     */
    public static final AudioCodec GSM_EFR = new AudioCodec(96, "GSM-EFR/8000", null);

    /**
     * Adaptive Multi-Rate narrowband audio codec, also known as AMR or AMR-NB.
     * Currently CRC, robust sorting, and interleaving are not supported. See
     * more details about these features in RFC 4867.
     */
    public static final AudioCodec AMR = new AudioCodec(97, "AMR/8000", null);

    private static final AudioCodec[] sCodecs = {GSM_EFR, AMR, GSM, PCMU, PCMA};

    private AudioCodec(int type, String rtpmap, String fmtp) {
        this.type = type;
        this.rtpmap = rtpmap;
        this.fmtp = fmtp;
    }

    /**
     * Returns system supported audio codecs.
     */
    public static AudioCodec[] getCodecs() {
        return Arrays.copyOf(sCodecs, sCodecs.length);
    }

    /**
     * Creates an AudioCodec according to the given configuration.
     *
     * @param type The payload type of the encoding defined in RTP/AVP.
     * @param rtpmap The encoding parameters specified in the corresponding SDP
     *     attribute, or null if it is not available.
     * @param fmtp The format parameters specified in the corresponding SDP
     *     attribute, or null if it is not available.
     * @return The configured AudioCodec or {@code null} if it is not supported.
     */
    public static AudioCodec getCodec(int type, String rtpmap, String fmtp) {
        if (type < 0 || type > 127) {
            return null;
        }

        AudioCodec hint = null;
        if (rtpmap != null) {
            String clue = rtpmap.trim().toUpperCase();
            for (AudioCodec codec : sCodecs) {
                if (clue.startsWith(codec.rtpmap)) {
                    String channels = clue.substring(codec.rtpmap.length());
                    if (channels.length() == 0 || channels.equals("/1")) {
                        hint = codec;
                    }
                    break;
                }
            }
        } else if (type < 96) {
            for (AudioCodec codec : sCodecs) {
                if (type == codec.type) {
                    hint = codec;
                    rtpmap = codec.rtpmap;
                    break;
                }
            }
        }

        if (hint == null) {
            return null;
        }
        if (hint == AMR && fmtp != null) {
            String clue = fmtp.toLowerCase();
            if (clue.contains("crc=1") || clue.contains("robust-sorting=1") ||
                    clue.contains("interleaving=")) {
                return null;
            }
        }
        return new AudioCodec(type, rtpmap, fmtp);
    }
}
