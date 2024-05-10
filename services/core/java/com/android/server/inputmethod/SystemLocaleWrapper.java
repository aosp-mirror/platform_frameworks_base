/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.inputmethod;

import android.annotation.AnyThread;
import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.LocaleList;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A set of thread-safe utility methods for the system locals.
 */
final class SystemLocaleWrapper {
    /**
     * Not intended to be instantiated.
     */
    private SystemLocaleWrapper() {
    }

    private static final AtomicReference<LocaleList> sSystemLocale =
            new AtomicReference<>(new LocaleList(Locale.getDefault()));

    /**
     * Returns {@link LocaleList} for the specified user.
     *
     * <p>Note: If you call this method twice, it is possible that the second value is different
     * from the first value. The caller is responsible for taking care of such cases.</p>
     *
     * @param userId the ID of the user to query about.
     * @return {@link LocaleList} associated with the user.
     */
    @AnyThread
    @NonNull
    static LocaleList get(@UserIdInt int userId) {
        // Currently system locale is not per-user.
        // TODO(b/30119489): Make this per-user.
        return sSystemLocale.get();
    }

    /**
     * Callback for the locale change event. When this gets filed, {@link #get(int)} is already
     * updated to return the new value.
     */
    interface Callback {
        void onLocaleChanged(@NonNull LocaleList prevLocales, @NonNull LocaleList newLocales);
    }

    /**
     * Called when {@link InputMethodManagerService} is about to start.
     *
     * @param context {@link Context} to be used.
     * @param callback {@link Callback} for the locale change events.
     */
    @AnyThread
    static void onStart(@NonNull Context context, @NonNull Callback callback,
            @NonNull Handler handler) {
        sSystemLocale.set(context.getResources().getConfiguration().getLocales());

        context.registerReceiver(new LocaleChangeListener(context, callback),
                new IntentFilter(Intent.ACTION_LOCALE_CHANGED), null, handler);
    }

    private static final class LocaleChangeListener extends BroadcastReceiver {
        @NonNull
        private final Context mContext;
        @NonNull
        private final Callback mCallback;
        LocaleChangeListener(@NonNull Context context, @NonNull Callback callback) {
            mContext = context;
            mCallback = callback;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Intent.ACTION_LOCALE_CHANGED.equals(intent.getAction())) {
                return;
            }
            final LocaleList newLocales = mContext.getResources().getConfiguration().getLocales();
            final LocaleList prevLocales = sSystemLocale.getAndSet(newLocales);
            if (!Objects.equals(newLocales, prevLocales)) {
                mCallback.onLocaleChanged(prevLocales, newLocales);
            }
        }
    }
}
