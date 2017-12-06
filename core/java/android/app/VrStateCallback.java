/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * Listens to VR Mode state changes. Use with methods in {@link VrManager}.
 *
 * @hide
 */
public abstract class VrStateCallback {

    /**
     * Callback triggered when there is a change to Persistent VR State.
     *
     * @param enabled True when VR State is in persistent mode, false otherwise.
     */
    public void onPersistentVrStateChanged(boolean enabled) {}

    /**
     * Callback triggered when there is a change to Vr State.
     *
     * @param enabled True when VR State is in VR mode, false otherwise.
     */
    public void onVrStateChanged(boolean enabled) {}
}
