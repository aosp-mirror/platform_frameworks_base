/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef ANDROID_HWUI_FBO_CACHE_H
#define ANDROID_HWUI_FBO_CACHE_H

#include <GLES2/gl2.h>

#include <utils/SortedVector.h>

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Cache
///////////////////////////////////////////////////////////////////////////////

class FboCache {
public:
    FboCache();
    ~FboCache();

    /**
     * Returns an FBO from the cache. If no FBO is available, a new one
     * is created. If creating a new FBO fails, 0 is returned.
     *
     * When an FBO is obtained from the cache, it is removed and the
     * total number of FBOs available in the cache decreases.
     *
     * @return The name of the FBO, or 0 if no FBO can be obtained.
     */
    GLuint get();

    /**
     * Adds the specified FBO to the cache.
     *
     * @param fbo The FBO to add to the cache.
     *
     * @return True if the FBO was added, false otherwise.
     */
    bool put(GLuint fbo);

    /**
     * Clears the cache. This causes all FBOs to be deleted.
     */
    void clear();

    /**
     * Returns the current size of the cache.
     */
    uint32_t getSize();

    /**
     * Returns the maximum number of FBOs that the cache can hold.
     */
    uint32_t getMaxSize();

private:
    SortedVector<GLuint> mCache;
    uint32_t mMaxSize;
}; // class FboCache

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_FBO_CACHE_H
