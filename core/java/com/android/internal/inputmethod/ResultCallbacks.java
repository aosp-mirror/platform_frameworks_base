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

import java.util.concurrent.atomic.AtomicReference;

/**
 * Defines a set of factory methods to create {@link android.os.IBinder}-based callbacks that are
 * associated with completable objects defined in {@link Completable}.
 */
public final class ResultCallbacks {

    /**
     * Not intended to be instantiated.
     */
    private ResultCallbacks() {
    }

    @AnyThread
    @Nullable
    private static <T> T unwrap(@NonNull AtomicReference<T> atomicRef) {
        // Only the first caller will receive the non-null original object.
        return atomicRef.getAndSet(null);
    }

    /**
     * Creates {@link IIntResultCallback.Stub} that is to set {@link Completable.Int} when receiving
     * the result.
     *
     * @param value {@link Completable.Int} to be set when receiving the result.
     * @return {@link IIntResultCallback.Stub} that can be passed as a binder IPC parameter.
     */
    @AnyThread
    public static IIntResultCallback.Stub of(@NonNull Completable.Int value) {
        final AtomicReference<Completable.Int> atomicRef = new AtomicReference<>(value);

        return new IIntResultCallback.Stub() {
            @BinderThread
            @Override
            public void onResult(int result) {
                final Completable.Int value = unwrap(atomicRef);
                if (value == null) {
                    return;
                }
                value.onComplete(result);
            }

            @BinderThread
            @Override
            public void onError(ThrowableHolder throwableHolder) {
                final Completable.Int value = unwrap(atomicRef);
                if (value == null) {
                    return;
                }
                value.onError(throwableHolder);
            }
        };
    }

    /**
     * Creates {@link ICharSequenceResultCallback.Stub} that is to set
     * {@link Completable.CharSequence} when receiving the result.
     *
     * @param value {@link Completable.CharSequence} to be set when receiving the result.
     * @return {@link ICharSequenceResultCallback.Stub} that can be passed as a binder IPC
     *         parameter.
     */
    @AnyThread
    public static ICharSequenceResultCallback.Stub of(
            @NonNull Completable.CharSequence value) {
        final AtomicReference<Completable.CharSequence> atomicRef = new AtomicReference<>(value);

        return new ICharSequenceResultCallback.Stub() {
            @BinderThread
            @Override
            public void onResult(CharSequence result) {
                final Completable.CharSequence value = unwrap(atomicRef);
                if (value == null) {
                    return;
                }
                value.onComplete(result);
            }
        };
    }

    /**
     * Creates {@link IExtractedTextResultCallback.Stub} that is to set
     * {@link Completable.ExtractedText} when receiving the result.
     *
     * @param value {@link Completable.ExtractedText} to be set when receiving the result.
     * @return {@link IExtractedTextResultCallback.Stub} that can be passed as a binder IPC
     *         parameter.
     */
    @AnyThread
    public static IExtractedTextResultCallback.Stub of(
            @NonNull Completable.ExtractedText value) {
        final AtomicReference<Completable.ExtractedText> atomicRef = new AtomicReference<>(value);

        return new IExtractedTextResultCallback.Stub() {
            @BinderThread
            @Override
            public void onResult(android.view.inputmethod.ExtractedText result) {
                final Completable.ExtractedText value = unwrap(atomicRef);
                if (value == null) {
                    return;
                }
                value.onComplete(result);
            }
        };
    }

    /**
     * Creates {@link ISurroundingTextResultCallback.Stub} that is to set
     * {@link Completable.SurroundingText} when receiving the result.
     *
     * @param value {@link Completable.SurroundingText} to be set when receiving the result.
     * @return {@link ISurroundingTextResultCallback.Stub} that can be passed as a binder IPC
     *         parameter.
     */
    @AnyThread
    public static ISurroundingTextResultCallback.Stub of(
            @NonNull Completable.SurroundingText value) {
        final AtomicReference<Completable.SurroundingText> atomicRef = new AtomicReference<>(value);

        return new ISurroundingTextResultCallback.Stub() {
            @BinderThread
            @Override
            public void onResult(android.view.inputmethod.SurroundingText result) {
                final Completable.SurroundingText value = unwrap(atomicRef);
                if (value == null) {
                    return;
                }
                value.onComplete(result);
            }
        };
    }

    /**
     * Creates {@link IBooleanResultCallback.Stub} that is to set {@link Completable.Boolean} when
     * receiving the result.
     *
     * @param value {@link Completable.Boolean} to be set when receiving the result.
     * @return {@link IBooleanResultCallback.Stub} that can be passed as a binder IPC parameter.
     */
    @AnyThread
    public static IBooleanResultCallback.Stub of(@NonNull Completable.Boolean value) {
        final AtomicReference<Completable.Boolean> atomicRef = new AtomicReference<>(value);

        return new IBooleanResultCallback.Stub() {
            @BinderThread
            @Override
            public void onResult(boolean result) {
                final Completable.Boolean value = unwrap(atomicRef);
                if (value == null) {
                    return;
                }
                value.onComplete(result);
            }

            @BinderThread
            @Override
            public void onError(ThrowableHolder throwableHolder) {
                final Completable.Boolean value = unwrap(atomicRef);
                if (value == null) {
                    return;
                }
                value.onError(throwableHolder);
            }
        };
    }

    /**
     * Creates {@link IVoidResultCallback.Stub} that is to set {@link Completable.Void} when
     * receiving the result.
     *
     * @param value {@link Completable.Void} to be set when receiving the result.
     * @return {@link IVoidResultCallback.Stub} that can be passed as a binder IPC parameter.
     */
    @AnyThread
    public static IVoidResultCallback.Stub of(@NonNull Completable.Void value) {
        final AtomicReference<Completable.Void> atomicRef = new AtomicReference<>(value);

        return new IVoidResultCallback.Stub() {
            @BinderThread
            @Override
            public void onResult() {
                final Completable.Void value = unwrap(atomicRef);
                if (value == null) {
                    return;
                }
                value.onComplete();
            }

            @BinderThread
            @Override
            public void onError(ThrowableHolder throwableHolder) {
                final Completable.Void value = unwrap(atomicRef);
                if (value == null) {
                    return;
                }
                value.onError(throwableHolder);
            }
        };
    }

    /**
     * Creates {@link IInputContentUriTokenResultCallback.Stub} that is to set
     * {@link Completable.IInputContentUriToken} when receiving the result.
     *
     * @param value {@link Completable.IInputContentUriToken} to be set when receiving the result.
     * @return {@link IInputContentUriTokenResultCallback.Stub} that can be passed as a binder IPC
     * parameter.
     */
    @AnyThread
    public static IInputContentUriTokenResultCallback.Stub of(
            @NonNull Completable.IInputContentUriToken value) {
        final AtomicReference<Completable.IInputContentUriToken>
                atomicRef = new AtomicReference<>(value);

        return new IInputContentUriTokenResultCallback.Stub() {
            @BinderThread
            @Override
            public void onResult(IInputContentUriToken result) {
                final Completable.IInputContentUriToken value = unwrap(atomicRef);
                if (value == null) {
                    return;
                }
                value.onComplete(result);
            }

            @BinderThread
            @Override
            public void onError(ThrowableHolder throwableHolder) {
                final Completable.IInputContentUriToken value = unwrap(atomicRef);
                if (value == null) {
                    return;
                }
                value.onError(throwableHolder);
            }
        };
    }
}
