/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.policy.impl;

import android.app.KeyguardManager;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.media.IAudioService;
import android.media.session.MediaSessionLegacyHelper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.Slog;
import android.view.View;
import android.view.HapticFeedbackConstants;
import android.view.FallbackEventHandler;
import android.view.KeyEvent;

public class PhoneFallbackEventHandler implements FallbackEventHandler {
    private static String TAG = "PhoneFallbackEventHandler";
    private static final boolean DEBUG = false;

    Context mContext;
    View mView;

    AudioManager mAudioManager;
    KeyguardManager mKeyguardManager;
    SearchManager mSearchManager;
    TelephonyManager mTelephonyManager;

    public PhoneFallbackEventHandler(Context context) {
        mContext = context;
    }

    public void setView(View v) {
        mView = v;
    }

    public void preDispatchKeyEvent(KeyEvent event) {
        getAudioManager().preDispatchKeyEvent(event, AudioManager.USE_DEFAULT_STREAM_TYPE);
    }

    public boolean dispatchKeyEvent(KeyEvent event) {

        final int action = event.getAction();
        final int keyCode = event.getKeyCode();

        if (action == KeyEvent.ACTION_DOWN) {
            return onKeyDown(keyCode, event);
        } else {
            return onKeyUp(keyCode, event);
        }
    }

    boolean onKeyDown(int keyCode, KeyEvent event) {
        /* ****************************************************************************
         * HOW TO DECIDE WHERE YOUR KEY HANDLING GOES.
         * See the comment in PhoneWindow.onKeyDown
         * ****************************************************************************/
        final KeyEvent.DispatcherState dispatcher = mView.getKeyDispatcherState();

        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE: {
                MediaSessionLegacyHelper.getHelper(mContext).sendVolumeKeyEvent(event, false);
                return true;
            }


            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                /* Suppress PLAY/PAUSE toggle when phone is ringing or in-call
                 * to avoid music playback */
                if (getTelephonyManager().getCallState() != TelephonyManager.CALL_STATE_IDLE) {
                    return true;  // suppress key event
                }
            case KeyEvent.KEYCODE_MUTE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_RECORD:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK: {
                handleMediaKeyEvent(event);
                return true;
            }

            case KeyEvent.KEYCODE_CALL: {
                if (getKeyguardManager().inKeyguardRestrictedInputMode() || dispatcher == null) {
                    break;
                }
                if (event.getRepeatCount() == 0) {
                    dispatcher.startTracking(event, this);
                } else if (event.isLongPress() && dispatcher.isTracking(event)) {
                    dispatcher.performedLongPress(event);
                    mView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    // launch the VoiceDialer
                    Intent intent = new Intent(Intent.ACTION_VOICE_COMMAND);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        sendCloseSystemWindows();
                        mContext.startActivity(intent);
                    } catch (ActivityNotFoundException e) {
                        startCallActivity();
                    }
                }
                return true;
            }

            case KeyEvent.KEYCODE_CAMERA: {
                if (getKeyguardManager().inKeyguardRestrictedInputMode() || dispatcher == null) {
                    break;
                }
                if (event.getRepeatCount() == 0) {
                    dispatcher.startTracking(event, this);
                } else if (event.isLongPress() && dispatcher.isTracking(event)) {
                    dispatcher.performedLongPress(event);
                    mView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    sendCloseSystemWindows();
                    // Broadcast an intent that the Camera button was longpressed
                    Intent intent = new Intent(Intent.ACTION_CAMERA_BUTTON, null);
                    intent.putExtra(Intent.EXTRA_KEY_EVENT, event);
                    mContext.sendOrderedBroadcastAsUser(intent, UserHandle.CURRENT_OR_SELF,
                            null, null, null, 0, null, null);
                }
                return true;
            }

            case KeyEvent.KEYCODE_SEARCH: {
                if (getKeyguardManager().inKeyguardRestrictedInputMode() || dispatcher == null) {
                    break;
                }
                if (event.getRepeatCount() == 0) {
                    dispatcher.startTracking(event, this);
                } else if (event.isLongPress() && dispatcher.isTracking(event)) {
                    Configuration config = mContext.getResources().getConfiguration();
                    if (config.keyboard == Configuration.KEYBOARD_NOKEYS
                            || config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_YES) {
                        // launch the search activity
                        Intent intent = new Intent(Intent.ACTION_SEARCH_LONG_PRESS);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try {
                            mView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                            sendCloseSystemWindows();
                            getSearchManager().stopSearch();
                            mContext.startActivity(intent);
                            // Only clear this if we successfully start the
                            // activity; otherwise we will allow the normal short
                            // press action to be performed.
                            dispatcher.performedLongPress(event);
                            return true;
                        } catch (ActivityNotFoundException e) {
                            // Ignore
                        }
                    }
                }
                break;
            }
        }
        return false;
    }

    boolean onKeyUp(int keyCode, KeyEvent event) {
        if (DEBUG) {
            Slog.d(TAG, "up " + keyCode);
        }
        final KeyEvent.DispatcherState dispatcher = mView.getKeyDispatcherState();
        if (dispatcher != null) {
            dispatcher.handleUpEvent(event);
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE: {
                if (!event.isCanceled()) {
                    MediaSessionLegacyHelper.getHelper(mContext).sendVolumeKeyEvent(event, false);
                }
                return true;
            }

            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MUTE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_RECORD:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK: {
                handleMediaKeyEvent(event);
                return true;
            }

            case KeyEvent.KEYCODE_CAMERA: {
                if (getKeyguardManager().inKeyguardRestrictedInputMode()) {
                    break;
                }
                if (event.isTracking() && !event.isCanceled()) {
                    // Add short press behavior here if desired
                }
                return true;
            }

            case KeyEvent.KEYCODE_CALL: {
                if (getKeyguardManager().inKeyguardRestrictedInputMode()) {
                    break;
                }
                if (event.isTracking() && !event.isCanceled()) {
                    startCallActivity();
                }
                return true;
            }
        }
        return false;
    }

    void startCallActivity() {
        sendCloseSystemWindows();
        Intent intent = new Intent(Intent.ACTION_CALL_BUTTON);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            mContext.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Slog.w(TAG, "No activity found for android.intent.action.CALL_BUTTON.");
        }
    }

    SearchManager getSearchManager() {
        if (mSearchManager == null) {
            mSearchManager = (SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE);
        }
        return mSearchManager;
    }

    TelephonyManager getTelephonyManager() {
        if (mTelephonyManager == null) {
            mTelephonyManager = (TelephonyManager)mContext.getSystemService(
                    Context.TELEPHONY_SERVICE);
        }
        return mTelephonyManager;
    }

    KeyguardManager getKeyguardManager() {
        if (mKeyguardManager == null) {
            mKeyguardManager = (KeyguardManager)mContext.getSystemService(Context.KEYGUARD_SERVICE);
        }
        return mKeyguardManager;
    }

    AudioManager getAudioManager() {
        if (mAudioManager == null) {
            mAudioManager = (AudioManager)mContext.getSystemService(Context.AUDIO_SERVICE);
        }
        return mAudioManager;
    }

    void sendCloseSystemWindows() {
        PhoneWindowManager.sendCloseSystemWindows(mContext, null);
    }

    private void handleMediaKeyEvent(KeyEvent keyEvent) {
        MediaSessionLegacyHelper.getHelper(mContext).sendMediaButtonEvent(keyEvent, false);
    }
}

