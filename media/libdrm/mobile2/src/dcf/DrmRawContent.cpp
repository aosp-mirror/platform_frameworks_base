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

#include <dcf/DrmRawContent.h>


DrmRawContent::DrmRawContent(istream& inRawData)
{
    uint32_t count = inRawData.stream_size();

    if (count <= MAX_PIECE_LEN)
    {
        uint8_t* data = new uint8_t[count];

        if(!data)
        {
            return;
        }

        inRawData.read(data,count);

        const uint8_t* dcf = data;

        //parse DCF file header
        if(false == parseDcfHeader(dcf))
        {
            delete []data;
            return;
        }

        dcf = data;
        dcf += FIX_HEADER_LEN;

        if(dcf >= (data + count))
        {
            return;
        }

        // parse container box
        FullBox conFullBox(dcf);

        if(DCF_CONTAINER_BOX != conFullBox.getType())
        {
            return;
        }

        //check whether it is multipart DCF or not
        do
        {
            uint64_t size = conFullBox.getSize();

            mContainer.push_back(new DcfContainer(dcf,inRawData,dcf-data));

            dcf += size;

            // come to the end of raw content
            if(dcf >= (data + count))
            {
                break;
            }

            conFullBox = FullBox(dcf);
        }while(DCF_CONTAINER_BOX == conFullBox.getType());

        // compute DCF hash using Sha1Agent
        Sha1Agent drmSha1Hash;
        drmSha1Hash.computeHash(data,dcf-data,mDcfHash);

        //// parse mutable box

        delete []data;
    }
}

DrmRawContent::~DrmRawContent()
{
    uint32_t size = mContainer.size();

    for(uint32_t i = 0; i < size; i++)
    {
        delete mContainer[i];
    }

    mContainer.clear();
}

vector<DcfContainer*> DrmRawContent::getContents(void) const
{
    return mContainer;
}

uint32_t DrmRawContent::getDcfHashLen() const
{
    return DCF_HASH_LEN;
}

void DrmRawContent::getDcfHash(uint8_t* outDcfHash) const
{
    if(outDcfHash)
    {
        memcpy(outDcfHash,mDcfHash,DCF_HASH_LEN);
    }

    return;
}

bool DrmRawContent::parseDcfHeader(const uint8_t* dcfHead)
{
    if(!dcfHead)
    {
        return false;
    }

    if(FIX_HEADER_LEN != ntohl(*(uint32_t *)dcfHead))
    {
        return false;
    }

    dcfHead += 4;
    uint32_t type = *(uint32_t *)dcfHead;

    if(DCF_FILE_TYPE != type)
    {
        return false;
    }

    dcfHead += 4;
    type = *(uint32_t *)dcfHead;

    if(DCF_FILE_BRAND != type)
    {
        return false;
    }

    dcfHead += 4;
    if(2 != ntohl(*(uint32_t *)dcfHead))
    {
        return false;
    }

    dcfHead += 4;
    type = *(uint32_t *)dcfHead;

    if(DCF_FILE_BRAND != type)
    {
        return false;
    }

    dcfHead += 4;
    return true;
}
