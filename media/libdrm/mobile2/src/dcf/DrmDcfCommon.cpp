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

#include <dcf/DrmDcfCommon.h>

int64_t ntoh_int64(int64_t x)
{
    return (((int64_t)(ntohl((int32_t)((x << 32) >> 32))) << 32) | (uint32_t)ntohl(((int32_t)(x >> 32))));
}

/**
 * Class: Box
 */
Box::Box(const uint8_t* box):mLargeSize(0),mUserType(NULL)
{
    if(!box)
    {
        return ;
    }

    const uint8_t* p = box;

    /* Get the size value */
    mSize = ntohl(*(uint32_t *)p);
    p += sizeof(mSize);

    /* Get the type value */
    mType = *((uint32_t *)p);
    p += sizeof(mType);

    if (1 == mSize)
    {
        mLargeSize = ntoh_int64(*(uint64_t *)p);
        p += sizeof(mLargeSize);
    }

    if (DCF_USER_TYPE == mType)
    {
        mUserType = new uint8_t[USER_TYPE_LEN];
        memcpy(mUserType, p, USER_TYPE_LEN);
        p += USER_TYPE_LEN;
    }

    mBoxLength = p - box;
}

Box::Box(const Box& other)
{
    mSize = other.mSize;
    mType = other.mType;
    mLargeSize = other.mLargeSize;
    mUserType = NULL;

    if(other.mUserType)
    {
        mUserType = new uint8_t[USER_TYPE_LEN];
        memcpy(mUserType,other.mUserType,USER_TYPE_LEN);
    }
}

Box& Box::operator=(const Box& other)
{
    if(this == &other)
    {
        return *this;
    }

    if(mUserType)
    {
        delete []mUserType;
        mUserType = NULL;
    }

    if(other.mUserType)
    {
        mUserType = new uint8_t[USER_TYPE_LEN];
        memcpy(mUserType, other.mUserType, USER_TYPE_LEN);
    }

    return *this;
}

Box::~Box()
{
    if(mUserType)
    {
        delete []mUserType;
        mUserType = NULL;
    }
}

uint64_t Box::getSize(void) const
{
    if(1 == mSize)
    {
        return mLargeSize;
    }

    return mSize;
}

uint32_t Box::getType(void) const
{
    return mType;
}

const uint8_t* Box::getUsertype(void) const
{
    return mUserType;
}

uint32_t Box::getLen(void) const
{
    return mBoxLength;
}


/**
 * Class: FullBox
 */
FullBox::FullBox(const uint8_t* fullBox) : Box(fullBox)
{
    if(!fullBox)
    {
        return ;
    }

    const uint8_t* p = fullBox;

    p += Box::getLen();

    mVersion = *p;
    p++;

    memcpy(mFlag, p,FLAG_LEN);
    p += FLAG_LEN;

    mFullBoxLength = p - fullBox;
}

uint8_t FullBox::getVersion(void) const
{
    return mVersion;
}

const uint8_t* FullBox::getFlag(void) const
{
    return mFlag;
}

uint32_t FullBox::getLen(void) const
{
    return mFullBoxLength;
}

///// class TextualHeader implementation
TextualHeader::TextualHeader(const string& inData)
{
    string::size_type loc1 = inData.find(":", 0);

    if (loc1 != string::npos)
    {
        name.assign(inData, 0, loc1);
    }

    string::size_type loc2 = inData.find(";", loc1 + 1);

    if (loc2 != string::npos)
    {
        value.assign(inData, loc1 + 1, loc2 - loc1 - 1);
        param.assign(inData, loc2 + 1, inData.length() - loc2 - 1);
    }
    else
    {
        value.assign(inData, loc1 + 1, inData.length() - loc1 - 1);
    }
}

string TextualHeader::getName() const
{
    return name;
}

string TextualHeader::getValue() const
{
    return value;
}

string TextualHeader::getParam() const
{
    return param;
}

