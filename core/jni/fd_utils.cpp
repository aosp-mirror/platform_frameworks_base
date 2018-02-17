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

// Static whitelist of open paths that the zygote is allowed to keep open.
static const char* kPathWhitelist[] = {
  "/dev/null",
  "/dev/socket/zygote",
  "/dev/socket/zygote_secondary",
  "/dev/socket/webview_zygote",
  "/sys/kernel/debug/tracing/trace_marker",
  "/system/framework/framework-res.apk",
  "/dev/urandom",
  "/dev/ion",
  "/dev/dri/renderD129", // Fixes b/31172436
};

static const char kFdPath[] = "/proc/self/fd";

// static
FileDescriptorWhitelist* FileDescriptorWhitelist::Get() {
  if (instance_ == nullptr) {
    instance_ = new FileDescriptorWhitelist();
  }
  return instance_;
}

bool FileDescriptorWhitelist::IsAllowed(const std::string& path) const {
  // Check the static whitelist path.
  for (const auto& whitelist_path : kPathWhitelist) {
    if (path == whitelist_path)
      return true;
  }

  // Check any paths added to the dynamic whitelist.
  for (const auto& whitelist_path : whitelist_) {
    if (path == whitelist_path)
      return true;
  }

  static const char* kFrameworksPrefix = "/system/framework/";
  static const char* kJarSuffix = ".jar";
  if (android::base::StartsWith(path, kFrameworksPrefix)
      && android::base::EndsWith(path, kJarSuffix)) {
    return true;
  }

  // Whitelist files needed for Runtime Resource Overlay, like these:
  // /system/vendor/overlay/framework-res.apk
  // /system/vendor/overlay-subdir/pg/framework-res.apk
  // /vendor/overlay/framework-res.apk
  // /vendor/overlay/PG/android-framework-runtime-resource-overlay.apk
  // /data/resource-cache/system@vendor@overlay@framework-res.apk@idmap
  // /data/resource-cache/system@vendor@overlay-subdir@pg@framework-res.apk@idmap
  // See AssetManager.cpp for more details on overlay-subdir.
  static const char* kOverlayDir = "/system/vendor/overlay/";
  static const char* kVendorOverlayDir = "/vendor/overlay";
  static const char* kOverlaySubdir = "/system/vendor/overlay-subdir/";
  static const char* kApkSuffix = ".apk";

  if ((android::base::StartsWith(path, kOverlayDir)
       || android::base::StartsWith(path, kOverlaySubdir)
       || android::base::StartsWith(path, kVendorOverlayDir))
      && android::base::EndsWith(path, kApkSuffix)
      && path.find("/../") == std::string::npos) {
    return true;
  }

  static const char* kOverlayIdmapPrefix = "/data/resource-cache/";
  static const char* kOverlayIdmapSuffix = ".apk@idmap";
  if (android::base::StartsWith(path, kOverlayIdmapPrefix)
      && android::base::EndsWith(path, kOverlayIdmapSuffix)
      && path.find("/../") == std::string::npos) {
    return true;
  }

  // All regular files that are placed under this path are whitelisted automatically.
  static const char* kZygoteWhitelistPath = "/vendor/zygote_whitelist/";
  if (android::base::StartsWith(path, kZygoteWhitelistPath)
      && path.find("/../") == std::string::npos) {
    return true;
  }

  return false;
}

FileDescriptorWhitelist::FileDescriptorWhitelist()
    : whitelist_() {
}

FileDescriptorWhitelist* FileDescriptorWhitelist::instance_ = nullptr;

// static
FileDescriptorInfo* FileDescriptorInfo::CreateFromFd(int fd) {
  struct stat f_stat;
  // This should never happen; the zygote should always have the right set
  // of permissions required to stat all its open files.
  if (TEMP_FAILURE_RETRY(fstat(fd, &f_stat)) == -1) {
    PLOG(ERROR) << "Unable to stat fd " << fd;
    return NULL;
  }

  const FileDescriptorWhitelist* whitelist = FileDescriptorWhitelist::Get();

  if (S_ISSOCK(f_stat.st_mode)) {
    std::string socket_name;
    if (!GetSocketName(fd, &socket_name)) {
      return NULL;
    }

    if (!whitelist->IsAllowed(socket_name)) {
      LOG(ERROR) << "Socket name not whitelisted : " << socket_name
                 << " (fd=" << fd << ")";
      return NULL;
    }

    return new FileDescriptorInfo(fd);
  }

  // We only handle whitelisted regular files and character devices. Whitelisted
  // character devices must provide a guarantee of sensible behaviour when
  // reopened.
  //
  // S_ISDIR : Not supported. (We could if we wanted to, but it's unused).
  // S_ISLINK : Not supported.
  // S_ISBLK : Not supported.
  // S_ISFIFO : Not supported. Note that the zygote uses pipes to communicate
  // with the child process across forks but those should have been closed
  // before we got to this point.
  if (!S_ISCHR(f_stat.st_mode) && !S_ISREG(f_stat.st_mode)) {
    LOG(ERROR) << "Unsupported st_mode " << f_stat.st_mode;
    return NULL;
  }

  std::string file_path;
  const std::string fd_path = android::base::StringPrintf("/proc/self/fd/%d", fd);
  if (!android::base::Readlink(fd_path, &file_path)) {
    return NULL;
  }

  if (!whitelist->IsAllowed(file_path)) {
    LOG(ERROR) << "Not whitelisted : " << file_path;
    return NULL;
  }

  // File descriptor flags : currently on FD_CLOEXEC. We can set these
  // using F_SETFD - we're single threaded at this point of execution so
  // there won't be any races.
  const int fd_flags = TEMP_FAILURE_RETRY(fcntl(fd, F_GETFD));
  if (fd_flags == -1) {
    PLOG(ERROR) << "Failed fcntl(" << fd << ", F_GETFD)";
    return NULL;
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
    PLOG(ERROR) << "Failed fcntl(" << fd << ", F_GETFL)";
    return NULL;
  }

  // File offset : Ignore the offset for non seekable files.
  const off_t offset = TEMP_FAILURE_RETRY(lseek64(fd, 0, SEEK_CUR));

  // We pass the flags that open accepts to open, and use F_SETFL for
  // the rest of them.
  static const int kOpenFlags = (O_RDONLY | O_WRONLY | O_RDWR | O_DSYNC | O_SYNC);
  int open_flags = fs_flags & (kOpenFlags);
  fs_flags = fs_flags & (~(kOpenFlags));

  return new FileDescriptorInfo(f_stat, file_path, fd, open_flags, fd_flags, fs_flags, offset);
}

bool FileDescriptorInfo::Restat() const {
  struct stat f_stat;
  if (TEMP_FAILURE_RETRY(fstat(fd, &f_stat)) == -1) {
    PLOG(ERROR) << "Unable to restat fd " << fd;
    return false;
  }

  return f_stat.st_ino == stat.st_ino && f_stat.st_dev == stat.st_dev;
}

bool FileDescriptorInfo::ReopenOrDetach() const {
  if (is_sock) {
    return DetachSocket();
  }

  // NOTE: This might happen if the file was unlinked after being opened.
  // It's a common pattern in the case of temporary files and the like but
  // we should not allow such usage from the zygote.
  const int new_fd = TEMP_FAILURE_RETRY(open(file_path.c_str(), open_flags));

  if (new_fd == -1) {
    PLOG(ERROR) << "Failed open(" << file_path << ", " << open_flags << ")";
    return false;
  }

  if (TEMP_FAILURE_RETRY(fcntl(new_fd, F_SETFD, fd_flags)) == -1) {
    close(new_fd);
    PLOG(ERROR) << "Failed fcntl(" << new_fd << ", F_SETFD, " << fd_flags << ")";
    return false;
  }

  if (TEMP_FAILURE_RETRY(fcntl(new_fd, F_SETFL, fs_flags)) == -1) {
    close(new_fd);
    PLOG(ERROR) << "Failed fcntl(" << new_fd << ", F_SETFL, " << fs_flags << ")";
    return false;
  }

  if (offset != -1 && TEMP_FAILURE_RETRY(lseek64(new_fd, offset, SEEK_SET)) == -1) {
    close(new_fd);
    PLOG(ERROR) << "Failed lseek64(" << new_fd << ", SEEK_SET)";
    return false;
  }

  if (TEMP_FAILURE_RETRY(dup2(new_fd, fd)) == -1) {
    close(new_fd);
    PLOG(ERROR) << "Failed dup2(" << fd << ", " << new_fd << ")";
    return false;
  }

  close(new_fd);

  return true;
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

// static
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

bool FileDescriptorInfo::DetachSocket() const {
  const int dev_null_fd = open("/dev/null", O_RDWR);
  if (dev_null_fd < 0) {
    PLOG(ERROR) << "Failed to open /dev/null";
    return false;
  }

  if (dup2(dev_null_fd, fd) == -1) {
    PLOG(ERROR) << "Failed dup2 on socket descriptor " << fd;
    return false;
  }

  if (close(dev_null_fd) == -1) {
    PLOG(ERROR) << "Failed close(" << dev_null_fd << ")";
    return false;
  }

  return true;
}

// static
FileDescriptorTable* FileDescriptorTable::Create(const std::vector<int>& fds_to_ignore) {
  DIR* d = opendir(kFdPath);
  if (d == NULL) {
    PLOG(ERROR) << "Unable to open directory " << std::string(kFdPath);
    return NULL;
  }
  int dir_fd = dirfd(d);
  dirent* e;

  std::unordered_map<int, FileDescriptorInfo*> open_fd_map;
  while ((e = readdir(d)) != NULL) {
    const int fd = ParseFd(e, dir_fd);
    if (fd == -1) {
      continue;
    }
    if (std::find(fds_to_ignore.begin(), fds_to_ignore.end(), fd) != fds_to_ignore.end()) {
      LOG(INFO) << "Ignoring open file descriptor " << fd;
      continue;
    }

    FileDescriptorInfo* info = FileDescriptorInfo::CreateFromFd(fd);
    if (info == NULL) {
      if (closedir(d) == -1) {
        PLOG(ERROR) << "Unable to close directory";
      }
      return NULL;
    }
    open_fd_map[fd] = info;
  }

  if (closedir(d) == -1) {
    PLOG(ERROR) << "Unable to close directory";
    return NULL;
  }
  return new FileDescriptorTable(open_fd_map);
}

bool FileDescriptorTable::Restat(const std::vector<int>& fds_to_ignore) {
  std::set<int> open_fds;

  // First get the list of open descriptors.
  DIR* d = opendir(kFdPath);
  if (d == NULL) {
    PLOG(ERROR) << "Unable to open directory " << std::string(kFdPath);
    return false;
  }

  int dir_fd = dirfd(d);
  dirent* e;
  while ((e = readdir(d)) != NULL) {
    const int fd = ParseFd(e, dir_fd);
    if (fd == -1) {
      continue;
    }
    if (std::find(fds_to_ignore.begin(), fds_to_ignore.end(), fd) != fds_to_ignore.end()) {
      LOG(INFO) << "Ignoring open file descriptor " << fd;
      continue;
    }

    open_fds.insert(fd);
  }

  if (closedir(d) == -1) {
    PLOG(ERROR) << "Unable to close directory";
    return false;
  }

  return RestatInternal(open_fds);
}

// Reopens all file descriptors that are contained in the table. Returns true
// if all descriptors were successfully re-opened or detached, and false if an
// error occurred.
bool FileDescriptorTable::ReopenOrDetach() {
  std::unordered_map<int, FileDescriptorInfo*>::const_iterator it;
  for (it = open_fd_map_.begin(); it != open_fd_map_.end(); ++it) {
    const FileDescriptorInfo* info = it->second;
    if (info == NULL || !info->ReopenOrDetach()) {
      return false;
    }
  }

  return true;
}

FileDescriptorTable::FileDescriptorTable(
    const std::unordered_map<int, FileDescriptorInfo*>& map)
    : open_fd_map_(map) {
}

bool FileDescriptorTable::RestatInternal(std::set<int>& open_fds) {
  bool error = false;

  // Iterate through the list of file descriptors we've already recorded
  // and check whether :
  //
  // (a) they continue to be open.
  // (b) they refer to the same file.
  std::unordered_map<int, FileDescriptorInfo*>::iterator it = open_fd_map_.begin();
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
      const bool same_file = it->second->Restat();
      if (!same_file) {
        // The file descriptor refers to a different description. We must
        // update our entry in the table.
        delete it->second;
        it->second = FileDescriptorInfo::CreateFromFd(*element);
        if (it->second == NULL) {
          // The descriptor no longer no longer refers to a whitelisted file.
          // We flag an error and remove it from the list of files we're
          // tracking.
          error = true;
          it = open_fd_map_.erase(it);
        } else {
          // Successfully restatted the file, move on to the next open FD.
          ++it;
        }
      } else {
        // It's the same file. Nothing to do here. Move on to the next open
        // FD.
        ++it;
      }

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
      FileDescriptorInfo* info = FileDescriptorInfo::CreateFromFd(fd);
      if (info == NULL) {
        // A newly opened file is not on the whitelist. Flag an error and
        // continue.
        error = true;
      } else {
        // Track the newly opened file.
        open_fd_map_[fd] = info;
      }
    }
  }

  return !error;
}

// static
int FileDescriptorTable::ParseFd(dirent* e, int dir_fd) {
  char* end;
  const int fd = strtol(e->d_name, &end, 10);
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
