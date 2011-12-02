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

int gServerSocket, gClientSocket;

void startServer(int port) {
    if (gServerSocket > 0) {
        LOGD("startServer: server socket already open!");
        return;
    }

    gServerSocket = socket(PF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (gServerSocket < 0) {
        LOGE("Error (%d) while creating socket. Check if app has network permissions.",
                                                                            gServerSocket);
        exit(-1);
    }

    struct sockaddr_in server, client;

    server.sin_family = AF_INET;
    server.sin_addr.s_addr = htonl(INADDR_ANY);
    server.sin_port = htons(port);

    socklen_t sockaddr_len = sizeof(sockaddr_in);
    if (bind(gServerSocket, (struct sockaddr *) &server, sizeof(server)) < 0) {
        close(gServerSocket);
        LOGE("Failed to bind the server socket");
        exit(-1);
    }

    if (listen(gServerSocket, 1) < 0) {
        close(gServerSocket);
        LOGE("Failed to listen on server socket");
        exit(-1);
    }

    LOGD("startServer: server started on %d", port);

    /* Wait for client connection */
    if ((gClientSocket = accept(gServerSocket, (struct sockaddr *)&client, &sockaddr_len)) < 0) {
        close(gServerSocket);
        LOGE("Failed to accept client connection");
        exit(-1);
    }

    LOGD("startServer: client connected: %s", inet_ntoa(client.sin_addr));
}

void stopServer() {
    if (gServerSocket > 0) {
        close(gServerSocket);
        close(gClientSocket);
        gServerSocket = gClientSocket = 0;
    }
}

/** Send GLMessage to the receiver on the host. */
void traceGLMessage(GLMessage *call) {
    if (gClientSocket <= 0) {
        LOGE("traceGLMessage: Attempt to send while client connection is not established");
        return;
    }

    std::string str;
    call->SerializeToString(&str);
    const uint32_t len = str.length();

    int n = write(gClientSocket, &len, sizeof len);
    if (n != sizeof len) {
        LOGE("traceGLMessage: Error (%d) while writing message length\n", n);
        stopServer();
        exit(-1);
    }

    n = write(gClientSocket, str.data(), str.length());
    if (n != (int) str.length()) {
        LOGE("traceGLMessage: Error while writing out message, result = %d, length = %d\n",
            n, str.length());
        stopServer();
        exit(-1);
    }
}

};  // namespace gltrace
};  // namespace android
