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

#include <stdio.h>
#include <stdlib.h>
#include <sys/types.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <errno.h>

#include <usbhost/usbhost.h>

#include "MtpClient.h"
#include "MtpDebug.h"
#include "MtpDeviceInfo.h"
#include "MtpStorageInfo.h"
#include "MtpStringBuffer.h"

namespace android {

MtpClient::MtpClient(struct usb_endpoint *ep_in, struct usb_endpoint *ep_out,
            struct usb_endpoint *ep_intr)
    :   mEndpointIn(ep_in),
        mEndpointOut(ep_out),
        mEndpointIntr(ep_intr),
        mSessionID(0),
        mTransactionID(0)
{

}

MtpClient::~MtpClient() {
}


bool MtpClient::openSession() {
printf("openSession\n");
    mSessionID = 0;
    mTransactionID = 0;
    MtpSessionID newSession = 1;
    mRequest.reset();
    mRequest.setParameter(1, newSession);
    if (!sendRequest(MTP_OPERATION_OPEN_SESSION))
        return false;
    MtpResponseCode ret = readResponse();
    if (ret == MTP_RESPONSE_SESSION_ALREADY_OPEN)
        newSession = mResponse.getParameter(1);
    else if (ret != MTP_RESPONSE_OK)
        return false;

    mSessionID = newSession;
    mTransactionID = 1;
    return true;
}

bool MtpClient::closeSession() {
    // FIXME
    return true;
}

MtpDeviceInfo* MtpClient::getDeviceInfo() {
    mRequest.reset();
    if (!sendRequest(MTP_OPERATION_GET_DEVICE_INFO))
        return NULL;
    if (!readData())
        return NULL;
    MtpResponseCode ret = readResponse();
printf("getDeviceInfo returned %04X\n", ret);
    if (ret == MTP_RESPONSE_OK) {
        MtpDeviceInfo* info = new MtpDeviceInfo;
        info->read(mData);
        return info;
    }
    return NULL;
}

MtpStorageIDList* MtpClient::getStorageIDs() {
    mRequest.reset();
    if (!sendRequest(MTP_OPERATION_GET_STORAGE_IDS))
        return NULL;
    if (!readData())
        return NULL;
    MtpResponseCode ret = readResponse();
    if (ret == MTP_RESPONSE_OK) {
        return mData.getAUInt32();
    }
    return NULL;
}

MtpStorageInfo* MtpClient::getStorageInfo(MtpStorageID storageID) {
    mRequest.reset();
    mRequest.setParameter(1, storageID);
    if (!sendRequest(MTP_OPERATION_GET_STORAGE_INFO))
        return NULL;
    if (!readData())
        return NULL;
    MtpResponseCode ret = readResponse();
printf("getStorageInfo returned %04X\n", ret);
    if (ret == MTP_RESPONSE_OK) {
        MtpStorageInfo* info = new MtpStorageInfo(storageID);
        info->read(mData);
        return info;
    }
    return NULL;
}

bool MtpClient::sendRequest(MtpOperationCode operation) {
    printf("sendRequest: %s\n", MtpDebug::getOperationCodeName(operation));
    mRequest.setOperationCode(operation);
    if (mTransactionID > 0)
        mRequest.setTransactionID(mTransactionID++);
    int ret = mRequest.write(mEndpointOut);
    mRequest.dump();
    return (ret > 0);
}

bool MtpClient::sendData(MtpOperationCode operation) {
    printf("sendData\n");
    mData.setOperationCode(mRequest.getOperationCode());
    mData.setTransactionID(mRequest.getTransactionID());
    int ret = mData.write(mEndpointOut);
    mData.dump();
    return (ret > 0);
}

bool MtpClient::readData() {
    mData.reset();
    int ret = mData.read(mEndpointIn);
    printf("readData returned %d\n", ret);
    if (ret >= MTP_CONTAINER_HEADER_SIZE) {
        mData.dump();
        return true;
    }
    else {
        printf("readResponse failed\n");
        return false;
    }
}

MtpResponseCode MtpClient::readResponse() {
    printf("readResponse\n");
    int ret = mResponse.read(mEndpointIn);
    if (ret >= MTP_CONTAINER_HEADER_SIZE) {
        mResponse.dump();
        return mResponse.getResponseCode();
    }
    else {
        printf("readResponse failed\n");
        return -1;
    }
}

}  // namespace android
