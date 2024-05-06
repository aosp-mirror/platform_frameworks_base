/*
 * Copyright (C) 2020 The Android Open Source Project
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

#include "com_android_internal_os_Zygote.h"

#include <algorithm>
#include <android-base/logging.h>
#include <async_safe/log.h>
#include <cctype>
#include <chrono>
#include <core_jni_helpers.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <optional>
#include <poll.h>
#include <unistd.h>
#include <utility>
#include <utils/misc.h>
#include <sys/mman.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/system_properties.h>
#include <vector>

namespace android {

using namespace std::placeholders;
using android::base::StringPrintf;
using android::zygote::ZygoteFailure;

// WARNING: Knows a little about the wire protocol used to communicate with Zygote.

// Commands and nice names have large arbitrary size limits to avoid dynamic memory allocation.
constexpr size_t MAX_COMMAND_BYTES = 32768;
constexpr size_t NICE_NAME_BYTES = 128;

// A buffer optionally bundled with a file descriptor from which we can fill it.
// Does not own the file descriptor; destroying a NativeCommandBuffer does not
// close the descriptor.
class NativeCommandBuffer {
 public:
  NativeCommandBuffer(int sourceFd): mEnd(0), mNext(0), mLinesLeft(0), mFd(sourceFd) {}

  // Read mNext line from mFd, filling mBuffer from file descriptor, as needed.
  // Return a pair of pointers pointing to the first character, and one past the
  // mEnd of the line, i.e. at the newline. Returns nothing on failure.
  template<class FailFn>
  std::optional<std::pair<char*, char*>> readLine(FailFn fail_fn) {
    char* result = mBuffer + mNext;
    while (true) {
      if (mNext == mEnd) {
        if (mEnd == MAX_COMMAND_BYTES) {
          return {};
        }
        if (mFd == -1) {
          fail_fn("ZygoteCommandBuffer.readLine attempted to read from mFd -1");
        }
        ssize_t nread = TEMP_FAILURE_RETRY(read(mFd, mBuffer + mEnd, MAX_COMMAND_BYTES - mEnd));
        if (nread <= 0) {
          if (nread == 0) {
            return {};
          }
          fail_fn(CREATE_ERROR("session socket read failed: %s", strerror(errno)));
        } else if (nread == MAX_COMMAND_BYTES - mEnd) {
          // This is pessimistic by one character, but close enough.
          fail_fn("ZygoteCommandBuffer overflowed: command too long");
        }
        mEnd += nread;
      }
      // UTF-8 does not allow newline to occur as part of a multibyte character.
      char* nl = static_cast<char *>(memchr(mBuffer + mNext, '\n', mEnd - mNext));
      if (nl == nullptr) {
        mNext = mEnd;
      } else {
        mNext = nl - mBuffer + 1;
        if (--mLinesLeft < 0) {
          fail_fn("ZygoteCommandBuffer.readLine attempted to read past mEnd of command");
        }
        return std::make_pair(result, nl);
      }
    }
  }

  void reset() {
    mNext = 0;
  }

  // Make sure the current command is fully buffered, without reading past the current command.
  template<class FailFn>
  void readAllLines(FailFn fail_fn) {
     while (mLinesLeft > 0) {
       readLine(fail_fn);
    }
  }

  void clear() {
    // Don't bother to actually clear the buffer; it'll be unmapped in the child anyway.
    reset();
    mNiceName[0] = '\0';
    mEnd = 0;
  }

  // Insert line into the mBuffer. Checks that the mBuffer is not associated with an mFd.
  // Implicitly adds newline separators. Allows mBuffer contents to be explicitly set.
  void insert(const char* line, size_t lineLen) {
    DCHECK(mFd == -1);
    CHECK(mEnd + lineLen < MAX_COMMAND_BYTES);
    strncpy(mBuffer + mEnd, line, lineLen);
    mBuffer[mEnd + lineLen] = '\n';
    mEnd += lineLen + 1;
  }

  // Clear mBuffer, start reading new command, return the number of arguments, leaving mBuffer
  // positioned at the beginning of first argument. Return 0 on EOF.
  template<class FailFn>
  int getCount(FailFn fail_fn) {
    mLinesLeft = 1;
    auto line = readLine(fail_fn);
    if (!line.has_value()) {
      return 0;
    }
    char* countString = line.value().first;  // Newline terminated.
    long nArgs = atol(countString);
    if (nArgs <= 0 || nArgs >= MAX_COMMAND_BYTES / 2) {
      fail_fn(CREATE_ERROR("Unreasonable argument count %ld", nArgs));
    }
    mLinesLeft = nArgs;
    return static_cast<int>(nArgs);
  }

  // Is the mBuffer a simple fork command?
  // We disallow request to wrap the child process, child zygotes, anything that
  // mentions capabilities or requests uid < minUid.
  // We insist that --setuid and --setgid arguments are explicitly included and that the
  // command starts with --runtime-args.
  // Assumes we are positioned at the beginning of the command after the argument count,
  // and leaves the position at some indeterminate position in the buffer.
  // As a side effect, this sets mNiceName to a non-empty string, if possible.
  template<class FailFn>
  bool isSimpleForkCommand(int minUid, FailFn fail_fn) {
    if (mLinesLeft <= 0 || mLinesLeft  >= MAX_COMMAND_BYTES / 2) {
      return false;
    }
    static const char* RUNTIME_ARGS = "--runtime-args";
    static const char* INVOKE_WITH = "--invoke-with";
    static const char* CHILD_ZYGOTE = "--start-child-zygote";
    static const char* SETUID = "--setuid=";
    static const char* SETGID = "--setgid=";
    static const char* CAPABILITIES = "--capabilities";
    static const char* NICE_NAME = "--nice-name=";
    static const size_t RA_LENGTH = strlen(RUNTIME_ARGS);
    static const size_t IW_LENGTH = strlen(INVOKE_WITH);
    static const size_t CZ_LENGTH = strlen(CHILD_ZYGOTE);
    static const size_t SU_LENGTH = strlen(SETUID);
    static const size_t SG_LENGTH = strlen(SETGID);
    static const size_t CA_LENGTH = strlen(CAPABILITIES);
    static const size_t NN_LENGTH = strlen(NICE_NAME);

    bool saw_setuid = false, saw_setgid = false;
    bool saw_runtime_args = false;

    while (mLinesLeft > 0) {
      auto read_result = readLine(fail_fn);
      if (!read_result.has_value()) {
        return false;
      }
      auto [arg_start, arg_end] = read_result.value();
      if (arg_end - arg_start == RA_LENGTH
          && strncmp(arg_start, RUNTIME_ARGS, RA_LENGTH) == 0) {
        saw_runtime_args = true;
        continue;
      }
      if (arg_end - arg_start >= NN_LENGTH
          && strncmp(arg_start, NICE_NAME, NN_LENGTH) == 0) {
        size_t name_len = arg_end - (arg_start + NN_LENGTH);
        size_t copy_len = std::min(name_len, NICE_NAME_BYTES - 1);
        memcpy(mNiceName, arg_start + NN_LENGTH, copy_len);
        mNiceName[copy_len] = '\0';
        if (haveWrapProperty()) {
          return false;
        }
        continue;
      }
      if (arg_end - arg_start == IW_LENGTH
          && strncmp(arg_start, INVOKE_WITH, IW_LENGTH) == 0) {
        // This also removes the need for invoke-with security checks here.
        return false;
      }
      if (arg_end - arg_start == CZ_LENGTH
          && strncmp(arg_start, CHILD_ZYGOTE, CZ_LENGTH) == 0) {
        return false;
      }
      if (arg_end - arg_start >= CA_LENGTH
          && strncmp(arg_start, CAPABILITIES, CA_LENGTH) == 0) {
        return false;
      }
      if (arg_end - arg_start >= SU_LENGTH
          && strncmp(arg_start, SETUID, SU_LENGTH) == 0) {
        int uid = digitsVal(arg_start + SU_LENGTH, arg_end);
        if (uid < minUid) {
          return false;
        }
        saw_setuid = true;
        continue;
      }
      if (arg_end - arg_start >= SG_LENGTH
          && strncmp(arg_start, SETGID, SG_LENGTH) == 0) {
        int gid = digitsVal(arg_start + SG_LENGTH, arg_end);
        if (gid == -1) {
          return false;
        }
        saw_setgid = true;
      }
      // ro.debuggable can be handled entirely in the child unless --invoke-with is also specified.
      // Thus we do not need to check it here.
    }
    return saw_runtime_args && saw_setuid && saw_setgid;
  }

  void setFd(int new_fd) {
    mFd = new_fd;
  }

  int getFd() const {
    return mFd;
  }

  const char* niceNameAddr() const {
    return mNiceName;
  }

  // Debug only:
  void logState() const {
    ALOGD("mbuffer starts with %c%c, nice name is %s, "
          "mEnd = %u, mNext = %u, mLinesLeft = %d, mFd = %d",
          mBuffer[0], (mBuffer[1] == '\n' ? ' ' : mBuffer[1]),
          niceNameAddr(),
          static_cast<unsigned>(mEnd), static_cast<unsigned>(mNext),
          static_cast<int>(mLinesLeft), mFd);
  }

 private:
  bool haveWrapProperty() {
    static const char* WRAP = "wrap.";
    static const size_t WRAP_LENGTH = strlen(WRAP);
    char propNameBuf[WRAP_LENGTH + NICE_NAME_BYTES];
    strcpy(propNameBuf, WRAP);
    strlcpy(propNameBuf + WRAP_LENGTH, mNiceName, NICE_NAME_BYTES);
    return __system_property_find(propNameBuf) != nullptr;
  }
  // Picky version of atoi(). No sign or unexpected characters allowed. Return -1 on failure.
  static int digitsVal(char* start, char* end) {
    int result = 0;
    if (end - start > 6) {
      return -1;
    }
    for (char* dp = start; dp < end; ++dp) {
      if (*dp < '0' || *dp > '9') {
        ALOGW("Argument failed integer format check");
        return -1;
      }
      result = 10 * result + (*dp - '0');
    }
    return result;
  }

  uint32_t mEnd;  // Index of first empty byte in the mBuffer.
  uint32_t mNext;  // Index of first character past last line returned by readLine.
  int32_t mLinesLeft;  // Lines in current command that haven't yet been read.
  int mFd;  // Open file descriptor from which we can read more. -1 if none.
  char mNiceName[NICE_NAME_BYTES];  // Always null terminated.
  char mBuffer[MAX_COMMAND_BYTES];
};

static int buffersAllocd(0);

// Get a new NativeCommandBuffer. Can only be called once between freeNativeBuffer calls,
// so that only one buffer exists at a time.
jlong com_android_internal_os_ZygoteCommandBuffer_getNativeBuffer(JNIEnv* env, jclass, jint fd) {
  CHECK(buffersAllocd == 0);
  ++buffersAllocd;
  // MMap explicitly to get it page aligned.
  void *bufferMem = mmap(NULL, sizeof(NativeCommandBuffer), PROT_READ | PROT_WRITE,
                         MAP_ANONYMOUS | MAP_PRIVATE, -1, 0);
  // Currently we mmap and unmap one for every request handled by the Java code.
  // That could be improved, but unclear it matters.
  if (bufferMem == MAP_FAILED) {
    ZygoteFailure(env, nullptr, nullptr, "Failed to map argument buffer");
  }
  return (jlong) new(bufferMem) NativeCommandBuffer(fd);
}

// Delete native command buffer.
void com_android_internal_os_ZygoteCommandBuffer_freeNativeBuffer(JNIEnv* env, jclass,
                                                                  jlong j_buffer) {
  CHECK(buffersAllocd == 1);
  NativeCommandBuffer* n_buffer = reinterpret_cast<NativeCommandBuffer*>(j_buffer);
  n_buffer->~NativeCommandBuffer();
  if (munmap(n_buffer, sizeof(NativeCommandBuffer)) != 0) {
    ZygoteFailure(env, nullptr, nullptr, "Failed to unmap argument buffer");
  }
  --buffersAllocd;
}

// Clear the buffer, read the line containing the count, and return the count.
jint com_android_internal_os_ZygoteCommandBuffer_nativeGetCount(JNIEnv* env, jclass,
                                                                jlong j_buffer) {
  NativeCommandBuffer* n_buffer = reinterpret_cast<NativeCommandBuffer*>(j_buffer);
  auto fail_fn = std::bind(ZygoteFailure, env, nullptr, nullptr, _1);
  return n_buffer->getCount(fail_fn);
}

// Explicitly insert a string as the last line (argument) of the buffer.
void com_android_internal_os_ZygoteCommandBuffer_insert(JNIEnv* env, jclass, jlong j_buffer,
                                                        jstring line) {
  NativeCommandBuffer* n_buffer = reinterpret_cast<NativeCommandBuffer*>(j_buffer);
  size_t lineLen = static_cast<size_t>(env->GetStringUTFLength(line));
  const char* cstring = env->GetStringUTFChars(line, NULL);
  n_buffer->insert(cstring, lineLen);
  env->ReleaseStringUTFChars(line, cstring);
}

// Read a line from the buffer, refilling as necessary.
jstring com_android_internal_os_ZygoteCommandBuffer_nativeNextArg(JNIEnv* env, jclass,
                                                                  jlong j_buffer) {
  NativeCommandBuffer* n_buffer = reinterpret_cast<NativeCommandBuffer*>(j_buffer);
  auto fail_fn = std::bind(ZygoteFailure, env, n_buffer->niceNameAddr(), nullptr, _1);
  auto line = n_buffer->readLine(fail_fn);
  if (!line.has_value()) {
    fail_fn("Incomplete zygote command");
  }
  auto [cresult, endp] = line.value();
  // OK to temporarily clobber the buffer, since this is not thread safe, and we're modifying
  // the buffer anyway.
  *endp = '\0';
  jstring result = env->NewStringUTF(cresult);
  *endp = '\n';
  return result;
}

static uid_t getSocketPeerUid(int socket, const std::function<void(const std::string&)>& fail_fn) {
  struct ucred credentials;
  socklen_t cred_size = sizeof credentials;
  if (getsockopt(socket, SOL_SOCKET, SO_PEERCRED, &credentials, &cred_size) == -1
      || cred_size != sizeof credentials) {
    fail_fn(CREATE_ERROR("Failed to get socket credentials, %s",
                         strerror(errno)));
  }

  return credentials.uid;
}

// Read all lines from the current command into the buffer, and then reset the buffer, so
// we will start reading again at the beginning of the command, starting with the argument
// count. And we don't need access to the fd to do so.
void com_android_internal_os_ZygoteCommandBuffer_nativeReadFullyAndReset(JNIEnv* env, jclass,
                                                                         jlong j_buffer) {
  NativeCommandBuffer* n_buffer = reinterpret_cast<NativeCommandBuffer*>(j_buffer);
  auto fail_fn = std::bind(ZygoteFailure, env, n_buffer->niceNameAddr(), nullptr, _1);
  n_buffer->readAllLines(fail_fn);
  n_buffer->reset();
}

// Fork a child as specified by the current command buffer, and refill the command
// buffer from the given socket. So long as the result is another simple fork command,
// repeat this process.
// It must contain a fork command, which is currently restricted not to fork another
// zygote or involve a wrapper process.
// The initial buffer should be partially or entirely read; we read it fully and reset it.
// When we return, the buffer contains the command we couldn't handle, and has been reset().
// We return false in the parent when we see a command we didn't understand, and thus the
// command in the buffer still needs to be executed.
// We return true in each child.
// We only process fork commands if the peer uid matches expected_uid.
// For every fork command after the first, we check that the requested uid is at
// least minUid.
NO_STACK_PROTECTOR
jboolean com_android_internal_os_ZygoteCommandBuffer_nativeForkRepeatedly(
            JNIEnv* env,
            jclass,
            jlong j_buffer,
            jint zygote_socket_fd,
            jint expected_uid,
            jint minUid,
            jstring managed_nice_name) {

  ALOGI("Entering forkRepeatedly native zygote loop");
  NativeCommandBuffer* n_buffer = reinterpret_cast<NativeCommandBuffer*>(j_buffer);
  int session_socket = n_buffer->getFd();
  std::vector<int> session_socket_fds {session_socket};
  auto fail_fn_1 = std::bind(ZygoteFailure, env, static_cast<const char*>(nullptr),
                             static_cast<jstring>(managed_nice_name), _1);
  // This binds to the nice name address; the actual names are updated by isSimpleForkCommand:
  auto fail_fn_n = std::bind(ZygoteFailure, env, n_buffer->niceNameAddr(),
                             static_cast<jstring>(nullptr), _1);
  auto fail_fn_z = std::bind(ZygoteFailure, env, "zygote", nullptr, _1);

  struct pollfd fd_structs[2];
  static const int ZYGOTE_IDX = 0;
  static const int SESSION_IDX = 1;
  fd_structs[ZYGOTE_IDX].fd = zygote_socket_fd;
  fd_structs[ZYGOTE_IDX].events = POLLIN;
  fd_structs[SESSION_IDX].fd = session_socket;
  fd_structs[SESSION_IDX].events = POLLIN;

  struct timeval timeout;
  socklen_t timeout_size = sizeof timeout;
  if (getsockopt(session_socket, SOL_SOCKET, SO_RCVTIMEO, &timeout, &timeout_size) != 0) {
    fail_fn_z("Failed to retrieve session socket timeout");
  }

  uid_t peerUid = getSocketPeerUid(session_socket, fail_fn_1);
  if (peerUid != static_cast<uid_t>(expected_uid)) {
    return JNI_FALSE;
  }
  bool first_time = true;
  do {
    n_buffer->readAllLines(first_time ? fail_fn_1 : fail_fn_n);
    n_buffer->reset();
    int pid = zygote::forkApp(env, /* no pipe FDs */ -1, -1, session_socket_fds,
                              /*args_known=*/ true, /*is_priority_fork=*/ true,
                              /*purge=*/ first_time);
    if (pid == 0) {
      return JNI_TRUE;
    }
    // We're in the parent. Write big-endian pid, followed by a boolean.
    char pid_buf[5];
    int tmp_pid = pid;
    for (int i = 3; i >= 0; --i) {
      pid_buf[i] = tmp_pid & 0xff;
      tmp_pid >>= 8;
    }
    pid_buf[4] = 0;  // Process is not wrapped.
    int res = TEMP_FAILURE_RETRY(write(session_socket, pid_buf, 5));
    if (res != 5) {
      if (res == -1) {
        (first_time ? fail_fn_1 : fail_fn_n)
            (CREATE_ERROR("Pid write error %d: %s", errno, strerror(errno)));
      } else {
        (first_time ? fail_fn_1 : fail_fn_n)
            (CREATE_ERROR("Write unexpectedly returned short: %d < 5", res));
      }
    }
    // Clear buffer and get count from next command.
    n_buffer->clear();
    for (;;) {
      bool valid_session_socket = true;
      // Poll isn't strictly necessary for now. But without it, disconnect is hard to detect.
      int poll_res = TEMP_FAILURE_RETRY(poll(fd_structs, 2, -1 /* infinite timeout */));
      if ((fd_structs[SESSION_IDX].revents & POLLIN) != 0) {
        if (n_buffer->getCount(fail_fn_z) != 0) {
          break;
        } else {
          // Session socket was disconnected
          valid_session_socket = false;
          close(session_socket);
        }
      } else if (poll_res == 0 || (fd_structs[ZYGOTE_IDX].revents & POLLIN) == 0) {
        fail_fn_z(
            CREATE_ERROR("Poll returned with no descriptors ready! Poll returned %d", poll_res));
      }
      int new_fd = -1;
      do {
        // We've now seen either a disconnect or connect request.
        new_fd = TEMP_FAILURE_RETRY(accept(zygote_socket_fd, nullptr, nullptr));
        if (new_fd == -1) {
          fail_fn_z(CREATE_ERROR("Accept(%d) failed: %s", zygote_socket_fd, strerror(errno)));
        }
        uid_t newPeerUid = getSocketPeerUid(new_fd, fail_fn_1);
        if (newPeerUid != static_cast<uid_t>(expected_uid)) {
          ALOGW("Dropping new connection with a mismatched uid %d\n", newPeerUid);
          close(new_fd);
          new_fd = -1;
        } else {
          // If we still have a valid session socket, close it now
          if (valid_session_socket) {
              close(session_socket);
          }
          valid_session_socket = true;
        }
      } while (!valid_session_socket);

      // At this point we either have a valid new connection (new_fd > 0), or
      // an existing session socket we can poll on
      if (new_fd == -1) {
        // The new connection wasn't valid, and we still have an old one; retry polling
        continue;
      }
      if (new_fd != session_socket) {
        // Move new_fd back to the old value, so that we don't have to change Java-level data
        // structures to reflect a change. This implicitly closes the old one.
        if (TEMP_FAILURE_RETRY(dup2(new_fd, session_socket)) != session_socket) {
          fail_fn_z(CREATE_ERROR("Failed to move fd %d to %d: %s",
                                 new_fd, session_socket, strerror(errno)));
        }
        close(new_fd);  //  On Linux, fd is closed even if EINTR is returned.
      }
      // If we ever return, we effectively reuse the old Java ZygoteConnection.
      // None of its state needs to change.
      if (setsockopt(session_socket, SOL_SOCKET, SO_RCVTIMEO, &timeout, timeout_size) != 0) {
        fail_fn_z(CREATE_ERROR("Failed to set receive timeout for socket %d: %s",
                               session_socket, strerror(errno)));
      }
      if (setsockopt(session_socket, SOL_SOCKET, SO_SNDTIMEO, &timeout, timeout_size) != 0) {
        fail_fn_z(CREATE_ERROR("Failed to set send timeout for socket %d: %s",
                               session_socket, strerror(errno)));
      }
    }
    first_time = false;
  } while (n_buffer->isSimpleForkCommand(minUid, fail_fn_n));
  ALOGW("forkRepeatedly terminated due to non-simple command");
  n_buffer->logState();
  n_buffer->reset();
  return JNI_FALSE;
}

#define METHOD_NAME(m) com_android_internal_os_ZygoteCommandBuffer_ ## m

static const JNINativeMethod gMethods[] = {
        {"getNativeBuffer", "(I)J", (void *) METHOD_NAME(getNativeBuffer)},
        {"freeNativeBuffer", "(J)V", (void *) METHOD_NAME(freeNativeBuffer)},
        {"insert", "(JLjava/lang/String;)V", (void *) METHOD_NAME(insert)},
        {"nativeNextArg", "(J)Ljava/lang/String;", (void *) METHOD_NAME(nativeNextArg)},
        {"nativeReadFullyAndReset", "(J)V", (void *) METHOD_NAME(nativeReadFullyAndReset)},
        {"nativeGetCount", "(J)I", (void *) METHOD_NAME(nativeGetCount)},
        {"nativeForkRepeatedly", "(JIIILjava/lang/String;)Z",
          (void *) METHOD_NAME(nativeForkRepeatedly)},
};

int register_com_android_internal_os_ZygoteCommandBuffer(JNIEnv* env) {
  return RegisterMethodsOrDie(env, "com/android/internal/os/ZygoteCommandBuffer", gMethods,
                              NELEM(gMethods));
}

}  // namespace android
