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

#ifdef _WIN32
// nothing to see here
#else
#include <optional>
#include <string>
#include <vector>

#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#include "android-base/logging.h"

#include "androidfw/PosixUtils.h"

static std::optional<std::string> ReadFile(int fd) {
  std::string str;
  char buf[1024];
  ssize_t r;
  while ((r = read(fd, buf, sizeof(buf))) > 0) {
    str.append(buf, r);
  }
  if (r != 0) {
    return std::nullopt;
  }
  return std::move(str);
}

namespace android {
namespace util {

ProcResult ExecuteBinary(const std::vector<std::string>& argv) {
  int stdout[2];  // [0] read, [1] write
  if (pipe(stdout) != 0) {
    PLOG(ERROR) << "out pipe";
    return ProcResult{-1};
  }

  int stderr[2];  // [0] read, [1] write
  if (pipe(stderr) != 0) {
    PLOG(ERROR) << "err pipe";
    close(stdout[0]);
    close(stdout[1]);
    return ProcResult{-1};
  }

  auto gid = getgid();
  auto uid = getuid();

  // better keep no C++ objects going into the child here
  auto argv0 = (char const**)malloc(sizeof(char*) * (argv.size() + 1));
  for (size_t i = 0; i < argv.size(); i++) {
    argv0[i] = argv[i].c_str();
  }
  argv0[argv.size()] = nullptr;
  int pid = fork();
  switch (pid) {
    case -1: // error
      free(argv0);
      close(stdout[0]);
      close(stdout[1]);
      close(stderr[0]);
      close(stderr[1]);
      PLOG(ERROR) << "fork";
      return ProcResult{-1};
    case 0: // child
      if (setgid(gid) != 0) {
        PLOG(ERROR) << "setgid";
        exit(1);
      }

      if (setuid(uid) != 0) {
        PLOG(ERROR) << "setuid";
        exit(1);
      }

      close(stdout[0]);
      if (dup2(stdout[1], STDOUT_FILENO) == -1) {
        abort();
      }
      close(stderr[0]);
      if (dup2(stderr[1], STDERR_FILENO) == -1) {
        abort();
      }
      execvp(argv0[0], const_cast<char* const*>(argv0));
      PLOG(ERROR) << "execv";
      abort();
    default: // parent
      free(argv0);
      close(stdout[1]);
      close(stderr[1]);
      int status;
      waitpid(pid, &status, 0);
      if (!WIFEXITED(status)) {
          close(stdout[0]);
          close(stderr[0]);
          return ProcResult{-1};
      }
      ProcResult result(status);
      auto out = ReadFile(stdout[0]);
      result.stdout_str = out ? std::move(*out) : "";
      close(stdout[0]);
      auto err = ReadFile(stderr[0]);
      result.stderr_str = err ? std::move(*err) : "";
      close(stderr[0]);
      return result;
  }
}

} // namespace util
} // namespace android
#endif
