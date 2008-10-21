/*
 ** Copyright 2008, The Android Open Source Project
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

#include <stdint.h>
#include <stddef.h>
#include <sys/types.h>

#include <utils/Atomic.h>
#include <utils/RefBase.h>
#include <utils/KeyedVector.h>
#include <utils/Errors.h>

#include <GLES/gl.h>

#include "BufferObjectManager.h"


namespace android {

using namespace gl;

// ----------------------------------------------------------------------------

EGLBufferObjectManager::EGLBufferObjectManager() 
: TokenManager(), mCount(0)
{
}

EGLBufferObjectManager::~EGLBufferObjectManager()
{
    // destroy all the buffer objects and their storage
    GLsizei n = mBuffers.size();
    for (GLsizei i=0 ; i<n ; i++) {
        buffer_t* bo = mBuffers.valueAt(i);
        free(bo->data);
        delete bo;
    }
}

buffer_t const* EGLBufferObjectManager::bind(GLuint buffer)
{
    Mutex::Autolock _l(mLock);
    int32_t i = mBuffers.indexOfKey(buffer);
    if (i >= 0) {
        return mBuffers.valueAt(i);
    }
    buffer_t* bo = new buffer_t;
    bo->data = 0;
    bo->usage = GL_STATIC_DRAW;
    bo->size = 0;
    bo->name = buffer;
    mBuffers.add(buffer, bo);
    return bo;
}

int EGLBufferObjectManager::allocateStore(buffer_t* bo,
        GLsizeiptr size, GLenum usage)
{
    Mutex::Autolock _l(mLock);
    if (size != bo->size) {
       uint8_t* data = (uint8_t*)malloc(size);
        if (data == 0)
            return -1;
        free(bo->data);
        bo->data = data;
        bo->size = size;
    }
    bo->usage = usage;
    return 0;
}

void EGLBufferObjectManager::deleteBuffers(GLsizei n, const GLuint* buffers)
{
    Mutex::Autolock _l(mLock);
    while (n--) {
        const GLuint t = *buffers++;
        if (t) {
            int32_t index = mBuffers.indexOfKey(t);
            if (index >= 0) {
                buffer_t* bo = mBuffers.valueAt(index);
                free(bo->data);
                mBuffers.removeItemsAt(index);
                delete bo;
            }
        }
    }
}

// ----------------------------------------------------------------------------
}; // namespace android
