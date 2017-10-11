/*
 * Copyright (C) 2016 The Android Open Source Project
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
 *
 */

#include <atomic>
#include <stdio.h>
#include <string.h>

#include "messagequeue.h"

namespace nativemididemo {

static const int messageBufferSize = 64 * 1024;
static char messageBuffer[messageBufferSize];
static std::atomic_ullong messagesLastWritePosition;

void writeMessage(const char* message)
{
    static unsigned long long lastWritePos = 0;
    size_t messageLen = strlen(message);
    if (messageLen == 0) return;

    messageLen += 1; // Also count in the null terminator.
    char buffer[1024];
    if (messageLen >= messageBufferSize) {
        snprintf(buffer, sizeof(buffer), "!!! Message too long: %zu bytes !!!", messageLen);
        message = buffer;
        messageLen = strlen(message);
    }

    size_t wrappedWritePos = lastWritePos % messageBufferSize;
    if (wrappedWritePos + messageLen >= messageBufferSize) {
        size_t tailLen = messageBufferSize - wrappedWritePos;
        memset(messageBuffer + wrappedWritePos, 0, tailLen);
        lastWritePos += tailLen;
        wrappedWritePos = 0;
    }

    memcpy(messageBuffer + wrappedWritePos, message, messageLen);
    lastWritePos += messageLen;
    messagesLastWritePosition.store(lastWritePos);
}

static char messageBufferCopy[messageBufferSize];

jobjectArray getRecentMessagesForJava(JNIEnv* env, jobject)
{
    static unsigned long long lastReadPos = 0;
    const char* overrunMessage = "";
    size_t messagesCount = 0;
    jobjectArray result = NULL;

    // First we copy the portion of the message buffer into messageBufferCopy.  If after finishing
    // the copy we notice that the writer has mutated the portion of the buffer that we were
    // copying, we report an overrun. Afterwards we can safely read messages from the copy.
    memset(messageBufferCopy, 0, sizeof(messageBufferCopy));
    unsigned long long lastWritePos = messagesLastWritePosition.load();
    if (lastWritePos - lastReadPos > messageBufferSize) {
        overrunMessage = "!!! Message buffer overrun !!!";
        messagesCount = 1;
        lastReadPos = lastWritePos;
        goto create_array;
    }
    if (lastWritePos == lastReadPos) return result;
    if (lastWritePos / messageBufferSize == lastReadPos / messageBufferSize) {
        size_t wrappedReadPos = lastReadPos % messageBufferSize;
        memcpy(messageBufferCopy + wrappedReadPos,
                messageBuffer + wrappedReadPos,
                lastWritePos % messageBufferSize - wrappedReadPos);
    } else {
        size_t wrappedReadPos = lastReadPos % messageBufferSize;
        memcpy(messageBufferCopy, messageBuffer, lastWritePos % messageBufferSize);
        memcpy(messageBufferCopy + wrappedReadPos,
                messageBuffer + wrappedReadPos,
                messageBufferSize - wrappedReadPos);
    }
    {
    unsigned long long newLastWritePos = messagesLastWritePosition.load();
    if (newLastWritePos - lastReadPos > messageBufferSize) {
        overrunMessage = "!!! Message buffer overrun !!!";
        messagesCount = 1;
        lastReadPos = lastWritePos = newLastWritePos;
        goto create_array;
    }
    }
    // Otherwise we ignore newLastWritePos, since we only have a copy of the buffer
    // up to lastWritePos.

    for (unsigned long long readPos = lastReadPos; readPos < lastWritePos; ) {
        size_t messageLen = strlen(messageBufferCopy + (readPos % messageBufferSize));
        if (messageLen != 0) {
            readPos += messageLen + 1;
            messagesCount++;
        } else {
            // Skip to the beginning of the buffer.
            readPos = (readPos / messageBufferSize + 1) * messageBufferSize;
        }
    }
    if (messagesCount == 0) {
        lastReadPos = lastWritePos;
        return result;
    }

create_array:
    result = env->NewObjectArray(
            messagesCount, env->FindClass("java/lang/String"), env->NewStringUTF(overrunMessage));
    if (lastWritePos == lastReadPos) return result;

    jsize arrayIndex = 0;
    while (lastReadPos < lastWritePos) {
        size_t wrappedReadPos = lastReadPos % messageBufferSize;
        if (messageBufferCopy[wrappedReadPos] != '\0') {
            jstring message = env->NewStringUTF(messageBufferCopy + wrappedReadPos);
            env->SetObjectArrayElement(result, arrayIndex++, message);
            lastReadPos += env->GetStringLength(message) + 1;
            env->DeleteLocalRef(message);
        } else {
            // Skip to the beginning of the buffer.
            lastReadPos = (lastReadPos / messageBufferSize + 1) * messageBufferSize;
        }
    }
    return result;
}

} // namespace nativemididemo
