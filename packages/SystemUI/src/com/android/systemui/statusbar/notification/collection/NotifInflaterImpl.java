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

package com.android.systemui.statusbar.notification.collection;

import androidx.annotation.NonNull;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.notification.InflationException;
import com.android.systemui.statusbar.notification.collection.inflation.NotifInflater;
import com.android.systemui.statusbar.notification.collection.inflation.NotificationRowBinderImpl;
import com.android.systemui.statusbar.notification.row.NotifInflationErrorManager;
import com.android.systemui.statusbar.notification.row.NotificationContentInflater;

import javax.inject.Inject;

/**
 * Handles notification inflating, rebinding, and inflation aborting.
 *
 * Currently a wrapper for NotificationRowBinderImpl.
 */
@SysUISingleton
public class NotifInflaterImpl implements NotifInflater {

    private final NotifInflationErrorManager mNotifErrorManager;

    private NotificationRowBinderImpl mNotificationRowBinder;

    @Inject
    public NotifInflaterImpl(NotifInflationErrorManager errorManager) {
        mNotifErrorManager = errorManager;
    }

    /**
     * Attaches the row binder for inflation.
     */
    public void setRowBinder(NotificationRowBinderImpl rowBinder) {
        mNotificationRowBinder = rowBinder;
    }

    @Override
    public void rebindViews(@NonNull NotificationEntry entry, @NonNull Params params,
            @NonNull InflationCallback callback) {
        inflateViews(entry, params, callback);
    }

    /**
     * Called to inflate the views of an entry.  Views are not considered inflated until all of its
     * views are bound.
     */
    @Override
    public void inflateViews(@NonNull NotificationEntry entry, @NonNull Params params,
            @NonNull InflationCallback callback) {
        try {
            requireBinder().inflateViews(
                    entry,
                    params,
                    wrapInflationCallback(callback));
        } catch (InflationException e) {
            mNotifErrorManager.setInflationError(entry, e);
        }
    }

    @Override
    public void abortInflation(NotificationEntry entry) {
        entry.abortTask();
    }

    private NotificationContentInflater.InflationCallback wrapInflationCallback(
            InflationCallback callback) {
        return new NotificationContentInflater.InflationCallback() {
            @Override
            public void handleInflationException(
                    NotificationEntry entry,
                    Exception e) {
                mNotifErrorManager.setInflationError(entry, e);
            }

            @Override
            public void onAsyncInflationFinished(NotificationEntry entry) {
                mNotifErrorManager.clearInflationError(entry);
                if (callback != null) {
                    callback.onInflationFinished(entry, entry.getRowController());
                }
            }
        };
    }

    private NotificationRowBinderImpl requireBinder() {
        if (mNotificationRowBinder == null) {
            throw new RuntimeException("NotificationRowBinder must be attached before using "
                    + "NotifInflaterImpl.");
        }
        return mNotificationRowBinder;
    }
}
