/*
 * Copyright (C) 2007 The Android Open Source Project
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

#ifndef __DRM_ISTREAM_H__
#define __DRM_ISTREAM_H__

#include <Drm2CommonTypes.h>
#include <util/crypto/DrmCrypto.h>
#include <dcf/DrmDcfContainer.h>
#include <ustring.h>

using namespace ustl;

class DcfContainer;

class DrmInStream
{
public:
    /** default constructor of DrmInStream */
    DrmInStream():mDecryptPos(0){}

    /**
     * constructor for DrmInStream, used to read DCF content
     * \param encFile  DCF container data
     * \param len   DCF container data len
     * \param off   the offset from the start of DCF container
     */
    DrmInStream(const DcfContainer* container,uint8_t* Key);

    /**
     * get the size of DRM Instream
     * \param none
     * \return
     *   the size of DRM Instream
     */
    uint64_t size() const;

    /**
     * read data from DRM Instream
     * \param  data  the buffer to store read data
     * \param  len   how much data need to read
     * \return
     *   the actual len of read data
     */
    uint64_t read(uint8_t* data,uint64_t len);

PRIVATE:
    static const uint32_t AES_IV_LEN = 16;
    static const uint32_t AES_KEY_LEN = 16;
    static const uint32_t AES_BLOCK_LEN = 16;

    const DcfContainer* mDcfCon;
    uint64_t mDecryptPos;
    uint8_t mAesKey[AES_KEY_LEN];
};



#endif





