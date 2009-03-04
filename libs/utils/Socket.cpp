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
// Internet address class.
//

#ifdef HAVE_WINSOCK
// This needs to come first, or Cygwin gets concerned about a potential
// clash between WinSock and <sys/types.h>.
# include <winsock2.h>
#endif

#include <utils/Socket.h>
#include <utils/inet_address.h>
#include <utils/Log.h>
#include <utils/Timers.h>

#ifndef HAVE_WINSOCK
# include <sys/types.h>
# include <sys/socket.h>
# include <netinet/in.h>
# include <arpa/inet.h>
#endif

#include <stdlib.h>
#include <stdio.h>
#include <unistd.h>
#include <string.h>
#include <errno.h>
#include <assert.h>

using namespace android;


/*
 * ===========================================================================
 *      Socket
 * ===========================================================================
 */

#ifndef INVALID_SOCKET
# define INVALID_SOCKET (-1)
#endif
#define UNDEF_SOCKET   ((unsigned long) INVALID_SOCKET)

/*static*/ bool Socket::mBootInitialized = false;

/*
 * Extract system-dependent error code.
 */
static inline int getSocketError(void) {
#ifdef HAVE_WINSOCK
    return WSAGetLastError();
#else
    return errno;
#endif
}

/*
 * One-time initialization for socket code.
 */
/*static*/ bool Socket::bootInit(void)
{
#ifdef HAVE_WINSOCK
    WSADATA wsaData;
    int err;

    err = WSAStartup(MAKEWORD(2, 0), &wsaData);
    if (err != 0) {
        LOG(LOG_ERROR, "socket", "Unable to start WinSock\n");
        return false;
    }

    LOG(LOG_INFO, "socket", "Using WinSock v%d.%d\n",
        LOBYTE(wsaData.wVersion), HIBYTE(wsaData.wVersion));
#endif

    mBootInitialized = true;
    return true;
}

/*
 * One-time shutdown for socket code.
 */
/*static*/ void Socket::finalShutdown(void)
{
#ifdef HAVE_WINSOCK
    WSACleanup();
#endif
    mBootInitialized = false;
}


/*
 * Simple constructor.  Allow the application to create us and then make
 * bind/connect calls.
 */
Socket::Socket(void)
    : mSock(UNDEF_SOCKET)
{
    if (!mBootInitialized)
        LOG(LOG_WARN, "socket", "WARNING: sockets not initialized\n");
}

/*
 * Destructor.  Closes the socket and resets our storage.
 */
Socket::~Socket(void)
{
    close();
}


/*
 * Create a socket and connect to the specified host and port.
 */
int Socket::connect(const char* host, int port)
{
    if (mSock != UNDEF_SOCKET) {
        LOG(LOG_WARN, "socket", "Socket already connected\n");
        return -1;
    }

    InetSocketAddress sockAddr;
    if (!sockAddr.create(host, port))
        return -1;

    //return doConnect(sockAddr);
    int foo;
    foo = doConnect(sockAddr);
    return foo;
}

/*
 * Create a socket and connect to the specified host and port.
 */
int Socket::connect(const InetAddress* addr, int port)
{
    if (mSock != UNDEF_SOCKET) {
        LOG(LOG_WARN, "socket", "Socket already connected\n");
        return -1;
    }

    InetSocketAddress sockAddr;
    if (!sockAddr.create(addr, port))
        return -1;

    return doConnect(sockAddr);
}

/*
 * Finish creating a socket by connecting to the remote host.
 *
 * Returns 0 on success.
 */
int Socket::doConnect(const InetSocketAddress& sockAddr)
{
#ifdef HAVE_WINSOCK
    SOCKET sock;
#else
    int sock;
#endif
    const InetAddress* addr = sockAddr.getAddress();
    int port = sockAddr.getPort();
    struct sockaddr_in inaddr;
    DurationTimer connectTimer;

    assert(sizeof(struct sockaddr_in) == addr->getAddressLength());
    memcpy(&inaddr, addr->getAddress(), addr->getAddressLength());
    inaddr.sin_port = htons(port);

    //fprintf(stderr, "--- connecting to %s:%d\n",
    //    sockAddr.getHostName(), port);

    sock = ::socket(PF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (sock == INVALID_SOCKET) {
        int err = getSocketError();
        LOG(LOG_ERROR, "socket", "Unable to create socket (err=%d)\n", err);
        return (err != 0) ? err : -1;
    }

    connectTimer.start();

    if (::connect(sock, (struct sockaddr*) &inaddr, sizeof(inaddr)) != 0) {
        int err = getSocketError();
        LOG(LOG_WARN, "socket", "Connect to %s:%d failed: %d\n",
            sockAddr.getHostName(), port, err);
        return (err != 0) ? err : -1;
    }

    connectTimer.stop();
    if ((long) connectTimer.durationUsecs() > 100000) {
        LOG(LOG_INFO, "socket",
            "Connect to %s:%d took %.3fs\n", sockAddr.getHostName(),
            port, ((long) connectTimer.durationUsecs()) / 1000000.0);
    }

    mSock = (unsigned long) sock;
    LOG(LOG_VERBOSE, "socket",
        "--- connected to %s:%d\n", sockAddr.getHostName(), port);
    return 0;
}


/*
 * Close the socket if it needs closing.
 */
bool Socket::close(void)
{
    if (mSock != UNDEF_SOCKET) {
        //fprintf(stderr, "--- closing socket %lu\n", mSock);
#ifdef HAVE_WINSOCK
        if (::closesocket((SOCKET) mSock) != 0)
            return false;
#else
        if (::close((int) mSock) != 0)
            return false;
#endif
    }

    mSock = UNDEF_SOCKET;

    return true;
}

/*
 * Read data from socket.
 *
 * Standard semantics: read up to "len" bytes into "buf".  Returns the
 * number of bytes read, or less than zero on error.
 */
int Socket::read(void* buf, ssize_t len) const
{
    if (mSock == UNDEF_SOCKET) {
        LOG(LOG_ERROR, "socket", "ERROR: read on invalid socket\n");
        return -500;
    }

#ifdef HAVE_WINSOCK
    SOCKET sock = (SOCKET) mSock;
#else
    int sock = (int) mSock;
#endif
    int cc;

    cc = recv(sock, (char*)buf, len, 0);
    if (cc < 0) {
        int err = getSocketError();
        return (err > 0) ? -err : -1;
    }

    return cc;
}

/*
 * Write data to a socket.
 *
 * Standard semantics: write up to "len" bytes into "buf".  Returns the
 * number of bytes written, or less than zero on error.
 */
int Socket::write(const void* buf, ssize_t len) const
{
    if (mSock == UNDEF_SOCKET) {
        LOG(LOG_ERROR, "socket", "ERROR: write on invalid socket\n");
        return -500;
    }

#ifdef HAVE_WINSOCK
    SOCKET sock = (SOCKET) mSock;
#else
    int sock = (int) mSock;
#endif
    int cc;

    cc = send(sock, (const char*)buf, len, 0);
    if (cc < 0) {
        int err = getSocketError();
        return (err > 0) ? -err : -1;
    }

    return cc;
}


/*
 * ===========================================================================
 *      Socket tests
 * ===========================================================================
 */

/*
 * Read all data from the socket.  The data is read into a buffer that
 * expands as needed.
 *
 * On exit, the buffer is returned, and the length of the data is stored
 * in "*sz".  A null byte is added to the end, but is not included in
 * the length.
 */
static char* socketReadAll(const Socket& s, int *sz)
{
    int max, r;
    char *data, *ptr, *tmp;

    data = (char*) malloc(max = 32768);
    if (data == NULL)
        return NULL;

    ptr = data;
    
    for (;;) {
        if ((ptr - data) == max) {
            tmp = (char*) realloc(data, max *= 2);
            if(tmp == 0) {
                free(data);
                return 0;
            }
        }
        r = s.read(ptr, max - (ptr - data));
        if (r == 0)
            break;
        if (r < 0) {
            LOG(LOG_WARN, "socket", "WARNING: socket read failed (res=%d)\n",r);
            break;
        }
        ptr += r;
    }

    if ((ptr - data) == max) {
        tmp = (char*) realloc(data, max + 1);
        if (tmp == NULL) {
            free(data);
            return NULL;
        }
    }
    *ptr = '\0';
    *sz = (ptr - data);
    return data;
}

/*
 * Exercise the Socket class.
 */
void android::TestSockets(void)
{
    printf("----- SOCKET TEST ------\n");
    Socket::bootInit();

    char* buf = NULL;
    int len, cc;
    const char* kTestStr =
        "GET / HTTP/1.0\n"
        "Connection: close\n"
        "\n";

    Socket sock;
    if (sock.connect("www.google.com", 80) != 0) {
        fprintf(stderr, "socket connected failed\n");
        goto bail;
    }

    cc = sock.write(kTestStr, strlen(kTestStr));
    if (cc != (int) strlen(kTestStr)) {
        fprintf(stderr, "write failed, res=%d\n", cc);
        goto bail;
    }
    buf = socketReadAll(sock, &len);

    printf("GOT '%s'\n", buf);

bail:
    sock.close();
    free(buf);
}

