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
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.service.media.MediaBrowserService;
import android.util.Log;
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
import com.android.systemui.qs.QSMediaBrowser;
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
    protected final Executor mBackgroundExecutor;

    private Context mContext;
    protected LinearLayout mMediaNotifView;
    private View mSeamless;
    private MediaSession.Token mToken;
    private MediaController mController;
    private int mForegroundColor;
    private int mBackgroundColor;
    private MediaDevice mDevice;
    protected ComponentName mServiceComponent;
    private boolean mIsRegistered = false;
    private String mKey;

    private final int[] mActionIds;

    public static final String MEDIA_PREFERENCES = "media_control_prefs";
    public static final String MEDIA_PREFERENCE_KEY = "browser_components";
    private SharedPreferences mSharedPrefs;
    private boolean mCheckedForResumption = false;

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
     * @param iconDrawable
     * @param iconColor
     * @param bgColor
     * @param contentIntent
     * @param appNameString
     * @param key
     */
    public void setMediaSession(MediaSession.Token token, Drawable iconDrawable, int iconColor,
            int bgColor, PendingIntent contentIntent, String appNameString, String key) {
        // Ensure that component names are updated if token has changed
        if (mToken == null || !mToken.equals(token)) {
            mToken = token;
            mServiceComponent = null;
            mCheckedForResumption = false;
        }

        mForegroundColor = iconColor;
        mBackgroundColor = bgColor;
        mController = new MediaController(mContext, mToken);
        mKey = key;

        // Try to find a browser service component for this app
        // TODO also check for a media button receiver intended for restarting (b/154127084)
        // Only check if we haven't tried yet or the session token changed
        String pkgName = mController.getPackageName();
        if (mServiceComponent == null && !mCheckedForResumption) {
            Log.d(TAG, "Checking for service component");
            PackageManager pm = mContext.getPackageManager();
            Intent resumeIntent = new Intent(MediaBrowserService.SERVICE_INTERFACE);
            List<ResolveInfo> resumeInfo = pm.queryIntentServices(resumeIntent, 0);
            if (resumeInfo != null) {
                for (ResolveInfo inf : resumeInfo) {
                    if (inf.serviceInfo.packageName.equals(mController.getPackageName())) {
                        mBackgroundExecutor.execute(() ->
                                tryUpdateResumptionList(inf.getComponentInfo().getComponentName()));
                        break;
                    }
                }
            }
            mCheckedForResumption = true;
        }

        mController.registerCallback(mSessionCallback);

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
        iconDrawable.setTint(mForegroundColor);
        appIcon.setImageDrawable(iconDrawable);

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

        // App title (not in mini player)
        TextView appName = mMediaNotifView.findViewById(R.id.app_name);
        if (appName != null) {
            appName.setText(appNameString);
            appName.setTextColor(mForegroundColor);
        }

        MediaMetadata mediaMetadata = mController.getMetadata();
        if (mediaMetadata == null) {
            Log.e(TAG, "Media metadata was null");
            return;
        }

        ImageView albumView = mMediaNotifView.findViewById(R.id.album_art);
        if (albumView != null) {
            // Resize art in a background thread
            mBackgroundExecutor.execute(() -> processAlbumArt(mediaMetadata, albumView));
        }

        // Song name
        TextView titleText = mMediaNotifView.findViewById(R.id.header_title);
        String songName = mediaMetadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        titleText.setText(songName);
        titleText.setTextColor(mForegroundColor);

        // Artist name (not in mini player)
        TextView artistText = mMediaNotifView.findViewById(R.id.header_artist);
        if (artistText != null) {
            String artistName = mediaMetadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            artistText.setText(artistName);
            artistText.setTextColor(mForegroundColor);
        }
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
     * @return the package name, or null if no controller
     */
    public String getMediaPlayerPackage() {
        if (mController == null) {
            return null;
        }
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
     * @param description media description
     * @param albumView view to hold the album art
     */
    protected void processAlbumArt(MediaDescription description, ImageView albumView) {
        Bitmap albumArt = description.getIconBitmap();
        //TODO check other fields (b/151054111, b/152067055)
        processAlbumArtInternal(albumArt, albumView);
    }

    /**
     * Process album art for layout
     * @param metadata media metadata
     * @param albumView view to hold the album art
     */
    private void processAlbumArt(MediaMetadata metadata, ImageView albumView) {
        Bitmap albumArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        //TODO check other fields (b/151054111, b/152067055)
        processAlbumArtInternal(albumArt, albumView);
    }

    private void processAlbumArtInternal(Bitmap albumArt, ImageView albumView) {
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
     * Puts controls into a resumption state if possible, or calls removePlayer if no component was
     * found that could resume playback
     */
    public void clearControls() {
        Log.d(TAG, "clearControls to resumption state package=" + getMediaPlayerPackage());
        if (mServiceComponent == null) {
            // If we don't have a way to resume, just remove the player altogether
            Log.d(TAG, "Removing unresumable controls");
            removePlayer();
            return;
        }
        resetButtons();
    }

    /**
     * Hide the media buttons and show only a restart button
     */
    protected void resetButtons() {
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
            QSMediaBrowser browser = new QSMediaBrowser(mContext, null, mServiceComponent);
            browser.restart();
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

    /**
     * Verify that we can connect to the given component with a MediaBrowser, and if so, add that
     * component to the list of resumption components
     */
    private void tryUpdateResumptionList(ComponentName componentName) {
        Log.d(TAG, "Testing if we can connect to " + componentName);
        QSMediaBrowser.testConnection(mContext,
                new QSMediaBrowser.Callback() {
                    @Override
                    public void onConnected() {
                        Log.d(TAG, "yes we can resume with " + componentName);
                        mServiceComponent = componentName;
                        updateResumptionList(componentName);
                    }

                    @Override
                    public void onError() {
                        Log.d(TAG, "Cannot resume with " + componentName);
                        mServiceComponent = null;
                        clearControls();
                        // remove
                    }
                },
                componentName);
    }

    /**
     * Add the component to the saved list of media browser services, checking for duplicates and
     * removing older components that exceed the maximum limit
     * @param componentName
     */
    private synchronized void updateResumptionList(ComponentName componentName) {
        // Add to front of saved list
        if (mSharedPrefs == null) {
            mSharedPrefs = mContext.getSharedPreferences(MEDIA_PREFERENCES, 0);
        }
        String componentString = componentName.flattenToString();
        String listString = mSharedPrefs.getString(MEDIA_PREFERENCE_KEY, null);
        if (listString == null) {
            listString = componentString;
        } else {
            String[] components = listString.split(QSMediaBrowser.DELIMITER);
            StringBuilder updated = new StringBuilder(componentString);
            int nBrowsers = 1;
            for (int i = 0; i < components.length
                    && nBrowsers < QSMediaBrowser.MAX_RESUMPTION_CONTROLS; i++) {
                if (componentString.equals(components[i])) {
                    continue;
                }
                updated.append(QSMediaBrowser.DELIMITER).append(components[i]);
                nBrowsers++;
            }
            listString = updated.toString();
        }
        mSharedPrefs.edit().putString(MEDIA_PREFERENCE_KEY, listString).apply();
    }

    /**
     * Called when a player can't be resumed to give it an opportunity to hide or remove itself
     */
    protected void removePlayer() { }
}
