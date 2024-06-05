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

package com.android.server.wm.flicker.testapp;

import static android.media.MediaMetadata.METADATA_KEY_TITLE;
import static android.media.session.PlaybackState.ACTION_PAUSE;
import static android.media.session.PlaybackState.ACTION_PLAY;
import static android.media.session.PlaybackState.ACTION_STOP;
import static android.media.session.PlaybackState.STATE_PAUSED;
import static android.media.session.PlaybackState.STATE_PLAYING;
import static android.media.session.PlaybackState.STATE_STOPPED;

import static com.android.server.wm.flicker.testapp.ActivityOptions.Pip.ACTION_ENTER_PIP;
import static com.android.server.wm.flicker.testapp.ActivityOptions.Pip.ACTION_SET_REQUESTED_ORIENTATION;
import static com.android.server.wm.flicker.testapp.ActivityOptions.Pip.EXTRA_ENTER_PIP;
import static com.android.server.wm.flicker.testapp.ActivityOptions.Pip.EXTRA_PIP_ORIENTATION;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.util.Log;
import android.util.Rational;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.RadioButton;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class PipActivity extends Activity {
    private static final String TAG = PipActivity.class.getSimpleName();
    /**
     * A media session title for when the session is in {@link STATE_PLAYING}.
     * TvPipNotificationTests check whether the actual notification title matches this string.
     */
    private static final String TITLE_STATE_PLAYING = "TestApp media is playing";
    /**
     * A media session title for when the session is in {@link STATE_PAUSED}.
     * TvPipNotificationTests check whether the actual notification title matches this string.
     */
    private static final String TITLE_STATE_PAUSED = "TestApp media is paused";

    private static final Rational RATIO_DEFAULT = null;
    private static final Rational RATIO_SQUARE = new Rational(1, 1);
    private static final Rational RATIO_WIDE = new Rational(2, 1);
    private static final Rational RATIO_TALL = new Rational(1, 2);

    private static final String PIP_ACTION_NO_OP = "No-Op";
    private static final String PIP_ACTION_OFF = "Off";
    private static final String PIP_ACTION_ON = "On";
    private static final String PIP_ACTION_CLEAR = "Clear";
    private static final String ACTION_NO_OP = "com.android.wm.shell.flicker.testapp.NO_OP";
    private static final String ACTION_SWITCH_OFF =
            "com.android.wm.shell.flicker.testapp.SWITCH_OFF";
    private static final String ACTION_SWITCH_ON = "com.android.wm.shell.flicker.testapp.SWITCH_ON";
    private static final String ACTION_CLEAR = "com.android.wm.shell.flicker.testapp.CLEAR";
    private static final String ACTION_ASPECT_RATIO =
            "com.android.wm.shell.flicker.testapp.ASPECT_RATIO";

    private final PictureInPictureParams.Builder mPipParamsBuilder =
            new PictureInPictureParams.Builder()
                    .setAspectRatio(RATIO_DEFAULT);
    private MediaSession mMediaSession;
    private final PlaybackState.Builder mPlaybackStateBuilder = new PlaybackState.Builder()
            .setActions(ACTION_PLAY | ACTION_PAUSE | ACTION_STOP)
            .setState(STATE_STOPPED, 0, 1f);
    private PlaybackState mPlaybackState = mPlaybackStateBuilder.build();
    private final MediaMetadata.Builder mMediaMetadataBuilder = new MediaMetadata.Builder();

    private final List<RemoteAction> mSwitchOffActions = new ArrayList<>();
    private final List<RemoteAction> mSwitchOnActions = new ArrayList<>();
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (isInPictureInPictureMode()) {
                switch (intent.getAction()) {
                    case ACTION_SWITCH_ON:
                        mPipParamsBuilder.setActions(mSwitchOnActions);
                        break;
                    case ACTION_SWITCH_OFF:
                        mPipParamsBuilder.setActions(mSwitchOffActions);
                        break;
                    case ACTION_CLEAR:
                        mPipParamsBuilder.setActions(Collections.emptyList());
                        break;
                    case ACTION_ASPECT_RATIO:
                        mPipParamsBuilder.setAspectRatio(RATIO_TALL);
                        break;
                    case ACTION_NO_OP:
                        return;
                    default:
                        Log.w(TAG, "Unhandled action=" + intent.getAction());
                        return;
                }
                setPictureInPictureParams(mPipParamsBuilder.build());
            } else {
                switch (intent.getAction()) {
                    case ACTION_ENTER_PIP:
                        enterPip(null);
                        break;
                    case ACTION_SET_REQUESTED_ORIENTATION:
                        setRequestedOrientation(Integer.parseInt(intent.getStringExtra(
                                EXTRA_PIP_ORIENTATION)));
                        break;
                    default:
                        Log.w(TAG, "Unhandled action=" + intent.getAction());
                }
            }
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Window window = getWindow();
        final WindowManager.LayoutParams layoutParams = window.getAttributes();
        layoutParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams
                .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        window.setAttributes(layoutParams);

        setContentView(R.layout.activity_pip);

        findViewById(R.id.media_session_start)
                .setOnClickListener(v -> updateMediaSessionState(STATE_PLAYING));
        findViewById(R.id.media_session_stop)
                .setOnClickListener(v -> updateMediaSessionState(STATE_STOPPED));

        mMediaSession = new MediaSession(this, "WMShell_TestApp");
        mMediaSession.setPlaybackState(mPlaybackStateBuilder.build());
        mMediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                updateMediaSessionState(STATE_PLAYING);
            }

            @Override
            public void onPause() {
                updateMediaSessionState(STATE_PAUSED);
            }

            @Override
            public void onStop() {
                updateMediaSessionState(STATE_STOPPED);
            }
        });

        // Build two sets of the custom actions. We'll replace one with the other when 'On'/'Off'
        // action is invoked.
        // The first set consists of 3 actions: 1) Off; 2) No-Op; 3) Clear.
        // The second set consists of 2 actions: 1) On; 2) Clear.
        // Upon invocation 'Clear' action clear-off all the custom actions, including itself.
        final Icon icon = Icon.createWithResource(this, android.R.drawable.ic_menu_help);
        final RemoteAction noOpAction = buildRemoteAction(icon, PIP_ACTION_NO_OP, ACTION_NO_OP);
        final RemoteAction switchOnAction =
                buildRemoteAction(icon, PIP_ACTION_ON, ACTION_SWITCH_ON);
        final RemoteAction switchOffAction =
                buildRemoteAction(icon, PIP_ACTION_OFF, ACTION_SWITCH_OFF);
        final RemoteAction clearAllAction = buildRemoteAction(icon, PIP_ACTION_CLEAR, ACTION_CLEAR);
        mSwitchOffActions.addAll(Arrays.asList(switchOnAction, clearAllAction));
        mSwitchOnActions.addAll(Arrays.asList(noOpAction, switchOffAction, clearAllAction));

        final IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_NO_OP);
        filter.addAction(ACTION_SWITCH_ON);
        filter.addAction(ACTION_SWITCH_OFF);
        filter.addAction(ACTION_CLEAR);
        filter.addAction(ACTION_SET_REQUESTED_ORIENTATION);
        filter.addAction(ACTION_ENTER_PIP);
        filter.addAction(ACTION_ASPECT_RATIO);
        registerReceiver(mBroadcastReceiver, filter, Context.RECEIVER_EXPORTED);

        handleIntentExtra(getIntent());
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(mBroadcastReceiver);
        super.onDestroy();
    }

    @Override
    protected void onUserLeaveHint() {
        // Only used when auto PiP is disabled. This is to simulate the behavior that an app
        // supports regular PiP but not auto PiP.
        final boolean manuallyEnterPip =
                ((RadioButton) findViewById(R.id.enter_pip_on_leave_manual)).isChecked();
        if (manuallyEnterPip) {
            enterPictureInPictureMode();
        }
    }

    private RemoteAction buildRemoteAction(Icon icon, String label, String action) {
        final Intent intent = new Intent(action);
        final PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, intent,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new RemoteAction(icon, label, label, pendingIntent);
    }

    public void enterPip(View v) {
        final boolean withCustomActions =
                ((CheckBox) findViewById(R.id.with_custom_actions)).isChecked();
        mPipParamsBuilder.setActions(
                withCustomActions ? mSwitchOnActions : Collections.emptyList());
        enterPictureInPictureMode(mPipParamsBuilder.build());
    }

    public void onAutoPipSelected(View v) {
        switch (v.getId()) {
            case R.id.enter_pip_on_leave_manual:
                // disable auto enter PiP
            case R.id.enter_pip_on_leave_disabled:
                mPipParamsBuilder.setAutoEnterEnabled(false);
                setPictureInPictureParams(mPipParamsBuilder.build());
                break;
            case R.id.enter_pip_on_leave_autoenter:
                mPipParamsBuilder.setAutoEnterEnabled(true);
                setPictureInPictureParams(mPipParamsBuilder.build());
                break;
        }
    }

    /**
     * Adds a temporary view used for testing sourceRectHint.
     *
     */
    public void setSourceRectHint(View v) {
        View rectView = findViewById(R.id.source_rect);
        if (rectView != null) {
            rectView.setVisibility(View.VISIBLE);
            rectView.getViewTreeObserver().addOnGlobalLayoutListener(
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            Rect boundingRect = new Rect();
                            rectView.getGlobalVisibleRect(boundingRect);
                            mPipParamsBuilder.setSourceRectHint(boundingRect);
                            setPictureInPictureParams(mPipParamsBuilder.build());
                            rectView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        }
                    });
            rectView.invalidate(); // changing the visibility, invalidating to redraw the view
        }
    }

    public void onRatioSelected(View v) {
        switch (v.getId()) {
            case R.id.ratio_default:
                mPipParamsBuilder.setAspectRatio(RATIO_DEFAULT);
                break;

            case R.id.ratio_square:
                mPipParamsBuilder.setAspectRatio(RATIO_SQUARE);
                break;

            case R.id.ratio_wide:
                mPipParamsBuilder.setAspectRatio(RATIO_WIDE);
                break;

            case R.id.ratio_tall:
                mPipParamsBuilder.setAspectRatio(RATIO_TALL);
                break;
        }
    }

    private void updateMediaSessionState(int newState) {
        if (mPlaybackState.getState() == newState) {
            return;
        }
        final String title;
        switch (newState) {
            case STATE_PLAYING:
                title = TITLE_STATE_PLAYING;
                break;
            case STATE_PAUSED:
                title = TITLE_STATE_PAUSED;
                break;
            case STATE_STOPPED:
                title = "";
                break;

            default:
                throw new IllegalArgumentException("Unknown state " + newState);
        }

        mPlaybackStateBuilder.setState(newState, 0, 1f);
        mPlaybackState = mPlaybackStateBuilder.build();

        mMediaMetadataBuilder.putText(METADATA_KEY_TITLE, title);

        mMediaSession.setPlaybackState(mPlaybackState);
        mMediaSession.setMetadata(mMediaMetadataBuilder.build());
        mMediaSession.setActive(newState != STATE_STOPPED);
    }

    private void handleIntentExtra(Intent intent) {
        // Set the fixed orientation if requested
        if (intent.hasExtra(EXTRA_PIP_ORIENTATION)) {
            final int ori = Integer.parseInt(getIntent().getStringExtra(EXTRA_PIP_ORIENTATION));
            setRequestedOrientation(ori);
        }
        // Enter picture in picture with the given aspect ratio if provided
        if (intent.hasExtra(EXTRA_ENTER_PIP)) {
            mPipParamsBuilder.setActions(mSwitchOnActions);
            enterPip(null);
        }
    }
}
