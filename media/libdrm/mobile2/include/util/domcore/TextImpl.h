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
#ifndef __DOM_TEXT_IMPL__
#define __DOM_TEXT_IMPL__
#include "CharacterDataImpl.h"
class TextImpl:public CharacterDataImpl
{
private:
        const static DOMString nodeName;
public:
        /** Text default constructor for TextImpl.*/
        TextImpl();

        /**
         * Constructor for TextImpl
         * @param data The specify data to be set.
         */
        TextImpl(const DOMString* data);

        /** Override getNodeType method in NodeImpl.h */
        NodeType getNodeType() const;

        /** Override getNodeName method in NodeImpl.h */
        const DOMString* getNodeName() const;
};
#endif /*__DOM_TEXT_IMPL__*/

