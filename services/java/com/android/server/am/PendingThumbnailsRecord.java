/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.server.am;

import android.app.IThumbnailReceiver;

import java.util.HashSet;

/**
 * This class keeps track of calls to getTasks() that are still
 * waiting for thumbnail images.
 */
class PendingThumbnailsRecord
{
    final IThumbnailReceiver receiver;   // who is waiting.
    HashSet pendingRecords; // HistoryRecord objects we still wait for.
    boolean finished;       // Is pendingRecords empty?

    PendingThumbnailsRecord(IThumbnailReceiver _receiver)
    {
        receiver = _receiver;
        pendingRecords = new HashSet();
        finished = false;
    }
}
