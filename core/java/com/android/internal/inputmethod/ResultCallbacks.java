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

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Defines a set of factory methods to create {@link android.os.IBinder}-based callbacks that are
 * associated with completable objects defined in {@link CancellationGroup.Completable}.
 */
public final class ResultCallbacks {

    /**
     * Not intended to be instantiated.
     */
    private ResultCallbacks() {
    }

    @AnyThread
    @Nullable
    private static <T> T unwrap(@NonNull AtomicReference<WeakReference<T>> atomicRef) {
        final WeakReference<T> ref = atomicRef.getAndSet(null);
        if (ref == null) {
            // Double-call is guaranteed to be ignored here.
            return null;
        }
        final T value = ref.get();
        ref.clear();
        return value;
    }

    /**
     * Creates {@link IIntResultCallback.Stub} that is to set
     * {@link CancellationGroup.Completable.Int} when receiving the result.
     *
     * @param value {@link CancellationGroup.Completable.Int} to be set when receiving the result.
     * @return {@link IIntResultCallback.Stub} that can be passed as a binder IPC parameter.
     */
    @AnyThread
    public static IIntResultCallback.Stub of(@NonNull CancellationGroup.Completable.Int value) {
        final AtomicReference<WeakReference<CancellationGroup.Completable.Int>>
                atomicRef = new AtomicReference<>(new WeakReference<>(value));

        return new IIntResultCallback.Stub() {
            @BinderThread
            @Override
            public void onResult(int result) {
                final CancellationGroup.Completable.Int value = unwrap(atomicRef);
                if (value == null) {
                    return;
                }
                value.onComplete(result);
            }
        };
    }

    /**
     * Creates {@link ICharSequenceResultCallback.Stub} that is to set
     * {@link CancellationGroup.Completable.CharSequence} when receiving the result.
     *
     * @param value {@link CancellationGroup.Completable.CharSequence} to be set when receiving the
     *              result.
     * @return {@link ICharSequenceResultCallback.Stub} that can be passed as a binder IPC
     *         parameter.
     */
    @AnyThread
    public static ICharSequenceResultCallback.Stub of(
            @NonNull CancellationGroup.Completable.CharSequence value) {
        final AtomicReference<WeakReference<CancellationGroup.Completable.CharSequence>> atomicRef =
                new AtomicReference<>(new WeakReference<>(value));

        return new ICharSequenceResultCallback.Stub() {
            @BinderThread
            @Override
            public void onResult(CharSequence result) {
                final CancellationGroup.Completable.CharSequence value = unwrap(atomicRef);
                if (value == null) {
                    return;
                }
                value.onComplete(result);
            }
        };
    }

    /**
     * Creates {@link IExtractedTextResultCallback.Stub} that is to set
     * {@link CancellationGroup.Completable.ExtractedText} when receiving the result.
     *
     * @param value {@link CancellationGroup.Completable.ExtractedText} to be set when receiving the
     *              result.
     * @return {@link IExtractedTextResultCallback.Stub} that can be passed as a binder IPC
     *         parameter.
     */
    @AnyThread
    public static IExtractedTextResultCallback.Stub of(
            @NonNull CancellationGroup.Completable.ExtractedText value) {
        final AtomicReference<WeakReference<CancellationGroup.Completable.ExtractedText>>
                atomicRef = new AtomicReference<>(new WeakReference<>(value));

        return new IExtractedTextResultCallback.Stub() {
            @BinderThread
            @Override
            public void onResult(android.view.inputmethod.ExtractedText result) {
                final CancellationGroup.Completable.ExtractedText value = unwrap(atomicRef);
                if (value == null) {
                    return;
                }
                value.onComplete(result);
            }
        };
    }
}
