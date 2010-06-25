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

#define LOG_TAG "OpenGLRenderer"

#include <stdlib.h>
#include <stdint.h>
#include <sys/types.h>

#include <utils/Errors.h>
#include <utils/KeyedVector.h>
#include <utils/Log.h>

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <SkXfermode.h>

#include "OpenGLRenderer.h"
#include "Matrix.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

#define SOLID_WHITE { 1.0f, 1.0f, 1.0f, 1.0f }

#define P(x, y) { x, y }

///////////////////////////////////////////////////////////////////////////////
// Globals
///////////////////////////////////////////////////////////////////////////////

const Vertex gDrawColorVertices[] = {
		{ P(0.0f, 0.0f), SOLID_WHITE },
		{ P(1.0f, 0.0f), SOLID_WHITE },
		{ P(0.0f, 1.0f), SOLID_WHITE },
		{ P(1.0f, 1.0f), SOLID_WHITE }
};

///////////////////////////////////////////////////////////////////////////////
// Shaders
///////////////////////////////////////////////////////////////////////////////

#define SHADER_SOURCE(name, source) const char* name = #source

#include "shaders/drawColor.vert"
#include "shaders/drawColor.frag"

Program::Program(const char* vertex, const char* fragment) {
	vertexShader = buildShader(vertex, GL_VERTEX_SHADER);
	fragmentShader = buildShader(fragment, GL_FRAGMENT_SHADER);

	id = glCreateProgram();
	glAttachShader(id, vertexShader);
	glAttachShader(id, fragmentShader);
	glLinkProgram(id);

	GLint status;
	glGetProgramiv(id, GL_LINK_STATUS, &status);
	if (status != GL_TRUE) {
		GLint infoLen = 0;
		glGetProgramiv(id, GL_INFO_LOG_LENGTH, &infoLen);
		if (infoLen > 1) {
			char* log = (char*) malloc(sizeof(char) * infoLen);
			glGetProgramInfoLog(id, infoLen, 0, log);
			LOGE("Error while linking shaders: %s", log);
			delete log;
		}
		glDeleteProgram(id);
	}
}

Program::~Program() {
	glDeleteShader(vertexShader);
	glDeleteShader(fragmentShader);
	glDeleteProgram(id);
}

void Program::use() {
	glUseProgram(id);
}

int Program::addAttrib(const char* name) {
	int slot = glGetAttribLocation(id, name);
	attributes.add(name, slot);
	return slot;
}

int Program::getAttrib(const char* name) {
	return attributes.valueFor(name);
}

int Program::addUniform(const char* name) {
	int slot = glGetUniformLocation(id, name);
	uniforms.add(name, slot);
	return slot;
}

int Program::getUniform(const char* name) {
	return uniforms.valueFor(name);
}

GLuint Program::buildShader(const char* source, GLenum type) {
	GLuint shader = glCreateShader(type);
	glShaderSource(shader, 1, &source, 0);
	glCompileShader(shader);

	GLint status;
	glGetShaderiv(shader, GL_COMPILE_STATUS, &status);
	if (status != GL_TRUE) {
		// Some drivers return wrong values for GL_INFO_LOG_LENGTH
		// use a fixed size instead
		GLchar log[512];
		glGetShaderInfoLog(shader, sizeof(log), 0, &log[0]);
		LOGE("Error while compiling shader: %s", log);
		glDeleteShader(shader);
	}

	return shader;
}

DrawColorProgram::DrawColorProgram():
		Program(gDrawColorVertexShader, gDrawColorFragmentShader) {
	position = addAttrib("position");
	color = addAttrib("color");
	projection = addUniform("projection");
	modelView = addUniform("modelView");
}

///////////////////////////////////////////////////////////////////////////////
// Support
///////////////////////////////////////////////////////////////////////////////

const Rect& Snapshot::getMappedClip() {
	if (flags & kFlagDirtyTransform) {
		flags &= ~kFlagDirtyTransform;
		mappedClip.set(clipRect);
		transform.mapRect(mappedClip);
	}
	return mappedClip;
}

///////////////////////////////////////////////////////////////////////////////
// Constructors/destructor
///////////////////////////////////////////////////////////////////////////////

OpenGLRenderer::OpenGLRenderer() {
    LOGD("Create OpenGLRenderer");

    mDrawColorShader = new DrawColorProgram;
}

OpenGLRenderer::~OpenGLRenderer() {
    LOGD("Destroy OpenGLRenderer");
}

///////////////////////////////////////////////////////////////////////////////
// Setup
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::setViewport(int width, int height) {
    glViewport(0, 0, width, height);

    mat4 ortho;
    ortho.loadOrtho(0, width, height, 0, 0, 1);
    ortho.copyTo(mOrthoMatrix);

    mWidth = width;
    mHeight = height;
}

void OpenGLRenderer::prepare() {
	mSnapshot = &mFirstSnapshot;
	mSaveCount = 0;

    glDisable(GL_SCISSOR_TEST);

    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    glEnable(GL_SCISSOR_TEST);

    mSnapshot->clipRect.set(0.0f, 0.0f, mWidth, mHeight);
}

///////////////////////////////////////////////////////////////////////////////
// State management
///////////////////////////////////////////////////////////////////////////////

int OpenGLRenderer::getSaveCount() const {
	return mSaveCount;
}

int OpenGLRenderer::save(int flags) {
	return saveSnapshot();
}

void OpenGLRenderer::restore() {
	if (mSaveCount == 0) return;

	if (restoreSnapshot()) {
		setScissorFromClip();
	}
}

void OpenGLRenderer::restoreToCount(int saveCount) {
	if (saveCount <= 0 || saveCount > mSaveCount) return;

	bool restoreClip = false;

	while (mSaveCount != saveCount - 1) {
		restoreClip |= restoreSnapshot();
	}

	if (restoreClip) {
		setScissorFromClip();
	}
}

int OpenGLRenderer::saveSnapshot() {
	mSnapshot = new Snapshot(mSnapshot);
	return ++mSaveCount;
}

bool OpenGLRenderer::restoreSnapshot() {
	bool restoreClip = mSnapshot->flags & Snapshot::kFlagClipSet;

	mSaveCount--;

	// Do not merge these two lines!
	sp<Snapshot> previous = mSnapshot->previous;
	mSnapshot = previous;

	return restoreClip;
}

///////////////////////////////////////////////////////////////////////////////
// Transforms
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::translate(float dx, float dy) {
	mSnapshot->transform.translate(dx, dy, 0.0f);
	mSnapshot->flags |= Snapshot::kFlagDirtyTransform;
}

void OpenGLRenderer::rotate(float degrees) {
	mSnapshot->transform.rotate(degrees, 0.0f, 0.0f, 1.0f);
	mSnapshot->flags |= Snapshot::kFlagDirtyTransform;
}

void OpenGLRenderer::scale(float sx, float sy) {
	mSnapshot->transform.scale(sx, sy, 1.0f);
	mSnapshot->flags |= Snapshot::kFlagDirtyTransform;
}

void OpenGLRenderer::setMatrix(SkMatrix* matrix) {
	mSnapshot->transform.load(*matrix);
	mSnapshot->flags |= Snapshot::kFlagDirtyTransform;
}

void OpenGLRenderer::getMatrix(SkMatrix* matrix) {
	mSnapshot->transform.copyTo(*matrix);
}

void OpenGLRenderer::concatMatrix(SkMatrix* matrix) {
	mat4 m(*matrix);
	mSnapshot->transform.multiply(m);
	mSnapshot->flags |= Snapshot::kFlagDirtyTransform;
}

///////////////////////////////////////////////////////////////////////////////
// Clipping
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::setScissorFromClip() {
	const Rect& clip = mSnapshot->getMappedClip();
	glScissor(clip.left, clip.top, clip.getWidth(), clip.getHeight());
}

const Rect& OpenGLRenderer::getClipBounds() {
	return mSnapshot->clipRect;
}

bool OpenGLRenderer::clipRect(float left, float top, float right, float bottom) {
	bool clipped = mSnapshot->clipRect.intersect(left, top, right, bottom);
	if (clipped) {
		mSnapshot->flags |= Snapshot::kFlagClipSet;
		setScissorFromClip();
	}
	return clipped;
}

///////////////////////////////////////////////////////////////////////////////
// Drawing
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::drawColor(int color, SkXfermode::Mode mode) {
	GLfloat a = ((color >> 24) & 0xFF) / 255.0f;
	GLfloat r = ((color >> 16) & 0xFF) / 255.0f;
	GLfloat g = ((color >>  8) & 0xFF) / 255.0f;
	GLfloat b = ((color      ) & 0xFF) / 255.0f;

	// TODO Optimize this section
	const Rect& clip = mSnapshot->getMappedClip();

	mat4 modelView;
	modelView.loadScale(clip.getWidth(), clip.getHeight(), 1.0f);
	modelView.translate(clip.left, clip.top, 0.0f);

	float matrix[16];
	modelView.copyTo(matrix);
	// TODO Optimize this section

	mDrawColorShader->use();

	glUniformMatrix4fv(mDrawColorShader->projection, 1, GL_FALSE, &mOrthoMatrix[0]);
	glUniformMatrix4fv(mDrawColorShader->modelView, 1, GL_FALSE, &matrix[0]);

	glEnableVertexAttribArray(mDrawColorShader->position);

	GLsizei stride = sizeof(Vertex);
	const GLvoid* p = &gDrawColorVertices[0].position[0];

	glVertexAttribPointer(mDrawColorShader->position, 2, GL_FLOAT, GL_FALSE, stride, p);
	glVertexAttrib4f(mDrawColorShader->color, r, g, b, a);

	GLsizei vertexCount = sizeof(gDrawColorVertices) / sizeof(Vertex);
	glDrawArrays(GL_TRIANGLE_STRIP, 0, vertexCount);

	glDisableVertexAttribArray(mDrawColorShader->position);
	glDisableVertexAttribArray(mDrawColorShader->color);
}

}; // namespace uirenderer
}; // namespace android
