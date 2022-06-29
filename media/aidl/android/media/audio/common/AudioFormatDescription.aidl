/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.media.audio.common;

import android.media.audio.common.AudioFormatType;
import android.media.audio.common.PcmType;

/**
 * An extensible type for specifying audio formats. All formats are largely
 * divided into two classes: PCM and non-PCM (bitstreams). Bitstreams can
 * be encapsulated into PCM streams.
 *
 * The type defined in a way to make each format uniquely identifiable, so
 * that if the framework and the HAL construct a value for the same type
 * (e.g. PCM 16 bit), they will produce identical parcelables which will have
 * identical hashes. This makes possible deduplicating type descriptions
 * by the framework when they are received from different HAL modules without
 * relying on having some centralized registry of enumeration values.
 *
 * {@hide}
 */
@JavaDerive(equals=true, toString=true)
@VintfStability
parcelable AudioFormatDescription {
    /**
     * The type of the audio format. See the 'AudioFormatType' for the
     * list of supported values.
     */
    AudioFormatType type = AudioFormatType.DEFAULT;
    /**
     * The type of the PCM stream or the transport stream for PCM
     * encapsulations.  See 'PcmType' for the list of supported values.
     */
    PcmType pcm = PcmType.DEFAULT;
    /**
     * Optional encoding specification. Must be left empty when:
     *
     *  - 'type == DEFAULT && pcm == DEFAULT' -- that means "default" type;
     *  - 'type == PCM' -- that means a regular PCM stream (not an encapsulation
     *    of an encoded bitstream).
     *
     * For PCM encapsulations of encoded bitstreams (e.g. an encapsulation
     * according to IEC-61937 standard), the value of the 'pcm' field must
     * be set accordingly, as an example, PCM_INT_16_BIT must be used for
     * IEC-61937. Note that 'type == NON_PCM' in this case.
     *
     * Encoding names mostly follow IANA standards for media types (MIME), and
     * frameworks/av/media/libstagefright/foundation/MediaDefs.cpp with the
     * latter having priority.  Since there are still many audio types not found
     * in any of these lists, the following rules are applied:
     *
     *   - If there is a direct MIME type for the encoding, the MIME type name
     *     is used as is, e.g. "audio/eac3" for the EAC-3 format.
     *   - If the encoding is a "subformat" of a MIME-registered format,
     *     the latter is augmented with a suffix, e.g. "audio/eac3-joc" for the
     *     JOC extension of EAC-3.
     *   - If it's a proprietary format, a "vnd." prefix is added, similar to
     *     IANA rules, e.g. "audio/vnd.dolby.truehd".
     *   - Otherwise, "x-" prefix is added, e.g. "audio/x-iec61937".
     *   - All MIME types not found in the IANA formats list have an associated
     *     comment.
     */
    @utf8InCpp String encoding;
}
