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
#ifndef _XML_H_
#define _XML_H_

#include <expat.h>
#include <ustring.h>
#include <Drm2CommonTypes.h>

using namespace ustl;

class ExpatWrapper {
public:
    /**
     * Constructor for ExpatWrapper.
     */
    ExpatWrapper();

    /**
     * Destructor for ExpatWrapper.
     */
    virtual ~ExpatWrapper();

    /**
     * decode call expat to parse the xml.
     * @param buf The buffer to be parsed.
     * @param len The length of the buffer.
     * @param isFinal The flag to indicate whether the buffer
     *                is a fragment or whole xml.
     */
    int decode(const char* buf, int len, int isFinal);

    /**
     * virtual funtion to deal with the start element in expat, need implement by child class.
     */
    virtual void startElement(const XML_Char *name, const XML_Char **atts);

    /**
     * virtual funtion to deal with the end element in expat, need implement by child class.
     */
    virtual void endElement(const XML_Char *name);

    /**
     * virtual funtion to deal with the data handler in expat, need implement by child class.
     */
    virtual void dataHandler(const XML_Char *s, int len);

PRIVATE:
    /**
     * Callback for Expat startElement.
     */
    static void startElementCallback(void *userData, const XML_Char *name, const XML_Char **atts);

    /**
     * Callback for Expat endElement.
     */
    static void endElementCallback(void *userData, const XML_Char *name);

    /**
     * Callback for Expat dataHandler.
     */
    static void dataHandlerCallback(void *userData, const XML_Char *s, int len);

PRIVATE:
    XML_Parser mParser; /**< The expat parser object. */
};

#endif
