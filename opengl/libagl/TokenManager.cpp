/* libs/opengles/surface.cpp
**
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License"); 
** you may not use this file except in compliance with the License. 
** You may obtain a copy of the License at 
**
**     http://www.apache.org/licenses/LICENSE-2.0 
**
** Unless required by applicable law or agreed to in writing, software 
** distributed under the License is distributed on an "AS IS" BASIS, 
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
** See the License for the specific language governing permissions and 
** limitations under the License.
*/

#include <stdio.h>
#include <stdlib.h>
#include "TokenManager.h"

namespace android {
// ----------------------------------------------------------------------------

TokenManager::TokenManager()
{
    // token 0 is always reserved
    mTokenizer.reserve(0);
}

TokenManager::~TokenManager()
{
}

status_t TokenManager::getToken(GLsizei n, GLuint *tokens)
{
    Mutex::Autolock _l(mLock);
    for (GLsizei i=0 ; i<n ; i++)
        *tokens++ = mTokenizer.acquire();
    return NO_ERROR;
}

void TokenManager::recycleTokens(GLsizei n, const GLuint *tokens)
{
    Mutex::Autolock _l(mLock);
    for (int i=0 ; i<n ; i++) {
        const GLuint token = *tokens++;
        if (token) {
            mTokenizer.release(token);
        }
    }
}

bool TokenManager::isTokenValid(GLuint token) const
{
    Mutex::Autolock _l(mLock);
    return mTokenizer.isAcquired(token);
}

// ----------------------------------------------------------------------------
}; // namespace android

