/*
 * Copyright (C) 2013 The Android Open Source Project
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

#ifndef ANDROID_HWUI_QUERY_H
#define ANDROID_HWUI_QUERY_H

#include <GLES3/gl3.h>

#include "Extensions.h"

namespace android {
namespace uirenderer {

/**
 * A Query instance can be used to perform occlusion queries. If the device
 * does not support occlusion queries, the result of a query will always be
 * 0 and the result will always be marked available.
 *
 * To run an occlusion query successfully, you must start end end the query:
 *
 * Query query;
 * query.begin();
 * // execute OpenGL calls
 * query.end();
 * GLuint result = query.getResult();
 */
class Query {
public:
    /**
     * Possible query targets.
     */
    enum Target {
        /**
         * Indicates if any sample passed the depth & stencil tests.
         */
        kTargetSamples = GL_ANY_SAMPLES_PASSED,
        /**
         * Indicates if any sample passed the depth & stencil tests.
         * The implementation may choose to use a less precise version
         * of the test, potentially resulting in false positives.
         */
        kTargetConservativeSamples = GL_ANY_SAMPLES_PASSED_CONSERVATIVE,
    };

    /**
     * Creates a new query with the specified target. The default
     * target is kTargetSamples (of GL_ANY_SAMPLES_PASSED in OpenGL.)
     */
    Query(Target target = kTargetSamples): mActive(false), mTarget(target),
            mCanQuery(Extensions::getInstance().hasOcclusionQueries()),
            mQuery(0) {
    }

    ~Query() {
        if (mQuery) {
            glDeleteQueries(1, &mQuery);
        }
    }

    /**
     * Begins the query. If the query has already begun or if the device
     * does not support occlusion queries, calling this method as no effect.
     * After calling this method successfully, the query is marked active.
     */
    void begin() {
        if (!mActive && mCanQuery) {
            if (!mQuery) {
                glGenQueries(1, &mQuery);
            }

            glBeginQuery(mTarget, mQuery);
            mActive = true;
        }
    }

    /**
     * Ends the query. If the query has already begun or if the device
     * does not support occlusion queries, calling this method as no effect.
     * After calling this method successfully, the query is marked inactive.
     */
    void end() {
        if (mQuery && mActive) {
            glEndQuery(mTarget);
            mActive = false;
        }
    }

    /**
     * Returns true if the query is active, false otherwise.
     */
    bool isActive() {
        return mActive;
    }

    /**
     * Returns true if the result of the query is available,
     * false otherwise. Calling getResult() before the result
     * is available may result in the calling thread being blocked.
     * If the device does not support queries, this method always
     * returns true.
     */
    bool isResultAvailable() {
        if (!mQuery) return true;

        GLuint result;
        glGetQueryObjectuiv(mQuery, GL_QUERY_RESULT_AVAILABLE, &result);
        return result == GL_TRUE;
    }

    /**
     * Returns the result of the query. If the device does not
     * support queries this method will return 0.
     *
     * Calling this method implicitely calls end() if the query
     * is currently active.
     */
    GLuint getResult() {
        if (!mQuery) return 0;

        end();

        GLuint result;
        glGetQueryObjectuiv(mQuery, GL_QUERY_RESULT, &result);
        return result;
    }


private:
    bool mActive;
    GLenum mTarget;
    bool mCanQuery;
    GLuint mQuery;

}; // class Query

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_QUERY_H
