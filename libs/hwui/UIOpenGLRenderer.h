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

namespace android {

class UIOpenGLRenderer {
public:
    UIOpenGLRenderer();
    ~UIOpenGLRenderer();

    void setViewport(int width, int height);
    void prepare();

private:
    float mOrthoMatrix[16];

};

}; // namespace android

#endif // ANDROID_UI_OPENGL_RENDERER_H
