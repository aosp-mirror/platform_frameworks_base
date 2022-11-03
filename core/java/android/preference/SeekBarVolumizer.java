/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.preference;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.app.NotificationManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.audiopolicy.AudioProductStrategy;
import android.media.audiopolicy.AudioVolumeGroup;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.preference.VolumePreference.VolumeStore;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.provider.Settings.System;
import android.service.notification.ZenModeConfig;
import android.util.Log;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.internal.os.SomeArgs;

import java.util.concurrent.TimeUnit;

/**
 * Turns a {@link SeekBar} into a volume control.
 * @hide
 *
 * @deprecated Use the <a href="{@docRoot}jetpack/androidx.html">AndroidX</a>
 *      <a href="{@docRoot}reference/androidx/preference/package-summary.html">
 *      Preference Library</a> for consistent behavior across all devices. For more information on
 *      using the AndroidX Preference Library see
 *      <a href="{@docRoot}guide/topics/ui/settings.html">Settings</a>.
 */
@Deprecated
public class SeekBarVolumizer implements OnSeekBarChangeListener, Handler.Callback {
    private static final String TAG = "SeekBarVolumizer";

    public interface Callback {
        void onSampleStarting(SeekBarVolumizer sbv);
        void onProgressChanged(SeekBar seekBar, int progress, boolean fromTouch);
        void onMuted(boolean muted, boolean zenMuted);
        /**
         * Callback reporting that the seek bar is start tracking.
         *
         * @param sbv - The seek bar that start tracking
         */
        void onStartTrackingTouch(SeekBarVolumizer sbv);

        /**
         * Callback reporting that the seek bar is stop tracking.
         *
         * @param sbv - The seek bar that stop tracking
         */
        default void onStopTrackingTouch(SeekBarVolumizer sbv) {
        }
    }

    private static final int MSG_GROUP_VOLUME_CHANGED = 1;
    private static long sStopVolumeTime = 0L;
    private final Handler mVolumeHandler = new VolumeHandler();
    private AudioAttributes mAttributes;
    private int mVolumeGroupId;

    private final AudioManager.VolumeGroupCallback mVolumeGroupCallback =
            new AudioManager.VolumeGroupCallback() {
        @Override
        public void onAudioVolumeGroupChanged(int group, int flags) {
            if (mHandler == null) {
                return;
            }
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = group;
            args.arg2 = flags;
            mVolumeHandler.sendMessage(mHandler.obtainMessage(MSG_GROUP_VOLUME_CHANGED, args));
        }
    };

    @UnsupportedAppUsage
    private final Context mContext;
    private final H mUiHandler = new H();
    private final Callback mCallback;
    private final Uri mDefaultUri;
    @UnsupportedAppUsage
    private final AudioManager mAudioManager;
    private final NotificationManager mNotificationManager;
    @UnsupportedAppUsage
    private final int mStreamType;
    private final int mMaxStreamVolume;
    private boolean mAffectedByRingerMode;
    private boolean mNotificationOrRing;
    private final Receiver mReceiver = new Receiver();

    private Handler mHandler;
    private Observer mVolumeObserver;
    @UnsupportedAppUsage
    private int mOriginalStreamVolume;
    private int mLastAudibleStreamVolume;
    // When the old handler is destroyed and a new one is created, there could be a situation where
    // this is accessed at the same time in different handlers. So, access to this field needs to be
    // synchronized.
    @GuardedBy("this")
    @UnsupportedAppUsage
    private Ringtone mRingtone;
    @UnsupportedAppUsage
    private int mLastProgress = -1;
    private boolean mMuted;
    @UnsupportedAppUsage
    private SeekBar mSeekBar;
    private int mVolumeBeforeMute = -1;
    private int mRingerMode;
    private int mZenMode;
    private boolean mPlaySample;

    private static final int MSG_SET_STREAM_VOLUME = 0;
    private static final int MSG_START_SAMPLE = 1;
    private static final int MSG_STOP_SAMPLE = 2;
    private static final int MSG_INIT_SAMPLE = 3;
    private static final int CHECK_RINGTONE_PLAYBACK_DELAY_MS = 1000;
    private static final long SET_STREAM_VOLUME_DELAY_MS = TimeUnit.MILLISECONDS.toMillis(500);
    private static final long START_SAMPLE_DELAY_MS = TimeUnit.MILLISECONDS.toMillis(500);
    private static final long DURATION_TO_START_DELAYING = TimeUnit.MILLISECONDS.toMillis(2000);

    private NotificationManager.Policy mNotificationPolicy;
    private boolean mAllowAlarms;
    private boolean mAllowMedia;
    private boolean mAllowRinger;

    @UnsupportedAppUsage
    public SeekBarVolumizer(Context context, int streamType, Uri defaultUri, Callback callback) {
        this(context, streamType, defaultUri, callback, true /* playSample */);
    }

    @RequiresPermission(Manifest.permission.READ_DEVICE_CONFIG)
    public SeekBarVolumizer(
            Context context,
            int streamType,
            Uri defaultUri,
            Callback callback,
            boolean playSample) {
        mContext = context;
        mAudioManager = context.getSystemService(AudioManager.class);
        mNotificationManager = context.getSystemService(NotificationManager.class);
        mNotificationPolicy = mNotificationManager.getConsolidatedNotificationPolicy();
        mAllowAlarms = (mNotificationPolicy.priorityCategories & NotificationManager.Policy
                .PRIORITY_CATEGORY_ALARMS) != 0;
        mAllowMedia = (mNotificationPolicy.priorityCategories & NotificationManager.Policy
                .PRIORITY_CATEGORY_MEDIA) != 0;
        mAllowRinger = !ZenModeConfig.areAllPriorityOnlyRingerSoundsMuted(
                mNotificationPolicy);
        mStreamType = streamType;
        mAffectedByRingerMode = mAudioManager.isStreamAffectedByRingerMode(mStreamType);
        mNotificationOrRing = isNotificationOrRing(mStreamType);
        if (mNotificationOrRing) {
            mRingerMode = mAudioManager.getRingerModeInternal();
        }
        mZenMode = mNotificationManager.getZenMode();

        if (hasAudioProductStrategies()) {
            mVolumeGroupId = getVolumeGroupIdForLegacyStreamType(mStreamType);
            mAttributes = getAudioAttributesForLegacyStreamType(
                    mStreamType);
        }

        mMaxStreamVolume = mAudioManager.getStreamMaxVolume(mStreamType);
        mCallback = callback;
        mOriginalStreamVolume = mAudioManager.getStreamVolume(mStreamType);
        mLastAudibleStreamVolume = mAudioManager.getLastAudibleStreamVolume(mStreamType);
        mMuted = mAudioManager.isStreamMute(mStreamType);
        mPlaySample = playSample;
        if (mCallback != null) {
            mCallback.onMuted(mMuted, isZenMuted());
        }
        if (defaultUri == null) {
            if (mStreamType == AudioManager.STREAM_RING) {
                defaultUri = Settings.System.DEFAULT_RINGTONE_URI;
            } else if (mStreamType == AudioManager.STREAM_NOTIFICATION) {
                defaultUri = Settings.System.DEFAULT_NOTIFICATION_URI;
            } else {
                defaultUri = Settings.System.DEFAULT_ALARM_ALERT_URI;
            }
        }
        mDefaultUri = defaultUri;
    }

    private boolean hasAudioProductStrategies() {
        return AudioManager.getAudioProductStrategies().size() > 0;
    }

    private int getVolumeGroupIdForLegacyStreamType(int streamType) {
        for (final AudioProductStrategy productStrategy :
                AudioManager.getAudioProductStrategies()) {
            int volumeGroupId = productStrategy.getVolumeGroupIdForLegacyStreamType(streamType);
            if (volumeGroupId != AudioVolumeGroup.DEFAULT_VOLUME_GROUP) {
                return volumeGroupId;
            }
        }

        return AudioManager.getAudioProductStrategies().stream()
                .map(strategy -> strategy.getVolumeGroupIdForAudioAttributes(
                        AudioProductStrategy.getDefaultAttributes()))
                .filter(volumeGroupId -> volumeGroupId != AudioVolumeGroup.DEFAULT_VOLUME_GROUP)
                .findFirst()
                .orElse(AudioVolumeGroup.DEFAULT_VOLUME_GROUP);
    }

    private @NonNull AudioAttributes getAudioAttributesForLegacyStreamType(int streamType) {
        for (final AudioProductStrategy productStrategy :
                AudioManager.getAudioProductStrategies()) {
            AudioAttributes aa = productStrategy.getAudioAttributesForLegacyStreamType(streamType);
            if (aa != null) {
                return aa;
            }
        }
        return new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                .setUsage(AudioAttributes.USAGE_UNKNOWN).build();
    }

    private static boolean isNotificationOrRing(int stream) {
        return stream == AudioManager.STREAM_RING || stream == AudioManager.STREAM_NOTIFICATION;
    }

    private static boolean isAlarmsStream(int stream) {
        return stream == AudioManager.STREAM_ALARM;
    }

    private static boolean isMediaStream(int stream) {
        return stream == AudioManager.STREAM_MUSIC;
    }

    public void setSeekBar(SeekBar seekBar) {
        if (mSeekBar != null) {
            mSeekBar.setOnSeekBarChangeListener(null);
        }
        mSeekBar = seekBar;
        mSeekBar.setOnSeekBarChangeListener(null);
        mSeekBar.setMax(mMaxStreamVolume);
        updateSeekBar();
        mSeekBar.setOnSeekBarChangeListener(this);
    }

    private boolean isZenMuted() {
        return mNotificationOrRing && mZenMode == Global.ZEN_MODE_ALARMS
                || mZenMode == Global.ZEN_MODE_NO_INTERRUPTIONS
                || (mZenMode == Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS
                    && ((!mAllowAlarms && isAlarmsStream(mStreamType))
                        || (!mAllowMedia && isMediaStream(mStreamType))
                        || (!mAllowRinger && isNotificationOrRing(mStreamType))));
    }

    protected void updateSeekBar() {
        final boolean zenMuted = isZenMuted();
        mSeekBar.setEnabled(!zenMuted);
        if (zenMuted) {
            mSeekBar.setProgress(mLastAudibleStreamVolume, true);
        } else if (mNotificationOrRing && mRingerMode == AudioManager.RINGER_MODE_VIBRATE) {
            /**
             * the first variable above is preserved and the conditions below are made explicit
             * so that when user attempts to slide the notification seekbar out of vibrate the
             * seekbar doesn't wrongly snap back to 0 when the streams aren't aliased
             */
            if (!DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_SYSTEMUI,
                    SystemUiDeviceConfigFlags.VOLUME_SEPARATE_NOTIFICATION, false)
                    || mStreamType == AudioManager.STREAM_RING
                    || (mStreamType == AudioManager.STREAM_NOTIFICATION && mMuted)) {
                mSeekBar.setProgress(0, true);
            }
        } else if (mMuted) {
            mSeekBar.setProgress(0, true);
        } else {
            mSeekBar.setProgress(mLastProgress > -1 ? mLastProgress : mOriginalStreamVolume, true);
        }
    }

    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_SET_STREAM_VOLUME:
                if (mMuted && mLastProgress > 0) {
                    mAudioManager.adjustStreamVolume(mStreamType, AudioManager.ADJUST_UNMUTE, 0);
                } else if (!mMuted && mLastProgress == 0) {
                    mAudioManager.adjustStreamVolume(mStreamType, AudioManager.ADJUST_MUTE, 0);
                }
                mAudioManager.setStreamVolume(mStreamType, mLastProgress,
                        AudioManager.FLAG_SHOW_UI_WARNINGS);
                break;
            case MSG_START_SAMPLE:
                if (mPlaySample) {
                    onStartSample();
                }
                break;
            case MSG_STOP_SAMPLE:
                if (mPlaySample) {
                    onStopSample();
                }
                break;
            case MSG_INIT_SAMPLE:
                if (mPlaySample) {
                    onInitSample();
                }
                break;
            default:
                Log.e(TAG, "invalid SeekBarVolumizer message: "+msg.what);
        }
        return true;
    }

    private void onInitSample() {
        synchronized (this) {
            mRingtone = RingtoneManager.getRingtone(mContext, mDefaultUri);
            if (mRingtone != null) {
                mRingtone.setStreamType(mStreamType);
            }
        }
    }

    private void postStartSample() {
        if (mHandler == null) return;
        mHandler.removeMessages(MSG_START_SAMPLE);
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_START_SAMPLE),
                isSamplePlaying() ? CHECK_RINGTONE_PLAYBACK_DELAY_MS
                        : isDelay() ? START_SAMPLE_DELAY_MS : 0);
    }

    // After stop volume it needs to add a small delay when playing volume or set stream.
    // It is because the call volume is from the earpiece and the alarm/ring/media
    // is from the speaker. If play the alarm volume or set alarm stream right after stop
    // call volume, the alarm volume on earpiece is returned then cause the volume value incorrect.
    // It needs a small delay after stop call volume to get alarm volume on speaker.
    // e.g. : If the ring volume has adjusted right after call volume stopped in 2 second
    // then delay 0.5 second to set stream or play volume ringtone.
    private boolean isDelay() {
        final long durationTime = java.lang.System.currentTimeMillis() - sStopVolumeTime;
        return durationTime >= 0 && durationTime < DURATION_TO_START_DELAYING;
    }

    private void setStopVolumeTime() {
        // set the time of stop volume
        if ((mStreamType == AudioManager.STREAM_VOICE_CALL
                || mStreamType == AudioManager.STREAM_RING
                || (DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.VOLUME_SEPARATE_NOTIFICATION, false)
                && mStreamType == AudioManager.STREAM_NOTIFICATION)
                || mStreamType == AudioManager.STREAM_ALARM)) {
            sStopVolumeTime = java.lang.System.currentTimeMillis();
        }
    }

    private void onStartSample() {
        if (!isSamplePlaying()) {
            if (mCallback != null) {
                mCallback.onSampleStarting(this);
            }

            synchronized (this) {
                if (mRingtone != null) {
                    try {
                        mRingtone.setAudioAttributes(new AudioAttributes.Builder(mRingtone
                                .getAudioAttributes())
                                .setFlags(AudioAttributes.FLAG_BYPASS_MUTE)
                                .build());
                        mRingtone.play();
                    } catch (Throwable e) {
                        Log.w(TAG, "Error playing ringtone, stream " + mStreamType, e);
                    }
                }
            }
        }
    }

    private void postStopSample() {
        if (mHandler == null) return;
        setStopVolumeTime();
        // remove pending delayed start messages
        mHandler.removeMessages(MSG_START_SAMPLE);
        mHandler.removeMessages(MSG_STOP_SAMPLE);
        mHandler.sendMessage(mHandler.obtainMessage(MSG_STOP_SAMPLE));
    }

    private void onStopSample() {
        synchronized (this) {
            if (mRingtone != null) {
                mRingtone.stop();
            }
        }
    }

    @UnsupportedAppUsage
    public void stop() {
        if (mHandler == null) return;  // already stopped
        postStopSample();
        mContext.getContentResolver().unregisterContentObserver(mVolumeObserver);
        mReceiver.setListening(false);
        if (hasAudioProductStrategies()) {
            unregisterVolumeGroupCb();
        }
        mSeekBar.setOnSeekBarChangeListener(null);
        mHandler.getLooper().quitSafely();
        mHandler = null;
        mVolumeObserver = null;
    }

    public void start() {
        if (mHandler != null) return;  // already started
        HandlerThread thread = new HandlerThread(TAG + ".CallbackHandler");
        thread.start();
        mHandler = new Handler(thread.getLooper(), this);
        mHandler.sendEmptyMessage(MSG_INIT_SAMPLE);
        mVolumeObserver = new Observer(mHandler);
        mContext.getContentResolver().registerContentObserver(
                System.getUriFor(System.VOLUME_SETTINGS_INT[mStreamType]),
                false, mVolumeObserver);
        mReceiver.setListening(true);
        if (hasAudioProductStrategies()) {
            registerVolumeGroupCb();
        }
    }

    public void revertVolume() {
        mAudioManager.setStreamVolume(mStreamType, mOriginalStreamVolume, 0);
    }

    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromTouch) {
        if (fromTouch) {
            postSetVolume(progress);
        }
        if (mCallback != null) {
            mCallback.onProgressChanged(seekBar, progress, fromTouch);
        }
    }

    private void postSetVolume(int progress) {
        if (mHandler == null) return;
        // Do the volume changing separately to give responsive UI
        mLastProgress = progress;
        mHandler.removeMessages(MSG_SET_STREAM_VOLUME);
        mHandler.removeMessages(MSG_START_SAMPLE);
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_SET_STREAM_VOLUME),
                isDelay() ? SET_STREAM_VOLUME_DELAY_MS : 0);
    }

    public void onStartTrackingTouch(SeekBar seekBar) {
        if (mCallback != null) {
            mCallback.onStartTrackingTouch(this);
        }
    }

    public void onStopTrackingTouch(SeekBar seekBar) {
        postStartSample();
        if (mCallback != null) {
            mCallback.onStopTrackingTouch(this);
        }
    }

    public boolean isSamplePlaying() {
        synchronized (this) {
            return mRingtone != null && mRingtone.isPlaying();
        }
    }

    public void startSample() {
        postStartSample();
    }

    public void stopSample() {
        postStopSample();
    }

    public SeekBar getSeekBar() {
        return mSeekBar;
    }

    public void changeVolumeBy(int amount) {
        mSeekBar.incrementProgressBy(amount);
        postSetVolume(mSeekBar.getProgress());
        postStartSample();
        mVolumeBeforeMute = -1;
    }

    public void muteVolume() {
        if (mVolumeBeforeMute != -1) {
            mSeekBar.setProgress(mVolumeBeforeMute, true);
            postSetVolume(mVolumeBeforeMute);
            postStartSample();
            mVolumeBeforeMute = -1;
        } else {
            mVolumeBeforeMute = mSeekBar.getProgress();
            mSeekBar.setProgress(0, true);
            postStopSample();
            postSetVolume(0);
        }
    }

    public void onSaveInstanceState(VolumeStore volumeStore) {
        if (mLastProgress >= 0) {
            volumeStore.volume = mLastProgress;
            volumeStore.originalVolume = mOriginalStreamVolume;
        }
    }

    public void onRestoreInstanceState(VolumeStore volumeStore) {
        if (volumeStore.volume != -1) {
            mOriginalStreamVolume = volumeStore.originalVolume;
            mLastProgress = volumeStore.volume;
            postSetVolume(mLastProgress);
        }
    }

    private final class H extends Handler {
        private static final int UPDATE_SLIDER = 1;

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == UPDATE_SLIDER) {
                if (mSeekBar != null) {
                    mLastProgress = msg.arg1;
                    mLastAudibleStreamVolume = msg.arg2;
                    final boolean muted = ((Boolean)msg.obj).booleanValue();
                    if (muted != mMuted) {
                        mMuted = muted;
                        if (mCallback != null) {
                            mCallback.onMuted(mMuted, isZenMuted());
                        }
                    }
                    updateSeekBar();
                }
            }
        }

        public void postUpdateSlider(int volume, int lastAudibleVolume, boolean mute) {
            obtainMessage(UPDATE_SLIDER, volume, lastAudibleVolume, new Boolean(mute)).sendToTarget();
        }
    }

    private void updateSlider() {
        if (mSeekBar != null && mAudioManager != null) {
            final int volume = mAudioManager.getStreamVolume(mStreamType);
            final int lastAudibleVolume = mAudioManager.getLastAudibleStreamVolume(mStreamType);
            final boolean mute = mAudioManager.isStreamMute(mStreamType);
            mUiHandler.postUpdateSlider(volume, lastAudibleVolume, mute);
        }
    }

    private final class Observer extends ContentObserver {
        public Observer(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            updateSlider();
        }
    }

    private final class Receiver extends BroadcastReceiver {
        private boolean mListening;

        public void setListening(boolean listening) {
            if (mListening == listening) return;
            mListening = listening;
            if (listening) {
                final IntentFilter filter = new IntentFilter(AudioManager.VOLUME_CHANGED_ACTION);
                filter.addAction(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION);
                filter.addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED);
                filter.addAction(NotificationManager.ACTION_NOTIFICATION_POLICY_CHANGED);
                filter.addAction(AudioManager.STREAM_DEVICES_CHANGED_ACTION);
                mContext.registerReceiver(this, filter);
            } else {
                mContext.unregisterReceiver(this);
            }
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (AudioManager.VOLUME_CHANGED_ACTION.equals(action)) {
                int streamType = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1);
                int streamValue = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, -1);
                if (hasAudioProductStrategies() && !isDelay()) {
                    updateVolumeSlider(streamType, streamValue);
                }
            } else if (AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION.equals(action)) {
                if (mNotificationOrRing) {
                    mRingerMode = mAudioManager.getRingerModeInternal();
                }
                if (mAffectedByRingerMode) {
                    updateSlider();
                }
            } else if (AudioManager.STREAM_DEVICES_CHANGED_ACTION.equals(action)) {
                int streamType = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1);
                if (hasAudioProductStrategies() && !isDelay()) {
                    int streamVolume = mAudioManager.getStreamVolume(streamType);
                    updateVolumeSlider(streamType, streamVolume);
                } else {
                    int volumeGroup = getVolumeGroupIdForLegacyStreamType(streamType);
                    if (volumeGroup != AudioVolumeGroup.DEFAULT_VOLUME_GROUP
                            && volumeGroup == mVolumeGroupId) {
                        int streamVolume = mAudioManager.getStreamVolume(streamType);
                        if (!isDelay()) {
                            updateVolumeSlider(streamType, streamVolume);
                        }
                    }
                }
            } else if (NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED.equals(action)) {
                mZenMode = mNotificationManager.getZenMode();
                updateSlider();
            } else if (NotificationManager.ACTION_NOTIFICATION_POLICY_CHANGED.equals(action)) {
                mNotificationPolicy = mNotificationManager.getConsolidatedNotificationPolicy();
                mAllowAlarms = (mNotificationPolicy.priorityCategories & NotificationManager.Policy
                        .PRIORITY_CATEGORY_ALARMS) != 0;
                mAllowMedia = (mNotificationPolicy.priorityCategories
                        & NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA) != 0;
                mAllowRinger = !ZenModeConfig.areAllPriorityOnlyRingerSoundsMuted(
                        mNotificationPolicy);
                updateSlider();
            }
        }

        private void updateVolumeSlider(int streamType, int streamValue) {
            final boolean streamMatch =  !DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_SYSTEMUI,
                    SystemUiDeviceConfigFlags.VOLUME_SEPARATE_NOTIFICATION, false)
                    && mNotificationOrRing ? isNotificationOrRing(streamType) :
                    streamType == mStreamType;
            if (mSeekBar != null && streamMatch && streamValue != -1) {
                final boolean muted = mAudioManager.isStreamMute(mStreamType)
                        || streamValue == 0;
                mUiHandler.postUpdateSlider(streamValue, mLastAudibleStreamVolume, muted);
            }
        }
    }

    private void registerVolumeGroupCb() {
        if (mVolumeGroupId != AudioVolumeGroup.DEFAULT_VOLUME_GROUP) {
            mAudioManager.registerVolumeGroupCallback(Runnable::run, mVolumeGroupCallback);
            updateSlider();
        }
    }

    private void unregisterVolumeGroupCb() {
        if (mVolumeGroupId != AudioVolumeGroup.DEFAULT_VOLUME_GROUP) {
            mAudioManager.unregisterVolumeGroupCallback(mVolumeGroupCallback);
        }
    }

    private class VolumeHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            SomeArgs args = (SomeArgs) msg.obj;
            switch (msg.what) {
                case MSG_GROUP_VOLUME_CHANGED:
                    int group = (int) args.arg1;
                    if (mVolumeGroupId != group
                            || mVolumeGroupId == AudioVolumeGroup.DEFAULT_VOLUME_GROUP) {
                        return;
                    }
                    updateSlider();
                    break;
            }
        }
    }
}
