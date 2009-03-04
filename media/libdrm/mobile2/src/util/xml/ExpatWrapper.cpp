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
#include <util/xml/ExpatWrapper.h>
#include <ustring.h>
using namespace ustl;

/** see ExpatWrapper.h */
ExpatWrapper::ExpatWrapper()
{
    mParser = XML_ParserCreate(NULL);
    ::XML_SetUserData(mParser, this);
    ::XML_SetElementHandler(mParser, startElementCallback, endElementCallback);
    ::XML_SetCharacterDataHandler(mParser, dataHandlerCallback);

}

/** see ExpatWrapper.h */
ExpatWrapper::~ExpatWrapper()
{
    if (mParser)
    {
        ::XML_ParserFree(mParser);
    }
}

/** see ExpatWrapper.h */
int ExpatWrapper::decode(const char* buf, int len, int isFinal)
{
    return ::XML_Parse(mParser, buf, len, isFinal);
}

/** see ExpatWrapper.h */
void ExpatWrapper::startElementCallback(void *userData, const XML_Char *name,
                                        const XML_Char **atts)
{
    ((ExpatWrapper *)userData)->startElement(name, atts);
}

/** see ExpatWrapper.h */
void ExpatWrapper::endElementCallback(void *userData, const XML_Char *name)
{
    ((ExpatWrapper *)userData)->endElement(name);
}

/** see ExpatWrapper.h */
void ExpatWrapper::dataHandlerCallback(void *userData, const XML_Char *s, int len)
{
    ((ExpatWrapper *)userData)->dataHandler(s, len);
}

/** see ExpatWrapper.h */
void ExpatWrapper::startElement(const XML_Char *name, const XML_Char **atts)
{
}

/** see ExpatWrapper.h */
void ExpatWrapper::endElement(const XML_Char *name)
{
}

/** see ExpatWrapper.h */
void ExpatWrapper::dataHandler(const XML_Char *s, int len)
{
}
