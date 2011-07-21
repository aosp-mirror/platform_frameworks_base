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

#ifndef ANDROID_EFFECTSFACTORY_H_
#define ANDROID_EFFECTSFACTORY_H_

#include <cutils/log.h>
#include <pthread.h>
#include <dirent.h>
#include <media/EffectsFactoryApi.h>

#if __cplusplus
extern "C" {
#endif


typedef struct list_elem_s {
    void *object;
    struct list_elem_s *next;
} list_elem_t;

typedef struct lib_entry_s {
    audio_effect_library_t *desc;
    char *name;
    char *path;
    void *handle;
    list_elem_t *effects; //list of effect_descriptor_t
    pthread_mutex_t lock;
} lib_entry_t;

typedef struct effect_entry_s {
    struct effect_interface_s *itfe;
    effect_handle_t subItfe;
    lib_entry_t *lib;
} effect_entry_t;

#if __cplusplus
}  // extern "C"
#endif


#endif /*ANDROID_EFFECTSFACTORY_H_*/
