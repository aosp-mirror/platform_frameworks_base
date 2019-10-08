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

package android.perftests.utils;

import static android.perftests.utils.SettingsHelper.NAMESPACE_SECURE;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.annotation.NonNull;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Helper used to block tests until a secure settings value has been updated.
 */
public final class OneTimeSettingsListener extends ContentObserver {
    private final CountDownLatch mLatch = new CountDownLatch(1);
    private final ContentResolver mResolver;
    private final String mKey;
    private final int mTimeoutMs;

    public OneTimeSettingsListener(@NonNull Context context, @NonNull String namespace,
            @NonNull String key, int timeoutMs) {
        super(new Handler(Looper.getMainLooper()));
        mKey = key;
        mResolver = context.getContentResolver();
        mTimeoutMs = timeoutMs;
        final Uri uri;
        switch (namespace) {
            case NAMESPACE_SECURE:
                uri = Settings.Secure.getUriFor(key);
                break;
            default:
                throw new IllegalArgumentException("invalid namespace: " + namespace);
        }
        mResolver.registerContentObserver(uri, false, this);
    }

    @Override
    public void onChange(boolean selfChange, Uri uri) {
        mResolver.unregisterContentObserver(this);
        mLatch.countDown();
    }

    /**
     * Blocks for a few seconds until it's called, or throws an {@link IllegalStateException} if
     * it isn't.
     */
    public void assertCalled() {
        try {
            final boolean updated = mLatch.await(mTimeoutMs, TimeUnit.MILLISECONDS);
            if (!updated) {
                throw new IllegalStateException(
                        "Settings " + mKey + " not called in " + mTimeoutMs + "ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }
}
