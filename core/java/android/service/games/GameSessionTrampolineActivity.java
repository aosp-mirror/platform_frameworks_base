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

import android.annotation.Nullable;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Slog;

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
public final class GameSessionTrampolineActivity extends Activity {
    private static final String TAG = "GameSessionTrampoline";
    private static final int REQUEST_CODE = 1;

    static final String FUTURE_KEY = "GameSessionTrampolineActivity.future";
    static final String INTENT_KEY = "GameSessionTrampolineActivity.intent";
    static final String OPTIONS_KEY = "GameSessionTrampolineActivity.options";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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
        }
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
    }
}
