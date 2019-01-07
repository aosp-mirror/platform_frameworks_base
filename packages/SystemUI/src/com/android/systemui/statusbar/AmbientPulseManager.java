/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar;

import static com.android.systemui.statusbar.notification.row.NotificationInflater.FLAG_CONTENT_VIEW_AMBIENT;

import android.annotation.NonNull;
import android.content.Context;
import android.content.res.Resources;
import android.util.ArraySet;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.NotificationInflater.InflationFlag;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Manager which handles high priority notifications that should "pulse" in when the device is
 * dozing and/or in AOD.  The pulse uses the notification's ambient view and pops in briefly
 * before automatically dismissing the alert.
 */
@Singleton
public class AmbientPulseManager extends AlertingNotificationManager {

    protected final ArraySet<OnAmbientChangedListener> mListeners = new ArraySet<>();
    @VisibleForTesting
    protected long mExtensionTime;

    @Inject
    public AmbientPulseManager(@NonNull final Context context) {
        Resources resources = context.getResources();
        mAutoDismissNotificationDecay = resources.getInteger(R.integer.ambient_notification_decay);
        mMinimumDisplayTime = resources.getInteger(R.integer.ambient_notification_minimum_time);
        mExtensionTime = resources.getInteger(R.integer.ambient_notification_extension_time);
    }

    /**
     * Adds an OnAmbientChangedListener to observe events.
     */
    public void addListener(@NonNull OnAmbientChangedListener listener) {
        mListeners.add(listener);
    }

    /**
     * Removes the OnAmbientChangedListener from the observer list.
     */
    public void removeListener(@NonNull OnAmbientChangedListener listener) {
        mListeners.remove(listener);
    }

    /**
     * Extends the lifetime of the currently showing pulsing notification so that the pulse lasts
     * longer.
     */
    public void extendPulse() {
        AmbientEntry topEntry = getTopEntry();
        if (topEntry == null) {
            return;
        }
        topEntry.extendPulse();
    }

    public @InflationFlag int getContentFlag() {
        return FLAG_CONTENT_VIEW_AMBIENT;
    }

    @Override
    protected void onAlertEntryAdded(AlertEntry alertEntry) {
        NotificationEntry entry = alertEntry.mEntry;
        entry.setAmbientPulsing(true);
        for (OnAmbientChangedListener listener : mListeners) {
            listener.onAmbientStateChanged(entry, true);
        }
    }

    @Override
    protected void onAlertEntryRemoved(AlertEntry alertEntry) {
        NotificationEntry entry = alertEntry.mEntry;
        entry.setAmbientPulsing(false);
        for (OnAmbientChangedListener listener : mListeners) {
            listener.onAmbientStateChanged(entry, false);
        }
        entry.freeContentViewWhenSafe(FLAG_CONTENT_VIEW_AMBIENT);
    }

    @Override
    protected AlertEntry createAlertEntry() {
        return new AmbientEntry();
    }

    /**
     * Get the top pulsing entry.  This should be the currently showing one if there are multiple.
     * @return the currently showing entry
     */
    private AmbientEntry getTopEntry() {
        if (mAlertEntries.isEmpty()) {
            return null;
        }
        AlertEntry topEntry = null;
        for (AlertEntry entry : mAlertEntries.values()) {
            if (topEntry == null || entry.compareTo(topEntry) < 0) {
                topEntry = entry;
            }
        }
        return (AmbientEntry) topEntry;
    }

    /**
     * Observer interface for any changes in the ambient entries.
     */
    public interface OnAmbientChangedListener {
        /**
         * Called when an entry starts or stops pulsing.
         * @param entry the entry that changed
         * @param isPulsing true if the entry is now pulsing, false otherwise
         */
        void onAmbientStateChanged(NotificationEntry entry, boolean isPulsing);
    }

    private final class AmbientEntry extends AlertEntry {
        private boolean extended;

        /**
         * Extend the lifetime of the alertEntry so that it auto-removes later.  Can only be
         * extended once.
         */
        private void extendPulse() {
            if (!extended) {
                extended = true;
                updateEntry(false);
            }
        }

        @Override
        public void reset() {
            super.reset();
            extended = false;
        }

        @Override
        protected long calculateFinishTime() {
            return super.calculateFinishTime() + (extended ? mExtensionTime : 0);
        }
    }
}
