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
// Internet address classes.  Modeled after Java classes.
//
#ifndef _RUNTIME_INET_ADDRESS_H
#define _RUNTIME_INET_ADDRESS_H

#ifdef HAVE_ANDROID_OS
#error DO NOT USE THIS FILE IN THE DEVICE BUILD
#endif


namespace android {

/*
 * This class holds Internet addresses.  Perhaps more useful is its
 * ability to look up addresses by name.
 *
 * Invoke one of the static factory methods to create a new object.
 */
class InetAddress {
public:
    virtual ~InetAddress(void);

    // create from w.x.y.z or foo.bar.com notation
    static InetAddress* getByName(const char* host);

    // copy-construction
    InetAddress(const InetAddress& orig);

    const void* getAddress(void) const { return mAddress; }
    int getAddressLength(void) const { return mLength; }
    const char* getHostName(void) const { return mName; }

private:
    InetAddress(void);
    // assignment (private)
    InetAddress& operator=(const InetAddress& addr);

    // use a void* here so we don't have to expose actual socket headers
    void*       mAddress;   // this is really a ptr to sockaddr_in
    int         mLength;
    char*       mName;
};


/*
 * Base class for socket addresses.
 */
class SocketAddress {
public:
    SocketAddress() {}
    virtual ~SocketAddress() {}
};


/*
 * Internet address class.  This combines an InetAddress with a port.
 */
class InetSocketAddress : public SocketAddress {
public:
    InetSocketAddress() :
        mAddress(0), mPort(-1)
        {}
    ~InetSocketAddress(void) {
        delete mAddress;
    }

    // Create an address with a host wildcard (useful for servers).
    bool create(int port);
    // Create an address with the specified host and port.
    bool create(const InetAddress* addr, int port);
    // Create an address with the specified host and port.  Does the
    // hostname lookup.
    bool create(const char* host, int port);

    const InetAddress* getAddress(void) const { return mAddress; }
    const int getPort(void) const { return mPort; }
    const char* getHostName(void) const { return mAddress->getHostName(); }

private:
    InetAddress* mAddress;
    int         mPort;
};

}; // namespace android

#endif // _RUNTIME_INET_ADDRESS_H
