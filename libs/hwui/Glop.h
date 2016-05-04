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

#include "FloatColor.h"
#include "Matrix.h"
#include "Program.h"
#include "Rect.h"
#include "SkiaShader.h"
#include "utils/Macros.h"

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <SkXfermode.h>

namespace android {
namespace uirenderer {

class Program;
class RoundRectClipState;
class Texture;

/*
 * Enumerates optional vertex attributes
 *
 * Position is always enabled by MeshState, these other attributes
 * are enabled/disabled dynamically based on mesh content.
 */

namespace VertexAttribFlags {
    enum {
        // Mesh is pure x,y vertex pairs
        None = 0,
        // Mesh has texture coordinates embedded. Note that texture can exist without this flag
        // being set, if coordinates passed to sampler are determined another way.
        TextureCoord = 1 << 0,
        // Mesh has color embedded (to export to varying)
        Color = 1 << 1,
        // Mesh has alpha embedded (to export to varying)
        Alpha = 1 << 2,
    };
};

/*
 * Enumerates transform features
 */
namespace TransformFlags {
    enum {
        None = 0,

        // offset the eventual drawing matrix by a tiny amount to
        // disambiguate sampling patterns with non-AA rendering
        OffsetByFudgeFactor = 1 << 0,

        // Canvas transform isn't applied to the mesh at draw time,
        //since it's already built in.
        MeshIgnoresCanvasTransform = 1 << 1, // TODO: remove for HWUI_NEW_OPS
    };
};

/**
 * Structure containing all data required to issue an OpenGL draw
 *
 * Includes all of the mesh, fill, and GL state required to perform
 * the operation. Pieces of data are either directly copied into the
 * structure, or stored as a pointer or GL object reference to data
 * managed.
 *
 * Eventually, a Glop should be able to be drawn multiple times from
 * a single construction, up until GL context destruction. Currently,
 * vertex/index/Texture/RoundRectClipState pointers prevent this from
 * being safe.
 */
struct Glop {
    PREVENT_COPY_AND_ASSIGN(Glop);
public:
    Glop() { }
    struct Mesh {
        GLuint primitiveMode; // GL_TRIANGLES and GL_TRIANGLE_STRIP supported

        // buffer object and void* are mutually exclusive.
        // Only GL_UNSIGNED_SHORT supported.
        struct Indices {
            GLuint bufferObject;
            const void* indices;
        } indices;

        // buffer object and void*s are mutually exclusive.
        // TODO: enforce mutual exclusion with restricted setters and/or unions
        struct Vertices {
            GLuint bufferObject;
            int attribFlags;
            const void* position;
            const void* texCoord;
            const void* color;
            GLsizei stride;
        } vertices;

        int elementCount;
        TextureVertex mappedVertices[4];
    } mesh;

    struct Fill {
        Program* program;

        struct TextureData {
            Texture* texture;
            GLenum target;
            GLenum filter;
            GLenum clamp;
            Matrix4* textureTransform;
        } texture;

        bool colorEnabled;
        FloatColor color;

        ProgramDescription::ColorFilterMode filterMode;
        union Filter {
            struct Matrix {
                float matrix[16];
                float vector[4];
            } matrix;
            FloatColor color;
        } filter;

        SkiaShaderData skiaShaderData;
    } fill;

    struct Transform {
        // modelView transform, accounting for delta between mesh transform and content of the mesh
        // often represents x/y offsets within command, or scaling for mesh unit size
        Matrix4 modelView;

        // Canvas transform of Glop - not necessarily applied to geometry (see flags)
        Matrix4 canvas;
        int transformFlags;

       const Matrix4& meshTransform() const {
           return (transformFlags & TransformFlags::MeshIgnoresCanvasTransform)
                   ? Matrix4::identity() : canvas;
       }
    } transform;

    const RoundRectClipState* roundRectClipState = nullptr;

    /**
     * Blending to be used by this draw - both GL_NONE if blending is disabled.
     *
     * Defined by fill step, but can be force-enabled by presence of kAlpha_Attrib
     */
    struct Blend {
        GLenum src;
        GLenum dst;
    } blend;

#if !HWUI_NEW_OPS
    /**
     * Bounds of the drawing command in layer space. Only mapped into layer
     * space once GlopBuilder::build() is called.
     */
    Rect bounds; // TODO: remove for HWUI_NEW_OPS
#endif

    /**
     * Additional render state to enumerate:
     * - scissor + (bits for whether each of LTRB needed?)
     * - stencil mode (draw into, mask, count, etc)
     */
};

} /* namespace uirenderer */
} /* namespace android */

#endif // ANDROID_HWUI_GLOP_H
