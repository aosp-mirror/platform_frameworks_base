/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.service.games;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.infra.AndroidFuture;

import java.util.concurrent.Executor;

/**
 * Trampoline activity that enables the
 * {@link GameSession#startActivityFromGameSessionForResult(Intent, Bundle, Executor,
 * GameSessionActivityCallback)} API by reusing existing activity result infrastructure in the
 * {@link Activity} class. This activity forwards activity results back to the calling
 * {@link GameSession} via {@link AndroidFuture}.
 *
 * @hide
 */
@VisibleForTesting
public final class GameSessionTrampolineActivity extends Activity {
    private static final String TAG = "GameSessionTrampoline";
    private static final int REQUEST_CODE = 1;

    static final String FUTURE_KEY = "GameSessionTrampolineActivity.future";
    static final String INTENT_KEY = "GameSessionTrampolineActivity.intent";
    static final String OPTIONS_KEY = "GameSessionTrampolineActivity.options";
    private static final String HAS_LAUNCHED_INTENT_KEY =
            "GameSessionTrampolineActivity.hasLaunchedIntent";
    private boolean mHasLaunchedIntent = false;

    /**
     * Create an {@link Intent} for the {@link GameSessionTrampolineActivity} with the given
     * parameters.
     *
     * @param targetIntent the forwarded {@link Intent} that is associated with the Activity that
     *                     will be launched by the {@link GameSessionTrampolineActivity}.
     * @param options      Activity options. See {@link #startActivity(Intent, Bundle)}.
     * @param resultFuture the {@link AndroidFuture} that will complete with the activity results of
     *                     {@code targetIntent} launched.
     * @return the Intent that will launch the {@link GameSessionTrampolineActivity} with the given
     * parameters.
     * @hide
     */
    @VisibleForTesting
    public static Intent createIntent(
            @NonNull Intent targetIntent,
            @Nullable Bundle options,
            @NonNull AndroidFuture<GameSessionActivityResult> resultFuture) {
        final Intent trampolineIntent = new Intent();
        trampolineIntent.setComponent(
                new ComponentName(
                        "android", "android.service.games.GameSessionTrampolineActivity"));
        trampolineIntent.putExtra(INTENT_KEY, targetIntent);
        trampolineIntent.putExtra(OPTIONS_KEY, options);
        trampolineIntent.putExtra(FUTURE_KEY, resultFuture);

        return trampolineIntent;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            mHasLaunchedIntent = savedInstanceState.getBoolean(HAS_LAUNCHED_INTENT_KEY);
        }

        if (mHasLaunchedIntent) {
            return;
        }
        mHasLaunchedIntent = true;

        try {
            startActivityAsCaller(
                    getIntent().getParcelableExtra(INTENT_KEY),
                    getIntent().getBundleExtra(OPTIONS_KEY),
                    false,
                    getUserId(),
                    REQUEST_CODE);
        } catch (Exception e) {
            Slog.w(TAG, "Unable to launch activity from game session");
            AndroidFuture<GameSessionActivityResult> future = getIntent().getParcelableExtra(
                    FUTURE_KEY);
            future.completeExceptionally(e);
            finish();
            overridePendingTransition(0, 0);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(HAS_LAUNCHED_INTENT_KEY, mHasLaunchedIntent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_CODE) {
            // Something went very wrong if we hit this code path, and we should bail.
            throw new IllegalStateException("Unexpected request code: " + requestCode);
        }

        AndroidFuture<GameSessionActivityResult> future = getIntent().getParcelableExtra(
                FUTURE_KEY);
        future.complete(new GameSessionActivityResult(resultCode, data));
        finish();
        overridePendingTransition(0, 0);
    }
}
