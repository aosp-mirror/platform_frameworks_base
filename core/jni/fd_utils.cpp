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

#include "fd_utils.h"

#include <algorithm>
#include <utility>

#include <fcntl.h>
#include <grp.h>
#include <stdlib.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/un.h>
#include <unistd.h>

#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/stringprintf.h>
#include <android-base/strings.h>

// Static allowlist of open paths that the zygote is allowed to keep open.
static const char* kPathAllowlist[] = {
        "/dev/null",
        "/dev/socket/zygote",
        "/dev/socket/zygote_secondary",
        "/dev/socket/usap_pool_primary",
        "/dev/socket/usap_pool_secondary",
        "/dev/socket/webview_zygote",
        "/dev/socket/heapprofd",
        "/sys/kernel/debug/tracing/trace_marker",
        "/sys/kernel/tracing/trace_marker",
        "/system/framework/framework-res.apk",
        "/dev/urandom",
        "/dev/ion",
        "/dev/dri/renderD129", // Fixes b/31172436
        "/dev/stune/foreground/tasks",
        "/dev/blkio/tasks",
        "/metadata/aconfig/maps/system.package.map",
        "/metadata/aconfig/maps/system.flag.map",
        "/metadata/aconfig/boot/system.val"
};

static const char kFdPath[] = "/proc/self/fd";

FileDescriptorAllowlist* FileDescriptorAllowlist::Get() {
    if (instance_ == nullptr) {
        instance_ = new FileDescriptorAllowlist();
    }
    return instance_;
}

static bool IsArtMemfd(const std::string& path) {
  return android::base::StartsWith(path, "/memfd:/boot-image-methods.art");
}

bool FileDescriptorAllowlist::IsAllowed(const std::string& path) const {
    // Check the static allowlist path.
    for (const auto& allowlist_path : kPathAllowlist) {
        if (path == allowlist_path) return true;
    }

    // Check any paths added to the dynamic allowlist.
    for (const auto& allowlist_path : allowlist_) {
        if (path == allowlist_path) return true;
    }

    // Framework jars are allowed.
    static const char* kFrameworksPrefix[] = {
            "/system/framework/",
            "/system_ext/framework/",
    };

    static const char* kJarSuffix = ".jar";

    for (const auto& frameworks_prefix : kFrameworksPrefix) {
        if (android::base::StartsWith(path, frameworks_prefix) &&
            android::base::EndsWith(path, kJarSuffix)) {
            return true;
        }
    }

    // Jars from APEXes are allowed. This matches /apex/**/javalib/*.jar.
    static const char* kApexPrefix = "/apex/";
    static const char* kApexJavalibPathSuffix = "/javalib";
    if (android::base::StartsWith(path, kApexPrefix) && android::base::EndsWith(path, kJarSuffix) &&
        android::base::EndsWith(android::base::Dirname(path), kApexJavalibPathSuffix)) {
        return true;
    }

    // the in-memory file created by ART through memfd_create is allowed.
    if (IsArtMemfd(path)) {
        return true;
    }

    // Allowlist files needed for Runtime Resource Overlay, like these:
    // /system/vendor/overlay/framework-res.apk
    // /system/vendor/overlay-subdir/pg/framework-res.apk
    // /vendor/overlay/framework-res.apk
    // /vendor/overlay/PG/android-framework-runtime-resource-overlay.apk
    // /data/resource-cache/system@vendor@overlay@framework-res.apk@idmap
    // /data/resource-cache/system@vendor@overlay-subdir@pg@framework-res.apk@idmap
    // See AssetManager.cpp for more details on overlay-subdir.
    static const char* kOverlayDir = "/system/vendor/overlay/";
    static const char* kVendorOverlayDir = "/vendor/overlay";
    static const char* kVendorOverlaySubdir = "/system/vendor/overlay-subdir/";
    static const char* kSystemProductOverlayDir = "/system/product/overlay/";
    static const char* kProductOverlayDir = "/product/overlay";
    static const char* kSystemSystemExtOverlayDir = "/system/system_ext/overlay/";
    static const char* kSystemExtOverlayDir = "/system_ext/overlay";
    static const char* kSystemOdmOverlayDir = "/system/odm/overlay";
    static const char* kOdmOverlayDir = "/odm/overlay";
    static const char* kSystemOemOverlayDir = "/system/oem/overlay";
    static const char* kOemOverlayDir = "/oem/overlay";
    static const char* kApkSuffix = ".apk";

    if ((android::base::StartsWith(path, kOverlayDir) ||
         android::base::StartsWith(path, kVendorOverlaySubdir) ||
         android::base::StartsWith(path, kVendorOverlayDir) ||
         android::base::StartsWith(path, kSystemProductOverlayDir) ||
         android::base::StartsWith(path, kProductOverlayDir) ||
         android::base::StartsWith(path, kSystemSystemExtOverlayDir) ||
         android::base::StartsWith(path, kSystemExtOverlayDir) ||
         android::base::StartsWith(path, kSystemOdmOverlayDir) ||
         android::base::StartsWith(path, kOdmOverlayDir) ||
         android::base::StartsWith(path, kSystemOemOverlayDir) ||
         android::base::StartsWith(path, kOemOverlayDir)) &&
        android::base::EndsWith(path, kApkSuffix) && path.find("/../") == std::string::npos) {
        return true;
    }

    // Allow Runtime Resource Overlays inside APEXes.
    static const char* kOverlayPathSuffix = "/overlay";
    if (android::base::StartsWith(path, kApexPrefix) &&
        android::base::EndsWith(android::base::Dirname(path), kOverlayPathSuffix) &&
        android::base::EndsWith(path, kApkSuffix) && path.find("/../") == std::string::npos) {
        return true;
    }

    static const char* kOverlayIdmapPrefix = "/data/resource-cache/";
    static const char* kOverlayIdmapSuffix = ".apk@idmap";
    if (android::base::StartsWith(path, kOverlayIdmapPrefix) &&
        android::base::EndsWith(path, kOverlayIdmapSuffix) &&
        path.find("/../") == std::string::npos) {
        return true;
    }

    // All regular files that are placed under this path are allowlisted
    // automatically.  The directory name is maintained for compatibility.
    static const char* kZygoteAllowlistPath = "/vendor/zygote_whitelist/";
    if (android::base::StartsWith(path, kZygoteAllowlistPath) &&
        path.find("/../") == std::string::npos) {
        return true;
    }

    return false;
}

FileDescriptorAllowlist::FileDescriptorAllowlist() : allowlist_() {}

FileDescriptorAllowlist* FileDescriptorAllowlist::instance_ = nullptr;

// Keeps track of all relevant information (flags, offset etc.) of an
// open zygote file descriptor.
class FileDescriptorInfo {
 public:
  // Create a FileDescriptorInfo for a given file descriptor.
  static std::unique_ptr<FileDescriptorInfo> CreateFromFd(int fd, fail_fn_t fail_fn);

  // Checks whether the file descriptor associated with this object refers to
  // the same description.
  bool RefersToSameFile() const;

  void ReopenOrDetach(fail_fn_t fail_fn) const;

  const int fd;
  const struct stat stat;
  const std::string file_path;
  const int open_flags;
  const int fd_flags;
  const int fs_flags;
  const off_t offset;
  const bool is_sock;

 private:
  // Constructs for sockets.
  explicit FileDescriptorInfo(int fd);

  // Constructs for non-socket file descriptors.
  FileDescriptorInfo(struct stat stat, const std::string& file_path, int fd, int open_flags,
                     int fd_flags, int fs_flags, off_t offset);

  // Returns the locally-bound name of the socket |fd|. Returns true
  // iff. all of the following hold :
  //
  // - the socket's sa_family is AF_UNIX.
  // - the length of the path is greater than zero (i.e, not an unnamed socket).
  // - the first byte of the path isn't zero (i.e, not a socket with an abstract
  //   address).
  static bool GetSocketName(const int fd, std::string* result);

  void DetachSocket(fail_fn_t fail_fn) const;

  DISALLOW_COPY_AND_ASSIGN(FileDescriptorInfo);
};

std::unique_ptr<FileDescriptorInfo> FileDescriptorInfo::CreateFromFd(int fd, fail_fn_t fail_fn) {
  struct stat f_stat;
  // This should never happen; the zygote should always have the right set
  // of permissions required to stat all its open files.
  if (TEMP_FAILURE_RETRY(fstat(fd, &f_stat)) == -1) {
    fail_fn(android::base::StringPrintf("Unable to stat %d", fd));
  }

  const FileDescriptorAllowlist* allowlist = FileDescriptorAllowlist::Get();

  if (S_ISSOCK(f_stat.st_mode)) {
    std::string socket_name;
    if (!GetSocketName(fd, &socket_name)) {
      fail_fn("Unable to get socket name");
    }

    if (!allowlist->IsAllowed(socket_name)) {
        fail_fn(android::base::StringPrintf("Socket name not allowlisted : %s (fd=%d)",
                                            socket_name.c_str(), fd));
    }

    return std::unique_ptr<FileDescriptorInfo>(new FileDescriptorInfo(fd));
  }

  // We only handle allowlisted regular files and character devices. Allowlisted
  // character devices must provide a guarantee of sensible behaviour when
  // reopened.
  //
  // S_ISDIR : Not supported. (We could if we wanted to, but it's unused).
  // S_ISLINK : Not supported.
  // S_ISBLK : Not supported.
  // S_ISFIFO : Not supported. Note that the Zygote and USAPs use pipes to
  // communicate with the child processes across forks but those should have been
  // added to the redirection exemption list.
  if (!S_ISCHR(f_stat.st_mode) && !S_ISREG(f_stat.st_mode)) {
    std::string mode = "Unknown";

    if (S_ISDIR(f_stat.st_mode)) {
      mode = "DIR";
    } else if (S_ISLNK(f_stat.st_mode)) {
      mode = "LINK";
    } else if (S_ISBLK(f_stat.st_mode)) {
      mode = "BLOCK";
    } else if (S_ISFIFO(f_stat.st_mode)) {
      mode = "FIFO";
    }

    fail_fn(android::base::StringPrintf("Unsupported st_mode for FD %d:  %s", fd, mode.c_str()));
  }

  std::string file_path;
  const std::string fd_path = android::base::StringPrintf("/proc/self/fd/%d", fd);
  if (!android::base::Readlink(fd_path, &file_path)) {
    fail_fn(android::base::StringPrintf("Could not read fd link %s: %s",
                                        fd_path.c_str(),
                                        strerror(errno)));
  }

  if (!allowlist->IsAllowed(file_path)) {
      fail_fn(android::base::StringPrintf("Not allowlisted (%d): %s", fd, file_path.c_str()));
  }

  // File descriptor flags : currently on FD_CLOEXEC. We can set these
  // using F_SETFD - we're single threaded at this point of execution so
  // there won't be any races.
  const int fd_flags = TEMP_FAILURE_RETRY(fcntl(fd, F_GETFD));
  if (fd_flags == -1) {
    fail_fn(android::base::StringPrintf("Failed fcntl(%d, F_GETFD) (%s): %s",
                                        fd,
                                        file_path.c_str(),
                                        strerror(errno)));
  }

  // File status flags :
  // - File access mode : (O_RDONLY, O_WRONLY...) we'll pass these through
  //   to the open() call.
  //
  // - File creation flags : (O_CREAT, O_EXCL...) - there's not much we can
  //   do about these, since the file has already been created. We shall ignore
  //   them here.
  //
  // - Other flags : We'll have to set these via F_SETFL. On linux, F_SETFL
  //   can only set O_APPEND, O_ASYNC, O_DIRECT, O_NOATIME, and O_NONBLOCK.
  //   In particular, it can't set O_SYNC and O_DSYNC. We'll have to test for
  //   their presence and pass them in to open().
  int fs_flags = TEMP_FAILURE_RETRY(fcntl(fd, F_GETFL));
  if (fs_flags == -1) {
    fail_fn(android::base::StringPrintf("Failed fcntl(%d, F_GETFL) (%s): %s",
                                        fd,
                                        file_path.c_str(),
                                        strerror(errno)));
  }

  // File offset : Ignore the offset for non seekable files.
  const off_t offset = TEMP_FAILURE_RETRY(lseek64(fd, 0, SEEK_CUR));

  // We pass the flags that open accepts to open, and use F_SETFL for
  // the rest of them.
  static const int kOpenFlags = (O_RDONLY | O_WRONLY | O_RDWR | O_DSYNC | O_SYNC);
  int open_flags = fs_flags & (kOpenFlags);
  fs_flags = fs_flags & (~(kOpenFlags));

  return std::unique_ptr<FileDescriptorInfo>(
    new FileDescriptorInfo(f_stat, file_path, fd, open_flags, fd_flags, fs_flags, offset));
}

bool FileDescriptorInfo::RefersToSameFile() const {
  struct stat f_stat;
  if (TEMP_FAILURE_RETRY(fstat(fd, &f_stat)) == -1) {
    PLOG(ERROR) << "Unable to restat fd " << fd;
    return false;
  }

  return f_stat.st_ino == stat.st_ino && f_stat.st_dev == stat.st_dev;
}

void FileDescriptorInfo::ReopenOrDetach(fail_fn_t fail_fn) const {
  if (is_sock) {
    return DetachSocket(fail_fn);
  }

  // Children can directly use the in-memory file created by ART through memfd_create.
  if (IsArtMemfd(file_path)) {
    return;
  }

  // NOTE: This might happen if the file was unlinked after being opened.
  // It's a common pattern in the case of temporary files and the like but
  // we should not allow such usage from the zygote.
  const int new_fd = TEMP_FAILURE_RETRY(open(file_path.c_str(), open_flags));

  if (new_fd == -1) {
    fail_fn(android::base::StringPrintf("Failed open(%s, %i): %s",
                                        file_path.c_str(),
                                        open_flags,
                                        strerror(errno)));
  }

  if (TEMP_FAILURE_RETRY(fcntl(new_fd, F_SETFD, fd_flags)) == -1) {
    close(new_fd);
    fail_fn(android::base::StringPrintf("Failed fcntl(%d, F_SETFD, %d) (%s): %s",
                                        new_fd,
                                        fd_flags,
                                        file_path.c_str(),
                                        strerror(errno)));
  }

  if (TEMP_FAILURE_RETRY(fcntl(new_fd, F_SETFL, fs_flags)) == -1) {
    close(new_fd);
    fail_fn(android::base::StringPrintf("Failed fcntl(%d, F_SETFL, %d) (%s): %s",
                                        new_fd,
                                        fs_flags,
                                        file_path.c_str(),
                                        strerror(errno)));
  }

  if (offset != -1 && TEMP_FAILURE_RETRY(lseek64(new_fd, offset, SEEK_SET)) == -1) {
    close(new_fd);
    fail_fn(android::base::StringPrintf("Failed lseek64(%d, SEEK_SET) (%s): %s",
                                        new_fd,
                                        file_path.c_str(),
                                        strerror(errno)));
  }

  int dup_flags = (fd_flags & FD_CLOEXEC) ? O_CLOEXEC : 0;
  if (TEMP_FAILURE_RETRY(dup3(new_fd, fd, dup_flags)) == -1) {
    close(new_fd);
    fail_fn(android::base::StringPrintf("Failed dup3(%d, %d, %d) (%s): %s",
                                        fd,
                                        new_fd,
                                        dup_flags,
                                        file_path.c_str(),
                                        strerror(errno)));
  }

  close(new_fd);
}

FileDescriptorInfo::FileDescriptorInfo(int fd) :
  fd(fd),
  stat(),
  open_flags(0),
  fd_flags(0),
  fs_flags(0),
  offset(0),
  is_sock(true) {
}

FileDescriptorInfo::FileDescriptorInfo(struct stat stat, const std::string& file_path,
                                       int fd, int open_flags, int fd_flags, int fs_flags,
                                       off_t offset) :
  fd(fd),
  stat(stat),
  file_path(file_path),
  open_flags(open_flags),
  fd_flags(fd_flags),
  fs_flags(fs_flags),
  offset(offset),
  is_sock(false) {
}

bool FileDescriptorInfo::GetSocketName(const int fd, std::string* result) {
  sockaddr_storage ss;
  sockaddr* addr = reinterpret_cast<sockaddr*>(&ss);
  socklen_t addr_len = sizeof(ss);

  if (TEMP_FAILURE_RETRY(getsockname(fd, addr, &addr_len)) == -1) {
    PLOG(ERROR) << "Failed getsockname(" << fd << ")";
    return false;
  }

  if (addr->sa_family != AF_UNIX) {
    LOG(ERROR) << "Unsupported socket (fd=" << fd << ") with family " << addr->sa_family;
    return false;
  }

  const sockaddr_un* unix_addr = reinterpret_cast<const sockaddr_un*>(&ss);

  size_t path_len = addr_len - offsetof(struct sockaddr_un, sun_path);
  // This is an unnamed local socket, we do not accept it.
  if (path_len == 0) {
    LOG(ERROR) << "Unsupported AF_UNIX socket (fd=" << fd << ") with empty path.";
    return false;
  }

  // This is a local socket with an abstract address. Remove the leading NUL byte and
  // add a human-readable "ABSTRACT/" prefix.
  if (unix_addr->sun_path[0] == '\0') {
    *result = "ABSTRACT/";
    result->append(&unix_addr->sun_path[1], path_len - 1);
    return true;
  }

  // If we're here, sun_path must refer to a null terminated filesystem
  // pathname (man 7 unix). Remove the terminator before assigning it to an
  // std::string.
  if (unix_addr->sun_path[path_len - 1] ==  '\0') {
    --path_len;
  }

  result->assign(unix_addr->sun_path, path_len);
  return true;
}

void FileDescriptorInfo::DetachSocket(fail_fn_t fail_fn) const {
  const int dev_null_fd = open("/dev/null", O_RDWR | O_CLOEXEC);
  if (dev_null_fd < 0) {
    fail_fn(std::string("Failed to open /dev/null: ").append(strerror(errno)));
  }

  if (dup3(dev_null_fd, fd, O_CLOEXEC) == -1) {
    fail_fn(android::base::StringPrintf("Failed dup3 on socket descriptor %d: %s",
                                        fd,
                                        strerror(errno)));
  }

  if (close(dev_null_fd) == -1) {
    fail_fn(android::base::StringPrintf("Failed close(%d): %s", dev_null_fd, strerror(errno)));
  }
}

// TODO: Move the definitions here and eliminate the forward declarations. They
// temporarily help making code reviews easier.
static int ParseFd(dirent* dir_entry, int dir_fd);
static std::unique_ptr<std::set<int>> GetOpenFdsIgnoring(const std::vector<int>& fds_to_ignore,
                                                         fail_fn_t fail_fn);

FileDescriptorTable* FileDescriptorTable::Create(const std::vector<int>& fds_to_ignore,
                                                 fail_fn_t fail_fn) {
  std::unique_ptr<std::set<int>> open_fds = GetOpenFdsIgnoring(fds_to_ignore, fail_fn);
  std::unordered_map<int, std::unique_ptr<FileDescriptorInfo>> open_fd_map;
  for (auto fd : *open_fds) {
    open_fd_map[fd] = FileDescriptorInfo::CreateFromFd(fd, fail_fn);
  }
  return new FileDescriptorTable(std::move(open_fd_map));
}

static std::unique_ptr<std::set<int>> GetOpenFdsIgnoring(const std::vector<int>& fds_to_ignore,
                                                         fail_fn_t fail_fn) {
  DIR* proc_fd_dir = opendir(kFdPath);
  if (proc_fd_dir == nullptr) {
    fail_fn(android::base::StringPrintf("Unable to open directory %s: %s",
                                        kFdPath,
                                        strerror(errno)));
  }

  auto result = std::make_unique<std::set<int>>();
  int dir_fd = dirfd(proc_fd_dir);
  dirent* dir_entry;
  while ((dir_entry = readdir(proc_fd_dir)) != nullptr) {
    const int fd = ParseFd(dir_entry, dir_fd);
    if (fd == -1) {
      continue;
    }

    if (std::find(fds_to_ignore.begin(), fds_to_ignore.end(), fd) != fds_to_ignore.end()) {
      continue;
    }

    result->insert(fd);
  }

  if (closedir(proc_fd_dir) == -1) {
    fail_fn(android::base::StringPrintf("Unable to close directory: %s", strerror(errno)));
  }
  return result;
}

std::unique_ptr<std::set<int>> GetOpenFds(fail_fn_t fail_fn) {
  const std::vector<int> nothing_to_ignore;
  return GetOpenFdsIgnoring(nothing_to_ignore, fail_fn);
}

void FileDescriptorTable::Restat(const std::vector<int>& fds_to_ignore, fail_fn_t fail_fn) {
  std::unique_ptr<std::set<int>> open_fds = GetOpenFdsIgnoring(fds_to_ignore, fail_fn);

  // Check that the files did not change, and leave only newly opened FDs in
  // |open_fds|.
  RestatInternal(*open_fds, fail_fn);
}

// Reopens all file descriptors that are contained in the table.
void FileDescriptorTable::ReopenOrDetach(fail_fn_t fail_fn) {
  std::unordered_map<int, std::unique_ptr<FileDescriptorInfo>>::const_iterator it;
  for (it = open_fd_map_.begin(); it != open_fd_map_.end(); ++it) {
    const FileDescriptorInfo* info = it->second.get();
    if (info == nullptr) {
      return;
    } else {
      info->ReopenOrDetach(fail_fn);
    }
  }
}

FileDescriptorTable::FileDescriptorTable(
  std::unordered_map<int, std::unique_ptr<FileDescriptorInfo>> map)
  : open_fd_map_(std::move(map)) {
}

FileDescriptorTable::~FileDescriptorTable() {}

void FileDescriptorTable::RestatInternal(std::set<int>& open_fds, fail_fn_t fail_fn) {
  // ART creates a file through memfd for optimization purposes. We make sure
  // there is at most one being created.
  bool art_memfd_seen = false;

  // Iterate through the list of file descriptors we've already recorded
  // and check whether :
  //
  // (a) they continue to be open.
  // (b) they refer to the same file.
  //
  // We'll only store the last error message.
  std::unordered_map<int, std::unique_ptr<FileDescriptorInfo>>::iterator it = open_fd_map_.begin();
  while (it != open_fd_map_.end()) {
    std::set<int>::const_iterator element = open_fds.find(it->first);
    if (element == open_fds.end()) {
      // The entry from the file descriptor table is no longer in the list
      // of open files. We warn about this condition and remove it from
      // the list of FDs under consideration.
      //
      // TODO(narayan): This will be an error in a future android release.
      // error = true;
      // ALOGW("Zygote closed file descriptor %d.", it->first);
      it = open_fd_map_.erase(it);
    } else {
      // The entry from the file descriptor table is still open. Restat
      // it and check whether it refers to the same file.
      if (!it->second->RefersToSameFile()) {
        // The file descriptor refers to a different description. We must
        // update our entry in the table.
        it->second = FileDescriptorInfo::CreateFromFd(*element, fail_fn);
      } else {
        // It's the same file. Nothing to do here. Move on to the next open
        // FD.
      }

      if (IsArtMemfd(it->second->file_path)) {
        if (art_memfd_seen) {
          fail_fn("ART fd already seen: " + it->second->file_path);
        } else {
          art_memfd_seen = true;
        }
      }

      ++it;

      // Finally, remove the FD from the set of open_fds. We do this last because
      // |element| will not remain valid after a call to erase.
      open_fds.erase(element);
    }
  }

  if (open_fds.size() > 0) {
    // The zygote has opened new file descriptors since our last inspection.
    // We warn about this condition and add them to our table.
    //
    // TODO(narayan): This will be an error in a future android release.
    // error = true;
    // ALOGW("Zygote opened %zd new file descriptor(s).", open_fds.size());

    // TODO(narayan): This code will be removed in a future android release.
    std::set<int>::const_iterator it;
    for (it = open_fds.begin(); it != open_fds.end(); ++it) {
      const int fd = (*it);
      open_fd_map_[fd] = FileDescriptorInfo::CreateFromFd(fd, fail_fn);
    }
  }
}

static int ParseFd(dirent* dir_entry, int dir_fd) {
  char* end;
  const int fd = strtol(dir_entry->d_name, &end, 10);
  if ((*end) != '\0') {
    return -1;
  }

  // Don't bother with the standard input/output/error, they're handled
  // specially post-fork anyway.
  if (fd <= STDERR_FILENO || fd == dir_fd) {
    return -1;
  }

  return fd;
}
