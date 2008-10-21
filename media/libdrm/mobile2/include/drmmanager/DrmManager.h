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
#ifndef _DRMMANAGER_
#define _DRMMANAGER_

#include <Drm2CommonTypes.h>
#include <ofstream.h>
#include <sostream.h>
#include <ustring.h>
#include <sistream.h>
#include <uvector.h>

using namespace ustl;

/** It should be removed after real DcfContainer is ready */
#define DcfContainer string
static const int16_t DRM_OK = 1;

class DrmManager
{
public:
    /**
     * Error definition
     */
     static const int16_t DRM_MANAGER_BASE = 100;
     static const int16_t ERR_DCFSTREAM_NOT_INITIALIZED = DRM_MANAGER_BASE+1;
     static const int16_t ERR_WRONG_DCFDATA = DRM_MANAGER_BASE+2;
     static const int16_t ERR_WRONG_RIGHTS = DRM_MANAGER_BASE+3;

    /**
     * Constructor for DrmManager,used to open local dcf file.
     * @param inRawData input stream of raw data.
     */
    DrmManager(istream *inRawData);

    /**
     * Constructor for DrmManager,used to separate dcf file and trig message when upper
     * application downloading one multipart message from CI.
     * @param inRawData input stream of raw data.
     */
    DrmManager(istream * inRawData, string mimeType);

    /** Destructor for DomExpatAgent. */
    ~DrmManager();
    /**
     * Config DRM engine
     * Fix me later
     */
     bool config();

    /**
     * Consume rights according to specified operation, DrmManager will check.
     * @param operationType the type of operation.
     * @return the operation result.
     */
    int16_t consumeRights(int16_t operationType);

    /**
     * Get the list of all dcf containers object reference in the dcf file.
     * @param the vector of the dcf objects list returned.
     * @return the operation result.
     */
     int16_t getListOfDcfObjects(vector<DcfContainer*> **outDcfList);

    /**
     * Open one Dcf container to read the decrypted data according to specified
     * operation.
     * @param oneDcfObject the reference of the DcfContainer.
     * @param operationType the type of operation.
     * @param decrypted data returned.
     * @return the operation result.
     */
    int16_t openDecryptedContent(DcfContainer *oneDcfObject,
                                int16_t operationType,
                                istream *outDecryptedData);

    /**
     * Get the separated Dcf raw data from multipart message.
     * @return the ifstream of the dcf raw data which should be stored by upper
     * application.
     */
    ifstream* getOriginalMediaData(void);

    /**
     * Handle DRM2.0 push message
     */
    bool handlePushMsg(uint8_t* data, string mimeType);

PRIVATE:
    istream *mDcfStream; /**< the handler of dcf stream. */
    vector<DcfContainer*> mDcfs; /**< all the dcf containers included in one dcf file. */
};

#endif //_DRMMANAGER_
