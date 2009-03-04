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
#ifndef _RIGHT_H
#define _RIGHT_H

#include <Drm2CommonTypes.h>
#include <uvector.h>
#include <ustring.h>
#include <rights/Constraint.h>
#include <rights/OperationPermission.h>
using namespace ustl;

class Right {
public:
    /**
     * Constructor for Right.
     */
    Right();

    /**
     * Destructor for Right.
     */
    ~Right();

    /**
     * Add the asset id related with right into asset name list.
     * @param id the id of the asset.
     */
    void addAssetID(const string& id);

    /**
     * Add a operation permission into right's operation permission list.
     * @param op a pointer of operation permission.
     */
    void addOperationPermission(OperationPermission* op);

    /**
     * Get the constraint related with operation type.
     * @param type the specific operation type.
     * @return NULL if not found otherwise the constraint pointer.
     */
    Constraint* getConstraint(OperationPermission::OPERATION type);

    /**
     * Test whether the right has specific operation type or not.
     * @param type the specific type.
     * @return true/false to indicate the result.
     */
    bool checkPermission(OperationPermission::OPERATION type);

public:
    vector<string> mAssetNameList;

PRIVATE:
    vector<OperationPermission*> mOpList;

PRIVATE:

    /**
     * Disable the assignment between rights.
     */
    Right& operator=(const Right& right);

    /**
     * Disable copy constructor.
     */
    Right(const Right& right);
   };

#endif
