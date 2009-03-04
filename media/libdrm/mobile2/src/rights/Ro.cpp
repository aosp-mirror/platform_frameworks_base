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

#include <rights/Ro.h>
#include <rights/Constraint.h>
#include <rights/OperationPermission.h>
#include <util/xml/DomExpatAgent.h>
#include <util/domcore/DOMString.h>
#include <utils/Log.h>

#include <uassert.h>
#include <time.h>
#include <ofstream.h>
using namespace ustl;

const char *STR_RO_RIGHTS = "o-ex:rights";
const char *STR_RO_CONTEXT = "o-ex:context";
const char *STR_RO_AGREEMENT = "o-ex:agreement";
const char *STR_RO_ASSET = "o-ex:asset";
const char *STR_RO_INHERIT = "o-ex:inherit";
const char *STR_RO_DIGEST = "o-ex:digest";
const char *STR_RO_KEYINFO = "ds:KeyInfo";
const char *STR_RO_PERMISSION = "o-ex:permission";
const char *STR_RO_ASSET_ID = "o-ex:id";
const char *STR_RO_ASSET_IDREF = "o-ex:idref";
const char *STR_RO_CONTEXT_ID = "o-dd:uid";
const char *STR_RO_CONTEXT_VERSION = "o-dd:version";
const char *STR_RO_DIGEST_VALUE = "ds:DigestValue";
const char *STR_RO_CIPHER_VALUE = "xenc:CipherValue";
const char *STR_RO_RETRIEVAL_METHOD = "ds:RetrievalMethod";
const char *STR_RO_PLAY = "o-dd:play";
const char *STR_RO_DISPLAY = "o-dd:display";
const char *STR_RO_EXECUTE = "o-dd:execute";
const char *STR_RO_PRINT = "o-dd:print";
const char *STR_RO_EXPORT = "o-dd:export";
const char *STR_RO_CONSTRAINT = "o-ex:constraint";
const char *STR_RO_COUNT = "o-dd:count";
const char *STR_RO_TIMEDCOUNT = "o-dd:timed-count";
const char *STR_RO_TIMER = "oma-dd:timer";
const char *STR_RO_INTERVAL = "o-dd:interval";
const char *STR_RO_DATETIME = "o-dd:datetime";
const char *STR_RO_START = "o-dd:start";
const char *STR_RO_END = "o-dd:end";
const char *STR_RO_ACCUMULATED = "o-dd:accumulated";
const char *STR_RO_INDIVIDUAL = "o-dd:individual";
const char *STR_RO_SYSTEM = "o-dd:system";

/** see Ro.h */
Ro::Ro()
{
    mDoc = new XMLDocumentImpl();
    mProperRight = NULL;
}

/** see Ro.h */
Ro::~Ro()
{
     for (vector<Right*>::iterator itr = mRightList.begin(); itr != mRightList.end(); itr++)
     {
        delete(*itr);
     }

     mRightList.clear();

     for (vector<Asset*>::iterator ita = mAssetList.begin(); ita != mAssetList.end(); ita++)
     {
        delete(*ita);
     }

     mAssetList.clear();

     mProperRight = NULL;
     delete mDoc;

}

/** see Ro.h */
void Ro::setRoID(string& id)
{
    mRoID = id;
}

/** see Ro.h */
const string& Ro::getRoID() const
{
    return mRoID;
}

/** see Ro.h */
void Ro::setRoVersion(string& version)
{
    mRoVersion = version;
}

/** see Ro.h */
void Ro::addAsset(Asset* asset)
{
    mAssetList.push_back(asset);
}

/** see Ro.h */
void Ro::addRight(Right* right)
{
    mRightList.push_back(right);
}

/** see Ro.h */
bool Ro::save()
{
     LOGI("==============Ro save.=================");

     return true;
}

/** see Ro.h */
Ro::ERRCODE Ro::parse(istringstream *roStream)
{
    DomExpatAgent xmlAgent(mDoc);

    if (NULL == roStream)
    {
        LOGI("NULL stream");
        return RO_NULL_STREAM;
    }

    if (xmlAgent.generateDocumentFromXML(roStream) == false)
    {
        LOGI("generate xml doc error");
        return RO_ERR_BAD_XML;
    }

    handleDocument(mDoc);

    return RO_OK;
}

/** see Ro.h */
bool Ro::handleDocument(const XMLDocumentImpl* doc)
{
    assert(doc != NULL);

    NodeImpl* node = doc->getDocumentElement();

    return handleRights(node);
}

/** see Ro.h */
bool Ro::handleRights(const NodeImpl *curNode)
{
    assert(curNode != NULL);

    NodeImpl *node = curNode->getFirstChild();

    while (NULL != node)
    {
        const DOMString* name;

        name = static_cast<const XMLElementImpl*>(node)->getTagName();

        if (name->compare(STR_RO_CONTEXT) == 0)
        {
            LOGI("rights context");
            const DOMString *token = NULL;
            token = static_cast<const XMLElementImpl*>(node)->getSoloText(STR_RO_CONTEXT_ID);

            if (token)
            {
                LOGI(*token);
                mRoID = *token;
            }

            token = static_cast<const XMLElementImpl*>(node)->getSoloText(STR_RO_CONTEXT_VERSION);
            if (token)
            {
                LOGI(*token);
                mRoVersion = *token;
            }
        }

        if (name->compare(STR_RO_AGREEMENT) == 0)
        {

            LOGI("rights agreement");
            if (handleAgreement(node) == false)
            {
                return false;
            }
        }

        node = node->getNextSibling();
    }
    return true;
}

/** see Ro.h */
bool Ro::handleAgreement(const NodeImpl *curNode)
{
    assert(curNode != NULL);

    NodeImpl *node = curNode->getFirstChild();

    while (NULL != node)
    {
        const DOMString* name;

        name = static_cast<const XMLElementImpl*>(node)->getTagName();

        if (name->compare(STR_RO_ASSET) == 0)
        {
            // do something about asset.
            LOGI("asset");

            if (handleAsset(node) == false)
            {
                return false;
            }
        }

        if (name->compare(STR_RO_PERMISSION) == 0)
        {
            // do something about permission.
            LOGI("permission");

            if (handlePermission(node) == false)
            {
                return false;
            }
        }

        node = node->getNextSibling();
    }

    return true;
}

/** see Ro.h */
bool Ro::handleAsset(const NodeImpl *curNode)
{
    assert(curNode != NULL);

    Asset *asset = new Asset();

    const XMLElementImpl *curElement = static_cast<const XMLElementImpl*>(curNode);

    if (curElement->hasAttributes())
    {
        DOMString assetID(STR_RO_ASSET_ID);
        LOGI("asset id:");

        const DOMString *id = curElement->getAttribute(&assetID);

        if (id)
        {
            asset->setID(*id);
        }

    }

    NodeImpl* node = curNode->getFirstChild();

    const DOMString *name = NULL;
    const string *token = NULL;

    while (NULL != node)
    {
        curElement = static_cast<const XMLElementImpl*>(node);
        name = curElement->getTagName();

        if (name->compare(STR_RO_CONTEXT) == 0 ||
            name->compare(STR_RO_INHERIT) == 0)
        {
            LOGI("asset context");

            token = curElement->getSoloText(STR_RO_CONTEXT_ID);
            if (token)
            {
                LOGI(*token);

                if (name->compare(STR_RO_CONTEXT) == 0)
                {
                    asset->setContentID(*token);
                }
                else
                {
                    //parent ID.
                    asset->setParentContentID(*token);
                }
            }
        }

        if (name->compare(STR_RO_DIGEST) == 0)
        {
            LOGI("asset digest");
            //digest method is fixed value:
            //http://www.w3.org/2000/09/xmldisig#sha1
            token = curElement->getSoloText(STR_RO_DIGEST_VALUE);
            if (token)
            {
                LOGI(*token);
                asset->setDCFDigest(*token);
            }
        }

        if (name->compare(STR_RO_KEYINFO) == 0)
        {
            LOGI("asset keyinfo");

            token = curElement->getSoloText(STR_RO_CIPHER_VALUE);
            if (token)
            {
                LOGI(*token);
                asset->setEncryptedKey(*token);
            }

            const XMLElementImpl *node = curElement->getSoloElement(STR_RO_RETRIEVAL_METHOD);

            if (node)
            {
                if (node->hasAttributes())
                {
                    DOMString uri("URI");
                    token = node->getAttribute(&uri);
                    if (token)
                    {
                        LOGI(*token);
                        asset->setKeyRetrievalMethod(*token);
                    }
                }
            }
        }

        node = node->getNextSibling();
    }

    this->addAsset(asset);
    return true;
}

/** see Ro.h */
bool Ro::handlePermission(const NodeImpl *curNode)
{
    assert(curNode != NULL);

    Right *right = new Right();

    const XMLElementImpl *curElement = static_cast<const XMLElementImpl*>(curNode);

    NodeImpl* node = curNode->getFirstChild();

    while (NULL != node)
    {
        const DOMString *name = NULL;
        NodeListImpl *nodeList = NULL;

        const string *token = NULL;
        curElement = static_cast<const XMLElementImpl*>(node);
        name = curElement->getTagName();

        if (name->compare(STR_RO_ASSET) == 0)
        {
            LOGI("permission asset");
            if (curElement->hasAttributes())
            {
                DOMString assetID(STR_RO_ASSET_IDREF);
                const DOMString *id = curElement->getAttribute(&assetID);
                if (id)
                {
                    right->addAssetID(*id);
                    LOGI(*id);
                }
            }
        }

        OperationPermission::OPERATION type = OperationPermission::NONE;

        if (name->compare(STR_RO_PLAY) == 0)
        {
            LOGI("permission play constraint");
            type = OperationPermission::PLAY;
        }

        if (name->compare(STR_RO_DISPLAY) == 0)
        {
            LOGI("permission display costraint");
            type = OperationPermission::DISPLAY;
        }

        if (name->compare(STR_RO_EXECUTE) == 0)
        {
            LOGI("permission execute constraint");
            type = OperationPermission::EXECUTE;
        }

        if (name->compare(STR_RO_EXPORT) == 0)
        {
            LOGI("permission export constraint");
            type = OperationPermission::EXPORT;
        }

        if (name->compare(STR_RO_PRINT) == 0)
        {
            LOGI("permission print constraint");
            type = OperationPermission::PRINT;
        }

        Constraint *cst = NULL;

        if (name->compare(STR_RO_CONSTRAINT) == 0)
        {
            LOGI("permission common constraint");
            type = OperationPermission::COMMON;
        }

        cst = getConstraint(curElement);
        if (cst)
        {
            OperationPermission *op = new OperationPermission(type, cst);
            right->addOperationPermission(op);
        }

        node = node->getNextSibling();
    }

    this->addRight(right);
    return true;
}

/** see Ro.h */
long Ro::convertISO8601DateTimeToLong(const char* ts)
{
    if (NULL == ts)
    {
        return -1;
    }

    struct tm time;
    memset(&time, 0, sizeof(struct tm));

    strptime(ts, "%FT%T%z", &time);

//need timezone support:  return mktime(&time) - timezone;
//It seems android-sooner doesn't support timezone function.
//line below is just for building, value would be wrong if no timezone minus.
    return mktime(&time);
}

/** see Ro.h */
long Ro::convertISO8601PeriodToLong(const char* ts)
{
    if (NULL == ts)
    {
        return -1;
    }

    int date, hour, min, sec;
    sscanf(ts, "P%dDT%dH%dM%dS", &date, &hour, &min, &sec);
    LOGI("%d %d %d %d", date, hour, min, sec);
    return (date*24*60*60 + hour*60*60 + min*60 + sec);
}

/** see Ro.h */
Constraint* Ro::getConstraint(const NodeImpl* curNode)
{
    assert(curNode != NULL);

    Constraint *constraint = new Constraint();

    const XMLElementImpl *curElement = static_cast<const XMLElementImpl*>(curNode);

    const string *name = NULL;
    const string *token = NULL;

    if ((token = curElement->getSoloText(STR_RO_COUNT)))
    {
        LOGI(*token);
        constraint->setCount(atoi(token->c_str()));
    }

    if ((token = curElement->getSoloText(STR_RO_START)))
    {
        LOGI(*token);
        //start Time
        constraint->setStartTime(convertISO8601DateTimeToLong(token->c_str()));
    }

    if ((token = curElement->getSoloText(STR_RO_END)))
    {
        LOGI(*token);
        //end Time
        constraint->setEndTime(convertISO8601DateTimeToLong(token->c_str()));
    }

    if ((token = curElement->getSoloText(STR_RO_INTERVAL)))
    {
        LOGI(*token);
        constraint->setInterval(atoi(token->c_str()));
    }

    if ((token = curElement->getSoloText(STR_RO_ACCUMULATED)))
    {
        LOGI(*token);
        //Period
        constraint->setAccumulated(convertISO8601PeriodToLong(token->c_str()));
    }

    if ((token = curElement->getSoloText(STR_RO_TIMEDCOUNT)))
    {
        LOGI(*token);
        constraint->setTimedCount(atoi(token->c_str()));

        const XMLElementImpl *node = curElement->getSoloElement(STR_RO_TIMEDCOUNT);

        if (node)
        {
            if (node->hasAttributes())
            {
                DOMString timer(STR_RO_TIMER);
                token = node->getAttribute(&timer);
                if (token)
                {
                    LOGI(*token);
                    constraint->setTimer(atoi(token->c_str()));
                }
            }
        }

    }

    return constraint;
}

/** see Ro.h */
void Ro::loadRights(const string& contentID)
{
    for (vector<Right*>::iterator it = this->mRightList.begin();
        it != this->mRightList.end(); it++)
    {
        if ((*it)->mAssetNameList.empty())
        {
            mContentRightList.push_back(*it);
        }
        else
        {
            for (vector<Asset*>::iterator ita = this->mAssetList.begin();
                 ita != this->mAssetList.end(); ita++)
            {
                for (vector<string>::iterator its = (*it)->mAssetNameList.begin();
                     its != (*it)->mAssetNameList.end(); its++)
                {
                    if ((*its).compare((*ita)->getID()) == 0)
                    {
                        if (contentID.compare((*ita)->getContentID()) == 0)
                        {
                            LOGI("find content right");
                            mContentRightList.push_back(*it);
                        }
                    }
                }
            }
        }


    }

}

/** see Ro.h */
void Ro::freeRights()
{
    mContentRightList.clear();
}

/** see Ro.h */
bool Ro::checkPermission(OperationPermission::OPERATION type,
                                const string& contentID)
{
    loadRights(contentID);

    for (vector<Right*>::iterator it = mContentRightList.begin(); it != mContentRightList.end(); it++)
     {
        if ((*it)->checkPermission(type))
        {
            freeRights();
            return true;
        }

     }
    freeRights();
    return false;
}

/** see Ro.h */
Ro::ERRCODE Ro::consume(OperationPermission::OPERATION type,
                         const string& contentID)
{
    loadRights(contentID);

    //check in mRightList
    vector<Right*>::iterator it;
    vector<Right*> tmpList;
    vector<Right*> retList;
    Constraint *constraint = NULL;
    long ealiestEnd = -1;
    bool hasCommonConstraint = false;
    bool hasUnconstraint = false;
    bool hasDateTimeConstraint = false;
    bool hasTimedCountConstraint = false;
    bool hasIntervalConstraint = false;


    //apply the RO rule, if do not satisfy the constraint, .
    //proper right select process

    for (it = mContentRightList.begin(); it != mContentRightList.end(); it++)
    {
        if ((*it)->checkPermission(type))
        {
            constraint = (*it)->getConstraint(OperationPermission::COMMON);
            if (constraint)
            {
                if (!constraint->isValid(time(NULL)))
                {
                    continue;
                }

                hasCommonConstraint = true;
                tmpList.push_back(*it);
            }

            constraint = (*it)->getConstraint(type);
            assert(constraint != NULL);

            if (!constraint->isValid(time(NULL)))
            {
                continue;
            }

            if (constraint->isUnConstraint())
            {
                //use uncontrainted firstly.
                hasUnconstraint = true;
                tmpList.push_back(*it);
                break;
            }

            if (constraint->isDateTimeConstraint())
            {
                //use datetime constraint in high priority.
                //if contain multipe constraints, use the earliest expire time.
                hasDateTimeConstraint = true;
                tmpList.push_back(*it);
                continue;
            }

            if (constraint->isTimedCountConstraint())
            {
            //illegal Operation when time counted
                if (type == OperationPermission::PRINT ||
                    type == OperationPermission::EXPORT)
                {
                    continue;
                }

                hasTimedCountConstraint = true;
                tmpList.push_back(*it);
                continue;
            }

            if (constraint->isIntervalConstraint())
            {
                hasIntervalConstraint = true;
                tmpList.push_back(*it);
                continue;
            }

            tmpList.push_back(*it);
        }
    }


    for (it = tmpList.begin(); it != tmpList.end(); it++)
    {
        if (hasUnconstraint == true)
        {
            //delete other constraint
            constraint = (*it)->getConstraint(type);
            if (constraint)
            {
                if (constraint->isUnConstraint())
                {
                    retList.push_back(*it);
                    break;
                }
            }
            continue;
        }

        if (hasDateTimeConstraint == true)
        {
            //delete other constraint
            constraint = (*it)->getConstraint(type);
            if (constraint)
            {
                if (constraint->isDateTimeConstraint())
                {
                    long tt = constraint->getEndTime();

                    if (ealiestEnd == -1)
                    {
                        ealiestEnd = tt;
                        retList.push_back(*it);
                    }
                    else if (ealiestEnd > tt)
                    {
                        ealiestEnd = tt;
                        retList.pop_back();
                        retList.push_back(*it);
                    }
                }
            }
            continue;
        }

        if (hasIntervalConstraint == true)
        {
            //delete other constraint
            constraint = (*it)->getConstraint(type);
            if (constraint)
            {
                if (constraint->isIntervalConstraint())
                {
                    retList.push_back(*it);
                }
            }
            continue;
        }

        if (hasTimedCountConstraint == true)
        {
            constraint = (*it)->getConstraint(type);
            if (constraint)
            {
                if (constraint->isTimedCountConstraint())
                {
                    retList.push_back(*it);
                }
            }
            continue;
        }

        retList.push_back(*it);
    }

    if (retList.size() == 0)
    {
        freeRights();
        return RO_BAD;
    }

    LOGI("Proper right has %d", retList.size());

    assert(retList.size() == 1);

    mProperRight = retList[0];
    constraint = retList[0]->getConstraint(OperationPermission::COMMON);
    if (constraint)
    {
        if (constraint->consume() == false)
        {
            freeRights();
            return RO_BAD;
        }
    }

    constraint = retList[0]->getConstraint(type);
    if (constraint)
    {
        if (constraint->consume() == false)
        {
            freeRights();
            return RO_BAD;
        }
    }

    //update the constraint
    freeRights();
    return RO_OK;
}

/** see Ro.h */
string Ro::getContentCek(const string& contentID)
{
    for (vector<Asset*>::iterator it = mAssetList.begin();
        it != mAssetList.end(); it++)
    {
        if (contentID.compare((*it)->getContentID()) == 0)
        {
            return (*it)->getCek();
        }
    }

    return "";
}

/** see Ro.h */
string Ro::getContentHash(const string& contentID)
{
    for (vector<Asset*>::iterator it = mAssetList.begin();
        it != mAssetList.end(); it++)
    {
        if (contentID.compare((*it)->getContentID()) == 0)
        {
            return (*it)->getDCFDigest();
        }
    }

    return "";
}
