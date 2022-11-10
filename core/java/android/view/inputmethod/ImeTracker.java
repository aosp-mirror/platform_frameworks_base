/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.view.inputmethod;

import static android.view.inputmethod.ImeTracker.Debug.originToString;
import static android.view.inputmethod.ImeTracker.Debug.phaseToString;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.ResultReceiver;
import android.util.Log;
import android.view.View;

import com.android.internal.inputmethod.InputMethodDebug;
import com.android.internal.inputmethod.SoftInputShowHideReason;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

/** @hide */
public interface ImeTracker {

    String TAG = "ImeTracker";

    /** The origin of the IME show request. */
    @IntDef(prefix = { "ORIGIN_" }, value = {
            ORIGIN_SHOW_SOFT_INPUT,
            ORIGIN_START_INPUT
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface Origin {}

    /**
     * The IME is already started.
     *
     * @see InputMethodManager#showSoftInput(View, int, ResultReceiver)
     */
    int ORIGIN_SHOW_SOFT_INPUT = 0;

    /**
     * The IME must be started.
     *
     * @see com.android.server.inputmethod.InputMethodManagerService#showCurrentInputImplicitLocked
     */
    int ORIGIN_START_INPUT = 1;

    /**
     * The current phase of the IME show request.
     *
     * The name follows the format {@code PHASE_x_...} where {@code x} denotes
     * where the phase occurs (i.e. {@code PHASE_WM_...} takes place in the WindowManager).
     */
    @IntDef(prefix = { "PHASE_" }, value = {
            PHASE_CLIENT_VIEW_SERVED,
            PHASE_SERVER_CLIENT_KNOWN,
            PHASE_SERVER_CLIENT_FOCUSED,
            PHASE_SERVER_ACCESSIBILITY,
            PHASE_SERVER_SYSTEM_READY,
            PHASE_SERVER_HAS_IME,
            PHASE_IME_WRAPPER,
            PHASE_IME_WRAPPER_DISPATCH,
            PHASE_IME_SHOW_SOFT_INPUT,
            PHASE_IME_ON_SHOW_SOFT_INPUT_TRUE,
            PHASE_IME_APPLY_VISIBILITY_INSETS_CONSUMER,
            PHASE_SERVER_APPLY_IME_VISIBILITY,
            PHASE_WM_SHOW_IME_RUNNER,
            PHASE_WM_SHOW_IME_READY,
            PHASE_WM_REMOTE_INSETS_CONTROL_TARGET,
            PHASE_WM_REMOTE_INSETS_CONTROLLER,
            PHASE_WM_ANIMATION_CREATE,
            PHASE_WM_ANIMATION_RUNNING,
            PHASE_CLIENT_SHOW_INSETS,
            PHASE_CLIENT_HANDLE_SHOW_INSETS,
            PHASE_CLIENT_APPLY_ANIMATION,
            PHASE_CLIENT_CONTROL_ANIMATION,
            PHASE_CLIENT_ANIMATION_RUNNING,
            PHASE_CLIENT_ANIMATION_CANCEL,
            PHASE_CLIENT_ANIMATION_FINISHED_SHOW
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface Phase {}

    /** The view that requested the IME has been served by the IMM. */
    int PHASE_CLIENT_VIEW_SERVED = 1;

    /** The IME client that requested the IME has window manager focus. */
    int PHASE_SERVER_CLIENT_KNOWN = 2;

    /** The IME client that requested the IME has IME focus. */
    int PHASE_SERVER_CLIENT_FOCUSED = 3;

    /** The IME request complies with the current accessibility settings. */
    int PHASE_SERVER_ACCESSIBILITY = 4;

    /** The server is ready to run third party code. */
    int PHASE_SERVER_SYSTEM_READY = 5;

    /** The server is waiting for a connection to the IME. */
    int PHASE_SERVER_WAIT_IME = 6;

    /** The server has a connection to the IME. */
    int PHASE_SERVER_HAS_IME = 7;

    /** Reached the IME wrapper. */
    int PHASE_IME_WRAPPER = 8;

    /** Dispatched from the IME wrapper to the IME. */
    int PHASE_IME_WRAPPER_DISPATCH = 9;

    /** Reached the IME. */
    int PHASE_IME_SHOW_SOFT_INPUT = 10;

    /** The server decided the IME should be shown. */
    int PHASE_IME_ON_SHOW_SOFT_INPUT_TRUE = 11;

    /** Requested applying the IME visibility in the insets source consumer. */
    int PHASE_IME_APPLY_VISIBILITY_INSETS_CONSUMER = 12;

    /** Applied the IME visibility. */
    int PHASE_SERVER_APPLY_IME_VISIBILITY = 13;

    /** Created the show IME runner. */
    int PHASE_WM_SHOW_IME_RUNNER = 14;

    /** Ready to show IME. */
    int PHASE_WM_SHOW_IME_READY = 15;

    /** Reached the remote insets control target. */
    int PHASE_WM_REMOTE_INSETS_CONTROL_TARGET = 16;

    /** Reached the remote insets controller. */
    int PHASE_WM_REMOTE_INSETS_CONTROLLER = 17;

    /** Created the IME window insets show animation. */
    int PHASE_WM_ANIMATION_CREATE = 18;

    /** Started the IME window insets show animation. */
    int PHASE_WM_ANIMATION_RUNNING = 19;

    /** Reached the client. */
    int PHASE_CLIENT_SHOW_INSETS = 20;

    /** Handled the IME window insets show request. */
    int PHASE_CLIENT_HANDLE_SHOW_INSETS = 21;

    /** Applied the IME window insets show animation. */
    int PHASE_CLIENT_APPLY_ANIMATION = 22;

    /** Started the IME window insets show animation. */
    int PHASE_CLIENT_CONTROL_ANIMATION = 23;

    /** Queued the IME window insets show animation. */
    int PHASE_CLIENT_ANIMATION_RUNNING = 24;

    /** Cancelled the IME window insets show animation. */
    int PHASE_CLIENT_ANIMATION_CANCEL = 25;

    /** Finished the IME window insets show animation. */
    int PHASE_CLIENT_ANIMATION_FINISHED_SHOW = 26;

    /**
     * Called when an IME show request is created.
     *
     * @param token the token tracking the current IME show request or {@code null} otherwise.
     * @param origin the origin of the IME show request.
     * @param reason the reason why the IME show request was created.
     */
    void onRequestShow(@Nullable Token token, @Origin int origin,
            @SoftInputShowHideReason int reason);

    /**
     * Called when an IME show request progresses to a further phase.
     *
     * @param token the token tracking the current IME show request or {@code null} otherwise.
     * @param phase the new phase the IME show request reached.
     */
    void onProgress(@Nullable Token token, @Phase int phase);

    /**
     * Called when an IME show request failed.
     *
     * @param token the token tracking the current IME show request or {@code null} otherwise.
     * @param phase the phase the IME show request failed at.
     */
    void onFailed(@Nullable Token token, @Phase int phase);

    /**
     * Called when an IME show request reached a flow that is not yet implemented.
     *
     * @param token the token tracking the current IME show request or {@code null} otherwise.
     * @param phase the phase the IME show request was currently at.
     */
    void onTodo(@Nullable Token token, @Phase int phase);

    /**
     * Called when an IME show request is cancelled.
     *
     * @param token the token tracking the current IME show request or {@code null} otherwise.
     * @param phase the phase the IME show request was cancelled at.
     */
    void onCancelled(@Nullable Token token, @Phase int phase);

    /**
     * Called when the IME show request is successful.
     *
     * @param token the token tracking the current IME show request or {@code null} otherwise.
     */
    void onShown(@Nullable Token token);

    /**
     * Get the singleton instance of this class.
     *
     * @return the singleton instance of this class
     */
    @NonNull
    static ImeTracker get() {
        return LOGGER;
    }

    /** The singleton IME tracker instance. */
    ImeTracker LOGGER = new ImeTracker() {

        @Override
        public void onRequestShow(@Nullable Token token, int origin, int reason) {
            if (token == null) return;
            Log.i(TAG, token.mTag + ": onRequestShow at " + originToString(origin)
                    + " reason " + InputMethodDebug.softInputDisplayReasonToString(reason));
        }

        @Override
        public void onProgress(@Nullable Token token, int phase) {
            if (token == null) return;
            Log.i(TAG, token.mTag + ": onProgress at " + phaseToString(phase));
        }

        @Override
        public void onFailed(@Nullable Token token, int phase) {
            if (token == null) return;
            Log.i(TAG, token.mTag + ": onFailed at " + phaseToString(phase));
        }

        @Override
        public void onTodo(@Nullable Token token, int phase) {
            if (token == null) return;
            Log.i(TAG, token.mTag + ": onTodo at " + phaseToString(phase));
        }

        @Override
        public void onCancelled(@Nullable Token token, int phase) {
            if (token == null) return;
            Log.i(TAG, token.mTag + ": onCancelled at " + phaseToString(phase));
        }

        @Override
        public void onShown(@Nullable Token token) {
            if (token == null) return;
            Log.i(TAG, token.mTag + ": onShown");
        }
    };

    /** A token that tracks the progress of an IME show request. */
    class Token implements Parcelable {

        private final IBinder mBinder;
        private final String mTag;

        public Token() {
            this(ActivityThread.currentProcessName());
        }

        public Token(String component) {
            this(new Binder(), component + ":" + Integer.toHexString((new Random().nextInt())));
        }

        private Token(IBinder binder, String tag) {
            mBinder = binder;
            mTag = tag;
        }

        /** For Parcelable, no special marshalled objects. */
        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeStrongBinder(mBinder);
            dest.writeString8(mTag);
        }

        @NonNull
        public static final Creator<Token> CREATOR = new Creator<>() {
            @Override
            public Token createFromParcel(Parcel source) {
                IBinder binder = source.readStrongBinder();
                String tag = source.readString8();
                return new Token(binder, tag);
            }

            @Override
            public Token[] newArray(int size) {
                return new Token[size];
            }
        };
    }

    /**
     * Utilities for mapping phases and origins IntDef values to their names.
     *
     * Note: This is held in a separate class so that it only gets initialized when actually needed.
     */
    class Debug {

        private static final Map<Integer, String> sOrigins =
                getFieldMapping(ImeTracker.class, "ORIGIN_");
        private static final Map<Integer, String> sPhases =
                getFieldMapping(ImeTracker.class, "PHASE_");

        public static String originToString(int origin) {
            return sOrigins.getOrDefault(origin, "ORIGIN_" + origin);
        }

        public static String phaseToString(int phase) {
            return sPhases.getOrDefault(phase, "PHASE_" + phase);
        }

        private static Map<Integer, String> getFieldMapping(Class<?> cls, String fieldPrefix) {
            return Arrays.stream(cls.getDeclaredFields())
                    .filter(field -> field.getName().startsWith(fieldPrefix))
                    .collect(Collectors.toMap(Debug::getFieldValue, Field::getName));
        }

        private static int getFieldValue(Field field) {
            try {
                return field.getInt(null);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
