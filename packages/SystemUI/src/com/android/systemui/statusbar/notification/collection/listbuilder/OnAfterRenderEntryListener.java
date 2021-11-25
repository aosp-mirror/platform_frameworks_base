/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.listbuilder;

import androidx.annotation.NonNull;

import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.render.NotifRowController;

/** See {@link NotifPipeline#addOnAfterRenderEntryListener(OnAfterRenderEntryListener)} */
public interface OnAfterRenderEntryListener {
    /**
     * Called at the end of the pipeline after an entry has been handed off to the view layer.
     * This will be called for every top level entry, every group summary, and every group child.
     *
     * @param entry the entry to read from.
     * @param controller the object to which data can be pushed.
     */
    void onAfterRenderEntry(
            @NonNull NotificationEntry entry,
            @NonNull NotifRowController controller);
}
