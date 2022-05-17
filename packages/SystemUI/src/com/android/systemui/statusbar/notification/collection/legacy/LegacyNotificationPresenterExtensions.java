/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.legacy;

import static com.android.systemui.statusbar.phone.CentralSurfaces.SPEW;

import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.statusbar.notification.NotificationEntryListener;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.render.NotifShadeEventSource;

import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;

/**
 * This is some logic extracted from the
 * {@link com.android.systemui.statusbar.phone.StatusBarNotificationPresenter}
 * into a class that implements a new-pipeline interface so that the new pipeline can implement it
 * correctly.
 *
 * Specifically, this is the logic which updates notifications when uiMode and screen properties
 * change, and which closes the shade when the last notification disappears.
 */
public class LegacyNotificationPresenterExtensions implements NotifShadeEventSource {
    private static final String TAG = "LegacyNotifPresenter";
    private final NotificationEntryManager mEntryManager;
    private boolean mEntryListenerAdded;
    private Runnable mShadeEmptiedCallback;
    private Runnable mNotifRemovedByUserCallback;

    @Inject
    public LegacyNotificationPresenterExtensions(NotificationEntryManager entryManager) {
        mEntryManager = entryManager;
    }

    private void ensureEntryListenerAdded() {
        if (mEntryListenerAdded) return;
        mEntryListenerAdded = true;
        mEntryManager.addNotificationEntryListener(new NotificationEntryListener() {
            @Override
            public void onEntryRemoved(
                    @NotNull NotificationEntry entry,
                    NotificationVisibility visibility,
                    boolean removedByUser,
                    int reason) {
                StatusBarNotification old = entry.getSbn();
                if (SPEW) {
                    Log.d(TAG, "removeNotification key=" + entry.getKey()
                            + " old=" + old + " reason=" + reason);
                }

                if (old != null && !mEntryManager.hasActiveNotifications()) {
                    if (mShadeEmptiedCallback != null) mShadeEmptiedCallback.run();
                }
                if (removedByUser) {
                    if (mNotifRemovedByUserCallback != null) mNotifRemovedByUserCallback.run();
                }
            }
        });
    }

    @Override
    public void setNotifRemovedByUserCallback(@NonNull Runnable callback) {
        if (mNotifRemovedByUserCallback != null) {
            throw new IllegalStateException("mNotifRemovedByUserCallback already set");
        }
        mNotifRemovedByUserCallback = callback;
        ensureEntryListenerAdded();
    }

    @Override
    public void setShadeEmptiedCallback(@NonNull Runnable callback) {
        if (mShadeEmptiedCallback != null) {
            throw new IllegalStateException("mShadeEmptiedCallback already set");
        }
        mShadeEmptiedCallback = callback;
        ensureEntryListenerAdded();
    }
}
