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

#ifndef GENERIC_MESSAGE_H
#define GENERIC_MESSAGE_H

#include <map>
#include <string>

using namespace std;

/**
 * Class to represent a protobuf Message, where we don't actually
 * know what any of the fields are, just their type codes. In other
 * words, this losslessly stores a parsed protobuf object without
 * having the .proto file that generated it.
 */
class GenericMessage
{
public:
    GenericMessage();
    ~GenericMessage();

    enum {
        TYPE_VALUE32,
        TYPE_VALUE64,
        TYPE_MESSAGE,
        TYPE_STRING,
        TYPE_DATA
    };

    struct Node {
        uint32_t type;
        union {
            uint32_t value32;
            uint64_t value64;
            GenericMessage* message;
            string* str;
            string* data;
        };
    };

    void addInt32(int32_t fieldId, uint32_t value);
    void addInt64(int32_t fieldId, uint64_t value);
    GenericMessage* addMessage(int32_t fieldId);
    void addString(int32_t fieldId, const string& value);

    typedef multimap<int32_t,Node>::const_iterator const_iterator;
    typedef pair<const_iterator,const_iterator> const_iterator_pair;

    const_iterator_pair find(int fieldId) const;

private:
    multimap<int,Node> mNodes;
};

#endif // GENERIC_MESSAGE_H

