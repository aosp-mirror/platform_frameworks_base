/*
 * Copyright (C) 2012 The Android Open Source Project
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

#ifndef ANDROID_HWUI_STENCIL_H
#define ANDROID_HWUI_STENCIL_H

#include <GLES2/gl2.h>

#include <cutils/compiler.h>

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Stencil buffer management
///////////////////////////////////////////////////////////////////////////////

class ANDROID_API Stencil {
public:
    /**
     * Returns the desired size for the stencil buffer. If the returned value
     * is 0, then no stencil buffer is required.
     */
    ANDROID_API static uint8_t getStencilSize();

    static GLenum getLayerStencilFormat();

    /**
     * Clears the stencil buffer.
     */
    void clear();

    /**
     * Enables stencil test. When the stencil test is enabled the stencil buffer is not written
     * into. An increment threshold of zero causes the stencil to use a constant reference value
     * and GL_EQUAL for the test. A non-zero increment threshold causes the stencil to use that
     * value as the reference value and GL_EQUAL for the test.
     */
    void enableTest(int incrementThreshold);

    /**
     * Enables stencil write. When stencil write is enabled, the stencil
     * test always succeeds and the value 0x1 is written in the stencil
     * buffer for each fragment. An increment threshold of zero causes the stencil to use a constant
     * reference value and GL_EQUAL for the test. A non-zero increment threshold causes the stencil
     * to use that value as the reference value and GL_EQUAL for the test.
     */
    void enableWrite(int incrementThreshold);

    /**
     * The test passes only when equal to the specified value.
     */
    void enableDebugTest(GLint value, bool greater = false);

    /**
     * Used for debugging. The stencil test always passes and increments.
     */
    void enableDebugWrite();

    /**
     * Disables stencil test and write.
     */
    void disable();

    /**
     * Indicates whether either test or write is enabled.
     */
    bool isEnabled() { return mState != StencilState::Disabled; }

    /**
     * Indicates whether testing only is enabled.
     */
    bool isTestEnabled() { return mState == StencilState::Test; }

    bool isWriteEnabled() { return mState == StencilState::Write; }

    void dump();

private:
    enum class StencilState { Disabled, Test, Write };

    void enable();
    StencilState mState = StencilState::Disabled;

};  // class Stencil

};  // namespace uirenderer
};  // namespace android

#endif  // ANDROID_HWUI_STENCIL_H
