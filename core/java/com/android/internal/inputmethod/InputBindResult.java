/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.annotation.IntDef;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.SparseArray;
import android.view.InputChannel;

import java.lang.annotation.Retention;

/**
 * Bundle of information returned by input method manager about a successful
 * binding to an input method.
 */
public final class InputBindResult implements Parcelable {

    @Retention(SOURCE)
    @IntDef({
            ResultCode.SUCCESS_WITH_IME_SESSION,
            ResultCode.SUCCESS_WAITING_IME_SESSION,
            ResultCode.SUCCESS_WAITING_IME_BINDING,
            ResultCode.SUCCESS_REPORT_WINDOW_FOCUS_ONLY,
            ResultCode.ERROR_NULL,
            ResultCode.ERROR_NO_IME,
            ResultCode.ERROR_INVALID_PACKAGE_NAME,
            ResultCode.ERROR_SYSTEM_NOT_READY,
            ResultCode.ERROR_IME_NOT_CONNECTED,
            ResultCode.ERROR_INVALID_USER,
            ResultCode.ERROR_NULL_EDITOR_INFO,
            ResultCode.ERROR_NOT_IME_TARGET_WINDOW,
            ResultCode.ERROR_NO_EDITOR,
            ResultCode.ERROR_DISPLAY_ID_MISMATCH,
            ResultCode.ERROR_INVALID_DISPLAY_ID,
            ResultCode.SUCCESS_WITH_ACCESSIBILITY_SESSION
    })
    public @interface ResultCode {
        /**
         * Indicates that everything in this result object including {@link #method} is valid.
         */
        int SUCCESS_WITH_IME_SESSION = 0;
        /**
         * Indicates that this is a temporary binding until the
         * {@link android.inputmethodservice.InputMethodService} (IMS) establishes a valid session
         * to {@link com.android.server.inputmethod.InputMethodManagerService} (IMMS).
         *
         * <p>Note that in this state the IMS is already bound to IMMS but the logical session
         * is not yet established on top of the IPC channel.</p>
         *
         * <p>Some of fields such as {@link #channel} is not yet available.</p>
         *
         * @see android.inputmethodservice.InputMethodService#onCreateInputMethodSessionInterface()
         **/
        int SUCCESS_WAITING_IME_SESSION = 1;
        /**
         * Indicates that this is a temporary binding until the
         * {@link android.inputmethodservice.InputMethodService} (IMS) establishes a valid session
         * to {@link com.android.server.inputmethod.InputMethodManagerService} (IMMS).
         *
         * <p>Note that in this state the IMMS has already initiated a connection to the IMS but
         * the binding process is not completed yet.</p>
         *
         * <p>Some of fields such as {@link #channel} is not yet available.</p>
         * @see android.content.ServiceConnection#onServiceConnected(ComponentName, IBinder)
         */
        int SUCCESS_WAITING_IME_BINDING = 2;
        /**
         * Indicates that {@link com.android.server.inputmethod.InputMethodManagerService} has a
         * pending operation to switch to a different user.
         *
         * <p>Note that in this state even what would be the next current IME is not determined.</p>
         */
        int SUCCESS_WAITING_USER_SWITCHING = 3;
        /**
         * Indicates that this is not intended for starting input but just for reporting window
         * focus change from the application process.
         *
         * <p>All other fields do not have meaningful value.</p>
         */
        int SUCCESS_REPORT_WINDOW_FOCUS_ONLY = 4;
        /**
         * Indicates somehow
         * {@link
         * com.android.server.inputmethod.InputMethodManagerService#startInputOrWindowGainedFocus}
         * is trying to return null {@link InputBindResult}, which must never happen.
         */
        int ERROR_NULL = 5;
        /**
         * Indicates that {@link com.android.server.inputmethod.InputMethodManagerService}
         * recognizes no IME.
         */
        int ERROR_NO_IME = 6;
        /**
         * Indicates that {@link android.view.inputmethod.EditorInfo#packageName} does not match
         * the caller UID.
         *
         * @see android.view.inputmethod.EditorInfo#packageName
         */
        int ERROR_INVALID_PACKAGE_NAME = 7;
        /**
         * Indicates that the system is still in an early stage of the boot process and any 3rd
         * party application is not allowed to run.
         *
         * @see com.android.server.SystemService#PHASE_THIRD_PARTY_APPS_CAN_START
         */
        int ERROR_SYSTEM_NOT_READY = 8;
        /**
         * Indicates that {@link com.android.server.inputmethod.InputMethodManagerService} tried to
         * connect to an {@link android.inputmethodservice.InputMethodService} but failed.
         *
         * @see android.content.Context#bindServiceAsUser(Intent, ServiceConnection, int,
         *      android.os.UserHandle)
         */
        int ERROR_IME_NOT_CONNECTED = 9;
        /**
         * Indicates that the caller is not the foreground user, does not have
         * {@link android.Manifest.permission#INTERACT_ACROSS_USERS_FULL} permission, or the user
         * specified in {@link android.view.inputmethod.EditorInfo#targetInputMethodUser} is not
         * running.
         */
        int ERROR_INVALID_USER = 10;
        /**
         * Indicates that the caller should have specified non-null
         * {@link android.view.inputmethod.EditorInfo}.
         */
        int ERROR_NULL_EDITOR_INFO = 11;
        /**
         * Indicates that the target window the client specified cannot be the IME target right now.
         *
         * <p>Due to the asynchronous nature of Android OS, we cannot completely avoid this error.
         * The client should try to restart input when its {@link android.view.Window} is focused
         * again.</p>
         *
         * @see com.android.server.wm.WindowManagerInternal#isInputMethodClientFocus(int, int, int)
         */
        int ERROR_NOT_IME_TARGET_WINDOW = 12;
        /**
         * Indicates that focused view in the current window is not an editor.
         */
        int ERROR_NO_EDITOR = 13;
        /**
         * Indicates that there is a mismatch in display ID between IME client and focused Window.
         */
        int ERROR_DISPLAY_ID_MISMATCH = 14;
        /**
         * Indicates that current IME client is no longer allowed to access to the associated
         * display.
         */
        int ERROR_INVALID_DISPLAY_ID = 15;
        /**
         * Indicates that a valid session is created and result is ready for accessibility.
         */
        int SUCCESS_WITH_ACCESSIBILITY_SESSION = 16;
    }

    @ResultCode
    public final int result;

    /**
     * The input method service.
     */
    public final IInputMethodSession method;

    /**
     * The accessibility services.
     */
    public final SparseArray<IAccessibilityInputMethodSession> accessibilitySessions;

    /**
     * The input channel used to send input events to this IME.
     */
    public final InputChannel channel;

    /**
     * The ID for this input method, as found in InputMethodInfo; null if
     * no input method will be bound.
     */
    public final String id;

    /**
     * Sequence number of this binding.
     */
    public final int sequence;

    /**
     * {@code true} if the IME explicitly specifies {@code suppressesSpellChecker="true"}.
     */
    public final boolean isInputMethodSuppressingSpellChecker;

    /**
     * Creates a new instance of {@link InputBindResult}.
     *
     * @param result A result code defined in {@link ResultCode}.
     * @param method {@link IInputMethodSession} to interact with the IME.
     * @param accessibilitySessions {@link IAccessibilityInputMethodSession} to interact with
     *                              accessibility services.
     * @param channel {@link InputChannel} to forward input events to the IME.
     * @param id The {@link String} representations of the IME, which is the same as
     *           {@link android.view.inputmethod.InputMethodInfo#getId()} and
     *           {@link android.content.ComponentName#flattenToShortString()}.
     * @param sequence A sequence number of this binding.
     * @param isInputMethodSuppressingSpellChecker {@code true} if the IME explicitly specifies
     *                                             {@code suppressesSpellChecker="true"}.
     */
    public InputBindResult(@ResultCode int result,
            IInputMethodSession method,
            SparseArray<IAccessibilityInputMethodSession> accessibilitySessions,
            InputChannel channel, String id, int sequence,
            boolean isInputMethodSuppressingSpellChecker) {
        this.result = result;
        this.method = method;
        this.accessibilitySessions = accessibilitySessions;
        this.channel = channel;
        this.id = id;
        this.sequence = sequence;
        this.isInputMethodSuppressingSpellChecker = isInputMethodSuppressingSpellChecker;
    }

    private InputBindResult(Parcel source) {
        result = source.readInt();
        method = IInputMethodSession.Stub.asInterface(source.readStrongBinder());
        int n = source.readInt();
        if (n < 0) {
            accessibilitySessions = null;
        } else {
            accessibilitySessions = new SparseArray<>(n);
            while (n > 0) {
                int key = source.readInt();
                IAccessibilityInputMethodSession value =
                        IAccessibilityInputMethodSession.Stub.asInterface(
                                source.readStrongBinder());
                accessibilitySessions.append(key, value);
                n--;
            }
        }
        if (source.readInt() != 0) {
            channel = InputChannel.CREATOR.createFromParcel(source);
        } else {
            channel = null;
        }
        id = source.readString();
        sequence = source.readInt();
        isInputMethodSuppressingSpellChecker = source.readBoolean();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "InputBindResult{result=" + getResultString() + " method=" + method + " id=" + id
                + " sequence=" + sequence
                + " result=" + result
                + " isInputMethodSuppressingSpellChecker=" + isInputMethodSuppressingSpellChecker
                + "}";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(result);
        dest.writeStrongInterface(method);
        if (accessibilitySessions == null) {
            dest.writeInt(-1);
        } else {
            int n = accessibilitySessions.size();
            dest.writeInt(n);
            int i = 0;
            while (i < n) {
                dest.writeInt(accessibilitySessions.keyAt(i));
                dest.writeStrongInterface(accessibilitySessions.valueAt(i));
                i++;
            }
        }
        if (channel != null) {
            dest.writeInt(1);
            channel.writeToParcel(dest, flags);
        } else {
            dest.writeInt(0);
        }
        dest.writeString(id);
        dest.writeInt(sequence);
        dest.writeBoolean(isInputMethodSuppressingSpellChecker);
    }

    /**
     * Used to make this class parcelable.
     */
    public static final Parcelable.Creator<InputBindResult> CREATOR =
            new Parcelable.Creator<InputBindResult>() {
        @Override
        public InputBindResult createFromParcel(Parcel source) {
            return new InputBindResult(source);
        }

        @Override
        public InputBindResult[] newArray(int size) {
            return new InputBindResult[size];
        }
    };

    /**
     * {@inheritDoc}
     */
    @Override
    public int describeContents() {
        return channel != null ? channel.describeContents() : 0;
    }

    private String getResultString() {
        switch (result) {
            case ResultCode.SUCCESS_WITH_IME_SESSION:
                return "SUCCESS_WITH_IME_SESSION";
            case ResultCode.SUCCESS_WAITING_IME_SESSION:
                return "SUCCESS_WAITING_IME_SESSION";
            case ResultCode.SUCCESS_WAITING_IME_BINDING:
                return "SUCCESS_WAITING_IME_BINDING";
            case ResultCode.SUCCESS_WAITING_USER_SWITCHING:
                return "SUCCESS_WAITING_USER_SWITCHING";
            case ResultCode.SUCCESS_REPORT_WINDOW_FOCUS_ONLY:
                return "SUCCESS_REPORT_WINDOW_FOCUS_ONLY";
            case ResultCode.ERROR_NULL:
                return "ERROR_NULL";
            case ResultCode.ERROR_NO_IME:
                return "ERROR_NO_IME";
            case ResultCode.ERROR_NO_EDITOR:
                return "ERROR_NO_EDITOR";
            case ResultCode.ERROR_INVALID_PACKAGE_NAME:
                return "ERROR_INVALID_PACKAGE_NAME";
            case ResultCode.ERROR_SYSTEM_NOT_READY:
                return "ERROR_SYSTEM_NOT_READY";
            case ResultCode.ERROR_IME_NOT_CONNECTED:
                return "ERROR_IME_NOT_CONNECTED";
            case ResultCode.ERROR_INVALID_USER:
                return "ERROR_INVALID_USER";
            case ResultCode.ERROR_NULL_EDITOR_INFO:
                return "ERROR_NULL_EDITOR_INFO";
            case ResultCode.ERROR_NOT_IME_TARGET_WINDOW:
                return "ERROR_NOT_IME_TARGET_WINDOW";
            case ResultCode.ERROR_DISPLAY_ID_MISMATCH:
                return "ERROR_DISPLAY_ID_MISMATCH";
            case ResultCode.ERROR_INVALID_DISPLAY_ID:
                return "ERROR_INVALID_DISPLAY_ID";
            case ResultCode.SUCCESS_WITH_ACCESSIBILITY_SESSION:
                return "SUCCESS_WITH_ACCESSIBILITY_SESSION";
            default:
                return "Unknown(" + result + ")";
        }
    }

    private static InputBindResult error(@ResultCode int result) {
        return new InputBindResult(result, null, null, null, null, -1, false);
    }

    /**
     * Predefined error object for {@link ResultCode#ERROR_NULL}.
     */
    public static final InputBindResult NULL = error(ResultCode.ERROR_NULL);
    /**
     * Predefined error object for {@link ResultCode#NO_IME}.
     */
    public static final InputBindResult NO_IME = error(ResultCode.ERROR_NO_IME);
    /**
     * Predefined error object for {@link ResultCode#NO_EDITOR}.
     */
    public static final InputBindResult NO_EDITOR = error(ResultCode.ERROR_NO_EDITOR);
    /**
     * Predefined error object for {@link ResultCode#ERROR_INVALID_PACKAGE_NAME}.
     */
    public static final InputBindResult INVALID_PACKAGE_NAME =
            error(ResultCode.ERROR_INVALID_PACKAGE_NAME);
    /**
     * Predefined error object for {@link ResultCode#ERROR_NULL_EDITOR_INFO}.
     */
    public static final InputBindResult NULL_EDITOR_INFO = error(ResultCode.ERROR_NULL_EDITOR_INFO);
    /**
     * Predefined error object for {@link ResultCode#ERROR_NOT_IME_TARGET_WINDOW}.
     */
    public static final InputBindResult NOT_IME_TARGET_WINDOW =
            error(ResultCode.ERROR_NOT_IME_TARGET_WINDOW);
    /**
     * Predefined error object for {@link ResultCode#ERROR_IME_NOT_CONNECTED}.
     */
    public static final InputBindResult IME_NOT_CONNECTED =
            error(ResultCode.ERROR_IME_NOT_CONNECTED);
    /**
     * Predefined error object for {@link ResultCode#ERROR_INVALID_USER}.
     */
    public static final InputBindResult INVALID_USER = error(ResultCode.ERROR_INVALID_USER);

    /**
     * Predefined error object for {@link ResultCode#ERROR_DISPLAY_ID_MISMATCH}.
     */
    public static final InputBindResult DISPLAY_ID_MISMATCH =
            error(ResultCode.ERROR_DISPLAY_ID_MISMATCH);

    /**
     * Predefined error object for {@link ResultCode#ERROR_INVALID_DISPLAY_ID}.
     */
    public static final InputBindResult INVALID_DISPLAY_ID =
            error(ResultCode.ERROR_INVALID_DISPLAY_ID);

    /**
     * Predefined <strong>success</strong> object for
     * {@link ResultCode#SUCCESS_WAITING_USER_SWITCHING}.
     */
    public static final InputBindResult USER_SWITCHING =
            error(ResultCode.SUCCESS_WAITING_USER_SWITCHING);
}
