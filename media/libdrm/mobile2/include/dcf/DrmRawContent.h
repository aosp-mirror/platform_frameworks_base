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

#ifndef __DRM_CONTENT_H__
#define __DRM_CONTENT_H__

#include <Drm2CommonTypes.h>
#include <dcf/DrmDcfContainer.h>

/////////////raw content
class DrmRawContent
{
public:
    /**
     * constructor for DrmRawContent, used to parse DCF
     * \param inRawData  input stream of raw data.
     */
    DrmRawContent(istream& inRawData);

    /** Destructor for DrmRawContent */
    ~DrmRawContent();

    /**
     * get DCF container
     * \param none
     * \return
     *   the DCF container
     */
    vector<DcfContainer*> getContents(void) const;

    /**
     * get the length of DCF hash
     * \param none
     * \return
     *   the length of DCF hash
     */
    uint32_t getDcfHashLen() const;

    /**
     * get DCF hash
     * \param outDcfHash  the buffer to store DCF hash
     * \return
     *   none
     */
    void getDcfHash(uint8_t* outDcfHash) const;

PRIVATE:
    static const uint32_t DCF_HASH_LEN = 20;
    static const uint32_t FIX_HEADER_LEN = 20;
    static const uint32_t MAX_PIECE_LEN = (100 * 1024);

    uint8_t mDcfHash[DCF_HASH_LEN];
    vector<DcfContainer*> mContainer;

PRIVATE:
    bool parseDcfHeader(const uint8_t* dcfHead);
    DrmRawContent(const DrmRawContent& rawContent){}
    DrmRawContent& operator=(const DrmRawContent& other){return *this;}
};

#endif
