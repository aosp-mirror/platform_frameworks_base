/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.packageinstaller.common;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

/**
 * Receives uninstall events and persists them using a {@link EventResultPersister}.
 */
public class UninstallEventReceiver extends BroadcastReceiver {
    private static final Object sLock = new Object();
    private static EventResultPersister sReceiver;

    /**
     * Get the event receiver persisting the results
     *
     * @return The event receiver.
     */
    @NonNull private static EventResultPersister getReceiver(@NonNull Context context) {
        synchronized (sLock) {
            if (sReceiver == null) {
                sReceiver = new EventResultPersister(
                        TemporaryFileManager.getUninstallStateFile(context));
            }
        }

        return sReceiver;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        getReceiver(context).onEventReceived(context, intent);
    }

    /**
     * Add an observer. If there is already an event for this id, call back inside of this call.
     *
     * @param context  A context of the current app
     * @param id       The id the observer is for or {@code GENERATE_NEW_ID} to generate a new one.
     * @param observer The observer to call back.
     *
     * @return The id for this event
     */
    public static int addObserver(@NonNull Context context, int id,
            @NonNull EventResultPersister.EventResultObserver observer)
            throws EventResultPersister.OutOfIdsException {
        return getReceiver(context).addObserver(id, observer);
    }

    /**
     * Remove a observer.
     *
     * @param context  A context of the current app
     * @param id The id the observer was added for
     */
    public static void removeObserver(@NonNull Context context, int id) {
        getReceiver(context).removeObserver(id);
    }

    /**
     * @param context A context of the current app
     *
     * @return A new uninstall id
     */
    public static int getNewId(@NonNull Context context)
        throws EventResultPersister.OutOfIdsException {
        return getReceiver(context).getNewId();
    }
}
