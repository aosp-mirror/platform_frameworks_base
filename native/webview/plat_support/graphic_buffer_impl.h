/*
 * Copyright (C) 2013 The Android Open Source Project
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

// Provides the implementation of the GraphicBuffer interface in
// renderer compostior

#ifndef ANDROID_GRAPHIC_BUFFER_IMPL_H
#define ANDROID_GRAPHIC_BUFFER_IMPL_H

#include <ui/GraphicBuffer.h>

#include "draw_gl.h"

namespace android {

class GraphicBufferImpl {
 public:
  ~GraphicBufferImpl();

  static long Create(int w, int h);
  static void Release(long buffer_id);
  static int MapStatic(long buffer_id, AwMapMode mode, void** vaddr);
  static int UnmapStatic(long buffer_id);
  static void* GetNativeBufferStatic(long buffer_id);
  static uint32_t GetStrideStatic(long buffer_id);

 private:
  status_t Map(AwMapMode mode, void** vaddr);
  status_t Unmap();
  status_t InitCheck() const;
  void* GetNativeBuffer() const;
  uint32_t GetStride() const;
  GraphicBufferImpl(uint32_t w, uint32_t h);

  sp<android::GraphicBuffer> mBuffer;
};

}  // namespace android

#endif  // ANDROID_GRAPHIC_BUFFER_IMPL_H
