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

#include "graphic_buffer_impl.h"

#include <utils/Errors.h>

namespace android {

GraphicBufferImpl::GraphicBufferImpl(uint32_t w, uint32_t h)
  : mBuffer(new android::GraphicBuffer(w, h, PIXEL_FORMAT_RGBA_8888,
       android::GraphicBuffer::USAGE_HW_TEXTURE |
       android::GraphicBuffer::USAGE_SW_READ_OFTEN |
       android::GraphicBuffer::USAGE_SW_WRITE_OFTEN)) {
}

GraphicBufferImpl::~GraphicBufferImpl() {
}

// static
long GraphicBufferImpl::Create(int w, int h) {
  GraphicBufferImpl* buffer = new GraphicBufferImpl(
      static_cast<uint32_t>(w), static_cast<uint32_t>(h));
  if (buffer->InitCheck() != NO_ERROR) {
    delete buffer;
    return 0;
  }
  return reinterpret_cast<intptr_t>(buffer);
}

// static
void GraphicBufferImpl::Release(long buffer_id) {
  GraphicBufferImpl* buffer = reinterpret_cast<GraphicBufferImpl*>(buffer_id);
  delete buffer;
}

// static
int GraphicBufferImpl::MapStatic(long buffer_id, AwMapMode mode, void** vaddr) {
  GraphicBufferImpl* buffer = reinterpret_cast<GraphicBufferImpl*>(buffer_id);
  return buffer->Map(mode, vaddr);
}

// static
int GraphicBufferImpl::UnmapStatic(long buffer_id) {
  GraphicBufferImpl* buffer = reinterpret_cast<GraphicBufferImpl*>(buffer_id);
  return buffer->Unmap();
}

// static
void* GraphicBufferImpl::GetNativeBufferStatic(long buffer_id) {
  GraphicBufferImpl* buffer = reinterpret_cast<GraphicBufferImpl*>(buffer_id);
  return buffer->GetNativeBuffer();
}

// static
uint32_t GraphicBufferImpl::GetStrideStatic(long buffer_id) {
  GraphicBufferImpl* buffer = reinterpret_cast<GraphicBufferImpl*>(buffer_id);
  return buffer->GetStride();
}

status_t GraphicBufferImpl::Map(AwMapMode mode, void** vaddr) {
  int usage = 0;
  switch (mode) {
    case MAP_READ_ONLY:
      usage = android::GraphicBuffer::USAGE_SW_READ_OFTEN;
      break;
    case MAP_WRITE_ONLY:
      usage = android::GraphicBuffer::USAGE_SW_WRITE_OFTEN;
      break;
    case MAP_READ_WRITE:
      usage = android::GraphicBuffer::USAGE_SW_READ_OFTEN |
          android::GraphicBuffer::USAGE_SW_WRITE_OFTEN;
      break;
    default:
      return INVALID_OPERATION;
  }
  return mBuffer->lock(usage, vaddr);
}

status_t GraphicBufferImpl::Unmap() {
  return mBuffer->unlock();
}

status_t GraphicBufferImpl::InitCheck() const {
  return mBuffer->initCheck();
}

void* GraphicBufferImpl::GetNativeBuffer() const {
  return mBuffer->getNativeBuffer();
}

uint32_t GraphicBufferImpl::GetStride() const {
  static const int kBytesPerPixel = 4;
  return mBuffer->getStride() * kBytesPerPixel;
}

} // namespace android
