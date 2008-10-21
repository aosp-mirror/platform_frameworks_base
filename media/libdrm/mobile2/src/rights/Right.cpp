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
#include <rights/Right.h>
#include <rights/OperationPermission.h>
#include <rights/Constraint.h>

/** see Right.h */
Right::Right()
{

}

/** see Right.h */
Right::~Right()
{
     vector<OperationPermission*>::iterator it;

     for (it = mOpList.begin(); it != mOpList.end(); it++)
     {
        delete(*it);
     }

     mOpList.clear();
}

/** see Right.h */
void Right::addAssetID(const string& id)
{
    mAssetNameList.push_back(id);
}

/** see Right.h */
void Right::addOperationPermission(OperationPermission* op)
{
    mOpList.push_back(op);
}

/** see Right.h */
bool Right::checkPermission(OperationPermission::OPERATION type)
{
    for (vector<OperationPermission*>::iterator it = mOpList.begin();
             it != mOpList.end(); it++)
    {
        if ((*it)->getType() == type)
        {
            return true;
        }
    }

    return false;
}

/** see Right.h */
Constraint* Right::getConstraint(OperationPermission::OPERATION type)
{
    for (vector<OperationPermission*>::iterator it = mOpList.begin();
             it != mOpList.end(); it++)
    {
        if ((*it)->getType() == type)
        {
            return (*it)->getConstraint();
        }
    }

    return NULL;
}
