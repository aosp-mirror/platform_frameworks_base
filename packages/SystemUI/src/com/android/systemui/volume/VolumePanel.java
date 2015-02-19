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

package com.android.systemui.volume;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnDismissListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioService;
import android.media.AudioSystem;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.media.VolumeProvider;
import android.media.session.MediaController;
import android.media.session.MediaController.PlaybackInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.Message;
import android.os.Vibrator;
import android.util.Log;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;

import com.android.internal.R;
import com.android.systemui.DemoMode;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.ZenModeController;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Handles the user interface for the volume keys.
 *
 * @hide
 */
public class VolumePanel extends Handler implements DemoMode {
    private static final String TAG = "VolumePanel";
    private static boolean LOGD = Log.isLoggable(TAG, Log.DEBUG);

    private static final int PLAY_SOUND_DELAY = AudioService.PLAY_SOUND_DELAY;

    /**
     * The delay before vibrating. This small period exists so if the user is
     * moving to silent mode, it will not emit a short vibrate (it normally
     * would since vibrate is between normal mode and silent mode using hardware
     * keys).
     */
    public static final int VIBRATE_DELAY = 300;

    private static final int VIBRATE_DURATION = 300;
    private static final int BEEP_DURATION = 150;
    private static final int MAX_VOLUME = 100;
    private static final int FREE_DELAY = 10000;
    private static final int TIMEOUT_DELAY = 3000;
    private static final int TIMEOUT_DELAY_SHORT = 1500;
    private static final int TIMEOUT_DELAY_COLLAPSED = 4500;
    private static final int TIMEOUT_DELAY_SAFETY_WARNING = 5000;
    private static final int TIMEOUT_DELAY_EXPANDED = 10000;

    private static final int MSG_VOLUME_CHANGED = 0;
    private static final int MSG_FREE_RESOURCES = 1;
    private static final int MSG_PLAY_SOUND = 2;
    private static final int MSG_STOP_SOUNDS = 3;
    private static final int MSG_VIBRATE = 4;
    private static final int MSG_TIMEOUT = 5;
    private static final int MSG_RINGER_MODE_CHANGED = 6;
    private static final int MSG_MUTE_CHANGED = 7;
    private static final int MSG_REMOTE_VOLUME_CHANGED = 8;
    private static final int MSG_REMOTE_VOLUME_UPDATE_IF_SHOWN = 9;
    private static final int MSG_SLIDER_VISIBILITY_CHANGED = 10;
    private static final int MSG_DISPLAY_SAFE_VOLUME_WARNING = 11;
    private static final int MSG_LAYOUT_DIRECTION = 12;
    private static final int MSG_ZEN_MODE_AVAILABLE_CHANGED = 13;
    private static final int MSG_USER_ACTIVITY = 14;
    private static final int MSG_NOTIFICATION_EFFECTS_SUPPRESSOR_CHANGED = 15;
    private static final int MSG_INTERNAL_RINGER_MODE_CHANGED = 16;

    // Pseudo stream type for master volume
    private static final int STREAM_MASTER = -100;
    // Pseudo stream type for remote volume
    private static final int STREAM_REMOTE_MUSIC = -200;

    private static final AudioAttributes VIBRATION_ATTRIBUTES = new AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .build();

    private static final int IC_AUDIO_VOL = com.android.systemui.R.drawable.ic_audio_vol;
    private static final int IC_AUDIO_VOL_MUTE = com.android.systemui.R.drawable.ic_audio_vol_mute;
    private static final int IC_AUDIO_BT = com.android.systemui.R.drawable.ic_audio_bt;
    private static final int IC_AUDIO_BT_MUTE = com.android.systemui.R.drawable.ic_audio_bt_mute;

    private final String mTag;
    protected final Context mContext;
    private final AudioManager mAudioManager;
    private final ZenModeController mZenController;
    private boolean mRingIsSilent;
    private boolean mVoiceCapable;
    private boolean mZenModeAvailable;
    private boolean mZenPanelExpanded;
    private int mTimeoutDelay = TIMEOUT_DELAY;
    private float mDisabledAlpha;
    private int mLastRingerMode = AudioManager.RINGER_MODE_NORMAL;
    private int mLastRingerProgress = 0;
    private int mDemoIcon;

    // True if we want to play tones on the system stream when the master stream is specified.
    private final boolean mPlayMasterStreamTones;


    /** Volume panel content view */
    private final View mView;
    /** Dialog hosting the panel */
    private final Dialog mDialog;

    /** The visible portion of the volume overlay */
    private final ViewGroup mPanel;
    /** Contains the slider and its touchable icons */
    private final ViewGroup mSliderPanel;
    /** The zen mode configuration panel view */
    private ZenModePanel mZenPanel;
    /** The component currently suppressing notification stream effects */
    private ComponentName mNotificationEffectsSuppressor;

    private Callback mCallback;

    /** Currently active stream that shows up at the top of the list of sliders */
    private int mActiveStreamType = -1;
    /** All the slider controls mapped by stream type */
    private SparseArray<StreamControl> mStreamControls;
    private final AccessibilityManager mAccessibilityManager;
    private final SecondaryIconTransition mSecondaryIconTransition;
    private final IconPulser mIconPulser;

    private enum StreamResources {
        BluetoothSCOStream(AudioManager.STREAM_BLUETOOTH_SCO,
                R.string.volume_icon_description_bluetooth,
                IC_AUDIO_BT,
                IC_AUDIO_BT_MUTE,
                false),
        RingerStream(AudioManager.STREAM_RING,
                R.string.volume_icon_description_ringer,
                com.android.systemui.R.drawable.ic_ringer_audible,
                com.android.systemui.R.drawable.ic_ringer_mute,
                false),
        VoiceStream(AudioManager.STREAM_VOICE_CALL,
                R.string.volume_icon_description_incall,
                com.android.systemui.R.drawable.ic_audio_phone,
                com.android.systemui.R.drawable.ic_audio_phone,
                false),
        AlarmStream(AudioManager.STREAM_ALARM,
                R.string.volume_alarm,
                com.android.systemui.R.drawable.ic_audio_alarm,
                com.android.systemui.R.drawable.ic_audio_alarm_mute,
                false),
        MediaStream(AudioManager.STREAM_MUSIC,
                R.string.volume_icon_description_media,
                IC_AUDIO_VOL,
                IC_AUDIO_VOL_MUTE,
                true),
        NotificationStream(AudioManager.STREAM_NOTIFICATION,
                R.string.volume_icon_description_notification,
                com.android.systemui.R.drawable.ic_ringer_audible,
                com.android.systemui.R.drawable.ic_ringer_mute,
                true),
        // for now, use media resources for master volume
        MasterStream(STREAM_MASTER,
                R.string.volume_icon_description_media, //FIXME should have its own description
                IC_AUDIO_VOL,
                IC_AUDIO_VOL_MUTE,
                false),
        RemoteStream(STREAM_REMOTE_MUSIC,
                R.string.volume_icon_description_media, //FIXME should have its own description
                com.android.systemui.R.drawable.ic_audio_remote,
                com.android.systemui.R.drawable.ic_audio_remote,
                false);// will be dynamically updated

        int streamType;
        int descRes;
        int iconRes;
        int iconMuteRes;
        // RING, VOICE_CALL & BLUETOOTH_SCO are hidden unless explicitly requested
        boolean show;

        StreamResources(int streamType, int descRes, int iconRes, int iconMuteRes, boolean show) {
            this.streamType = streamType;
            this.descRes = descRes;
            this.iconRes = iconRes;
            this.iconMuteRes = iconMuteRes;
            this.show = show;
        }
    }

    // List of stream types and their order
    private static final StreamResources[] STREAMS = {
        StreamResources.BluetoothSCOStream,
        StreamResources.RingerStream,
        StreamResources.VoiceStream,
        StreamResources.MediaStream,
        StreamResources.NotificationStream,
        StreamResources.AlarmStream,
        StreamResources.MasterStream,
        StreamResources.RemoteStream
    };

    /** Object that contains data for each slider */
    private class StreamControl {
        int streamType;
        MediaController controller;
        ViewGroup group;
        ImageView icon;
        SeekBar seekbarView;
        TextView suppressorView;
        View divider;
        ImageView secondaryIcon;
        int iconRes;
        int iconMuteRes;
        int iconSuppressedRes;
    }

    // Synchronize when accessing this
    private ToneGenerator mToneGenerators[];
    private Vibrator mVibrator;
    private boolean mHasVibrator;

    private static AlertDialog sSafetyWarning;
    private static Object sSafetyWarningLock = new Object();

    private static class SafetyWarning extends SystemUIDialog
            implements DialogInterface.OnDismissListener, DialogInterface.OnClickListener {
        private final Context mContext;
        private final VolumePanel mVolumePanel;
        private final AudioManager mAudioManager;

        private boolean mNewVolumeUp;

        SafetyWarning(Context context, VolumePanel volumePanel, AudioManager audioManager) {
            super(context);
            mContext = context;
            mVolumePanel = volumePanel;
            mAudioManager = audioManager;

            setMessage(mContext.getString(com.android.internal.R.string.safe_media_volume_warning));
            setButton(DialogInterface.BUTTON_POSITIVE,
                    mContext.getString(com.android.internal.R.string.yes), this);
            setButton(DialogInterface.BUTTON_NEGATIVE,
                    mContext.getString(com.android.internal.R.string.no), (OnClickListener) null);
            setOnDismissListener(this);

            IntentFilter filter = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            context.registerReceiver(mReceiver, filter);
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP && event.getRepeatCount() == 0) {
                mNewVolumeUp = true;
            }
            return super.onKeyDown(keyCode, event);
        }

        @Override
        public boolean onKeyUp(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP && mNewVolumeUp) {
                if (LOGD) Log.d(TAG, "Confirmed warning via VOLUME_UP");
                mAudioManager.disableSafeMediaVolume();
                dismiss();
            }
            return super.onKeyUp(keyCode, event);
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            mAudioManager.disableSafeMediaVolume();
        }

        @Override
        public void onDismiss(DialogInterface unused) {
            mContext.unregisterReceiver(mReceiver);
            cleanUp();
        }

        private void cleanUp() {
            synchronized (sSafetyWarningLock) {
                sSafetyWarning = null;
            }
            mVolumePanel.forceTimeout(0);
            mVolumePanel.updateStates();
        }

        private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())) {
                    if (LOGD) Log.d(TAG, "Received ACTION_CLOSE_SYSTEM_DIALOGS");
                    cancel();
                    cleanUp();
                }
            }
        };
    }

    public VolumePanel(Context context, ZenModeController zenController) {
        mTag = String.format("%s.%08x", TAG, hashCode());
        mContext = context;
        mZenController = zenController;
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mAccessibilityManager = (AccessibilityManager) context.getSystemService(
                Context.ACCESSIBILITY_SERVICE);
        mSecondaryIconTransition = new SecondaryIconTransition();
        mIconPulser = new IconPulser(context);

        // For now, only show master volume if master volume is supported
        final Resources res = context.getResources();
        final boolean useMasterVolume = res.getBoolean(R.bool.config_useMasterVolume);
        if (useMasterVolume) {
            for (int i = 0; i < STREAMS.length; i++) {
                StreamResources streamRes = STREAMS[i];
                streamRes.show = (streamRes.streamType == STREAM_MASTER);
            }
        }
        if (LOGD) Log.d(mTag, "new VolumePanel");

        mDisabledAlpha = 0.5f;
        if (mContext.getTheme() != null) {
            final TypedArray arr = mContext.getTheme().obtainStyledAttributes(
                    new int[] { android.R.attr.disabledAlpha });
            mDisabledAlpha = arr.getFloat(0, mDisabledAlpha);
            arr.recycle();
        }

        mDialog = new Dialog(context) {
            @Override
            public boolean onTouchEvent(MotionEvent event) {
                if (isShowing() && event.getAction() == MotionEvent.ACTION_OUTSIDE &&
                        sSafetyWarning == null) {
                    forceTimeout(0);
                    return true;
                }
                return false;
            }
        };

        final Window window = mDialog.getWindow();
        window.requestFeature(Window.FEATURE_NO_TITLE);
        mDialog.setCanceledOnTouchOutside(true);
        mDialog.setContentView(com.android.systemui.R.layout.volume_dialog);
        mDialog.setOnDismissListener(new OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                mActiveStreamType = -1;
                mAudioManager.forceVolumeControlStream(mActiveStreamType);
                setZenPanelVisible(false);
                mDemoIcon = 0;
                mSecondaryIconTransition.cancel();
            }
        });

        mDialog.create();

        final LayoutParams lp = window.getAttributes();
        lp.token = null;
        lp.y = res.getDimensionPixelOffset(com.android.systemui.R.dimen.volume_panel_top);
        lp.type = LayoutParams.TYPE_STATUS_BAR_PANEL;
        lp.format = PixelFormat.TRANSLUCENT;
        lp.windowAnimations = com.android.systemui.R.style.VolumePanelAnimation;
        lp.setTitle(TAG);
        window.setAttributes(lp);

        updateWidth();

        window.setBackgroundDrawable(new ColorDrawable(0x00000000));
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.addFlags(LayoutParams.FLAG_NOT_FOCUSABLE
                | LayoutParams.FLAG_NOT_TOUCH_MODAL
                | LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | LayoutParams.FLAG_HARDWARE_ACCELERATED);
        mView = window.findViewById(R.id.content);
        Interaction.register(mView, new Interaction.Callback() {
            @Override
            public void onInteraction() {
                resetTimeout();
            }
        });

        mPanel = (ViewGroup) mView.findViewById(com.android.systemui.R.id.visible_panel);
        mSliderPanel = (ViewGroup) mView.findViewById(com.android.systemui.R.id.slider_panel);
        mZenPanel = (ZenModePanel) mView.findViewById(com.android.systemui.R.id.zen_mode_panel);
        initZenModePanel();

        mToneGenerators = new ToneGenerator[AudioSystem.getNumStreamTypes()];
        mVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        mHasVibrator = mVibrator != null && mVibrator.hasVibrator();
        mVoiceCapable = context.getResources().getBoolean(R.bool.config_voice_capable);

        if (mZenController != null && !useMasterVolume) {
            mZenModeAvailable = mZenController.isZenAvailable();
            mNotificationEffectsSuppressor = mZenController.getEffectsSuppressor();
            mZenController.addCallback(mZenCallback);
        }

        final boolean masterVolumeOnly = res.getBoolean(R.bool.config_useMasterVolume);
        final boolean masterVolumeKeySounds = res.getBoolean(R.bool.config_useVolumeKeySounds);
        mPlayMasterStreamTones = masterVolumeOnly && masterVolumeKeySounds;

        registerReceiver();
    }

    public void onConfigurationChanged(Configuration newConfig) {
        updateWidth();
        if (mZenPanel != null) {
            mZenPanel.updateLocale();
        }
    }

    private void updateWidth() {
        final Resources res = mContext.getResources();
        final LayoutParams lp = mDialog.getWindow().getAttributes();
        lp.width = res.getDimensionPixelSize(com.android.systemui.R.dimen.notification_panel_width);
        lp.gravity =
                res.getInteger(com.android.systemui.R.integer.notification_panel_layout_gravity);
        mDialog.getWindow().setAttributes(lp);
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("VolumePanel state:");
        pw.print("  mTag="); pw.println(mTag);
        pw.print("  mRingIsSilent="); pw.println(mRingIsSilent);
        pw.print("  mVoiceCapable="); pw.println(mVoiceCapable);
        pw.print("  mHasVibrator="); pw.println(mHasVibrator);
        pw.print("  mZenModeAvailable="); pw.println(mZenModeAvailable);
        pw.print("  mZenPanelExpanded="); pw.println(mZenPanelExpanded);
        pw.print("  mNotificationEffectsSuppressor="); pw.println(mNotificationEffectsSuppressor);
        pw.print("  mTimeoutDelay="); pw.println(mTimeoutDelay);
        pw.print("  mDisabledAlpha="); pw.println(mDisabledAlpha);
        pw.print("  mLastRingerMode="); pw.println(mLastRingerMode);
        pw.print("  mLastRingerProgress="); pw.println(mLastRingerProgress);
        pw.print("  mPlayMasterStreamTones="); pw.println(mPlayMasterStreamTones);
        pw.print("  isShowing()="); pw.println(isShowing());
        pw.print("  mCallback="); pw.println(mCallback);
        pw.print("  sConfirmSafeVolumeDialog=");
        pw.println(sSafetyWarning != null ? "<not null>" : null);
        pw.print("  mActiveStreamType="); pw.println(mActiveStreamType);
        pw.print("  mStreamControls=");
        if (mStreamControls == null) {
            pw.println("null");
        } else {
            final int N = mStreamControls.size();
            pw.print("<size "); pw.print(N); pw.println('>');
            for (int i = 0; i < N; i++) {
                final StreamControl sc = mStreamControls.valueAt(i);
                pw.print("    stream "); pw.print(sc.streamType); pw.print(":");
                if (sc.seekbarView != null) {
                    pw.print(" progress="); pw.print(sc.seekbarView.getProgress());
                    pw.print(" of "); pw.print(sc.seekbarView.getMax());
                    if (!sc.seekbarView.isEnabled()) pw.print(" (disabled)");
                }
                if (sc.icon != null && sc.icon.isClickable()) pw.print(" (clickable)");
                pw.println();
            }
        }
        if (mZenPanel != null) {
            mZenPanel.dump(fd, pw, args);
        }
    }

    private void initZenModePanel() {
        mZenPanel.init(mZenController);
        mZenPanel.setCallback(new ZenModePanel.Callback() {
            @Override
            public void onMoreSettings() {
                if (mCallback != null) {
                    mCallback.onZenSettings();
                }
            }

            @Override
            public void onInteraction() {
                resetTimeout();
            }

            @Override
            public void onExpanded(boolean expanded) {
                if (mZenPanelExpanded == expanded) return;
                mZenPanelExpanded = expanded;
                updateTimeoutDelay();
                resetTimeout();
            }
        });
    }

    private void setLayoutDirection(int layoutDirection) {
        mPanel.setLayoutDirection(layoutDirection);
        updateStates();
    }

    private void registerReceiver() {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        filter.addAction(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent.getAction();

                if (AudioManager.RINGER_MODE_CHANGED_ACTION.equals(action)) {
                    removeMessages(MSG_RINGER_MODE_CHANGED);
                    sendEmptyMessage(MSG_RINGER_MODE_CHANGED);
                }

                if (AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION.equals(action)) {
                    removeMessages(MSG_INTERNAL_RINGER_MODE_CHANGED);
                    sendEmptyMessage(MSG_INTERNAL_RINGER_MODE_CHANGED);
                }

                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    postDismiss(0);
                }
            }
        }, filter);
    }

    private boolean isMuted(int streamType) {
        if (streamType == STREAM_MASTER) {
            return mAudioManager.isMasterMute();
        } else if (streamType == STREAM_REMOTE_MUSIC) {
            // TODO do we need to support a distinct mute property for remote?
            return false;
        } else {
            return mAudioManager.isStreamMute(streamType);
        }
    }

    private int getStreamMaxVolume(int streamType) {
        if (streamType == STREAM_MASTER) {
            return mAudioManager.getMasterMaxVolume();
        } else if (streamType == STREAM_REMOTE_MUSIC) {
            if (mStreamControls != null) {
                StreamControl sc = mStreamControls.get(streamType);
                if (sc != null && sc.controller != null) {
                    PlaybackInfo ai = sc.controller.getPlaybackInfo();
                    return ai.getMaxVolume();
                }
            }
            return -1;
        } else {
            return mAudioManager.getStreamMaxVolume(streamType);
        }
    }

    private int getStreamVolume(int streamType) {
        if (streamType == STREAM_MASTER) {
            return mAudioManager.getMasterVolume();
        } else if (streamType == STREAM_REMOTE_MUSIC) {
            if (mStreamControls != null) {
                StreamControl sc = mStreamControls.get(streamType);
                if (sc != null && sc.controller != null) {
                    PlaybackInfo ai = sc.controller.getPlaybackInfo();
                    return ai.getCurrentVolume();
                }
            }
            return -1;
        } else {
            return mAudioManager.getStreamVolume(streamType);
        }
    }

    private void setStreamVolume(StreamControl sc, int index, int flags) {
        if (sc.streamType == STREAM_REMOTE_MUSIC) {
            if (sc.controller != null) {
                sc.controller.setVolumeTo(index, flags);
            } else {
                Log.w(mTag, "Adjusting remote volume without a controller!");
            }
        } else if (getStreamVolume(sc.streamType) != index) {
            if (sc.streamType == STREAM_MASTER) {
                mAudioManager.setMasterVolume(index, flags);
            } else {
                mAudioManager.setStreamVolume(sc.streamType, index, flags);
            }
        }
    }

    private void createSliders() {
        final Resources res = mContext.getResources();
        final LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);

        mStreamControls = new SparseArray<StreamControl>(STREAMS.length);

        final StreamResources notificationStream = StreamResources.NotificationStream;
        for (int i = 0; i < STREAMS.length; i++) {
            StreamResources streamRes = STREAMS[i];

            final int streamType = streamRes.streamType;
            final boolean isNotification = isNotificationOrRing(streamType);

            final StreamControl sc = new StreamControl();
            sc.streamType = streamType;
            sc.group = (ViewGroup) inflater.inflate(
                    com.android.systemui.R.layout.volume_panel_item, null);
            sc.group.setTag(sc);
            sc.icon = (ImageView) sc.group.findViewById(com.android.systemui.R.id.stream_icon);
            sc.icon.setTag(sc);
            sc.icon.setContentDescription(res.getString(streamRes.descRes));
            sc.iconRes = streamRes.iconRes;
            sc.iconMuteRes = streamRes.iconMuteRes;
            sc.icon.setImageResource(sc.iconRes);
            sc.icon.setClickable(isNotification && mHasVibrator);
            if (isNotification) {
                if (mHasVibrator) {
                    sc.icon.setSoundEffectsEnabled(false);
                    sc.iconMuteRes = com.android.systemui.R.drawable.ic_ringer_vibrate;
                    sc.icon.setOnClickListener(new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            resetTimeout();
                            toggleRinger(sc);
                        }
                    });
                }
                sc.iconSuppressedRes = com.android.systemui.R.drawable.ic_ringer_mute;
            }
            sc.seekbarView = (SeekBar) sc.group.findViewById(com.android.systemui.R.id.seekbar);
            sc.suppressorView =
                    (TextView) sc.group.findViewById(com.android.systemui.R.id.suppressor);
            sc.suppressorView.setVisibility(View.GONE);
            final boolean showSecondary = !isNotification && notificationStream.show;
            sc.divider = sc.group.findViewById(com.android.systemui.R.id.divider);
            sc.secondaryIcon = (ImageView) sc.group
                    .findViewById(com.android.systemui.R.id.secondary_icon);
            sc.secondaryIcon.setImageResource(com.android.systemui.R.drawable.ic_ringer_audible);
            sc.secondaryIcon.setContentDescription(res.getString(notificationStream.descRes));
            sc.secondaryIcon.setClickable(showSecondary);
            sc.divider.setVisibility(showSecondary ? View.VISIBLE : View.GONE);
            sc.secondaryIcon.setVisibility(showSecondary ? View.VISIBLE : View.GONE);
            if (showSecondary) {
                sc.secondaryIcon.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        mSecondaryIconTransition.start(sc);
                    }
                });
            }
            final int plusOne = (streamType == AudioSystem.STREAM_BLUETOOTH_SCO ||
                    streamType == AudioSystem.STREAM_VOICE_CALL) ? 1 : 0;
            sc.seekbarView.setMax(getStreamMaxVolume(streamType) + plusOne);
            sc.seekbarView.setOnSeekBarChangeListener(mSeekListener);
            sc.seekbarView.setTag(sc);
            mStreamControls.put(streamType, sc);
        }
    }

    private void toggleRinger(StreamControl sc) {
        if (!mHasVibrator) return;
        if (mAudioManager.getRingerModeInternal() == AudioManager.RINGER_MODE_NORMAL) {
            mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_VIBRATE);
            postVolumeChanged(sc.streamType, AudioManager.FLAG_SHOW_UI | AudioManager.FLAG_VIBRATE);
        } else {
            mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL);
            postVolumeChanged(sc.streamType, AudioManager.FLAG_PLAY_SOUND);
        }
    }

    private void reorderSliders(int activeStreamType) {
        mSliderPanel.removeAllViews();

        final StreamControl active = mStreamControls.get(activeStreamType);
        if (active == null) {
            Log.e(TAG, "Missing stream type! - " + activeStreamType);
            mActiveStreamType = -1;
        } else {
            mSliderPanel.addView(active.group);
            mActiveStreamType = activeStreamType;
            active.group.setVisibility(View.VISIBLE);
            updateSlider(active, true /*forceReloadIcon*/);
            updateTimeoutDelay();
            updateZenPanelVisible();
        }
    }

    private void updateSliderProgress(StreamControl sc, int progress) {
        final boolean isRinger = isNotificationOrRing(sc.streamType);
        if (isRinger && mAudioManager.getRingerModeInternal() == AudioManager.RINGER_MODE_SILENT) {
            progress = mLastRingerProgress;
        }
        if (progress < 0) {
            progress = getStreamVolume(sc.streamType);
        }
        sc.seekbarView.setProgress(progress);
        if (isRinger) {
            mLastRingerProgress = progress;
        }
    }

    private void updateSliderIcon(StreamControl sc, boolean muted) {
        ComponentName suppressor = null;
        if (isNotificationOrRing(sc.streamType)) {
            suppressor = mNotificationEffectsSuppressor;
            int ringerMode = mAudioManager.getRingerModeInternal();
            if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
                ringerMode = mLastRingerMode;
            } else {
                mLastRingerMode = ringerMode;
            }
            if (mHasVibrator) {
                muted = ringerMode == AudioManager.RINGER_MODE_VIBRATE;
            } else {
                muted = false;
            }
        }
        sc.icon.setImageResource(mDemoIcon != 0 ? mDemoIcon
                : suppressor != null ? sc.iconSuppressedRes
                : muted ? sc.iconMuteRes
                : sc.iconRes);
    }

    private void updateSliderSuppressor(StreamControl sc) {
        final ComponentName suppressor = isNotificationOrRing(sc.streamType)
                ? mNotificationEffectsSuppressor : null;
        if (suppressor == null) {
            sc.seekbarView.setVisibility(View.VISIBLE);
            sc.suppressorView.setVisibility(View.GONE);
        } else {
            sc.seekbarView.setVisibility(View.GONE);
            sc.suppressorView.setVisibility(View.VISIBLE);
            sc.suppressorView.setText(mContext.getString(R.string.muted_by,
                    getSuppressorCaption(suppressor)));
        }
    }

    private String getSuppressorCaption(ComponentName suppressor) {
        final PackageManager pm = mContext.getPackageManager();
        try {
            final ServiceInfo info = pm.getServiceInfo(suppressor, 0);
            if (info != null) {
                final CharSequence seq = info.loadLabel(pm);
                if (seq != null) {
                    final String str = seq.toString().trim();
                    if (str.length() > 0) {
                        return str;
                    }
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "Error loading suppressor caption", e);
        }
        return suppressor.getPackageName();
    }

    /** Update the mute and progress state of a slider */
    private void updateSlider(StreamControl sc, boolean forceReloadIcon) {
        updateSliderProgress(sc, -1);
        final boolean muted = isMuted(sc.streamType);
        if (forceReloadIcon) {
            sc.icon.setImageDrawable(null);
        }
        updateSliderIcon(sc, muted);
        updateSliderEnabled(sc, muted, false);
        updateSliderSuppressor(sc);
    }

    private void updateSliderEnabled(final StreamControl sc, boolean muted, boolean fixedVolume) {
        final boolean wasEnabled = sc.seekbarView.isEnabled();
        final boolean isRinger = isNotificationOrRing(sc.streamType);
        if (sc.streamType == STREAM_REMOTE_MUSIC) {
            // never disable touch interactions for remote playback, the muting is not tied to
            // the state of the phone.
            sc.seekbarView.setEnabled(!fixedVolume);
        } else if (isRinger && mNotificationEffectsSuppressor != null) {
            sc.icon.setEnabled(true);
            sc.icon.setAlpha(1f);
            sc.icon.setClickable(false);
        } else if (isRinger
                && mAudioManager.getRingerModeInternal() == AudioManager.RINGER_MODE_SILENT) {
            sc.seekbarView.setEnabled(false);
            sc.icon.setEnabled(false);
            sc.icon.setAlpha(mDisabledAlpha);
            sc.icon.setClickable(false);
        } else if (fixedVolume ||
                (sc.streamType != mAudioManager.getMasterStreamType() && !isRinger && muted) ||
                (sSafetyWarning != null)) {
            sc.seekbarView.setEnabled(false);
        } else {
            sc.seekbarView.setEnabled(true);
            sc.icon.setEnabled(true);
            sc.icon.setAlpha(1f);
        }
        // show the silent hint when the disabled slider is touched in silent mode
        if (isRinger && wasEnabled != sc.seekbarView.isEnabled()) {
            if (sc.seekbarView.isEnabled()) {
                sc.group.setOnTouchListener(null);
                sc.icon.setClickable(mHasVibrator);
            } else {
                final View.OnTouchListener showHintOnTouch = new View.OnTouchListener() {
                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        resetTimeout();
                        showSilentHint();
                        return false;
                    }
                };
                sc.group.setOnTouchListener(showHintOnTouch);
            }
        }
    }

    private void showSilentHint() {
        if (mZenPanel != null) {
            mZenPanel.showSilentHint();
        }
    }

    private void showVibrateHint() {
        final StreamControl active = mStreamControls.get(mActiveStreamType);
        if (active != null) {
            mIconPulser.start(active.icon);
            if (!hasMessages(MSG_VIBRATE)) {
                sendEmptyMessageDelayed(MSG_VIBRATE, VIBRATE_DELAY);
            }
        }
    }

    private static boolean isNotificationOrRing(int streamType) {
        return streamType == AudioManager.STREAM_RING
                || streamType == AudioManager.STREAM_NOTIFICATION;
    }

    public void setCallback(Callback callback) {
        mCallback = callback;
    }

    private void updateTimeoutDelay() {
        mTimeoutDelay = mDemoIcon != 0 ? TIMEOUT_DELAY_EXPANDED
                : sSafetyWarning != null ? TIMEOUT_DELAY_SAFETY_WARNING
                : mActiveStreamType == AudioManager.STREAM_MUSIC ? TIMEOUT_DELAY_SHORT
                : mZenPanelExpanded ? TIMEOUT_DELAY_EXPANDED
                : isZenPanelVisible() ? TIMEOUT_DELAY_COLLAPSED
                : TIMEOUT_DELAY;
    }

    private boolean isZenPanelVisible() {
        return mZenPanel != null && mZenPanel.getVisibility() == View.VISIBLE;
    }

    private void setZenPanelVisible(boolean visible) {
        if (LOGD) Log.d(mTag, "setZenPanelVisible " + visible + " mZenPanel=" + mZenPanel);
        final boolean changing = visible != isZenPanelVisible();
        if (visible) {
            mZenPanel.setHidden(false);
            resetTimeout();
        } else {
            mZenPanel.setHidden(true);
        }
        if (changing) {
            updateTimeoutDelay();
            resetTimeout();
        }
    }

    private void updateStates() {
        final int count = mSliderPanel.getChildCount();
        for (int i = 0; i < count; i++) {
            StreamControl sc = (StreamControl) mSliderPanel.getChildAt(i).getTag();
            updateSlider(sc, true /*forceReloadIcon*/);
        }
    }

    private void updateActiveSlider() {
        final StreamControl active = mStreamControls.get(mActiveStreamType);
        if (active != null) {
            updateSlider(active, false /*forceReloadIcon*/);
        }
    }

    private void updateZenPanelVisible() {
        setZenPanelVisible(mZenModeAvailable && isNotificationOrRing(mActiveStreamType));
    }

    public void postVolumeChanged(int streamType, int flags) {
        if (hasMessages(MSG_VOLUME_CHANGED)) return;
        synchronized (this) {
            if (mStreamControls == null) {
                createSliders();
            }
        }
        removeMessages(MSG_FREE_RESOURCES);
        obtainMessage(MSG_VOLUME_CHANGED, streamType, flags).sendToTarget();
    }

    public void postRemoteVolumeChanged(MediaController controller, int flags) {
        if (hasMessages(MSG_REMOTE_VOLUME_CHANGED)) return;
        synchronized (this) {
            if (mStreamControls == null) {
                createSliders();
            }
        }
        removeMessages(MSG_FREE_RESOURCES);
        obtainMessage(MSG_REMOTE_VOLUME_CHANGED, flags, 0, controller).sendToTarget();
    }

    public void postRemoteSliderVisibility(boolean visible) {
        obtainMessage(MSG_SLIDER_VISIBILITY_CHANGED,
                STREAM_REMOTE_MUSIC, visible ? 1 : 0).sendToTarget();
    }

    /**
     * Called by AudioService when it has received new remote playback information that
     * would affect the VolumePanel display (mainly volumes). The difference with
     * {@link #postRemoteVolumeChanged(int, int)} is that the handling of the posted message
     * (MSG_REMOTE_VOLUME_UPDATE_IF_SHOWN) will only update the volume slider if it is being
     * displayed.
     * This special code path is due to the fact that remote volume updates arrive to AudioService
     * asynchronously. So after AudioService has sent the volume update (which should be treated
     * as a request to update the volume), the application will likely set a new volume. If the UI
     * is still up, we need to refresh the display to show this new value.
     */
    public void postHasNewRemotePlaybackInfo() {
        if (hasMessages(MSG_REMOTE_VOLUME_UPDATE_IF_SHOWN)) return;
        // don't create or prevent resources to be freed, if they disappear, this update came too
        //   late and shouldn't warrant the panel to be displayed longer
        obtainMessage(MSG_REMOTE_VOLUME_UPDATE_IF_SHOWN).sendToTarget();
    }

    public void postMasterVolumeChanged(int flags) {
        postVolumeChanged(STREAM_MASTER, flags);
    }

    public void postMuteChanged(int streamType, int flags) {
        if (hasMessages(MSG_VOLUME_CHANGED)) return;
        synchronized (this) {
            if (mStreamControls == null) {
                createSliders();
            }
        }
        removeMessages(MSG_FREE_RESOURCES);
        obtainMessage(MSG_MUTE_CHANGED, streamType, flags).sendToTarget();
    }

    public void postMasterMuteChanged(int flags) {
        postMuteChanged(STREAM_MASTER, flags);
    }

    public void postDisplaySafeVolumeWarning(int flags) {
        if (hasMessages(MSG_DISPLAY_SAFE_VOLUME_WARNING)) return;
        obtainMessage(MSG_DISPLAY_SAFE_VOLUME_WARNING, flags, 0).sendToTarget();
    }

    public void postDismiss(long delay) {
        forceTimeout(delay);
    }

    public void postLayoutDirection(int layoutDirection) {
        removeMessages(MSG_LAYOUT_DIRECTION);
        obtainMessage(MSG_LAYOUT_DIRECTION, layoutDirection, 0).sendToTarget();
    }

    private static String flagsToString(int flags) {
        return flags == 0 ? "0" : (flags + "=" + AudioManager.flagsToString(flags));
    }

    private static String streamToString(int stream) {
        return AudioService.streamToString(stream);
    }

    /**
     * Override this if you have other work to do when the volume changes (for
     * example, vibrating, playing a sound, etc.). Make sure to call through to
     * the superclass implementation.
     */
    protected void onVolumeChanged(int streamType, int flags) {

        if (LOGD) Log.d(mTag, "onVolumeChanged(streamType: " + streamToString(streamType)
                + ", flags: " + flagsToString(flags) + ")");

        if ((flags & AudioManager.FLAG_SHOW_UI) != 0) {
            synchronized (this) {
                if (mActiveStreamType != streamType) {
                    reorderSliders(streamType);
                }
                onShowVolumeChanged(streamType, flags, null);
            }
        }

        if ((flags & AudioManager.FLAG_PLAY_SOUND) != 0 && ! mRingIsSilent) {
            removeMessages(MSG_PLAY_SOUND);
            sendMessageDelayed(obtainMessage(MSG_PLAY_SOUND, streamType, flags), PLAY_SOUND_DELAY);
        }

        if ((flags & AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE) != 0) {
            removeMessages(MSG_PLAY_SOUND);
            removeMessages(MSG_VIBRATE);
            onStopSounds();
        }

        removeMessages(MSG_FREE_RESOURCES);
        sendMessageDelayed(obtainMessage(MSG_FREE_RESOURCES), FREE_DELAY);
        resetTimeout();
    }

    protected void onMuteChanged(int streamType, int flags) {

        if (LOGD) Log.d(mTag, "onMuteChanged(streamType: " + streamToString(streamType)
                + ", flags: " + flagsToString(flags) + ")");

        StreamControl sc = mStreamControls.get(streamType);
        if (sc != null) {
            updateSliderIcon(sc, isMuted(sc.streamType));
        }

        onVolumeChanged(streamType, flags);
    }

    protected void onShowVolumeChanged(int streamType, int flags, MediaController controller) {
        int index = getStreamVolume(streamType);

        mRingIsSilent = false;

        if (LOGD) {
            Log.d(mTag, "onShowVolumeChanged(streamType: " + streamToString(streamType)
                    + ", flags: " + flagsToString(flags) + "), index: " + index);
        }

        // get max volume for progress bar

        int max = getStreamMaxVolume(streamType);
        StreamControl sc = mStreamControls.get(streamType);

        switch (streamType) {

            case AudioManager.STREAM_RING: {
                Uri ringuri = RingtoneManager.getActualDefaultRingtoneUri(
                        mContext, RingtoneManager.TYPE_RINGTONE);
                if (ringuri == null) {
                    mRingIsSilent = true;
                }
                break;
            }

            case AudioManager.STREAM_MUSIC: {
                // Special case for when Bluetooth is active for music
                if ((mAudioManager.getDevicesForStream(AudioManager.STREAM_MUSIC) &
                        (AudioManager.DEVICE_OUT_BLUETOOTH_A2DP |
                        AudioManager.DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES |
                        AudioManager.DEVICE_OUT_BLUETOOTH_A2DP_SPEAKER)) != 0) {
                    setMusicIcon(IC_AUDIO_BT, IC_AUDIO_BT_MUTE);
                } else {
                    setMusicIcon(IC_AUDIO_VOL, IC_AUDIO_VOL_MUTE);
                }
                break;
            }

            case AudioManager.STREAM_VOICE_CALL: {
                /*
                 * For in-call voice call volume, there is no inaudible volume.
                 * Rescale the UI control so the progress bar doesn't go all
                 * the way to zero and don't show the mute icon.
                 */
                index++;
                max++;
                break;
            }

            case AudioManager.STREAM_ALARM: {
                break;
            }

            case AudioManager.STREAM_NOTIFICATION: {
                Uri ringuri = RingtoneManager.getActualDefaultRingtoneUri(
                        mContext, RingtoneManager.TYPE_NOTIFICATION);
                if (ringuri == null) {
                    mRingIsSilent = true;
                }
                break;
            }

            case AudioManager.STREAM_BLUETOOTH_SCO: {
                /*
                 * For in-call voice call volume, there is no inaudible volume.
                 * Rescale the UI control so the progress bar doesn't go all
                 * the way to zero and don't show the mute icon.
                 */
                index++;
                max++;
                break;
            }

            case STREAM_REMOTE_MUSIC: {
                if (controller == null && sc != null) {
                    // If we weren't passed one try using the last one set.
                    controller = sc.controller;
                }
                if (controller == null) {
                    // We still don't have one, ignore the command.
                    Log.w(mTag, "sent remote volume change without a controller!");
                } else {
                    PlaybackInfo vi = controller.getPlaybackInfo();
                    index = vi.getCurrentVolume();
                    max = vi.getMaxVolume();
                    if ((vi.getVolumeControl() & VolumeProvider.VOLUME_CONTROL_FIXED) != 0) {
                        // if the remote volume is fixed add the flag for the UI
                        flags |= AudioManager.FLAG_FIXED_VOLUME;
                    }
                }
                if (LOGD) { Log.d(mTag, "showing remote volume "+index+" over "+ max); }
                break;
            }
        }

        if (sc != null) {
            if (streamType == STREAM_REMOTE_MUSIC && controller != sc.controller) {
                if (sc.controller != null) {
                    sc.controller.unregisterCallback(mMediaControllerCb);
                }
                sc.controller = controller;
                if (controller != null) {
                    sc.controller.registerCallback(mMediaControllerCb);
                }
            }
            if (sc.seekbarView.getMax() != max) {
                sc.seekbarView.setMax(max);
            }
            updateSliderProgress(sc, index);
            final boolean muted = isMuted(streamType);
            updateSliderEnabled(sc, muted, (flags & AudioManager.FLAG_FIXED_VOLUME) != 0);
            if (isNotificationOrRing(streamType)) {
                // check for secondary-icon transition completion
                if (mSecondaryIconTransition.isRunning()) {
                    mSecondaryIconTransition.cancel();  // safe to reset
                    sc.seekbarView.setAlpha(0); sc.seekbarView.animate().alpha(1);
                    mZenPanel.setAlpha(0); mZenPanel.animate().alpha(1);
                }
                updateSliderIcon(sc, muted);
            }
        }

        if (!isShowing()) {
            int stream = (streamType == STREAM_REMOTE_MUSIC) ? -1 : streamType;
            // when the stream is for remote playback, use -1 to reset the stream type evaluation
            if (stream != STREAM_MASTER) {
                mAudioManager.forceVolumeControlStream(stream);
            }
            mDialog.show();
            if (mCallback != null) {
                mCallback.onVisible(true);
            }
            announceDialogShown();
        }

        // Do a little vibrate if applicable (only when going into vibrate mode)
        if ((streamType != STREAM_REMOTE_MUSIC) &&
                ((flags & AudioManager.FLAG_VIBRATE) != 0) &&
                isNotificationOrRing(streamType) &&
                mAudioManager.getRingerModeInternal() == AudioManager.RINGER_MODE_VIBRATE) {
            sendMessageDelayed(obtainMessage(MSG_VIBRATE), VIBRATE_DELAY);
        }

        // Pulse the zen icon if an adjustment was suppressed due to silent mode.
        if ((flags & AudioManager.FLAG_SHOW_SILENT_HINT) != 0) {
            showSilentHint();
        }

        // Pulse the slider icon & vibrate if an adjustment down was suppressed due to vibrate mode.
        if ((flags & AudioManager.FLAG_SHOW_VIBRATE_HINT) != 0) {
            showVibrateHint();
        }
    }

    private void announceDialogShown() {
        mView.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
    }

    private boolean isShowing() {
        return mDialog.isShowing();
    }

    protected void onPlaySound(int streamType, int flags) {

        if (hasMessages(MSG_STOP_SOUNDS)) {
            removeMessages(MSG_STOP_SOUNDS);
            // Force stop right now
            onStopSounds();
        }

        synchronized (this) {
            ToneGenerator toneGen = getOrCreateToneGenerator(streamType);
            if (toneGen != null) {
                toneGen.startTone(ToneGenerator.TONE_PROP_BEEP);
                sendMessageDelayed(obtainMessage(MSG_STOP_SOUNDS), BEEP_DURATION);
            }
        }
    }

    protected void onStopSounds() {

        synchronized (this) {
            int numStreamTypes = AudioSystem.getNumStreamTypes();
            for (int i = numStreamTypes - 1; i >= 0; i--) {
                ToneGenerator toneGen = mToneGenerators[i];
                if (toneGen != null) {
                    toneGen.stopTone();
                }
            }
        }
    }

    protected void onVibrate() {

        // Make sure we ended up in vibrate ringer mode
        if (mAudioManager.getRingerModeInternal() != AudioManager.RINGER_MODE_VIBRATE) {
            return;
        }
        if (mVibrator != null) {
            mVibrator.vibrate(VIBRATE_DURATION, VIBRATION_ATTRIBUTES);
        }
    }

    protected void onRemoteVolumeChanged(MediaController controller, int flags) {
        if (LOGD) Log.d(mTag, "onRemoteVolumeChanged(controller:" + controller + ", flags: "
                + flagsToString(flags) + ")");

        if (((flags & AudioManager.FLAG_SHOW_UI) != 0) || isShowing()) {
            synchronized (this) {
                if (mActiveStreamType != STREAM_REMOTE_MUSIC) {
                    reorderSliders(STREAM_REMOTE_MUSIC);
                }
                onShowVolumeChanged(STREAM_REMOTE_MUSIC, flags, controller);
            }
        } else {
            if (LOGD) Log.d(mTag, "not calling onShowVolumeChanged(), no FLAG_SHOW_UI or no UI");
        }

        removeMessages(MSG_FREE_RESOURCES);
        sendMessageDelayed(obtainMessage(MSG_FREE_RESOURCES), FREE_DELAY);
        resetTimeout();
    }

    protected void onRemoteVolumeUpdateIfShown() {
        if (LOGD) Log.d(mTag, "onRemoteVolumeUpdateIfShown()");
        if (isShowing()
                && (mActiveStreamType == STREAM_REMOTE_MUSIC)
                && (mStreamControls != null)) {
            onShowVolumeChanged(STREAM_REMOTE_MUSIC, 0, null);
        }
    }

    /**
     * Clear the current remote stream controller.
     */
    private void clearRemoteStreamController() {
        if (mStreamControls != null) {
            StreamControl sc = mStreamControls.get(STREAM_REMOTE_MUSIC);
            if (sc != null) {
                if (sc.controller != null) {
                    sc.controller.unregisterCallback(mMediaControllerCb);
                    sc.controller = null;
                }
            }
        }
    }

    /**
     * Handler for MSG_SLIDER_VISIBILITY_CHANGED Hide or show a slider
     *
     * @param streamType can be a valid stream type value, or
     *            VolumePanel.STREAM_MASTER, or VolumePanel.STREAM_REMOTE_MUSIC
     * @param visible
     */
    synchronized protected void onSliderVisibilityChanged(int streamType, int visible) {
        if (LOGD) Log.d(mTag, "onSliderVisibilityChanged(stream="+streamType+", visi="+visible+")");
        boolean isVisible = (visible == 1);
        for (int i = STREAMS.length - 1 ; i >= 0 ; i--) {
            StreamResources streamRes = STREAMS[i];
            if (streamRes.streamType == streamType) {
                streamRes.show = isVisible;
                if (!isVisible && (mActiveStreamType == streamType)) {
                    mActiveStreamType = -1;
                }
                break;
            }
        }
    }

    protected void onDisplaySafeVolumeWarning(int flags) {
        if ((flags & (AudioManager.FLAG_SHOW_UI | AudioManager.FLAG_SHOW_UI_WARNINGS)) != 0
                || isShowing()) {
            synchronized (sSafetyWarningLock) {
                if (sSafetyWarning != null) {
                    return;
                }
                sSafetyWarning = new SafetyWarning(mContext, this, mAudioManager);
                sSafetyWarning.show();
            }
            updateStates();
        }
        if (mAccessibilityManager.isTouchExplorationEnabled()) {
            removeMessages(MSG_TIMEOUT);
        } else {
            updateTimeoutDelay();
            resetTimeout();
        }
    }

    /**
     * Lock on this VolumePanel instance as long as you use the returned ToneGenerator.
     */
    private ToneGenerator getOrCreateToneGenerator(int streamType) {
        if (streamType == STREAM_MASTER) {
            // For devices that use the master volume setting only but still want to
            // play a volume-changed tone, direct the master volume pseudostream to
            // the system stream's tone generator.
            if (mPlayMasterStreamTones) {
                streamType = AudioManager.STREAM_SYSTEM;
            } else {
                return null;
            }
        }
        synchronized (this) {
            if (mToneGenerators[streamType] == null) {
                try {
                    mToneGenerators[streamType] = new ToneGenerator(streamType, MAX_VOLUME);
                } catch (RuntimeException e) {
                    if (LOGD) {
                        Log.d(mTag, "ToneGenerator constructor failed with "
                                + "RuntimeException: " + e);
                    }
                }
            }
            return mToneGenerators[streamType];
        }
    }


    /**
     * Switch between icons because Bluetooth music is same as music volume, but with
     * different icons.
     */
    private void setMusicIcon(int resId, int resMuteId) {
        StreamControl sc = mStreamControls.get(AudioManager.STREAM_MUSIC);
        if (sc != null) {
            sc.iconRes = resId;
            sc.iconMuteRes = resMuteId;
            updateSliderIcon(sc, isMuted(sc.streamType));
        }
    }

    protected void onFreeResources() {
        synchronized (this) {
            for (int i = mToneGenerators.length - 1; i >= 0; i--) {
                if (mToneGenerators[i] != null) {
                    mToneGenerators[i].release();
                }
                mToneGenerators[i] = null;
            }
        }
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {

            case MSG_VOLUME_CHANGED: {
                onVolumeChanged(msg.arg1, msg.arg2);
                break;
            }

            case MSG_MUTE_CHANGED: {
                onMuteChanged(msg.arg1, msg.arg2);
                break;
            }

            case MSG_FREE_RESOURCES: {
                onFreeResources();
                break;
            }

            case MSG_STOP_SOUNDS: {
                onStopSounds();
                break;
            }

            case MSG_PLAY_SOUND: {
                onPlaySound(msg.arg1, msg.arg2);
                break;
            }

            case MSG_VIBRATE: {
                onVibrate();
                break;
            }

            case MSG_TIMEOUT: {
                if (isShowing()) {
                    mDialog.dismiss();
                    clearRemoteStreamController();
                    mActiveStreamType = -1;
                    if (mCallback != null) {
                        mCallback.onVisible(false);
                    }
                }
                synchronized (sSafetyWarningLock) {
                    if (sSafetyWarning != null) {
                        if (LOGD) Log.d(mTag, "SafetyWarning timeout");
                        sSafetyWarning.dismiss();
                    }
                }
                break;
            }

            case MSG_RINGER_MODE_CHANGED:
            case MSG_INTERNAL_RINGER_MODE_CHANGED:
            case MSG_NOTIFICATION_EFFECTS_SUPPRESSOR_CHANGED: {
                if (isShowing()) {
                    updateActiveSlider();
                }
                break;
            }

            case MSG_REMOTE_VOLUME_CHANGED: {
                onRemoteVolumeChanged((MediaController) msg.obj, msg.arg1);
                break;
            }

            case MSG_REMOTE_VOLUME_UPDATE_IF_SHOWN:
                onRemoteVolumeUpdateIfShown();
                break;

            case MSG_SLIDER_VISIBILITY_CHANGED:
                onSliderVisibilityChanged(msg.arg1, msg.arg2);
                break;

            case MSG_DISPLAY_SAFE_VOLUME_WARNING:
                onDisplaySafeVolumeWarning(msg.arg1);
                break;

            case MSG_LAYOUT_DIRECTION:
                setLayoutDirection(msg.arg1);
                break;

            case MSG_ZEN_MODE_AVAILABLE_CHANGED:
                mZenModeAvailable = msg.arg1 != 0;
                updateZenPanelVisible();
                break;

            case MSG_USER_ACTIVITY:
                if (mCallback != null) {
                    mCallback.onInteraction();
                }
                break;
        }
    }

    private void resetTimeout() {
        final boolean touchExploration = mAccessibilityManager.isTouchExplorationEnabled();
        if (LOGD) Log.d(mTag, "resetTimeout at " + System.currentTimeMillis()
                + " delay=" + mTimeoutDelay + " touchExploration=" + touchExploration);
        if (sSafetyWarning == null || !touchExploration) {
            removeMessages(MSG_TIMEOUT);
            sendEmptyMessageDelayed(MSG_TIMEOUT, mTimeoutDelay);
            removeMessages(MSG_USER_ACTIVITY);
            sendEmptyMessage(MSG_USER_ACTIVITY);
        }
    }

    private void forceTimeout(long delay) {
        if (LOGD) Log.d(mTag, "forceTimeout delay=" + delay + " callers=" + Debug.getCallers(3));
        removeMessages(MSG_TIMEOUT);
        sendEmptyMessageDelayed(MSG_TIMEOUT, delay);
    }

    public ZenModeController getZenController() {
        return mZenController;
    }

    @Override
    public void dispatchDemoCommand(String command, Bundle args) {
        if (!COMMAND_VOLUME.equals(command)) return;
        String icon = args.getString("icon");
        final String iconMute = args.getString("iconmute");
        final boolean mute = iconMute != null;
        icon = mute ? iconMute : icon;
        icon = icon.endsWith("Stream") ? icon : (icon + "Stream");
        final StreamResources sr = StreamResources.valueOf(icon);
        mDemoIcon = mute ? sr.iconMuteRes : sr.iconRes;
        final int forcedStreamType = StreamResources.MediaStream.streamType;
        mAudioManager.forceVolumeControlStream(forcedStreamType);
        mAudioManager.adjustStreamVolume(forcedStreamType, AudioManager.ADJUST_SAME,
                AudioManager.FLAG_SHOW_UI);
    }

    private final OnSeekBarChangeListener mSeekListener = new OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            final Object tag = seekBar.getTag();
            if (fromUser && tag instanceof StreamControl) {
                StreamControl sc = (StreamControl) tag;
                setStreamVolume(sc, progress,
                        AudioManager.FLAG_SHOW_UI | AudioManager.FLAG_VIBRATE);
            }
            resetTimeout();
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    };

    private final ZenModeController.Callback mZenCallback = new ZenModeController.Callback() {
        @Override
        public void onZenAvailableChanged(boolean available) {
            obtainMessage(MSG_ZEN_MODE_AVAILABLE_CHANGED, available ? 1 : 0, 0).sendToTarget();
        }

        @Override
        public void onEffectsSupressorChanged() {
            mNotificationEffectsSuppressor = mZenController.getEffectsSuppressor();
            sendEmptyMessage(MSG_NOTIFICATION_EFFECTS_SUPPRESSOR_CHANGED);
        }
    };

    private final MediaController.Callback mMediaControllerCb = new MediaController.Callback() {
        public void onAudioInfoChanged(PlaybackInfo info) {
            onRemoteVolumeUpdateIfShown();
        }
    };

    private final class SecondaryIconTransition extends AnimatorListenerAdapter
            implements Runnable {
        private static final int ANIMATION_TIME = 400;
        private static final int WAIT_FOR_SWITCH_TIME = 1000;

        private final int mAnimationTime = (int)(ANIMATION_TIME * ValueAnimator.getDurationScale());
        private final int mFadeOutTime = mAnimationTime / 2;
        private final int mDelayTime = mAnimationTime / 3;

        private final Interpolator mIconInterpolator =
                AnimationUtils.loadInterpolator(mContext, android.R.interpolator.fast_out_slow_in);

        private StreamControl mTarget;

        public void start(StreamControl sc) {
            if (sc == null) throw new IllegalArgumentException();
            if (LOGD) Log.d(mTag, "Secondary icon animation start");
            if (mTarget != null) {
                cancel();
            }
            mTarget = sc;
            mTimeoutDelay = mAnimationTime + WAIT_FOR_SWITCH_TIME;
            resetTimeout();
            mTarget.secondaryIcon.setClickable(false);
            final int N = mTarget.group.getChildCount();
            for (int i = 0; i < N; i++) {
                final View child = mTarget.group.getChildAt(i);
                if (child != mTarget.secondaryIcon) {
                    child.animate().alpha(0).setDuration(mFadeOutTime).start();
                }
            }
            mTarget.secondaryIcon.animate()
                    .translationXBy(mTarget.icon.getX() - mTarget.secondaryIcon.getX())
                    .setInterpolator(mIconInterpolator)
                    .setStartDelay(mDelayTime)
                    .setDuration(mAnimationTime - mDelayTime)
                    .setListener(this)
                    .start();
        }

        public boolean isRunning() {
            return mTarget != null;
        }

        public void cancel() {
            if (mTarget == null) return;
            mTarget.secondaryIcon.setClickable(true);
            final int N = mTarget.group.getChildCount();
            for (int i = 0; i < N; i++) {
                final View child = mTarget.group.getChildAt(i);
                if (child != mTarget.secondaryIcon) {
                    child.animate().cancel();
                    child.setAlpha(1);
                }
            }
            mTarget.secondaryIcon.animate().cancel();
            mTarget.secondaryIcon.setTranslationX(0);
            mTarget = null;
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            if (mTarget == null) return;
            AsyncTask.execute(this);
        }

        @Override
        public void run() {
            if (mTarget == null) return;
            if (LOGD) Log.d(mTag, "Secondary icon animation complete, show notification slider");
            mAudioManager.forceVolumeControlStream(StreamResources.NotificationStream.streamType);
            mAudioManager.adjustStreamVolume(StreamResources.NotificationStream.streamType,
                    AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI);
        }
    }

    public interface Callback {
        void onZenSettings();
        void onInteraction();
        void onVisible(boolean visible);
    }
}
