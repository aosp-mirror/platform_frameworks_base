/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.internal.os;

import android.os.DropBoxManager;
import android.os.ParcelFileDescriptor;

/**
 * "Backend" interface used by {@link android.os.DropBoxManager} to talk to the
 * DropBoxManagerService that actually implements the drop box functionality.
 *
 * @see DropBoxManager
 * @hide
 */
interface IDropBoxManagerService {
    void addData(String tag, in byte[] data, int flags);
    void addFile(String tag, in ParcelFileDescriptor fd, int flags);

    /** @see DropBoxManager#getNextEntry */
    boolean isTagEnabled(String tag);

    /** @see DropBoxManager#getNextEntry */
    @UnsupportedAppUsage(maxTargetSdk=30,
            publicAlternatives="Use {@link android.os.DropBoxManager#getNextEntry} instead")
    DropBoxManager.Entry getNextEntry(String tag, long millis, String packageName);

    DropBoxManager.Entry getNextEntryWithAttribution(String tag, long millis, String packageName,
            String attributionTag);
}
