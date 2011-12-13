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

#ifndef ANDROID_HWUI_PROGRAM_H
#define ANDROID_HWUI_PROGRAM_H

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <utils/KeyedVector.h>

#include "Matrix.h"

namespace android {
namespace uirenderer {

/**
 * A program holds a vertex and a fragment shader. It offers several utility
 * methods to query attributes and uniforms.
 */
class Program {
public:
    enum ShaderBindings {
        kBindingPosition,
        kBindingTexCoords
    };

    /**
     * Creates a new program with the specified vertex and fragment
     * shaders sources.
     */
    Program(const char* vertex, const char* fragment);
    virtual ~Program();

    /**
     * Binds this program to the GL context.
     */
    virtual void use();

    /**
     * Marks this program as unused. This will not unbind
     * the program from the GL context.
     */
    virtual void remove();

    /**
     * Returns the OpenGL name of the specified attribute.
     */
    int getAttrib(const char* name);

    /**
     * Returns the OpenGL name of the specified uniform.
     */
    int getUniform(const char* name);

    /**
     * Indicates whether this program is currently in use with
     * the GL context.
     */
    inline bool isInUse() const {
        return mUse;
    }

    /**
     * Indicates whether this program was correctly compiled and linked.
     */
    inline bool isInitialized() const {
        return mInitialized;
    }

    /**
     * Binds the program with the specified projection, modelView and
     * transform matrices.
     */
    void set(const mat4& projectionMatrix, const mat4& modelViewMatrix,
             const mat4& transformMatrix, bool offset = false);

    /**
     * Sets the color associated with this shader.
     */
    void setColor(const float r, const float g, const float b, const float a);

    /**
     * Name of the position attribute.
     */
    int position;

    /**
     * Name of the transform uniform.
     */
    int transform;

protected:
    /**
     * Adds an attribute with the specified name.
     *
     * @return The OpenGL name of the attribute.
     */
    int addAttrib(const char* name);

    /**
     * Binds the specified attribute name to the specified slot.
     */
    int bindAttrib(const char* name, ShaderBindings bindingSlot);

    /**
     * Adds a uniform with the specified name.
     *
     * @return The OpenGL name of the uniform.
     */
    int addUniform(const char* name);

private:
    /**
     * Compiles the specified shader of the specified type.
     *
     * @return The name of the compiled shader.
     */
    GLuint buildShader(const char* source, GLenum type);

    // Name of the OpenGL program and shaders
    GLuint mProgramId;
    GLuint mVertexShader;
    GLuint mFragmentShader;

    // Keeps track of attributes and uniforms slots
    KeyedVector<const char*, int> mAttributes;
    KeyedVector<const char*, int> mUniforms;

    bool mUse;
    bool mInitialized;

    bool mHasColorUniform;
    int mColorUniform;
}; // class Program

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_PROGRAM_H
