/*
 * Copyright (C) 2005 The Android Open Source Project
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

//
// FIFO I/O.
//
#ifndef _LIBS_UTILS_PIPE_H
#define _LIBS_UTILS_PIPE_H

#ifdef HAVE_ANDROID_OS
#error DO NOT USE THIS FILE IN THE DEVICE BUILD
#endif

namespace android {

/*
 * Simple anonymous unidirectional pipe.
 *
 * The primary goal is to create an implementation with minimal overhead
 * under Linux.  Making Windows, Mac OS X, and Linux all work the same way
 * is a secondary goal.  Part of this goal is to have something that can
 * be fed to a select() call, so that the application can sleep in the
 * kernel until something interesting happens.
 */
class Pipe {
public:
    Pipe(void);
    virtual ~Pipe(void);

    /* Create the pipe */
    bool create(void);

    /* Create a read-only pipe, using the supplied handle as read handle */
    bool createReader(unsigned long handle);
    /* Create a write-only pipe, using the supplied handle as write handle */
    bool createWriter(unsigned long handle);

    /* Is this object ready to go? */
    bool isCreated(void);

    /*
     * Read "count" bytes from the pipe.  Returns the amount of data read,
     * or 0 if no data available and we're non-blocking.
     * Returns -1 on error.
     */
    int read(void* buf, int count);

    /*
     * Write "count" bytes into the pipe.  Returns number of bytes written,
     * or 0 if there's no room for more data and we're non-blocking.
     * Returns -1 on error.
     */
    int write(const void* buf, int count);

    /* Returns "true" if data is available to read */
    bool readReady(void);

    /* Enable or disable non-blocking I/O for reads */
    bool setReadNonBlocking(bool val);
    /* Enable or disable non-blocking I/O for writes.  Only works on Linux. */
    bool setWriteNonBlocking(bool val);

    /*
     * Get the handle.  Only useful in some platform-specific situations.
     */
    unsigned long getReadHandle(void);
    unsigned long getWriteHandle(void);

    /*
     * Modify inheritance, i.e. whether or not a child process will get
     * copies of the descriptors.  Systems with fork+exec allow us to close
     * the descriptors before launching the child process, but Win32
     * doesn't allow it.
     */
    bool disallowReadInherit(void);
    bool disallowWriteInherit(void);

    /*
     * Close one side or the other.  Useful in the parent after launching
     * a child process.
     */
    bool closeRead(void);
    bool closeWrite(void);

private:
    bool    mReadNonBlocking;
    bool    mWriteNonBlocking;

    unsigned long mReadHandle;
    unsigned long mWriteHandle;
};

}; // android

#endif // _LIBS_UTILS_PIPE_H
