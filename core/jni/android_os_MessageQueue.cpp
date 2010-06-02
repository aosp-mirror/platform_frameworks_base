/*
 * Copyright (C) 2010 The Android Open Source Project
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

#define LOG_TAG "MQNative"

#include "JNIHelp.h"

#include <sys/socket.h>
#include <sys/select.h>
#include <sys/time.h>
#include <fcntl.h>

#include <android_runtime/AndroidRuntime.h>
#include <utils/SystemClock.h>
#include <utils/Vector.h>
#include <utils/Log.h>

using namespace android;

// ----------------------------------------------------------------------------

static struct {
    jclass mClass;

    jfieldID mObject;   // native object attached to the DVM MessageQueue
} gMessageQueueOffsets;

static struct {
    jclass mClass;
    jmethodID mConstructor;
} gKeyEventOffsets;

// TODO: also MotionEvent offsets etc. a la gKeyEventOffsets

static struct {
    jclass mClass;
    jmethodID mObtain;      // obtain(Handler h, int what, Object obj)
} gMessageOffsets;

// ----------------------------------------------------------------------------

static void doThrow(JNIEnv* env, const char* exc, const char* msg = NULL)
{
    if (jniThrowException(env, exc, msg) != 0)
        assert(false);
}

// ----------------------------------------------------------------------------

class MessageQueueNative {
public:
    MessageQueueNative(int readSocket, int writeSocket);
    ~MessageQueueNative();

    // select on all FDs until the designated time; forever if wakeupTime is < 0
    int waitForSignal(jobject mqueue, jlong wakeupTime);

    // signal the queue-ready pipe
    void signalQueuePipe();

    // Specify a new input pipe, passing in responsibility for the socket fd and
    // ashmem region
    int registerInputPipe(JNIEnv* env, int socketFd, int memRegionFd, jobject handler);

    // Forget about this input pipe, closing the socket and ashmem region as well
    int unregisterInputPipe(JNIEnv* env, int socketFd);

    size_t numRegisteredPipes() const { return mInputPipes.size(); }

private:
    struct InputPipe {
        int fd;
        int region;
        jobject handler;

        InputPipe() {}
        InputPipe(int _fd, int _r, jobject _h) : fd(_fd), region(_r), handler(_h) {}
    };

    // consume an event from a socket, put it on the DVM MessageQueue indicated,
    // and notify the other end of the pipe that we've consumed it.
    void queueEventFromPipe(const InputPipe& pipe, jobject mqueue);

    int mQueueReadFd, mQueueWriteFd;
    Vector<InputPipe> mInputPipes;
};

MessageQueueNative::MessageQueueNative(int readSocket, int writeSocket) 
        : mQueueReadFd(readSocket), mQueueWriteFd(writeSocket) {
}

MessageQueueNative::~MessageQueueNative() {
}

int MessageQueueNative::waitForSignal(jobject mqueue, jlong timeoutMillis) {
    struct timeval tv, *timeout;
    fd_set fdset;

    if (timeoutMillis < 0) {
        timeout = NULL;
    } else {
        if (timeoutMillis == 0) {
            tv.tv_sec = 0;
            tv.tv_usec = 0;
        } else {
            tv.tv_sec = (timeoutMillis / 1000);
            tv.tv_usec = (timeoutMillis - (1000 * tv.tv_sec)) * 1000;
        }
        timeout = &tv;
    }

    // always rebuild the fd set from scratch
    FD_ZERO(&fdset);

    // the queue signalling pipe
    FD_SET(mQueueReadFd, &fdset);
    int maxFd = mQueueReadFd;

    // and the input sockets themselves
    for (size_t i = 0; i < mInputPipes.size(); i++) {
        FD_SET(mInputPipes[i].fd, &fdset);
        if (maxFd < mInputPipes[i].fd) {
            maxFd = mInputPipes[i].fd;
        }
    }

    // now wait
    int res = select(maxFd + 1, &fdset, NULL, NULL, timeout);

    // Error?  Just return it and bail
    if (res < 0) return res;

    // What happened -- timeout or actual data arrived?
    if (res == 0) {
        // select() returned zero, which means we timed out, which means that it's time
        // to deliver the head element that was already on the queue.  Just fall through
        // without doing anything else.
    } else {
        // Data (or a queue signal) arrived!
        //
        // If it's data, pull the data off the pipe, build a new Message with it, put it on
        // the DVM-side MessageQueue (pointed to by the 'mqueue' parameter), then proceed
        // into the queue-signal case.
        //
        // If a queue signal arrived, just consume any data pending in that pipe and
        // fall out.
        bool queue_signalled = (FD_ISSET(mQueueReadFd, &fdset) != 0);

        for (size_t i = 0; i < mInputPipes.size(); i++) {
            if (FD_ISSET(mInputPipes[i].fd, &fdset)) {
                queueEventFromPipe(mInputPipes[i], mqueue);
                queue_signalled = true;     // we know a priori that queueing the event does this
            }
        }

        // Okay, stuff went on the queue.  Consume the contents of the signal pipe
        // now that we're awake and about to start dispatching messages again.
        if (queue_signalled) {
            uint8_t buf[16];
            ssize_t nRead;
            do {
                nRead = read(mQueueReadFd, buf, sizeof(buf));
            } while (nRead > 0); // in nonblocking mode we'll get -1 when it's drained
        }
    }

    return 0;
}

// signals to the queue pipe are one undefined byte.  it's just a "data has arrived" token
// and the pipe is drained on receipt of at least one signal
void MessageQueueNative::signalQueuePipe() {
    int dummy[1];
    write(mQueueWriteFd, dummy, 1);
}

void MessageQueueNative::queueEventFromPipe(const InputPipe& inPipe, jobject mqueue) {
    // !!! TODO: read the event data from the InputPipe's ashmem region, convert it to a DVM
    // event object of the proper type [MotionEvent or KeyEvent], create a Message holding
    // it as appropriate, point the Message to the Handler associated with this InputPipe,
    // and call up to the DVM MessageQueue implementation to enqueue it for delivery.
}

// the number of registered pipes on success; < 0 on error
int MessageQueueNative::registerInputPipe(JNIEnv* env,
        int socketFd, int memRegionFd, jobject handler) {
    // make sure this fd is not already known to us
    for (size_t i = 0; i < mInputPipes.size(); i++) {
        if (mInputPipes[i].fd == socketFd) {
            LOGE("Attempt to re-register input fd %d", socketFd);
            return -1;
        }
    }

    mInputPipes.push( InputPipe(socketFd, memRegionFd, env->NewGlobalRef(handler)) );
    return mInputPipes.size();
}

// Remove an input pipe from our bookkeeping.  Also closes the socket and ashmem
// region file descriptor!
//
// returns the number of remaining input pipes on success; < 0 on error
int MessageQueueNative::unregisterInputPipe(JNIEnv* env, int socketFd) {
    for (size_t i = 0; i < mInputPipes.size(); i++) {
        if (mInputPipes[i].fd == socketFd) {
            close(mInputPipes[i].fd);
            close(mInputPipes[i].region);
            env->DeleteGlobalRef(mInputPipes[i].handler);
            mInputPipes.removeAt(i);
            return mInputPipes.size();
        }
    }
    LOGW("Tried to unregister input pipe %d but not found!", socketFd);
    return -1;
}

// ----------------------------------------------------------------------------

namespace android {
    
static void android_os_MessageQueue_init(JNIEnv* env, jobject obj) {
    // Create the pipe
    int fds[2];
    int err = socketpair(AF_LOCAL, SOCK_STREAM, 0, fds);
    if (err != 0) {
        doThrow(env, "java/lang/RuntimeException", "Unable to create socket pair");
    }

    MessageQueueNative *mqn = new MessageQueueNative(fds[0], fds[1]);
    if (mqn == NULL) {
        close(fds[0]);
        close(fds[1]);
        doThrow(env, "java/lang/RuntimeException", "Unable to allocate native queue");
    }

    int flags = fcntl(fds[0], F_GETFL);
    fcntl(fds[0], F_SETFL, flags | O_NONBLOCK);
    flags = fcntl(fds[1], F_GETFL);
    fcntl(fds[1], F_SETFL, flags | O_NONBLOCK);

    env->SetIntField(obj, gMessageQueueOffsets.mObject, (jint)mqn);
}

static void android_os_MessageQueue_signal(JNIEnv* env, jobject obj) {
    MessageQueueNative *mqn = (MessageQueueNative*) env->GetIntField(obj, gMessageQueueOffsets.mObject);
    if (mqn != NULL) {
        mqn->signalQueuePipe();
    } else {
        doThrow(env, "java/lang/IllegalStateException", "Queue not initialized");
    }
}

static int android_os_MessageQueue_waitForNext(JNIEnv* env, jobject obj, jlong when) {
    MessageQueueNative *mqn = (MessageQueueNative*) env->GetIntField(obj, gMessageQueueOffsets.mObject);
    if (mqn != NULL) {
        int res = mqn->waitForSignal(obj, when);
        return res; // the DVM event, if any, has been constructed and queued now
    }

    return -1;
}

static void android_os_MessageQueue_registerInputStream(JNIEnv* env, jobject obj,
        jint socketFd, jint regionFd, jobject handler) {
    MessageQueueNative *mqn = (MessageQueueNative*) env->GetIntField(obj, gMessageQueueOffsets.mObject);
    if (mqn != NULL) {
        mqn->registerInputPipe(env, socketFd, regionFd, handler);
    } else {
        doThrow(env, "java/lang/IllegalStateException", "Queue not initialized");
    }
}

static void android_os_MessageQueue_unregisterInputStream(JNIEnv* env, jobject obj,
        jint socketFd) {
    MessageQueueNative *mqn = (MessageQueueNative*) env->GetIntField(obj, gMessageQueueOffsets.mObject);
    if (mqn != NULL) {
        mqn->unregisterInputPipe(env, socketFd);
    } else {
        doThrow(env, "java/lang/IllegalStateException", "Queue not initialized");
    }
}

// ----------------------------------------------------------------------------

const char* const kKeyEventPathName = "android/view/KeyEvent";
const char* const kMessagePathName = "android/os/Message";
const char* const kMessageQueuePathName = "android/os/MessageQueue";

static JNINativeMethod gMessageQueueMethods[] = {
    /* name, signature, funcPtr */
    { "nativeInit", "()V", (void*)android_os_MessageQueue_init },
    { "nativeSignal", "()V", (void*)android_os_MessageQueue_signal },
    { "nativeWaitForNext", "(J)I", (void*)android_os_MessageQueue_waitForNext },
    { "nativeRegisterInputStream", "(IILandroid/os/Handler;)V", (void*)android_os_MessageQueue_registerInputStream },
    { "nativeUnregisterInputStream", "(I)V", (void*)android_os_MessageQueue_unregisterInputStream },
};

int register_android_os_MessageQueue(JNIEnv* env) {
    jclass clazz;

    clazz = env->FindClass(kMessageQueuePathName);
    LOG_FATAL_IF(clazz == NULL, "Unable to find class android.os.MessageQueue");
    gMessageQueueOffsets.mClass = (jclass) env->NewGlobalRef(clazz);
    gMessageQueueOffsets.mObject = env->GetFieldID(clazz, "mObject", "I");
    assert(gMessageQueueOffsets.mObject);

    clazz = env->FindClass(kMessagePathName);
    LOG_FATAL_IF(clazz == NULL, "Unable to find class android.os.Message");
    gMessageOffsets.mClass = (jclass) env->NewGlobalRef(clazz);
    gMessageOffsets.mObtain = env->GetStaticMethodID(clazz, "obtain",
            "(Landroid/os/Handler;ILjava/lang/Object;)Landroid/os/Message;");
    assert(gMessageOffsets.mObtain);

    clazz = env->FindClass(kKeyEventPathName);
    LOG_FATAL_IF(clazz == NULL, "Unable to find class android.view.KeyEvent");
    gKeyEventOffsets.mClass = (jclass) env->NewGlobalRef(clazz);
    gKeyEventOffsets.mConstructor = env->GetMethodID(clazz, "<init>", "(JJIIIIIII)V");
    assert(gKeyEventOffsets.mConstructor);
    
    return AndroidRuntime::registerNativeMethods(env, kMessageQueuePathName,
            gMessageQueueMethods, NELEM(gMessageQueueMethods));
}


}; // end of namespace android
