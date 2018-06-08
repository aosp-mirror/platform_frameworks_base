/*
 * Copyright (C) 2015 The Android Open Source Project
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

import static android.media.AudioManager.RINGER_MODE_NORMAL;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.ContentObserver;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.media.IAudioService;
import android.media.IVolumeController;
import android.media.VolumePolicy;
import android.media.session.MediaController.PlaybackInfo;
import android.media.session.MediaSession.Token;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.service.notification.Condition;
import android.service.notification.ZenModeConfig;
import android.util.ArrayMap;
import android.util.Log;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.annotations.GuardedBy;
import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.SysUiServiceProvider;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.plugins.VolumeDialogController;
import com.android.systemui.qs.tiles.DndTile;
import com.android.systemui.statusbar.phone.StatusBar;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 *  Source of truth for all state / events related to the volume dialog.  No presentation.
 *
 *  All work done on a dedicated background worker thread & associated worker.
 *
 *  Methods ending in "W" must be called on the worker thread.
 */
public class VolumeDialogControllerImpl implements VolumeDialogController, Dumpable {
    private static final String TAG = Util.logTag(VolumeDialogControllerImpl.class);


    private static final int TOUCH_FEEDBACK_TIMEOUT_MS = 1000;
    private static final int DYNAMIC_STREAM_START_INDEX = 100;
    private static final int VIBRATE_HINT_DURATION = 50;
    private static final AudioAttributes SONIFICIATION_VIBRATION_ATTRIBUTES =
            new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .build();

    static final ArrayMap<Integer, Integer> STREAMS = new ArrayMap<>();
    static {
        STREAMS.put(AudioSystem.STREAM_ALARM, R.string.stream_alarm);
        STREAMS.put(AudioSystem.STREAM_BLUETOOTH_SCO, R.string.stream_bluetooth_sco);
        STREAMS.put(AudioSystem.STREAM_DTMF, R.string.stream_dtmf);
        STREAMS.put(AudioSystem.STREAM_MUSIC, R.string.stream_music);
        STREAMS.put(AudioSystem.STREAM_ACCESSIBILITY, R.string.stream_accessibility);
        STREAMS.put(AudioSystem.STREAM_NOTIFICATION, R.string.stream_notification);
        STREAMS.put(AudioSystem.STREAM_RING, R.string.stream_ring);
        STREAMS.put(AudioSystem.STREAM_SYSTEM, R.string.stream_system);
        STREAMS.put(AudioSystem.STREAM_SYSTEM_ENFORCED, R.string.stream_system_enforced);
        STREAMS.put(AudioSystem.STREAM_TTS, R.string.stream_tts);
        STREAMS.put(AudioSystem.STREAM_VOICE_CALL, R.string.stream_voice_call);
    }

    private final HandlerThread mWorkerThread;
    private final W mWorker;
    private final Context mContext;
    private AudioManager mAudio;
    private IAudioService mAudioService;
    protected StatusBar mStatusBar;
    private final NotificationManager mNoMan;
    private final SettingObserver mObserver;
    private final Receiver mReceiver = new Receiver();
    private final MediaSessions mMediaSessions;
    protected C mCallbacks = new C();
    private final State mState = new State();
    protected final MediaSessionsCallbacks mMediaSessionsCallbacksW = new MediaSessionsCallbacks();
    private final Vibrator mVibrator;
    private final boolean mHasVibrator;
    private boolean mShowA11yStream;
    private boolean mShowVolumeDialog;
    private boolean mShowSafetyWarning;
    private long mLastToggledRingerOn;
    private final NotificationManager mNotificationManager;

    private boolean mDestroyed;
    private VolumePolicy mVolumePolicy;
    private boolean mShowDndTile = true;
    @GuardedBy("this")
    private UserActivityListener mUserActivityListener;

    protected final VC mVolumeController = new VC();

    public VolumeDialogControllerImpl(Context context) {
        mContext = context.getApplicationContext();
        mNotificationManager = (NotificationManager) mContext.getSystemService(
                Context.NOTIFICATION_SERVICE);
        Events.writeEvent(mContext, Events.EVENT_COLLECTION_STARTED);
        mWorkerThread = new HandlerThread(VolumeDialogControllerImpl.class.getSimpleName());
        mWorkerThread.start();
        mWorker = new W(mWorkerThread.getLooper());
        mMediaSessions = createMediaSessions(mContext, mWorkerThread.getLooper(),
                mMediaSessionsCallbacksW);
        mAudio = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mNoMan = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        mObserver = new SettingObserver(mWorker);
        mObserver.init();
        mReceiver.init();
        mVibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
        mHasVibrator = mVibrator != null && mVibrator.hasVibrator();
        mAudioService = IAudioService.Stub.asInterface(
                ServiceManager.getService(Context.AUDIO_SERVICE));
        updateStatusBar();

        boolean accessibilityVolumeStreamActive = context.getSystemService(
                AccessibilityManager.class).isAccessibilityVolumeStreamActive();
        mVolumeController.setA11yMode(accessibilityVolumeStreamActive ?
                    VolumePolicy.A11Y_MODE_INDEPENDENT_A11Y_VOLUME :
                        VolumePolicy.A11Y_MODE_MEDIA_A11Y_VOLUME);
    }

    public AudioManager getAudioManager() {
        return mAudio;
    }

    public void dismiss() {
        mCallbacks.onDismissRequested(Events.DISMISS_REASON_VOLUME_CONTROLLER);
    }

    protected void setVolumeController() {
        try {
            mAudio.setVolumeController(mVolumeController);
        } catch (SecurityException e) {
            Log.w(TAG, "Unable to set the volume controller", e);
            return;
        }
    }

    protected void setAudioManagerStreamVolume(int stream, int level, int flag) {
        mAudio.setStreamVolume(stream, level, flag);
    }

    protected int getAudioManagerStreamVolume(int stream) {
        return mAudio.getLastAudibleStreamVolume(stream);
    }

    protected int getAudioManagerStreamMaxVolume(int stream) {
        return mAudio.getStreamMaxVolume(stream);
    }

    protected int getAudioManagerStreamMinVolume(int stream) {
        return mAudio.getStreamMinVolumeInt(stream);
    }

    public void register() {
        setVolumeController();
        setVolumePolicy(mVolumePolicy);
        showDndTile(mShowDndTile);
        try {
            mMediaSessions.init();
        } catch (SecurityException e) {
            Log.w(TAG, "No access to media sessions", e);
        }
    }

    public void setVolumePolicy(VolumePolicy policy) {
        mVolumePolicy = policy;
        if (mVolumePolicy == null) return;
        try {
            mAudio.setVolumePolicy(mVolumePolicy);
        } catch (NoSuchMethodError e) {
            Log.w(TAG, "No volume policy api");
        }
    }

    protected MediaSessions createMediaSessions(Context context, Looper looper,
            MediaSessions.Callbacks callbacks) {
        return new MediaSessions(context, looper, callbacks);
    }

    public void destroy() {
        if (D.BUG) Log.d(TAG, "destroy");
        if (mDestroyed) return;
        mDestroyed = true;
        Events.writeEvent(mContext, Events.EVENT_COLLECTION_STOPPED);
        mMediaSessions.destroy();
        mObserver.destroy();
        mReceiver.destroy();
        mWorkerThread.quitSafely();
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println(VolumeDialogControllerImpl.class.getSimpleName() + " state:");
        pw.print("  mDestroyed: "); pw.println(mDestroyed);
        pw.print("  mVolumePolicy: "); pw.println(mVolumePolicy);
        pw.print("  mState: "); pw.println(mState.toString(4));
        pw.print("  mShowDndTile: "); pw.println(mShowDndTile);
        pw.print("  mHasVibrator: "); pw.println(mHasVibrator);
        pw.print("  mRemoteStreams: "); pw.println(mMediaSessionsCallbacksW.mRemoteStreams
                .values());
        pw.print("  mShowA11yStream: "); pw.println(mShowA11yStream);
        pw.println();
        mMediaSessions.dump(pw);
    }

    public void addCallback(Callbacks callback, Handler handler) {
        mCallbacks.add(callback, handler);
        callback.onAccessibilityModeChanged(mShowA11yStream);
    }

    public void setUserActivityListener(UserActivityListener listener) {
        if (mDestroyed) return;
        synchronized (this) {
            mUserActivityListener = listener;
        }
    }

    public void removeCallback(Callbacks callback) {
        mCallbacks.remove(callback);
    }

    public void getState() {
        if (mDestroyed) return;
        mWorker.sendEmptyMessage(W.GET_STATE);
    }

    public void notifyVisible(boolean visible) {
        if (mDestroyed) return;
        mWorker.obtainMessage(W.NOTIFY_VISIBLE, visible ? 1 : 0, 0).sendToTarget();
    }

    public void userActivity() {
        if (mDestroyed) return;
        mWorker.removeMessages(W.USER_ACTIVITY);
        mWorker.sendEmptyMessage(W.USER_ACTIVITY);
    }

    public void setRingerMode(int value, boolean external) {
        if (mDestroyed) return;
        mWorker.obtainMessage(W.SET_RINGER_MODE, value, external ? 1 : 0).sendToTarget();
    }

    public void setZenMode(int value) {
        if (mDestroyed) return;
        mWorker.obtainMessage(W.SET_ZEN_MODE, value, 0).sendToTarget();
    }

    public void setExitCondition(Condition condition) {
        if (mDestroyed) return;
        mWorker.obtainMessage(W.SET_EXIT_CONDITION, condition).sendToTarget();
    }

    public void setStreamMute(int stream, boolean mute) {
        if (mDestroyed) return;
        mWorker.obtainMessage(W.SET_STREAM_MUTE, stream, mute ? 1 : 0).sendToTarget();
    }

    public void setStreamVolume(int stream, int level) {
        if (mDestroyed) return;
        mWorker.obtainMessage(W.SET_STREAM_VOLUME, stream, level).sendToTarget();
    }

    public void setActiveStream(int stream) {
        if (mDestroyed) return;
        mWorker.obtainMessage(W.SET_ACTIVE_STREAM, stream, 0).sendToTarget();
    }

    public void setEnableDialogs(boolean volumeUi, boolean safetyWarning) {
      mShowVolumeDialog = volumeUi;
      mShowSafetyWarning = safetyWarning;
    }

    @Override
    public void scheduleTouchFeedback() {
        mLastToggledRingerOn = System.currentTimeMillis();
    }

    private void playTouchFeedback() {
        if (System.currentTimeMillis() - mLastToggledRingerOn < TOUCH_FEEDBACK_TIMEOUT_MS) {
            try {
                mAudioService.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD);
            } catch (RemoteException e) {
                // ignore
            }
        }
    }

    public void vibrate(VibrationEffect effect) {
        if (mHasVibrator) {
            mVibrator.vibrate(effect, SONIFICIATION_VIBRATION_ATTRIBUTES);
        }
    }

    public boolean hasVibrator() {
        return mHasVibrator;
    }

    private void onNotifyVisibleW(boolean visible) {
        if (mDestroyed) return; 
        mAudio.notifyVolumeControllerVisible(mVolumeController, visible);
        if (!visible) {
            if (updateActiveStreamW(-1)) {
                mCallbacks.onStateChanged(mState);
            }
        }
    }

    private void onUserActivityW() {
        synchronized (this) {
            if (mUserActivityListener != null) {
                mUserActivityListener.onUserActivity();
            }
        }
    }

    private void onShowSafetyWarningW(int flags) {
        if (mShowSafetyWarning) {
            mCallbacks.onShowSafetyWarning(flags);
        }
    }

    private void onAccessibilityModeChanged(Boolean showA11yStream) {
        mCallbacks.onAccessibilityModeChanged(showA11yStream);
    }

    private boolean checkRoutedToBluetoothW(int stream) {
        boolean changed = false;
        if (stream == AudioManager.STREAM_MUSIC) {
            final boolean routedToBluetooth =
                    (mAudio.getDevicesForStream(AudioManager.STREAM_MUSIC) &
                            (AudioManager.DEVICE_OUT_BLUETOOTH_A2DP |
                            AudioManager.DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES |
                            AudioManager.DEVICE_OUT_BLUETOOTH_A2DP_SPEAKER)) != 0;
            changed |= updateStreamRoutedToBluetoothW(stream, routedToBluetooth);
        }
        return changed;
    }

    private void updateStatusBar() {
        if (mStatusBar == null) {
            mStatusBar = SysUiServiceProvider.getComponent(mContext, StatusBar.class);
        }
    }

    private boolean shouldShowUI(int flags) {
        updateStatusBar();
        // if status bar isn't null, check if phone is in AOD, else check flags
        // since we could be using a different status bar
        return mStatusBar != null ?
                mStatusBar.getWakefulnessState() != WakefulnessLifecycle.WAKEFULNESS_ASLEEP
                && mStatusBar.getWakefulnessState() !=
                        WakefulnessLifecycle.WAKEFULNESS_GOING_TO_SLEEP
                && mStatusBar.isDeviceInteractive()
                && (flags & AudioManager.FLAG_SHOW_UI) != 0 && mShowVolumeDialog
                : mShowVolumeDialog && (flags & AudioManager.FLAG_SHOW_UI) != 0;
    }

    boolean onVolumeChangedW(int stream, int flags) {
        final boolean showUI = shouldShowUI(flags);
        final boolean fromKey = (flags & AudioManager.FLAG_FROM_KEY) != 0;
        final boolean showVibrateHint = (flags & AudioManager.FLAG_SHOW_VIBRATE_HINT) != 0;
        final boolean showSilentHint = (flags & AudioManager.FLAG_SHOW_SILENT_HINT) != 0;
        boolean changed = false;
        if (showUI) {
            changed |= updateActiveStreamW(stream);
        }
        int lastAudibleStreamVolume = getAudioManagerStreamVolume(stream);
        changed |= updateStreamLevelW(stream, lastAudibleStreamVolume);
        changed |= checkRoutedToBluetoothW(showUI ? AudioManager.STREAM_MUSIC : stream);
        if (changed) {
            mCallbacks.onStateChanged(mState);
        }
        if (showUI) {
            mCallbacks.onShowRequested(Events.SHOW_REASON_VOLUME_CHANGED);
        }
        if (showVibrateHint) {
            mCallbacks.onShowVibrateHint();
        }
        if (showSilentHint) {
            mCallbacks.onShowSilentHint();
        }
        if (changed && fromKey) {
            Events.writeEvent(mContext, Events.EVENT_KEY, stream, lastAudibleStreamVolume);
        }
        return changed;
    }

    private boolean updateActiveStreamW(int activeStream) {
        if (activeStream == mState.activeStream) return false;
        mState.activeStream = activeStream;
        Events.writeEvent(mContext, Events.EVENT_ACTIVE_STREAM_CHANGED, activeStream);
        if (D.BUG) Log.d(TAG, "updateActiveStreamW " + activeStream);
        final int s = activeStream < DYNAMIC_STREAM_START_INDEX ? activeStream : -1;
        if (D.BUG) Log.d(TAG, "forceVolumeControlStream " + s);
        mAudio.forceVolumeControlStream(s);
        return true;
    }

    private StreamState streamStateW(int stream) {
        StreamState ss = mState.states.get(stream);
        if (ss == null) {
            ss = new StreamState();
            mState.states.put(stream, ss);
        }
        return ss;
    }

    private void onGetStateW() {
        for (int stream : STREAMS.keySet()) {
            updateStreamLevelW(stream, getAudioManagerStreamVolume(stream));
            streamStateW(stream).levelMin = getAudioManagerStreamMinVolume(stream);
            streamStateW(stream).levelMax = Math.max(1, getAudioManagerStreamMaxVolume(stream));
            updateStreamMuteW(stream, mAudio.isStreamMute(stream));
            final StreamState ss = streamStateW(stream);
            ss.muteSupported = mAudio.isStreamAffectedByMute(stream);
            ss.name = STREAMS.get(stream);
            checkRoutedToBluetoothW(stream);
        }
        updateRingerModeExternalW(mAudio.getRingerMode());
        updateZenModeW();
        updateZenConfig();
        updateEffectsSuppressorW(mNoMan.getEffectsSuppressor());
        mCallbacks.onStateChanged(mState);
    }

    private boolean updateStreamRoutedToBluetoothW(int stream, boolean routedToBluetooth) {
        final StreamState ss = streamStateW(stream);
        if (ss.routedToBluetooth == routedToBluetooth) return false;
        ss.routedToBluetooth = routedToBluetooth;
        if (D.BUG) Log.d(TAG, "updateStreamRoutedToBluetoothW stream=" + stream
                + " routedToBluetooth=" + routedToBluetooth);
        return true;
    }

    private boolean updateStreamLevelW(int stream, int level) {
        final StreamState ss = streamStateW(stream);
        if (ss.level == level) return false;
        ss.level = level;
        if (isLogWorthy(stream)) {
            Events.writeEvent(mContext, Events.EVENT_LEVEL_CHANGED, stream, level);
        }
        return true;
    }

    private static boolean isLogWorthy(int stream) {
        switch (stream) {
            case AudioSystem.STREAM_ALARM:
            case AudioSystem.STREAM_BLUETOOTH_SCO:
            case AudioSystem.STREAM_MUSIC:
            case AudioSystem.STREAM_RING:
            case AudioSystem.STREAM_SYSTEM:
            case AudioSystem.STREAM_VOICE_CALL:
                return true;
        }
        return false;
    }

    private boolean updateStreamMuteW(int stream, boolean muted) {
        final StreamState ss = streamStateW(stream);
        if (ss.muted == muted) return false;
        ss.muted = muted;
        if (isLogWorthy(stream)) {
            Events.writeEvent(mContext, Events.EVENT_MUTE_CHANGED, stream, muted);
        }
        if (muted && isRinger(stream)) {
            updateRingerModeInternalW(mAudio.getRingerModeInternal());
        }
        return true;
    }

    private static boolean isRinger(int stream) {
        return stream == AudioManager.STREAM_RING || stream == AudioManager.STREAM_NOTIFICATION;
    }

    private boolean updateEffectsSuppressorW(ComponentName effectsSuppressor) {
        if (Objects.equals(mState.effectsSuppressor, effectsSuppressor)) return false;
        mState.effectsSuppressor = effectsSuppressor;
        mState.effectsSuppressorName = getApplicationName(mContext, mState.effectsSuppressor);
        Events.writeEvent(mContext, Events.EVENT_SUPPRESSOR_CHANGED, mState.effectsSuppressor,
                mState.effectsSuppressorName);
        return true;
    }

    private static String getApplicationName(Context context, ComponentName component) {
        if (component == null) return null;
        final PackageManager pm = context.getPackageManager();
        final String pkg = component.getPackageName();
        try {
            final ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            final String rt = Objects.toString(ai.loadLabel(pm), "").trim();
            if (rt.length() > 0) {
                return rt;
            }
        } catch (NameNotFoundException e) {}
        return pkg;
    }

    private boolean updateZenModeW() {
        final int zen = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.ZEN_MODE, Settings.Global.ZEN_MODE_OFF);
        if (mState.zenMode == zen) return false;
        mState.zenMode = zen;
        Events.writeEvent(mContext, Events.EVENT_ZEN_MODE_CHANGED, zen);
        return true;
    }

    private boolean updateZenConfig() {
        final NotificationManager.Policy policy = mNotificationManager.getNotificationPolicy();
        boolean disallowAlarms = (policy.priorityCategories & NotificationManager.Policy
                .PRIORITY_CATEGORY_ALARMS) == 0;
        boolean disallowMedia = (policy.priorityCategories & NotificationManager.Policy
                .PRIORITY_CATEGORY_MEDIA) == 0;
        boolean disallowSystem = (policy.priorityCategories & NotificationManager.Policy
                .PRIORITY_CATEGORY_SYSTEM) == 0;
        boolean disallowRinger = ZenModeConfig.areAllPriorityOnlyNotificationZenSoundsMuted(policy);
        if (mState.disallowAlarms == disallowAlarms
                && mState.disallowMedia == disallowMedia
                && mState.disallowRinger == disallowRinger
                && mState.disallowSystem == disallowSystem) {
            return false;
        }
        mState.disallowAlarms = disallowAlarms;
        mState.disallowMedia = disallowMedia;
        mState.disallowSystem = disallowSystem;
        mState.disallowRinger = disallowRinger;
        Events.writeEvent(mContext, Events.EVENT_ZEN_CONFIG_CHANGED, "disallowAlarms=" +
                disallowAlarms + " disallowMedia=" + disallowMedia + " disallowSystem=" +
                disallowSystem + " disallowRinger=" + disallowRinger);
        return true;
    }

    private boolean updateRingerModeExternalW(int rm) {
        if (rm == mState.ringerModeExternal) return false;
        mState.ringerModeExternal = rm;
        Events.writeEvent(mContext, Events.EVENT_EXTERNAL_RINGER_MODE_CHANGED, rm);
        return true;
    }

    private boolean updateRingerModeInternalW(int rm) {
        if (rm == mState.ringerModeInternal) return false;
        mState.ringerModeInternal = rm;
        Events.writeEvent(mContext, Events.EVENT_INTERNAL_RINGER_MODE_CHANGED, rm);

        if (mState.ringerModeInternal == RINGER_MODE_NORMAL) {
            playTouchFeedback();
        }

        return true;
    }

    private void onSetRingerModeW(int mode, boolean external) {
        if (external) {
            mAudio.setRingerMode(mode);
        } else {
            mAudio.setRingerModeInternal(mode);
        }
    }

    private void onSetStreamMuteW(int stream, boolean mute) {
        mAudio.adjustStreamVolume(stream, mute ? AudioManager.ADJUST_MUTE
                : AudioManager.ADJUST_UNMUTE, 0);
    }

    private void onSetStreamVolumeW(int stream, int level) {
        if (D.BUG) Log.d(TAG, "onSetStreamVolume " + stream + " level=" + level);
        if (stream >= DYNAMIC_STREAM_START_INDEX) {
            mMediaSessionsCallbacksW.setStreamVolume(stream, level);
            return;
        }
        setAudioManagerStreamVolume(stream, level, 0);
    }

    private void onSetActiveStreamW(int stream) {
        boolean changed = updateActiveStreamW(stream);
        if (changed) {
            mCallbacks.onStateChanged(mState);
        }
    }

    private void onSetExitConditionW(Condition condition) {
        mNoMan.setZenMode(mState.zenMode, condition != null ? condition.id : null, TAG);
    }

    private void onSetZenModeW(int mode) {
        if (D.BUG) Log.d(TAG, "onSetZenModeW " + mode);
        mNoMan.setZenMode(mode, null, TAG);
    }

    private void onDismissRequestedW(int reason) {
        mCallbacks.onDismissRequested(reason);
    }

    public void showDndTile(boolean visible) {
        if (D.BUG) Log.d(TAG, "showDndTile");
        DndTile.setVisible(mContext, visible);
    }

    private final class VC extends IVolumeController.Stub {
        private final String TAG = VolumeDialogControllerImpl.TAG + ".VC";

        @Override
        public void displaySafeVolumeWarning(int flags) throws RemoteException {
            if (D.BUG) Log.d(TAG, "displaySafeVolumeWarning "
                    + Util.audioManagerFlagsToString(flags));
            if (mDestroyed) return;
            mWorker.obtainMessage(W.SHOW_SAFETY_WARNING, flags, 0).sendToTarget();
        }

        @Override
        public void volumeChanged(int streamType, int flags) throws RemoteException {
            if (D.BUG) Log.d(TAG, "volumeChanged " + AudioSystem.streamToString(streamType)
                    + " " + Util.audioManagerFlagsToString(flags));
            if (mDestroyed) return;
            mWorker.obtainMessage(W.VOLUME_CHANGED, streamType, flags).sendToTarget();
        }

        @Override
        public void masterMuteChanged(int flags) throws RemoteException {
            if (D.BUG) Log.d(TAG, "masterMuteChanged");
        }

        @Override
        public void setLayoutDirection(int layoutDirection) throws RemoteException {
            if (D.BUG) Log.d(TAG, "setLayoutDirection");
            if (mDestroyed) return;
            mWorker.obtainMessage(W.LAYOUT_DIRECTION_CHANGED, layoutDirection, 0).sendToTarget();
        }

        @Override
        public void dismiss() throws RemoteException {
            if (D.BUG) Log.d(TAG, "dismiss requested");
            if (mDestroyed) return;
            mWorker.obtainMessage(W.DISMISS_REQUESTED, Events.DISMISS_REASON_VOLUME_CONTROLLER, 0)
                    .sendToTarget();
            mWorker.sendEmptyMessage(W.DISMISS_REQUESTED);
        }

        @Override
        public void setA11yMode(int mode) {
            if (D.BUG) Log.d(TAG, "setA11yMode to " + mode);
            if (mDestroyed) return;
            switch (mode) {
                case VolumePolicy.A11Y_MODE_MEDIA_A11Y_VOLUME:
                    // "legacy" mode
                    mShowA11yStream = false;
                    break;
                case VolumePolicy.A11Y_MODE_INDEPENDENT_A11Y_VOLUME:
                    mShowA11yStream = true;
                    break;
                default:
                    Log.e(TAG, "Invalid accessibility mode " + mode);
                    break;
            }
            mWorker.obtainMessage(W.ACCESSIBILITY_MODE_CHANGED, mShowA11yStream).sendToTarget();
        }
    }

    private final class W extends Handler {
        private static final int VOLUME_CHANGED = 1;
        private static final int DISMISS_REQUESTED = 2;
        private static final int GET_STATE = 3;
        private static final int SET_RINGER_MODE = 4;
        private static final int SET_ZEN_MODE = 5;
        private static final int SET_EXIT_CONDITION = 6;
        private static final int SET_STREAM_MUTE = 7;
        private static final int LAYOUT_DIRECTION_CHANGED = 8;
        private static final int CONFIGURATION_CHANGED = 9;
        private static final int SET_STREAM_VOLUME = 10;
        private static final int SET_ACTIVE_STREAM = 11;
        private static final int NOTIFY_VISIBLE = 12;
        private static final int USER_ACTIVITY = 13;
        private static final int SHOW_SAFETY_WARNING = 14;
        private static final int ACCESSIBILITY_MODE_CHANGED = 15;

        W(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case VOLUME_CHANGED: onVolumeChangedW(msg.arg1, msg.arg2); break;
                case DISMISS_REQUESTED: onDismissRequestedW(msg.arg1); break;
                case GET_STATE: onGetStateW(); break;
                case SET_RINGER_MODE: onSetRingerModeW(msg.arg1, msg.arg2 != 0); break;
                case SET_ZEN_MODE: onSetZenModeW(msg.arg1); break;
                case SET_EXIT_CONDITION: onSetExitConditionW((Condition) msg.obj); break;
                case SET_STREAM_MUTE: onSetStreamMuteW(msg.arg1, msg.arg2 != 0); break;
                case LAYOUT_DIRECTION_CHANGED: mCallbacks.onLayoutDirectionChanged(msg.arg1); break;
                case CONFIGURATION_CHANGED: mCallbacks.onConfigurationChanged(); break;
                case SET_STREAM_VOLUME: onSetStreamVolumeW(msg.arg1, msg.arg2); break;
                case SET_ACTIVE_STREAM: onSetActiveStreamW(msg.arg1); break;
                case NOTIFY_VISIBLE: onNotifyVisibleW(msg.arg1 != 0); break;
                case USER_ACTIVITY: onUserActivityW(); break;
                case SHOW_SAFETY_WARNING: onShowSafetyWarningW(msg.arg1); break;
                case ACCESSIBILITY_MODE_CHANGED: onAccessibilityModeChanged((Boolean) msg.obj);

            }
        }
    }

    class C implements Callbacks {
        private final HashMap<Callbacks, Handler> mCallbackMap = new HashMap<>();

        public void add(Callbacks callback, Handler handler) {
            if (callback == null || handler == null) throw new IllegalArgumentException();
            mCallbackMap.put(callback, handler);
        }

        public void remove(Callbacks callback) {
            mCallbackMap.remove(callback);
        }

        @Override
        public void onShowRequested(final int reason) {
            for (final Map.Entry<Callbacks, Handler> entry : mCallbackMap.entrySet()) {
                entry.getValue().post(new Runnable() {
                    @Override
                    public void run() {
                        entry.getKey().onShowRequested(reason);
                    }
                });
            }
        }

        @Override
        public void onDismissRequested(final int reason) {
            for (final Map.Entry<Callbacks, Handler> entry : mCallbackMap.entrySet()) {
                entry.getValue().post(new Runnable() {
                    @Override
                    public void run() {
                        entry.getKey().onDismissRequested(reason);
                    }
                });
            }
        }

        @Override
        public void onStateChanged(final State state) {
            final long time = System.currentTimeMillis();
            final State copy = state.copy();
            for (final Map.Entry<Callbacks, Handler> entry : mCallbackMap.entrySet()) {
                entry.getValue().post(new Runnable() {
                    @Override
                    public void run() {
                        entry.getKey().onStateChanged(copy);
                    }
                });
            }
            Events.writeState(time, copy);
        }

        @Override
        public void onLayoutDirectionChanged(final int layoutDirection) {
            for (final Map.Entry<Callbacks, Handler> entry : mCallbackMap.entrySet()) {
                entry.getValue().post(new Runnable() {
                    @Override
                    public void run() {
                        entry.getKey().onLayoutDirectionChanged(layoutDirection);
                    }
                });
            }
        }

        @Override
        public void onConfigurationChanged() {
            for (final Map.Entry<Callbacks, Handler> entry : mCallbackMap.entrySet()) {
                entry.getValue().post(new Runnable() {
                    @Override
                    public void run() {
                        entry.getKey().onConfigurationChanged();
                    }
                });
            }
        }

        @Override
        public void onShowVibrateHint() {
            for (final Map.Entry<Callbacks, Handler> entry : mCallbackMap.entrySet()) {
                entry.getValue().post(new Runnable() {
                    @Override
                    public void run() {
                        entry.getKey().onShowVibrateHint();
                    }
                });
            }
        }

        @Override
        public void onShowSilentHint() {
            for (final Map.Entry<Callbacks, Handler> entry : mCallbackMap.entrySet()) {
                entry.getValue().post(new Runnable() {
                    @Override
                    public void run() {
                        entry.getKey().onShowSilentHint();
                    }
                });
            }
        }

        @Override
        public void onScreenOff() {
            for (final Map.Entry<Callbacks, Handler> entry : mCallbackMap.entrySet()) {
                entry.getValue().post(new Runnable() {
                    @Override
                    public void run() {
                        entry.getKey().onScreenOff();
                    }
                });
            }
        }

        @Override
        public void onShowSafetyWarning(final int flags) {
            for (final Map.Entry<Callbacks, Handler> entry : mCallbackMap.entrySet()) {
                entry.getValue().post(new Runnable() {
                    @Override
                    public void run() {
                        entry.getKey().onShowSafetyWarning(flags);
                    }
                });
            }
        }

        @Override
        public void onAccessibilityModeChanged(Boolean showA11yStream) {
            boolean show = showA11yStream == null ? false : showA11yStream;
            for (final Map.Entry<Callbacks, Handler> entry : mCallbackMap.entrySet()) {
                entry.getValue().post(new Runnable() {
                    @Override
                    public void run() {
                        entry.getKey().onAccessibilityModeChanged(show);
                    }
                });
            }
        }
    }


    private final class SettingObserver extends ContentObserver {
        private final Uri ZEN_MODE_URI =
                Settings.Global.getUriFor(Settings.Global.ZEN_MODE);
        private final Uri ZEN_MODE_CONFIG_URI =
                Settings.Global.getUriFor(Settings.Global.ZEN_MODE_CONFIG_ETAG);

        public SettingObserver(Handler handler) {
            super(handler);
        }

        public void init() {
            mContext.getContentResolver().registerContentObserver(ZEN_MODE_URI, false, this);
            mContext.getContentResolver().registerContentObserver(ZEN_MODE_CONFIG_URI, false, this);
        }

        public void destroy() {
            mContext.getContentResolver().unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            boolean changed = false;
            if (ZEN_MODE_URI.equals(uri)) {
                changed = updateZenModeW();
            }
            if (ZEN_MODE_CONFIG_URI.equals(uri)) {
                changed |= updateZenConfig();
            }

            if (changed) {
                mCallbacks.onStateChanged(mState);
            }
        }
    }

    private final class Receiver extends BroadcastReceiver {

        public void init() {
            final IntentFilter filter = new IntentFilter();
            filter.addAction(AudioManager.VOLUME_CHANGED_ACTION);
            filter.addAction(AudioManager.STREAM_DEVICES_CHANGED_ACTION);
            filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
            filter.addAction(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION);
            filter.addAction(AudioManager.STREAM_MUTE_CHANGED_ACTION);
            filter.addAction(NotificationManager.ACTION_EFFECTS_SUPPRESSOR_CHANGED);
            filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            mContext.registerReceiver(this, filter, null, mWorker);
        }

        public void destroy() {
            mContext.unregisterReceiver(this);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            boolean changed = false;
            if (action.equals(AudioManager.VOLUME_CHANGED_ACTION)) {
                final int stream = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1);
                final int level = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, -1);
                final int oldLevel = intent
                        .getIntExtra(AudioManager.EXTRA_PREV_VOLUME_STREAM_VALUE, -1);
                if (D.BUG) Log.d(TAG, "onReceive VOLUME_CHANGED_ACTION stream=" + stream
                        + " level=" + level + " oldLevel=" + oldLevel);
                changed = updateStreamLevelW(stream, level);
            } else if (action.equals(AudioManager.STREAM_DEVICES_CHANGED_ACTION)) {
                final int stream = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1);
                final int devices = intent
                        .getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_DEVICES, -1);
                final int oldDevices = intent
                        .getIntExtra(AudioManager.EXTRA_PREV_VOLUME_STREAM_DEVICES, -1);
                if (D.BUG) Log.d(TAG, "onReceive STREAM_DEVICES_CHANGED_ACTION stream="
                        + stream + " devices=" + devices + " oldDevices=" + oldDevices);
                changed = checkRoutedToBluetoothW(stream);
                changed |= onVolumeChangedW(stream, 0);
            } else if (action.equals(AudioManager.RINGER_MODE_CHANGED_ACTION)) {
                final int rm = intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1);
                if (D.BUG) Log.d(TAG, "onReceive RINGER_MODE_CHANGED_ACTION rm="
                        + Util.ringerModeToString(rm));
                changed = updateRingerModeExternalW(rm);
            } else if (action.equals(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION)) {
                final int rm = intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1);
                if (D.BUG) Log.d(TAG, "onReceive INTERNAL_RINGER_MODE_CHANGED_ACTION rm="
                        + Util.ringerModeToString(rm));
                changed = updateRingerModeInternalW(rm);
            } else if (action.equals(AudioManager.STREAM_MUTE_CHANGED_ACTION)) {
                final int stream = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1);
                final boolean muted = intent
                        .getBooleanExtra(AudioManager.EXTRA_STREAM_VOLUME_MUTED, false);
                if (D.BUG) Log.d(TAG, "onReceive STREAM_MUTE_CHANGED_ACTION stream=" + stream
                        + " muted=" + muted);
                changed = updateStreamMuteW(stream, muted);
            } else if (action.equals(NotificationManager.ACTION_EFFECTS_SUPPRESSOR_CHANGED)) {
                if (D.BUG) Log.d(TAG, "onReceive ACTION_EFFECTS_SUPPRESSOR_CHANGED");
                changed = updateEffectsSuppressorW(mNoMan.getEffectsSuppressor());
            } else if (action.equals(Intent.ACTION_CONFIGURATION_CHANGED)) {
                if (D.BUG) Log.d(TAG, "onReceive ACTION_CONFIGURATION_CHANGED");
                mCallbacks.onConfigurationChanged();
            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                if (D.BUG) Log.d(TAG, "onReceive ACTION_SCREEN_OFF");
                mCallbacks.onScreenOff();
            } else if (action.equals(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) {
                if (D.BUG) Log.d(TAG, "onReceive ACTION_CLOSE_SYSTEM_DIALOGS");
                dismiss();
            }
            if (changed) {
                mCallbacks.onStateChanged(mState);
            }
        }
    }

    protected final class MediaSessionsCallbacks implements MediaSessions.Callbacks {
        private final HashMap<Token, Integer> mRemoteStreams = new HashMap<>();

        private int mNextStream = DYNAMIC_STREAM_START_INDEX;

        @Override
        public void onRemoteUpdate(Token token, String name, PlaybackInfo pi) {
            addStream(token, "onRemoteUpdate");
            final int stream = mRemoteStreams.get(token);
            boolean changed = mState.states.indexOfKey(stream) < 0;
            final StreamState ss = streamStateW(stream);
            ss.dynamic = true;
            ss.levelMin = 0;
            ss.levelMax = pi.getMaxVolume();
            if (ss.level != pi.getCurrentVolume()) {
                ss.level = pi.getCurrentVolume();
                changed = true;
            }
            if (!Objects.equals(ss.remoteLabel, name)) {
                ss.name = -1;
                ss.remoteLabel = name;
                changed = true;
            }
            if (changed) {
                if (D.BUG) Log.d(TAG, "onRemoteUpdate: " + name + ": " + ss.level
                        + " of " + ss.levelMax);
                mCallbacks.onStateChanged(mState);
            }
        }

        @Override
        public void onRemoteVolumeChanged(Token token, int flags) {
            addStream(token, "onRemoteVolumeChanged");
            final int stream = mRemoteStreams.get(token);
            final boolean showUI = shouldShowUI(flags);
            boolean changed = updateActiveStreamW(stream);
            if (showUI) {
                changed |= checkRoutedToBluetoothW(AudioManager.STREAM_MUSIC);
            }
            if (changed) {
                mCallbacks.onStateChanged(mState);
            }
            if (showUI) {
                mCallbacks.onShowRequested(Events.SHOW_REASON_REMOTE_VOLUME_CHANGED);
            }
        }

        @Override
        public void onRemoteRemoved(Token token) {
            if (!mRemoteStreams.containsKey(token)) {
                if (D.BUG) Log.d(TAG, "onRemoteRemoved: stream doesn't exist, "
                        + "aborting remote removed for token:" +  token.toString());
                return;
            }
            final int stream = mRemoteStreams.get(token);
            mState.states.remove(stream);
            if (mState.activeStream == stream) {
                updateActiveStreamW(-1);
            }
            mCallbacks.onStateChanged(mState);
        }

        public void setStreamVolume(int stream, int level) {
            final Token t = findToken(stream);
            if (t == null) {
                Log.w(TAG, "setStreamVolume: No token found for stream: " + stream);
                return;
            }
            mMediaSessions.setVolume(t, level);
        }

        private Token findToken(int stream) {
            for (Map.Entry<Token, Integer> entry : mRemoteStreams.entrySet()) {
                if (entry.getValue().equals(stream)) {
                    return entry.getKey();
                }
            }
            return null;
        }

        private void addStream(Token token, String triggeringMethod) {
            if (!mRemoteStreams.containsKey(token)) {
                mRemoteStreams.put(token, mNextStream);
                if (D.BUG) Log.d(TAG, triggeringMethod + ": added stream " +  mNextStream
                        + " from token + "+ token.toString());
                mNextStream++;
            }
        }
    }

    public interface UserActivityListener {
        void onUserActivity();
    }
}
