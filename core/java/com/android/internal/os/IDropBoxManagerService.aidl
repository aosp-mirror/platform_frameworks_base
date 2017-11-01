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

/**
 * "Backend" interface used by {@link android.os.DropBoxManager} to talk to the
 * DropBoxManagerService that actually implements the drop box functionality.
 *
 * @see DropBoxManager
 * @hide
 */
interface IDropBoxManagerService {
    /**
     * @see DropBoxManager#addText
     * @see DropBoxManager#addData
     * @see DropBoxManager#addFile
     */
    void add(in DropBoxManager.Entry entry);

    /** @see DropBoxManager#getNextEntry */
    boolean isTagEnabled(String tag);

    /** @see DropBoxManager#getNextEntry */
    DropBoxManager.Entry getNextEntry(String tag, long millis);
}
