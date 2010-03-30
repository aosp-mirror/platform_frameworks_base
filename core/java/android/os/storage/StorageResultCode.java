/*
 * Copyright (C) 2007 The Android Open Source Project
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
 * Class that provides access to constants returned from StorageManager
 * and lower level MountService APIs.
 *
 * @hide
 */
public class StorageResultCode
{
    /**
     * Operation succeeded.
     * @see android.os.storage.StorageManager
     */
    public static final int OperationSucceeded               =  0;

    /**
     * Operation failed: Internal error.
     * @see android.os.storage.StorageManager
     */
    public static final int OperationFailedInternalError     = -1;

    /**
     * Operation failed: Missing media.
     * @see android.os.storage.StorageManager
     */
    public static final int OperationFailedNoMedia           = -2;

    /**
     * Operation failed: Media is blank.
     * @see android.os.storage.StorageManager
     */
    public static final int OperationFailedMediaBlank        = -3;

    /**
     * Operation failed: Media is corrupt.
     * @see android.os.storage.StorageManager
     */
    public static final int OperationFailedMediaCorrupt      = -4;

    /**
     * Operation failed: Storage not mounted.
     * @see android.os.storage.StorageManager
     */
    public static final int OperationFailedStorageNotMounted  = -5;

    /**
     * Operation failed: Storage is mounted.
     * @see android.os.storage.StorageManager
     */
    public static final int OperationFailedStorageMounted     = -6;

    /**
     * Operation failed: Storage is busy.
     * @see android.os.storage.StorageManager
     */
    public static final int OperationFailedStorageBusy        = -7;

}
