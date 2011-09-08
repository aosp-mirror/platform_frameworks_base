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

extern "C" {
#include "gsm.h"
}

namespace {

class GsmCodec : public AudioCodec
{
public:
    GsmCodec() {
        mEncode = gsm_create();
        mDecode = gsm_create();
    }

    ~GsmCodec() {
        if (mEncode) {
            gsm_destroy(mEncode);
        }
        if (mDecode) {
            gsm_destroy(mDecode);
        }
    }

    int set(int sampleRate, const char *fmtp) {
        return (sampleRate == 8000 && mEncode && mDecode) ? 160 : -1;
    }

    int encode(void *payload, int16_t *samples);
    int decode(int16_t *samples, int count, void *payload, int length);

private:
    gsm mEncode;
    gsm mDecode;
};

int GsmCodec::encode(void *payload, int16_t *samples)
{
    gsm_encode(mEncode, samples, (unsigned char *)payload);
    return 33;
}

int GsmCodec::decode(int16_t *samples, int count, void *payload, int length)
{
    unsigned char *bytes = (unsigned char *)payload;
    int n = 0;
    while (n + 160 <= count && length >= 33 &&
        gsm_decode(mDecode, bytes, &samples[n]) == 0) {
        n += 160;
        length -= 33;
        bytes += 33;
    }
    return n;
}

} // namespace

AudioCodec *newGsmCodec()
{
    return new GsmCodec;
}
