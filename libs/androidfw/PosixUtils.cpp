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
#include <memory>
#include <string>
#include <vector>

#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#include "android-base/logging.h"

#include "androidfw/PosixUtils.h"

namespace {

std::unique_ptr<std::string> ReadFile(int fd) {
  std::unique_ptr<std::string> str(new std::string());
  char buf[1024];
  ssize_t r;
  while ((r = read(fd, buf, sizeof(buf))) > 0) {
    str->append(buf, r);
  }
  if (r != 0) {
    return nullptr;
  }
  return str;
}

}

namespace android {
namespace util {

std::unique_ptr<ProcResult> ExecuteBinary(const std::vector<std::string>& argv) {
  int stdout[2];  // stdout[0] read, stdout[1] write
  if (pipe(stdout) != 0) {
    PLOG(ERROR) << "pipe";
    return nullptr;
  }

  int stderr[2];  // stdout[0] read, stdout[1] write
  if (pipe(stderr) != 0) {
    PLOG(ERROR) << "pipe";
    close(stdout[0]);
    close(stdout[1]);
    return nullptr;
  }

  auto gid = getgid();
  auto uid = getuid();

  char const** argv0 = (char const**)malloc(sizeof(char*) * (argv.size() + 1));
  for (size_t i = 0; i < argv.size(); i++) {
    argv0[i] = argv[i].c_str();
  }
  argv0[argv.size()] = nullptr;
  switch (fork()) {
    case -1: // error
      free(argv0);
      PLOG(ERROR) << "fork";
      return nullptr;
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
      wait(&status);
      if (!WIFEXITED(status)) {
          return nullptr;
      }
      std::unique_ptr<ProcResult> result(new ProcResult());
      result->status = status;
      const auto out = ReadFile(stdout[0]);
      result->stdout = out ? *out : "";
      close(stdout[0]);
      const auto err = ReadFile(stderr[0]);
      result->stderr = err ? *err : "";
      close(stderr[0]);
      return result;
  }
}

} // namespace util
} // namespace android
#endif
