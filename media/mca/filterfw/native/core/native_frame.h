/*
 * Copyright (C) 2011 The Android Open Source Project
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

#ifndef ANDROID_FILTERFW_CORE_NATIVE_FRAME_H
#define ANDROID_FILTERFW_CORE_NATIVE_FRAME_H

#include "base/utilities.h"

namespace android {
namespace filterfw {

// A NativeFrame stores data in a memory buffer (on the heap). It is used for
// data processing on the CPU.
class NativeFrame {
  public:
    // Create an empty native frame.
    explicit NativeFrame(int size);

    ~NativeFrame();

    // Set the frame data and size in bytes. The NativeFrame object takes ownership of the data.
    // To copy data into an existing frame, use WriteData().
    bool SetData(uint8_t* data, int size);

    // Write the specified data of the given size to the frame at the specified offset. The
    // receiver must be large enough to hold the data.
    bool WriteData(const uint8_t* data, int offset, int size);

    // Returns a pointer to the data, or NULL if no data was set.
    const uint8_t* Data() const {
      return data_;
    }

    // Returns a non-const pointer to the data, or NULL if no data was set.
    uint8_t* MutableData() {
      return data_;
    }

    // Resize the frame. You can only resize to a size that fits within the frame's capacity.
    // Returns true if the resize was successful.
    bool Resize(int newSize);

    // Returns the size of the frame in bytes.
    int Size() {
      return size_;
    }

    // Returns the capacity of the frame in bytes.
    int Capacity() {
      return capacity_;
    }

    // Returns a new native frame
    NativeFrame* Clone() const;

  private:
    // Pointer to the data. Owned by the frame.
    uint8_t* data_;

    // Size of data buffer in bytes.
    int size_;

    // Capacity of data buffer in bytes.
    int capacity_;

    NativeFrame(const NativeFrame&) = delete;
    NativeFrame& operator=(const NativeFrame&) = delete;
};

} // namespace filterfw
} // namespace android

#endif  // ANDROID_FILTERFW_CORE_NATIVE_FRAME_H
