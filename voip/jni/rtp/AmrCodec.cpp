/*
 * Copyrightm (C) 2010 The Android Open Source Project
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

#include "AudioCodec.h"

#include "gsmamr_dec.h"
#include "gsmamr_enc.h"

namespace {

class GsmEfrCodec : public AudioCodec
{
public:
    GsmEfrCodec() {
        if (AMREncodeInit(&mEncoder, &mSidSync, false)) {
            mEncoder = NULL;
        }
        if (GSMInitDecode(&mDecoder, (Word8 *)"RTP")) {
            mDecoder = NULL;
        }
    }

    ~GsmEfrCodec() {
        if (mEncoder) {
            AMREncodeExit(&mEncoder, &mSidSync);
        }
        if (mDecoder) {
            GSMDecodeFrameExit(&mDecoder);
        }
    }

    int set(int sampleRate, const char *fmtp) {
        return (sampleRate == 8000 && mEncoder && mDecoder) ? 160 : -1;
    }

    int encode(void *payload, int16_t *samples);
    int decode(int16_t *samples, void *payload, int length);

private:
    void *mEncoder;
    void *mSidSync;
    void *mDecoder;
};

int GsmEfrCodec::encode(void *payload, int16_t *samples)
{
    unsigned char *bytes = (unsigned char *)payload;
    Frame_Type_3GPP type;

    int length = AMREncode(mEncoder, mSidSync, MR122,
        samples, bytes, &type, AMR_TX_WMF);

    if (type == AMR_122 && length == 32) {
        bytes[0] = 0xC0 | (bytes[1] >> 4);
        for (int i = 1; i < 31; ++i) {
            bytes[i] = (bytes[i] << 4) | (bytes[i + 1] >> 4);
        }
        return 31;
    }
    return -1;
}

int GsmEfrCodec::decode(int16_t *samples, void *payload, int length)
{
    unsigned char *bytes = (unsigned char *)payload;
    if (length == 31 && (bytes[0] >> 4) == 0x0C) {
        for (int i = 0; i < 30; ++i) {
            bytes[i] = (bytes[i] << 4) | (bytes[i + 1] >> 4);
        }
        bytes[30] <<= 4;

        if (AMRDecode(mDecoder, AMR_122, bytes, samples, MIME_IETF) == 31) {
            return 160;
        }
    }
    return -1;
}

} // namespace

AudioCodec *newGsmEfrCodec()
{
    return new GsmEfrCodec;
}
