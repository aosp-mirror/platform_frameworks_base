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
// Socket class.  Modeled after Java classes.
//
#ifndef _RUNTIME_SOCKET_H
#define _RUNTIME_SOCKET_H

#include <utils/inet_address.h>
#include <sys/types.h>

namespace android {

/*
 * Basic socket class, needed to abstract away the differences between
 * BSD sockets and WinSock.  This establishes a streaming network
 * connection (TCP/IP) to somebody.
 */
class Socket {
public:
    Socket(void);
    ~Socket(void);

    // Create a connection to somewhere.
    // Return 0 on success.
    int connect(const char* host, int port);
    int connect(const InetAddress* addr, int port);


    // Close the socket.  Don't try to use this object again after
    // calling this.  Returns false on failure.
    bool close(void);

    // If we created the socket without an address, we can use these
    // to finish the connection.  Returns 0 on success.
    int bind(const SocketAddress& bindPoint);
    int connect(const SocketAddress& endPoint);

    // Here we deviate from the traditional object-oriented fanciness
    // and just provide read/write operators instead of getters for
    // objects that abstract a stream.
    //
    // Standard read/write semantics.
    int read(void* buf, ssize_t len) const;
    int write(const void* buf, ssize_t len) const;

    // This must be called once, at program startup.
    static bool bootInit(void);
    static void finalShutdown(void);

private:
    // Internal function that establishes a connection.
    int doConnect(const InetSocketAddress& addr);

    unsigned long   mSock;      // holds SOCKET or int

    static bool     mBootInitialized;
};


// debug -- unit tests
void TestSockets(void);

}; // namespace android

#endif // _RUNTIME_SOCKET_H
