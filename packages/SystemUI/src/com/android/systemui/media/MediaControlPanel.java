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

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.RippleDrawable;
import android.media.session.MediaController;
import android.media.session.MediaController.PlaybackInfo;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.service.media.MediaBrowserService;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.constraintlayout.motion.widget.Key;
import androidx.constraintlayout.motion.widget.KeyAttributes;
import androidx.constraintlayout.motion.widget.KeyFrames;
import androidx.constraintlayout.motion.widget.MotionLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

import com.android.settingslib.Utils;
import com.android.settingslib.media.LocalMediaManager;
import com.android.settingslib.media.MediaDevice;
import com.android.settingslib.media.MediaOutputSliceConstants;
import com.android.settingslib.widget.AdaptiveIcon;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.qs.QSMediaBrowser;
import com.android.systemui.util.Assert;
import com.android.systemui.util.concurrency.DelayableExecutor;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * A view controller used for Media Playback.
 */
public class MediaControlPanel {
    private static final String TAG = "MediaControlPanel";
    @Nullable private final LocalMediaManager mLocalMediaManager;

    // Button IDs for QS controls
    static final int[] ACTION_IDS = {
            R.id.action0,
            R.id.action1,
            R.id.action2,
            R.id.action3,
            R.id.action4
    };

    private final SeekBarViewModel mSeekBarViewModel;
    private final SeekBarObserver mSeekBarObserver;
    private final Executor mForegroundExecutor;
    protected final Executor mBackgroundExecutor;
    private final ActivityStarter mActivityStarter;

    private Context mContext;
    private MotionLayout mMediaNotifView;
    private final View mBackground;
    private View mSeamless;
    private MediaSession.Token mToken;
    private MediaController mController;
    private int mForegroundColor;
    private int mBackgroundColor;
    private MediaDevice mDevice;
    protected ComponentName mServiceComponent;
    private boolean mIsRegistered = false;
    private final List<KeyFrames> mKeyFrames;
    private String mKey;
    private int mAlbumArtSize;
    private int mAlbumArtRadius;

    public static final String MEDIA_PREFERENCES = "media_control_prefs";
    public static final String MEDIA_PREFERENCE_KEY = "browser_components";
    private SharedPreferences mSharedPrefs;
    private boolean mCheckedForResumption = false;
    private boolean mIsRemotePlayback;
    private QSMediaBrowser mQSMediaBrowser;

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
     * @param foregroundExecutor foreground executor
     * @param backgroundExecutor background executor, used for processing artwork
     * @param activityStarter activity starter
     */
    public MediaControlPanel(Context context, ViewGroup parent,
            @Nullable LocalMediaManager routeManager, Executor foregroundExecutor,
            DelayableExecutor backgroundExecutor, ActivityStarter activityStarter) {
        mContext = context;
        LayoutInflater inflater = LayoutInflater.from(mContext);
        mMediaNotifView = (MotionLayout) inflater.inflate(R.layout.qs_media_panel, parent, false);
        mBackground = mMediaNotifView.findViewById(R.id.media_background);
        mKeyFrames = mMediaNotifView.getDefinedTransitions().get(0).getKeyFrameList();
        mLocalMediaManager = routeManager;
        mForegroundExecutor = foregroundExecutor;
        mBackgroundExecutor = backgroundExecutor;
        mActivityStarter = activityStarter;
        mSeekBarViewModel = new SeekBarViewModel(backgroundExecutor);
        mSeekBarObserver = new SeekBarObserver(getView());
        // TODO: we should pause this whenever the screen is off / panel is collapsed etc.
        mSeekBarViewModel.getProgress().observeForever(mSeekBarObserver);
        SeekBar bar = getView().findViewById(R.id.media_progress_bar);
        bar.setOnSeekBarChangeListener(mSeekBarViewModel.getSeekBarListener());
        bar.setOnTouchListener(mSeekBarViewModel.getSeekBarTouchListener());
        loadDimens();
    }

    public void onDestroy() {
        mSeekBarViewModel.getProgress().removeObserver(mSeekBarObserver);
        makeInactive();
    }

    private void loadDimens() {
        mAlbumArtRadius = mContext.getResources().getDimensionPixelSize(
                Utils.getThemeAttr(mContext, android.R.attr.dialogCornerRadius));
        mAlbumArtSize = mContext.getResources().getDimensionPixelSize(R.dimen.qs_media_album_size);
    }

    /**
     * Get the view used to display media controls
     * @return the view
     */
    public MotionLayout getView() {
        return mMediaNotifView;
    }

    /**
     * Sets the listening state of the player.
     *
     * Should be set to true when the QS panel is open. Otherwise, false. This is a signal to avoid
     * unnecessary work when the QS panel is closed.
     *
     * @param listening True when player should be active. Otherwise, false.
     */
    public void setListening(boolean listening) {
        mSeekBarViewModel.setListening(listening);
    }

    /**
     * Get the context
     * @return context
     */
    public Context getContext() {
        return mContext;
    }

    /**
     * Bind this view based on the data given
     */
    public void bind(@NotNull MediaData data) {
        MediaSession.Token token = data.getToken();
        mForegroundColor = data.getForegroundColor();
        mBackgroundColor = data.getBackgroundColor();
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

        mController = new MediaController(mContext, mToken);

        // Try to find a browser service component for this app
        // TODO also check for a media button receiver intended for restarting (b/154127084)
        // Only check if we haven't tried yet or the session token changed
        final String pkgName = mController.getPackageName();
        if (mServiceComponent == null && !mCheckedForResumption) {
            Log.d(TAG, "Checking for service component");
            PackageManager pm = mContext.getPackageManager();
            Intent resumeIntent = new Intent(MediaBrowserService.SERVICE_INTERFACE);
            List<ResolveInfo> resumeInfo = pm.queryIntentServices(resumeIntent, 0);
            // TODO: look into this resumption
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

        mMediaNotifView.requireViewById(R.id.media_background).setBackgroundTintList(
                ColorStateList.valueOf(mBackgroundColor));

        // Click action
        PendingIntent clickIntent = data.getClickIntent();
        if (clickIntent != null) {
            mMediaNotifView.setOnClickListener(v -> {
                mActivityStarter.postStartActivityDismissingKeyguard(clickIntent);
            });
        }

        ImageView albumView = mMediaNotifView.findViewById(R.id.album_art);
        // TODO: migrate this to a view with rounded corners instead of baking the rounding
        // into the bitmap
        Drawable artwork = createRoundedBitmap(data.getArtwork());
        albumView.setImageDrawable(artwork);

        // App icon
        ImageView appIcon = mMediaNotifView.requireViewById(R.id.icon);
        // TODO: look at iconDrawable
        Drawable iconDrawable = data.getAppIcon();
        iconDrawable.setTint(mForegroundColor);
        appIcon.setImageDrawable(iconDrawable);

        // Song name
        TextView titleText = mMediaNotifView.requireViewById(R.id.header_title);
        titleText.setText(data.getSong());
        titleText.setTextColor(data.getForegroundColor());

        // App title
        TextView appName = mMediaNotifView.requireViewById(R.id.app_name);
        appName.setText(data.getApp());
        appName.setTextColor(mForegroundColor);

        // Artist name
        TextView artistText = mMediaNotifView.requireViewById(R.id.header_artist);
        artistText.setText(data.getArtist());
        artistText.setTextColor(mForegroundColor);

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

        ConstraintSet expandedSet = mMediaNotifView.getConstraintSet(R.id.expanded);
        ConstraintSet collapsedSet = mMediaNotifView.getConstraintSet(R.id.collapsed);
        List<Integer> actionsWhenCollapsed = data.getActionsToShowInCompact();
        // Media controls
        int i = 0;
        List<MediaAction> actionIcons = data.getActions();
        for (; i < actionIcons.size() && i < ACTION_IDS.length; i++) {
            int actionId = ACTION_IDS[i];
            final ImageButton button = mMediaNotifView.findViewById(actionId);
            MediaAction mediaAction = actionIcons.get(i);
            button.setImageDrawable(mediaAction.getDrawable());
            button.setContentDescription(mediaAction.getContentDescription());
            button.setImageTintList(ColorStateList.valueOf(mForegroundColor));
            PendingIntent actionIntent = mediaAction.getIntent();

            if (mBackground.getBackground() instanceof IlluminationDrawable) {
                ((IlluminationDrawable) mBackground.getBackground())
                        .setupTouch(button, mMediaNotifView);
            }

            button.setOnClickListener(v -> {
                if (actionIntent != null) {
                    try {
                        actionIntent.send();
                    } catch (PendingIntent.CanceledException e) {
                        e.printStackTrace();
                    }
                }
            });
            boolean visibleInCompat = actionsWhenCollapsed.contains(i);
            updateKeyFrameVisibility(actionId, visibleInCompat);
            collapsedSet.setVisibility(actionId,
                    visibleInCompat ? ConstraintSet.VISIBLE : ConstraintSet.GONE);
            expandedSet.setVisibility(actionId, ConstraintSet.VISIBLE);
        }

        // Hide any unused buttons
        for (; i < ACTION_IDS.length; i++) {
            expandedSet.setVisibility(ACTION_IDS[i], ConstraintSet.GONE);
            collapsedSet.setVisibility(ACTION_IDS[i], ConstraintSet.GONE);
        }

        // Seek Bar
        final MediaController controller = new MediaController(getContext(), data.getToken());
        mBackgroundExecutor.execute(
                () -> mSeekBarViewModel.updateController(controller, data.getForegroundColor()));

        // Set up long press menu
        // TODO: b/156036025 bring back media guts

        makeActive();
    }

    private Drawable createRoundedBitmap(Icon icon) {
        if (icon == null) {
            return null;
        }
        // Let's scale down the View, such that the content always nicely fills the view.
        // ThumbnailUtils actually scales it down such that it may not be filled for odd aspect
        // ratios
        Drawable drawable = icon.loadDrawable(mContext);
        float aspectRatio = drawable.getIntrinsicHeight() / (float) drawable.getIntrinsicWidth();
        Rect bounds;
        if (aspectRatio > 1.0f) {
            bounds = new Rect(0, 0, mAlbumArtSize, (int) (mAlbumArtSize * aspectRatio));
        } else {
            bounds = new Rect(0, 0, (int) (mAlbumArtSize / aspectRatio), mAlbumArtSize);
        }
        if (bounds.width() > mAlbumArtSize || bounds.height() > mAlbumArtSize) {
            float offsetX = (bounds.width() - mAlbumArtSize) / 2.0f;
            float offsetY = (bounds.height() - mAlbumArtSize) / 2.0f;
            bounds.offset((int) -offsetX,(int) -offsetY);
        }
        drawable.setBounds(bounds);
        Bitmap scaled = Bitmap.createBitmap(mAlbumArtSize, mAlbumArtSize,
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(scaled);
        drawable.draw(canvas);
        RoundedBitmapDrawable artwork = RoundedBitmapDrawableFactory.create(
                mContext.getResources(), scaled);
        artwork.setCornerRadius(mAlbumArtRadius);
        return artwork;
    }

    /**
     * Updates the keyframe visibility such that only views that are not visible actually go
     * through a transition and fade in.
     *
     * @param actionId the id to change
     * @param visible is the view visible
     */
    private void updateKeyFrameVisibility(int actionId, boolean visible) {
        for (int i = 0; i < mKeyFrames.size(); i++) {
            KeyFrames keyframe = mKeyFrames.get(i);
            ArrayList<Key> viewKeyFrames = keyframe.getKeyFramesForView(actionId);
            for (int j = 0; j < viewKeyFrames.size(); j++) {
                Key key = viewKeyFrames.get(j);
                if (key instanceof KeyAttributes) {
                    KeyAttributes attributes = (KeyAttributes) key;
                    attributes.setValue("alpha", visible ? 1.0f : 0.0f);
                }
            }
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

        ConstraintSet expandedSet = mMediaNotifView.getConstraintSet(R.id.expanded);
        ConstraintSet collapsedSet = mMediaNotifView.getConstraintSet(R.id.collapsed);
        for (int i = 1; i < ACTION_IDS.length; i++) {
            expandedSet.setVisibility(ACTION_IDS[i], ConstraintSet.GONE);
            collapsedSet.setVisibility(ACTION_IDS[i], ConstraintSet.GONE);
        }

        // Add a restart button
        ImageButton btn = mMediaNotifView.findViewById(ACTION_IDS[0]);
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
        expandedSet.setVisibility(ACTION_IDS[0], ConstraintSet.VISIBLE);
        collapsedSet.setVisibility(ACTION_IDS[0], ConstraintSet.VISIBLE);

        mSeekBarViewModel.clearController();
        // TODO: fix guts
        //        View guts = mMediaNotifView.findViewById(R.id.media_guts);
        View options = mMediaNotifView.findViewById(R.id.qs_media_controls_options);

        mMediaNotifView.setOnLongClickListener(v -> {
            // Replace player view with close/cancel view
//            guts.setVisibility(View.GONE);
            options.setVisibility(View.VISIBLE);
            return true; // consumed click
        });
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
