/*
 * Copyright (C) 2019 The Android Open Source Project
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
#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>

#include <errno.h>
#include <fcntl.h>
#include <linux/fiemap.h>
#include <linux/fs.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <android-base/unique_fd.h>

// This program modifies a file at given offset, but directly against the block
// device, purposely to bypass the filesystem. Note that the change on block
// device may not reflect the same way when read from filesystem, for example,
// when the file is encrypted on disk.
//
// Only one byte is supported for now just so that we don't need to handle the
// case when the range crosses different "extents".
//
// References:
//  https://www.kernel.org/doc/Documentation/filesystems/fiemap.txt
//  https://git.kernel.org/pub/scm/fs/xfs/xfsprogs-dev.git/tree/io/fiemap.c

#ifndef F2FS_IOC_SET_PIN_FILE
#ifndef F2FS_IOCTL_MAGIC
#define F2FS_IOCTL_MAGIC 0xf5
#endif
#define F2FS_IOC_SET_PIN_FILE _IOW(F2FS_IOCTL_MAGIC, 13, __u32)
#define F2FS_IOC_GET_PIN_FILE _IOR(F2FS_IOCTL_MAGIC, 14, __u32)
#endif

struct Args {
  const char* block_device;
  const char* file_name;
  uint64_t byte_offset;
  bool use_f2fs_pinning;
};

class ScopedF2fsFilePinning {
 public:
  explicit ScopedF2fsFilePinning(const char* file_path) {
    fd_.reset(TEMP_FAILURE_RETRY(open(file_path, O_WRONLY | O_CLOEXEC, 0)));
    if (fd_.get() == -1) {
      perror("Failed to open");
      return;
    }
    __u32 set = 1;
    ioctl(fd_.get(), F2FS_IOC_SET_PIN_FILE, &set);
  }

  ~ScopedF2fsFilePinning() {
    __u32 set = 0;
    ioctl(fd_.get(), F2FS_IOC_SET_PIN_FILE, &set);
  }

 private:
  android::base::unique_fd fd_;
};

ssize_t get_logical_block_size(const char* block_device) {
  android::base::unique_fd fd(open(block_device, O_RDONLY));
  if (fd.get() < 0) {
    fprintf(stderr, "open %s failed\n", block_device);
    return -1;
  }

  int size;
  if (ioctl(fd, BLKSSZGET, &size) < 0) {
    fprintf(stderr, "ioctl(BLKSSZGET) failed: %s\n", strerror(errno));
    return -1;
  }
  return size;
}

int64_t get_physical_offset(const char* file_name, uint64_t byte_offset) {
  android::base::unique_fd fd(open(file_name, O_RDONLY));
  if (fd.get() < 0) {
    fprintf(stderr, "open %s failed\n", file_name);
    return -1;
  }

  const int map_size = sizeof(struct fiemap) + sizeof(struct fiemap_extent);
  char fiemap_buffer[map_size] = {0};
  struct fiemap* fiemap = reinterpret_cast<struct fiemap*>(&fiemap_buffer);

  fiemap->fm_flags = FIEMAP_FLAG_SYNC;
  fiemap->fm_start = byte_offset;
  fiemap->fm_length = 1;
  fiemap->fm_extent_count = 1;

  int ret = ioctl(fd.get(), FS_IOC_FIEMAP, fiemap);
  if (ret < 0) {
    fprintf(stderr, "ioctl(FS_IOC_FIEMAP) failed: %s\n", strerror(errno));
    return -1;
  }

  if (fiemap->fm_mapped_extents != 1) {
    fprintf(stderr, "fm_mapped_extents != 1 (is %d)\n",
            fiemap->fm_mapped_extents);
    return -1;
  }

  struct fiemap_extent* extent = &fiemap->fm_extents[0];
  printf(
      "logical offset: %llu, physical offset: %llu, length: %llu, "
      "flags: %x\n",
      extent->fe_logical, extent->fe_physical, extent->fe_length,
      extent->fe_flags);
  if (extent->fe_flags & (FIEMAP_EXTENT_UNKNOWN |
                          FIEMAP_EXTENT_UNWRITTEN)) {
    fprintf(stderr, "Failed to locate physical offset safely\n");
    return -1;
  }

  return extent->fe_physical + (byte_offset - extent->fe_logical);
}

int read_block_from_device(const char* device_path, uint64_t block_offset,
                           ssize_t block_size, char* block_buffer) {
  assert(block_offset % block_size == 0);
  android::base::unique_fd fd(open(device_path, O_RDONLY | O_DIRECT));
  if (fd.get() < 0) {
    fprintf(stderr, "open %s failed\n", device_path);
    return -1;
  }

  ssize_t retval =
      TEMP_FAILURE_RETRY(pread(fd, block_buffer, block_size, block_offset));
  if (retval != block_size) {
    fprintf(stderr, "read returns error or incomplete result (%zu): %s\n",
            retval, strerror(errno));
    return -1;
  }
  return 0;
}

int write_block_to_device(const char* device_path, uint64_t block_offset,
                          ssize_t block_size, char* block_buffer) {
  assert(block_offset % block_size == 0);
  android::base::unique_fd fd(open(device_path, O_WRONLY | O_DIRECT));
  if (fd.get() < 0) {
    fprintf(stderr, "open %s failed\n", device_path);
    return -1;
  }

  ssize_t retval = TEMP_FAILURE_RETRY(
      pwrite(fd.get(), block_buffer, block_size, block_offset));
  if (retval != block_size) {
    fprintf(stderr, "write returns error or incomplete result (%zu): %s\n",
            retval, strerror(errno));
    return -1;
  }
  return 0;
}

std::unique_ptr<Args> parse_args(int argc, const char** argv) {
  if (argc != 4 && argc != 5) {
    fprintf(stderr,
            "Usage: %s [--use-f2fs-pinning] block_dev filename byte_offset\n"
            "\n"
            "This program bypasses filesystem and damages the specified byte\n"
            "at the physical position on <block_dev> corresponding to the\n"
            "logical byte location in <filename>.\n",
            argv[0]);
    return nullptr;
  }

  auto args = std::make_unique<Args>();
  const char** arg = &argv[1];
  args->use_f2fs_pinning = strcmp(*arg, "--use-f2fs-pinning") == 0;
  if (args->use_f2fs_pinning) {
    ++arg;
  }
  args->block_device = *(arg++);
  args->file_name = *(arg++);
  args->byte_offset = strtoull(*arg, nullptr, 10);
  if (args->byte_offset == ULLONG_MAX) {
    perror("Invalid byte offset");
    return nullptr;
  }
  return args;
}

int main(int argc, const char** argv) {
  std::unique_ptr<Args> args = parse_args(argc, argv);
  if (args == nullptr) {
    return -1;
  }

  ssize_t block_size = get_logical_block_size(args->block_device);
  if (block_size < 0) {
    return -1;
  }

  std::unique_ptr<ScopedF2fsFilePinning> pinned_file;
  if (args->use_f2fs_pinning) {
    pinned_file = std::make_unique<ScopedF2fsFilePinning>(args->file_name);
  }

  int64_t physical_offset_signed = get_physical_offset(args->file_name, args->byte_offset);
  if (physical_offset_signed < 0) {
    return -1;
  }

  uint64_t physical_offset = static_cast<uint64_t>(physical_offset_signed);
  uint64_t offset_within_block = physical_offset % block_size;
  uint64_t physical_block_offset = physical_offset - offset_within_block;

  // Direct I/O requires aligned buffer
  std::unique_ptr<char> buf(static_cast<char*>(
      aligned_alloc(block_size /* alignment */, block_size /* size */)));

  if (read_block_from_device(args->block_device, physical_block_offset, block_size,
                             buf.get()) < 0) {
    return -1;
  }
  char* p = buf.get() + offset_within_block;
  printf("before: %hhx\n", *p);
  *p ^= 0xff;
  printf("after: %hhx\n", *p);
  if (write_block_to_device(args->block_device, physical_block_offset, block_size,
                            buf.get()) < 0) {
    return -1;
  }

  return 0;
}
