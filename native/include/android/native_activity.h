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


#ifndef ANDROID_NATIVE_ACTIVITY_H
#define ANDROID_NATIVE_ACTIVITY_H

#include <stdint.h>
#include <sys/types.h>

#include <jni.h>

#include <android/input.h>

#ifdef __cplusplus
extern "C" {
#endif

// Temporary until native surface API is defined.
struct ASurfaceHolder;
typedef struct ASurfaceHolder ASurfaceHolder;

struct ANativeActivityCallbacks;

/**
 * This structure defines the native side of an android.app.NativeActivity.
 * It is created by the framework, and handed to the application's native
 * code as it is being launched.
 */
typedef struct ANativeActivity {
    /**
     * Pointer to the callback function table of the native application.
     * You can set the functions here to your own callbacks.  The callbacks
     * pointer itself here should not be changed; it is allocated and managed
     * for you by the framework.
     */
    struct ANativeActivityCallbacks* callbacks;

    /**
     * The global handle on the process's Java VM.
     */
    JavaVM* vm;

    /**
     * JNI context for the main thread of the app.  Note that this field
     * can ONLY be used from the main thread of the process; that is, the
     * thread that calls into the ANativeActivityCallbacks.
     */
    JNIEnv* env;

    /**
     * The NativeActivity Java class.
     */
    jobject clazz;

    /**
     * This is the native instance of the application.  It is not used by
     * the framework, but can be set by the application to its own instance
     * state.
     */
    void* instance;
} ANativeActivity;

/**
 * These are the callbacks the framework makes into a native application.
 * All of these callbacks happen on the main thread of the application.
 * By default, all callbacks are NULL; set to a pointer to your own function
 * to have it called.
 */
typedef struct ANativeActivityCallbacks {
    /**
     * NativeActivity has started.  See Java documentation for Activity.onStart()
     * for more information.
     */
    void (*onStart)(ANativeActivity* activity);
    
    /**
     * NativeActivity has resumed.  See Java documentation for Activity.onResume()
     * for more information.
     */
    void (*onResume)(ANativeActivity* activity);
    
    /**
     * Framework is asking NativeActivity to save its current instance state.
     * See Java documentation for Activity.onSaveInstanceState() for more
     * information.  The returned pointer needs to be created with malloc();
     * the framework will call free() on it for you.  You also must fill in
     * outSize with the number of bytes in the allocation.  Note that the
     * saved state will be persisted, so it can not contain any active
     * entities (pointers to memory, file descriptors, etc).
     */
    void* (*onSaveInstanceState)(ANativeActivity* activity, size_t* outSize);
    
    /**
     * NativeActivity has paused.  See Java documentation for Activity.onPause()
     * for more information.
     */
    void (*onPause)(ANativeActivity* activity);
    
    /**
     * NativeActivity has stopped.  See Java documentation for Activity.onStop()
     * for more information.
     */
    void (*onStop)(ANativeActivity* activity);
    
    /**
     * NativeActivity is being destroyed.  See Java documentation for Activity.onDestroy()
     * for more information.
     */
    void (*onDestroy)(ANativeActivity* activity);

    /**
     * Focus has changed in this NativeActivity's window.  This is often used,
     * for example, to pause a game when it loses input focus.
     */
    void (*onWindowFocusChanged)(ANativeActivity* activity, int hasFocus);
    
    /**
     * The drawing surface for this native activity has been created.  You
     * can use the given surface object to start drawing.  NOTE: surface
     * drawing API is not yet defined.
     */
    void (*onSurfaceCreated)(ANativeActivity* activity, ASurfaceHolder* surface);

    /**
     * The drawing surface for this native activity has changed.  The surface
     * given here is guaranteed to be the same as the one last given to
     * onSurfaceCreated.  This is simply to inform you about interesting
     * changed to that surface.
     */
    void (*onSurfaceChanged)(ANativeActivity* activity, ASurfaceHolder* surface,
            int format, int width, int height);

    /**
     * The drawing surface for this native activity is going to be destroyed.
     * You MUST ensure that you do not touch the surface object after returning
     * from this function: in the common case of drawing to the surface from
     * another thread, that means the implementation of this callback must
     * properly synchronize with the other thread to stop its drawing before
     * returning from here.
     */
    void (*onSurfaceDestroyed)(ANativeActivity* activity, ASurfaceHolder* surface);
    
    /**
     * The input queue for this native activity's window has been created.
     * You can use the given input queue to start retrieving input events.
     */
    void (*onInputQueueCreated)(ANativeActivity* activity, AInputQueue* queue);
    
    /**
     * The input queue for this native activity's window is being destroyed.
     * You should no longer try to reference this object upon returning from this
     * function.
     */
    void (*onInputQueueDestroyed)(ANativeActivity* activity, AInputQueue* queue);

    /**
     * The system is running low on memory.  Use this callback to release
     * resources you do not need, to help the system avoid killing more
     * important processes.
     */
    void (*onLowMemory)(ANativeActivity* activity);
} ANativeActivityCallbacks;

/**
 * This is the function that must be in the native code to instantiate the
 * application's native activity.  It is called with the activity instance (see
 * above); if the code is being instantiated from a previously saved instance,
 * the savedState will be non-NULL and point to the saved data.
 */
typedef void ANativeActivity_createFunc(ANativeActivity* activity,
        void* savedState, size_t savedStateSize);

/**
 * The name of the function that NativeInstance looks for when launching its
 * native code.
 */
extern ANativeActivity_createFunc ANativeActivity_onCreate;

#ifdef __cplusplus
};
#endif

#endif // ANDROID_NATIVE_ACTIVITY_H

