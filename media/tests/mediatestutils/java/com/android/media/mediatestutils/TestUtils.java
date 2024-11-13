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

import androidx.concurrent.futures.CallbackToFutureAdapter;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/** Utils for audio tests. */
public class TestUtils {
    /**
     * Return a future for an intent delivered by a broadcast receiver which matches an action and
     * predicate.
     *
     * @param context - Context to register the receiver with
     * @param action - String representing action to register receiver for
     * @param pred - Predicate which sets the future if evaluates to true, otherwise, leaves the
     *     future unset. If the predicate throws, the future is set exceptionally
     * @return - The future representing intent delivery matching predicate.
     */
    public static ListenableFuture<Intent> getFutureForIntent(
            Context context, String action, Predicate<Intent> pred) {
        // These are evaluated async
        Objects.requireNonNull(action);
        Objects.requireNonNull(pred);
        return getFutureForListener(
                (recv) ->
                        context.registerReceiver(
                                recv, new IntentFilter(action), Context.RECEIVER_EXPORTED),
                (recv) -> {
                    try {
                        context.unregisterReceiver(recv);
                    } catch (IllegalArgumentException e) {
                        // Thrown when receiver is already unregistered, nothing to do
                    }
                },
                (completer) ->
                        new BroadcastReceiver() {
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
                        },
                "Intent receiver future for action: " + action);
    }

    /**
     * Return a future for an intent delivered by a broadcast receiver which matches one of a set of
     * actions and predicate.
     *
     * @param context - Context to register the receiver with
     * @param actionsCollection - Collection of actions which to listen for, completing on any
     * @param pred - Predicate which sets the future if evaluates to true, otherwise, leaves the
     *     future unset. If the predicate throws, the future is set exceptionally
     * @return - The future representing intent delivery matching predicate.
     */
    public static ListenableFuture<Intent> getFutureForIntent(
            Context context, Collection<String> actionsCollection, Predicate<Intent> pred) {
        // These are evaluated async
        Objects.requireNonNull(actionsCollection);
        Objects.requireNonNull(pred);
        if (actionsCollection.isEmpty()) {
            throw new IllegalArgumentException("actionsCollection must not be empty");
        }
        return getFutureForListener(
                (recv) ->
                        context.registerReceiver(
                                recv,
                                actionsCollection.stream()
                                        .reduce(
                                                new IntentFilter(),
                                                (IntentFilter filter, String x) -> {
                                                    filter.addAction(x);
                                                    return filter;
                                                },
                                                (x, y) -> {
                                                    throw new IllegalStateException(
                                                            "No parallel support");
                                                }),
                                Context.RECEIVER_EXPORTED),
                (recv) -> {
                    try {
                        context.unregisterReceiver(recv);
                    } catch (IllegalArgumentException e) {
                        // Thrown when receiver is already unregistered, nothing to do
                    }
                },
                (completer) ->
                        new BroadcastReceiver() {
                            @Override
                            public void onReceive(Context context, Intent intent) {
                                try {
                                    if (actionsCollection.contains(intent.getAction())
                                            && pred.test(intent)) {
                                        completer.set(intent);
                                    }
                                } catch (Exception e) {
                                    completer.setException(e);
                                }
                            }
                        },
                "Intent receiver future for actions: " + actionsCollection);
    }

    /** Same as previous, but with no predicate. */
    public static ListenableFuture<Intent> getFutureForIntent(Context context, String action) {
        return getFutureForIntent(context, action, i -> true);
    }

    /**
     * Return a future for a callback registered to a listener interface.
     *
     * @param registerFunc - Function which consumes the callback object for registration
     * @param unregisterFunc - Function which consumes the callback object for unregistration This
     *     function is called when the future is completed or cancelled
     * @param instantiateCallback - Factory function for the callback object, provided a completer
     *     object (see {@code CallbackToFutureAdapter.Completer<T>}), which is a logical reference
     *     to the future returned by this function
     * @param debug - Debug string contained in future {@code toString} representation.
     */
    public static <T, V> ListenableFuture<T> getFutureForListener(
            Consumer<V> registerFunc,
            Consumer<V> unregisterFunc,
            Function<CallbackToFutureAdapter.Completer<T>, V> instantiateCallback,
            String debug) {
        // Doesn't need to be thread safe since the resolver is called inline
        final WeakReference<V> wrapper[] = new WeakReference[1];
        ListenableFuture<T> future =
                CallbackToFutureAdapter.getFuture(
                        completer -> {
                            final var cb = instantiateCallback.apply(completer);
                            wrapper[0] = new WeakReference(cb);
                            registerFunc.accept(cb);
                            return debug;
                        });
        if (wrapper[0] == null) {
            throw new AssertionError("Resolver should be called inline");
        }
        final var weakref = wrapper[0];
        future.addListener(
                () -> {
                    var cb = weakref.get();
                    // If there is no reference left, the receiver has already been unregistered
                    if (cb != null) {
                        unregisterFunc.accept(cb);
                        return;
                    }
                },
                MoreExecutors.directExecutor()); // Direct executor is fine since lightweight
        return future;
    }
}
