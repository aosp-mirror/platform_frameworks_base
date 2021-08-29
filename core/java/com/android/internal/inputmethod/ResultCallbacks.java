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

package com.android.internal.inputmethod;

import android.annotation.AnyThread;
import android.annotation.BinderThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.SurroundingText;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Defines a set of factory methods to create {@link android.os.IBinder}-based callbacks that are
 * associated with completable objects defined in {@link CompletableFuture}.
 */
public final class ResultCallbacks {

    /**
     * Not intended to be instantiated.
     */
    private ResultCallbacks() {
    }

    private static final class LightweightThrowable extends RuntimeException {
        LightweightThrowable(@Nullable ThrowableHolder throwableHolder) {
            super(throwableHolder != null ? throwableHolder.getMessage() : null,
                    null, false, false);
        }
    }

    @AnyThread
    @Nullable
    private static <T> T unwrap(@NonNull AtomicReference<T> atomicRef) {
        // Only the first caller will receive the non-null original object.
        return atomicRef.getAndSet(null);
    }

    /**
     * Creates {@link IIntResultCallback.Stub} that is to set {@link CompletableFuture<Integer>}
     * when receiving the result.
     *
     * @param value {@link CompletableFuture<Integer>} to be set when receiving the result.
     * @return {@link IIntResultCallback.Stub} that can be passed as a binder IPC parameter.
     */
    @AnyThread
    public static IIntResultCallback.Stub ofInteger(@NonNull CompletableFuture<Integer> value) {
        final AtomicReference<CompletableFuture<Integer>> atomicRef = new AtomicReference<>(value);

        return new IIntResultCallback.Stub() {
            @BinderThread
            @Override
            public void onResult(int result) {
                final CompletableFuture<Integer> value = unwrap(atomicRef);
                if (value == null) {
                    return;
                }
                value.complete(result);
            }

            @BinderThread
            @Override
            public void onError(ThrowableHolder throwableHolder) {
                final CompletableFuture<Integer> value = unwrap(atomicRef);
                if (value == null) {
                    return;
                }
                value.completeExceptionally(new LightweightThrowable(throwableHolder));
            }
        };
    }

    /**
     * Creates {@link ICharSequenceResultCallback.Stub} that is to set
     * {@link CompletableFuture<CharSequence>} when receiving the result.
     *
     * @param value {@link CompletableFuture<CharSequence>} to be set when receiving the result.
     * @return {@link ICharSequenceResultCallback.Stub} that can be passed as a binder IPC
     *         parameter.
     */
    @AnyThread
    public static ICharSequenceResultCallback.Stub ofCharSequence(
            @NonNull CompletableFuture<CharSequence> value) {
        final AtomicReference<CompletableFuture<CharSequence>> atomicRef =
                new AtomicReference<>(value);

        return new ICharSequenceResultCallback.Stub() {
            @BinderThread
            @Override
            public void onResult(CharSequence result) {
                final CompletableFuture<CharSequence> value = unwrap(atomicRef);
                if (value == null) {
                    return;
                }
                value.complete(result);
            }
        };
    }

    /**
     * Creates {@link IExtractedTextResultCallback.Stub} that is to set
     * {@link CompletableFuture<ExtractedText>} when receiving the result.
     *
     * @param value {@link CompletableFuture<ExtractedText>} to be set when receiving the result.
     * @return {@link IExtractedTextResultCallback.Stub} that can be passed as a binder IPC
     *         parameter.
     */
    @AnyThread
    public static IExtractedTextResultCallback.Stub ofExtractedText(
            @NonNull CompletableFuture<ExtractedText> value) {
        final AtomicReference<CompletableFuture<ExtractedText>> atomicRef =
                new AtomicReference<>(value);

        return new IExtractedTextResultCallback.Stub() {
            @BinderThread
            @Override
            public void onResult(android.view.inputmethod.ExtractedText result) {
                final CompletableFuture<ExtractedText> value = unwrap(atomicRef);
                if (value == null) {
                    return;
                }
                value.complete(result);
            }
        };
    }

    /**
     * Creates {@link ISurroundingTextResultCallback.Stub} that is to set
     * {@link CompletableFuture<SurroundingText>} when receiving the result.
     *
     * @param value {@link CompletableFuture<SurroundingText>} to be set when receiving the result.
     * @return {@link ISurroundingTextResultCallback.Stub} that can be passed as a binder IPC
     *         parameter.
     */
    @AnyThread
    public static ISurroundingTextResultCallback.Stub ofSurroundingText(
            @NonNull CompletableFuture<SurroundingText> value) {
        final AtomicReference<CompletableFuture<SurroundingText>> atomicRef =
                new AtomicReference<>(value);

        return new ISurroundingTextResultCallback.Stub() {
            @BinderThread
            @Override
            public void onResult(android.view.inputmethod.SurroundingText result) {
                final CompletableFuture<SurroundingText> value = unwrap(atomicRef);
                if (value == null) {
                    return;
                }
                value.complete(result);
            }
        };
    }

    /**
     * Creates {@link IBooleanResultCallback.Stub} that is to set {@link CompletableFuture<Boolean>}
     * when receiving the result.
     *
     * @param value {@link CompletableFuture<Boolean>} to be set when receiving the result.
     * @return {@link IBooleanResultCallback.Stub} that can be passed as a binder IPC parameter.
     */
    @AnyThread
    public static IBooleanResultCallback.Stub ofBoolean(@NonNull CompletableFuture<Boolean> value) {
        final AtomicReference<CompletableFuture<Boolean>> atomicRef = new AtomicReference<>(value);

        return new IBooleanResultCallback.Stub() {
            @BinderThread
            @Override
            public void onResult(boolean result) {
                final CompletableFuture<Boolean> value = unwrap(atomicRef);
                if (value == null) {
                    return;
                }
                value.complete(result);
            }

            @BinderThread
            @Override
            public void onError(ThrowableHolder throwableHolder) {
                final CompletableFuture<Boolean> value = unwrap(atomicRef);
                if (value == null) {
                    return;
                }
                value.completeExceptionally(new LightweightThrowable(throwableHolder));
            }
        };
    }

    /**
     * Creates {@link IVoidResultCallback.Stub} that is to set {@link CompletableFuture<Void>} when
     * receiving the result.
     *
     * @param value {@link CompletableFuture<Void>} to be set when receiving the result.
     * @return {@link IVoidResultCallback.Stub} that can be passed as a binder IPC parameter.
     */
    @AnyThread
    public static IVoidResultCallback.Stub ofVoid(@NonNull CompletableFuture<Void> value) {
        final AtomicReference<CompletableFuture<Void>> atomicRef = new AtomicReference<>(value);

        return new IVoidResultCallback.Stub() {
            @BinderThread
            @Override
            public void onResult() {
                final CompletableFuture<Void> value = unwrap(atomicRef);
                if (value == null) {
                    return;
                }
                value.complete(null);
            }

            @BinderThread
            @Override
            public void onError(ThrowableHolder throwableHolder) {
                final CompletableFuture<Void> value = unwrap(atomicRef);
                if (value == null) {
                    return;
                }
                value.completeExceptionally(new LightweightThrowable(throwableHolder));
            }
        };
    }

    /**
     * Creates {@link IInputContentUriTokenResultCallback.Stub} that is to set
     * {@link CompletableFuture<IInputContentUriToken>} when receiving the result.
     *
     * @param value {@link CompletableFuture<IInputContentUriToken>} to be set when receiving the
     *              result.
     * @return {@link IInputContentUriTokenResultCallback.Stub} that can be passed as a binder IPC
     * parameter.
     */
    @AnyThread
    public static IInputContentUriTokenResultCallback.Stub ofIInputContentUriToken(
            @NonNull CompletableFuture<IInputContentUriToken> value) {
        final AtomicReference<CompletableFuture<IInputContentUriToken>>
                atomicRef = new AtomicReference<>(value);

        return new IInputContentUriTokenResultCallback.Stub() {
            @BinderThread
            @Override
            public void onResult(IInputContentUriToken result) {
                final CompletableFuture<IInputContentUriToken> value = unwrap(atomicRef);
                if (value == null) {
                    return;
                }
                value.complete(result);
            }

            @BinderThread
            @Override
            public void onError(ThrowableHolder throwableHolder) {
                final CompletableFuture<IInputContentUriToken> value = unwrap(atomicRef);
                if (value == null) {
                    return;
                }
                value.completeExceptionally(new LightweightThrowable(throwableHolder));
            }
        };
    }
}
