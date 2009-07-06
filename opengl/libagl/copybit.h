/*
**
** Copyright 2009, The Android Open Source Project
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

#ifndef ANDROID_OPENGLES_COPYBIT_H
#define ANDROID_OPENGLES_COPYBIT_H

#include <stdlib.h>

#include <GLES/gl.h>

#include "TextureObjectManager.h"
namespace android {
#ifdef LIBAGL_USE_GRALLOC_COPYBITS

bool drawTexiOESWithCopybit_impl(GLint x, GLint y, GLint z,
        GLint w, GLint h, ogles_context_t* c);

bool drawTriangleFanWithCopybit_impl(ogles_context_t* c, GLint first,
        GLsizei count);

inline bool copybitQuickCheckContext(ogles_context_t* c) {
        return  c->copybits.drawSurfaceBuffer != 0
            && c->rasterizer.state.enabled_tmu == 1
            && c->textures.tmu[0].texture->try_copybit;
}

/*
 * Tries to draw a drawTexiOES using copybit hardware.
 * Returns true if successful.
 */
inline bool drawTexiOESWithCopybit(GLint x, GLint y, GLint z,
        GLint w, GLint h, ogles_context_t* c) {
    if (!copybitQuickCheckContext(c)) {
    	return false;
   	}
   	
   	return drawTexiOESWithCopybit_impl(x, y, z, w, h, c);
}

/*
 * Tries to draw a triangle fan using copybit hardware.
 * Returns true if successful.
 */
inline bool drawTriangleFanWithCopybit(ogles_context_t* c, GLint first,
        GLsizei count) {
    /*
     * We are looking for the glDrawArrays call made by SurfaceFlinger.
     */

    if ((count!=4) || first || !copybitQuickCheckContext(c))
        return false;
    
    return drawTriangleFanWithCopybit_impl(c, first, count);
}


#endif // LIBAGL_USE_GRALLOC_COPYBITS

} // namespace android

#endif // ANDROID_OPENGLES_COPYBIT_H
