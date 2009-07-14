/*
 * Copyright (C) 2009 The Android Open Source Project
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

#ifndef ANDROID_WAVEWRITER_H_

#define ANDROID_WAVEWRITER_H_

namespace android {

class WaveWriter {
public:
    WaveWriter(const char *filename,
               uint16_t num_channels, uint32_t sampling_rate)
        : mFile(fopen(filename, "wb")),
          mTotalBytes(0) {
        fwrite("RIFFxxxxWAVEfmt \x10\x00\x00\x00\x01\x00", 1, 22, mFile); 
        write_u16(num_channels);
        write_u32(sampling_rate);
        write_u32(sampling_rate * num_channels * 2);
        write_u16(num_channels * 2);
        write_u16(16);
        fwrite("dataxxxx", 1, 8, mFile);
    }

    ~WaveWriter() {
        fseek(mFile, 40, SEEK_SET);
        write_u32(mTotalBytes);

        fseek(mFile, 4, SEEK_SET);
        write_u32(36 + mTotalBytes);

        fclose(mFile);
        mFile = NULL;
    }

    void Append(const void *data, size_t size) {
        fwrite(data, 1, size, mFile);
        mTotalBytes += size;
    }

private:
    void write_u16(uint16_t x) {
        fputc(x & 0xff, mFile);
        fputc(x >> 8, mFile);
    }

    void write_u32(uint32_t x) {
        write_u16(x & 0xffff);
        write_u16(x >> 16);
    }

    FILE *mFile;
    size_t mTotalBytes;
};

}  // namespace android

#endif  // ANDROID_WAVEWRITER_H_
