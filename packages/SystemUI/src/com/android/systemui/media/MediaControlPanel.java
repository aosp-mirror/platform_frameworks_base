/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.media;

import android.annotation.LayoutRes;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.RippleDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnAttachStateChangeListener;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

import com.android.settingslib.media.LocalMediaManager;
import com.android.settingslib.media.MediaDevice;
import com.android.settingslib.media.MediaOutputSliceConstants;
import com.android.settingslib.widget.AdaptiveIcon;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.util.Assert;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Base media control panel for System UI
 */
public class MediaControlPanel {
    private static final String TAG = "MediaControlPanel";
    @Nullable private final LocalMediaManager mLocalMediaManager;
    private final Executor mForegroundExecutor;
    private final Executor mBackgroundExecutor;

    private Context mContext;
    protected LinearLayout mMediaNotifView;
    private View mSeamless;
    private MediaSession.Token mToken;
    private MediaController mController;
    private int mForegroundColor;
    private int mBackgroundColor;
    protected ComponentName mRecvComponent;
    private MediaDevice mDevice;
    private boolean mIsRegistered = false;
    private String mKey;

    private final int[] mActionIds;

    // Button IDs used in notifications
    protected static final int[] NOTIF_ACTION_IDS = {
            com.android.internal.R.id.action0,
            com.android.internal.R.id.action1,
            com.android.internal.R.id.action2,
            com.android.internal.R.id.action3,
            com.android.internal.R.id.action4
    };

    private final MediaController.Callback mSessionCallback = new MediaController.Callback() {
        @Override
        public void onSessionDestroyed() {
            Log.d(TAG, "session destroyed");
            mController.unregisterCallback(mSessionCallback);
            clearControls();
            makeInactive();
        }
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            final int s = state != null ? state.getState() : PlaybackState.STATE_NONE;
            // When the playback state is NONE or CONNECTING, transition the player to the
            // resumption state. State CONNECTING needs to be considered for Cast sessions. Ending
            // a cast session in YT results in the CONNECTING state, which makes sense if you
            // thinking of the session as waiting to connect to another cast device.
            if (s == PlaybackState.STATE_NONE || s == PlaybackState.STATE_CONNECTING) {
                Log.d(TAG, "playback state change will trigger resumption, state=" + state);
                clearControls();
                makeInactive();
            }
        }
    };

    private final OnAttachStateChangeListener mStateListener = new OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(View unused) {
            makeActive();
        }
        @Override
        public void onViewDetachedFromWindow(View unused) {
            makeInactive();
        }
    };

    private final LocalMediaManager.DeviceCallback mDeviceCallback =
            new LocalMediaManager.DeviceCallback() {
        @Override
        public void onDeviceListUpdate(List<MediaDevice> devices) {
            if (mLocalMediaManager == null) {
                return;
            }
            MediaDevice currentDevice = mLocalMediaManager.getCurrentConnectedDevice();
            // Check because this can be called several times while changing devices
            if (mDevice == null || !mDevice.equals(currentDevice)) {
                mDevice = currentDevice;
                updateDevice(mDevice);
            }
        }

        @Override
        public void onSelectedDeviceStateChanged(MediaDevice device, int state) {
            if (mDevice == null || !mDevice.equals(device)) {
                mDevice = device;
                updateDevice(mDevice);
            }
        }
    };

    /**
     * Initialize a new control panel
     * @param context
     * @param parent
     * @param manager
     * @param routeManager Manager used to listen for device change events.
     * @param layoutId layout resource to use for this control panel
     * @param actionIds resource IDs for action buttons in the layout
     * @param foregroundExecutor foreground executor
     * @param backgroundExecutor background executor, used for processing artwork
     */
    public MediaControlPanel(Context context, ViewGroup parent,
            @Nullable LocalMediaManager routeManager, @LayoutRes int layoutId, int[] actionIds,
            Executor foregroundExecutor, Executor backgroundExecutor) {
        mContext = context;
        LayoutInflater inflater = LayoutInflater.from(mContext);
        mMediaNotifView = (LinearLayout) inflater.inflate(layoutId, parent, false);
        // TODO(b/150854549): removeOnAttachStateChangeListener when this doesn't inflate views
        // mStateListener shouldn't need to be unregistered since this object shares the same
        // lifecycle with the inflated view. It would be better, however, if this controller used an
        // attach/detach of views instead of inflating them in the constructor, which would allow
        // mStateListener to be unregistered in detach.
        mMediaNotifView.addOnAttachStateChangeListener(mStateListener);
        mLocalMediaManager = routeManager;
        mActionIds = actionIds;
        mForegroundExecutor = foregroundExecutor;
        mBackgroundExecutor = backgroundExecutor;
    }

    /**
     * Get the view used to display media controls
     * @return the view
     */
    public View getView() {
        return mMediaNotifView;
    }

    /**
     * Get the context
     * @return context
     */
    public Context getContext() {
        return mContext;
    }

    /**
     * Update the media panel view for the given media session
     * @param token
     * @param icon
     * @param iconColor
     * @param bgColor
     * @param contentIntent
     * @param appNameString
     * @param key
     */
    public void setMediaSession(MediaSession.Token token, Icon icon, int iconColor,
            int bgColor, PendingIntent contentIntent, String appNameString, String key) {
        mToken = token;
        mForegroundColor = iconColor;
        mBackgroundColor = bgColor;
        mController = new MediaController(mContext, mToken);
        mKey = key;

        MediaMetadata mediaMetadata = mController.getMetadata();

        // Try to find a receiver for the media button that matches this app
        PackageManager pm = mContext.getPackageManager();
        Intent it = new Intent(Intent.ACTION_MEDIA_BUTTON);
        List<ResolveInfo> info = pm.queryBroadcastReceiversAsUser(it, 0, mContext.getUser());
        if (info != null) {
            for (ResolveInfo inf : info) {
                if (inf.activityInfo.packageName.equals(mController.getPackageName())) {
                    mRecvComponent = inf.getComponentInfo().getComponentName();
                }
            }
        }

        mController.registerCallback(mSessionCallback);

        if (mediaMetadata == null) {
            Log.e(TAG, "Media metadata was null");
            return;
        }

        ImageView albumView = mMediaNotifView.findViewById(R.id.album_art);
        if (albumView != null) {
            // Resize art in a background thread
            mBackgroundExecutor.execute(() -> processAlbumArt(mediaMetadata, albumView));
        }
        mMediaNotifView.setBackgroundTintList(ColorStateList.valueOf(mBackgroundColor));

        // Click action
        if (contentIntent != null) {
            mMediaNotifView.setOnClickListener(v -> {
                try {
                    contentIntent.send();
                    // Also close shade
                    mContext.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
                } catch (PendingIntent.CanceledException e) {
                    Log.e(TAG, "Pending intent was canceled", e);
                }
            });
        }

        // App icon
        ImageView appIcon = mMediaNotifView.findViewById(R.id.icon);
        Drawable iconDrawable = icon.loadDrawable(mContext);
        iconDrawable.setTint(mForegroundColor);
        appIcon.setImageDrawable(iconDrawable);

        // Song name
        TextView titleText = mMediaNotifView.findViewById(R.id.header_title);
        String songName = mediaMetadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        titleText.setText(songName);
        titleText.setTextColor(mForegroundColor);

        // Not in mini player:
        // App title
        TextView appName = mMediaNotifView.findViewById(R.id.app_name);
        if (appName != null) {
            appName.setText(appNameString);
            appName.setTextColor(mForegroundColor);
        }

        // Artist name
        TextView artistText = mMediaNotifView.findViewById(R.id.header_artist);
        if (artistText != null) {
            String artistName = mediaMetadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            artistText.setText(artistName);
            artistText.setTextColor(mForegroundColor);
        }

        // Transfer chip
        mSeamless = mMediaNotifView.findViewById(R.id.media_seamless);
        if (mSeamless != null && mLocalMediaManager != null) {
            mSeamless.setVisibility(View.VISIBLE);
            updateDevice(mLocalMediaManager.getCurrentConnectedDevice());
            ActivityStarter mActivityStarter = Dependency.get(ActivityStarter.class);
            mSeamless.setOnClickListener(v -> {
                final Intent intent = new Intent()
                        .setAction(MediaOutputSliceConstants.ACTION_MEDIA_OUTPUT)
                        .putExtra(MediaOutputSliceConstants.EXTRA_PACKAGE_NAME,
                                mController.getPackageName())
                        .putExtra(MediaOutputSliceConstants.KEY_MEDIA_SESSION_TOKEN, mToken);
                mActivityStarter.startActivity(intent, false, true /* dismissShade */,
                        Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            });
        }

        makeActive();
    }

    /**
     * Return the token for the current media session
     * @return the token
     */
    public MediaSession.Token getMediaSessionToken() {
        return mToken;
    }

    /**
     * Get the current media controller
     * @return the controller
     */
    public MediaController getController() {
        return mController;
    }

    /**
     * Get the name of the package associated with the current media controller
     * @return the package name
     */
    public String getMediaPlayerPackage() {
        return mController.getPackageName();
    }

    /**
     * Return the original notification's key
     * @return The notification key
     */
    public String getKey()  {
        return mKey;
    }

    /**
     * Check whether this player has an attached media session.
     * @return whether there is a controller with a current media session.
     */
    public boolean hasMediaSession() {
        return mController != null && mController.getPlaybackState() != null;
    }

    /**
     * Check whether the media controlled by this player is currently playing
     * @return whether it is playing, or false if no controller information
     */
    public boolean isPlaying() {
        return isPlaying(mController);
    }

    /**
     * Check whether the given controller is currently playing
     * @param controller media controller to check
     * @return whether it is playing, or false if no controller information
     */
    protected boolean isPlaying(MediaController controller) {
        if (controller == null) {
            return false;
        }

        PlaybackState state = controller.getPlaybackState();
        if (state == null) {
            return false;
        }

        return (state.getState() == PlaybackState.STATE_PLAYING);
    }

    /**
     * Process album art for layout
     * @param metadata media metadata
     * @param albumView view to hold the album art
     */
    private void processAlbumArt(MediaMetadata metadata, ImageView albumView) {
        Bitmap albumArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        float radius = mContext.getResources().getDimension(R.dimen.qs_media_corner_radius);
        RoundedBitmapDrawable roundedDrawable = null;
        if (albumArt != null) {
            Bitmap original = albumArt.copy(Bitmap.Config.ARGB_8888, true);
            int albumSize = (int) mContext.getResources().getDimension(
                    R.dimen.qs_media_album_size);
            Bitmap scaled = Bitmap.createScaledBitmap(original, albumSize, albumSize, false);
            roundedDrawable = RoundedBitmapDrawableFactory.create(mContext.getResources(), scaled);
            roundedDrawable.setCornerRadius(radius);
        } else {
            Log.e(TAG, "No album art available");
        }

        // Now that it's resized, update the UI
        final RoundedBitmapDrawable result = roundedDrawable;
        mForegroundExecutor.execute(() -> {
            if (result != null) {
                albumView.setImageDrawable(result);
                albumView.setVisibility(View.VISIBLE);
            } else {
                albumView.setImageDrawable(null);
                albumView.setVisibility(View.GONE);
            }
        });
    }

    /**
     * Update the current device information
     * @param device device information to display
     */
    private void updateDevice(MediaDevice device) {
        if (mSeamless == null) {
            return;
        }
        mForegroundExecutor.execute(() -> {
            updateChipInternal(device);
        });
    }

    private void updateChipInternal(MediaDevice device) {
        ColorStateList fgTintList = ColorStateList.valueOf(mForegroundColor);

        // Update the outline color
        LinearLayout viewLayout = (LinearLayout) mSeamless;
        RippleDrawable bkgDrawable = (RippleDrawable) viewLayout.getBackground();
        GradientDrawable rect = (GradientDrawable) bkgDrawable.getDrawable(0);
        rect.setStroke(2, mForegroundColor);
        rect.setColor(mBackgroundColor);

        ImageView iconView = mSeamless.findViewById(R.id.media_seamless_image);
        TextView deviceName = mSeamless.findViewById(R.id.media_seamless_text);
        deviceName.setTextColor(fgTintList);

        if (device != null) {
            Drawable icon = device.getIcon();
            iconView.setVisibility(View.VISIBLE);
            iconView.setImageTintList(fgTintList);

            if (icon instanceof AdaptiveIcon) {
                AdaptiveIcon aIcon = (AdaptiveIcon) icon;
                aIcon.setBackgroundColor(mBackgroundColor);
                iconView.setImageDrawable(aIcon);
            } else {
                iconView.setImageDrawable(icon);
            }
            deviceName.setText(device.getName());
        } else {
            // Reset to default
            iconView.setVisibility(View.GONE);
            deviceName.setText(com.android.internal.R.string.ext_media_seamless_action);
        }
    }

    /**
     * Put controls into a resumption state
     */
    public void clearControls() {
        Log.d(TAG, "clearControls to resumption state package=" + getMediaPlayerPackage());
        // Hide all the old buttons
        for (int i = 0; i < mActionIds.length; i++) {
            ImageButton thisBtn = mMediaNotifView.findViewById(mActionIds[i]);
            if (thisBtn != null) {
                thisBtn.setVisibility(View.GONE);
            }
        }

        // Add a restart button
        ImageButton btn = mMediaNotifView.findViewById(mActionIds[0]);
        btn.setOnClickListener(v -> {
            Log.d(TAG, "Attempting to restart session");
            // Send a media button event to previously found receiver
            if (mRecvComponent != null) {
                Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
                intent.setComponent(mRecvComponent);
                int keyCode = KeyEvent.KEYCODE_MEDIA_PLAY;
                intent.putExtra(
                        Intent.EXTRA_KEY_EVENT,
                        new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
                mContext.sendBroadcast(intent);
            } else {
                // If we don't have a receiver, try relaunching the activity instead
                if (mController.getSessionActivity() != null) {
                    try {
                        mController.getSessionActivity().send();
                    } catch (PendingIntent.CanceledException e) {
                        Log.e(TAG, "Pending intent was canceled", e);
                    }
                } else {
                    Log.e(TAG, "No receiver or activity to restart");
                }
            }
        });
        btn.setImageDrawable(mContext.getResources().getDrawable(R.drawable.lb_ic_play));
        btn.setImageTintList(ColorStateList.valueOf(mForegroundColor));
        btn.setVisibility(View.VISIBLE);
    }

    private void makeActive() {
        Assert.isMainThread();
        if (!mIsRegistered) {
            if (mLocalMediaManager != null) {
                mLocalMediaManager.registerCallback(mDeviceCallback);
                mLocalMediaManager.startScan();
            }
            mIsRegistered = true;
        }
    }

    private void makeInactive() {
        Assert.isMainThread();
        if (mIsRegistered) {
            if (mLocalMediaManager != null) {
                mLocalMediaManager.stopScan();
                mLocalMediaManager.unregisterCallback(mDeviceCallback);
            }
            mIsRegistered = false;
        }
    }

}
