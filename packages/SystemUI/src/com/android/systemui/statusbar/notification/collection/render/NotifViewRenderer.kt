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

package com.android.systemui.statusbar.notification.collection.render

import com.android.systemui.statusbar.notification.collection.GroupEntry
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntry

/**
 * This interface and the interfaces it returns define the main API surface that must be
 * implemented by the view implementation.  The term "render" is used to indicate a handoff
 * to the view system, whether that be to attach views to the hierarchy or to update independent
 * view models, data stores, or adapters.
 */
interface NotifViewRenderer {

    /**
     * Hand off the list of notifications to the view implementation.  This may attach views to the
     * hierarchy or simply update an independent datastore, but once called, the implementer myst
     * also ensure that future calls to [getStackController], [getGroupController], and
     * [getRowController] will provide valid results.
     */
    fun onRenderList(notifList: List<ListEntry>)

    /**
     * Provides an interface for the pipeline to update the overall shade.
     * This will be called at most once for each time [onRenderList] is called.
     */
    fun getStackController(): NotifStackController

    /**
     * Provides an interface for the pipeline to update individual groups.
     * This will be called at most once for each group in the most recent call to [onRenderList].
     */
    fun getGroupController(group: GroupEntry): NotifGroupController

    /**
     * Provides an interface for the pipeline to update individual entries.
     * This will be called at most once for each entry in the most recent call to [onRenderList].
     * This includes top level entries, group summaries, and group children.
     */
    fun getRowController(entry: NotificationEntry): NotifRowController

    /**
     * Invoked after the render stage manager has finished dispatching to all of the listeners.
     *
     * This is an opportunity for the view system to do any cleanup or trigger any finalization
     * logic now that all data from the pipeline is known to have been set for this execution.
     *
     * When this is called, the view system can expect that no more calls will be made to the
     * getters on this interface until after the next call to [onRenderList].  Additionally, there
     * should be no further calls made on the objects previously returned by those getters.
     */
    fun onDispatchComplete() {}
}