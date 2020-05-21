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
import android.graphics.Color;
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
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.constraintlayout.motion.widget.Key;
import androidx.constraintlayout.motion.widget.KeyAttributes;
import androidx.constraintlayout.motion.widget.KeyFrames;
import androidx.constraintlayout.motion.widget.MotionLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

import com.android.settingslib.Utils;
import com.android.settingslib.media.MediaOutputSliceConstants;
import com.android.settingslib.widget.AdaptiveIcon;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.qs.QSMediaBrowser;
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

    // Button IDs for QS controls
    static final int[] ACTION_IDS = {
            R.id.action0,
            R.id.action1,
            R.id.action2,
            R.id.action3,
            R.id.action4
    };

    private final SeekBarViewModel mSeekBarViewModel;
    private SeekBarObserver mSeekBarObserver;
    private final Executor mForegroundExecutor;
    protected final Executor mBackgroundExecutor;
    private final ActivityStarter mActivityStarter;
    private LayoutAnimationHelper mLayoutAnimationHelper;

    private Context mContext;
    private PlayerViewHolder mViewHolder;
    private MediaSession.Token mToken;
    private MediaController mController;
    private int mBackgroundColor;
    protected ComponentName mServiceComponent;
    private boolean mIsRegistered = false;
    private List<KeyFrames> mKeyFrames;
    private String mKey;
    private int mAlbumArtSize;
    private int mAlbumArtRadius;
    private int mViewWidth;

    public static final String MEDIA_PREFERENCES = "media_control_prefs";
    public static final String MEDIA_PREFERENCE_KEY = "browser_components";
    private SharedPreferences mSharedPrefs;
    private boolean mCheckedForResumption = false;
    private QSMediaBrowser mQSMediaBrowser;

    private final MediaController.Callback mSessionCallback = new MediaController.Callback() {
        @Override
        public void onSessionDestroyed() {
            Log.d(TAG, "session destroyed");
            mController.unregisterCallback(mSessionCallback);
            clearControls();
        }
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            final int s = state != null ? state.getState() : PlaybackState.STATE_NONE;
            if (s == PlaybackState.STATE_NONE) {
                Log.d(TAG, "playback state change will trigger resumption, state=" + state);
                clearControls();
            }
        }
    };

    /**
     * Initialize a new control panel
     * @param context
     * @param foregroundExecutor foreground executor
     * @param backgroundExecutor background executor, used for processing artwork
     * @param activityStarter activity starter
     */
    public MediaControlPanel(Context context, Executor foregroundExecutor,
            DelayableExecutor backgroundExecutor, ActivityStarter activityStarter) {
        mContext = context;
        mForegroundExecutor = foregroundExecutor;
        mBackgroundExecutor = backgroundExecutor;
        mActivityStarter = activityStarter;
        mSeekBarViewModel = new SeekBarViewModel(backgroundExecutor);
        loadDimens();
    }

    public void onDestroy() {
        if (mSeekBarObserver != null) {
            mSeekBarViewModel.getProgress().removeObserver(mSeekBarObserver);
        }
        mSeekBarViewModel.onDestroy();
    }

    private void loadDimens() {
        mAlbumArtRadius = mContext.getResources().getDimensionPixelSize(
                Utils.getThemeAttr(mContext, android.R.attr.dialogCornerRadius));
        mAlbumArtSize = mContext.getResources().getDimensionPixelSize(R.dimen.qs_media_album_size);
    }

    /**
     * Get the view holder used to display media controls
     * @return the view holder
     */
    @Nullable
    public PlayerViewHolder getView() {
        return mViewHolder;
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

    /** Attaches the player to the view holder. */
    public void attach(PlayerViewHolder vh) {
        mViewHolder = vh;
        MotionLayout motionView = vh.getPlayer();
        mLayoutAnimationHelper = new LayoutAnimationHelper(motionView);
        GoneChildrenHideHelper.clipGoneChildrenOnLayout(motionView);
        mKeyFrames = motionView.getDefinedTransitions().get(0).getKeyFrameList();
        mSeekBarObserver = new SeekBarObserver(vh);
        mSeekBarViewModel.getProgress().observeForever(mSeekBarObserver);
        SeekBar bar = vh.getSeekBar();
        bar.setOnSeekBarChangeListener(mSeekBarViewModel.getSeekBarListener());
        bar.setOnTouchListener(mSeekBarViewModel.getSeekBarTouchListener());
    }

    /**
     * Bind this view based on the data given
     */
    public void bind(@NotNull MediaData data) {
        if (mViewHolder == null) {
            return;
        }
        MediaSession.Token token = data.getToken();
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

        ConstraintSet expandedSet = mViewHolder.getPlayer().getConstraintSet(R.id.expanded);
        ConstraintSet collapsedSet = mViewHolder.getPlayer().getConstraintSet(R.id.collapsed);

        // Try to find a browser service component for this app
        // TODO also check for a media button receiver intended for restarting (b/154127084)
        // Only check if we haven't tried yet or the session token changed
        final String pkgName = data.getPackageName();
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

        mViewHolder.getBackground().setBackgroundTintList(
                ColorStateList.valueOf(mBackgroundColor));

        // Click action
        PendingIntent clickIntent = data.getClickIntent();
        if (clickIntent != null) {
            mViewHolder.getPlayer().setOnClickListener(v -> {
                mActivityStarter.postStartActivityDismissingKeyguard(clickIntent);
            });
        }

        ImageView albumView = mViewHolder.getAlbumView();
        // TODO: migrate this to a view with rounded corners instead of baking the rounding
        // into the bitmap
        Drawable artwork = createRoundedBitmap(data.getArtwork());
        albumView.setImageDrawable(artwork);

        // App icon
        ImageView appIcon = mViewHolder.getAppIcon();
        appIcon.setImageDrawable(data.getAppIcon());

        // Song name
        TextView titleText = mViewHolder.getTitleText();
        titleText.setText(data.getSong());

        // App title
        TextView appName = mViewHolder.getAppName();
        appName.setText(data.getApp());

        // Artist name
        TextView artistText = mViewHolder.getArtistText();
        artistText.setText(data.getArtist());

        // Transfer chip
        mViewHolder.getSeamless().setVisibility(View.VISIBLE);
        setVisibleAndAlpha(collapsedSet, R.id.media_seamless, true /*visible */);
        setVisibleAndAlpha(expandedSet, R.id.media_seamless, true /*visible */);
        mViewHolder.getSeamless().setOnClickListener(v -> {
            final Intent intent = new Intent()
                    .setAction(MediaOutputSliceConstants.ACTION_MEDIA_OUTPUT)
                    .putExtra(MediaOutputSliceConstants.EXTRA_PACKAGE_NAME,
                            mController.getPackageName())
                    .putExtra(MediaOutputSliceConstants.KEY_MEDIA_SESSION_TOKEN, mToken);
            mActivityStarter.startActivity(intent, false, true /* dismissShade */,
                    Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        });
        final boolean isRemotePlayback;
        PlaybackInfo playbackInfo = mController.getPlaybackInfo();
        if (playbackInfo != null) {
            isRemotePlayback = playbackInfo.getPlaybackType() == PlaybackInfo.PLAYBACK_TYPE_REMOTE;
        } else {
            Log.d(TAG, "PlaybackInfo was null. Defaulting to local playback.");
            isRemotePlayback = false;
        }

        ImageView iconView = mViewHolder.getSeamlessIcon();
        TextView deviceName = mViewHolder.getSeamlessText();

        // Update the outline color
        RippleDrawable bkgDrawable = (RippleDrawable) mViewHolder.getSeamless().getBackground();
        GradientDrawable rect = (GradientDrawable) bkgDrawable.getDrawable(0);
        rect.setStroke(2, deviceName.getCurrentTextColor());
        rect.setColor(Color.TRANSPARENT);

        if (isRemotePlayback) {
            mViewHolder.getSeamless().setEnabled(false);
            // TODO(b/156875717): setEnabled should cause the alpha to change.
            mViewHolder.getSeamless().setAlpha(0.38f);
            iconView.setImageResource(R.drawable.ic_hardware_speaker);
            iconView.setVisibility(View.VISIBLE);
            deviceName.setText(R.string.media_seamless_remote_device);
        } else if (data.getDevice() != null && data.getDevice().getIcon() != null
                && data.getDevice().getName() != null) {
            mViewHolder.getSeamless().setEnabled(true);
            mViewHolder.getSeamless().setAlpha(1f);
            Drawable icon = data.getDevice().getIcon();
            iconView.setVisibility(View.VISIBLE);

            if (icon instanceof AdaptiveIcon) {
                AdaptiveIcon aIcon = (AdaptiveIcon) icon;
                aIcon.setBackgroundColor(mBackgroundColor);
                iconView.setImageDrawable(aIcon);
            } else {
                iconView.setImageDrawable(icon);
            }
            deviceName.setText(data.getDevice().getName());
        } else {
            // Reset to default
            Log.w(TAG, "device is null. Not binding output chip.");
            mViewHolder.getSeamless().setEnabled(true);
            mViewHolder.getSeamless().setAlpha(1f);
            iconView.setVisibility(View.GONE);
            deviceName.setText(com.android.internal.R.string.ext_media_seamless_action);
        }

        List<Integer> actionsWhenCollapsed = data.getActionsToShowInCompact();
        // Media controls
        int i = 0;
        List<MediaAction> actionIcons = data.getActions();
        for (; i < actionIcons.size() && i < ACTION_IDS.length; i++) {
            int actionId = ACTION_IDS[i];
            final ImageButton button = mViewHolder.getAction(actionId);
            MediaAction mediaAction = actionIcons.get(i);
            button.setImageDrawable(mediaAction.getDrawable());
            button.setContentDescription(mediaAction.getContentDescription());
            PendingIntent actionIntent = mediaAction.getIntent();

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
            setVisibleAndAlpha(collapsedSet, actionId, visibleInCompat);
            setVisibleAndAlpha(expandedSet, actionId, true /*visible */);
        }

        // Hide any unused buttons
        for (; i < ACTION_IDS.length; i++) {
            setVisibleAndAlpha(expandedSet, ACTION_IDS[i], false /*visible */);
            setVisibleAndAlpha(collapsedSet, ACTION_IDS[i], false /*visible */);
        }

        // Seek Bar
        final MediaController controller = getController();
        mBackgroundExecutor.execute(() -> mSeekBarViewModel.updateController(controller));

        // Set up long press menu
        // TODO: b/156036025 bring back media guts

        // Update both constraint sets to regenerate the animation.
        mViewHolder.getPlayer().updateState(R.id.collapsed, collapsedSet);
        mViewHolder.getPlayer().updateState(R.id.expanded, expandedSet);
    }

    @UiThread
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
        if (mKeyFrames == null) {
            return;
        }
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
        if (mViewHolder == null) {
            return;
        }
        // Hide all the old buttons

        ConstraintSet expandedSet = mViewHolder.getPlayer().getConstraintSet(R.id.expanded);
        ConstraintSet collapsedSet = mViewHolder.getPlayer().getConstraintSet(R.id.collapsed);
        for (int i = 1; i < ACTION_IDS.length; i++) {
            setVisibleAndAlpha(expandedSet, ACTION_IDS[i], false /*visible */);
            setVisibleAndAlpha(collapsedSet, ACTION_IDS[i], false /*visible */);
        }

        // Add a restart button
        ImageButton btn = mViewHolder.getAction0();
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
        setVisibleAndAlpha(expandedSet, ACTION_IDS[0], true /*visible */);
        setVisibleAndAlpha(collapsedSet, ACTION_IDS[0], true /*visible */);

        mSeekBarViewModel.clearController();
        // TODO: fix guts
        //        View guts = mMediaNotifView.findViewById(R.id.media_guts);
        View options = mViewHolder.getOptions();

        mViewHolder.getPlayer().setOnLongClickListener(v -> {
            // Replace player view with close/cancel view
//            guts.setVisibility(View.GONE);
            options.setVisibility(View.VISIBLE);
            return true; // consumed click
        });
    }

    private void setVisibleAndAlpha(ConstraintSet set, int actionId, boolean visible) {
        set.setVisibility(actionId, visible? ConstraintSet.VISIBLE : ConstraintSet.GONE);
        set.setAlpha(actionId, visible ? 1.0f : 0.0f);
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

    public void measure(@Nullable MediaMeasurementInput input) {
        if (mViewHolder == null) {
            return;
        }
        if (input != null) {
            int width = input.getWidth();
            setPlayerWidth(width);
            mViewHolder.getPlayer().measure(input.getWidthMeasureSpec(),
                    input.getHeightMeasureSpec());
        }
    }

    public void setPlayerWidth(int width) {
        if (mViewHolder == null) {
            return;
        }
        MotionLayout view = mViewHolder.getPlayer();
        ConstraintSet expandedSet = view.getConstraintSet(R.id.expanded);
        ConstraintSet collapsedSet = view.getConstraintSet(R.id.collapsed);
        collapsedSet.setGuidelineBegin(R.id.view_width, width);
        expandedSet.setGuidelineBegin(R.id.view_width, width);
        view.updateState(R.id.collapsed, collapsedSet);
        view.updateState(R.id.expanded, expandedSet);
    }

    public void animatePendingSizeChange(long duration, long startDelay) {
        mLayoutAnimationHelper.animatePendingSizeChange(duration, startDelay);
    }
}
