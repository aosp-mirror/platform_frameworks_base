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

#include "adb.h"

#include "command.h"
#include "print.h"
#include "util.h"

#include <errno.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <limits.h>

#include <iostream>
#include <istream>
#include <streambuf>

using namespace std;

struct Buffer: public streambuf
{
    Buffer(char* begin, size_t size);
};

Buffer::Buffer(char* begin, size_t size)
{
    this->setg(begin, begin, begin + size);
}

int
run_adb(const char* first, ...)
{
    Command cmd("adb");

    if (first == NULL) {
        return 0;
    }

    cmd.AddArg(first);

    va_list args;
    va_start(args, first);
    while (true) {
        const char* arg = va_arg(args, char*);
        if (arg == NULL) {
            break;
        }
        cmd.AddArg(arg);
    }
    va_end(args);

    return run_command(cmd);
}

string
get_system_property(const string& name, int* err)
{
    Command cmd("adb");
    cmd.AddArg("shell");
    cmd.AddArg("getprop");
    cmd.AddArg(name);

    return trim(get_command_output(cmd, err, false));
}


static uint64_t
read_varint(int fd, int* err, bool* done)
{
    uint32_t bits = 0;
    uint64_t result = 0;
    while (true) {
        uint8_t byte;
        ssize_t amt = read(fd, &byte, 1);
        if (amt == 0) {
            *done = true;
            return result;
        } else if (amt < 0) {
            return *err = errno;
        }
        result |= uint64_t(byte & 0x7F) << bits;
        if ((byte & 0x80) == 0) {
            return result;
        }
        bits += 7;
        if (bits > 64) {
            *err = -1;
            return 0;
        }
    }
}

static char*
read_sized_buffer(int fd, int* err, size_t* resultSize)
{
    bool done = false;
    uint64_t size = read_varint(fd, err, &done);
    if (*err != 0 || done) {
        return NULL;
    }
    if (size == 0) {
        *resultSize = 0;
        return NULL;
    }
    // 10 MB seems like a reasonable limit.
    if (size > 10*1024*1024) {
        print_error("result buffer too large: %llu", size);
        return NULL;
    }
    char* buf = (char*)malloc(size);
    if (buf == NULL) {
        print_error("Can't allocate a buffer of size for test results: %llu", size);
        return NULL;
    }
    int pos = 0;
    while (size - pos > 0) {
        ssize_t amt = read(fd, buf+pos, size-pos);
        if (amt == 0) {
            // early end of pipe
            print_error("Early end of pipe.");
            *err = -1;
            free(buf);
            return NULL;
        } else if (amt < 0) {
            // error
            *err = errno;
            free(buf);
            return NULL;
        }
        pos += amt;
    }
    *resultSize = (size_t)size;
    return buf;
}

static int
read_sized_proto(int fd, Message* message)
{
    int err = 0;
    size_t size;
    char* buf = read_sized_buffer(fd, &err, &size);
    if (err != 0) {
        if (buf != NULL) {
            free(buf);
        }
        return err;
    } else if (size == 0) {
        if (buf != NULL) {
            free(buf);
        }
        return 0;
    } else if (buf == NULL) {
        return -1;
    }
    Buffer buffer(buf, size);
    istream in(&buffer);

    err = message->ParseFromIstream(&in) ? 0 : -1;

    free(buf);
    return err;
}

static int
skip_bytes(int fd, ssize_t size, char* scratch, int scratchSize)
{
    while (size > 0) {
        ssize_t amt = size < scratchSize ? size : scratchSize;
        fprintf(stderr, "skipping %lu/%ld bytes\n", size, amt);
        amt = read(fd, scratch, amt);
        if (amt == 0) {
            // early end of pipe
            print_error("Early end of pipe.");
            return -1;
        } else if (amt < 0) {
            // error
            return errno;
        }
        size -= amt;
    }
    return 0;
}

static int
skip_unknown_field(int fd, uint64_t tag, char* scratch, int scratchSize) {
    bool done;
    int err;
    uint64_t size;
    switch (tag & 0x7) {
        case 0: // varint
            read_varint(fd, &err, &done);
            if (err != 0) {
                return err;
            } else if (done) {
                return -1;
            } else {
                return 0;
            }
        case 1:
            return skip_bytes(fd, 8, scratch, scratchSize);
        case 2:
            size = read_varint(fd, &err, &done);
            if (err != 0) {
                return err;
            } else if (done) {
                return -1;
            }
            if (size > INT_MAX) {
                // we'll be here a long time but this keeps it from overflowing
                return -1;
            }
            return skip_bytes(fd, (ssize_t)size, scratch, scratchSize);
        case 5:
            return skip_bytes(fd, 4, scratch, scratchSize);
        default:
            print_error("bad wire type for tag 0x%lx\n", tag);
            return -1;
    }
}

static int
read_instrumentation_results(int fd, char* scratch, int scratchSize,
        InstrumentationCallbacks* callbacks)
{
    bool done = false;
    int err = 0;
    string result;
    while (true) {
        uint64_t tag = read_varint(fd, &err, &done);
        if (done) {
            // Done reading input (this is the only place that a stream end isn't an error).
            return 0;
        } else if (err != 0) {
            return err;
        } else if (tag == 0xa) { // test_status
            TestStatus status;
            err = read_sized_proto(fd, &status);
            if (err != 0) {
                return err;
            }
            callbacks->OnTestStatus(status);
        } else if (tag == 0x12) { // session_status
            SessionStatus status;
            err = read_sized_proto(fd, &status);
            if (err != 0) {
                return err;
            }
            callbacks->OnSessionStatus(status);
        } else {
            err = skip_unknown_field(fd, tag, scratch, scratchSize);
            if (err != 0) {
                return err;
            }
        }
    }
    return 0;
}

int
run_instrumentation_test(const string& packageName, const string& runner, const string& className,
        InstrumentationCallbacks* callbacks)
{
    Command cmd("adb");
    cmd.AddArg("shell");
    cmd.AddArg("am");
    cmd.AddArg("instrument");
    cmd.AddArg("-w");
    cmd.AddArg("-m");
    const int classLen = className.length();
    if (classLen > 0) {
        if (classLen > 1 && className[classLen - 1] == '.') {
            cmd.AddArg("-e");
            cmd.AddArg("package");

            // "am" actually accepts without removing the last ".", but for cleanlines...
            cmd.AddArg(className.substr(0, classLen - 1));
        } else {
            cmd.AddArg("-e");
            cmd.AddArg("class");
            cmd.AddArg(className);
        }
    }
    cmd.AddArg(packageName + "/" + runner);

    print_command(cmd);

    int fds[2];
    if (0 != pipe(fds)) {
        return errno;
    }

    pid_t pid = fork();

    if (pid == -1) {
        // fork error
        return errno;
    } else if (pid == 0) {
        // child
        while ((dup2(fds[1], STDOUT_FILENO) == -1) && (errno == EINTR)) {}
        close(fds[1]);
        close(fds[0]);
        const char* prog = cmd.GetProg();
        char* const* argv = cmd.GetArgv();
        char* const* env = cmd.GetEnv();
        exec_with_path_search(prog, argv, env);
        print_error("Unable to run command: %s", prog);
        exit(1);
    } else {
        // parent
        close(fds[1]);
        string result;
        const int size = 16*1024;
        char* buf = (char*)malloc(size);
        int err = read_instrumentation_results(fds[0], buf, size, callbacks);
        free(buf);
        int status;
        waitpid(pid, &status, 0);
        if (err != 0) {
            return err;
        }
        if (WIFEXITED(status)) {
            return WEXITSTATUS(status);
        } else {
            return -1;
        }
    }
}

/**
 * Get the second to last bundle in the args list. Stores the last name found
 * in last. If the path is not found or if the args list is empty, returns NULL.
 */
static const ResultsBundleEntry *
find_penultimate_entry(const ResultsBundle& bundle, va_list args)
{
    const ResultsBundle* b = &bundle;
    const char* arg = va_arg(args, char*);
    while (arg) {
        string last = arg;
        arg = va_arg(args, char*);
        bool found = false;
        for (int i=0; i<b->entries_size(); i++) {
            const ResultsBundleEntry& e = b->entries(i);
            if (e.key() == last) {
                if (arg == NULL) {
                    return &e;
                } else if (e.has_value_bundle()) {
                    b = &e.value_bundle();
                    found = true;
                }
            }
        }
        if (!found) {
            return NULL;
        }
        if (arg == NULL) {
            return NULL;
        }
    }
    return NULL;
}

string
get_bundle_string(const ResultsBundle& bundle, bool* found, ...)
{
    va_list args;
    va_start(args, found);
    const ResultsBundleEntry* entry = find_penultimate_entry(bundle, args);
    va_end(args);
    if (entry == NULL) {
        *found = false;
        return string();
    }
    if (entry->has_value_string()) {
        *found = true;
        return entry->value_string();
    }
    *found = false;
    return string();
}

int32_t
get_bundle_int(const ResultsBundle& bundle, bool* found, ...)
{
    va_list args;
    va_start(args, found);
    const ResultsBundleEntry* entry = find_penultimate_entry(bundle, args);
    va_end(args);
    if (entry == NULL) {
        *found = false;
        return 0;
    }
    if (entry->has_value_int()) {
        *found = true;
        return entry->value_int();
    }
    *found = false;
    return 0;
}

float
get_bundle_float(const ResultsBundle& bundle, bool* found, ...)
{
    va_list args;
    va_start(args, found);
    const ResultsBundleEntry* entry = find_penultimate_entry(bundle, args);
    va_end(args);
    if (entry == NULL) {
        *found = false;
        return 0;
    }
    if (entry->has_value_float()) {
        *found = true;
        return entry->value_float();
    }
    *found = false;
    return 0;
}

double
get_bundle_double(const ResultsBundle& bundle, bool* found, ...)
{
    va_list args;
    va_start(args, found);
    const ResultsBundleEntry* entry = find_penultimate_entry(bundle, args);
    va_end(args);
    if (entry == NULL) {
        *found = false;
        return 0;
    }
    if (entry->has_value_double()) {
        *found = true;
        return entry->value_double();
    }
    *found = false;
    return 0;
}

int64_t
get_bundle_long(const ResultsBundle& bundle, bool* found, ...)
{
    va_list args;
    va_start(args, found);
    const ResultsBundleEntry* entry = find_penultimate_entry(bundle, args);
    va_end(args);
    if (entry == NULL) {
        *found = false;
        return 0;
    }
    if (entry->has_value_long()) {
        *found = true;
        return entry->value_long();
    }
    *found = false;
    return 0;
}

