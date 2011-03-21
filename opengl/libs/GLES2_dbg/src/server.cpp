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

#include <sys/ioctl.h>
#include <unistd.h>
#include <sys/socket.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <pthread.h>

#include "header.h"

namespace android
{

int serverSock = -1, clientSock = -1;

int timeMode = SYSTEM_TIME_THREAD;

static void Die(const char * msg)
{
    LOGD("\n*\n*\n* GLESv2_dbg: Die: %s \n*\n*", msg);
    StopDebugServer();
    exit(1);
}

void StartDebugServer(unsigned short port)
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
    server.sin_addr.s_addr = htonl(INADDR_LOOPBACK);   /* Incoming addr */
    server.sin_port = htons(port);       /* server port */

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

void Receive(glesv2debugger::Message & cmd)
{
    unsigned len = 0;

    int received = recv(clientSock, &len, 4, MSG_WAITALL);
    if (received < 0)
        Die("Failed to receive response length");
    else if (4 != received) {
        LOGD("received %dB: %.8X", received, len);
        Die("Received length mismatch, expected 4");
    }
    len = ntohl(len);
    static void * buffer = NULL;
    static unsigned bufferSize = 0;
    if (bufferSize < len) {
        buffer = realloc(buffer, len);
        assert(buffer);
        bufferSize = len;
    }
    received = recv(clientSock, buffer, len, MSG_WAITALL);
    if (received < 0)
        Die("Failed to receive response");
    else if (len != received)
        Die("Received length mismatch");
    cmd.Clear();
    cmd.ParseFromArray(buffer, len);
}

bool TryReceive(glesv2debugger::Message & cmd)
{
    fd_set readSet;
    FD_ZERO(&readSet);
    FD_SET(clientSock, &readSet);
    timeval timeout;
    timeout.tv_sec = timeout.tv_usec = 0;

    int rc = select(clientSock + 1, &readSet, NULL, NULL, &timeout);
    if (rc < 0)
        Die("failed to select clientSock");

    bool received = false;
    if (FD_ISSET(clientSock, &readSet)) {
        LOGD("TryReceive: avaiable for read");
        Receive(cmd);
        return true;
    }
    return false;
}

float Send(const glesv2debugger::Message & msg, glesv2debugger::Message & cmd)
{
    static pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;
    pthread_mutex_lock(&mutex); // TODO: this is just temporary

    if (msg.function() != glesv2debugger::Message_Function_ACK)
        assert(msg.has_context_id() && msg.context_id() != 0);
    static std::string str;
    msg.SerializeToString(&str);
    uint32_t len = htonl(str.length());
    int sent = -1;
    sent = send(clientSock, &len, sizeof(len), 0);
    if (sent != sizeof(len)) {
        LOGD("actual sent=%d expected=%d clientSock=%d", sent, sizeof(len), clientSock);
        Die("Failed to send message length");
    }
    nsecs_t c0 = systemTime(timeMode);
    sent = send(clientSock, str.c_str(), str.length(), 0);
    float t = (float)ns2ms(systemTime(timeMode) - c0);
    if (sent != str.length()) {
        LOGD("actual sent=%d expected=%d clientSock=%d", sent, str.length(), clientSock);
        Die("Failed to send message");
    }

    // try to receive commands even though not expecting response,
    // since client can send SETPROP commands anytime
    if (!msg.expect_response()) {
        if (TryReceive(cmd)) {
            LOGD("Send: TryReceived");
            if (glesv2debugger::Message_Function_SETPROP == cmd.function())
                LOGD("Send: received SETPROP");
            else
                LOGD("Send: received something else");
        }
    } else
        Receive(cmd);

    pthread_mutex_unlock(&mutex);
    return t;
}

void SetProp(DbgContext * const dbg, const glesv2debugger::Message & cmd)
{
    switch (cmd.prop()) {
    case glesv2debugger::Message_Prop_Capture:
        LOGD("SetProp Message_Prop_Capture %d", cmd.arg0());
        capture = cmd.arg0();
        break;
    case glesv2debugger::Message_Prop_TimeMode:
        LOGD("SetProp Message_Prop_TimeMode %d", cmd.arg0());
        timeMode = cmd.arg0();
        break;
    case glesv2debugger::Message_Prop_ExpectResponse:
        LOGD("SetProp Message_Prop_ExpectResponse %d=%d", cmd.arg0(), cmd.arg1());
        dbg->expectResponse.Bit((glesv2debugger::Message_Function)cmd.arg0(), cmd.arg1());
        break;
    default:
        assert(0);
    }
}

int * MessageLoop(FunctionCall & functionCall, glesv2debugger::Message & msg,
                  const glesv2debugger::Message_Function function)
{
    DbgContext * const dbg = getDbgContextThreadSpecific();
    const int * ret = 0;
    glesv2debugger::Message cmd;
    msg.set_context_id(reinterpret_cast<int>(dbg));
    msg.set_type(glesv2debugger::Message_Type_BeforeCall);
    const bool expectResponse = dbg->expectResponse.Bit(function);
    msg.set_expect_response(expectResponse);
    msg.set_function(function);
    if (!expectResponse)
        cmd.set_function(glesv2debugger::Message_Function_CONTINUE);
    Send(msg, cmd);
    while (true) {
        msg.Clear();
        nsecs_t c0 = systemTime(timeMode);
        switch (cmd.function()) {
        case glesv2debugger::Message_Function_CONTINUE:
            ret = functionCall(&dbg->hooks->gl, msg);
            while (GLenum error = dbg->hooks->gl.glGetError())
                LOGD("Function=%u glGetError() = 0x%.4X", function, error);
            if (!msg.has_time()) // some has output data copy, so time inside call
                msg.set_time((systemTime(timeMode) - c0) * 1e-6f);
            msg.set_context_id(reinterpret_cast<int>(dbg));
            msg.set_function(function);
            msg.set_type(glesv2debugger::Message_Type_AfterCall);
            msg.set_expect_response(expectResponse);
            if (!expectResponse)
                cmd.set_function(glesv2debugger::Message_Function_SKIP);
            Send(msg, cmd);
            break;
        case glesv2debugger::Message_Function_SKIP:
            return const_cast<int *>(ret);
        case glesv2debugger::Message_Function_SETPROP:
            SetProp(dbg, cmd);
            Receive(cmd);
            break;
        default:
            assert(0); //GenerateCall(msg, cmd);
            break;
        }
    }
    return 0;
}
}; // namespace android {
