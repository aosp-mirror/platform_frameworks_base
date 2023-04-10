/*
 * Copyright (C) 2023 The Android Open Source Project
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
#ifndef BUFFERUTILS_H_
#define BUFFERUTILS_H_

#include <jni.h>

#include <vector>

/**
 * Helper method to load a java.nio.Buffer instance into a vector. This handles
 * both direct and indirect buffers and promptly releases any critical arrays that
 * have been retrieved in order to avoid potential jni exceptions due to interleaved
 * jni calls between get/release primitive method invocations.
 */
std::vector<uint8_t> copyJavaNioBufferToVector(JNIEnv* env, jobject buffer, size_t size,
                                               jboolean isDirect);

#endif  // BUFFERUTILS_H_
