/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.companion.datatransfer.contextsync;

import android.annotation.IntDef;
import android.companion.AssociationInfo;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Set;

/** Callback for call metadata syncing. */
public abstract class CrossDeviceSyncControllerCallback {

    static final int TYPE_CONNECTION_SERVICE = 1;
    static final int TYPE_IN_CALL_SERVICE = 2;
    @IntDef(prefix = { "TYPE_" }, value = {
            TYPE_CONNECTION_SERVICE,
            TYPE_IN_CALL_SERVICE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Type {
    }

    void processContextSyncMessage(int associationId, CallMetadataSyncData callMetadataSyncData) {}

    void requestCrossDeviceSync(AssociationInfo associationInfo) {}

    void updateNumberOfActiveSyncAssociations(int userId, boolean added) {}

    /** Clean up any remaining state for the given calls. */
    void cleanUpCallIds(Set<String> callIds) {}
}
