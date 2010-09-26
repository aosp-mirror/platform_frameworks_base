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

#ifndef _MTP_STRING_BUFFER_H
#define _MTP_STRING_BUFFER_H

#include <stdint.h>

namespace android {

class MtpDataPacket;

// Represents a utf8 string, with a maximum of 255 characters
class MtpStringBuffer {

private:
    // mBuffer contains string in UTF8 format
    // maximum 3 bytes/character, with 1 extra for zero termination
    uint8_t         mBuffer[255 * 3 + 1];
    int             mCharCount;
    int             mByteCount;

public:
                    MtpStringBuffer();
                    MtpStringBuffer(const char* src);
                    MtpStringBuffer(const uint16_t* src);
                    MtpStringBuffer(const MtpStringBuffer& src);
    virtual         ~MtpStringBuffer();

    void            set(const char* src);
    void            set(const uint16_t* src);

    void            readFromPacket(MtpDataPacket* packet);
    void            writeToPacket(MtpDataPacket* packet) const;

    inline int      getCharCount() const { return mCharCount; }
    inline int      getByteCount() const { return mByteCount; }

	inline operator const char*() const { return (const char *)mBuffer; }
};

}; // namespace android

#endif // _MTP_STRING_BUFFER_H
