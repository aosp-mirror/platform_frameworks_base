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

import static android.view.InsetsController.ANIMATION_TYPE_HIDE;
import static android.view.InsetsController.ANIMATION_TYPE_SHOW;

import static com.android.internal.inputmethod.InputMethodDebug.softInputDisplayReasonToString;
import static com.android.internal.jank.Cuj.CUJ_IME_INSETS_HIDE_ANIMATION;
import static com.android.internal.jank.Cuj.CUJ_IME_INSETS_SHOW_ANIMATION;
import static com.android.internal.util.LatencyTracker.ACTION_REQUEST_IME_HIDDEN;
import static com.android.internal.util.LatencyTracker.ACTION_REQUEST_IME_SHOWN;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread;
import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemProperties;
import android.util.Log;
import android.view.InsetsController.AnimationType;
import android.view.SurfaceControl;
import android.view.View;

import com.android.internal.inputmethod.InputMethodDebug;
import com.android.internal.inputmethod.SoftInputShowHideReason;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.jank.InteractionJankMonitor.Configuration;
import com.android.internal.util.LatencyTracker;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/** @hide */
public interface ImeTracker {

    String TAG = "ImeTracker";

    /** The debug flag for IME visibility event log. */
    boolean DEBUG_IME_VISIBILITY = SystemProperties.getBoolean("persist.debug.imf_event", false);

    /** The message to indicate if there is no valid {@link Token}. */
    String TOKEN_NONE = "TOKEN_NONE";

    /** The type of the IME request. */
    @IntDef(prefix = { "TYPE_" }, value = {
            TYPE_SHOW,
            TYPE_HIDE
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface Type {}

    /** IME show request type. */
    int TYPE_SHOW = ImeProtoEnums.TYPE_SHOW;

    /** IME hide request type. */
    int TYPE_HIDE = ImeProtoEnums.TYPE_HIDE;

    /** The status of the IME request. */
    @IntDef(prefix = { "STATUS_" }, value = {
            STATUS_RUN,
            STATUS_CANCEL,
            STATUS_FAIL,
            STATUS_SUCCESS,
            STATUS_TIMEOUT
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface Status {}

    /** IME request running. */
    int STATUS_RUN = ImeProtoEnums.STATUS_RUN;

    /** IME request cancelled. */
    int STATUS_CANCEL = ImeProtoEnums.STATUS_CANCEL;

    /** IME request failed. */
    int STATUS_FAIL = ImeProtoEnums.STATUS_FAIL;

    /** IME request succeeded. */
    int STATUS_SUCCESS = ImeProtoEnums.STATUS_SUCCESS;

    /** IME request timed out. */
    int STATUS_TIMEOUT = ImeProtoEnums.STATUS_TIMEOUT;

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

    /** The IME show request originated in the client. */
    int ORIGIN_CLIENT_SHOW_SOFT_INPUT = ImeProtoEnums.ORIGIN_CLIENT_SHOW_SOFT_INPUT;

    /** The IME hide request originated in the client. */
    int ORIGIN_CLIENT_HIDE_SOFT_INPUT = ImeProtoEnums.ORIGIN_CLIENT_HIDE_SOFT_INPUT;

    /** The IME show request originated in the server. */
    int ORIGIN_SERVER_START_INPUT = ImeProtoEnums.ORIGIN_SERVER_START_INPUT;

    /** The IME hide request originated in the server. */
    int ORIGIN_SERVER_HIDE_INPUT = ImeProtoEnums.ORIGIN_SERVER_HIDE_INPUT;

    /**
     * The current phase of the IME request.
     *
     * The name follows the format {@code PHASE_x_...} where {@code x} denotes
     * where the phase is (i.e. {@code PHASE_SERVER_...} occurs in the server).
     */
    @IntDef(prefix = { "PHASE_" }, value = {
            PHASE_NOT_SET,
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
            PHASE_CLIENT_COLLECT_SOURCE_CONTROLS,
            PHASE_CLIENT_INSETS_CONSUMER_REQUEST_SHOW,
            PHASE_CLIENT_REQUEST_IME_SHOW,
            PHASE_CLIENT_INSETS_CONSUMER_NOTIFY_HIDDEN,
            PHASE_CLIENT_ANIMATION_RUNNING,
            PHASE_CLIENT_ANIMATION_CANCEL,
            PHASE_CLIENT_ANIMATION_FINISHED_SHOW,
            PHASE_CLIENT_ANIMATION_FINISHED_HIDE
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface Phase {}

    int PHASE_NOT_SET = ImeProtoEnums.PHASE_NOT_SET;

    /** The view that requested the IME has been served by the IMM. */
    int PHASE_CLIENT_VIEW_SERVED = ImeProtoEnums.PHASE_CLIENT_VIEW_SERVED;

    /** The IME client that requested the IME has window manager focus. */
    int PHASE_SERVER_CLIENT_KNOWN = ImeProtoEnums.PHASE_SERVER_CLIENT_KNOWN;

    /** The IME client that requested the IME has IME focus. */
    int PHASE_SERVER_CLIENT_FOCUSED = ImeProtoEnums.PHASE_SERVER_CLIENT_FOCUSED;

    /** The IME request complies with the current accessibility settings. */
    int PHASE_SERVER_ACCESSIBILITY = ImeProtoEnums.PHASE_SERVER_ACCESSIBILITY;

    /** The server is ready to run third party code. */
    int PHASE_SERVER_SYSTEM_READY = ImeProtoEnums.PHASE_SERVER_SYSTEM_READY;

    /** Checked the implicit hide request against any explicit show requests. */
    int PHASE_SERVER_HIDE_IMPLICIT = ImeProtoEnums.PHASE_SERVER_HIDE_IMPLICIT;

    /** Checked the not-always hide request against any forced show requests. */
    int PHASE_SERVER_HIDE_NOT_ALWAYS = ImeProtoEnums.PHASE_SERVER_HIDE_NOT_ALWAYS;

    /** The server is waiting for a connection to the IME. */
    int PHASE_SERVER_WAIT_IME = ImeProtoEnums.PHASE_SERVER_WAIT_IME;

    /** The server has a connection to the IME. */
    int PHASE_SERVER_HAS_IME = ImeProtoEnums.PHASE_SERVER_HAS_IME;

    /** The server decided the IME should be hidden. */
    int PHASE_SERVER_SHOULD_HIDE = ImeProtoEnums.PHASE_SERVER_SHOULD_HIDE;

    /** Reached the IME wrapper. */
    int PHASE_IME_WRAPPER = ImeProtoEnums.PHASE_IME_WRAPPER;

    /** Dispatched from the IME wrapper to the IME. */
    int PHASE_IME_WRAPPER_DISPATCH = ImeProtoEnums.PHASE_IME_WRAPPER_DISPATCH;

    /** Reached the IME' showSoftInput method. */
    int PHASE_IME_SHOW_SOFT_INPUT = ImeProtoEnums.PHASE_IME_SHOW_SOFT_INPUT;

    /** Reached the IME' hideSoftInput method. */
    int PHASE_IME_HIDE_SOFT_INPUT = ImeProtoEnums.PHASE_IME_HIDE_SOFT_INPUT;

    /** The server decided the IME should be shown. */
    int PHASE_IME_ON_SHOW_SOFT_INPUT_TRUE = ImeProtoEnums.PHASE_IME_ON_SHOW_SOFT_INPUT_TRUE;

    /** Requested applying the IME visibility in the insets source consumer. */
    int PHASE_IME_APPLY_VISIBILITY_INSETS_CONSUMER =
            ImeProtoEnums.PHASE_IME_APPLY_VISIBILITY_INSETS_CONSUMER;

    /** Applied the IME visibility. */
    int PHASE_SERVER_APPLY_IME_VISIBILITY = ImeProtoEnums.PHASE_SERVER_APPLY_IME_VISIBILITY;

    /** Created the show IME runner. */
    int PHASE_WM_SHOW_IME_RUNNER = ImeProtoEnums.PHASE_WM_SHOW_IME_RUNNER;

    /** Ready to show IME. */
    int PHASE_WM_SHOW_IME_READY = ImeProtoEnums.PHASE_WM_SHOW_IME_READY;

    /** The Window Manager has a connection to the IME insets control target. */
    int PHASE_WM_HAS_IME_INSETS_CONTROL_TARGET =
            ImeProtoEnums.PHASE_WM_HAS_IME_INSETS_CONTROL_TARGET;

    /** Reached the window insets control target's show insets method. */
    int PHASE_WM_WINDOW_INSETS_CONTROL_TARGET_SHOW_INSETS =
            ImeProtoEnums.PHASE_WM_WINDOW_INSETS_CONTROL_TARGET_SHOW_INSETS;

    /** Reached the window insets control target's hide insets method. */
    int PHASE_WM_WINDOW_INSETS_CONTROL_TARGET_HIDE_INSETS =
            ImeProtoEnums.PHASE_WM_WINDOW_INSETS_CONTROL_TARGET_HIDE_INSETS;

    /** Reached the remote insets control target's show insets method. */
    int PHASE_WM_REMOTE_INSETS_CONTROL_TARGET_SHOW_INSETS =
            ImeProtoEnums.PHASE_WM_REMOTE_INSETS_CONTROL_TARGET_SHOW_INSETS;

    /** Reached the remote insets control target's hide insets method. */
    int PHASE_WM_REMOTE_INSETS_CONTROL_TARGET_HIDE_INSETS =
            ImeProtoEnums.PHASE_WM_REMOTE_INSETS_CONTROL_TARGET_HIDE_INSETS;

    /** Reached the remote insets controller. */
    int PHASE_WM_REMOTE_INSETS_CONTROLLER = ImeProtoEnums.PHASE_WM_REMOTE_INSETS_CONTROLLER;

    /** Created the IME window insets show animation. */
    int PHASE_WM_ANIMATION_CREATE = ImeProtoEnums.PHASE_WM_ANIMATION_CREATE;

    /** Started the IME window insets show animation. */
    int PHASE_WM_ANIMATION_RUNNING = ImeProtoEnums.PHASE_WM_ANIMATION_RUNNING;

    /** Reached the client's show insets method. */
    int PHASE_CLIENT_SHOW_INSETS = ImeProtoEnums.PHASE_CLIENT_SHOW_INSETS;

    /** Reached the client's hide insets method. */
    int PHASE_CLIENT_HIDE_INSETS = ImeProtoEnums.PHASE_CLIENT_HIDE_INSETS;

    /** Handling the IME window insets show request. */
    int PHASE_CLIENT_HANDLE_SHOW_INSETS = ImeProtoEnums.PHASE_CLIENT_HANDLE_SHOW_INSETS;

    /** Handling the IME window insets hide request. */
    int PHASE_CLIENT_HANDLE_HIDE_INSETS = ImeProtoEnums.PHASE_CLIENT_HANDLE_HIDE_INSETS;

    /** Applied the IME window insets show animation. */
    int PHASE_CLIENT_APPLY_ANIMATION = ImeProtoEnums.PHASE_CLIENT_APPLY_ANIMATION;

    /** Started the IME window insets show animation. */
    int PHASE_CLIENT_CONTROL_ANIMATION = ImeProtoEnums.PHASE_CLIENT_CONTROL_ANIMATION;

    /** Collecting insets source controls. */
    int PHASE_CLIENT_COLLECT_SOURCE_CONTROLS = ImeProtoEnums.PHASE_CLIENT_COLLECT_SOURCE_CONTROLS;

    /** Reached the insets source consumer's show request method. */
    int PHASE_CLIENT_INSETS_CONSUMER_REQUEST_SHOW =
            ImeProtoEnums.PHASE_CLIENT_INSETS_CONSUMER_REQUEST_SHOW;

    /** Reached input method manager's request IME show method. */
    int PHASE_CLIENT_REQUEST_IME_SHOW = ImeProtoEnums.PHASE_CLIENT_REQUEST_IME_SHOW;

    /** Reached the insets source consumer's notify hidden method. */
    int PHASE_CLIENT_INSETS_CONSUMER_NOTIFY_HIDDEN =
            ImeProtoEnums.PHASE_CLIENT_INSETS_CONSUMER_NOTIFY_HIDDEN;

    /** Queued the IME window insets show animation. */
    int PHASE_CLIENT_ANIMATION_RUNNING = ImeProtoEnums.PHASE_CLIENT_ANIMATION_RUNNING;

    /** Cancelled the IME window insets show animation. */
    int PHASE_CLIENT_ANIMATION_CANCEL = ImeProtoEnums.PHASE_CLIENT_ANIMATION_CANCEL;

    /** Finished the IME window insets show animation. */
    int PHASE_CLIENT_ANIMATION_FINISHED_SHOW = ImeProtoEnums.PHASE_CLIENT_ANIMATION_FINISHED_SHOW;

    /** Finished the IME window insets hide animation. */
    int PHASE_CLIENT_ANIMATION_FINISHED_HIDE = ImeProtoEnums.PHASE_CLIENT_ANIMATION_FINISHED_HIDE;

    /**
     * Creates an IME show request tracking token.
     *
     * @param component the name of the component that created the IME request, or {@code null}
     *                  otherwise (defaulting to {@link ActivityThread#currentProcessName()}).
     * @param uid the uid of the client that requested the IME.
     * @param origin the origin of the IME show request.
     * @param reason the reason why the IME show request was created.
     * @param fromUser whether this request was created directly from user interaction.
     *
     * @return An IME tracking token.
     */
    @NonNull
    Token onRequestShow(@Nullable String component, int uid, @Origin int origin,
            @SoftInputShowHideReason int reason, boolean fromUser);

    /**
     * Creates an IME hide request tracking token.
     *
     * @param component the name of the component that created the IME request, or {@code null}
     *                  otherwise (defaulting to {@link ActivityThread#currentProcessName()}).
     * @param uid the uid of the client that requested the IME.
     * @param origin the origin of the IME hide request.
     * @param reason the reason why the IME hide request was created.
     * @param fromUser whether this request was created directly from user interaction.
     *
     * @return An IME tracking token.
     */
    @NonNull
    Token onRequestHide(@Nullable String component, int uid, @Origin int origin,
            @SoftInputShowHideReason int reason, boolean fromUser);

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
     * Returns whether the current IME request was created due to a user interaction. This can
     * only be {@code true} when running on the view's UI thread.
     *
     * @param view the view for which the IME was requested.
     * @return {@code true} if this request is coming from a user interaction,
     * {@code false} otherwise.
     */
    static boolean isFromUser(@Nullable View view) {
        if (view == null) {
            return false;
        }
        final var handler = view.getHandler();
        // Early return if not on the UI thread, to ensure safe access to getViewRootImpl() below.
        if (handler == null || handler.getLooper() == null
                || !handler.getLooper().isCurrentThread()) {
            return false;
        }
        final var viewRootImpl = view.getViewRootImpl();
        return viewRootImpl != null && viewRootImpl.isHandlingPointerEvent();
    }

    /**
     * Get the singleton request tracker instance.
     *
     * @return the singleton request tracker instance
     */
    @NonNull
    static ImeTracker forLogging() {
        return LOGGER;
    }

    /**
     * Get the singleton jank tracker instance.
     *
     * @return the singleton jank tracker instance
     */
    @NonNull
    static ImeJankTracker forJank() {
        return JANK_TRACKER;
    }

    /**
     * Get the singleton latency tracker instance.
     *
     * @return the singleton latency tracker instance
     */
    @NonNull
    static ImeLatencyTracker forLatency() {
        return LATENCY_TRACKER;
    }

    /** The singleton IME tracker instance. */
    @NonNull
    ImeTracker LOGGER = new ImeTracker() {

        {
            // Read initial system properties.
            reloadSystemProperties();
            // Update when system properties change.
            SystemProperties.addChangeCallback(this::reloadSystemProperties);
        }

        /** Whether {@link #onProgress} calls should be logged. */
        private boolean mLogProgress;

        /** Whether the stack trace at the request call site should be logged. */
        private boolean mLogStackTrace;

        private void reloadSystemProperties() {
            mLogProgress = SystemProperties.getBoolean(
                    "persist.debug.imetracker", false);
            mLogStackTrace = SystemProperties.getBoolean(
                    "persist.debug.imerequest.logstacktrace", false);
        }

        @NonNull
        @Override
        public Token onRequestShow(@Nullable String component, int uid, @Origin int origin,
                @SoftInputShowHideReason int reason, boolean fromUser) {
            final var tag = getTag(component);
            final var token = IInputMethodManagerGlobalInvoker.onRequestShow(tag, uid, origin,
                    reason, fromUser);

            Log.i(TAG, token.mTag + ": onRequestShow at " + Debug.originToString(origin)
                    + " reason " + InputMethodDebug.softInputDisplayReasonToString(reason)
                    + " fromUser " + fromUser,
                    mLogStackTrace ? new Throwable() : null);

            return token;
        }

        @NonNull
        @Override
        public Token onRequestHide(@Nullable String component, int uid, @Origin int origin,
                @SoftInputShowHideReason int reason, boolean fromUser) {
            final var tag = getTag(component);
            final var token = IInputMethodManagerGlobalInvoker.onRequestHide(tag, uid, origin,
                    reason, fromUser);

            Log.i(TAG, token.mTag + ": onRequestHide at " + Debug.originToString(origin)
                    + " reason " + InputMethodDebug.softInputDisplayReasonToString(reason)
                    + " fromUser " + fromUser,
                    mLogStackTrace ? new Throwable() : null);

            return token;
        }

        @Override
        public void onProgress(@Nullable Token token, @Phase int phase) {
            if (token == null) return;
            IInputMethodManagerGlobalInvoker.onProgress(token.mBinder, phase);

            if (mLogProgress) {
                Log.i(TAG, token.mTag + ": onProgress at " + Debug.phaseToString(phase));
            }
        }

        @Override
        public void onFailed(@Nullable Token token, @Phase int phase) {
            if (token == null) return;
            IInputMethodManagerGlobalInvoker.onFailed(token, phase);

            Log.i(TAG, token.mTag + ": onFailed at " + Debug.phaseToString(phase));
        }

        @Override
        public void onTodo(@Nullable Token token, @Phase int phase) {
            if (token == null) return;
            Log.i(TAG, token.mTag + ": onTodo at " + Debug.phaseToString(phase));
        }

        @Override
        public void onCancelled(@Nullable Token token, @Phase int phase) {
            if (token == null) return;
            IInputMethodManagerGlobalInvoker.onCancelled(token, phase);

            Log.i(TAG, token.mTag + ": onCancelled at " + Debug.phaseToString(phase));
        }

        @Override
        public void onShown(@Nullable Token token) {
            if (token == null) return;
            IInputMethodManagerGlobalInvoker.onShown(token);

            Log.i(TAG, token.mTag + ": onShown");
        }

        @Override
        public void onHidden(@Nullable Token token) {
            if (token == null) return;
            IInputMethodManagerGlobalInvoker.onHidden(token);

            Log.i(TAG, token.mTag + ": onHidden");
        }

        /**
         * Returns a logging tag using the given component name.
         *
         * @param component the name of the component that created the IME request, or {@code null}
         *                  otherwise (defaulting to {@link ActivityThread#currentProcessName()}).
         */
        @NonNull
        private String getTag(@Nullable String component) {
            if (component == null) {
                component = ActivityThread.currentProcessName();
            }
            return component + ":" + Integer.toHexString(ThreadLocalRandom.current().nextInt());
        }
    };

    /** The singleton IME tracker instance for instrumenting jank metrics. */
    ImeJankTracker JANK_TRACKER = new ImeJankTracker();

    /** The singleton IME tracker instance for instrumenting latency metrics. */
    ImeLatencyTracker LATENCY_TRACKER = new ImeLatencyTracker();

    /** A token that tracks the progress of an IME request. */
    final class Token implements Parcelable {

        /** The binder used to identify this token. */
        @NonNull
        private final IBinder mBinder;

        /** Logging tag, of the shape "component:random_hexadecimal". */
        @NonNull
        private final String mTag;

        public Token(@NonNull IBinder binder, @NonNull String tag) {
            mBinder = binder;
            mTag = tag;
        }

        private Token(@NonNull Parcel in) {
            mBinder = in.readStrongBinder();
            mTag = in.readString8();
        }

        @NonNull
        public IBinder getBinder() {
            return mBinder;
        }

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
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeStrongBinder(mBinder);
            dest.writeString8(mTag);
        }

        @NonNull
        public static final Creator<Token> CREATOR = new Creator<>() {
            @NonNull
            @Override
            public Token createFromParcel(@NonNull Parcel in) {
                return new Token(in);
            }

            @NonNull
            @Override
            public Token[] newArray(int size) {
                return new Token[size];
            }
        };
    }

    /**
     * Utilities for mapping IntDef values to their names.
     *
     * Note: This is held in a separate class so that it only gets initialized when actually needed.
     */
    final class Debug {

        @NonNull
        private static final Map<Integer, String> sTypes =
                getFieldMapping(ImeTracker.class, "TYPE_");
        @NonNull
        private static final Map<Integer, String> sStatus =
                getFieldMapping(ImeTracker.class, "STATUS_");
        @NonNull
        private static final Map<Integer, String> sOrigins =
                getFieldMapping(ImeTracker.class, "ORIGIN_");
        @NonNull
        private static final Map<Integer, String> sPhases =
                getFieldMapping(ImeTracker.class, "PHASE_");

        @NonNull
        public static String typeToString(@Type int type) {
            return sTypes.getOrDefault(type, "TYPE_" + type);
        }

        @NonNull
        public static String statusToString(@Status int status) {
            return sStatus.getOrDefault(status, "STATUS_" + status);
        }

        @NonNull
        public static String originToString(@Origin int origin) {
            return sOrigins.getOrDefault(origin, "ORIGIN_" + origin);
        }

        @NonNull
        public static String phaseToString(@Phase int phase) {
            return sPhases.getOrDefault(phase, "PHASE_" + phase);
        }

        @NonNull
        private static Map<Integer, String> getFieldMapping(Class<?> cls,
                @NonNull String fieldPrefix) {
            return Arrays.stream(cls.getDeclaredFields())
                    .filter(field -> field.getName().startsWith(fieldPrefix))
                    .collect(Collectors.toMap(Debug::getFieldValue, Field::getName));
        }

        private static int getFieldValue(@NonNull Field field) {
            try {
                return field.getInt(null);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Context related to {@link InteractionJankMonitor}.
     */
    interface InputMethodJankContext {
        /**
         * @return a context associated with a display
         */
        Context getDisplayContext();

        /**
         * @return a SurfaceControl that is going to be monitored
         */
        SurfaceControl getTargetSurfaceControl();

        /**
         * @return the package name of the host
         */
        String getHostPackageName();
    }

    /**
     * Context related to {@link LatencyTracker}.
     */
    interface InputMethodLatencyContext {
        /**
         * @return a context associated with current application
         */
        Context getAppContext();
    }

    /**
     * A tracker instance which is in charge of communicating with {@link InteractionJankMonitor}.
     * This class disallows instantiating from outside, use {@link #forJank()} to get the singleton.
     */
    final class ImeJankTracker {

        /**
         * This class disallows instantiating from outside.
         */
        private ImeJankTracker() {
        }

        /**
         * Called when the animation, which is going to be monitored, starts.
         *
         * @param jankContext context which is needed by {@link InteractionJankMonitor}.
         * @param animType the animation type.
         * @param useSeparatedThread {@code true} if the animation is handled by the app,
         *                           {@code false} if the animation will be scheduled on the
         *                           {@link android.view.InsetsAnimationThread}.
         */
        public void onRequestAnimation(@NonNull InputMethodJankContext jankContext,
                @AnimationType int animType, boolean useSeparatedThread) {
            final int cujType = getImeInsetsCujFromAnimation(animType);
            if (jankContext.getDisplayContext() == null
                    || jankContext.getTargetSurfaceControl() == null
                    || cujType == -1) {
                return;
            }
            final Configuration.Builder builder = Configuration.Builder.withSurface(
                            cujType,
                            jankContext.getDisplayContext(),
                            jankContext.getTargetSurfaceControl())
                    .setTag(String.format(Locale.US, "%d@%d@%s", animType,
                            useSeparatedThread ? 0 : 1, jankContext.getHostPackageName()));
            InteractionJankMonitor.getInstance().begin(builder);
        }

        /**
         * Called when the animation, which is going to be monitored, cancels.
         *
         * @param animType the animation type.
         */
        public void onCancelAnimation(@AnimationType int animType) {
            final int cujType = getImeInsetsCujFromAnimation(animType);
            if (cujType != -1) {
                InteractionJankMonitor.getInstance().cancel(cujType);
            }
        }

        /**
         * Called when the animation, which is going to be monitored, ends.
         *
         * @param animType the animation type.
         */
        public void onFinishAnimation(@AnimationType int animType) {
            final int cujType = getImeInsetsCujFromAnimation(animType);
            if (cujType != -1) {
                InteractionJankMonitor.getInstance().end(cujType);
            }
        }

        /**
         * A helper method to translate animation type to CUJ type for IME animations.
         *
         * @param animType the animation type.
         * @return the integer in {@link com.android.internal.jank.Cuj.CujType},
         * or {@code -1} if the animation type is not supported for tracking yet.
         */
        private static int getImeInsetsCujFromAnimation(@AnimationType int animType) {
            switch (animType) {
                case ANIMATION_TYPE_SHOW:
                    return CUJ_IME_INSETS_SHOW_ANIMATION;
                case ANIMATION_TYPE_HIDE:
                    return CUJ_IME_INSETS_HIDE_ANIMATION;
                default:
                    return -1;
            }
        }
    }

    /**
     * A tracker instance which is in charge of communicating with {@link LatencyTracker}.
     * This class disallows instantiating from outside, use {@link #forLatency()}
     * to get the singleton.
     */
    final class ImeLatencyTracker {

        /**
         * This class disallows instantiating from outside.
         */
        private ImeLatencyTracker() {
        }

        private boolean shouldMonitorLatency(@SoftInputShowHideReason int reason) {
            return reason == SoftInputShowHideReason.SHOW_SOFT_INPUT
                    || reason == SoftInputShowHideReason.HIDE_SOFT_INPUT
                    || reason == SoftInputShowHideReason.HIDE_SOFT_INPUT_FROM_VIEW
                    || reason == SoftInputShowHideReason.SHOW_SOFT_INPUT_BY_INSETS_API
                    || reason == SoftInputShowHideReason.HIDE_SOFT_INPUT_BY_INSETS_API
                    || reason == SoftInputShowHideReason.SHOW_SOFT_INPUT_FROM_IME
                    || reason == SoftInputShowHideReason.HIDE_SOFT_INPUT_FROM_IME;
        }

        public void onRequestShow(@Nullable Token token, @Origin int origin,
                @SoftInputShowHideReason int reason,
                @NonNull InputMethodLatencyContext latencyContext) {
            if (!shouldMonitorLatency(reason)) return;
            LatencyTracker.getInstance(latencyContext.getAppContext())
                    .onActionStart(
                            ACTION_REQUEST_IME_SHOWN,
                            softInputDisplayReasonToString(reason));
        }

        public void onRequestHide(@Nullable Token token, @Origin int origin,
                @SoftInputShowHideReason int reason,
                @NonNull InputMethodLatencyContext latencyContext) {
            if (!shouldMonitorLatency(reason)) return;
            LatencyTracker.getInstance(latencyContext.getAppContext())
                    .onActionStart(
                            ACTION_REQUEST_IME_HIDDEN,
                            softInputDisplayReasonToString(reason));
        }

        public void onShowFailed(@Nullable Token token, @Phase int phase,
                @NonNull InputMethodLatencyContext latencyContext) {
            onShowCancelled(token, phase, latencyContext);
        }

        public void onHideFailed(@Nullable Token token, @Phase int phase,
                @NonNull InputMethodLatencyContext latencyContext) {
            onHideCancelled(token, phase, latencyContext);
        }

        public void onShowCancelled(@Nullable Token token, @Phase int phase,
                @NonNull InputMethodLatencyContext latencyContext) {
            LatencyTracker.getInstance(latencyContext.getAppContext())
                    .onActionCancel(ACTION_REQUEST_IME_SHOWN);
        }

        public void onHideCancelled(@Nullable Token token, @Phase int phase,
                @NonNull InputMethodLatencyContext latencyContext) {
            LatencyTracker.getInstance(latencyContext.getAppContext())
                    .onActionCancel(ACTION_REQUEST_IME_HIDDEN);
        }

        public void onShown(@Nullable Token token,
                @NonNull InputMethodLatencyContext latencyContext) {
            LatencyTracker.getInstance(latencyContext.getAppContext())
                    .onActionEnd(ACTION_REQUEST_IME_SHOWN);
        }

        public void onHidden(@Nullable Token token,
                @NonNull InputMethodLatencyContext latencyContext) {
            LatencyTracker.getInstance(latencyContext.getAppContext())
                    .onActionEnd(ACTION_REQUEST_IME_HIDDEN);
        }
    }
}
