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

#include <dcf/DrmDcfContainer.h>

DcfContainer::DcfContainer(const uint8_t* data,istream& inRawData,uint64_t conOff)
                : FullBox(data),mConStream(inRawData)
{
    if(!data)
    {
        return;
    }

    const uint8_t* p = data;
    const uint8_t* flag = this->getFlag();

    if(flag[0] & USER_DATA_FLAG)
    {
        mHasUserData = true;
    }
    else
    {
        mHasUserData = false;
    }

    p += this->getLen();

    FullBox fullBoxDiscrete(p);

    p += fullBoxDiscrete.getLen();

    mContentTypeLen = *p;
    p++;

    mContentType.assign((const char*)p,0,mContentTypeLen);
    p += mContentTypeLen;

    // parse common header
    FullBox fullBoxComm(p);
    p += fullBoxComm.getLen();

    mEncryptionMethod = *p;
    p++;

    mPaddingScheme = *p;
    p++;

    mPlaintextLength = ntoh_int64(*((uint64_t *)p));
    p += sizeof(mPlaintextLength);

    mContentIDLength = ntohs(*(uint16_t *)p);
    p += sizeof(mContentIDLength);

    mRightsIssuerURLLength = ntohs(*(uint16_t *)p);
    p += sizeof(mRightsIssuerURLLength);

    mTextualHeadersLength = ntohs(*(uint16_t *)p);
    p += sizeof(mTextualHeadersLength);

    mContentID.assign((const char *)p,0,mContentIDLength);
    p += mContentIDLength;

    mRightsIssuerURL.assign((const char *)p,0,mRightsIssuerURLLength);
    p += mRightsIssuerURLLength;

    // parse textual header
    if (mTextualHeadersLength > 0)
    {
        if(false == parseTextualHeaders(p,mTextualHeadersLength))
        {
            return;
        }

        p += mTextualHeadersLength;
    }

    ////////////// parser group id

    ///parse content
    p = data + this->getLen() + fullBoxDiscrete.getSize();
    FullBox fullBoxContetn(p);
    p += fullBoxContetn.getLen();
    mDataLen = ntoh_int64(*((uint64_t *)p));
    p += sizeof(mDataLen);

    mDecOffset = conOff + (p - data);
    p += mDataLen;

    /////////////// parser user data
}

DcfContainer::~DcfContainer()
{
    uint32_t size = mTextualHeaders.size();

    for(uint32_t i = 0; i < size; i++)
    {
        delete mTextualHeaders[i];
    }

    mTextualHeaders.clear();
    mCustomHeader.clear();
}


string DcfContainer::getContentType(void) const
{
    return mContentType;
}

uint8_t DcfContainer::getEncryptionMethod(void) const
{
    return mEncryptionMethod;
}

uint8_t DcfContainer::getPaddingScheme(void) const
{
    return mPaddingScheme;
}

uint64_t DcfContainer::getPlaintextLength(void) const
{
    return mPlaintextLength;
}

uint16_t DcfContainer::getContentIDLength(void) const
{
    return mContentIDLength;
}

uint16_t DcfContainer::getRightsIssuerURLLength(void) const
{
    return mRightsIssuerURLLength;
}

uint16_t DcfContainer::getTextualHeadersLength(void) const
{
    return mTextualHeadersLength;
}

string DcfContainer::getContentID(void) const
{
    return mContentID;
}

string DcfContainer::getRightsIssuerURL(void) const
{
    return mRightsIssuerURL;
}

string DcfContainer::getPreviewMethod(void) const
{
    return mSlientMethod;
}

string DcfContainer::getContentLocation(void) const
{
    return mContentLocation;
}

string DcfContainer::getContentURL(void) const
{
    return mContentURL;
}

vector<string> DcfContainer::getCustomerHead(void) const
{
    return mCustomHeader;
}

istream& DcfContainer::getStream(void) const
{
    return mConStream;
}

DrmInStream DcfContainer::getPreviewElementData(void) const
{
     // get data based on mPreviewElementURI
     //encryptedData = ;

     DrmInStream inStream;
     return inStream;
}

DrmInStream DcfContainer::getDecryptContent(uint8_t* decryptKey) const
{
    DrmInStream inStream(this,decryptKey);
    return inStream;
}

bool DcfContainer::parseTextualHeaders(const uint8_t* data, uint32_t len)
{
    if(!data)
    {
        return false;
    }

    const uint8_t* p = data;

    while (len > (uint32_t)(p - data))
    {
        uint32_t l = strlen((const char*)p);

        string str((const char*)p, l);
        TextualHeader* tmp = new TextualHeader(str);

        if(!tmp)
        {
            return false;
        }

        mTextualHeaders.push_back(tmp);

        p += l + 1;
    }

    uint32_t size = mTextualHeaders.size();
    uint32_t silentpos = 0;
    uint32_t previewpos = 0;

    for( uint32_t i = 0; i < size; i++)
    {
        string tempStr = mTextualHeaders[i]->getName();

        if(tempStr == "Silent")
        {
            silentpos = i;
            mSlientMethod = mTextualHeaders[i]->getValue();
            mSilentRightsURL = mTextualHeaders[i]->getParam();
        }
        else if(tempStr == "Preview")
        {
            previewpos = i;
            mPreviewMethod = mTextualHeaders[i]->getValue();

            if(mPreviewMethod == "instant")
            {
                mPreviewElementURI = mTextualHeaders[i]->getParam();
            }
            else
            {
                mPreviewRightsURL = mTextualHeaders[i]->getParam();
            }
        }
        else if(tempStr == "ContentURL")
        {
            mContentURL = mTextualHeaders[i]->getValue();
        }
        else if(tempStr == "ContentVersion")
        {
            mContentVersion = mTextualHeaders[i]->getValue();
        }
        if(tempStr == "Content-Location")
        {
            mContentLocation = mTextualHeaders[i]->getValue();
        }
        else
        {
            string str = mTextualHeaders[i]->getName();
            str += ":";
            str += mTextualHeaders[i]->getValue();
            mCustomHeader.push_back(str);
        }
    }

    if(silentpos < previewpos)
    {
        mSilentFirst = true;
    }
    else
    {
        mSilentFirst = false;
    }

    return true;
}





