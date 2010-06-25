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

#ifndef ANDROID_UI_OPENGL_RENDERER_H
#define ANDROID_UI_OPENGL_RENDERER_H

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <SkMatrix.h>
#include <SkXfermode.h>

#include <utils/KeyedVector.h>
#include <utils/RefBase.h>

#include "Matrix.h"
#include "Rect.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Support
///////////////////////////////////////////////////////////////////////////////

class Snapshot: public LightRefBase<Snapshot> {
public:
	Snapshot() {
	}

	Snapshot(const sp<Snapshot> s):
			transform(s->transform),
			clipRect(s->clipRect),
			flags(kFlagDirtyTransform),
			previous(s) {
	}

	enum Flags {
		kFlagClipSet = 0x1,
		kFlagDirtyTransform = 0x2,
	};

	const Rect& getMappedClip();

	// Local transformations
	mat4 transform;

	// Clipping rectangle at the time of this snapshot
	Rect clipRect;

	// Dirty flags
	int flags;

	// Previous snapshot in the frames stack
	sp<Snapshot> previous;

private:
	// Clipping rectangle mapped with the transform
	Rect mappedClip;
}; // class Snapshot

struct Vertex {
	float position[2];
	float color[4];
}; // struct Vertex

typedef char* shader;

class Program: public LightRefBase<Program> {
public:
	Program(const char* vertex, const char* fragment);
	~Program();

	void use();

protected:
	int addAttrib(const char* name);
	int getAttrib(const char* name);

	int addUniform(const char* name);
	int getUniform(const char* name);

private:
	GLuint buildShader(const char* source, GLenum type);

	// Handle of the OpenGL program
	GLuint id;

	// Handles of the shaders
	GLuint vertexShader;
	GLuint fragmentShader;

	// Keeps track of attributes and uniforms slots
	KeyedVector<const char*, int> attributes;
	KeyedVector<const char*, int> uniforms;
}; // class Program

class DrawColorProgram: public Program {
public:
	DrawColorProgram();

	int position;
	int color;

	int projection;
	int modelView;
};

///////////////////////////////////////////////////////////////////////////////
// Renderer
///////////////////////////////////////////////////////////////////////////////

class OpenGLRenderer {
public:
    OpenGLRenderer();
    ~OpenGLRenderer();

    void setViewport(int width, int height);
    void prepare();

    int getSaveCount() const;
    int save(int flags);
    void restore();
    void restoreToCount(int saveCount);

    void translate(float dx, float dy);
    void rotate(float degrees);
    void scale(float sx, float sy);

    void setMatrix(SkMatrix* matrix);
    void getMatrix(SkMatrix* matrix);
    void concatMatrix(SkMatrix* matrix);

    const Rect& getClipBounds();
    bool clipRect(float left, float top, float right, float bottom);

    void drawColor(int color, SkXfermode::Mode mode);

private:
    int saveSnapshot();
    bool restoreSnapshot();

    void setScissorFromClip();

    // Dimensions of the drawing surface
    int mWidth, mHeight;

    // Matrix used for ortho projection in shaders
    float mOrthoMatrix[16];

    // Number of saved states
    int mSaveCount;
    // Base state
    Snapshot mFirstSnapshot;
    // Current state
    sp<Snapshot> mSnapshot;

    // Shaders
    sp<DrawColorProgram> mDrawColorShader;
}; // class OpenGLRenderer

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_UI_OPENGL_RENDERER_H
