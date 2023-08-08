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

package com.android.media.mediatestutils;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;

import static com.android.media.mediatestutils.TestUtils.getFutureForIntent;
import static com.android.media.mediatestutils.TestUtils.getFutureForListener;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import androidx.test.runner.AndroidJUnit4;

import com.google.common.util.concurrent.ListenableFuture;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;

@RunWith(AndroidJUnit4.class)
public class GetFutureForIntentTest {
    public static final String INTENT_ACTION = "com.android.media.mediatestutils.TEST_ACTION";
    public static final String INTENT_EXTRA = "com.android.media.mediatestutils.TEST_EXTRA";
    public static final int MAGIC_VALUE = 7;

    public final Context mContext = getApplicationContext();
    public final Predicate<Intent> mPred =
            i -> (i != null) && (i.getIntExtra(INTENT_EXTRA, -1) == MAGIC_VALUE);

    @Test
    public void futureCompletes_afterBroadcastFiresPredicatePasses() throws Exception {
        final var future = getFutureForIntent(mContext, INTENT_ACTION, mPred);
        sendIntent(true);
        var intent = future.get();
        assertThat(intent.getAction()).isEqualTo(INTENT_ACTION);
        assertThat(intent.getIntExtra(INTENT_EXTRA, -1)).isEqualTo(MAGIC_VALUE);
    }

    @Test
    public void futureDoesNotComplete_afterBroadcastFiresPredicateFails() throws Exception {
        final var future = getFutureForIntent(mContext, INTENT_ACTION, mPred);
        sendIntent(false);

        // Wait a bit, and ensure the future hasn't completed
        SystemClock.sleep(100);
        assertThat(future.isDone()).isFalse();

        // Future should still respond to subsequent passing intent
        sendIntent(true);
        var intent = future.get();
        assertThat(intent.getAction()).isEqualTo(INTENT_ACTION);
        assertThat(intent.getIntExtra(INTENT_EXTRA, -1)).isEqualTo(MAGIC_VALUE);
    }

    @Test
    public void futureCompletesExceptionally_afterBroadcastFiresPredicateThrows() throws Exception {
        final var future =
                getFutureForIntent(
                        mContext,
                        INTENT_ACTION,
                        i -> {
                            throw new IllegalStateException();
                        });

        sendIntent(true);
        try {
            var intent = future.get();
            fail("Exception expected if predicate throws");
        } catch (ExecutionException e) {
            assertThat(e.getCause().getClass()).isEqualTo(IllegalStateException.class);
        }
    }

    @Test
    public void doesNotThrow_whenDoubleSet() throws Exception {
        final var future = getFutureForIntent(mContext, INTENT_ACTION, mPred);
        sendIntent(true);
        sendIntent(true);
        var intent = future.get();
        assertThat(intent.getAction()).isEqualTo(INTENT_ACTION);
        assertThat(intent.getIntExtra(INTENT_EXTRA, -1)).isEqualTo(MAGIC_VALUE);
    }

    @Test
    public void unregisterListener_whenComplete() throws Exception {
        final var service = new FakeService();
        final ListenableFuture<Void> future =
                getFutureForListener(
                        service::registerListener,
                        service::unregisterListener,
                        completer ->
                                () -> {
                                    completer.set(null);
                                },
                        "FakeService listener future");
        service.mRunnable.run();
        assertThat(service.mRunnable).isNull();
    }

    @Test
    public void unregisterListener_whenCancel() throws Exception {
        final var service = new FakeService();
        final ListenableFuture<Void> future =
                getFutureForListener(
                        service::registerListener,
                        service::unregisterListener,
                        completer ->
                                () -> {
                                    completer.set(null);
                                },
                        "FakeService listener future");
        future.cancel(false);
        assertThat(service.mRunnable).isNull();
    }

    private static class FakeService {
        Runnable mRunnable;

        void registerListener(Runnable r) {
            mRunnable = r;
        }

        void unregisterListener(Runnable r) {
            assertThat(r).isEqualTo(mRunnable);
            mRunnable = null;
        }
    }

    private void sendIntent(boolean correctValue) {
        final Intent intent = new Intent(INTENT_ACTION).setPackage(mContext.getPackageName());
        intent.putExtra(INTENT_EXTRA, correctValue ? MAGIC_VALUE : MAGIC_VALUE + 1);
        mContext.sendBroadcast(intent);
    }
}
