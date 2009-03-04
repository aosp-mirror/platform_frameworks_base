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
#ifndef _ROAPMESSAGEHADLER_
#define _ROAPMESSAGEHADLER_

#include <Drm2CommonTypes.h>
#include <util/xml/XMLDocumentImpl.h>

class RoapMessageHandler
{
public:
    /**
     * define all the client message types.
     */
     enum msgType {DeviceHello=1,RegistrationRequest,RORequest};

    /**
     * Constructor for DrmManager,used to open local dcf file.
     * @param type the message type.
     */
     RoapMessageHandler();

    /**
     * Create one specified client message based on message template xml file.
     * @param type the message type.     
     * @return the pointer of the document object of the message if successful,otherwise
     *         return NULL.
     */
     XMLDocumentImpl* createClientMsg(msgType type);

    /**
     * Handle received message from RI.
     * @return true if successful, otherwise return false.
     */
    bool handlePeerMsg();

    /**
     * Send the client message to RI
     */
    int16_t send();

    /**
     * Receive message from RI and parse it 
     * @return the pointer of the parsed document.
     */
    XMLDocumentImpl* receive();

PROTECTED:
    XMLDocumentImpl * mDoc;
PRIVATE:
    int16_t mMsgType;
};
#endif //_ROAPMESSAGEHADLER_
