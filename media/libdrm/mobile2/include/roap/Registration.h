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
#ifndef _REGISTRATION_
#define _REGISTRATION_

#include <roap/RoapMessageHandler.h>

class Registration : public RoapMessageHandler
{
public:
    /**
     * Constructor for Registration.
     * @param type the address of RI.
     */
    Registration(string riAddres);

    /**
     * Registration with the RI.
     * @return the result of registration.
     */
    int16_t registerWithRI();

    /**
     * Create one specified client message based on message template xml file.
     * @param type the message type.     
     * @return the pointer of the document object of the message if successful,otherwise
     *         return NULL.
     */
     XMLDocumentImpl* createClientMsg(int16_t type);
}
#endif _REGISTRATION_
