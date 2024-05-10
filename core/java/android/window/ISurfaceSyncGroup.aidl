/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.window;

import android.os.IBinder;

/**
 * An ISurfaceSyncGroup that can be added to another ISurfaceSyncGroup or is the root
 * ISurfaceSyncGroup.
 *
 * See SurfaceSyncGroup.md
 *
 * {@hide}
 */
interface ISurfaceSyncGroup {
    /**
     * Called when the ISurfaceSyncGroup is ready to begin handling a sync request. When invoked,
     * the implementor should set up the {@link android.window.ITransactionReadyCallback}, either
     * via system server or in the local process.
     *
     * @param parentSyncGroup The parent that added this ISurfaceSyncGroup
     * @param parentSyncGroupMerge true if the ISurfaceSyncGroup is added because its child was
     *                             added to a new SurfaceSyncGroup.
     * @return true if it was successfully added to the sync, false otherwise.
     */
    boolean onAddedToSyncGroup(in IBinder parentSyncGroupToken, boolean parentSyncGroupMerge);

    /**
     * Call to add a ISurfaceSyncGroup to this ISurfaceSyncGroup. This is adding a child
     * ISurfaceSyncGroup so this group can't complete until the child does.
     *
     * @param The child ISurfaceSyncGroup to add to this ISurfaceSyncGroup.
     * @param parentSyncGroupMerge true if the current ISurfaceSyncGroup is added because its child
     *                             was added to a new SurfaceSyncGroup. That would require the code
     *                             to call newParent.addToSync(oldParent). When this occurs, we need
     *                             to reverse the merge order because the oldParent should always be
     *                             considered older than any other SurfaceSyncGroups.
     * @return true if it was successfully added to the sync, false otherwise.
     */
    boolean addToSync(in ISurfaceSyncGroup surfaceSyncGroup, boolean parentSyncGroupMerge);
}