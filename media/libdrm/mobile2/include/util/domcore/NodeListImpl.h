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
#ifndef __NODE_LIST_IMPL__
#define __NODE_LIST_IMPL__

#include "NodeImpl.h"
#include "Vector.h"
class NodeListImpl {
private:
    vector<const NodeImpl*> nodeList;
public:
    /**
     *  Add a special node into list.
     *  @param newNode specify component.
     */
    void append(const NodeImpl* newNode);

    /**
     * Return The special position node pointer.
     * @param index The special position.
     * @return The node's pointer on success.
     *         NULL when out of list's boundary.
     */
    const NodeImpl* item(int index) const;

    /**
     * Return the length of list.
     * @return the length of list.
     */
    int getLength() const;

    ~NodeListImpl();
};
#endif /*__NODE_LIST_IMPL__ */

