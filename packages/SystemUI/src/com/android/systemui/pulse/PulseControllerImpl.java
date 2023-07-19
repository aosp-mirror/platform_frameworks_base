/**
 * Copyright (C) 2014 The TeamEos Project
 * Copyright (C) 2016-2022 crDroid Android Project
 *
 * @author: Randall Rushing <randall.rushing@gmail.com>
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
 *
 * Control class for Pulse media fuctions and visualizer state management
 * Basic logic flow inspired by Roman Birg aka romanbb in his Equalizer
 * tile produced for Cyanogenmod
 *
 */

package com.android.systemui.pulse;

import java.util.ArrayList;
import java.util.List;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.IAudioService;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.widget.FrameLayout;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.UiBackground;
import com.android.systemui.navigationbar.NavigationBarFrame;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.CommandQueue.Callbacks;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.phone.CentralSurfacesImpl;
import com.android.systemui.statusbar.policy.ConfigurationController;

import java.util.concurrent.Executor;

@SysUISingleton
public class PulseControllerImpl implements
        NotificationMediaManager.MediaListener,
        CommandQueue.Callbacks,
        ConfigurationController.ConfigurationListener {

    public static final boolean DEBUG = false;

    private static final String TAG = PulseControllerImpl.class.getSimpleName();
    private static final int RENDER_STYLE_LEGACY = 0;
    private static final int RENDER_STYLE_CM = 1;

    private Context mContext;
    private AudioManager mAudioManager;
    private Renderer mRenderer;
    private VisualizerStreamHandler mStreamHandler;
    private ColorController mColorController;
    private SettingsObserver mSettingsObserver;
    private PulseView mPulseView;
    private int mPulseStyle;
    private CentralSurfacesImpl mStatusbar;
    private final PowerManager mPowerManager;

    // Pulse state
    private boolean mLinked;
    private boolean mPowerSaveModeEnabled;
    private boolean mScreenOn = true; // MUST initialize as true
    private boolean mMusicStreamMuted;
    private boolean mLeftInLandscape;
    private boolean mScreenPinningEnabled;
    private boolean mIsMediaPlaying;
    private boolean mAttached;

    private boolean mNavPulseEnabled;
    private boolean mLsPulseEnabled;
    private boolean mAmbPulseEnabled;
    private boolean mKeyguardShowing;
    private boolean mDozing;
    private boolean mKeyguardGoingAway;

    private boolean mNavPulseAttached;
    private boolean mLsPulseAttached;

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                mScreenOn = false;
                doLinkage();
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                mScreenOn = true;
                doLinkage();
            } else if (PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(intent.getAction())) {
                mPowerSaveModeEnabled = mPowerManager.isPowerSaveMode();
                doLinkage();
            } else if (AudioManager.STREAM_MUTE_CHANGED_ACTION.equals(intent.getAction())
                    || (AudioManager.VOLUME_CHANGED_ACTION.equals(intent.getAction()))) {
                int streamType = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1);
                if (streamType == AudioManager.STREAM_MUSIC) {
                    boolean muted = isMusicMuted(streamType);
                    if (mMusicStreamMuted != muted) {
                        mMusicStreamMuted = muted;
                        doLinkage();
                    }
                }
            }
        }
    };

    private final VisualizerStreamHandler.Listener mStreamListener = new VisualizerStreamHandler.Listener() {
        @Override
        public void onStreamAnalyzed(boolean isValid) {
            if (mRenderer != null) {
                mRenderer.onStreamAnalyzed(isValid);
            }
            if (isValid) {
                turnOnPulse();
            } else {
                doSilentUnlinkVisualizer();
            }
        }

        @Override
        public void onFFTUpdate(byte[] bytes) {
            if (mRenderer != null && bytes != null) {
                mRenderer.onFFTUpdate(bytes);
            }
        }

        @Override
        public void onWaveFormUpdate(byte[] bytes) {
            if (mRenderer != null && bytes != null) {
                mRenderer.onWaveFormUpdate(bytes);
            }
        }
    };

    private class SettingsObserver extends ContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
        }

        void register() {
            mContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.NAVBAR_PULSE_ENABLED), false, this,
                    UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.LOCKSCREEN_PULSE_ENABLED), false, this,
                    UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.AMBIENT_PULSE_ENABLED), false, this,
                    UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.PULSE_RENDER_STYLE), false, this,
                    UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (uri.equals(Settings.Secure.getUriFor(Settings.Secure.NAVBAR_PULSE_ENABLED))
                    || uri.equals(Settings.Secure.getUriFor(Settings.Secure.LOCKSCREEN_PULSE_ENABLED))
                    || uri.equals(Settings.Secure.getUriFor(Settings.Secure.AMBIENT_PULSE_ENABLED))) {
                updateEnabled();
                updatePulseVisibility();
            } else if (uri.equals(Settings.Secure.getUriFor(Settings.Secure.PULSE_RENDER_STYLE))) {
                updateRenderMode();
                loadRenderer();
            }
        }

        void updateSettings() {
            updateEnabled();
            updateRenderMode();
        }

        void updateEnabled() {
            mNavPulseEnabled = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.NAVBAR_PULSE_ENABLED, 0, UserHandle.USER_CURRENT) == 1;
            mLsPulseEnabled = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.LOCKSCREEN_PULSE_ENABLED, 0, UserHandle.USER_CURRENT) == 1;
            mAmbPulseEnabled = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.AMBIENT_PULSE_ENABLED, 0, UserHandle.USER_CURRENT) == 1;
        }

        void updateRenderMode() {
            mPulseStyle = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.PULSE_RENDER_STYLE, RENDER_STYLE_CM, UserHandle.USER_CURRENT);
        }
    };

    public void notifyKeyguardGoingAway() {
        if (mLsPulseEnabled) {
            mKeyguardGoingAway = true;
            updatePulseVisibility();
            mKeyguardGoingAway = false;
        }
    }

    private void updatePulseVisibility() {
        if (mStatusbar == null) return;

        NavigationBarFrame nv = mStatusbar.getNavigationBarView() != null ?
                mStatusbar.getNavigationBarView().getNavbarFrame() : null;
        VisualizerView vv = mStatusbar.getLsVisualizer();
        boolean allowAmbPulse = vv != null && vv.isAttached()
                && mAmbPulseEnabled && mKeyguardShowing && mDozing;
        boolean allowLsPulse = vv != null && vv.isAttached()
                && mLsPulseEnabled && mKeyguardShowing && !mDozing;
        boolean allowNavPulse = nv!= null && nv.isAttached()
            && mNavPulseEnabled && !mKeyguardShowing;

        if (mKeyguardGoingAway) {
            if (mLsPulseAttached) {
                detachPulseFrom(vv, allowNavPulse/*keep linked*/);
                mLsPulseAttached = false;
            }
            return;
        }
        if (!allowNavPulse && mNavPulseAttached) {
            detachPulseFrom(nv, allowLsPulse || allowAmbPulse/*keep linked*/);
            mNavPulseAttached = false;
        }
        if (!allowLsPulse && !allowAmbPulse && mLsPulseAttached) {
            detachPulseFrom(vv, allowNavPulse/*keep linked*/);
            mLsPulseAttached = false;
        }

        if ((allowLsPulse || allowAmbPulse) && !mLsPulseAttached) {
            attachPulseTo(vv);
            mLsPulseAttached = true;
        } else if (allowNavPulse && !mNavPulseAttached) {
            attachPulseTo(nv);
            mNavPulseAttached = true;
        }
    }

    public void setDozing(boolean dozing) {
        if (mDozing != dozing) {
            mDozing = dozing;
            updatePulseVisibility();
        }
    }

    public void setKeyguardShowing(boolean showing) {
        if (showing != mKeyguardShowing) {
            mKeyguardShowing = showing;
            if (mRenderer != null) {
                mRenderer.setKeyguardShowing(showing);
            }
            updatePulseVisibility();
        }
    }

    private final Handler mHandler = new Handler();

    public PulseControllerImpl(Context context,
            CentralSurfacesImpl statusBar,
            CommandQueue commandQueue,
            @UiBackground Executor uiBgExecutor) {
        mContext = context;
        mStatusbar = statusBar;
        mSettingsObserver = new SettingsObserver(mHandler);
        mSettingsObserver.updateSettings();
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mMusicStreamMuted = isMusicMuted(AudioManager.STREAM_MUSIC);
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mPowerSaveModeEnabled = mPowerManager.isPowerSaveMode();
        mSettingsObserver.register();
        mStreamHandler = new VisualizerStreamHandler(mContext, this, mStreamListener, uiBgExecutor);
        mPulseView = new PulseView(context, this);
        mColorController = new ColorController(mContext, mHandler);
        loadRenderer();
        commandQueue.addCallback(this);
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        filter.addAction(AudioManager.STREAM_MUTE_CHANGED_ACTION);
        filter.addAction(AudioManager.VOLUME_CHANGED_ACTION);
        context.registerReceiverAsUser(mBroadcastReceiver, UserHandle.ALL, filter, null, null);
    }

    private void attachPulseTo(FrameLayout parent) {
        if (parent == null) return;
        View v = parent.findViewWithTag(PulseView.TAG);
        if (v == null) {
            parent.addView(mPulseView);
            mAttached = true;
            log("attachPulseTo() ");
            doLinkage();
        }
    }

    private void detachPulseFrom(FrameLayout parent, boolean keepLinked) {
        if (parent == null) return;
        View v = parent.findViewWithTag(PulseView.TAG);
        if (v != null) {
            parent.removeView(mPulseView);
            mAttached = keepLinked;
            log("detachPulseFrom() ");
            doLinkage();
        }
    }

    private void loadRenderer() {
        final boolean isRendering = shouldDrawPulse();
        if (isRendering) {
            mStreamHandler.pause();
        }
        if (mRenderer != null) {
            mRenderer.destroy();
            mRenderer = null;
        }
        mRenderer = getRenderer();
        mColorController.setRenderer(mRenderer);
        mRenderer.setLeftInLandscape(mLeftInLandscape);
        if (isRendering) {
            mRenderer.onStreamAnalyzed(true);
            mStreamHandler.resume();
        }
    }

    @Override
    public void screenPinningStateChanged(boolean enabled) {
        mScreenPinningEnabled = enabled;
        doLinkage();
    }

    @Override
    public void leftInLandscapeChanged(boolean isLeft) {
        if (mLeftInLandscape != isLeft) {
            mLeftInLandscape = isLeft;
            if (mRenderer != null) {
                mRenderer.setLeftInLandscape(isLeft);
            }
        }
    }

    /**
     * Current rendering state: There is a visualizer link and the fft stream is validated
     *
     * @return true if bar elements should be hidden, false if not
     */
    public boolean shouldDrawPulse() {
        return mLinked && mStreamHandler.isValidStream() && mRenderer != null;
    }

    public void onDraw(Canvas canvas) {
        if (shouldDrawPulse()) {
            mRenderer.draw(canvas);
        }
    }

    private void turnOnPulse() {
        if (shouldDrawPulse()) {
            mStreamHandler.resume(); // let bytes hit visualizer
        }
    }

    void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (mRenderer != null) {
            mRenderer.onSizeChanged(w, h, oldw, oldh);
        }
    }

    private Renderer getRenderer() {
        switch (mPulseStyle) {
            case RENDER_STYLE_LEGACY:
                return new FadingBlockRenderer(mContext, mHandler, mPulseView, this, mColorController);
            case RENDER_STYLE_CM:
                return new SolidLineRenderer(mContext, mHandler, mPulseView, this, mColorController);
            default:
                return new FadingBlockRenderer(mContext, mHandler, mPulseView, this, mColorController);
        }
    }

    private boolean isMusicMuted(int streamType) {
        return streamType == AudioManager.STREAM_MUSIC &&
                (mAudioManager.isStreamMute(streamType) ||
                mAudioManager.getStreamVolume(streamType) == 0);
    }

    private static void setVisualizerLocked(boolean doLock) {
        try {
            IBinder b = ServiceManager.getService(Context.AUDIO_SERVICE);
            IAudioService audioService = IAudioService.Stub.asInterface(b);
            audioService.setVisualizerLocked(doLock);
        } catch (RemoteException e) {
            Log.e(TAG, "Error setting visualizer lock");
        }
    }

    /**
     * if any of these conditions are met, we unlink regardless of any other states
     *
     * @return true if unlink is required, false if unlinking is not mandatory
     */
    private boolean isUnlinkRequired() {
        return (!mScreenOn && !mAmbPulseEnabled)
                || mPowerSaveModeEnabled
                || mMusicStreamMuted
                || mScreenPinningEnabled
                || !mAttached;
    }

    /**
     * All of these conditions must be met to allow a visualizer link
     *
     * @return true if all conditions are met to allow link, false if and conditions are not met
     */
    private boolean isAbleToLink() {
        return (mScreenOn || mAmbPulseEnabled)
                && mIsMediaPlaying
                && !mPowerSaveModeEnabled
                && !mMusicStreamMuted
                && !mScreenPinningEnabled
                && mAttached;
    }

    private void doUnlinkVisualizer() {
        if (mStreamHandler != null) {
            if (mLinked) {
                mStreamHandler.unlink();
                setVisualizerLocked(false);
                mLinked = false;
                if (mRenderer != null) {
                    mRenderer.onVisualizerLinkChanged(false);
                }
                mPulseView.postInvalidate();
            }
        }
    }

    /**
     * Incoming event in which we need to
     * toggle our link state. Use runnable to
     * handle multiple events at same time.
     */
    private void doLinkage() {
        if (isUnlinkRequired()) {
            if (mLinked) {
                // explicitly unlink
                doUnlinkVisualizer();
            }
        } else {
            if (isAbleToLink()) {
                doLinkVisualizer();
            } else if (mLinked) {
                doUnlinkVisualizer();
            }
        }
    }

    /**
     * Invalid media event not providing
     * a data stream to visualizer. Unlink
     * without calling into view. Like it
     * never happened
     */
    private void doSilentUnlinkVisualizer() {
        if (mStreamHandler != null) {
            if (mLinked) {
                mStreamHandler.unlink();
                setVisualizerLocked(false);
                mLinked = false;
            }
        }
    }

    /**
     * Link to visualizer after conditions
     * are confirmed
     */
    private void doLinkVisualizer() {
        if (mStreamHandler != null) {
            if (!mLinked) {
                setVisualizerLocked(true);
                mStreamHandler.link();
                mLinked = true;
                if (mRenderer != null) {
                    mRenderer.onVisualizerLinkChanged(true);
                }
            }
        }
    }

    @Override
    public void onPrimaryMetadataOrStateChanged(MediaMetadata metadata, @PlaybackState.State int state) {
        boolean isPlaying = state == PlaybackState.STATE_PLAYING;
        if (mIsMediaPlaying != isPlaying) {
            mIsMediaPlaying = isPlaying;
            doLinkage();
        }
    }

    @Override
    public String toString() {
        return TAG + " " + getState();
    }

    private String getState() {
        return "isAbleToLink() = " + isAbleToLink() + " "
                + "shouldDrawPulse() = " + shouldDrawPulse() + " "
                + "mScreenOn = " + mScreenOn + " "
                + "mIsMediaPlaying = " + mIsMediaPlaying + " "
                + "mLinked = " + mLinked + " "
                + "mPowerSaveModeEnabled = " + mPowerSaveModeEnabled + " "
                + "mMusicStreamMuted = " + mMusicStreamMuted + " "
                + "mScreenPinningEnabled = " + mScreenPinningEnabled + " "
                + "mAttached = " + mAttached + " "
                + "mStreamHandler.isValidStream() = " + mStreamHandler.isValidStream() + " ";
    }

    private void log(String msg) {
        if (DEBUG) {
            Log.i(TAG, msg + " " + getState());
        }
    }
}
