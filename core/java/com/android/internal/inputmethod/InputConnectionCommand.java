/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.AnyThread;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.BadParcelableException;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputContentInfo;

import java.lang.annotation.Retention;

/**
 * Defines the command message to be used for IMEs to remotely invoke
 * {@link android.view.inputmethod.InputConnection} APIs in the IME client process then receive
 * results.
 */
public final class InputConnectionCommand implements Parcelable {
    private static final String TAG = "InputConnectionCommand";

    @Retention(SOURCE)
    @IntDef(value = {
            ResultCallbackType.NULL,
            ResultCallbackType.BOOLEAN,
            ResultCallbackType.INT,
            ResultCallbackType.CHAR_SEQUENCE,
            ResultCallbackType.EXTRACTED_TEXT,
            ResultCallbackType.SURROUNDING_TEXT,
    })
    @interface ResultCallbackType {
        int NULL = 0;
        int BOOLEAN = 1;
        int INT = 2;
        int CHAR_SEQUENCE = 3;
        int EXTRACTED_TEXT = 4;
        int SURROUNDING_TEXT = 5;
    }

    @Retention(SOURCE)
    @IntDef(value = {
            ParcelableType.NULL,
            ParcelableType.EXTRACTED_TEXT_REQUEST,
            ParcelableType.COMPLETION_INFO,
            ParcelableType.CORRECTION_INFO,
            ParcelableType.KEY_EVENT,
            ParcelableType.INPUT_CONTENT_INFO,
    })
    @interface ParcelableType {
        int NULL = 0;
        int EXTRACTED_TEXT_REQUEST = 1;
        int COMPLETION_INFO = 2;
        int CORRECTION_INFO = 3;
        int KEY_EVENT = 4;
        int INPUT_CONTENT_INFO = 5;
    }

    @Retention(SOURCE)
    @IntDef(flag = true, value = {
            FieldMask.INT_ARG0,
            FieldMask.INT_ARG1,
            FieldMask.FLAGS,
            FieldMask.CHAR_SEQUENCE,
            FieldMask.STRING,
            FieldMask.BUNDLE,
            FieldMask.PARCELABLE,
            FieldMask.CALLBACK,
    })
    @interface FieldMask {
        int INT_ARG0 = 1 << 0;
        int INT_ARG1 = 1 << 1;
        int FLAGS = 1 << 2;
        int CHAR_SEQUENCE = 1 << 3;
        int STRING = 1 << 4;
        int BUNDLE = 1 << 5;
        int PARCELABLE = 1 << 6;
        int CALLBACK = 1 << 7;
    }

    @IntRange(from = InputConnectionCommandType.FIRST_COMMAND,
            to = InputConnectionCommandType.LAST_COMMAND)
    @InputConnectionCommandType
    public final int mCommandType;
    public final int mIntArg0;
    public final int mIntArg1;
    public final int mFlags;
    public final CharSequence mCharSequence;
    public final String mString;
    public final Bundle mBundle;
    @ParcelableType
    public final int mParcelableType;
    public final Parcelable mParcelable;
    @ResultCallbackType
    public final int mResultCallbackType;
    public final IBinder mResultCallback;

    private InputConnectionCommand(
            @IntRange(
                    from = InputConnectionCommandType.FIRST_COMMAND,
                    to = InputConnectionCommandType.LAST_COMMAND)
            @InputConnectionCommandType int type, int intArg0, int intArg1, int flags,
            @Nullable CharSequence charSequence, @Nullable String string, @Nullable Bundle bundle,
            @ParcelableType int parcelableType, @Nullable Parcelable parcelable,
            @ResultCallbackType int resultCallbackType, @Nullable IBinder resultCallback) {
        if (type < InputConnectionCommandType.FIRST_COMMAND
                || InputConnectionCommandType.LAST_COMMAND < type) {
            throw new IllegalArgumentException("Unknown type=" + type);
        }
        mCommandType = type;
        mIntArg0 = intArg0;
        mIntArg1 = intArg1;
        mFlags = flags;
        mCharSequence = charSequence;
        mString = string;
        mBundle = bundle;
        mParcelableType = parcelableType;
        mParcelable = parcelable;
        mResultCallbackType = resultCallbackType;
        mResultCallback = resultCallback;
    }

    /**
     * Creates {@link InputConnectionCommand} with the given {@link InputConnectionCommandType}.
     *
     * @param type {@link InputConnectionCommandType} to be set.
     * @return An {@link InputConnectionCommand} that is initialized with {@code type}.
     */
    @NonNull
    public static InputConnectionCommand create(
            @IntRange(
                    from = InputConnectionCommandType.FIRST_COMMAND,
                    to = InputConnectionCommandType.LAST_COMMAND)
            @InputConnectionCommandType int type) {
        return create(type, 0);
    }

    @NonNull
    static InputConnectionCommand create(
            @IntRange(
                    from = InputConnectionCommandType.FIRST_COMMAND,
                    to = InputConnectionCommandType.LAST_COMMAND)
            @InputConnectionCommandType int type, int intArg0) {
        return create(type, intArg0, 0);
    }

    @NonNull
    static InputConnectionCommand create(
            @IntRange(
                    from = InputConnectionCommandType.FIRST_COMMAND,
                    to = InputConnectionCommandType.LAST_COMMAND)
            @InputConnectionCommandType int type, int intArg0, int intArg1) {
        return create(type, intArg0, intArg1, 0, null);
    }

    @NonNull
    static InputConnectionCommand create(
            @IntRange(
                    from = InputConnectionCommandType.FIRST_COMMAND,
                    to = InputConnectionCommandType.LAST_COMMAND)
            @InputConnectionCommandType int type, int intArg0,
            int intArg1, int flags, @Nullable CharSequence charSequence) {
        return create(type, intArg0, intArg1, flags, charSequence, null, null);
    }

    @NonNull
    static InputConnectionCommand create(
            @IntRange(
                    from = InputConnectionCommandType.FIRST_COMMAND,
                    to = InputConnectionCommandType.LAST_COMMAND)
            @InputConnectionCommandType int type,
            int intArg0, int intArg1, int flags, @Nullable CharSequence charSequence,
            @Nullable String string, @Nullable Bundle bundle) {
        return create(type, intArg0, intArg1, flags, charSequence, string,
                bundle, ParcelableType.NULL, null);
    }

    @NonNull
    static InputConnectionCommand create(
            @IntRange(
                    from = InputConnectionCommandType.FIRST_COMMAND,
                    to = InputConnectionCommandType.LAST_COMMAND)
            @InputConnectionCommandType int type,
            int intArg0, int intArg1, int flags, @Nullable CharSequence charSequence,
            @Nullable String string, @Nullable Bundle bundle,
            @ParcelableType int parcelableType, @Nullable Parcelable parcelable) {
        return new InputConnectionCommand(type, intArg0, intArg1, flags, charSequence, string,
                bundle, parcelableType, parcelable, ResultCallbackType.NULL, null);
    }

    @NonNull
    static InputConnectionCommand create(
            @IntRange(
                    from = InputConnectionCommandType.FIRST_COMMAND,
                    to = InputConnectionCommandType.LAST_COMMAND)
            @InputConnectionCommandType int type,
            int intArg0, int intArg1, int flags, @Nullable CharSequence charSequence,
            @Nullable String string, @Nullable Bundle bundle,
            @ParcelableType int parcelableType, @Nullable Parcelable parcelable,
            @NonNull Completable.Boolean returnValue) {
        return new InputConnectionCommand(type, intArg0, intArg1, flags, charSequence, string,
                bundle, parcelableType, parcelable,
                ResultCallbackType.BOOLEAN, ResultCallbacks.of(returnValue));
    }

    @NonNull
    static InputConnectionCommand create(
            @IntRange(
                    from = InputConnectionCommandType.FIRST_COMMAND,
                    to = InputConnectionCommandType.LAST_COMMAND)
            @InputConnectionCommandType int type,
            int intArg0, int intArg1, int flags, @Nullable CharSequence charSequence,
            @Nullable String string, @Nullable Bundle bundle,
            @ParcelableType int parcelableType, @Nullable Parcelable parcelable,
            @NonNull Completable.Int returnValue) {
        return new InputConnectionCommand(type, intArg0, intArg1, flags, charSequence, string,
                bundle, parcelableType, parcelable,
                ResultCallbackType.INT, ResultCallbacks.of(returnValue));
    }

    @NonNull
    static InputConnectionCommand create(
            @IntRange(
                    from = InputConnectionCommandType.FIRST_COMMAND,
                    to = InputConnectionCommandType.LAST_COMMAND)
            @InputConnectionCommandType int type,
            int intArg0, int intArg1, int flags, @Nullable CharSequence charSequence,
            @Nullable String string, @Nullable Bundle bundle,
            @ParcelableType int parcelableType, @Nullable Parcelable parcelable,
            @NonNull Completable.CharSequence returnValue) {
        return new InputConnectionCommand(type, intArg0, intArg1, flags, charSequence, string,
                bundle, parcelableType, parcelable,
                ResultCallbackType.CHAR_SEQUENCE, ResultCallbacks.of(returnValue));
    }

    @NonNull
    static InputConnectionCommand create(
            @IntRange(
                    from = InputConnectionCommandType.FIRST_COMMAND,
                    to = InputConnectionCommandType.LAST_COMMAND)
            @InputConnectionCommandType int type,
            int intArg0, int intArg1, int flags, @Nullable CharSequence charSequence,
            @Nullable String string, @Nullable Bundle bundle,
            @ParcelableType int parcelableType, @Nullable Parcelable parcelable,
            @NonNull Completable.ExtractedText returnValue) {
        return new InputConnectionCommand(type, intArg0, intArg1, flags, charSequence, string,
                bundle, parcelableType, parcelable,
                ResultCallbackType.EXTRACTED_TEXT, ResultCallbacks.of(returnValue));
    }

    @NonNull
    static InputConnectionCommand create(
            @IntRange(
                    from = InputConnectionCommandType.FIRST_COMMAND,
                    to = InputConnectionCommandType.LAST_COMMAND)
            @InputConnectionCommandType int type,
            int intArg0, int intArg1, int flags, @Nullable CharSequence charSequence,
            @Nullable String string, @Nullable Bundle bundle,
            @ParcelableType int parcelableType, @Nullable Parcelable parcelable,
            @NonNull Completable.SurroundingText returnValue) {
        return new InputConnectionCommand(type, intArg0, intArg1, flags, charSequence, string,
                bundle, parcelableType, parcelable,
                ResultCallbackType.SURROUNDING_TEXT, ResultCallbacks.of(returnValue));
    }

    /**
     * {@inheritDoc}
     */
    @AnyThread
    @Override
    public int describeContents() {
        int result = 0;
        if (mBundle != null) {
            result |= mBundle.describeContents();
        }
        if (mParcelable != null) {
            result |= mParcelable.describeContents();
        }
        // Here we assume other objects will never contain FDs to be parcelled.
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @AnyThread
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mCommandType);

        @FieldMask final int fieldMask = getFieldMask();
        dest.writeInt(fieldMask);
        if ((fieldMask & FieldMask.INT_ARG0) != 0) {
            dest.writeInt(mIntArg0);
        }
        if ((fieldMask & FieldMask.INT_ARG1) != 0) {
            dest.writeInt(mIntArg1);
        }
        if ((fieldMask & FieldMask.FLAGS) != 0) {
            dest.writeInt(mFlags);
        }
        if ((fieldMask & FieldMask.CHAR_SEQUENCE) != 0) {
            TextUtils.writeToParcel(mCharSequence, dest, flags);
        }
        if ((fieldMask & FieldMask.STRING) != 0) {
            dest.writeString(mString);
        }
        if ((fieldMask & FieldMask.BUNDLE) != 0) {
            dest.writeBundle(mBundle);
        }
        if ((fieldMask & FieldMask.PARCELABLE) != 0) {
            dest.writeInt(mParcelableType);
            dest.writeTypedObject(mParcelable, flags);
        }
        if ((fieldMask & FieldMask.CALLBACK) != 0) {
            dest.writeInt(mResultCallbackType);
            dest.writeStrongBinder(mResultCallback);
        }
    }

    @FieldMask
    @AnyThread
    private int getFieldMask() {
        return (mIntArg0 != 0 ? FieldMask.INT_ARG0 : 0)
                | (mIntArg1 != 0 ? FieldMask.INT_ARG1 : 0)
                | (mFlags != 0 ? FieldMask.FLAGS : 0)
                | (mCharSequence != null ? FieldMask.CHAR_SEQUENCE : 0)
                | (mString != null ? FieldMask.STRING : 0)
                | (mBundle != null ? FieldMask.BUNDLE : 0)
                | (mParcelableType != ParcelableType.NULL ? FieldMask.PARCELABLE : 0)
                | (mResultCallbackType != ResultCallbackType.NULL ? FieldMask.CALLBACK : 0);
    }


    /**
     * A utility method to unparcel {@link InputConnectionCommand} from the given {@link Parcel}.
     *
     * <p>When this method throws any {@link RuntimeException} or its derived class, notably
     * {@link BadParcelableException}, {@code source} is considered to be in an unexpected state and
     * unsafe to continue reading any subsequent data.</p>
     *
     * @param source {@link Parcel} to read the data from.
     * @return {@link InputConnectionCommand} that is parcelled from {@code source}.
     */
    @AnyThread
    @NonNull
    private static InputConnectionCommand createFromParcel(@NonNull Parcel source) {
        final int type = source.readInt();
        if (type < InputConnectionCommandType.FIRST_COMMAND
                || InputConnectionCommandType.LAST_COMMAND < type) {
            throw new BadParcelableException("Invalid InputConnectionCommandType=" + type);
        }

        @FieldMask final int fieldMask = source.readInt();
        final int intArg0 = (fieldMask & FieldMask.INT_ARG0) != 0 ? source.readInt() : 0;
        final int intArg1 = (fieldMask & FieldMask.INT_ARG1) != 0 ? source.readInt() : 0;
        final int flags = (fieldMask & FieldMask.FLAGS) != 0 ? source.readInt() : 0;
        final CharSequence charSequence = (fieldMask & FieldMask.CHAR_SEQUENCE) != 0
                ? TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source) : null;
        final String string = (fieldMask & FieldMask.STRING) != 0 ? source.readString() : null;
        final Bundle bundle = (fieldMask & FieldMask.BUNDLE) != 0 ? source.readBundle() : null;

        @ParcelableType final int parcelableType;
        final Parcelable parcelable;
        if ((fieldMask & FieldMask.PARCELABLE) != 0) {
            parcelableType = source.readInt();
            switch (parcelableType) {
                case ParcelableType.EXTRACTED_TEXT_REQUEST:
                    parcelable = source.readTypedObject(ExtractedTextRequest.CREATOR);
                    break;
                case ParcelableType.COMPLETION_INFO:
                    parcelable = source.readTypedObject(CompletionInfo.CREATOR);
                    break;
                case ParcelableType.CORRECTION_INFO:
                    parcelable = source.readTypedObject(CorrectionInfo.CREATOR);
                    break;
                case ParcelableType.KEY_EVENT:
                    parcelable = source.readTypedObject(KeyEvent.CREATOR);
                    break;
                case ParcelableType.INPUT_CONTENT_INFO:
                    parcelable = source.readTypedObject(InputContentInfo.CREATOR);
                    break;
                default:
                    throw new BadParcelableException(
                            "Invalid InputConnectionCommand.ParcelableType=" + parcelableType);
            }
        } else {
            parcelableType = ParcelableType.NULL;
            parcelable = null;
        }
        @ResultCallbackType final int resultCallbackType;
        final IBinder resultCallback;
        if ((fieldMask & FieldMask.CALLBACK) != 0) {
            resultCallbackType = source.readInt();
            switch (resultCallbackType) {
                case ResultCallbackType.BOOLEAN:
                case ResultCallbackType.INT:
                case ResultCallbackType.CHAR_SEQUENCE:
                case ResultCallbackType.EXTRACTED_TEXT:
                case ResultCallbackType.SURROUNDING_TEXT:
                    resultCallback = source.readStrongBinder();
                    break;
                default:
                    throw new BadParcelableException(
                            "Invalid InputConnectionCommand.ResultCallbackType="
                                    + resultCallbackType);
            }
        } else {
            resultCallbackType = ResultCallbackType.NULL;
            resultCallback = null;
        }
        return new InputConnectionCommand(type, intArg0, intArg1, flags, charSequence, string,
                bundle, parcelableType, parcelable, resultCallbackType, resultCallback);
    }

    /**
     * Used to make this class parcelable.
     */
    public static final Parcelable.Creator<InputConnectionCommand> CREATOR =
            new Parcelable.Creator<InputConnectionCommand>() {
                @AnyThread
                @NonNull
                @Override
                public InputConnectionCommand createFromParcel(Parcel source) {
                    return InputConnectionCommand.createFromParcel(source);
                }

                @AnyThread
                @NonNull
                @Override
                public InputConnectionCommand[] newArray(int size) {
                    return new InputConnectionCommand[size];
                }
            };
}
