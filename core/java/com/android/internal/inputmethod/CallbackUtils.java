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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.RemoteException;
import android.util.Log;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.SurroundingText;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Defines a set of helper methods to callback corresponding results in {@link ResultCallbacks}.
 */
public final class CallbackUtils {

    /**
     * Not intended to be instantiated.
     */
    private CallbackUtils() {
    }

    /**
     * A utility method using given {@link IBooleanResultCallback} to callback the result.
     *
     * @param callback {@link IBooleanResultCallback} to be called back.
     * @param resultSupplier the supplier from which the result is provided.
     */
    public static void onResult(@NonNull IBooleanResultCallback callback,
            @NonNull BooleanSupplier resultSupplier) {
        boolean result = false;
        Throwable exception = null;

        try {
            result = resultSupplier.getAsBoolean();
        } catch (Throwable throwable) {
            exception = throwable;
        }

        try {
            if (exception != null) {
                callback.onError(ThrowableHolder.of(exception));
                return;
            }
            callback.onResult(result);
        } catch (RemoteException ignored) { }
    }

    /**
     * A utility method using given {@link IIntResultCallback} to callback the result.
     *
     * @param callback {@link IIntResultCallback} to be called back.
     * @param resultSupplier the supplier from which the result is provided.
     */
    public static void onResult(@NonNull IIntResultCallback callback,
            @NonNull IntSupplier resultSupplier) {
        int result = 0;
        Throwable exception = null;

        try {
            result = resultSupplier.getAsInt();
        } catch (Throwable throwable) {
            exception = throwable;
        }

        try {
            if (exception != null) {
                callback.onError(ThrowableHolder.of(exception));
                return;
            }
            callback.onResult(result);
        } catch (RemoteException ignored) { }
    }

    /**
     * A utility method using given {@link IVoidResultCallback} to callback the result.
     *
     * @param callback {@link IVoidResultCallback} to be called back.
     * @param runnable to execute the given method
     */
    public static void onResult(@NonNull IVoidResultCallback callback,
            @NonNull Runnable runnable) {
        Throwable exception = null;

        try {
            runnable.run();
        } catch (Throwable throwable) {
            exception = throwable;
        }

        try {
            if (exception != null) {
                callback.onError(ThrowableHolder.of(exception));
                return;
            }
            callback.onResult();
        } catch (RemoteException ignored) { }
    }

    /**
     * A utility method using given {@link IInputContentUriTokenResultCallback} to callback the
     * result.
     *
     * @param callback {@link IInputContentUriTokenResultCallback} to be called back.
     * @param resultSupplier the supplier from which the result is provided.
     */
    public static void onResult(@NonNull IInputContentUriTokenResultCallback callback,
            @NonNull Supplier<IInputContentUriToken> resultSupplier) {
        IInputContentUriToken result = null;
        Throwable exception = null;

        try {
            result = resultSupplier.get();
        } catch (Throwable throwable) {
            exception = throwable;
        }

        try {
            if (exception != null) {
                callback.onError(ThrowableHolder.of(exception));
                return;
            }
            callback.onResult(result);
        } catch (RemoteException ignored) { }
    }

    /**
     * A utility method to reply associated with {@link InputConnectionCommand}.
     *
     * @param command {@link InputConnectionCommand} to be replied.
     * @param result a {@link String} value to be replied.
     * @param tag tag name to be used for debug output when the invocation fails.
     */
    public static void onResult(@NonNull InputConnectionCommand command, boolean result,
            @Nullable String tag) {
        if (command.mResultCallbackType != InputConnectionCommand.ResultCallbackType.BOOLEAN) {
            if (tag != null) {
                Log.e(tag, InputMethodDebug.inputConnectionCommandTypeToString(command.mCommandType)
                        + ": Failed to return result=" + result + " due to callback type mismatch."
                        + " expected=String actual=" + command.mResultCallbackType);
            }
            return;
        }
        try {
            IBooleanResultCallback.Stub.asInterface(command.mResultCallback).onResult(result);
        } catch (Throwable e) {
            if (tag != null) {
                Log.e(tag, InputMethodDebug.inputConnectionCommandTypeToString(command.mCommandType)
                        + ": Failed to return result=" + result, e);
            }
        }
    }

    /**
     * A utility method to reply associated with {@link InputConnectionCommand}.
     *
     * @param command {@link InputConnectionCommand} to be replied.
     * @param result an int result value to be replied.
     * @param tag tag name to be used for debug output when the invocation fails.
     */
    public static void onResult(@NonNull InputConnectionCommand command, int result,
            @Nullable String tag) {
        if (command.mResultCallbackType != InputConnectionCommand.ResultCallbackType.INT) {
            if (tag != null) {
                Log.e(tag, InputMethodDebug.inputConnectionCommandTypeToString(command.mCommandType)
                        + ": Failed to return result=" + result + " due to callback type mismatch."
                        + " expected=int actual=" + command.mResultCallbackType);
            }
            return;
        }
        try {
            IIntResultCallback.Stub.asInterface(command.mResultCallback).onResult(result);
        } catch (Throwable e) {
            if (tag != null) {
                Log.e(tag, InputMethodDebug.inputConnectionCommandTypeToString(command.mCommandType)
                        + ": Failed to return result=" + result, e);
            }
        }
    }

    /**
     * A utility method to reply associated with {@link InputConnectionCommand}.
     *
     * @param command {@link InputConnectionCommand} to be replied.
     * @param result a {@link CharSequence} result value to be replied.
     * @param tag tag name to be used for debug output when the invocation fails.
     */
    public static void onResult(@NonNull InputConnectionCommand command,
            @Nullable CharSequence result, @Nullable String tag) {
        if (command.mResultCallbackType
                != InputConnectionCommand.ResultCallbackType.CHAR_SEQUENCE) {
            if (tag != null) {
                Log.e(tag, InputMethodDebug.inputConnectionCommandTypeToString(command.mCommandType)
                        + ": Failed to return result=" + result + " due to callback type mismatch."
                        + " expected=CharSequence actual=" + command.mResultCallbackType);
            }
            return;
        }
        try {
            ICharSequenceResultCallback.Stub.asInterface(command.mResultCallback).onResult(result);
        } catch (Throwable e) {
            if (tag != null) {
                Log.e(tag, InputMethodDebug.inputConnectionCommandTypeToString(command.mCommandType)
                        + ": Failed to return result=" + result, e);
            }
        }
    }

    /**
     * A utility method to reply associated with {@link InputConnectionCommand}.
     *
     * @param command {@link InputConnectionCommand} to be replied.
     * @param result a {@link ExtractedText} result value to be replied.
     * @param tag tag name to be used for debug output when the invocation fails.
     */
    public static void onResult(@NonNull InputConnectionCommand command,
            @Nullable ExtractedText result, @Nullable String tag) {
        if (command.mResultCallbackType
                != InputConnectionCommand.ResultCallbackType.EXTRACTED_TEXT) {
            if (tag != null) {
                Log.e(tag, InputMethodDebug.inputConnectionCommandTypeToString(command.mCommandType)
                        + ": Failed to return result=" + result + " due to callback type mismatch."
                        + " expected=ExtractedText actual=" + command.mResultCallbackType);
            }
            return;
        }
        try {
            IExtractedTextResultCallback.Stub.asInterface(command.mResultCallback).onResult(result);
        } catch (Throwable e) {
            if (tag != null) {
                Log.e(tag, InputMethodDebug.inputConnectionCommandTypeToString(command.mCommandType)
                        + ": Failed to return result=" + result, e);
            }
        }
    }

    /**
     * A utility method to reply associated with {@link InputConnectionCommand}.
     *
     * @param command {@link InputConnectionCommand} to be replied.
     * @param result a {@link SurroundingText} result value to be replied.
     * @param tag tag name to be used for debug output when the invocation fails.
     */
    public static void onResult(@NonNull InputConnectionCommand command,
            @Nullable SurroundingText result, @Nullable String tag) {
        if (command.mResultCallbackType
                != InputConnectionCommand.ResultCallbackType.SURROUNDING_TEXT) {
            if (tag != null) {
                Log.e(tag, InputMethodDebug.inputConnectionCommandTypeToString(command.mCommandType)
                        + ": Failed to return result=" + result + " due to callback type mismatch."
                        + " expected=SurroundingText actual=" + command.mResultCallbackType);
            }
            return;
        }
        try {
            ISurroundingTextResultCallback.Stub.asInterface(command.mResultCallback)
                    .onResult(result);
        } catch (Throwable e) {
            if (tag != null) {
                Log.e(tag, InputMethodDebug.inputConnectionCommandTypeToString(command.mCommandType)
                        + ": Failed to return result=" + result, e);
            }
        }
    }
}
