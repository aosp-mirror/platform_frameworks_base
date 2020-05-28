/*
 * Copyright 2020 The Android Open Source Project
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

package com.android.server.media;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.media.session.ISessionManager;
import android.media.session.MediaSession;
import android.os.Binder;
import android.view.KeyEvent;
import android.view.ViewConfiguration;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashMap;
import java.util.Map;

/**
 * Provides a way to customize behavior for media key events.
 * <p>
 * In order to override the implementation of the single/double/triple tap or long press,
 * {@link #setOverriddenKeyEvents(int, int)} should be called for each key code with the
 * overridden {@link KeyEventType} bit value set, and the corresponding method,
 * {@link #onSingleTap(KeyEvent)}, {@link #onDoubleTap(KeyEvent)},
 * {@link #onTripleTap(KeyEvent)}, {@link #onLongPress(KeyEvent)} should be implemented.
 * <p>
 * Note: When instantiating this class, {@link MediaSessionService} will only use the constructor
 * without any parameters.
 */
// TODO: Move this class to apex/media/
public abstract class MediaKeyDispatcher {
    @IntDef(flag = true, value = {
            KEY_EVENT_SINGLE_TAP,
            KEY_EVENT_DOUBLE_TAP,
            KEY_EVENT_TRIPLE_TAP,
            KEY_EVENT_LONG_PRESS
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface KeyEventType {}
    static final int KEY_EVENT_SINGLE_TAP = 1 << 0;
    static final int KEY_EVENT_DOUBLE_TAP = 1 << 1;
    static final int KEY_EVENT_TRIPLE_TAP = 1 << 2;
    static final int KEY_EVENT_LONG_PRESS = 1 << 3;

    private Map<Integer, Integer> mOverriddenKeyEvents;

    public MediaKeyDispatcher() {
        // Constructor used for reflection
        mOverriddenKeyEvents = new HashMap<>();
        mOverriddenKeyEvents.put(KeyEvent.KEYCODE_MEDIA_PLAY, 0);
        mOverriddenKeyEvents.put(KeyEvent.KEYCODE_MEDIA_PAUSE, 0);
        mOverriddenKeyEvents.put(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0);
        mOverriddenKeyEvents.put(KeyEvent.KEYCODE_MUTE, 0);
        mOverriddenKeyEvents.put(KeyEvent.KEYCODE_HEADSETHOOK, 0);
        mOverriddenKeyEvents.put(KeyEvent.KEYCODE_MEDIA_STOP, 0);
        mOverriddenKeyEvents.put(KeyEvent.KEYCODE_MEDIA_NEXT, 0);
        mOverriddenKeyEvents.put(KeyEvent.KEYCODE_MEDIA_PREVIOUS, 0);
    }

    // TODO: Move this method into SessionPolicyProvider.java for better readability.
    /**
     * Implement this to customize the logic for which MediaSession should consume which key event.
     *
     * Note: This session will have greater priority over the {@link PendingIntent} returned from
     * {@link #getCustomMediaButtonReceiver()}.
     *
     * @param keyEvent a non-null KeyEvent whose key code is one of the supported media buttons.
     * @param uid the uid value retrieved by calling {@link Binder#getCallingUid()} from
     *         {@link ISessionManager#dispatchMediaKeyEvent(String, boolean, KeyEvent, boolean)}
     * @param asSystemService {@code true} if the event came from the system service via hardware
     *         devices. {@code false} if the event came from the app process through key injection.
     * @return a {@link MediaSession.Token} instance that should consume the given key event.
     */
    @Nullable
    MediaSession.Token getCustomMediaSession(@NonNull KeyEvent keyEvent, int uid,
            boolean asSystemService) {
        return null;
    }

    /**
     * Implement this to customize the logic for which MediaButtonReceiver should consume a
     * dispatched key event.
     *
     * Note: This pending intent will have lower priority over the {@link MediaSession.Token}
     * returned from {@link #getCustomMediaButtonReceiver()}.
     *
     * @return a {@link PendingIntent} instance that should receive the dispatched key event.
     */
    @Nullable
    PendingIntent getCustomMediaButtonReceiver() {
        return null;
    }

    /**
     * Gets the map of key code -> {@link KeyEventType} that have been overridden.
     * <p>
     * The list of valid key codes are the following:
     * <ul>
     * <li> {@link KeyEvent#KEYCODE_MEDIA_PLAY}
     * <li> {@link KeyEvent#KEYCODE_MEDIA_PAUSE}
     * <li> {@link KeyEvent#KEYCODE_MEDIA_PLAY_PAUSE}
     * <li> {@link KeyEvent#KEYCODE_MUTE}
     * <li> {@link KeyEvent#KEYCODE_HEADSETHOOK}
     * <li> {@link KeyEvent#KEYCODE_MEDIA_STOP}
     * <li> {@link KeyEvent#KEYCODE_MEDIA_NEXT}
     * <li> {@link KeyEvent#KEYCODE_MEDIA_PREVIOUS}
     * </ul>
     * @see {@link KeyEvent#isMediaSessionKey(int)}
     */
    @KeyEventType Map<Integer, Integer> getOverriddenKeyEvents() {
        return mOverriddenKeyEvents;
    }

    static boolean isSingleTapOverridden(@KeyEventType int overriddenKeyEvents) {
        return (overriddenKeyEvents & MediaKeyDispatcher.KEY_EVENT_SINGLE_TAP) != 0;
    }

    static boolean isDoubleTapOverridden(@KeyEventType int overriddenKeyEvents) {
        return (overriddenKeyEvents & MediaKeyDispatcher.KEY_EVENT_DOUBLE_TAP) != 0;
    }

    static boolean isTripleTapOverridden(@KeyEventType int overriddenKeyEvents) {
        return (overriddenKeyEvents & MediaKeyDispatcher.KEY_EVENT_TRIPLE_TAP) != 0;
    }

    static boolean isLongPressOverridden(@KeyEventType int overriddenKeyEvents) {
        return (overriddenKeyEvents & MediaKeyDispatcher.KEY_EVENT_LONG_PRESS) != 0;
    }

    /**
     * Sets the value of the given key event type flagged with overridden {@link KeyEventType} to
     * the given key code. If called multiple times for the same key code, will be overwritten to
     * the most recently called {@link KeyEventType} value.
     * <p>
     * The list of valid key codes are the following:
     * <ul>
     * <li> {@link KeyEvent#KEYCODE_MEDIA_PLAY}
     * <li> {@link KeyEvent#KEYCODE_MEDIA_PAUSE}
     * <li> {@link KeyEvent#KEYCODE_MEDIA_PLAY_PAUSE}
     * <li> {@link KeyEvent#KEYCODE_MUTE}
     * <li> {@link KeyEvent#KEYCODE_HEADSETHOOK}
     * <li> {@link KeyEvent#KEYCODE_MEDIA_STOP}
     * <li> {@link KeyEvent#KEYCODE_MEDIA_NEXT}
     * <li> {@link KeyEvent#KEYCODE_MEDIA_PREVIOUS}
     * </ul>
     * @see {@link KeyEvent#isMediaSessionKey(int)}
     * @param keyCode
     */
    void setOverriddenKeyEvents(int keyCode, @KeyEventType int keyEventType) {
        mOverriddenKeyEvents.put(keyCode, keyEventType);
    }

    /**
     * Customized implementation for single tap event. Will be run if
     * {@link #KEY_EVENT_SINGLE_TAP} flag is on for the corresponding key code from
     * {@link #getOverriddenKeyEvents()}.
     *
     * It is considered a single tap if only one {@link KeyEvent} with the same
     * {@link KeyEvent#getKeyCode()} is dispatched within
     * {@link ViewConfiguration#getMultiPressTimeout()} milliseconds. Change the
     * {@link android.provider.Settings.Secure#MULTI_PRESS_TIMEOUT} value to adjust the interval.
     *
     * Note: This will only be called once with the {@link KeyEvent#ACTION_UP} KeyEvent.
     *
     * @param keyEvent
     */
    void onSingleTap(KeyEvent keyEvent) {
    }

    /**
     * Customized implementation for double tap event. Will be run if
     * {@link #KEY_EVENT_DOUBLE_TAP} flag is on for the corresponding key code from
     * {@link #getOverriddenKeyEvents()}.
     *
     * It is considered a double tap if two {@link KeyEvent}s with the same
     * {@link KeyEvent#getKeyCode()} are dispatched within
     * {@link ViewConfiguration#getMultiPressTimeout()} milliseconds of each other. Change the
     * {@link android.provider.Settings.Secure#MULTI_PRESS_TIMEOUT} value to adjust the interval.
     *
     * Note: This will only be called once with the {@link KeyEvent#ACTION_UP} KeyEvent.
     *
     * @param keyEvent
     */
    void onDoubleTap(KeyEvent keyEvent) {
    }

    /**
     * Customized implementation for triple tap event. Will be run if
     * {@link #KEY_EVENT_TRIPLE_TAP} flag is on for the corresponding key code from
     * {@link #getOverriddenKeyEvents()}.
     *
     * It is considered a triple tap if three {@link KeyEvent}s with the same
     * {@link KeyEvent#getKeyCode()} are dispatched within
     * {@link ViewConfiguration#getMultiPressTimeout()} milliseconds of each other. Change the
     * {@link android.provider.Settings.Secure#MULTI_PRESS_TIMEOUT} value to adjust the interval.
     *
     * Note: This will only be called once with the {@link KeyEvent#ACTION_UP} KeyEvent.
     *
     * @param keyEvent
     */
    void onTripleTap(KeyEvent keyEvent) {
    }

    /**
     * Customized implementation for long press event. Will be run if
     * {@link #KEY_EVENT_LONG_PRESS} flag is on for the corresponding key code from
     * {@link #getOverriddenKeyEvents()}.
     *
     * It is considered a long press if an {@link KeyEvent#ACTION_DOWN} key event is followed by
     * another {@link KeyEvent#ACTION_DOWN} key event with {@link KeyEvent#FLAG_LONG_PRESS}
     * enabled, and an {@link KeyEvent#getRepeatCount()} that is equal to 1.
     *
     * Note: This will be called for the following key events:
     * <ul>
     *   <li>A {@link KeyEvent#ACTION_DOWN} KeyEvent with {@link KeyEvent#FLAG_LONG_PRESS} and
     *   {@link KeyEvent#getRepeatCount()} equal to 1</li>
     *   <li>Multiple {@link KeyEvent#ACTION_DOWN} KeyEvents with increasing
     *   {@link KeyEvent#getRepeatCount()}</li>
     *   <li>A {@link KeyEvent#ACTION_UP} KeyEvent</li>
     * </ul>
     *
     * @param keyEvent
     */
    void onLongPress(KeyEvent keyEvent) {
    }
}
