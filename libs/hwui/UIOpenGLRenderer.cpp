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

#define LOG_TAG "UIOpenGLRenderer"

#include <stdlib.h>
#include <stdint.h>
#include <sys/types.h>

#include <utils/Errors.h>
#include <utils/Log.h>

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include "UIOpenGLRenderer.h"

namespace android {

UIOpenGLRenderer::UIOpenGLRenderer() {
    LOGD("Create UIOpenGLRenderer");
}

UIOpenGLRenderer::~UIOpenGLRenderer() {
    LOGD("Destroy UIOpenGLRenderer");
}

void UIOpenGLRenderer::setViewport(int width, int height) {
    LOGD("Setting viewport");
}

void UIOpenGLRenderer::prepare() {
    LOGD("Prepare");
}

}; // namespace android
