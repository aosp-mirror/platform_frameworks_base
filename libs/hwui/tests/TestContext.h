/*
 * Copyright (C) 2014 The Android Open Source Project
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

#ifndef TESTCONTEXT_H
#define TESTCONTEXT_H

#include <ui/DisplayInfo.h>
#include <gui/SurfaceControl.h>

extern android::DisplayInfo gDisplay;
#define dp(x) ((x) * gDisplay.density)

// Initializes all the static globals that are shared across all contexts
// such as display info
void createTestEnvironment();

// Defaults to fullscreen
android::sp<android::SurfaceControl> createWindow(int width = -1, int height = -1);

#endif
