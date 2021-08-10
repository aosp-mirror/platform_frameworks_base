/**
 * Copyright 2021, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.media.tv.tunerresourcemanager;

/**
 * FrontendInfo interface that carries tuner frontend information.
 *
 * This is used to update the TunerResourceManager fronted resources.
 * @hide
 */
parcelable TunerFrontendInfo {
    /**
     * Frontend Handle
     */
    int handle;

    /**
     * Frontend Type
     */
    int type;

    /**
     * Frontends are assigned with the same exclusiveGroupId if they can't
     * function at same time. For instance, they share same hardware module.
     */
    int exclusiveGroupId;
}
