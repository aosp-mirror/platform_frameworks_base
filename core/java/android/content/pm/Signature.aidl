/* //device/java/android/android/view/WindowManager.aidl
**
** Copyright 2007, The Android Open Source Project
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

package android.content.pm;

/* For the key attestation application id provider service we needed a native implementation
 * of the Signature parcelable because the service is used by the native keystore.
 * The native implementation is now located at
 * system/security/keystore/Signature.cpp
 * and
 * system/security/keystore/include/keystore/Signature.h.
 * and can be used by linking against libkeystore_binder.
 *
 * This is not the best arrangement. If you, dear reader, happen to implement native implementations
 * for the package manager's parcelables, consider moving Signature.cpp/.h to your library and
 * adjust keystore's dependencies accordingly. Thank you.
 */
parcelable Signature cpp_header "keystore/Signature.h";
