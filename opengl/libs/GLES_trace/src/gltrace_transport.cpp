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

#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>

#include <cutils/log.h>

#include "gltrace_transport.h"

namespace android {
namespace gltrace {

int acceptClientConnection(int serverPort) {
    int serverSocket = socket(PF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (serverSocket < 0) {
        ALOGE("Error (%d) while creating socket. Check if app has network permissions.",
                                                                            serverSocket);
        return -1;
    }

    struct sockaddr_in server, client;

    server.sin_family = AF_INET;
    server.sin_addr.s_addr = htonl(INADDR_ANY);
    server.sin_port = htons(serverPort);

    socklen_t sockaddr_len = sizeof(sockaddr_in);
    if (bind(serverSocket, (struct sockaddr *) &server, sizeof(server)) < 0) {
        close(serverSocket);
        ALOGE("Failed to bind the server socket");
        return -1;
    }

    if (listen(serverSocket, 1) < 0) {
        close(serverSocket);
        ALOGE("Failed to listen on server socket");
        return -1;
    }

    ALOGD("gltrace::waitForClientConnection: server listening @ port %d", serverPort);

    int clientSocket = accept(serverSocket, (struct sockaddr *)&client, &sockaddr_len);
    if (clientSocket < 0) {
        close(serverSocket);
        ALOGE("Failed to accept client connection");
        return -1;
    }

    ALOGD("gltrace::waitForClientConnection: client connected: %s", inet_ntoa(client.sin_addr));

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
