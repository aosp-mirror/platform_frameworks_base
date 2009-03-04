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
#ifndef _ASSET_H
#define _ASSET_H

#include <ustring.h>
#include <uvector.h>
#include <Drm2CommonTypes.h>
#include <rights/Right.h>
using namespace ustl;

class Asset {
public:
    /**
     * Constructor for asset.
     */
    Asset();

    /**
     * Destructor for asset.
     */
    ~Asset();

    /**
     * Test whether asset has parent or not.
     * @return true/false to indicate the result.
     */
    bool hasParent();

    /**
     * Set id of asset.
     * @param id the id of asset.
     */
    void setID(const string &id);

    /**
     * Get the id of content.
     * @return asset id.
     */
    const string& getID() const;

    /**
     * Set contend id related to asset.
     * @param id the id of content.
     */
    void setContentID(const string &id);

    /**
     * Get content id.
     * @return content id.
     */
    const string& getContentID() const;

    /**
     * Set digest value of DCF.
     * @param value the DCF digest value.
     */
    void setDCFDigest(const string &value);

    /**
     * Get the DCF digest value.
     * @return the digest value of DCF.
     */
    const string& getDCFDigest() const;

    /**
     * Set encrypted key in asset.
     * @param the encrypted key.
     */
    void setEncryptedKey(const string &key);

    /**
     * Get encrypted key.
     * @return encypted key.
     */
    const string& getEncrytedKey() const;

    /**
     * Get cek.
     * @return cek.
     */
    const char* getCek() const;

    /**
     * Set the retrieval method of key.
     * @param rm the retrieval method of the key.
     */
    void setKeyRetrievalMethod(const string &rm);

    /**
     * Set parent content id for asset.
     * @param id the parent content id.
     */
    void setParentContentID(const string &id);

    /**
     * Get the parent content id of the asset.
     * @return the parent content id.
     */
    const string& getParentContentID() const;

    /**
     * Recover the CEK using private key.
     */
    void recoverCek();

PRIVATE:
    string mAssetID;
    string mContentID;
    string mDigestMethod;
    string mDigestValue;
    string mEncryptedMethod;
    string mEncryptedKey;
    string mRetrievalMethod;
    string mParentContentID;
    string mCek;
};

#endif
