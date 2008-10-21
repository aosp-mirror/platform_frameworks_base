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
#include <util/domcore/NodeListImpl.h>

/** see NodeListImpl.h*/
void NodeListImpl::append(const NodeImpl* newNode)
{
    if (newNode == NULL)
        return;

    nodeList.push_back(newNode);
}

/** see NodeListImpl.h*/
const NodeImpl* NodeListImpl::item(int index) const
{
    int size = nodeList.size();

    if (size == 0 || index > size - 1 || index < 0)
        return NULL;

    return nodeList.at(index);
}

/** see NodeListImpl.h*/
int NodeListImpl::getLength() const
{
    return nodeList.size();
}

/** see NodeListImpl.h*/
NodeListImpl::~NodeListImpl()
{
    nodeList.clear();
}
