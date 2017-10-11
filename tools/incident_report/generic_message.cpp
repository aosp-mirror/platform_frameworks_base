/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "generic_message.h"

GenericMessage::GenericMessage()
{
}

GenericMessage::~GenericMessage()
{
}

void
GenericMessage::addInt32(int32_t fieldId, uint32_t value)
{
    Node node;
    node.type = TYPE_VALUE32;
    node.value32 = value;
    mNodes.insert(pair<int32_t,Node>(fieldId, node));
}

void
GenericMessage::addInt64(int32_t fieldId, uint64_t value)
{
    Node node;
    node.type = TYPE_VALUE64;
    node.value64 = value;
    mNodes.insert(pair<int32_t,Node>(fieldId, node));
}

GenericMessage*
GenericMessage::addMessage(int32_t fieldId)
{
    GenericMessage* result = new GenericMessage();
    Node node;
    node.type = TYPE_MESSAGE;
    node.message = result;
    mNodes.insert(pair<int32_t,Node>(fieldId, node));
    return result;
}

void
GenericMessage::addString(int32_t fieldId, const string& value)
{
    Node node;
    node.type = TYPE_STRING;
    node.str = new string(value);
    mNodes.insert(pair<int32_t,Node>(fieldId, node));
}

GenericMessage::const_iterator_pair
GenericMessage::find(int fieldId) const
{
    return mNodes.equal_range(fieldId);
}

