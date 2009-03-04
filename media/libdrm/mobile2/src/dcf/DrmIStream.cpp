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

#include <dcf/DrmIStream.h>


DrmInStream::DrmInStream(const DcfContainer* container,uint8_t* Key)
:mDcfCon(container),mDecryptPos(0)
{
    memcpy(mAesKey,Key,AES_KEY_LEN);
}

uint64_t DrmInStream::size() const
{
    return mDcfCon->mPlaintextLength;
}

uint64_t DrmInStream::read(uint8_t* data, uint64_t len)
{
    if(!data)
    {
        return 0;
    }

    if(mDecryptPos >= mDcfCon->mPlaintextLength)
    {
        return 0;
    }

    uint64_t readLen = len;

    // come to the end of decrypted data
    if(mDecryptPos + len > mDcfCon->mPlaintextLength)
    {
        readLen = mDcfCon->mPlaintextLength - mDecryptPos;
    }

    uint64_t encLen = mDcfCon->mDataLen;
    uint8_t* encData = new uint8_t[encLen];

    if(!encData)
    {
        return 0;
    }

    mDcfCon->mConStream.seek(mDcfCon->mDecOffset);
    mDcfCon->mConStream.read(encData,encLen);

    uint8_t iv[AES_IV_LEN] = {0};

    memcpy(iv,encData,AES_IV_LEN);
    encLen -= AES_IV_LEN;

    if(AES_128_CBC != mDcfCon->mEncryptionMethod)
    {
        delete []encData;
        return 0;
    }

    AesAgent drmAesDecrypt(AES_128_CBC,mAesKey);
    int32_t padLen = drmAesDecrypt.decContent( iv,
                                               encData + AES_IV_LEN,
                                               encLen,
                                               data);

    delete []encData;

    if(padLen >= 0)
    {
        return readLen;
    }
    else
    {
        return 0;
    }
}





