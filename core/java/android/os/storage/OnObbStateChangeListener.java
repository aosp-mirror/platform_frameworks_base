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
 * Used for receiving notifications from {@link StorageManager} about OBB file
 * states.
 */
public abstract class OnObbStateChangeListener {

    /**
     * The OBB container is now mounted and ready for use. Returned in status
     * messages from calls made via {@link StorageManager}
     */
    public static final int MOUNTED = 1;

    /**
     * The OBB container is now unmounted and not usable. Returned in status
     * messages from calls made via {@link StorageManager}
     */
    public static final int UNMOUNTED = 2;

    /**
     * There was an internal system error encountered while trying to mount the
     * OBB. Returned in status messages from calls made via
     * {@link StorageManager}
     */
    public static final int ERROR_INTERNAL = 20;

    /**
     * The OBB could not be mounted by the system. Returned in status messages
     * from calls made via {@link StorageManager}
     */
    public static final int ERROR_COULD_NOT_MOUNT = 21;

    /**
     * The OBB could not be unmounted. This most likely indicates that a file is
     * in use on the OBB. Returned in status messages from calls made via
     * {@link StorageManager}
     */
    public static final int ERROR_COULD_NOT_UNMOUNT = 22;

    /**
     * A call was made to unmount the OBB when it was not mounted. Returned in
     * status messages from calls made via {@link StorageManager}
     */
    public static final int ERROR_NOT_MOUNTED = 23;

    /**
     * The OBB has already been mounted. Returned in status messages from calls
     * made via {@link StorageManager}
     */
    public static final int ERROR_ALREADY_MOUNTED = 24;

    /**
     * The current application does not have permission to use this OBB. This
     * could be because the OBB indicates it's owned by a different package or
     * some other error. Returned in status messages from calls made via
     * {@link StorageManager}
     */
    public static final int ERROR_PERMISSION_DENIED = 25;

    /**
     * Called when an OBB has changed states.
     * 
     * @param path path to the OBB file the state change has happened on
     * @param state the current state of the OBB
     */
    public void onObbStateChange(String path, int state) {
    }
}
