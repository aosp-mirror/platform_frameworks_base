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

#ifndef _RO_H_
#define _RO_H_

#include <rights/Asset.h>
#include <rights/Right.h>
#include <uvector.h>
#include <ustring.h>
#include <sistream.h>
using namespace ustl;

class Asset;
class XMLDocumentImpl;
class XMLElementImpl;
class NodeImpl;

class Ro {
public:
    enum ERRCODE { RO_NULL_STREAM, RO_ERR_BAD_XML, RO_OK, RO_BAD };

    /**
     * Constructor for Ro.
     */
    Ro();

    /**
     * Destructor for Ro.
     */
    ~Ro();

    /**
     * Set id for Ro.
     * @param id the id of Ro.
     */
    void setRoID(string &id);

    /**
     * Get the id of Ro.
     * @return the id of Ro.
     */
    const string& getRoID() const;

    /**
     * Set version for Ro.
     */
    void setRoVersion(string &version);

    /**
     * Add a asset into ro's asset list.
     * @param asset the pointer of asset.
     */
    void addAsset(Asset* asset);

    /**
     * Add a right into ro's right list.
     * @param right the pointer of right.
     */
    void addRight(Right* right);

    /**
     * Save the Ro.
     */
    bool save();

    /**
     * Verify the Ro.
     */
    bool verify();

    /**
     * Parse the ro from stream.
     * @param roStream the input ro stream.
     * @return RO_OK if parse successfully otherwise return error code.
     */
    ERRCODE parse(istringstream *roStream);

    /**
     * Check the permission of the content.
     * @param type the operation type.
     * @param contentID the specific contentID.
     * @return true/false to indicate result.
     */
    bool checkPermission(OperationPermission::OPERATION type,
                         const string& contentID);

    /**
     * Consume the right related to content.
     * @param type the operation type.
     * @param contentID the specific contentID.
     * @return the status of consume.
     */
    ERRCODE consume(OperationPermission::OPERATION type,
                    const string& contentID);

    /**
     * Get CEK of content.
     * @param contentID the specific content id.
     * @return "" if not found otherwise return CEK.
     */
    string getContentCek(const string& contentID);

    /**
     * Get Digest value of content.
     * @param contentID the specific content id.
     * @return "" if not found otherwise return digest value.
     */
    string getContentHash(const string& contentID);

PRIVATE:
    /**
     * Handle the xml dom document.
     * @param doc the pointer to the dom document.
     * @return true/false to indicate the result.
     */
    bool handleDocument(const XMLDocumentImpl* doc);

    /**
     * Handle the xml dom node which contains <right> element.
     * @param curNode the dom node which contains <right> element.
     * @return true/false to indicate the result.
     */
    bool handleRights(const NodeImpl *curNode);

    /**
     * Handle the xml dom node which contains the <agreement> element.
     * @param curNode the dom node which contains <agreement> element.
     * @return true/false to indicate the result.
     */
    bool handleAgreement(const NodeImpl *curNode);

    /**
     * Handle the xml dom node which contains the <asset> element.
     * @param curNode the dom node which contains <asset> element.
     * @return true/false to indicate the result.
     */
    bool handleAsset(const NodeImpl *curNode);

    /**
     * Handle the xml dom node which contains the <permission> element.
     * @param curNode the dom node which contains <permission> element.
     * @return true/false to indicate the result.
     */
    bool handlePermission(const NodeImpl *curNode);

    /**
     * Get the constraint in xml dom node.
     * @param curNode the dom node which contains constraint.
     * @return the constraint.
     */
    Constraint* getConstraint(const NodeImpl *curNode);

    /**
     * Convert ISO8601 time to long.
     * @param ts the string with ISO8601 time.
     * @return the result value.
     */
    long convertISO8601DateTimeToLong(const char* ts);

    /**
     * Convert ISO8601 period to long.
     * @param ts the string with ISO8601 period.
     * @return the result value.
     */
    long convertISO8601PeriodToLong(const char* ts);

    /**
     * Load the rights related with specific contentinto content rights list.
     * @param contentID the specific content id.
     */
    void loadRights(const string& contentID);

    /**
     * Free the current content rights list.
     */
    void freeRights();

PRIVATE:
    /**
     * Disable the assignment between rights.
     */
    Ro& operator=(const Ro& ro);

    /**
     * Disable copy constructor.
     */
    Ro(const Ro& ro);

public:
    vector<Asset*> mAssetList;
    vector<Right*> mRightList;

PRIVATE:
    string mRoID; /** the Ro id. */
    string mRoVersion; /** the Ro version. */
    XMLDocumentImpl *mDoc; /**< the xml document handle. */
    vector<Right*> mContentRightList; /**< the right list to store the result related with specific content. */
    Right* mProperRight; /**< the right to consume. */
};
#endif
