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
#ifndef _ROMANAGER_H
#define _ROMANAGER_H

#include <Drm2CommonTypes.h>
#include <ustring.h>
#include <rights/Ro.h>

using namespace ustl;

class RoManager {

public:
    /**
     * Singleton instance function.
     * @return the singleton pointer.
     */
    static RoManager* Instance();

    /**
     * Destructor for ExpatWrapper.
     */
    ~RoManager();

    /**
     * Install Ro from stream.
     * @param roStream the input ro stream.
     * @return the status of installaltion.
     */
    Ro::ERRCODE installRo(istringstream *roStream);

    /**
     * Check whether Ro in cache or not.
     * @param roID the specific roID.
     * @return true/false to indicate result.
     */
    bool checkRoInCache(const string& roID);

    /**
     * Get the ro.
     * @param roID the specific id of ro.
     * @return NULL if not found otherwise return ro.
     */
    Ro* getRo(const string& roID);

    /**
     * Get all the Ro.
     * @return ro list.
     */
    vector<Ro*> getAllRo();

    /**
     * Get ro which contained rights of specific content.
     * @param contentID the specific id of content.
     * @return NULL if not fount otherwise the related ro.
     */
    Ro* getRoByContentID(const string& contentID);

    /**
     * Delete Ro by its id.
     * @param roID the specific roID.
     * @return true/false to indicate the result.
     */
    bool deleteRo(const string& roID);


PRIVATE:
    /**
     * Constructor for RoManager.
     */
    RoManager();

PRIVATE:
    static RoManager* msInstance; /**< singleton instance pointer. */
    vector<Ro*> mRoList; /**< the ro list. */
};

#endif
