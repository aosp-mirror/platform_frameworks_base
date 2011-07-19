/*
** Copyright 2010, The Android Open Source Project
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

#ifndef ANDROID_BLUETOOTH_C_H
#define ANDROID_BLUETOOTH_C_H
#ifdef HAVE_BLUETOOTH

#include <bluetooth/bluetooth.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * A C helper for creating a bdaddr_t object with the value BDADDR_ANY.
 * We have to do this in C because the macro BDADDR_ANY in bluetooth.h
 * is not valid C++ code.
 */
bdaddr_t android_bluetooth_bdaddr_any(void);

#ifdef __cplusplus
}
#endif

#endif /*HAVE_BLUETOOTH*/
#endif /*ANDROID_BLUETOOTH_C_H*/
