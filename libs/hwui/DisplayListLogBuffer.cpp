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

#include "DisplayListLogBuffer.h"

// BUFFER_SIZE size must be one more than a multiple of COMMAND_SIZE to ensure
// that mStart always points at the next command, not just the next item
#define COMMAND_SIZE 2
#define NUM_COMMANDS 50
#define BUFFER_SIZE ((NUM_COMMANDS * COMMAND_SIZE) + 1)

/**
 * DisplayListLogBuffer is a utility class which logs the most recent display
 * list operations in a circular buffer. The log is process-wide, because we
 * only care about the most recent operations, not the operations on a per-window
 * basis for a given activity. The purpose of the log is to provide more debugging
 * information in a bug report, by telling us not just where a process hung (which
 * generally is just reported as a stack trace at the Java level) or crashed, but
 * also what happened immediately before that hang or crash. This may help track down
 * problems in the native rendering code or driver interaction related to the display
 * list operations that led up to the hang or crash.
 *
 * The log is implemented as a circular buffer for both space and performance
 * reasons - we only care about the last several operations to give us context
 * leading up to the problem, and we don't want to constantly copy data around or do
 * additional mallocs to keep the most recent operations logged. Only numbers are
 * logged to make the operation fast. If and when the log is output, we process this
 * data into meaningful strings.
 *
 * There is an assumption about the format of the command (currently 2 ints: the
 * opcode and the nesting level). If the type of information logged changes (for example,
 * we may want to save a timestamp), then the size of the buffer and the way the
 * information is recorded in writeCommand() should change to suit.
 */

namespace android {

#ifdef USE_OPENGL_RENDERER
using namespace uirenderer;
ANDROID_SINGLETON_STATIC_INSTANCE(DisplayListLogBuffer);
#endif

namespace uirenderer {


DisplayListLogBuffer::DisplayListLogBuffer() {
    mBufferFirst = (int*) malloc(BUFFER_SIZE * sizeof(int));
    mStart = mBufferFirst;
    mBufferLast = mBufferFirst + BUFFER_SIZE - 1;
    mEnd = mStart;
}

DisplayListLogBuffer::~DisplayListLogBuffer() {
    free(mBufferFirst);
}

/**
 * Called from DisplayListRenderer to output the current buffer into the
 * specified FILE. This only happens in a dumpsys/bugreport operation.
 */
void DisplayListLogBuffer::outputCommands(FILE *file, const char* opNames[])
{
    int *tmpBufferPtr = mStart;
    while (true) {
        if (tmpBufferPtr == mEnd) {
            break;
        }
        int level = *tmpBufferPtr++;
        if (tmpBufferPtr > mBufferLast) {
            tmpBufferPtr = mBufferFirst;
        }
        int op = *tmpBufferPtr++;
        if (tmpBufferPtr > mBufferLast) {
            tmpBufferPtr = mBufferFirst;
        }
        uint32_t count = (level + 1) * 2;
        char indent[count + 1];
        for (uint32_t i = 0; i < count; i++) {
            indent[i] = ' ';
        }
        indent[count] = '\0';
        fprintf(file, "%s%s\n", indent, opNames[op]);
    }
}

void DisplayListLogBuffer::writeCommand(int level, int op) {
    writeInt(level);
    writeInt(op);
}

/**
 * Store the given value in the buffer and increment/wrap the mEnd
 * and mStart values as appropriate.
 */
void DisplayListLogBuffer::writeInt(int value) {
    *((int*)mEnd) = value;
    if (mEnd == mBufferLast) {
        mEnd = mBufferFirst;
    } else {
        mEnd++;
    }
    if (mEnd == mStart) {
        mStart++;
        if (mStart > mBufferLast) {
            mStart = mBufferFirst;
        }
    }
}

}; // namespace uirenderer
}; // namespace android
