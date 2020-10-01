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
import androidx.annotation.Nullable;
import androidx.core.os.CancellationSignal;

import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.NotifBindPipeline.BindCallback;

/**
 * A {@link BindRequester} is a general superclass for something that notifies
 * {@link NotifBindPipeline} when it needs it to kick off a bind run.
 */
public abstract class BindRequester {
    private @Nullable BindRequestListener mBindRequestListener;

    /**
     * Notifies the listener that some parameters/state has changed for some notification and that
     * content needs to be bound again.
     *
     * The caller can also specify a callback for when the entire bind pipeline completes, i.e.
     * when the change is fully propagated to the final view. The caller can cancel this
     * callback with the returned cancellation signal.
     *
     * @param callback callback after bind completely finishes
     * @return cancellation signal to cancel callback
     */
    public final CancellationSignal requestRebind(
            @NonNull NotificationEntry entry,
            @Nullable BindCallback callback) {
        CancellationSignal signal = new CancellationSignal();
        if (mBindRequestListener != null) {
            mBindRequestListener.onBindRequest(entry, signal, callback);
        }
        return signal;
    }

    final void setBindRequestListener(BindRequestListener listener) {
        mBindRequestListener = listener;
    }

    /**
     * Listener interface for when content needs to be bound again.
     */
    public interface BindRequestListener {

        /**
         * Called when {@link #requestRebind} is called.
         *
         * @param entry notification that has outdated content
         * @param signal cancellation signal to cancel callback
         * @param callback callback after content is fully updated
         */
        void onBindRequest(
                @NonNull NotificationEntry entry,
                @NonNull CancellationSignal signal,
                @Nullable BindCallback callback);

    }
}
