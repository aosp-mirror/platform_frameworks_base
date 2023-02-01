/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.internal.app;

import android.annotation.NonNull;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Bundle;
import android.util.Slog;

import java.util.Objects;

/**
 * Activity used to intercept lock screen intents and show the bouncer before launching the
 * original intent.
 */
public class LaunchAfterAuthenticationActivity extends Activity {
    private static final String TAG = LaunchAfterAuthenticationActivity.class.getSimpleName();
    private static final String EXTRA_ON_SUCCESS_INTENT =
            "com.android.internal.app.extra.ON_SUCCESS_INTENT";

    /**
     * Builds the intent used to launch this activity.
     *
     * @param onSuccessIntent The intent to launch after the user has authenticated.
     */
    public static Intent createLaunchAfterAuthenticationIntent(IntentSender onSuccessIntent) {
        return new Intent()
                .setClassName(/* packageName= */"android",
                        LaunchAfterAuthenticationActivity.class.getName())
                .putExtra(EXTRA_ON_SUCCESS_INTENT, onSuccessIntent)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        final IntentSender onSuccessIntent = getIntent().getParcelableExtra(
                EXTRA_ON_SUCCESS_INTENT);
        requestDismissKeyguardIfNeeded(onSuccessIntent);
    }

    private void requestDismissKeyguardIfNeeded(IntentSender onSuccessIntent) {
        final KeyguardManager km = Objects.requireNonNull(getSystemService(KeyguardManager.class));
        if (km.isKeyguardLocked()) {
            km.requestDismissKeyguard(this,
                    new KeyguardManager.KeyguardDismissCallback() {
                        @Override
                        public void onDismissCancelled() {
                            LaunchAfterAuthenticationActivity.this.finish();
                        }

                        @Override
                        public void onDismissSucceeded() {
                            if (onSuccessIntent != null) {
                                onUnlocked(onSuccessIntent);
                            }
                            LaunchAfterAuthenticationActivity.this.finish();
                        }

                        @Override
                        public void onDismissError() {
                            Slog.e(TAG, "Error while dismissing keyguard.");
                            LaunchAfterAuthenticationActivity.this.finish();
                        }
                    });
        } else {
            finish();
        }
    }

    private void onUnlocked(@NonNull IntentSender targetIntent) {
        try {
            targetIntent.sendIntent(
                    /* context= */ this,
                    /* code= */ 0,
                    /* intent= */null,
                    /* onFinished= */ null,
                    /* handler= */ null);
        } catch (IntentSender.SendIntentException e) {
            Slog.e(TAG, "Error while sending original intent", e);
        }
    }
}
