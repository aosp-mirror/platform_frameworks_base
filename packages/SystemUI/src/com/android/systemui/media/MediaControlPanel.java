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
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.RippleDrawable;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.ThumbnailUtils;
import android.media.session.MediaController;
import android.media.session.MediaController.PlaybackInfo;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.service.media.MediaBrowserService;
import android.text.TextUtils;
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
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.qs.QSMediaBrowser;
import com.android.systemui.util.Assert;

import java.io.IOException;
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
    private final ActivityStarter mActivityStarter;

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
    private boolean mIsRemotePlayback;
    private QSMediaBrowser mQSMediaBrowser;

    // Button IDs used in notifications
    protected static final int[] NOTIF_ACTION_IDS = {
            com.android.internal.R.id.action0,
            com.android.internal.R.id.action1,
            com.android.internal.R.id.action2,
            com.android.internal.R.id.action3,
            com.android.internal.R.id.action4
    };

    // URI fields to try loading album art from
    private static final String[] ART_URIS = {
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI
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
            if (s == PlaybackState.STATE_NONE) {
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
     * @param activityStarter activity starter
     */
    public MediaControlPanel(Context context, ViewGroup parent,
            @Nullable LocalMediaManager routeManager, @LayoutRes int layoutId, int[] actionIds,
            Executor foregroundExecutor, Executor backgroundExecutor,
            ActivityStarter activityStarter) {
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
        mActivityStarter = activityStarter;
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
     * @param largeIcon
     * @param iconColor
     * @param bgColor
     * @param contentIntent
     * @param appNameString
     * @param key
     */
    public void setMediaSession(MediaSession.Token token, Drawable iconDrawable, Icon largeIcon,
            int iconColor, int bgColor, PendingIntent contentIntent, String appNameString,
            String key) {
        // Ensure that component names are updated if token has changed
        if (mToken == null || !mToken.equals(token)) {
            if (mQSMediaBrowser != null) {
                Log.d(TAG, "Disconnecting old media browser");
                mQSMediaBrowser.disconnect();
                mQSMediaBrowser = null;
            }
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
        final String pkgName = mController.getPackageName();
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
                mActivityStarter.postStartActivityDismissingKeyguard(contentIntent);
            });
        }

        // App icon
        ImageView appIcon = mMediaNotifView.findViewById(R.id.icon);
        iconDrawable.setTint(mForegroundColor);
        appIcon.setImageDrawable(iconDrawable);

        // Transfer chip
        mSeamless = mMediaNotifView.findViewById(R.id.media_seamless);
        if (mSeamless != null) {
            if (mLocalMediaManager != null) {
                mSeamless.setVisibility(View.VISIBLE);
                updateDevice(mLocalMediaManager.getCurrentConnectedDevice());
                mSeamless.setOnClickListener(v -> {
                    final Intent intent = new Intent()
                            .setAction(MediaOutputSliceConstants.ACTION_MEDIA_OUTPUT)
                            .putExtra(MediaOutputSliceConstants.EXTRA_PACKAGE_NAME,
                                    mController.getPackageName())
                            .putExtra(MediaOutputSliceConstants.KEY_MEDIA_SESSION_TOKEN, mToken);
                    mActivityStarter.startActivity(intent, false, true /* dismissShade */,
                            Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                });
            } else {
                Log.d(TAG, "LocalMediaManager is null. Not binding output chip for pkg=" + pkgName);
            }
        }
        PlaybackInfo playbackInfo = mController.getPlaybackInfo();
        if (playbackInfo != null) {
            mIsRemotePlayback = playbackInfo.getPlaybackType() == PlaybackInfo.PLAYBACK_TYPE_REMOTE;
        } else {
            Log.d(TAG, "PlaybackInfo was null. Defaulting to local playback.");
            mIsRemotePlayback = false;
        }

        makeActive();

        // App title (not in mini player)
        TextView appName = mMediaNotifView.findViewById(R.id.app_name);
        if (appName != null) {
            appName.setText(appNameString);
            appName.setTextColor(mForegroundColor);
        }

        // Can be null!
        MediaMetadata mediaMetadata = mController.getMetadata();

        ImageView albumView = mMediaNotifView.findViewById(R.id.album_art);
        if (albumView != null) {
            // Resize art in a background thread
            mBackgroundExecutor.execute(() -> processAlbumArt(mediaMetadata, largeIcon, albumView));
        }

        // Song name
        TextView titleText = mMediaNotifView.findViewById(R.id.header_title);
        String songName = "";
        if (mediaMetadata != null) {
            songName = mediaMetadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        }
        titleText.setText(songName);
        titleText.setTextColor(mForegroundColor);

        // Artist name (not in mini player)
        TextView artistText = mMediaNotifView.findViewById(R.id.header_artist);
        if (artistText != null) {
            String artistName = "";
            if (mediaMetadata != null) {
                artistName = mediaMetadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            }
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
        Bitmap albumArt = null;

        // First try loading from URI
        albumArt = loadBitmapFromUri(description.getIconUri());

        // Then check bitmap
        if (albumArt == null) {
            albumArt = description.getIconBitmap();
        }

        processAlbumArtInternal(albumArt, albumView);
    }

    /**
     * Process album art for layout
     * @param metadata media metadata
     * @param largeIcon from notification, checked as a fallback if metadata does not have art
     * @param albumView view to hold the album art
     */
    private void processAlbumArt(MediaMetadata metadata, Icon largeIcon, ImageView albumView) {
        Bitmap albumArt = null;

        if (metadata != null) {
            // First look in URI fields
            for (String field : ART_URIS) {
                String uriString = metadata.getString(field);
                if (!TextUtils.isEmpty(uriString)) {
                    albumArt = loadBitmapFromUri(Uri.parse(uriString));
                    if (albumArt != null) {
                        Log.d(TAG, "loaded art from " + field);
                        break;
                    }
                }
            }

            // Then check bitmap field
            if (albumArt == null) {
                albumArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            }
        }

        // Finally try the notification's largeIcon
        if (albumArt == null && largeIcon != null) {
            albumArt = largeIcon.getBitmap();
        }

        processAlbumArtInternal(albumArt, albumView);
    }

    /**
     * Load a bitmap from a URI
     * @param uri
     * @return bitmap, or null if couldn't be loaded
     */
    private Bitmap loadBitmapFromUri(Uri uri) {
        // ImageDecoder requires a scheme of the following types
        if (uri.getScheme() == null) {
            return null;
        }

        if (!uri.getScheme().equals(ContentResolver.SCHEME_CONTENT)
                && !uri.getScheme().equals(ContentResolver.SCHEME_ANDROID_RESOURCE)
                && !uri.getScheme().equals(ContentResolver.SCHEME_FILE)) {
            return null;
        }

        ImageDecoder.Source source = ImageDecoder.createSource(mContext.getContentResolver(), uri);
        try {
            return ImageDecoder.decodeBitmap(source);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Resize and crop the image if provided and update the control view
     * @param albumArt Bitmap of art to display, or null to hide view
     * @param albumView View that will hold the art
     */
    private void processAlbumArtInternal(@Nullable Bitmap albumArt, ImageView albumView) {
        // Resize
        RoundedBitmapDrawable roundedDrawable = null;
        if (albumArt != null) {
            float radius = mContext.getResources().getDimension(R.dimen.qs_media_corner_radius);
            Bitmap original = albumArt.copy(Bitmap.Config.ARGB_8888, true);
            int albumSize = (int) mContext.getResources().getDimension(
                    R.dimen.qs_media_album_size);
            Bitmap scaled = ThumbnailUtils.extractThumbnail(original, albumSize, albumSize);
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

        if (mIsRemotePlayback) {
            mSeamless.setEnabled(false);
            mSeamless.setAlpha(0.38f);
            iconView.setImageResource(R.drawable.ic_hardware_speaker);
            iconView.setVisibility(View.VISIBLE);
            iconView.setImageTintList(fgTintList);
            deviceName.setText(R.string.media_seamless_remote_device);
        } else if (device != null) {
            mSeamless.setEnabled(true);
            mSeamless.setAlpha(1f);
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
            Log.d(TAG, "device is null. Not binding output chip.");
            mSeamless.setEnabled(true);
            mSeamless.setAlpha(1f);
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
            if (mQSMediaBrowser != null) {
                mQSMediaBrowser.disconnect();
            }
            mQSMediaBrowser = new QSMediaBrowser(mContext, new QSMediaBrowser.Callback(){
                @Override
                public void onConnected() {
                    Log.d(TAG, "Successfully restarted");
                }
                @Override
                public void onError() {
                    Log.e(TAG, "Error restarting");
                    mQSMediaBrowser.disconnect();
                    mQSMediaBrowser = null;
                }
            }, mServiceComponent);
            mQSMediaBrowser.restart();
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
        if (mQSMediaBrowser != null) {
            mQSMediaBrowser.disconnect();
        }
        mQSMediaBrowser = new QSMediaBrowser(mContext,
                new QSMediaBrowser.Callback() {
                    @Override
                    public void onConnected() {
                        Log.d(TAG, "yes we can resume with " + componentName);
                        mServiceComponent = componentName;
                        updateResumptionList(componentName);
                        mQSMediaBrowser.disconnect();
                        mQSMediaBrowser = null;
                    }

                    @Override
                    public void onError() {
                        Log.d(TAG, "Cannot resume with " + componentName);
                        mServiceComponent = null;
                        if (!hasMediaSession()) {
                            // If it's not active and we can't resume, remove
                            removePlayer();
                        }
                        mQSMediaBrowser.disconnect();
                        mQSMediaBrowser = null;
                    }
                },
                componentName);
        mQSMediaBrowser.testConnection();
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
