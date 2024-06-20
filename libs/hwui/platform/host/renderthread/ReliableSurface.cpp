/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include "renderthread/ReliableSurface.h"

#include <log/log_main.h>
#include <system/window.h>

namespace android::uirenderer::renderthread {

ReliableSurface::ReliableSurface(ANativeWindow* window) : mWindow(window) {
    LOG_ALWAYS_FATAL_IF(!mWindow, "Error, unable to wrap a nullptr");
    ANativeWindow_acquire(mWindow);
}

ReliableSurface::~ReliableSurface() {
    ANativeWindow_release(mWindow);
}

void ReliableSurface::init() {}

int ReliableSurface::reserveNext() {
    return OK;
}

};  // namespace android::uirenderer::renderthread
