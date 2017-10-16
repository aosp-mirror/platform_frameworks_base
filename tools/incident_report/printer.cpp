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

#include "printer.h"

#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>

#define INITIAL_BUF_SIZE (16*1024)

char const* SPACES = "                                                            ";
const int SPACE_COUNT = strlen(SPACES);

Out::Out(int fd)
    :mOut(fd == STDOUT_FILENO ? stdout : fdopen(fd, "w")),
     mBufSize(INITIAL_BUF_SIZE),
     mBuf((char*)malloc(INITIAL_BUF_SIZE)),
     mIndent(0),
     mPendingIndent(false)
{
}

Out::~Out()
{
    fclose(mOut);
}

int
Out::reallocate(int size)
{
    if (size > mBufSize) {
        char* p = (char*)malloc(size);
        if (p != NULL) {
            free(mBuf);
            mBufSize = size;
            mBuf = p;
            return size;
        }
    }
    return mBufSize;
}

void
Out::printf(const char* format, ...)
{
    if (mPendingIndent) {
        print_indent();
        mPendingIndent = false;
    }

    int len;

    va_list args;
    va_start(args, format);

    len = vsnprintf(mBuf, mBufSize, format, args);
    va_end(args);

    va_start(args, format);
    len = vsnprintf(mBuf, mBufSize, format, args);
    va_end(args);

    if (len > 0) {
        if (mIndent == 0) {
            fwrite(mBuf, len, 1, mOut);
        } else {
            char* last = mBuf;
            char* p;
            do {
                p = strchr(last, '\n');
                int size = p != NULL ? p - last + 1 : strlen(last);
                fwrite(last, size, 1, mOut);
                if (p != NULL) {
                    if (p[1] == '\0') {
                        mPendingIndent = true;
                    } else {
                        print_indent();
                    }
                }
                last = p+1;
            } while (p != NULL);
        }
    }
}

void
Out::indent()
{
    mPendingIndent = true;
    mIndent += 2;
}

void
Out::dedent()
{
    if (mIndent > 0) {
        mIndent -= 2;
    }
}

void
Out::print_indent()
{
#if 0
    fprintf(mOut, "[%d]", mIndent);
#else
    int indent = mIndent;
    while (indent > SPACE_COUNT) {
        fwrite(SPACES, SPACE_COUNT, 1, mOut);
        indent -= SPACE_COUNT;
    }
    fwrite(SPACES + SPACE_COUNT - indent, indent, 1, mOut);
#endif
}
