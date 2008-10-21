/*
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

#ifndef ANDROID_OPENGLES_TOKEN_MANAGER_H
#define ANDROID_OPENGLES_TOKEN_MANAGER_H

#include <stdint.h>
#include <stddef.h>
#include <sys/types.h>

#include <utils/threads.h>

#include <GLES/gl.h>

#include "Tokenizer.h"

namespace android {

// ----------------------------------------------------------------------------

class TokenManager
{
public:
                TokenManager();
                ~TokenManager();

    status_t    getToken(GLsizei n, GLuint *tokens);
    void        recycleTokens(GLsizei n, const GLuint *tokens);
    bool        isTokenValid(GLuint token) const;

private:
    mutable Mutex   mLock;
    Tokenizer       mTokenizer;
};

// ----------------------------------------------------------------------------
}; // namespace android

#endif // ANDROID_OPENGLES_TOKEN_MANAGER_H

