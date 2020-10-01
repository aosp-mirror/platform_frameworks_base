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

#include <hwui/Canvas.h>

#include <jni.h>

// Creates a Java Canvas object from canvas, calls jimageDecoder's PostProcess on it, and then
// releases the Canvas.
// Caller needs to check for exceptions.
jint postProcessAndRelease(JNIEnv* env, jobject jimageDecoder,
                           std::unique_ptr<android::Canvas> canvas);
