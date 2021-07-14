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

import static android.provider.Settings.ACTION_MEDIA_CONTROLS_SETTINGS;

import android.app.PendingIntent;
import android.app.smartspace.SmartspaceAction;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.text.Layout;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.constraintlayout.widget.ConstraintSet;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.settingslib.widget.AdaptiveIcon;
import com.android.systemui.R;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.animation.GhostedViewLaunchAnimatorController;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.media.dialog.MediaOutputDialogFactory;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.shared.system.SysUiStatsLog;
import com.android.systemui.statusbar.phone.KeyguardDismissUtil;
import com.android.systemui.util.animation.TransitionLayout;

import java.net.URISyntaxException;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import dagger.Lazy;
import kotlin.Unit;

/**
 * A view controller used for Media Playback.
 */
public class MediaControlPanel {
    private static final String TAG = "MediaControlPanel";

    private static final float DISABLED_ALPHA = 0.38f;
    private static final String EXTRAS_SMARTSPACE_INTENT =
            "com.google.android.apps.gsa.smartspace.extra.SMARTSPACE_INTENT";
    private static final int MEDIA_RECOMMENDATION_ITEMS_PER_ROW = 3;
    private static final int MEDIA_RECOMMENDATION_MAX_NUM = 6;
    private static final String KEY_SMARTSPACE_ARTIST_NAME = "artist_name";
    private static final String KEY_SMARTSPACE_OPEN_IN_FOREGROUND = "KEY_OPEN_IN_FOREGROUND";

    private static final Intent SETTINGS_INTENT = new Intent(ACTION_MEDIA_CONTROLS_SETTINGS);

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
    protected final Executor mBackgroundExecutor;
    private final ActivityStarter mActivityStarter;

    private Context mContext;
    private PlayerViewHolder mPlayerViewHolder;
    private RecommendationViewHolder mRecommendationViewHolder;
    private String mKey;
    private MediaViewController mMediaViewController;
    private MediaSession.Token mToken;
    private MediaController mController;
    private KeyguardDismissUtil mKeyguardDismissUtil;
    private Lazy<MediaDataManager> mMediaDataManagerLazy;
    private int mBackgroundColor;
    private int mDevicePadding;
    private int mAlbumArtSize;
    // Instance id for logging purpose.
    protected int mInstanceId = -1;
    private MediaCarouselController mMediaCarouselController;
    private final MediaOutputDialogFactory mMediaOutputDialogFactory;

    /**
     * Initialize a new control panel
     *
     * @param backgroundExecutor background executor, used for processing artwork
     * @param activityStarter    activity starter
     */
    @Inject
    public MediaControlPanel(Context context, @Background Executor backgroundExecutor,
            ActivityStarter activityStarter, MediaViewController mediaViewController,
            SeekBarViewModel seekBarViewModel, Lazy<MediaDataManager> lazyMediaDataManager,
            KeyguardDismissUtil keyguardDismissUtil, MediaOutputDialogFactory
            mediaOutputDialogFactory, MediaCarouselController mediaCarouselController) {
        mContext = context;
        mBackgroundExecutor = backgroundExecutor;
        mActivityStarter = activityStarter;
        mSeekBarViewModel = seekBarViewModel;
        mMediaViewController = mediaViewController;
        mMediaDataManagerLazy = lazyMediaDataManager;
        mKeyguardDismissUtil = keyguardDismissUtil;
        mMediaOutputDialogFactory = mediaOutputDialogFactory;
        mMediaCarouselController = mediaCarouselController;
        loadDimens();

        mSeekBarViewModel.setLogSmartspaceClick(() -> {
            logSmartspaceCardReported(
                    760, // SMARTSPACE_CARD_CLICK
                    /* isRecommendationCard */ false);
            return Unit.INSTANCE;
        });
    }

    public void onDestroy() {
        if (mSeekBarObserver != null) {
            mSeekBarViewModel.getProgress().removeObserver(mSeekBarObserver);
        }
        mSeekBarViewModel.onDestroy();
        mMediaViewController.onDestroy();
    }

    private void loadDimens() {
        mAlbumArtSize = mContext.getResources().getDimensionPixelSize(R.dimen.qs_media_album_size);
        mDevicePadding = mContext.getResources()
                .getDimensionPixelSize(R.dimen.qs_media_album_device_padding);
    }

    /**
     * Get the player view holder used to display media controls.
     *
     * @return the player view holder
     */
    @Nullable
    public PlayerViewHolder getPlayerViewHolder() {
        return mPlayerViewHolder;
    }

    /**
     * Get the recommendation view holder used to display Smartspace media recs.
     * @return the recommendation view holder
     */
    @Nullable
    public RecommendationViewHolder getRecommendationViewHolder() {
        return mRecommendationViewHolder;
    }

    /**
     * Get the view controller used to display media controls
     *
     * @return the media view controller
     */
    @NonNull
    public MediaViewController getMediaViewController() {
        return mMediaViewController;
    }

    /**
     * Sets the listening state of the player.
     * <p>
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
     *
     * @return context
     */
    public Context getContext() {
        return mContext;
    }

    /** Attaches the player to the player view holder. */
    public void attachPlayer(PlayerViewHolder vh) {
        mPlayerViewHolder = vh;
        TransitionLayout player = vh.getPlayer();

        mSeekBarObserver = new SeekBarObserver(vh);
        mSeekBarViewModel.getProgress().observeForever(mSeekBarObserver);
        mSeekBarViewModel.attachTouchHandlers(vh.getSeekBar());
        mMediaViewController.attach(player, MediaViewController.TYPE.PLAYER);

        mPlayerViewHolder.getPlayer().setOnLongClickListener(v -> {
            if (!mMediaViewController.isGutsVisible()) {
                openGuts();
                return true;
            } else {
                closeGuts();
                return true;
            }
        });
        mPlayerViewHolder.getCancel().setOnClickListener(v -> {
            closeGuts();
        });
        mPlayerViewHolder.getSettings().setOnClickListener(v -> {
            mActivityStarter.startActivity(SETTINGS_INTENT, true /* dismissShade */);
        });
    }

    /** Attaches the recommendations to the recommendation view holder. */
    public void attachRecommendation(RecommendationViewHolder vh) {
        mRecommendationViewHolder = vh;
        TransitionLayout recommendations = vh.getRecommendations();

        mMediaViewController.attach(recommendations, MediaViewController.TYPE.RECOMMENDATION);

        mRecommendationViewHolder.getRecommendations().setOnLongClickListener(v -> {
            if (!mMediaViewController.isGutsVisible()) {
                openGuts();
                return true;
            } else {
                return false;
            }
        });
        mRecommendationViewHolder.getCancel().setOnClickListener(v -> {
            closeGuts();
        });
        mRecommendationViewHolder.getSettings().setOnClickListener(v -> {
            mActivityStarter.startActivity(SETTINGS_INTENT, true /* dismissShade */);
        });
    }

    /** Bind this player view based on the data given. */
    public void bindPlayer(@NonNull MediaData data, String key) {
        if (mPlayerViewHolder == null) {
            return;
        }
        mKey = key;
        MediaSession.Token token = data.getToken();
        mInstanceId = SmallHash.hash(data.getPackageName());

        mBackgroundColor = data.getBackgroundColor();
        if (mToken == null || !mToken.equals(token)) {
            mToken = token;
        }

        if (mToken != null) {
            mController = new MediaController(mContext, mToken);
        } else {
            mController = null;
        }

        ConstraintSet expandedSet = mMediaViewController.getExpandedLayout();
        ConstraintSet collapsedSet = mMediaViewController.getCollapsedLayout();

        // Click action
        PendingIntent clickIntent = data.getClickIntent();
        if (clickIntent != null) {
            mPlayerViewHolder.getPlayer().setOnClickListener(v -> {
                if (mMediaViewController.isGutsVisible()) return;

                logSmartspaceCardReported(760, // SMARTSPACE_CARD_CLICK
                        /* isRecommendationCard */ false);
                mActivityStarter.postStartActivityDismissingKeyguard(clickIntent,
                        buildLaunchAnimatorController(mPlayerViewHolder.getPlayer()));
            });
        }

        // Accessibility label
        mPlayerViewHolder.getPlayer().setContentDescription(
                mContext.getString(
                        R.string.controls_media_playing_item_description,
                        data.getSong(), data.getArtist(), data.getApp()));

        ImageView albumView = mPlayerViewHolder.getAlbumView();
        boolean hasArtwork = data.getArtwork() != null;
        if (hasArtwork) {
            Drawable artwork = scaleDrawable(data.getArtwork());
            albumView.setPadding(0, 0, 0, 0);
            albumView.setImageDrawable(artwork);
        } else {
            Drawable deviceIcon;
            if (data.getDevice() != null && data.getDevice().getIcon() != null) {
                deviceIcon = data.getDevice().getIcon().getConstantState().newDrawable().mutate();
            } else {
                deviceIcon = getContext().getDrawable(R.drawable.ic_headphone);
            }
            deviceIcon.setTintList(ColorStateList.valueOf(mBackgroundColor));
            albumView.setPadding(mDevicePadding, mDevicePadding, mDevicePadding, mDevicePadding);
            albumView.setImageDrawable(deviceIcon);
        }

        // App icon
        ImageView appIconView = mPlayerViewHolder.getAppIcon();
        appIconView.clearColorFilter();
        if (data.getAppIcon() != null && !data.getResumption()) {
            appIconView.setImageIcon(data.getAppIcon());
            int color = mContext.getColor(android.R.color.system_accent2_900);
            appIconView.setColorFilter(color);
        } else {
            appIconView.setColorFilter(getGrayscaleFilter());
            try {
                Drawable icon = mContext.getPackageManager().getApplicationIcon(
                        data.getPackageName());
                appIconView.setImageDrawable(icon);
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, "Cannot find icon for package " + data.getPackageName(), e);
                appIconView.setImageResource(R.drawable.ic_music_note);
            }
        }

        // Song name
        TextView titleText = mPlayerViewHolder.getTitleText();
        titleText.setText(data.getSong());

        // Artist name
        TextView artistText = mPlayerViewHolder.getArtistText();
        artistText.setText(data.getArtist());

        // Transfer chip
        ViewGroup seamlessView = mPlayerViewHolder.getSeamless();
        seamlessView.setVisibility(View.VISIBLE);
        setVisibleAndAlpha(collapsedSet, R.id.media_seamless, true /*visible */);
        setVisibleAndAlpha(expandedSet, R.id.media_seamless, true /*visible */);
        seamlessView.setOnClickListener(v -> {
            mMediaOutputDialogFactory.create(data.getPackageName(), true);
        });

        ImageView iconView = mPlayerViewHolder.getSeamlessIcon();
        TextView deviceName = mPlayerViewHolder.getSeamlessText();

        final MediaDeviceData device = data.getDevice();
        final int seamlessId = mPlayerViewHolder.getSeamless().getId();
        final int seamlessFallbackId = mPlayerViewHolder.getSeamlessFallback().getId();
        final boolean showFallback = device != null && !device.getEnabled();
        final int seamlessFallbackVisibility = showFallback ? View.VISIBLE : View.GONE;
        mPlayerViewHolder.getSeamlessFallback().setVisibility(seamlessFallbackVisibility);
        expandedSet.setVisibility(seamlessFallbackId, seamlessFallbackVisibility);
        collapsedSet.setVisibility(seamlessFallbackId, seamlessFallbackVisibility);
        final int seamlessVisibility = showFallback ? View.GONE : View.VISIBLE;
        mPlayerViewHolder.getSeamless().setVisibility(seamlessVisibility);
        expandedSet.setVisibility(seamlessId, seamlessVisibility);
        collapsedSet.setVisibility(seamlessId, seamlessVisibility);
        final float seamlessAlpha = data.getResumption() ? DISABLED_ALPHA : 1.0f;
        expandedSet.setAlpha(seamlessId, seamlessAlpha);
        collapsedSet.setAlpha(seamlessId, seamlessAlpha);
        // Disable clicking on output switcher for resumption controls.
        mPlayerViewHolder.getSeamless().setEnabled(!data.getResumption());
        String deviceString = null;
        if (showFallback) {
            iconView.setImageDrawable(null);
        } else if (device != null) {
            Drawable icon = device.getIcon();
            iconView.setVisibility(View.VISIBLE);
            if (icon instanceof AdaptiveIcon) {
                AdaptiveIcon aIcon = (AdaptiveIcon) icon;
                aIcon.setBackgroundColor(mBackgroundColor);
                iconView.setImageDrawable(aIcon);
            } else {
                iconView.setImageDrawable(icon);
            }
            deviceString = device.getName();
        } else {
            // Reset to default
            Log.w(TAG, "device is null. Not binding output chip.");
            iconView.setVisibility(View.GONE);
            deviceString = mContext.getString(
                    com.android.internal.R.string.ext_media_seamless_action);
        }
        deviceName.setText(deviceString);
        seamlessView.setContentDescription(deviceString);

        List<Integer> actionsWhenCollapsed = data.getActionsToShowInCompact();
        // Media controls
        int i = 0;
        List<MediaAction> actionIcons = data.getActions();
        for (; i < actionIcons.size() && i < ACTION_IDS.length; i++) {
            int actionId = ACTION_IDS[i];
            final ImageButton button = mPlayerViewHolder.getAction(actionId);
            MediaAction mediaAction = actionIcons.get(i);
            button.setImageIcon(mediaAction.getIcon());
            button.setContentDescription(mediaAction.getContentDescription());
            Runnable action = mediaAction.getAction();

            if (action == null) {
                button.setEnabled(false);
            } else {
                button.setEnabled(true);
                button.setOnClickListener(v -> {
                    logSmartspaceCardReported(760, // SMARTSPACE_CARD_CLICK
                            /* isRecommendationCard */ false);
                    action.run();
                });
            }
            boolean visibleInCompat = actionsWhenCollapsed.contains(i);
            setVisibleAndAlpha(collapsedSet, actionId, visibleInCompat);
            setVisibleAndAlpha(expandedSet, actionId, true /*visible */);
        }

        // Hide any unused buttons
        for (; i < ACTION_IDS.length; i++) {
            setVisibleAndAlpha(collapsedSet, ACTION_IDS[i], false /*visible */);
            setVisibleAndAlpha(expandedSet, ACTION_IDS[i], false /* visible */);
        }
        // If no actions, set the first view as INVISIBLE so expanded height remains constant
        if (actionIcons.size() == 0) {
            expandedSet.setVisibility(ACTION_IDS[0], ConstraintSet.INVISIBLE);
        }

        // Seek Bar
        final MediaController controller = getController();
        mBackgroundExecutor.execute(() -> mSeekBarViewModel.updateController(controller));

        // Guts label
        boolean isDismissible = data.isClearable();
        mPlayerViewHolder.getLongPressText().setText(isDismissible
                ? R.string.controls_media_close_session
                : R.string.controls_media_active_session);

        // Dismiss
        mPlayerViewHolder.getDismissLabel().setAlpha(isDismissible ? 1 : DISABLED_ALPHA);
        mPlayerViewHolder.getDismiss().setEnabled(isDismissible);
        mPlayerViewHolder.getDismiss().setOnClickListener(v -> {
            logSmartspaceCardReported(761, // SMARTSPACE_CARD_DISMISS
                    /* isRecommendationCard */ false);

            if (mKey != null) {
                closeGuts();
                if (!mMediaDataManagerLazy.get().dismissMediaData(mKey,
                        MediaViewController.GUTS_ANIMATION_DURATION + 100)) {
                    Log.w(TAG, "Manager failed to dismiss media " + mKey);
                    // Remove directly from carousel to let user recover - TODO(b/190799184)
                    mMediaCarouselController.removePlayer(key, false, false);
                }
            } else {
                Log.w(TAG, "Dismiss media with null notification. Token uid="
                        + data.getToken().getUid());
            }
        });

        // TODO: We don't need to refresh this state constantly, only if the state actually changed
        // to something which might impact the measurement
        mMediaViewController.refreshState();
    }

    @Nullable
    private ActivityLaunchAnimator.Controller buildLaunchAnimatorController(
            TransitionLayout player) {
        // TODO(b/174236650): Make sure that the carousel indicator also fades out.
        // TODO(b/174236650): Instrument the animation to measure jank.
        return new GhostedViewLaunchAnimatorController(player,
                InteractionJankMonitor.CUJ_SHADE_APP_LAUNCH_FROM_MEDIA_PLAYER) {
            @Override
            protected float getCurrentTopCornerRadius() {
                return ((IlluminationDrawable) player.getBackground()).getCornerRadius();
            }

            @Override
            protected float getCurrentBottomCornerRadius() {
                // TODO(b/184121838): Make IlluminationDrawable support top and bottom radius.
                return getCurrentTopCornerRadius();
            }

            @Override
            protected void setBackgroundCornerRadius(Drawable background, float topCornerRadius,
                    float bottomCornerRadius) {
                // TODO(b/184121838): Make IlluminationDrawable support top and bottom radius.
                float radius = Math.min(topCornerRadius, bottomCornerRadius);
                ((IlluminationDrawable) background).setCornerRadiusOverride(radius);
            }

            @Override
            public void onLaunchAnimationEnd(boolean isExpandingFullyAbove) {
                super.onLaunchAnimationEnd(isExpandingFullyAbove);
                ((IlluminationDrawable) player.getBackground()).setCornerRadiusOverride(null);
            }
        };
    }

    /** Bind this recommendation view based on the given data. */
    public void bindRecommendation(@NonNull SmartspaceMediaData data) {
        if (mRecommendationViewHolder == null) {
            return;
        }

        mInstanceId = SmallHash.hash(data.getTargetId());
        mBackgroundColor = data.getBackgroundColor();
        TransitionLayout recommendationCard = mRecommendationViewHolder.getRecommendations();
        recommendationCard.setBackgroundTintList(ColorStateList.valueOf(mBackgroundColor));

        List<SmartspaceAction> mediaRecommendationList = data.getRecommendations();
        if (mediaRecommendationList == null || mediaRecommendationList.isEmpty()) {
            Log.w(TAG, "Empty media recommendations");
            return;
        }

        // Set up recommendation card's header.
        ApplicationInfo applicationInfo = null;
        try {
            applicationInfo = mContext.getPackageManager()
                    .getApplicationInfo(data.getPackageName(), 0 /* flags */);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Fail to get media recommendation's app info", e);
            return;
        }

        PackageManager packageManager = mContext.getPackageManager();
        // Set up media source app's logo.
        Drawable icon = packageManager.getApplicationIcon(applicationInfo);
        icon.setColorFilter(getGrayscaleFilter());
        ImageView headerLogoImageView = mRecommendationViewHolder.getCardIcon();
        headerLogoImageView.setImageDrawable(icon);
        // Set up media source app's label text.
        CharSequence appLabel = packageManager.getApplicationLabel(applicationInfo);
        if (appLabel.length() != 0) {
            TextView headerTitleText = mRecommendationViewHolder.getCardText();
            headerTitleText.setText(appLabel);
        }
        // Set up media rec card's tap action if applicable.
        setSmartspaceRecItemOnClickListener(recommendationCard, data.getCardAction());
        // Set up media rec card's accessibility label.
        recommendationCard.setContentDescription(
                mContext.getString(R.string.controls_media_smartspace_rec_description, appLabel));

        List<ImageView> mediaCoverItems = mRecommendationViewHolder.getMediaCoverItems();
        List<ViewGroup> mediaCoverContainers = mRecommendationViewHolder.getMediaCoverContainers();
        List<Integer> mediaCoverItemsResIds = mRecommendationViewHolder.getMediaCoverItemsResIds();
        List<Integer> mediaCoverContainersResIds =
                mRecommendationViewHolder.getMediaCoverContainersResIds();
        ConstraintSet expandedSet = mMediaViewController.getExpandedLayout();
        ConstraintSet collapsedSet = mMediaViewController.getCollapsedLayout();
        int mediaRecommendationNum = Math.min(mediaRecommendationList.size(),
                MEDIA_RECOMMENDATION_MAX_NUM);
        for (int itemIndex = 0, uiComponentIndex = 0;
                itemIndex < mediaRecommendationNum && uiComponentIndex < mediaRecommendationNum;
                itemIndex++) {
            SmartspaceAction recommendation = mediaRecommendationList.get(itemIndex);
            if (recommendation.getIcon() == null) {
                Log.w(TAG, "No media cover is provided. Skipping this item...");
                continue;
            }

            // Set up media item cover.
            ImageView mediaCoverImageView = mediaCoverItems.get(uiComponentIndex);
            mediaCoverImageView.setImageIcon(recommendation.getIcon());

            // Set up the media item's click listener if applicable.
            ViewGroup mediaCoverContainer = mediaCoverContainers.get(uiComponentIndex);
            setSmartspaceRecItemOnClickListener(mediaCoverContainer, recommendation);

            // Set up the accessibility label for the media item.
            String artistName = recommendation.getExtras()
                    .getString(KEY_SMARTSPACE_ARTIST_NAME, "");
            if (artistName.isEmpty()) {
                mediaCoverImageView.setContentDescription(
                        mContext.getString(
                                R.string.controls_media_smartspace_rec_item_no_artist_description,
                                recommendation.getTitle(), appLabel));
            } else {
                mediaCoverImageView.setContentDescription(
                        mContext.getString(
                                R.string.controls_media_smartspace_rec_item_description,
                                recommendation.getTitle(), artistName, appLabel));
            }

            if (uiComponentIndex < MEDIA_RECOMMENDATION_ITEMS_PER_ROW) {
                setVisibleAndAlpha(collapsedSet,
                        mediaCoverItemsResIds.get(uiComponentIndex), true);
                setVisibleAndAlpha(collapsedSet,
                        mediaCoverContainersResIds.get(uiComponentIndex), true);
            } else {
                setVisibleAndAlpha(collapsedSet,
                        mediaCoverItemsResIds.get(uiComponentIndex), false);
                setVisibleAndAlpha(collapsedSet,
                        mediaCoverContainersResIds.get(uiComponentIndex), false);
            }
            setVisibleAndAlpha(expandedSet,
                    mediaCoverItemsResIds.get(uiComponentIndex), true);
            setVisibleAndAlpha(expandedSet,
                    mediaCoverContainersResIds.get(uiComponentIndex), true);

            uiComponentIndex++;
        }

        // Set up long press to show guts setting panel.
        mRecommendationViewHolder.getDismiss().setOnClickListener(v -> {
            logSmartspaceCardReported(761, // SMARTSPACE_CARD_DISMISS
                    /* isRecommendationCard */ true);
            closeGuts();
            mMediaDataManagerLazy.get().dismissSmartspaceRecommendation(
                    data.getTargetId(), MediaViewController.GUTS_ANIMATION_DURATION + 100L);
        });

        mController = null;
        mMediaViewController.refreshState();
    }

    /**
     * Close the guts for this player.
     *
     * @param immediate {@code true} if it should be closed without animation
     */
    public void closeGuts(boolean immediate) {
        if (mPlayerViewHolder != null) {
            mPlayerViewHolder.marquee(false, mMediaViewController.GUTS_ANIMATION_DURATION);
        } else if (mRecommendationViewHolder != null) {
            mRecommendationViewHolder.marquee(false, mMediaViewController.GUTS_ANIMATION_DURATION);
        }
        mMediaViewController.closeGuts(immediate);
    }

    private void closeGuts() {
        closeGuts(false);
    }

    private void openGuts() {
        ConstraintSet expandedSet = mMediaViewController.getExpandedLayout();
        ConstraintSet collapsedSet = mMediaViewController.getCollapsedLayout();

        boolean wasTruncated = false;
        Layout l = null;
        if (mPlayerViewHolder != null) {
            mPlayerViewHolder.marquee(true, mMediaViewController.GUTS_ANIMATION_DURATION);
            l = mPlayerViewHolder.getSettingsText().getLayout();
        } else if (mRecommendationViewHolder != null) {
            mRecommendationViewHolder.marquee(true, mMediaViewController.GUTS_ANIMATION_DURATION);
            l = mRecommendationViewHolder.getSettingsText().getLayout();
        }
        if (l != null) {
            wasTruncated = l.getEllipsisCount(0) > 0;
        }
        mMediaViewController.setShouldHideGutsSettings(wasTruncated);
        if (wasTruncated) {
            // not enough room for the settings button to show fully, let's hide it
            expandedSet.constrainMaxWidth(R.id.settings, 0);
            collapsedSet.constrainMaxWidth(R.id.settings, 0);
        }

        mMediaViewController.openGuts();
    }

    @UiThread
    private Drawable scaleDrawable(Icon icon) {
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
            bounds.offset((int) -offsetX, (int) -offsetY);
        }
        drawable.setBounds(bounds);
        return drawable;
    }

    /**
     * Get the current media controller
     *
     * @return the controller
     */
    public MediaController getController() {
        return mController;
    }

    /**
     * Check whether the media controlled by this player is currently playing
     *
     * @return whether it is playing, or false if no controller information
     */
    public boolean isPlaying() {
        return isPlaying(mController);
    }

    /**
     * Check whether the given controller is currently playing
     *
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

    private ColorMatrixColorFilter getGrayscaleFilter() {
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(0);
        return new ColorMatrixColorFilter(matrix);
    }

    private void setVisibleAndAlpha(ConstraintSet set, int actionId, boolean visible) {
        set.setVisibility(actionId, visible ? ConstraintSet.VISIBLE : ConstraintSet.GONE);
        set.setAlpha(actionId, visible ? 1.0f : 0.0f);
    }

    private void setSmartspaceRecItemOnClickListener(
            @NonNull View view,
            @NonNull SmartspaceAction action) {
        if (view == null || action == null || action.getIntent() == null
                || action.getIntent().getExtras() == null) {
            Log.e(TAG, "No tap action can be set up");
            return;
        }

        view.setOnClickListener(v -> {
            // When media recommendation card is shown, it will always be the top card.
            logSmartspaceCardReported(760, // SMARTSPACE_CARD_CLICK
                    /* isRecommendationCard */ true);

            if (shouldSmartspaceRecItemOpenInForeground(action)) {
                // Request to unlock the device if the activity needs to be opened in foreground.
                mActivityStarter.postStartActivityDismissingKeyguard(
                        action.getIntent(),
                        0 /* delay */,
                        buildLaunchAnimatorController(
                                mRecommendationViewHolder.getRecommendations()));
            } else {
                // Otherwise, open the activity in background directly.
                view.getContext().startActivity(action.getIntent());
            }

            // Automatically scroll to the active player once the media is loaded.
            mMediaCarouselController.setShouldScrollToActivePlayer(true);
        });
    }

    /** Returns if the Smartspace action will open the activity in foreground. */
    private boolean shouldSmartspaceRecItemOpenInForeground(SmartspaceAction action) {
        if (action == null || action.getIntent() == null
                || action.getIntent().getExtras() == null) {
            return false;
        }

        String intentString = action.getIntent().getExtras().getString(EXTRAS_SMARTSPACE_INTENT);
        if (intentString == null) {
            return false;
        }

        try {
            Intent wrapperIntent = Intent.parseUri(intentString, Intent.URI_INTENT_SCHEME);
            return wrapperIntent.getBooleanExtra(KEY_SMARTSPACE_OPEN_IN_FOREGROUND, false);
        } catch (URISyntaxException e) {
            Log.wtf(TAG, "Failed to create intent from URI: " + intentString);
            e.printStackTrace();
        }

        return false;
    }

    /**
     * Get the surface given the current end location for MediaViewController
     * @return surface used for Smartspace logging
     */
    protected int getSurfaceForSmartspaceLogging() {
        int currentEndLocation = mMediaViewController.getCurrentEndLocation();
        if (currentEndLocation == MediaHierarchyManager.LOCATION_QQS
                || currentEndLocation == MediaHierarchyManager.LOCATION_QS) {
            return SysUiStatsLog.SMART_SPACE_CARD_REPORTED__DISPLAY_SURFACE__SHADE;
        } else if (currentEndLocation == MediaHierarchyManager.LOCATION_LOCKSCREEN) {
            return SysUiStatsLog.SMART_SPACE_CARD_REPORTED__DISPLAY_SURFACE__LOCKSCREEN;
        }
        return SysUiStatsLog.SMART_SPACE_CARD_REPORTED__DISPLAY_SURFACE__DEFAULT_SURFACE;
    }

    private void logSmartspaceCardReported(int eventId, boolean isRecommendationCard) {
        mMediaCarouselController.logSmartspaceCardReported(eventId,
                mInstanceId,
                isRecommendationCard,
                getSurfaceForSmartspaceLogging());
    }
}
