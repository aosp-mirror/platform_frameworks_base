/*
 * Copyright (C) 2016 The Android Open Source Project
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

#ifndef AAPT_IO_IO_H
#define AAPT_IO_IO_H

#include <string>

namespace aapt {
namespace io {

// InputStream interface that mimics protobuf's ZeroCopyInputStream,
// with added error handling methods to better report issues.
class InputStream {
 public:
  virtual ~InputStream() = default;

  // Returns a chunk of data for reading. data and size must not be nullptr.
  // Returns true so long as there is more data to read, returns false if an error occurred
  // or no data remains. If an error occurred, check HadError().
  // The stream owns the buffer returned from this method and the buffer is invalidated
  // anytime another mutable method is called.
  virtual bool Next(const void** data, size_t* size) = 0;

  // Backup count bytes, where count is smaller or equal to the size of the last buffer returned
  // from Next().
  // Useful when the last block returned from Next() wasn't fully read.
  virtual void BackUp(size_t count) = 0;

  // Returns true if this InputStream can rewind. If so, Rewind() can be called.
  virtual bool CanRewind() const { return false; };

  // Rewinds the stream to the beginning so it can be read again.
  // Returns true if the rewind succeeded.
  // This does nothing if CanRewind() returns false.
  virtual bool Rewind() { return false; }

  // Returns the number of bytes that have been read from the stream.
  virtual size_t ByteCount() const = 0;

  // Returns an error message if HadError() returned true.
  virtual std::string GetError() const { return {}; }

  // Returns true if an error occurred. Errors are permanent.
  virtual bool HadError() const = 0;
};

// A sub-InputStream interface that knows the total size of its stream.
class KnownSizeInputStream : public InputStream {
 public:
  virtual size_t TotalSize() const = 0;
};

// OutputStream interface that mimics protobuf's ZeroCopyOutputStream,
// with added error handling methods to better report issues.
class OutputStream {
 public:
  virtual ~OutputStream() = default;

  // Returns a buffer to which data can be written to. The data written to this buffer will
  // eventually be written to the stream. Call BackUp() if the data written doesn't occupy the
  // entire buffer.
  // Return false if there was an error.
  // The stream owns the buffer returned from this method and the buffer is invalidated
  // anytime another mutable method is called.
  virtual bool Next(void** data, size_t* size) = 0;

  // Backup count bytes, where count is smaller or equal to the size of the last buffer returned
  // from Next().
  // Useful for when the last block returned from Next() wasn't fully written to.
  virtual void BackUp(size_t count) = 0;

  // Returns the number of bytes that have been written to the stream.
  virtual size_t ByteCount() const = 0;

  // Returns an error message if HadError() returned true.
  virtual std::string GetError() const { return {}; }

  // Returns true if an error occurred. Errors are permanent.
  virtual bool HadError() const = 0;
};

}  // namespace io
}  // namespace aapt

#endif /* AAPT_IO_IO_H */
