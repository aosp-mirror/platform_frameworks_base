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

package android.os;

/**
 * Callback class for receiving progress reports during a restore operation.  These
 * methods will all be called on your application's main thread.
 * @hide
 */
public abstract class MountServiceListener {
    /**
     * A sharing method has changed availability state.
     *
     * @param method The share method which has changed.
     * @param available The share availability state.
     */
    void shareAvailabilityChange(String method, boolean available) {
    }

    /**
     * Media has been inserted
     *
     * @param label The volume label.
     * @param path The volume mount path.
     * @param major The backing device major number.
     * @param minor The backing device minor number.
     */
    void mediaInserted(String label, String path, int major, int minor) {
    }

    /**
     * Media has been removed
     *
     * @param label The volume label.
     * @param path The volume mount path.
     * @param major The backing device major number.
     * @param minor The backing device minor number.
     * @param clean Indicates if the removal was clean (unmounted first).
     */
    void mediaRemoved(String label, String path, int major, int minor, boolean clean) {
    }

    /**
     *  Volume state has changed.
     *
     * @param label The volume label.
     * @param path The volume mount path.
     * @param oldState The old state of the volume.
     * @param newState The new state of the volume.
     *
     * Note: State is one of the values returned by Environment.getExternalStorageState()
     */
    void volumeStateChange(String label, String path, String oldState, String newState) {
    }
}
