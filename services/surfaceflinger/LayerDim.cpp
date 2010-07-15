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

#include <stdlib.h>
#include <stdint.h>
#include <sys/types.h>

#include <utils/Errors.h>
#include <utils/Log.h>

#include <ui/GraphicBuffer.h>

#include "LayerDim.h"
#include "SurfaceFlinger.h"
#include "DisplayHardware/DisplayHardware.h"

namespace android {
// ---------------------------------------------------------------------------

bool LayerDim::sUseTexture;
GLuint LayerDim::sTexId;
EGLImageKHR LayerDim::sImage;
int32_t LayerDim::sWidth;
int32_t LayerDim::sHeight;

// ---------------------------------------------------------------------------

LayerDim::LayerDim(SurfaceFlinger* flinger, DisplayID display,
        const sp<Client>& client)
    : LayerBaseClient(flinger, display, client)
{
}

void LayerDim::initDimmer(SurfaceFlinger* flinger, uint32_t w, uint32_t h)
{
    sTexId = -1;
    sImage = EGL_NO_IMAGE_KHR;
    sWidth = w;
    sHeight = h;
    sUseTexture = false;
}

LayerDim::~LayerDim()
{
}

void LayerDim::onDraw(const Region& clip) const
{
    const State& s(drawingState());
    Region::const_iterator it = clip.begin();
    Region::const_iterator const end = clip.end();
    if (s.alpha>0 && (it != end)) {
        const DisplayHardware& hw(graphicPlane(0).displayHardware());
        const GLfloat alpha = s.alpha/255.0f;
        const uint32_t fbHeight = hw.getHeight();
        glDisable(GL_DITHER);
        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        glColor4f(0, 0, 0, alpha);

#if defined(GL_OES_texture_external)
        if (GLExtensions::getInstance().haveTextureExternal()) {
            glDisable(GL_TEXTURE_EXTERNAL_OES);
        }
#endif
        glDisable(GL_TEXTURE_2D);

        GLshort w = sWidth;
        GLshort h = sHeight;
        const GLshort vertices[4][2] = {
                { 0, 0 },
                { 0, h },
                { w, h },
                { w, 0 }
        };
        glVertexPointer(2, GL_SHORT, 0, vertices);

        while (it != end) {
            const Rect& r = *it++;
            const GLint sy = fbHeight - (r.top + r.height());
            glScissor(r.left, sy, r.width(), r.height());
            glDrawArrays(GL_TRIANGLE_FAN, 0, 4); 
        }
    }
    glDisableClientState(GL_TEXTURE_COORD_ARRAY);
}

// ---------------------------------------------------------------------------

}; // namespace android
