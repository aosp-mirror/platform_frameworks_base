/*
**
** Copyright 2019, The Android Open Source Project
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

//#define LOG_NDEBUG 0
#define LOG_TAG "MediaPlayer2-JNI"
#include "utils/Log.h"

#include "jni.h"
#include <android/dlext.h>
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <string.h>

extern "C" {
  // Copied from GraphicsEnv.cpp
  // TODO(b/37049319) Get this from a header once one exists
  android_namespace_t* android_create_namespace(const char* name,
                                                const char* ld_library_path,
                                                const char* default_library_path,
                                                uint64_t type,
                                                const char* permitted_when_isolated_path,
                                                android_namespace_t* parent);
  bool android_link_namespaces(android_namespace_t* from,
                               android_namespace_t* to,
                               const char* shared_libs_sonames);
  enum {
     ANDROID_NAMESPACE_TYPE_ISOLATED = 1,
  };

}  // extern "C"

static const char kApexLibPath[] =  "/apex/com.android.media/lib"
#ifdef __LP64__
    "64"
#endif
    "/";
static const char kMediaPlayer2LibPath[] =  "/apex/com.android.media/lib"
#ifdef __LP64__
    "64"
#endif
    "/libmediaplayer2_jni.so";

typedef jint (*Media2JniOnLoad)(JavaVM*, void*);

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
    android_namespace_t *media2Ns = android_create_namespace("media2",
            nullptr,  // ld_library_path
            kApexLibPath,
            ANDROID_NAMESPACE_TYPE_ISOLATED,
            nullptr,  // permitted_when_isolated_path
            nullptr); // parent
    if (!android_link_namespaces(media2Ns, nullptr, LINKED_LIBRARIES)) {
        ALOGE("Failed to link namespace. Failed to load extractor plug-ins in apex.");
        return -1;
    }
    const android_dlextinfo dlextinfo = {
        .flags = ANDROID_DLEXT_USE_NAMESPACE,
        .library_namespace = media2Ns,
    };
    // load libmediaplayer2_jni and call JNI_OnLoad.
    void *libHandle = android_dlopen_ext(kMediaPlayer2LibPath, RTLD_NOW | RTLD_LOCAL, &dlextinfo);
    if (libHandle == NULL) {
        ALOGW("couldn't dlopen(%s) %s", kMediaPlayer2LibPath, strerror(errno));
        return -1;
    }
    Media2JniOnLoad media2JniOnLoad = (Media2JniOnLoad) dlsym(libHandle, "JNI_OnLoad");
    if (!media2JniOnLoad) {
        ALOGW("%s does not contain JNI_OnLoad()", kMediaPlayer2LibPath);
        dlclose(libHandle);
        return -1;
    }
    return media2JniOnLoad(vm, reserved);
}
