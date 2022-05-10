/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.app;

/**
 * Notify the client of all changes to services' foreground state.
 * @param serviceToken unique identifier for a service instance
 * @param packageName identifies the app hosting the service
 * @param userId identifies the started user in which the app is running
 * @param isForeground whether the service is in the "foreground" mode now, i.e.
 *     whether it is an FGS
 *
 * @hide
 */
oneway interface IForegroundServiceObserver {
    void onForegroundStateChanged(in IBinder serviceToken, in String packageName, int userId, boolean isForeground);
}
