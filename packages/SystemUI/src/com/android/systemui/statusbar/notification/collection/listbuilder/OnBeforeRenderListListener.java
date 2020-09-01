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

import com.android.systemui.statusbar.notification.collection.ListEntry;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;

import java.util.List;

/** See {@link NotifPipeline#addOnBeforeRenderListListener(OnBeforeRenderListListener)} */
public interface OnBeforeRenderListListener {
    /**
     * Called at the end of the pipeline after the notif list has been finalized but before it has
     * been handed off to the view layer.
     *
     * @param entries The current list of top-level entries. Note that this is a live view into the
     *                current list and will change whenever the pipeline is rerun.
     */
    void onBeforeRenderList(List<ListEntry> entries);
}
