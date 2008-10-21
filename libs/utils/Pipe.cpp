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
// Unidirectional pipe.
//

#include <utils/Pipe.h>
#include <utils/Log.h>

#if defined(HAVE_WIN32_IPC)
# include <windows.h>
#else
# include <fcntl.h>
# include <unistd.h>
# include <errno.h>
#endif

#include <stdlib.h>
#include <stdio.h>
#include <assert.h>
#include <string.h>

using namespace android;

const unsigned long kInvalidHandle = (unsigned long) -1;


/*
 * Constructor.  Do little.
 */
Pipe::Pipe(void)
    : mReadNonBlocking(false), mReadHandle(kInvalidHandle),
      mWriteHandle(kInvalidHandle)
{
}

/*
 * Destructor.  Use the system-appropriate close call.
 */
Pipe::~Pipe(void)
{
#if defined(HAVE_WIN32_IPC)
    if (mReadHandle != kInvalidHandle) {
        if (!CloseHandle((HANDLE)mReadHandle))
            LOG(LOG_WARN, "pipe", "failed closing read handle (%ld)\n",
                mReadHandle);
    }
    if (mWriteHandle != kInvalidHandle) {
        FlushFileBuffers((HANDLE)mWriteHandle);
        if (!CloseHandle((HANDLE)mWriteHandle))
            LOG(LOG_WARN, "pipe", "failed closing write handle (%ld)\n",
                mWriteHandle);
    }
#else
    if (mReadHandle != kInvalidHandle) {
        if (close((int) mReadHandle) != 0)
            LOG(LOG_WARN, "pipe", "failed closing read fd (%d)\n",
                (int) mReadHandle);
    }
    if (mWriteHandle != kInvalidHandle) {
        if (close((int) mWriteHandle) != 0)
            LOG(LOG_WARN, "pipe", "failed closing write fd (%d)\n",
                (int) mWriteHandle);
    }
#endif
}

/*
 * Create the pipe.
 *
 * Use the POSIX stuff for everything but Windows.
 */
bool Pipe::create(void)
{
    assert(mReadHandle == kInvalidHandle);
    assert(mWriteHandle == kInvalidHandle);

#if defined(HAVE_WIN32_IPC)
    /* we use this across processes, so they need to be inheritable */
    HANDLE handles[2];
    SECURITY_ATTRIBUTES saAttr;

    saAttr.nLength = sizeof(SECURITY_ATTRIBUTES);
    saAttr.bInheritHandle = TRUE;
    saAttr.lpSecurityDescriptor = NULL;

    if (!CreatePipe(&handles[0], &handles[1], &saAttr, 0)) {
        LOG(LOG_ERROR, "pipe", "unable to create pipe\n");
        return false;
    }
    mReadHandle = (unsigned long) handles[0];
    mWriteHandle = (unsigned long) handles[1];
    return true;
#else
    int fds[2];

    if (pipe(fds) != 0) {
        LOG(LOG_ERROR, "pipe", "unable to create pipe\n");
        return false;
    }
    mReadHandle = fds[0];
    mWriteHandle = fds[1];
    return true;
#endif
}

/*
 * Create a "half pipe".  Please, no Segway riding.
 */
bool Pipe::createReader(unsigned long handle)
{
    mReadHandle = handle;
    assert(mWriteHandle == kInvalidHandle);
    return true;
}

/*
 * Create a "half pipe" for writing.
 */
bool Pipe::createWriter(unsigned long handle)
{
    mWriteHandle = handle;
    assert(mReadHandle == kInvalidHandle);
    return true;
}

/*
 * Return "true" if create() has been called successfully.
 */
bool Pipe::isCreated(void)
{
    // one or the other should be open
    return (mReadHandle != kInvalidHandle || mWriteHandle != kInvalidHandle);
}


/*
 * Read data from the pipe.
 *
 * For Linux and Darwin, just call read().  For Windows, implement
 * non-blocking reads by calling PeekNamedPipe first.
 */
int Pipe::read(void* buf, int count)
{
    assert(mReadHandle != kInvalidHandle);

#if defined(HAVE_WIN32_IPC)
    DWORD totalBytesAvail = count;
    DWORD bytesRead;

    if (mReadNonBlocking) {
        // use PeekNamedPipe to adjust read count expectations
        if (!PeekNamedPipe((HANDLE) mReadHandle, NULL, 0, NULL,
                &totalBytesAvail, NULL))
        {
            LOG(LOG_ERROR, "pipe", "PeekNamedPipe failed\n");
            return -1;
        }

        if (totalBytesAvail == 0)
            return 0;
    }

    if (!ReadFile((HANDLE) mReadHandle, buf, totalBytesAvail, &bytesRead,
            NULL))
    {
        DWORD err = GetLastError();
        if (err == ERROR_HANDLE_EOF || err == ERROR_BROKEN_PIPE)
            return 0;
        LOG(LOG_ERROR, "pipe", "ReadFile failed (err=%ld)\n", err);
        return -1;
    }

    return (int) bytesRead;
#else
    int cc;
    cc = ::read(mReadHandle, buf, count);
    if (cc < 0 && errno == EAGAIN)
        return 0;
    return cc;
#endif
}

/*
 * Write data to the pipe.
 *
 * POSIX systems are trivial, Windows uses a different call and doesn't
 * handle non-blocking writes.
 *
 * If we add non-blocking support here, we probably want to make it an
 * all-or-nothing write.
 *
 * DO NOT use LOG() here, we could be writing a log message.
 */
int Pipe::write(const void* buf, int count)
{
    assert(mWriteHandle != kInvalidHandle);

#if defined(HAVE_WIN32_IPC)
    DWORD bytesWritten;

    if (mWriteNonBlocking) {
        // BUG: can't use PeekNamedPipe() to get the amount of space
        // left.  Looks like we need to use "overlapped I/O" functions.
        // I just don't care that much.
    }

    if (!WriteFile((HANDLE) mWriteHandle, buf, count, &bytesWritten, NULL)) {
        // can't LOG, use stderr
        fprintf(stderr, "WriteFile failed (err=%ld)\n", GetLastError());
        return -1;
    }

    return (int) bytesWritten;
#else
    int cc;
    cc = ::write(mWriteHandle, buf, count);
    if (cc < 0 && errno == EAGAIN)
        return 0;
    return cc;
#endif
}

/*
 * Figure out if there is data available on the read fd.
 *
 * We return "true" on error because we want the caller to try to read
 * from the pipe.  They'll notice the read failure and do something
 * appropriate.
 */
bool Pipe::readReady(void)
{
    assert(mReadHandle != kInvalidHandle);

#if defined(HAVE_WIN32_IPC)
    DWORD totalBytesAvail;

    if (!PeekNamedPipe((HANDLE) mReadHandle, NULL, 0, NULL,
            &totalBytesAvail, NULL))
    {
        LOG(LOG_ERROR, "pipe", "PeekNamedPipe failed\n");
        return true;
    }

    return (totalBytesAvail != 0);
#else
    errno = 0;
    fd_set readfds;
    struct timeval tv = { 0, 0 };
    int cc;

    FD_ZERO(&readfds);
    FD_SET(mReadHandle, &readfds);

    cc = select(mReadHandle+1, &readfds, NULL, NULL, &tv);
    if (cc < 0) {
        LOG(LOG_ERROR, "pipe", "select() failed\n");
        return true;
    } else if (cc == 0) {
        /* timed out, nothing available */
        return false;
    } else if (cc == 1) {
        /* our fd is ready */
        return true;
    } else {
        LOG(LOG_ERROR, "pipe", "HUH? select() returned > 1\n");
        return true;
    }
#endif
}

/*
 * Enable or disable non-blocking mode for the read descriptor.
 *
 * NOTE: the calls succeed under Mac OS X, but the pipe doesn't appear to
 * actually be in non-blocking mode.  If this matters -- i.e. you're not
 * using a select() call -- put a call to readReady() in front of the
 * ::read() call, with a PIPE_NONBLOCK_BROKEN #ifdef in the Makefile for
 * Darwin.
 */
bool Pipe::setReadNonBlocking(bool val)
{
    assert(mReadHandle != kInvalidHandle);

#if defined(HAVE_WIN32_IPC)
    // nothing to do
#else
    int flags;

    if (fcntl(mReadHandle, F_GETFL, &flags) == -1) {
        LOG(LOG_ERROR, "pipe", "couldn't get flags for pipe read fd\n");
        return false;
    }
    if (val)
        flags |= O_NONBLOCK;
    else
        flags &= ~(O_NONBLOCK);
    if (fcntl(mReadHandle, F_SETFL, &flags) == -1) {
        LOG(LOG_ERROR, "pipe", "couldn't set flags for pipe read fd\n");
        return false;
    }
#endif

    mReadNonBlocking = val;
    return true;
}

/*
 * Enable or disable non-blocking mode for the write descriptor.
 *
 * As with setReadNonBlocking(), this does not work on the Mac.
 */
bool Pipe::setWriteNonBlocking(bool val)
{
    assert(mWriteHandle != kInvalidHandle);

#if defined(HAVE_WIN32_IPC)
    // nothing to do
#else
    int flags;

    if (fcntl(mWriteHandle, F_GETFL, &flags) == -1) {
        LOG(LOG_WARN, "pipe",
            "Warning: couldn't get flags for pipe write fd (errno=%d)\n",
            errno);
        return false;
    }
    if (val)
        flags |= O_NONBLOCK;
    else
        flags &= ~(O_NONBLOCK);
    if (fcntl(mWriteHandle, F_SETFL, &flags) == -1) {
        LOG(LOG_WARN, "pipe",
            "Warning: couldn't set flags for pipe write fd (errno=%d)\n",
            errno);
        return false;
    }
#endif

    mWriteNonBlocking = val;
    return true;
}

/*
 * Specify whether a file descriptor can be inherited by a child process.
 * Under Linux this means setting the close-on-exec flag, under Windows
 * this is SetHandleInformation(HANDLE_FLAG_INHERIT).
 */
bool Pipe::disallowReadInherit(void)
{
    if (mReadHandle == kInvalidHandle)
        return false;

#if defined(HAVE_WIN32_IPC)
    if (SetHandleInformation((HANDLE) mReadHandle, HANDLE_FLAG_INHERIT, 0) == 0)
        return false;
#else
    if (fcntl((int) mReadHandle, F_SETFD, FD_CLOEXEC) != 0)
        return false;
#endif
    return true;
}
bool Pipe::disallowWriteInherit(void)
{
    if (mWriteHandle == kInvalidHandle)
        return false;

#if defined(HAVE_WIN32_IPC)
    if (SetHandleInformation((HANDLE) mWriteHandle, HANDLE_FLAG_INHERIT, 0) == 0)
        return false;
#else
    if (fcntl((int) mWriteHandle, F_SETFD, FD_CLOEXEC) != 0)
        return false;
#endif
    return true;
}

/*
 * Close read descriptor.
 */
bool Pipe::closeRead(void)
{
    if (mReadHandle == kInvalidHandle)
        return false;

#if defined(HAVE_WIN32_IPC)
    if (mReadHandle != kInvalidHandle) {
        if (!CloseHandle((HANDLE)mReadHandle)) {
            LOG(LOG_WARN, "pipe", "failed closing read handle\n");
            return false;
        }
    }
#else
    if (mReadHandle != kInvalidHandle) {
        if (close((int) mReadHandle) != 0) {
            LOG(LOG_WARN, "pipe", "failed closing read fd\n");
            return false;
        }
    }
#endif
    mReadHandle = kInvalidHandle;
    return true;
}

/*
 * Close write descriptor.
 */
bool Pipe::closeWrite(void)
{
    if (mWriteHandle == kInvalidHandle)
        return false;

#if defined(HAVE_WIN32_IPC)
    if (mWriteHandle != kInvalidHandle) {
        if (!CloseHandle((HANDLE)mWriteHandle)) {
            LOG(LOG_WARN, "pipe", "failed closing write handle\n");
            return false;
        }
    }
#else
    if (mWriteHandle != kInvalidHandle) {
        if (close((int) mWriteHandle) != 0) {
            LOG(LOG_WARN, "pipe", "failed closing write fd\n");
            return false;
        }
    }
#endif
    mWriteHandle = kInvalidHandle;
    return true;
}

/*
 * Get the read handle.
 */
unsigned long Pipe::getReadHandle(void)
{
    assert(mReadHandle != kInvalidHandle);

    return mReadHandle;
}

/*
 * Get the write handle.
 */
unsigned long Pipe::getWriteHandle(void)
{
    assert(mWriteHandle != kInvalidHandle);

    return mWriteHandle;
}

