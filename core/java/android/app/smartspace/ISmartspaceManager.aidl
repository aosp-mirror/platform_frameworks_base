/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.app.smartspace;

import android.app.smartspace.SmartspaceTarget;
import android.app.smartspace.SmartspaceTargetEvent;
import android.app.smartspace.SmartspaceSessionId;
import android.app.smartspace.SmartspaceConfig;
import android.app.smartspace.ISmartspaceCallback;
import android.content.pm.ParceledListSlice;

/**
 * @hide
 */
oneway interface ISmartspaceManager {

    void createSmartspaceSession(in SmartspaceConfig config, in SmartspaceSessionId sessionId,
            in IBinder token);

    void notifySmartspaceEvent(in SmartspaceSessionId sessionId, in SmartspaceTargetEvent event);

    void requestSmartspaceUpdate(in SmartspaceSessionId sessionId);

    void registerSmartspaceUpdates(in SmartspaceSessionId sessionId,
            in ISmartspaceCallback callback);

    void unregisterSmartspaceUpdates(in SmartspaceSessionId sessionId,
            in ISmartspaceCallback callback);

    void destroySmartspaceSession(in SmartspaceSessionId sessionId);
}
