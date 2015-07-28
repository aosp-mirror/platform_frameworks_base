/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.mtp;

/**
 * Static utilities for ID.
 */
abstract class Identifier {
    // TODO: Make the ID persistent.
    static String createRootId(long deviceId, long storageId) {
        return String.format("%d:%d", deviceId, storageId);
    }

    // TODO: Make the ID persistent.
    static String createDocumentId(String rootId, long objectHandle) {
        return String.format("%s:%d", rootId, objectHandle);
    }
}
