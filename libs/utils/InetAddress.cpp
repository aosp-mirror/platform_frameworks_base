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
# include <winsock2.h>
#else
# include <sys/types.h>
# include <sys/socket.h>
# include <netinet/in.h>
//# include <arpa/inet.h>
# include <netdb.h>
#endif

#include <utils/inet_address.h>
#include <utils/threads.h>
#include <utils/Log.h>

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <assert.h>

using namespace android;


/*
 * ===========================================================================
 *      InetAddress
 * ===========================================================================
 */

// lock for the next couple of functions; could tuck into InetAddress
static Mutex*   gGHBNLock;

/*
 * Lock/unlock access to the hostent struct returned by gethostbyname().
 */
static inline void lock_gethostbyname(void)
{
    if (gGHBNLock == NULL)
        gGHBNLock = new Mutex;
    gGHBNLock->lock();
}
static inline void unlock_gethostbyname(void)
{
    assert(gGHBNLock != NULL);
    gGHBNLock->unlock();
}


/*
 * Constructor -- just init members.  This is private so that callers
 * are required to use getByName().
 */
InetAddress::InetAddress(void)
    : mAddress(NULL), mLength(-1), mName(NULL)
{
}

/*
 * Destructor -- free address storage.
 */
InetAddress::~InetAddress(void)
{
    delete[] (char*) mAddress;
    delete[] mName;
}

/*
 * Copy constructor.
 */
InetAddress::InetAddress(const InetAddress& orig)
{
    *this = orig;   // use assignment code
}

/*
 * Assignment operator.
 */
InetAddress& InetAddress::operator=(const InetAddress& addr)
{
    // handle self-assignment
    if (this == &addr)
        return *this;
    // copy mLength and mAddress
    mLength = addr.mLength;
    if (mLength > 0) {
        mAddress = new char[mLength];
        memcpy(mAddress, addr.mAddress, mLength);
        LOG(LOG_DEBUG, "socket",
            "HEY: copied %d bytes in assignment operator\n", mLength);
    } else {
        mAddress = NULL;
    }
    // copy mName
    mName = new char[strlen(addr.mName)+1];
    strcpy(mName, addr.mName);

    return *this;
}

/*
 * Create a new object from a name or a dotted-number IP notation.
 *
 * Returns NULL on failure.
 */
InetAddress*
InetAddress::getByName(const char* host)
{
    InetAddress* newAddr = NULL;
    struct sockaddr_in addr;
    struct hostent* he;
    DurationTimer hostTimer, lockTimer;

    // gethostbyname() isn't reentrant, so we need to lock things until
    // we can copy the data out.
    lockTimer.start();
    lock_gethostbyname();
    hostTimer.start();

    he = gethostbyname(host);
    if (he == NULL) {
        LOG(LOG_WARN, "socket", "WARNING: cannot resolve host %s\n", host);
        unlock_gethostbyname();
        return NULL;
    }

    memcpy(&addr.sin_addr, he->h_addr, he->h_length);
    addr.sin_family = he->h_addrtype;
    addr.sin_port = 0;

    // got it, unlock us
    hostTimer.stop();
    he = NULL;
    unlock_gethostbyname();

    lockTimer.stop();
    if ((long) lockTimer.durationUsecs() > 100000) {
        long lockTime = (long) lockTimer.durationUsecs();
        long hostTime = (long) hostTimer.durationUsecs();
        LOG(LOG_DEBUG, "socket",
            "Lookup of %s took %.3fs (gethostbyname=%.3fs lock=%.3fs)\n",
            host, lockTime / 1000000.0, hostTime / 1000000.0,
            (lockTime - hostTime) / 1000000.0);
    }

    // Alloc storage and copy it over.
    newAddr = new InetAddress();
    if (newAddr == NULL)
        return NULL;

    newAddr->mLength = sizeof(struct sockaddr_in);
    newAddr->mAddress = new char[sizeof(struct sockaddr_in)];
    if (newAddr->mAddress == NULL) {
        delete newAddr;
        return NULL;
    }
    memcpy(newAddr->mAddress, &addr, newAddr->mLength);

    // Keep this for debug messages.
    newAddr->mName = new char[strlen(host)+1];
    if (newAddr->mName == NULL) {
        delete newAddr;
        return NULL;
    }
    strcpy(newAddr->mName, host);

    return newAddr;
}


/*
 * ===========================================================================
 *      InetSocketAddress
 * ===========================================================================
 */

/*
 * Create an address with the host wildcard (INADDR_ANY).
 */
bool InetSocketAddress::create(int port)
{
    assert(mAddress == NULL);

    mAddress = InetAddress::getByName("0.0.0.0");
    if (mAddress == NULL)
        return false;
    mPort = port;
    return true;
}

/*
 * Create address with host and port specified.
 */
bool InetSocketAddress::create(const InetAddress* addr, int port)
{
    assert(mAddress == NULL);

    mAddress = new InetAddress(*addr);  // make a copy
    if (mAddress == NULL)
        return false;
    mPort = port;
    return true;
}

/*
 * Create address with host and port specified.
 */
bool InetSocketAddress::create(const char* host, int port)
{
    assert(mAddress == NULL);

    mAddress = InetAddress::getByName(host);
    if (mAddress == NULL)
        return false;
    mPort = port;
    return true;
}

