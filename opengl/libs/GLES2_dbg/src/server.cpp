/*
 ** Copyright 2011, The Android Open Source Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

#include <fcntl.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <pthread.h>

#include "header.h"

namespace android
{

char sockBuff [BUFFSIZE];

int serverSock = -1, clientSock = -1;

void StopDebugServer();

static void Die(const char * msg)
{
    LOGD("\n*\n*\n* GLESv2_dbg: Die: %s \n*\n*", msg);
    StopDebugServer();
    exit(1);
}

void StartDebugServer()
{
    LOGD("GLESv2_dbg: StartDebugServer");
    if (serverSock >= 0)
        return;

    LOGD("GLESv2_dbg: StartDebugServer create socket");
    struct sockaddr_in server = {}, client = {};

    /* Create the TCP socket */
    if ((serverSock = socket(PF_INET, SOCK_STREAM, IPPROTO_TCP)) < 0) {
        Die("Failed to create socket");
    }
    /* Construct the server sockaddr_in structure */
    server.sin_family = AF_INET;                  /* Internet/IP */
    server.sin_addr.s_addr = htonl(INADDR_ANY);   /* Incoming addr */
    server.sin_port = htons(5039);       /* server port */

    /* Bind the server socket */
    socklen_t sizeofSockaddr_in = sizeof(sockaddr_in);
    if (bind(serverSock, (struct sockaddr *) &server,
             sizeof(server)) < 0) {
        Die("Failed to bind the server socket");
    }
    /* Listen on the server socket */
    if (listen(serverSock, 1) < 0) {
        Die("Failed to listen on server socket");
    }

    LOGD("server started on %d \n", server.sin_port);


    /* Wait for client connection */
    if ((clientSock =
                accept(serverSock, (struct sockaddr *) &client,
                       &sizeofSockaddr_in)) < 0) {
        Die("Failed to accept client connection");
    }

    LOGD("Client connected: %s\n", inet_ntoa(client.sin_addr));
//    fcntl(clientSock, F_SETFL, O_NONBLOCK);

    GLESv2Debugger::Message msg, cmd;
    msg.set_context_id(0);
    msg.set_function(GLESv2Debugger::Message_Function_ACK);
    msg.set_has_next_message(false);
    msg.set_expect_response(true);
    Send(msg, cmd);
}

void StopDebugServer()
{
    LOGD("GLESv2_dbg: StopDebugServer");
    if (clientSock > 0) {
        close(clientSock);
        clientSock = -1;
    }
    if (serverSock > 0) {
        close(serverSock);
        serverSock = -1;
    }

}

void Send(const GLESv2Debugger::Message & msg, GLESv2Debugger::Message & cmd)
{
    static pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;
    pthread_mutex_lock(&mutex); // TODO: this is just temporary

    static std::string str;
    const_cast<GLESv2Debugger::Message &>(msg).set_context_id(pthread_self());
    msg.SerializeToString(&str);
    unsigned len = str.length();
    len = htonl(len);
    int sent = -1;
    sent = send(clientSock, (const char *)&len, sizeof(len), 0);
    if (sent != sizeof(len)) {
        LOGD("actual sent=%d expected=%d clientSock=%d", sent, sizeof(len), clientSock);
        Die("Failed to send message length");
    }
    sent = send(clientSock, str.c_str(), str.length(), 0);
    if (sent != str.length()) {
        LOGD("actual sent=%d expected=%d clientSock=%d", sent, str.length(), clientSock);
        Die("Failed to send message");
    }

    if (!msg.expect_response()) {
        pthread_mutex_unlock(&mutex);
        return;
    }

    int received = recv(clientSock, sockBuff, 4, MSG_WAITALL);
    if (received < 0)
        Die("Failed to receive response");
    else if (4 != received) {
        LOGD("received %dB: %.8X", received, *(unsigned *)sockBuff);
        Die("Received length mismatch, expected 4");
    }
    len = ntohl(*(unsigned *)sockBuff);
    static void * buffer = NULL;
    static unsigned bufferSize = 0;
    if (bufferSize < len) {
        buffer = realloc(buffer, len);
        ASSERT(buffer);
        bufferSize = len;
    }
    received = recv(clientSock, buffer, len, MSG_WAITALL);
    if (received < 0)
        Die("Failed to receive response");
    else if (len != received)
        Die("Received length mismatch");
    cmd.Clear();
    cmd.ParseFromArray(buffer, len);

    //LOGD("Message sent tid=%lu len=%d", pthread_self(), str.length());
    pthread_mutex_unlock(&mutex);
}

}; // namespace android {
