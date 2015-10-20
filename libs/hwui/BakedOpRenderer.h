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

#ifndef ANDROID_HWUI_BAKED_OP_RENDERER_H
#define ANDROID_HWUI_BAKED_OP_RENDERER_H

#include "BakedOpState.h"
#include "Matrix.h"

namespace android {
namespace uirenderer {

class Caches;
struct Glop;
class RenderState;

class BakedOpRenderer {
public:
    class Info {
    public:
        Info(Caches& caches, RenderState& renderState, int viewportWidth, int viewportHeight, bool opaque)
                : renderState(renderState)
                , caches(caches)
                , opaque(opaque)
                , viewportWidth(viewportWidth)
                , viewportHeight(viewportHeight) {
            orthoMatrix.loadOrtho(viewportWidth, viewportHeight);
        }

        Texture* getTexture(const SkBitmap* bitmap);

        void renderGlop(const BakedOpState& state, const Glop& glop);
        RenderState& renderState;
        Caches& caches;

        bool didDraw = false;
        bool opaque;


        // where should these live? layer state object?
        int viewportWidth;
        int viewportHeight;
        Matrix4 orthoMatrix;
    };

    static void startFrame(Info& info);
    static void endFrame(Info& info);

    /**
     * Declare all "onBitmapOp(...)" style function for every op type.
     *
     * These functions will perform the actual rendering of the individual operations in OpenGL,
     * given the transform/clip and other state built into the BakedOpState object passed in.
     */
    #define BAKED_OP_RENDERER_METHOD(Type) static void on##Type(Info& info, const Type& op, const BakedOpState& state);
    MAP_OPS(BAKED_OP_RENDERER_METHOD);
};

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_BAKED_OP_RENDERER_H
