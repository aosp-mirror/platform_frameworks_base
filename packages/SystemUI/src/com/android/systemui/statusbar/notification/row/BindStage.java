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

import android.annotation.MainThread;
import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.util.Map;

/**
 * A {@link BindStage} is an abstraction for a unit of work in inflating/binding/unbinding
 * views to a notification. Used by {@link NotifBindPipeline}.
 *
 * Clients may also use {@link #getStageParams} to provide parameters for this stage for a given
 * notification and request a rebind.
 *
 * @param <Params> params to do this stage
 */
@MainThread
public abstract class BindStage<Params> extends BindRequester {

    private Map<NotificationEntry, Params> mContentParams = new ArrayMap<>();

    /**
     * Execute the stage asynchronously.
     *
     * @param row notification top-level view to bind views to
     * @param callback callback after stage finishes
     */
    protected abstract void executeStage(
            @NonNull NotificationEntry entry,
            @NonNull ExpandableNotificationRow row,
            @NonNull StageCallback callback);

    /**
     * Abort the stage if in progress.
     *
     * @param row notification top-level view to bind views to
     */
    protected abstract void abortStage(
            @NonNull NotificationEntry entry,
            @NonNull ExpandableNotificationRow row);

    /**
     * Get the stage parameters for the entry. Clients should use this to modify how the stage
     * handles the notification content.
     */
    public final @NonNull Params getStageParams(@NonNull NotificationEntry entry) {
        Params params = mContentParams.get(entry);
        if (params == null) {
            // TODO: This should throw an exception but there are some cases of re-entrant calls
            // in NotificationEntryManager (e.g. b/155324756) that cause removal in update that
            // lead to inflation after the notification is "removed". We return an empty params
            // to avoid any NPEs for now, but we should remove this when all re-entrant calls are
            // fixed.
            Log.wtf(TAG, String.format("Entry does not have any stage parameters. key: %s",
                            entry.getKey()));
            return newStageParams();
        }
        return params;
    }

    // TODO(b/253081345): Remove this method.
    /**
     * Get the stage parameters for the entry, or null if there are no stage parameters for the
     * entry.
     *
     * @see #getStageParams(NotificationEntry)
     */
    public final @Nullable Params tryGetStageParams(@NonNull NotificationEntry entry) {
        return mContentParams.get(entry);
    }

    /**
     * Create a params entry for the notification for this stage.
     */
    final void createStageParams(@NonNull NotificationEntry entry) {
        mContentParams.put(entry, newStageParams());
    }

    /**
     * Delete params entry for notification.
     */
    final void deleteStageParams(@NonNull NotificationEntry entry) {
        mContentParams.remove(entry);
    }

    /**
     * Create a new, empty stage params object.
     */
    protected abstract Params newStageParams();

    private static final String TAG = "BindStage";

    /**
     * Interface for callback.
     */
    interface StageCallback {
        /**
         * Callback for when the stage is complete.
         */
        void onStageFinished(NotificationEntry entry);
    }
}
