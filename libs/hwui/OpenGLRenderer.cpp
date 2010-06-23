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
#include <utils/Log.h>

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <SkXfermode.h>

#include "OpenGLRenderer.h"
#include "Matrix.h"

namespace android {

OpenGLRenderer::OpenGLRenderer() {
    LOGD("Create OpenGLRenderer");

    mSnapshot = new Snapshot;
    mSaveCount = 0;
}

OpenGLRenderer::~OpenGLRenderer() {
    LOGD("Destroy OpenGLRenderer");
}

void OpenGLRenderer::setViewport(int width, int height) {
    glViewport(0, 0, width, height);

    mat4 ortho;
    ortho.loadOrtho(0, width, height, 0, 0, 1);
    ortho.copyTo(mOrthoMatrix);

    mWidth = width;
    mHeight = height;
}

void OpenGLRenderer::prepare() {
    glDisable(GL_SCISSOR_TEST);

    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    glEnable(GL_SCISSOR_TEST);
    mSnapshot->clipRect.set(0.0f, 0.0f, mWidth, mHeight);
}

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
	mSaveCount++;
	return mSaveCount;
}

bool OpenGLRenderer::restoreSnapshot() {
	// TODO: handle local transformations
	bool restoreClip = mSnapshot->flags & Snapshot::kFlagClipSet;

	mSaveCount--;
	mSnapshot = mSnapshot->previous;

	return restoreClip;
}

void OpenGLRenderer::setScissorFromClip() {
	Rect clip = mSnapshot->clipRect;
	glScissor(clip.left, clip.top, clip.getWidth(), clip.getHeight());
}

bool OpenGLRenderer::clipRect(float left, float top, float right, float bottom) {
	// TODO: take local translate transform into account
	bool clipped = mSnapshot->clipRect.intersect(left, top, right, bottom);
	if (clipped) {
		mSnapshot->flags |= Snapshot::kFlagClipSet;
		setScissorFromClip();
	}
	return clipped;
}

void OpenGLRenderer::drawColor(int color, SkXfermode::Mode mode) {
	LOGD("Drawing color");
}

}; // namespace android
