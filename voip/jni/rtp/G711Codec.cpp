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

namespace {

const int8_t gExponents[128] = {
    0, 1, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4,
    5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5,
    6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6,
    6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6,
    7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
    7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
    7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
    7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
};

//------------------------------------------------------------------------------

class UlawCodec : public AudioCodec
{
public:
    int set(int sampleRate, const char *fmtp) {
        mSampleCount = sampleRate / 50;
        return mSampleCount;
    }
    int encode(void *payload, int16_t *samples);
    int decode(int16_t *samples, int count, void *payload, int length);
private:
    int mSampleCount;
};

int UlawCodec::encode(void *payload, int16_t *samples)
{
    int8_t *ulaws = (int8_t *)payload;
    for (int i = 0; i < mSampleCount; ++i) {
        int sample = samples[i];
        int sign = (sample >> 8) & 0x80;
        if (sample < 0) {
            sample = -sample;
        }
        sample += 132;
        if (sample > 32767) {
            sample = 32767;
        }
        int exponent = gExponents[sample >> 8];
        int mantissa = (sample >> (exponent + 3)) & 0x0F;
        ulaws[i] = ~(sign | (exponent << 4) | mantissa);
    }
    return mSampleCount;
}

int UlawCodec::decode(int16_t *samples, int count, void *payload, int length)
{
    int8_t *ulaws = (int8_t *)payload;
    if (length > count) {
        length = count;
    }
    for (int i = 0; i < length; ++i) {
        int ulaw = ~ulaws[i];
        int exponent = (ulaw >> 4) & 0x07;
        int mantissa = ulaw & 0x0F;
        int sample = (((mantissa << 3) + 132) << exponent) - 132;
        samples[i] = (ulaw < 0 ? -sample : sample);
    }
    return length;
}

//------------------------------------------------------------------------------

class AlawCodec : public AudioCodec
{
public:
    int set(int sampleRate, const char *fmtp) {
        mSampleCount = sampleRate / 50;
        return mSampleCount;
    }
    int encode(void *payload, int16_t *samples);
    int decode(int16_t *samples, int count, void *payload, int length);
private:
    int mSampleCount;
};

int AlawCodec::encode(void *payload, int16_t *samples)
{
    int8_t *alaws = (int8_t *)payload;
    for (int i = 0; i < mSampleCount; ++i) {
        int sample = samples[i];
        int sign = (sample >> 8) & 0x80;
        if (sample < 0) {
            sample = -sample;
        }
        if (sample > 32767) {
            sample = 32767;
        }
        int exponent = gExponents[sample >> 8];
        int mantissa = (sample >> (exponent == 0 ? 4 : exponent + 3)) & 0x0F;
        alaws[i] = (sign | (exponent << 4) | mantissa) ^ 0xD5;
    }
    return mSampleCount;
}

int AlawCodec::decode(int16_t *samples, int count, void *payload, int length)
{
    int8_t *alaws = (int8_t *)payload;
    if (length > count) {
        length = count;
    }
    for (int i = 0; i < length; ++i) {
        int alaw = alaws[i] ^ 0x55;
        int exponent = (alaw >> 4) & 0x07;
        int mantissa = alaw & 0x0F;
        int sample = (exponent == 0 ? (mantissa << 4) + 8 :
            ((mantissa << 3) + 132) << exponent);
        samples[i] = (alaw < 0 ? sample : -sample);
    }
    return length;
}

} // namespace

AudioCodec *newUlawCodec()
{
    return new UlawCodec;
}

AudioCodec *newAlawCodec()
{
    return new AlawCodec;
}
