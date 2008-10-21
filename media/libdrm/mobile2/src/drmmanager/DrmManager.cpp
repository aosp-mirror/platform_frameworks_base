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
#include <drmmanager/DrmManager.h>
#include <ustring.h>
#include <ofstream.h>
#include <sostream.h>
#include <sistream.h>
using namespace ustl;

/**see DrmManager.h */
DrmManager::DrmManager(istream * inRawData)
{
    mDcfStream = NULL;
    if (inRawData != NULL)
    {
        mDcfStream = inRawData;
    }
}

/**see DrmManager.h */
DrmManager::DrmManager(istream * inRawData, string mimeType)
{
    mDcfStream = inRawData;
}

/**see DrmManager.h */
int16_t DrmManager::getListOfDcfObjects(vector<DcfContainer*> **outDcfList)
{
    /** call dcf functions to parse the dcf file*/
    if (NULL == mDcfStream)
    {
        return ERR_DCFSTREAM_NOT_INITIALIZED;
    }
    if (NULL == outDcfList)
    {
        return ERR_DCFSTREAM_NOT_INITIALIZED;
    }
    *outDcfList=&mDcfs;
    return DRM_OK;
}

/**see DrmManager.h */
int16_t DrmManager::openDecryptedContent(DcfContainer *oneDcfObject,
                                         int16_t operationType,
                                         istream *outDecryptedData)
{
    return 1;
}

/**see DrmManager.h */
DrmManager::~DrmManager()
{

}
