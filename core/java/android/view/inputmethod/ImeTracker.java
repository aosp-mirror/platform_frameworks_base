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
import android.os.SystemProperties;
import android.util.Log;

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

    /**
     * The origin of the IME request
     *
     * The name follows the format {@code PHASE_x_...} where {@code x} denotes
     * where the origin is (i.e. {@code PHASE_SERVER_...} occurs in the server).
     */
    @IntDef(prefix = { "ORIGIN_" }, value = {
            ORIGIN_CLIENT_SHOW_SOFT_INPUT,
            ORIGIN_CLIENT_HIDE_SOFT_INPUT,
            ORIGIN_SERVER_START_INPUT,
            ORIGIN_SERVER_HIDE_INPUT
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface Origin {}

    /**
     * The IME show request originated in the client.
     */
    int ORIGIN_CLIENT_SHOW_SOFT_INPUT = 0;

    /**
     * The IME hide request originated in the client.
     */
    int ORIGIN_CLIENT_HIDE_SOFT_INPUT = 1;

    /**
     * The IME show request originated in the server.
     */
    int ORIGIN_SERVER_START_INPUT = 2;

    /**
     * The IME hide request originated in the server.
     */
    int ORIGIN_SERVER_HIDE_INPUT = 3;

    /**
     * The current phase of the IME request.
     *
     * The name follows the format {@code PHASE_x_...} where {@code x} denotes
     * where the phase is (i.e. {@code PHASE_SERVER_...} occurs in the server).
     */
    @IntDef(prefix = { "PHASE_" }, value = {
            PHASE_CLIENT_VIEW_SERVED,
            PHASE_SERVER_CLIENT_KNOWN,
            PHASE_SERVER_CLIENT_FOCUSED,
            PHASE_SERVER_ACCESSIBILITY,
            PHASE_SERVER_SYSTEM_READY,
            PHASE_SERVER_HIDE_IMPLICIT,
            PHASE_SERVER_HIDE_NOT_ALWAYS,
            PHASE_SERVER_WAIT_IME,
            PHASE_SERVER_HAS_IME,
            PHASE_SERVER_SHOULD_HIDE,
            PHASE_IME_WRAPPER,
            PHASE_IME_WRAPPER_DISPATCH,
            PHASE_IME_SHOW_SOFT_INPUT,
            PHASE_IME_HIDE_SOFT_INPUT,
            PHASE_IME_ON_SHOW_SOFT_INPUT_TRUE,
            PHASE_IME_APPLY_VISIBILITY_INSETS_CONSUMER,
            PHASE_SERVER_APPLY_IME_VISIBILITY,
            PHASE_WM_SHOW_IME_RUNNER,
            PHASE_WM_SHOW_IME_READY,
            PHASE_WM_HAS_IME_INSETS_CONTROL_TARGET,
            PHASE_WM_WINDOW_INSETS_CONTROL_TARGET_SHOW_INSETS,
            PHASE_WM_WINDOW_INSETS_CONTROL_TARGET_HIDE_INSETS,
            PHASE_WM_REMOTE_INSETS_CONTROL_TARGET_SHOW_INSETS,
            PHASE_WM_REMOTE_INSETS_CONTROL_TARGET_HIDE_INSETS,
            PHASE_WM_REMOTE_INSETS_CONTROLLER,
            PHASE_WM_ANIMATION_CREATE,
            PHASE_WM_ANIMATION_RUNNING,
            PHASE_CLIENT_SHOW_INSETS,
            PHASE_CLIENT_HIDE_INSETS,
            PHASE_CLIENT_HANDLE_SHOW_INSETS,
            PHASE_CLIENT_HANDLE_HIDE_INSETS,
            PHASE_CLIENT_APPLY_ANIMATION,
            PHASE_CLIENT_CONTROL_ANIMATION,
            PHASE_CLIENT_ANIMATION_RUNNING,
            PHASE_CLIENT_ANIMATION_CANCEL,
            PHASE_CLIENT_ANIMATION_FINISHED_SHOW,
            PHASE_CLIENT_ANIMATION_FINISHED_HIDE
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface Phase {}

    /** The view that requested the IME has been served by the IMM. */
    int PHASE_CLIENT_VIEW_SERVED = 0;

    /** The IME client that requested the IME has window manager focus. */
    int PHASE_SERVER_CLIENT_KNOWN = 1;

    /** The IME client that requested the IME has IME focus. */
    int PHASE_SERVER_CLIENT_FOCUSED = 2;

    /** The IME request complies with the current accessibility settings. */
    int PHASE_SERVER_ACCESSIBILITY = 3;

    /** The server is ready to run third party code. */
    int PHASE_SERVER_SYSTEM_READY = 4;

    /** Checked the implicit hide request against any explicit show requests. */
    int PHASE_SERVER_HIDE_IMPLICIT = 5;

    /** Checked the not-always hide request against any forced show requests. */
    int PHASE_SERVER_HIDE_NOT_ALWAYS = 6;

    /** The server is waiting for a connection to the IME. */
    int PHASE_SERVER_WAIT_IME = 7;

    /** The server has a connection to the IME. */
    int PHASE_SERVER_HAS_IME = 8;

    /** The server decided the IME should be hidden. */
    int PHASE_SERVER_SHOULD_HIDE = 9;

    /** Reached the IME wrapper. */
    int PHASE_IME_WRAPPER = 10;

    /** Dispatched from the IME wrapper to the IME. */
    int PHASE_IME_WRAPPER_DISPATCH = 11;

    /** Reached the IME' showSoftInput method. */
    int PHASE_IME_SHOW_SOFT_INPUT = 12;

    /** Reached the IME' hideSoftInput method. */
    int PHASE_IME_HIDE_SOFT_INPUT = 13;

    /** The server decided the IME should be shown. */
    int PHASE_IME_ON_SHOW_SOFT_INPUT_TRUE = 14;

    /** Requested applying the IME visibility in the insets source consumer. */
    int PHASE_IME_APPLY_VISIBILITY_INSETS_CONSUMER = 15;

    /** Applied the IME visibility. */
    int PHASE_SERVER_APPLY_IME_VISIBILITY = 16;

    /** Created the show IME runner. */
    int PHASE_WM_SHOW_IME_RUNNER = 17;

    /** Ready to show IME. */
    int PHASE_WM_SHOW_IME_READY = 18;

    /** The Window Manager has a connection to the IME insets control target. */
    int PHASE_WM_HAS_IME_INSETS_CONTROL_TARGET = 19;

    /** Reached the window insets control target's show insets method. */
    int PHASE_WM_WINDOW_INSETS_CONTROL_TARGET_SHOW_INSETS = 20;

    /** Reached the window insets control target's hide insets method. */
    int PHASE_WM_WINDOW_INSETS_CONTROL_TARGET_HIDE_INSETS = 21;

    /** Reached the remote insets control target's show insets method. */
    int PHASE_WM_REMOTE_INSETS_CONTROL_TARGET_SHOW_INSETS = 22;

    /** Reached the remote insets control target's hide insets method. */
    int PHASE_WM_REMOTE_INSETS_CONTROL_TARGET_HIDE_INSETS = 23;

    /** Reached the remote insets controller. */
    int PHASE_WM_REMOTE_INSETS_CONTROLLER = 24;

    /** Created the IME window insets show animation. */
    int PHASE_WM_ANIMATION_CREATE = 25;

    /** Started the IME window insets show animation. */
    int PHASE_WM_ANIMATION_RUNNING = 26;

    /** Reached the client's show insets method. */
    int PHASE_CLIENT_SHOW_INSETS = 27;

    /** Reached the client's hide insets method. */
    int PHASE_CLIENT_HIDE_INSETS = 28;

    /** Handling the IME window insets show request. */
    int PHASE_CLIENT_HANDLE_SHOW_INSETS = 29;

    /** Handling the IME window insets hide request. */
    int PHASE_CLIENT_HANDLE_HIDE_INSETS = 30;

    /** Applied the IME window insets show animation. */
    int PHASE_CLIENT_APPLY_ANIMATION = 31;

    /** Started the IME window insets show animation. */
    int PHASE_CLIENT_CONTROL_ANIMATION = 32;

    /** Queued the IME window insets show animation. */
    int PHASE_CLIENT_ANIMATION_RUNNING = 33;

    /** Cancelled the IME window insets show animation. */
    int PHASE_CLIENT_ANIMATION_CANCEL = 34;

    /** Finished the IME window insets show animation. */
    int PHASE_CLIENT_ANIMATION_FINISHED_SHOW = 35;

    /** Finished the IME window insets hide animation. */
    int PHASE_CLIENT_ANIMATION_FINISHED_HIDE = 36;

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
     * Called when an IME hide request is created.
     *
     * @param token the token tracking the current IME hide request or {@code null} otherwise.
     * @param origin the origin of the IME hide request.
     * @param reason the reason why the IME hide request was created.
     */
    void onRequestHide(@Nullable Token token, @Origin int origin,
            @SoftInputShowHideReason int reason);

    /**
     * Called when an IME request progresses to a further phase.
     *
     * @param token the token tracking the current IME request or {@code null} otherwise.
     * @param phase the new phase the IME request reached.
     */
    void onProgress(@Nullable Token token, @Phase int phase);

    /**
     * Called when an IME request fails.
     *
     * @param token the token tracking the current IME request or {@code null} otherwise.
     * @param phase the phase the IME request failed at.
     */
    void onFailed(@Nullable Token token, @Phase int phase);

    /**
     * Called when an IME request reached a flow that is not yet implemented.
     *
     * @param token the token tracking the current IME request or {@code null} otherwise.
     * @param phase the phase the IME request was currently at.
     */
    void onTodo(@Nullable Token token, @Phase int phase);

    /**
     * Called when an IME request is cancelled.
     *
     * @param token the token tracking the current IME request or {@code null} otherwise.
     * @param phase the phase the IME request was cancelled at.
     */
    void onCancelled(@Nullable Token token, @Phase int phase);

    /**
     * Called when the IME show request is successful.
     *
     * @param token the token tracking the current IME show request or {@code null} otherwise.
     */
    void onShown(@Nullable Token token);

    /**
     * Called when the IME hide request is successful.
     *
     * @param token the token tracking the current IME hide request or {@code null} otherwise.
     */
    void onHidden(@Nullable Token token);

    /**
     * Get the singleton instance of this class.
     *
     * @return the singleton instance of this class
     */
    @NonNull
    static ImeTracker get() {
        return SystemProperties.getBoolean("persist.debug.imetracker", false)
                ? LOGGER
                : NOOP_LOGGER;
    }

    /** The singleton IME tracker instance. */
    ImeTracker LOGGER = new ImeTracker() {

        @Override
        public void onRequestShow(@Nullable Token token, int origin,
                @SoftInputShowHideReason int reason) {
            if (token == null) return;
            Log.i(TAG, token.mTag + ": onRequestShow at " + originToString(origin)
                    + " reason " + InputMethodDebug.softInputDisplayReasonToString(reason));
        }

        @Override
        public void onRequestHide(@Nullable Token token, int origin,
                @SoftInputShowHideReason int reason) {
            if (token == null) return;
            Log.i(TAG, token.mTag + ": onRequestHide at " + originToString(origin)
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

        @Override
        public void onHidden(@Nullable Token token) {
            if (token == null) return;
            Log.i(TAG, token.mTag + ": onHidden");
        }
    };

    /** The singleton no-op IME tracker instance. */
    ImeTracker NOOP_LOGGER = new ImeTracker() {

        @Override
        public void onRequestShow(@Nullable Token token, int origin,
                @SoftInputShowHideReason int reason) {}

        @Override
        public void onRequestHide(@Nullable Token token, int origin,
                @SoftInputShowHideReason int reason) {}

        @Override
        public void onProgress(@Nullable Token token, int phase) {}

        @Override
        public void onFailed(@Nullable Token token, int phase) {}

        @Override
        public void onTodo(@Nullable Token token, int phase) {}

        @Override
        public void onCancelled(@Nullable Token token, int phase) {}

        @Override
        public void onShown(@Nullable Token token) {}

        @Override
        public void onHidden(@Nullable Token token) {}
    };

    /** A token that tracks the progress of an IME request. */
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

        /** Returns the {@link Token#mTag} */
        @NonNull
        public String getTag() {
            return mTag;
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
