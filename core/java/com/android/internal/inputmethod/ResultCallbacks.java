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
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;

import com.android.internal.view.InputBindResult;

import java.lang.ref.WeakReference;
import java.util.List;
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
     * Creates {@link IIntResultCallback.Stub} that is to set {@link Completable.Int} when receiving
     * the result.
     *
     * @param value {@link Completable.Int} to be set when receiving the result.
     * @return {@link IIntResultCallback.Stub} that can be passed as a binder IPC parameter.
     */
    @AnyThread
    public static IIntResultCallback.Stub of(@NonNull Completable.Int value) {
        final AtomicReference<WeakReference<Completable.Int>>
                atomicRef = new AtomicReference<>(new WeakReference<>(value));

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
        final AtomicReference<WeakReference<Completable.CharSequence>> atomicRef =
                new AtomicReference<>(new WeakReference<>(value));

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
        final AtomicReference<WeakReference<Completable.ExtractedText>>
                atomicRef = new AtomicReference<>(new WeakReference<>(value));

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
        final AtomicReference<WeakReference<Completable.SurroundingText>>
                atomicRef = new AtomicReference<>(new WeakReference<>(value));

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
     * Creates {@link IInputBindResultResultCallback.Stub} that is to set
     * {@link Completable.InputBindResult} when receiving the result.
     *
     * @param value {@link Completable.InputBindResult} to be set when receiving the result.
     * @return {@link IInputBindResultResultCallback.Stub} that can be passed as a binder IPC
     *         parameter.
     */
    @AnyThread
    public static IInputBindResultResultCallback.Stub of(
            @NonNull Completable.InputBindResult value) {
        final AtomicReference<WeakReference<Completable.InputBindResult>>
                atomicRef = new AtomicReference<>(new WeakReference<>(value));

        return new IInputBindResultResultCallback.Stub() {
            @BinderThread
            @Override
            public void onResult(InputBindResult result) {
                final Completable.InputBindResult value = unwrap(atomicRef);
                if (value == null) {
                    return;
                }
                value.onComplete(result);
            }

            @BinderThread
            @Override
            public void onError(ThrowableHolder throwableHolder) {
                final Completable.InputBindResult value = unwrap(atomicRef);
                if (value == null) {
                    return;
                }
                value.onError(throwableHolder);
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
        final AtomicReference<WeakReference<Completable.Boolean>>
                atomicRef = new AtomicReference<>(new WeakReference<>(value));

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
     * Creates {@link IInputMethodSubtypeResultCallback.Stub} that is to set
     * {@link Completable.InputMethodSubtype} when receiving the result.
     *
     * @param value {@link Completable.InputMethodSubtype} to be set when receiving the result.
     * @return {@link IInputMethodSubtypeResultCallback.Stub} that can be passed as a binder
     * IPC parameter.
     */
    @AnyThread
    public static IInputMethodSubtypeResultCallback.Stub of(
            @NonNull Completable.InputMethodSubtype value) {
        final AtomicReference<WeakReference<Completable.InputMethodSubtype>>
                atomicRef = new AtomicReference<>(new WeakReference<>(value));

        return new IInputMethodSubtypeResultCallback.Stub() {
            @BinderThread
            @Override
            public void onResult(InputMethodSubtype result) {
                final Completable.InputMethodSubtype value = unwrap(atomicRef);
                if (value == null) {
                    return;
                }
                value.onComplete(result);
            }

            @BinderThread
            @Override
            public void onError(ThrowableHolder throwableHolder) {
                final Completable.InputMethodSubtype value = unwrap(atomicRef);
                if (value == null) {
                    return;
                }
                value.onError(throwableHolder);
            }
        };
    }

    /**
     * Creates {@link IInputMethodSubtypeListResultCallback.Stub} that is to set
     * {@link Completable.InputMethodSubtypeList} when receiving the result.
     *
     * @param value {@link Completable.InputMethodSubtypeList} to be set when receiving the result.
     * @return {@link IInputMethodSubtypeListResultCallback.Stub} that can be passed as a binder
     * IPC parameter.
     */
    @AnyThread
    public static IInputMethodSubtypeListResultCallback.Stub of(
            @NonNull Completable.InputMethodSubtypeList value) {
        final AtomicReference<WeakReference<Completable.InputMethodSubtypeList>>
                atomicRef = new AtomicReference<>(new WeakReference<>(value));

        return new IInputMethodSubtypeListResultCallback.Stub() {
            @BinderThread
            @Override
            public void onResult(List<InputMethodSubtype> result) {
                final Completable.InputMethodSubtypeList value = unwrap(atomicRef);
                if (value == null) {
                    return;
                }
                value.onComplete(result);
            }

            @BinderThread
            @Override
            public void onError(ThrowableHolder throwableHolder) {
                final Completable.InputMethodSubtypeList value = unwrap(atomicRef);
                if (value == null) {
                    return;
                }
                value.onError(throwableHolder);
            }
        };
    }

    /**
     * Creates {@link IInputMethodInfoListResultCallback.Stub} that is to set
     * {@link Completable.InputMethodInfoList} when receiving the result.
     *
     * @param value {@link Completable.InputMethodInfoList} to be set when receiving the result.
     * @return {@link IInputMethodInfoListResultCallback.Stub} that can be passed as a binder
     * IPC parameter.
     */
    @AnyThread
    public static IInputMethodInfoListResultCallback.Stub of(
            @NonNull Completable.InputMethodInfoList value) {
        final AtomicReference<WeakReference<Completable.InputMethodInfoList>>
                atomicRef = new AtomicReference<>(new WeakReference<>(value));

        return new IInputMethodInfoListResultCallback.Stub() {
            @BinderThread
            @Override
            public void onResult(List<InputMethodInfo> result) {
                final Completable.InputMethodInfoList value = unwrap(atomicRef);
                if (value == null) {
                    return;
                }
                value.onComplete(result);
            }

            @BinderThread
            @Override
            public void onError(ThrowableHolder throwableHolder) {
                final Completable.InputMethodInfoList value = unwrap(atomicRef);
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
        final AtomicReference<WeakReference<Completable.Void>> atomicRef =
                new AtomicReference<>(new WeakReference<>(value));

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
     * Creates {@link IIInputContentUriTokenResultCallback.Stub} that is to set
     * {@link Completable.IInputContentUriToken} when receiving the result.
     *
     * @param value {@link Completable.IInputContentUriToken} to be set when receiving the result.
     * @return {@link IIInputContentUriTokenResultCallback.Stub} that can be passed as a binder IPC
     * parameter.
     */
    @AnyThread
    public static IIInputContentUriTokenResultCallback.Stub of(
            @NonNull Completable.IInputContentUriToken value) {
        final AtomicReference<WeakReference<Completable.IInputContentUriToken>>
                atomicRef = new AtomicReference<>(new WeakReference<>(value));

        return new IIInputContentUriTokenResultCallback.Stub() {
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
