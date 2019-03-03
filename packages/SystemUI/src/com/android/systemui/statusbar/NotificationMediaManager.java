/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.systemui.statusbar;

import static com.android.systemui.Dependency.MAIN_HANDLER;
import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;
import static com.android.systemui.statusbar.phone.StatusBar.DEBUG_MEDIA_FAKE_ARTWORK;
import static com.android.systemui.statusbar.phone.StatusBar.ENABLE_LOCKSCREEN_WALLPAPER;
import static com.android.systemui.statusbar.phone.StatusBar.SHOW_LOCKSCREEN_MEDIA_ARTWORK;

import android.annotation.Nullable;
import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.Dependency;
import com.android.systemui.Dumpable;
import com.android.systemui.Interpolators;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.notification.NotificationEntryListener;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.phone.BiometricUnlockController;
import com.android.systemui.statusbar.phone.LockscreenWallpaper;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.phone.ScrimState;
import com.android.systemui.statusbar.phone.ShadeController;
import com.android.systemui.statusbar.phone.StatusBarWindowController;
import com.android.systemui.statusbar.policy.KeyguardMonitor;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.Lazy;

/**
 * Handles tasks and state related to media notifications. For example, there is a 'current' media
 * notification, which this class keeps track of.
 */
@Singleton
public class NotificationMediaManager implements Dumpable {
    private static final String TAG = "NotificationMediaManager";
    public static final boolean DEBUG_MEDIA = false;

    private final StatusBarStateController mStatusBarStateController
            = Dependency.get(StatusBarStateController.class);
    private final SysuiColorExtractor mColorExtractor = Dependency.get(SysuiColorExtractor.class);
    private final KeyguardMonitor mKeyguardMonitor = Dependency.get(KeyguardMonitor.class);

    // Late binding
    private NotificationEntryManager mEntryManager;

    // Late binding, also @Nullable due to being in com.android.systemui.statusbar.phone package
    @Nullable
    private Lazy<ShadeController> mShadeController;
    @Nullable
    private Lazy<StatusBarWindowController> mStatusBarWindowController;

    @Nullable
    private BiometricUnlockController mBiometricUnlockController;
    @Nullable
    private ScrimController mScrimController;
    @Nullable
    private LockscreenWallpaper mLockscreenWallpaper;

    private final Handler mHandler = Dependency.get(MAIN_HANDLER);

    private final Context mContext;
    private final MediaSessionManager mMediaSessionManager;
    private final ArrayList<MediaListener> mMediaListeners;
    private final MediaArtworkProcessor mMediaArtworkProcessor;

    protected NotificationPresenter mPresenter;
    private MediaController mMediaController;
    private String mMediaNotificationKey;
    private MediaMetadata mMediaMetadata;

    private BackDropView mBackdrop;
    private ImageView mBackdropFront;
    private ImageView mBackdropBack;

    private boolean mShowCompactMediaSeekbar;
    private final DeviceConfig.OnPropertyChangedListener mPropertyChangedListener =
            new DeviceConfig.OnPropertyChangedListener() {
        @Override
        public void onPropertyChanged(String namespace, String name, String value) {
            if (SystemUiDeviceConfigFlags.COMPACT_MEDIA_SEEKBAR_ENABLED.equals(name)) {
                if (DEBUG_MEDIA) {
                    Log.v(TAG, "DEBUG_MEDIA: compact media seekbar flag updated: " + value);
                }
                mShowCompactMediaSeekbar = "true".equals(value);
            }
        }
    };

    private final MediaController.Callback mMediaListener = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            super.onPlaybackStateChanged(state);
            if (DEBUG_MEDIA) {
                Log.v(TAG, "DEBUG_MEDIA: onPlaybackStateChanged: " + state);
            }
            if (state != null) {
                if (!isPlaybackActive(state.getState())) {
                    clearCurrentMediaNotification();
                    dispatchUpdateMediaMetaData(true /* changed */, true /* allowAnimation */);
                }
            }
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            super.onMetadataChanged(metadata);
            if (DEBUG_MEDIA) {
                Log.v(TAG, "DEBUG_MEDIA: onMetadataChanged: " + metadata);
            }
            mMediaArtworkProcessor.clearCache();
            mMediaMetadata = metadata;
            dispatchUpdateMediaMetaData(true /* changed */, true /* allowAnimation */);
        }
    };

    @Inject
    public NotificationMediaManager(
            Context context,
            Lazy<ShadeController> shadeController,
            Lazy<StatusBarWindowController> statusBarWindowController,
            NotificationEntryManager notificationEntryManager,
            MediaArtworkProcessor mediaArtworkProcessor) {
        mContext = context;
        mMediaArtworkProcessor = mediaArtworkProcessor;
        mMediaListeners = new ArrayList<>();
        mMediaSessionManager
                = (MediaSessionManager) mContext.getSystemService(Context.MEDIA_SESSION_SERVICE);
        // TODO: use MediaSessionManager.SessionListener to hook us up to future updates
        // in session state
        mShadeController = shadeController;
        mStatusBarWindowController = statusBarWindowController;
        mEntryManager = notificationEntryManager;
        notificationEntryManager.addNotificationEntryListener(new NotificationEntryListener() {
            @Override
            public void onEntryRemoved(
                    NotificationEntry entry,
                    NotificationVisibility visibility,
                    boolean removedByUser) {
                onNotificationRemoved(entry.key);
            }
        });

        mShowCompactMediaSeekbar = "true".equals(
                DeviceConfig.getProperty(DeviceConfig.NAMESPACE_SYSTEMUI,
                    SystemUiDeviceConfigFlags.COMPACT_MEDIA_SEEKBAR_ENABLED));

        DeviceConfig.addOnPropertyChangedListener(DeviceConfig.NAMESPACE_SYSTEMUI,
                mContext.getMainExecutor(),
                mPropertyChangedListener);
    }

    public void setUpWithPresenter(NotificationPresenter presenter) {
        mPresenter = presenter;
    }

    public void onNotificationRemoved(String key) {
        if (key.equals(mMediaNotificationKey)) {
            clearCurrentMediaNotification();
            dispatchUpdateMediaMetaData(true /* changed */, true /* allowEnterAnimation */);
        }
    }

    public String getMediaNotificationKey() {
        return mMediaNotificationKey;
    }

    public MediaMetadata getMediaMetadata() {
        return mMediaMetadata;
    }

    public boolean getShowCompactMediaSeekbar() {
        return mShowCompactMediaSeekbar;
    }

    public Icon getMediaIcon() {
        if (mMediaNotificationKey == null) {
            return null;
        }
        synchronized (mEntryManager.getNotificationData()) {
            NotificationEntry entry = mEntryManager.getNotificationData().get(mMediaNotificationKey);
            if (entry == null || entry.expandedIcon == null) {
                return null;
            }

            return entry.expandedIcon.getSourceIcon();
        }
    }

    public void addCallback(MediaListener callback) {
        mMediaListeners.add(callback);
        callback.onMetadataChanged(mMediaMetadata);
    }

    public void removeCallback(MediaListener callback) {
        mMediaListeners.remove(callback);
    }

    public void findAndUpdateMediaNotifications() {
        boolean metaDataChanged = false;

        synchronized (mEntryManager.getNotificationData()) {
            ArrayList<NotificationEntry> activeNotifications =
                    mEntryManager.getNotificationData().getActiveNotifications();
            final int N = activeNotifications.size();

            // Promote the media notification with a controller in 'playing' state, if any.
            NotificationEntry mediaNotification = null;
            MediaController controller = null;
            for (int i = 0; i < N; i++) {
                final NotificationEntry entry = activeNotifications.get(i);

                if (entry.isMediaNotification()) {
                    final MediaSession.Token token =
                            entry.notification.getNotification().extras.getParcelable(
                                    Notification.EXTRA_MEDIA_SESSION);
                    if (token != null) {
                        MediaController aController = new MediaController(mContext, token);
                        if (PlaybackState.STATE_PLAYING ==
                                getMediaControllerPlaybackState(aController)) {
                            if (DEBUG_MEDIA) {
                                Log.v(TAG, "DEBUG_MEDIA: found mediastyle controller matching "
                                        + entry.notification.getKey());
                            }
                            mediaNotification = entry;
                            controller = aController;
                            break;
                        }
                    }
                }
            }
            if (mediaNotification == null) {
                // Still nothing? OK, let's just look for live media sessions and see if they match
                // one of our notifications. This will catch apps that aren't (yet!) using media
                // notifications.

                if (mMediaSessionManager != null) {
                    // TODO: Should this really be for all users?
                    final List<MediaController> sessions
                            = mMediaSessionManager.getActiveSessionsForUser(
                            null,
                            UserHandle.USER_ALL);

                    for (MediaController aController : sessions) {
                        if (PlaybackState.STATE_PLAYING ==
                                getMediaControllerPlaybackState(aController)) {
                            // now to see if we have one like this
                            final String pkg = aController.getPackageName();

                            for (int i = 0; i < N; i++) {
                                final NotificationEntry entry = activeNotifications.get(i);
                                if (entry.notification.getPackageName().equals(pkg)) {
                                    if (DEBUG_MEDIA) {
                                        Log.v(TAG, "DEBUG_MEDIA: found controller matching "
                                                + entry.notification.getKey());
                                    }
                                    controller = aController;
                                    mediaNotification = entry;
                                    break;
                                }
                            }
                        }
                    }
                }
            }

            if (controller != null && !sameSessions(mMediaController, controller)) {
                // We have a new media session
                clearCurrentMediaNotificationSession();
                mMediaController = controller;
                mMediaController.registerCallback(mMediaListener);
                mMediaMetadata = mMediaController.getMetadata();
                if (DEBUG_MEDIA) {
                    Log.v(TAG, "DEBUG_MEDIA: insert listener, found new controller: "
                            + mMediaController + ", receive metadata: " + mMediaMetadata);
                }

                metaDataChanged = true;
            }

            if (mediaNotification != null
                    && !mediaNotification.notification.getKey().equals(mMediaNotificationKey)) {
                mMediaNotificationKey = mediaNotification.notification.getKey();
                if (DEBUG_MEDIA) {
                    Log.v(TAG, "DEBUG_MEDIA: Found new media notification: key="
                            + mMediaNotificationKey);
                }
            }
        }

        if (metaDataChanged) {
            mEntryManager.updateNotifications();
        }

        dispatchUpdateMediaMetaData(metaDataChanged, true /* allowEnterAnimation */);
    }

    public void clearCurrentMediaNotification() {
        mMediaNotificationKey = null;
        clearCurrentMediaNotificationSession();
    }

    private void dispatchUpdateMediaMetaData(boolean changed, boolean allowEnterAnimation) {
        if (mPresenter != null) {
            mPresenter.updateMediaMetaData(changed, allowEnterAnimation);
        }
        ArrayList<MediaListener> callbacks = new ArrayList<>(mMediaListeners);
        for (int i = 0; i < callbacks.size(); i++) {
            callbacks.get(i).onMetadataChanged(mMediaMetadata);
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.print("    mMediaSessionManager=");
        pw.println(mMediaSessionManager);
        pw.print("    mMediaNotificationKey=");
        pw.println(mMediaNotificationKey);
        pw.print("    mMediaController=");
        pw.print(mMediaController);
        if (mMediaController != null) {
            pw.print(" state=" + mMediaController.getPlaybackState());
        }
        pw.println();
        pw.print("    mMediaMetadata=");
        pw.print(mMediaMetadata);
        if (mMediaMetadata != null) {
            pw.print(" title=" + mMediaMetadata.getText(MediaMetadata.METADATA_KEY_TITLE));
        }
        pw.println();
    }

    private boolean isPlaybackActive(int state) {
        return state != PlaybackState.STATE_STOPPED && state != PlaybackState.STATE_ERROR
                && state != PlaybackState.STATE_NONE;
    }

    private boolean sameSessions(MediaController a, MediaController b) {
        if (a == b) {
            return true;
        }
        if (a == null) {
            return false;
        }
        return a.controlsSameSession(b);
    }

    private int getMediaControllerPlaybackState(MediaController controller) {
        if (controller != null) {
            final PlaybackState playbackState = controller.getPlaybackState();
            if (playbackState != null) {
                return playbackState.getState();
            }
        }
        return PlaybackState.STATE_NONE;
    }

    private void clearCurrentMediaNotificationSession() {
        mMediaArtworkProcessor.clearCache();
        mMediaMetadata = null;
        if (mMediaController != null) {
            if (DEBUG_MEDIA) {
                Log.v(TAG, "DEBUG_MEDIA: Disconnecting from old controller: "
                        + mMediaController.getPackageName());
            }
            mMediaController.unregisterCallback(mMediaListener);
        }
        mMediaController = null;
    }

    /**
     * Refresh or remove lockscreen artwork from media metadata or the lockscreen wallpaper.
     */
    public void updateMediaMetaData(boolean metaDataChanged, boolean allowEnterAnimation) {
        Trace.beginSection("StatusBar#updateMediaMetaData");
        if (!SHOW_LOCKSCREEN_MEDIA_ARTWORK) {
            Trace.endSection();
            return;
        }

        if (mBackdrop == null) {
            Trace.endSection();
            return; // called too early
        }

        boolean wakeAndUnlock = mBiometricUnlockController != null
            && mBiometricUnlockController.isWakeAndUnlock();
        if (mKeyguardMonitor.isLaunchTransitionFadingAway() || wakeAndUnlock) {
            mBackdrop.setVisibility(View.INVISIBLE);
            Trace.endSection();
            return;
        }

        MediaMetadata mediaMetadata = getMediaMetadata();

        if (DEBUG_MEDIA) {
            Log.v(TAG, "DEBUG_MEDIA: updating album art for notification "
                    + getMediaNotificationKey()
                    + " metadata=" + mediaMetadata
                    + " metaDataChanged=" + metaDataChanged
                    + " state=" + mStatusBarStateController.getState());
        }

        Drawable artworkDrawable = null;
        if (mediaMetadata != null) {
            Bitmap artworkBitmap = mediaMetadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (artworkBitmap == null) {
                artworkBitmap = mediaMetadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                // might still be null
            }
            if (artworkBitmap != null) {
                int notificationColor;
                synchronized (mEntryManager.getNotificationData()) {
                    NotificationEntry entry = mEntryManager.getNotificationData()
                            .get(mMediaNotificationKey);
                    if (entry == null || entry.getRow() == null) {
                        notificationColor = Color.TRANSPARENT;
                    } else {
                        notificationColor = entry.getRow().calculateBgColor();
                    }
                }
                Bitmap bmp = mMediaArtworkProcessor.processArtwork(mContext, artworkBitmap,
                        notificationColor);
                artworkDrawable = new BitmapDrawable(mBackdropBack.getResources(), bmp);
            }
        }
        boolean allowWhenShade = false;
        if (ENABLE_LOCKSCREEN_WALLPAPER && artworkDrawable == null) {
            Bitmap lockWallpaper =
                    mLockscreenWallpaper != null ? mLockscreenWallpaper.getBitmap() : null;
            if (lockWallpaper != null) {
                artworkDrawable = new LockscreenWallpaper.WallpaperDrawable(
                        mBackdropBack.getResources(), lockWallpaper);
                // We're in the SHADE mode on the SIM screen - yet we still need to show
                // the lockscreen wallpaper in that mode.
                allowWhenShade = mStatusBarStateController.getState() == KEYGUARD;
            }
        }

        ShadeController shadeController = mShadeController.get();
        StatusBarWindowController windowController = mStatusBarWindowController.get();
        boolean hideBecauseOccluded = shadeController != null && shadeController.isOccluded();

        final boolean hasArtwork = artworkDrawable != null;
        mColorExtractor.setHasBackdrop(hasArtwork);
        if (mScrimController != null) {
            mScrimController.setHasBackdrop(hasArtwork);
        }

        if ((hasArtwork || DEBUG_MEDIA_FAKE_ARTWORK)
                && (mStatusBarStateController.getState() != StatusBarState.SHADE || allowWhenShade)
                &&  mBiometricUnlockController != null && mBiometricUnlockController.getMode()
                        != BiometricUnlockController.MODE_WAKE_AND_UNLOCK_PULSING
                && !hideBecauseOccluded) {
            // time to show some art!
            if (mBackdrop.getVisibility() != View.VISIBLE) {
                mBackdrop.setVisibility(View.VISIBLE);
                if (allowEnterAnimation) {
                    mBackdrop.setAlpha(0);
                    mBackdrop.animate().alpha(1f);
                } else {
                    mBackdrop.animate().cancel();
                    mBackdrop.setAlpha(1f);
                }
                if (windowController != null) {
                    windowController.setBackdropShowing(true);
                }
                metaDataChanged = true;
                if (DEBUG_MEDIA) {
                    Log.v(TAG, "DEBUG_MEDIA: Fading in album artwork");
                }
            }
            if (metaDataChanged) {
                if (mBackdropBack.getDrawable() != null) {
                    Drawable drawable =
                            mBackdropBack.getDrawable().getConstantState()
                                    .newDrawable(mBackdropFront.getResources()).mutate();
                    mBackdropFront.setImageDrawable(drawable);
                    mBackdropFront.setAlpha(1f);
                    mBackdropFront.setVisibility(View.VISIBLE);
                } else {
                    mBackdropFront.setVisibility(View.INVISIBLE);
                }

                if (DEBUG_MEDIA_FAKE_ARTWORK) {
                    final int c = 0xFF000000 | (int)(Math.random() * 0xFFFFFF);
                    Log.v(TAG, String.format("DEBUG_MEDIA: setting new color: 0x%08x", c));
                    mBackdropBack.setBackgroundColor(0xFFFFFFFF);
                    mBackdropBack.setImageDrawable(new ColorDrawable(c));
                } else {
                    mBackdropBack.setImageDrawable(artworkDrawable);
                }

                if (mBackdropFront.getVisibility() == View.VISIBLE) {
                    if (DEBUG_MEDIA) {
                        Log.v(TAG, "DEBUG_MEDIA: Crossfading album artwork from "
                                + mBackdropFront.getDrawable()
                                + " to "
                                + mBackdropBack.getDrawable());
                    }
                    mBackdropFront.animate()
                            .setDuration(250)
                            .alpha(0f).withEndAction(mHideBackdropFront);
                }
            }
        } else {
            // need to hide the album art, either because we are unlocked, on AOD
            // or because the metadata isn't there to support it
            if (mBackdrop.getVisibility() != View.GONE) {
                if (DEBUG_MEDIA) {
                    Log.v(TAG, "DEBUG_MEDIA: Fading out album artwork");
                }
                boolean cannotAnimateDoze = shadeController != null
                        && shadeController.isDozing()
                        && !ScrimState.AOD.getAnimateChange();
                if (mBiometricUnlockController != null && mBiometricUnlockController.getMode()
                        == BiometricUnlockController.MODE_WAKE_AND_UNLOCK_PULSING
                        || hideBecauseOccluded || cannotAnimateDoze) {

                    // We are unlocking directly - no animation!
                    mBackdrop.setVisibility(View.GONE);
                    mBackdropBack.setImageDrawable(null);
                    if (windowController != null) {
                        windowController.setBackdropShowing(false);
                    }
                } else {
                    if (windowController != null) {
                        windowController.setBackdropShowing(false);
                    }
                    mBackdrop.animate()
                            .alpha(0)
                            .setInterpolator(Interpolators.ACCELERATE_DECELERATE)
                            .setDuration(300)
                            .setStartDelay(0)
                            .withEndAction(() -> {
                                mBackdrop.setVisibility(View.GONE);
                                mBackdropFront.animate().cancel();
                                mBackdropBack.setImageDrawable(null);
                                mHandler.post(mHideBackdropFront);
                            });
                    if (mKeyguardMonitor.isKeyguardFadingAway()) {
                        mBackdrop.animate()
                                // Make it disappear faster, as the focus should be on the activity
                                // behind.
                                .setDuration(mKeyguardMonitor.getKeyguardFadingAwayDuration() / 2)
                                .setStartDelay(mKeyguardMonitor.getKeyguardFadingAwayDelay())
                                .setInterpolator(Interpolators.LINEAR)
                                .start();
                    }
                }
            }
        }
        Trace.endSection();
    }

    public void setup(BackDropView backdrop, ImageView backdropFront, ImageView backdropBack,
            ScrimController scrimController, LockscreenWallpaper lockscreenWallpaper) {
        mBackdrop = backdrop;
        mBackdropFront = backdropFront;
        mBackdropBack = backdropBack;
        mScrimController = scrimController;
        mLockscreenWallpaper = lockscreenWallpaper;
    }

    public void setBiometricUnlockController(BiometricUnlockController biometricUnlockController) {
        mBiometricUnlockController = biometricUnlockController;
    }

    /**
     * Hide the album artwork that is fading out and release its bitmap.
     */
    protected final Runnable mHideBackdropFront = new Runnable() {
        @Override
        public void run() {
            if (DEBUG_MEDIA) {
                Log.v(TAG, "DEBUG_MEDIA: removing fade layer");
            }
            mBackdropFront.setVisibility(View.INVISIBLE);
            mBackdropFront.animate().cancel();
            mBackdropFront.setImageDrawable(null);
        }
    };

    public interface MediaListener {
        void onMetadataChanged(MediaMetadata metadata);
    }
}
