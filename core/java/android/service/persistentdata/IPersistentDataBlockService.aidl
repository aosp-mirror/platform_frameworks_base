/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.service.persistentdata;

import android.os.ParcelFileDescriptor;

/**
 * Internal interface through which to communicate to the
 * PersistentDataBlockService. The persistent data block allows writing
 * raw data and setting the OEM unlock enabled/disabled bit contained
 * in the partition.
 *
 * @hide
 */
interface IPersistentDataBlockService {
    int write(in byte[] data);
    byte[] read();
    void wipe();
    int getDataBlockSize();
    long getMaximumDataBlockSize();

    void setOemUnlockEnabled(boolean enabled);
    boolean getOemUnlockEnabled();
}
