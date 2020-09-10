/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.notifcollection;

import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.util.Collection;

/**
 * Interface for the class responsible for converting a NotifCollection into the final sorted,
 * filtered, and grouped list of currently visible notifications.
 */
public interface CollectionReadyForBuildListener {
    /**
     * Called by the NotifCollection to indicate that something in the collection has changed and
     * that the list builder should regenerate the list.
     */
    void onBuildList(Collection<NotificationEntry> entries);
}
