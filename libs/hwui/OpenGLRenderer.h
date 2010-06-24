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

#ifndef ANDROID_OPENGL_RENDERER_H
#define ANDROID_OPENGL_RENDERER_H

#include <SkMatrix.h>
#include <SkXfermode.h>

#include <utils/RefBase.h>

#include "Matrix.h"
#include "Rect.h"

namespace android {

///////////////////////////////////////////////////////////////////////////////
// Support
///////////////////////////////////////////////////////////////////////////////

class Snapshot: public LightRefBase<Snapshot> {
public:
	Snapshot() {
	}

	Snapshot(const sp<Snapshot> s): transform(s->transform), clipRect(s->clipRect),
				flags(0), previous(s) {
	}

	enum Flags {
		kFlagClipSet = 0x1,
	};

	// Local transformations
	mat4 transform;

	// Clipping rectangle at the time of this snapshot
	Rect clipRect;

	// Dirty flags
	int flags;

	// Previous snapshot in the frames stack
	sp<Snapshot> previous;
}; // struct Snapshot

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
}; // class OpenGLRenderer

}; // namespace android

#endif // ANDROID_OPENGL_RENDERER_H
