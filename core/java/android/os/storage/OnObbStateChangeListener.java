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

package android.os.storage;

/**
 * Used for receiving notifications from {@link StorageManager}.
 */
public abstract class OnObbStateChangeListener {
    /**
     * Called when an OBB has changed states.
     * 
     * @param path path to the OBB file the state change has happened on
     * @param state the current state of the OBB
     */
    public void onObbStateChange(String path, String state) {
    }
}
