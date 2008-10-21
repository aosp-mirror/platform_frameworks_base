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
#include <util/domcore/CharacterDataImpl.h>

/** see CharacterDataImpl.h */
const DOMString* CharacterDataImpl::getData() const throw (DOMException)
{
    return charData;
}

/** see CharacterDataImpl.h */
CharacterDataImpl::CharacterDataImpl():charData(NULL)
{
}

/** see CharacterDataImpl.h*/
CharacterDataImpl::CharacterDataImpl(const DOMString* data):charData(NULL)
{
    if (data != NULL)
        charData = new DOMString(*data);
}

/** see CharacterDataImpl.h */
void CharacterDataImpl::setData(const DOMString* data) throw (DOMException)
{

    if (charData != NULL)
        delete charData;

    if (data == NULL)
        charData = NULL;
    else
        charData = new DOMString(*data);
}

/** see CharacterDataImpl.h */
int CharacterDataImpl::getLength() const
{
    return charData != NULL ? charData->length() : 0;
}

/** see CharacterDataImpl.h */
void CharacterDataImpl::appendData(const DOMString* arg) throw(DOMException)
{
    if (arg != NULL) {
        if (charData != NULL)
            charData->append(*arg);
        else
            charData = new DOMString(*arg);
    }
}

/** see CharacterDataImpl.h */
const DOMString* CharacterDataImpl::getNodeValue() const throw(DOMException)
{
    return getData();
}

/** see CharacterDataImpl.h */
void CharacterDataImpl::setNodeValue(DOMString* nodeValue) throw(DOMException)
{
    setData(nodeValue);
}

/** see CharacterDataImpl.h */
CharacterDataImpl::~CharacterDataImpl()
{
    delete charData;
}

