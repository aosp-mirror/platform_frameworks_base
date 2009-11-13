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

#include <rights/RoManager.h>
#include <rights/Asset.h>

using namespace ustl;

RoManager* RoManager::msInstance = NULL;

/** see RoManager.h */
RoManager* RoManager::Instance()
{
    if (NULL == msInstance)
    {
        msInstance = new RoManager();
    }

    return msInstance;
}

/** see RoManager.h */
RoManager::RoManager()
{
//load the ro list from local system.
}

/** see RoManager.h */
RoManager::~RoManager()
{
    msInstance = NULL;

    for (vector<Ro*>::iterator it = mRoList.begin();
         it != mRoList.end(); it++)
    {
        delete (*it);
    }

    mRoList.clear();
}

/** see RoManager.h */
Ro::ERRCODE RoManager::installRo(istringstream *roStream)
{
    Ro *ro = new Ro();

    Ro::ERRCODE ret = ro->parse(roStream);

    if (Ro::RO_OK == ret)
    {
        ro->save();

        mRoList.push_back(ro);
    }

    return ret;
}

/** see RoManager.h */
Ro* RoManager::getRoByContentID(const string& contentID)
{
    for (vector<Ro*>::iterator it = mRoList.begin();
         it != mRoList.end(); it++)
    {
        for (vector<Asset*>::iterator ita = (*it)->mAssetList.begin();
             ita != (*it)->mAssetList.end(); ita++)
        {
            if (contentID.compare((*ita)->getContentID()) == 0)
            {
                return *it;
            }
        }
    }

    return NULL;
}

/** see RoManager.h */
Ro* RoManager::getRo(const string& roID)
{
    for (vector<Ro*>::iterator it = mRoList.begin();
         it != mRoList.end(); it++)
    {
        if (roID.compare((*it)->getRoID()) == 0)
        {
            return (*it);
        }
    }

    return NULL;
}

/** see RoManager.h */
vector<Ro*> RoManager::getAllRo()
{
    return mRoList;
}

/** see RoManager.h */
bool RoManager::deleteRo(const string& roID)
{
    return true;
}

/** see RoManager.h */
bool RoManager::checkRoInCache(const string& roID)
{
    return true;
}

