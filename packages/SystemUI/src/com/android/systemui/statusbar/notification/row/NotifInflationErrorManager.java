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

package com.android.systemui.statusbar.notification.row;

import androidx.annotation.NonNull;
import androidx.collection.ArraySet;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

/**
 * A manager handling the error state of a notification when it encounters an exception while
 * inflating. We don't want to show these notifications to the user but may want to keep them
 * around for logging purposes.
 */
@SysUISingleton
public class NotifInflationErrorManager {

    Set<String> mErroredNotifs = new ArraySet<>();
    List<NotifInflationErrorListener> mListeners = new ArrayList<>();

    @Inject
    public NotifInflationErrorManager() { }

    /**
     * Mark the notification as errored out due to encountering an exception while inflating.
     *
     * @param e the exception encountered while inflating
     */
    public void setInflationError(NotificationEntry entry, Exception e) {
        mErroredNotifs.add(entry.getKey());
        for (int i = 0; i < mListeners.size(); i++) {
            mListeners.get(i).onNotifInflationError(entry, e);
        }
    }

    /**
     * Notification inflated successfully and is no longer errored out.
     */
    public void clearInflationError(NotificationEntry entry) {
        if (mErroredNotifs.contains(entry.getKey())) {
            mErroredNotifs.remove(entry.getKey());
            for (int i = 0; i < mListeners.size(); i++) {
                mListeners.get(i).onNotifInflationErrorCleared(entry);
            }
        }
    }

    /**
     * Whether or not the notification encountered an exception while inflating.
     */
    public boolean hasInflationError(@NonNull NotificationEntry entry) {
        return mErroredNotifs.contains(entry.getKey());
    }

    /**
     * Add listener for changes in inflation error state.
     */
    public void addInflationErrorListener(NotifInflationErrorListener listener) {
        mListeners.add(listener);
    }

    /**
     * Listener for changes in notification inflation error state.
     */
    public interface NotifInflationErrorListener {

        /**
         * Called when notification encounters an inflation exception.
         *
         * @param e the exception encountered while inflating
         */
        void onNotifInflationError(NotificationEntry entry, Exception e);

        /**
         * Called when notification inflation error is cleared.
         */
        default void onNotifInflationErrorCleared(NotificationEntry entry) {}
    }
}
