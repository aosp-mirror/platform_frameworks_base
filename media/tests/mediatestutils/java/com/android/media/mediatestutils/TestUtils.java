/*
 * Copyright 2023 The Android Open Source Project
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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import androidx.concurrent.futures.CallbackToFutureAdapter;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ListenableFuture;

import java.lang.ref.WeakReference;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.Objects;
import java.util.function.Predicate;

/**
 *
 */
public class TestUtils {
    public static final String TAG = "MediaTestUtils";

    public static ListenableFuture<Intent> getFutureForIntent(Context context, String action,
            Predicate<Intent> pred) {
        // These are evaluated async
        Objects.requireNonNull(action);
        Objects.requireNonNull(pred);
        // Doesn't need to be thread safe since the resolver is called inline
        final WeakReference<BroadcastReceiver> wrapper[] = new WeakReference[1];
        ListenableFuture<Intent> future = CallbackToFutureAdapter.getFuture(completer -> {
            var receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        if (action.equals(intent.getAction()) && pred.test(intent)) {
                            completer.set(intent);
                        }
                    } catch (Exception e) {
                        completer.setException(e);
                    }
                }
            };
            wrapper[0] = new WeakReference(receiver);
            context.registerReceiver(receiver, new IntentFilter(action),
                    Context.RECEIVER_NOT_EXPORTED);
            return "Intent receiver future for ";
        });
        if (wrapper[0] == null) {
            throw new AssertionError("CallbackToFutureAdapter resolver should be called inline");
        }
        final var weakref = wrapper[0];
        future.addListener(() -> {
            try {
                var recv = weakref.get();
                // If there is no reference left, the receiver has already been unregistered
                if (recv != null) {
                    context.unregisterReceiver(recv);
                    return;
                }
            } catch (IllegalArgumentException e) {
                // Receiver already unregistered, nothing to do.
            }
            Log.d(TAG, "Intent receiver future for action: " + action +
                    "unregistered prior to future completion/cancellation.");
        } , MoreExecutors.directExecutor()); // Direct executor is fine since lightweight
        return future;
    }
}
