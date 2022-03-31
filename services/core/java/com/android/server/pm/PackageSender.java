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

package com.android.server.pm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.IIntentReceiver;
import android.os.Bundle;
import android.util.SparseArray;

interface PackageSender {
    /**
     * @param userIds User IDs where the action occurred on a full application
     * @param instantUserIds User IDs where the action occurred on an instant application
     */
    void sendPackageBroadcast(String action, String pkg,
            Bundle extras, int flags, String targetPkg,
            IIntentReceiver finishedReceiver, int[] userIds, int[] instantUserIds,
            @Nullable SparseArray<int[]> broadcastAllowList, @Nullable Bundle bOptions);
    void sendPackageAddedForNewUsers(@NonNull Computer snapshot, String packageName,
            boolean sendBootCompleted, boolean includeStopped, int appId, int[] userIds,
            int[] instantUserIds, int dataLoaderType);
    void notifyPackageAdded(String packageName, int uid);
    void notifyPackageChanged(String packageName, int uid);
    void notifyPackageRemoved(String packageName, int uid);
}
