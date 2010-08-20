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


#ifndef ANDROID_STORAGE_MANAGER_H
#define ANDROID_STORAGE_MANAGER_H

#ifdef __cplusplus
extern "C" {
#endif

struct AStorageManager;
typedef struct AStorageManager AStorageManager;


/**
 * Obtains a new instance of AStorageManager.
 */
AStorageManager* AStorageManager_new();

/**
 * Release AStorageManager instance.
 */
void AStorageManager_delete(AStorageManager* mgr);

/**
 * Callback to call when requested OBB is complete.
 */
void AStorageManager_setObbCallback(AStorageManager* mgr, void* cb);

/**
 * Attempts to mount an OBB file.
 */
void AStorageManager_mountObb(AStorageManager* mgr, const char* filename, const char* key);

/**
 * Attempts to unmount an OBB file.
 */
void AStorageManager_unmountObb(AStorageManager* mgr, const char* filename, const int force);

/**
 * Check whether an OBB is mounted.
 */
int AStorageManager_isObbMounted(AStorageManager* mgr, const char* filename);

/**
 * Get the mounted path for an OBB.
 */
const char* AStorageManager_getMountedObbPath(AStorageManager* mgr, const char* filename);


#ifdef __cplusplus
};
#endif

#endif      // ANDROID_PACKAGE_MANAGER_H
