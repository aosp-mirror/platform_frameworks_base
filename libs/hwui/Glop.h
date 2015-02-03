/*
 * Copyright (C) 2015 The Android Open Source Project
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

#ifndef ANDROID_HWUI_GLOP_H
#define ANDROID_HWUI_GLOP_H

#include "Matrix.h"
#include "Rect.h"
#include "utils/Macros.h"

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

namespace android {
namespace uirenderer {

/*
 * Enumerates optional vertex attributes
 *
 * Position is always enabled by MeshState, these other attributes
 * are enabled/disabled dynamically based on mesh content.
 */
enum VertexAttribFlags {
    // NOTE: position attribute always enabled
    kTextureCoord_Attrib = 1 << 0,
    kColor_Attrib = 1 << 1,
    kAlpha_Attrib = 1 << 2,
};

/**
 * Structure containing all data required to issue a single OpenGL draw
 *
 * Includes all of the mesh, fill, and GL state required to perform
 * the operation. Pieces of data are either directly copied into the
 * structure, or stored as a pointer or GL object reference to data
 * managed
 */
// TODO: PREVENT_COPY_AND_ASSIGN(...) or similar
struct Glop {
    Rect bounds;

    struct Mesh {
        VertexAttribFlags vertexFlags = static_cast<VertexAttribFlags>(0);
        GLuint primitiveMode; // GL_TRIANGLES and GL_TRIANGLE_STRIP supported
        GLuint vertexBufferObject = 0;
        GLuint indexBufferObject = 0;
        int vertexCount;
        GLsizei stride;
    } mesh;

    struct Fill {
        Program* program;
        GLuint shaderId;
        GLuint textureId;

        struct Color {
            float a, r, g, b;
        } color;

        /* TODO
        union shader {
            //...
        }; TODO
        union filter {
            //color
            //matrix + vector
        };
        */
    } fill;

    struct Transform {
        Matrix4 ortho; // TODO: out of op, since this is static per FBO
        Matrix4 modelView;
        Matrix4 canvas;
        bool offset;
    } transform;

    struct Blend {
        static const SkXfermode::Mode kDisable =
                static_cast<SkXfermode::Mode>(SkXfermode::kLastMode + 1);
        SkXfermode::Mode mode;
        bool swapSrcDst;
    } blend;

    /**
     * Additional render state to enumerate:
     * - scissor + (bits for whether each of LTRB needed?)
     * - stencil mode (draw into, mask, count, etc)
     */
};

} /* namespace uirenderer */
} /* namespace android */

#endif // ANDROID_HWUI_GLOP_H
