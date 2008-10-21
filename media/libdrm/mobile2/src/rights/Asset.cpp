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

#include <rights/Asset.h>

/** see Asset.h */
Asset::Asset()
{}

/** see Asset.h */
Asset::~Asset()
{}

/** see Asset.h */
bool Asset::hasParent()
{
    return false;
}

/** see Asset.h */
void Asset::setID(const string &id)
{
    mAssetID = id;
}

/** see Asset.h */
const string& Asset::getID() const
{
    return mAssetID;
}

/** see Asset.h */
void Asset::setContentID(const string &id)
{
    mContentID = id;
}

/** see Asset.h */
const string& Asset::getContentID() const
{
    return mContentID;
}

/** see Asset.h */
void Asset::setEncryptedKey(const string &key)
{
    mEncryptedKey = key;
}

/** see Asset.h */
void Asset::setDCFDigest(const string &value)
{
    mDigestValue = value;
}

/** see Asset.h */
const string& Asset::getDCFDigest() const
{
    return mDigestValue;
}

/** see Asset.h */
void Asset::setKeyRetrievalMethod(const string &rm)
{
    mRetrievalMethod = rm;
}

/** see Asset.h */
void Asset::setParentContentID(const string &id)
{
    mParentContentID = id;
}

/** see Asset.h */
const string& Asset::getEncrytedKey() const
{
    return mEncryptedKey;
}

/** see Asset.h */
const char* Asset::getCek() const
{
    return NULL;
}

/** see Asset.h */
void Asset::recoverCek()
{
//fix later.

}

/** see Asset.h */
const string& Asset::getParentContentID() const
{
    return mParentContentID;
}
