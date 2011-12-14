/*
 * Copyright 2011, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef __GLTRACE_TRANSPORT_H_
#define __GLTRACE_TRANSPORT_H_

#include <pthread.h>

#include "gltrace.pb.h"

namespace android {
namespace gltrace {

/**
 * TCPStream provides a TCP based communication channel from the device to
 * the host for transferring GLMessages.
 */
class TCPStream {
    int mSocket;
    pthread_mutex_t mSocketWriteMutex;
public:
    /** Create a TCP based communication channel over @socket */
    TCPStream(int socket);
    ~TCPStream();

    /** Close the channel. */
    void closeStream();

    /** Send @data of size @len to host. . Returns -1 on error, 0 on success. */
    int send(void *data, size_t len);

    /** Receive data into @buf from the remote end. This is a blocking call. */
    int receive(void *buf, size_t size);
};

/**
 * BufferedOutputStream provides buffering of data sent to the underlying
 * unbuffered channel.
 */
class BufferedOutputStream {
    TCPStream *mStream;

    size_t mBufferSize;
    std::string mStringBuffer;

    /** Enqueue message into internal buffer. */
    void enqueueMessage(GLMessage *msg);
public:
    /**
     * Construct a Buffered stream of size @bufferSize, using @stream as
     * its underlying channel for transport.
     */
    BufferedOutputStream(TCPStream *stream, size_t bufferSize);

    /**
     * Send @msg. The message could be buffered and sent later with a
     * subsequent message. Returns -1 on error, 0 on success.
     */
    int send(GLMessage *msg);

    /** Send any buffered messages, returns -1 on error, 0 on success. */
    int flush();
};

/**
 * Utility method: start a server at @serverPort, and wait for a client
 * connection. Returns the connected client socket on success, or -1 on failure.
 */
int acceptClientConnection(int serverPort);

};
};

#endif
