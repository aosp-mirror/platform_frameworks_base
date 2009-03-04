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
#ifndef _OPERATIONPERMISSION_H
#define _OPERATIONPERMISSION_H

#include <Drm2CommonTypes.h>
#include <rights/Constraint.h>

class OperationPermission {
public:
    enum OPERATION {NONE, PLAY, DISPLAY, EXECUTE, PRINT, EXPORT, COMMON};

    /**
     * Construtor of OperationPermission.
     */
    OperationPermission();

    /**
     * Construtor of OperationPermission.
     * @param type the specific operation type.
     * @param cst the constraint related with operation permission.
     */
    OperationPermission(OPERATION type, Constraint* cst=NULL);

    /**
     * Destrutor of OperationPermission.
     */
    ~OperationPermission();

    /**
     * Set the type for operation permission.
     * @param type the specific type.
     */
    void setType(OPERATION type);

    /**
     * Get the type of operation permission.
     * @return operation type.
     */
    OPERATION getType() const;

    /**
     * Add constraint for operation permission.
     * @param constraint the constraint related with operation permission.
     */
    void addConstraint(Constraint* constraint);

    /**
     * Add constraint for operation permission.
     * @return constraint related with operation permission.
     */
    Constraint* getConstraint() const;

PRIVATE:
    OPERATION mType;
    Constraint* mConstraint;

PRIVATE:
    /**
     * Disable the assignment between OperationPermissions.
     */
    OperationPermission& operator=(const OperationPermission &op);

    /**
     * Disable copy construtor.
     */
    OperationPermission(const OperationPermission &op);
};

#endif
