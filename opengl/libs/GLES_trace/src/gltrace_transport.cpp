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

#include <stdlib.h>
#include <unistd.h>

#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <netinet/in.h>

#include <cutils/log.h>

#include "gltrace_transport.h"

namespace android {
namespace gltrace {

int acceptClientConnection(char *sockname) {
    int serverSocket = socket(AF_LOCAL, SOCK_STREAM, 0);
    if (serverSocket < 0) {
        ALOGE("Error (%d) while creating socket. Check if app has network permissions.",
                                                                            serverSocket);
        return -1;
    }

    struct sockaddr_un server, client;

    memset(&server, 0, sizeof server);
    server.sun_family = AF_UNIX;
    // the first byte of sun_path should be '\0' for abstract namespace
    strcpy(server.sun_path + 1, sockname);

    // note that sockaddr_len should be set to the exact size of the buffer that is used.
    socklen_t sockaddr_len = sizeof(server.sun_family) + strlen(sockname) + 1;
    if (bind(serverSocket, (struct sockaddr *) &server, sockaddr_len) < 0) {
        close(serverSocket);
        ALOGE("Failed to bind the server socket");
        return -1;
    }

    if (listen(serverSocket, 1) < 0) {
        close(serverSocket);
        ALOGE("Failed to listen on server socket");
        return -1;
    }

    ALOGD("gltrace::waitForClientConnection: server listening @ path %s", sockname);

    int clientSocket = accept(serverSocket, (struct sockaddr *)&client, &sockaddr_len);
    if (clientSocket < 0) {
        close(serverSocket);
        ALOGE("Failed to accept client connection");
        return -1;
    }

    ALOGD("gltrace::waitForClientConnection: client connected.");

    // do not accept any more incoming connections
    close(serverSocket);

    return clientSocket;
}

TCPStream::TCPStream(int socket) {
    mSocket = socket;
    pthread_mutex_init(&mSocketWriteMutex, NULL);
}

TCPStream::~TCPStream() {
    pthread_mutex_destroy(&mSocketWriteMutex);
}

void TCPStream::closeStream() {
    if (mSocket > 0) {
        close(mSocket);
        mSocket = 0;
    }
}

int TCPStream::send(void *buf, size_t len) {
    if (mSocket <= 0) {
        return -1;
    }

    pthread_mutex_lock(&mSocketWriteMutex);
    int n = write(mSocket, buf, len);
    pthread_mutex_unlock(&mSocketWriteMutex);

    return n;
}

int TCPStream::receive(void *data, size_t len) {
    if (mSocket <= 0) {
        return -1;
    }

    return read(mSocket, data, len);
}

BufferedOutputStream::BufferedOutputStream(TCPStream *stream, size_t bufferSize) {
    mStream = stream;

    mBufferSize = bufferSize;
    mStringBuffer = "";
    mStringBuffer.reserve(bufferSize);
}

int BufferedOutputStream::flush() {
    if (mStringBuffer.size() == 0) {
        return 0;
    }

    int n = mStream->send((void *)mStringBuffer.data(), mStringBuffer.size());
    mStringBuffer.clear();
    return n;
}

void BufferedOutputStream::enqueueMessage(GLMessage *msg) {
    const uint32_t len = msg->ByteSize();

    mStringBuffer.append((const char *)&len, sizeof(len));    // append header
    msg->AppendToString(&mStringBuffer);                      // append message
}

int BufferedOutputStream::send(GLMessage *msg) {
    enqueueMessage(msg);

    if (mStringBuffer.size() > mBufferSize) {
        return flush();
    }

    return 0;
}

};  // namespace gltrace
};  // namespace android
