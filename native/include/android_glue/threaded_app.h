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
 *
 */

#include <poll.h>
#include <pthread.h>
#include <sched.h>

#include <android/native_activity.h>
#include <android/looper.h>

/**
 * This is the interface for the standard glue code of a threaded
 * application.  In this model, the application's code is running
 * in its own thread separate from the main thread of the process.
 * It is not required that this thread be associated with the Java
 * VM, although it will need to be in order to make JNI calls any
 * Java objects.
 */
struct android_app {
    // The application can place a pointer to its own state object
    // here if it likes.
    void* userData;

    // The ANativeActivity object instance that this app is running in.
    ANativeActivity* activity;

    // The ALooper associated with the app's thread.
    ALooper* looper;

    // When non-NULL, this is the input queue from which the app will
    // receive user input events.
    AInputQueue* inputQueue;

    // When non-NULL, this is the window surface that the app can draw in.
    ANativeWindow* window;

    // Current state of the app's activity.  May be either APP_CMD_START,
    // APP_CMD_RESUME, APP_CMD_PAUSE, or APP_CMD_STOP; see below.
    int activityState;

    // -------------------------------------------------
    // Below are "private" implementation of the glue code.

    pthread_mutex_t mutex;
    pthread_cond_t cond;

    int msgread;
    int msgwrite;

    pthread_t thread;

    // This is non-zero when the application's NativeActivity is being
    // destroyed and waiting for the app thread to complete.
    int destroyRequested;

    int running;
    int destroyed;
    AInputQueue* pendingInputQueue;
    ANativeWindow* pendingWindow;
};

enum {
    /**
     * Looper data ID of commands coming from the app's main thread.
     * These can be retrieved and processed with android_app_read_cmd()
     * and android_app_exec_cmd().
     */
    LOOPER_ID_MAIN = 1,

    /**
     * Looper data ID of events coming from the AInputQueue of the
     * application's window.  These can be read via the inputQueue
     * object of android_app.
     */
    LOOPER_ID_EVENT = 2
};

enum {
    /**
     * Command from main thread: the AInputQueue has changed.  Upon processing
     * this command, android_app->inputQueue will be updated to the new queue
     * (or NULL).
     */
    APP_CMD_INPUT_CHANGED,

    /**
     * Command from main thread: the ANativeWindow has changed.  Upon processing
     * this command, android_app->window will be updated to the new window surface
     * (or NULL).
     */
    APP_CMD_WINDOW_CHANGED,

    /**
     * Command from main thread: the app's activity window has gained
     * input focus.
     */
    APP_CMD_GAINED_FOCUS,

    /**
     * Command from main thread: the app's activity window has lost
     * input focus.
     */
    APP_CMD_LOST_FOCUS,

    /**
     * Command from main thread: the app's activity has been started.
     */
    APP_CMD_START,

    /**
     * Command from main thread: the app's activity has been resumed.
     */
    APP_CMD_RESUME,

    /**
     * Command from main thread: the app's activity has been paused.
     */
    APP_CMD_PAUSE,

    /**
     * Command from main thread: the app's activity has been stopped.
     */
    APP_CMD_STOP,

    /**
     * Command from main thread: the app's activity is being destroyed,
     * and waiting for the app thread to clean up and exit before proceeding.
     */
    APP_CMD_DESTROY,
};

/**
 * Call when ALooper_pollAll() returns LOOPER_ID_MAIN, reading the next
 * app command message.
 */
int8_t android_app_read_cmd(struct android_app* android_app);

/**
 * Call with the command returned by android_app_read_cmd() to do the
 * default processing of the given command.
 *
 * Important: returns 0 if the app should exit.  You must ALWAYS check for
 * a zero return and, if found, exit your android_main() function.
 */
int32_t android_app_exec_cmd(struct android_app* android_app, int8_t cmd);

/**
 * This is the function that application code must implement, representing
 * the main entry to the app.
 */
extern void android_main(struct android_app* app);
