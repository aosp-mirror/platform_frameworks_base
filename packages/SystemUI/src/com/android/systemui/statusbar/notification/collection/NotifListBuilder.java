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

package com.android.systemui.statusbar.notification.collection;

import java.util.Collection;

/**
 * Interface for the class responsible for converting a NotifCollection into the final sorted,
 * filtered, and grouped list of currently visible notifications.
 */
public interface NotifListBuilder {
    /**
     * Called after the NotifCollection has received an update from NotificationManager but before
     * it dispatches any change events to its listeners. This is to inform the list builder that
     * the first stage of the pipeline has been triggered. After events have been dispatched,
     * onBuildList() will be called.
     *
     * While onBuildList() is always called after this method is called, the converse is not always
     * true: sometimes the NotifCollection applies an update that does not need to dispatch events,
     * in which case this method will be skipped and onBuildList will be called directly.
     */
    void onBeginDispatchToListeners();

    /**
     * Called by the NotifCollection to indicate that something in the collection has changed and
     * that the list builder should regenerate the list.
     */
    void onBuildList(Collection<NotificationEntry> entries);
}
