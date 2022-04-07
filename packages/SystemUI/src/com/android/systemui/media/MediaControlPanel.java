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
import android.app.WallpaperColors;
import android.app.smartspace.SmartspaceAction;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Animatable2;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.constraintlayout.widget.ConstraintSet;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.InstanceId;
import com.android.settingslib.widget.AdaptiveIcon;
import com.android.systemui.R;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.animation.GhostedViewLaunchAnimatorController;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.media.dialog.MediaOutputDialogFactory;
import com.android.systemui.monet.ColorScheme;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.shared.system.SysUiStatsLog;
import com.android.systemui.util.animation.TransitionLayout;
import com.android.systemui.util.time.SystemClock;

import java.net.URISyntaxException;
import java.util.ArrayList;
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
    private static final String EXPORTED_SMARTSPACE_TRAMPOLINE_ACTIVITY_NAME = "com.google"
            + ".android.apps.gsa.staticplugins.opa.smartspace.ExportedSmartspaceTrampolineActivity";
    private static final String EXTRAS_SMARTSPACE_INTENT =
            "com.google.android.apps.gsa.smartspace.extra.SMARTSPACE_INTENT";
    private static final int MEDIA_RECOMMENDATION_ITEMS_PER_ROW = 3;
    private static final int MEDIA_RECOMMENDATION_MAX_NUM = 6;
    private static final String KEY_SMARTSPACE_ARTIST_NAME = "artist_name";
    private static final String KEY_SMARTSPACE_OPEN_IN_FOREGROUND = "KEY_OPEN_IN_FOREGROUND";
    private static final String KEY_SMARTSPACE_APP_NAME = "KEY_SMARTSPACE_APP_NAME";

    // Event types logged by smartspace
    private static final int SMARTSPACE_CARD_CLICK_EVENT = 760;
    protected static final int SMARTSPACE_CARD_DISMISS_EVENT = 761;

    private static final Intent SETTINGS_INTENT = new Intent(ACTION_MEDIA_CONTROLS_SETTINGS);

    // Buttons to show in small player when using semantic actions
    private static final List<Integer> SEMANTIC_ACTIONS_COMPACT = List.of(
            R.id.actionPlayPause,
            R.id.actionPrev,
            R.id.actionNext
    );

    // Buttons that should get hidden when we're scrubbing (they will be replaced with the views
    // showing scrubbing time)
    private static final List<Integer> SEMANTIC_ACTIONS_HIDE_WHEN_SCRUBBING = List.of(
            R.id.actionPrev,
            R.id.actionNext
    );

    // Buttons to show in small player when using semantic actions
    private static final List<Integer> SEMANTIC_ACTIONS_ALL = List.of(
            R.id.actionPlayPause,
            R.id.actionPrev,
            R.id.actionNext,
            R.id.action0,
            R.id.action1
    );

    private final SeekBarViewModel mSeekBarViewModel;
    private SeekBarObserver mSeekBarObserver;
    protected final Executor mBackgroundExecutor;
    private final Executor mMainExecutor;
    private final ActivityStarter mActivityStarter;
    private final BroadcastSender mBroadcastSender;

    private Context mContext;
    private MediaViewHolder mMediaViewHolder;
    private RecommendationViewHolder mRecommendationViewHolder;
    private String mKey;
    private MediaData mMediaData;
    private MediaViewController mMediaViewController;
    private MediaSession.Token mToken;
    private MediaController mController;
    private Lazy<MediaDataManager> mMediaDataManagerLazy;
    private int mBackgroundColor;
    // Uid for the media app.
    protected int mUid = Process.INVALID_UID;
    private int mSmartspaceMediaItemsCount;
    private MediaCarouselController mMediaCarouselController;
    private final MediaOutputDialogFactory mMediaOutputDialogFactory;
    private final FalsingManager mFalsingManager;

    // Used for logging.
    protected boolean mIsImpressed = false;
    private SystemClock mSystemClock;
    private MediaUiEventLogger mLogger;
    private InstanceId mInstanceId;
    protected int mSmartspaceId = -1;
    private String mPackageName;

    private boolean mIsScrubbing = false;

    private final SeekBarViewModel.ScrubbingChangeListener mScrubbingChangeListener =
            this::setIsScrubbing;

    /**
     * Initialize a new control panel
     *
     * @param backgroundExecutor background executor, used for processing artwork
     * @param mainExecutor main thread executor, used if we receive callbacks on the background
     *                     thread that then trigger UI changes.
     * @param activityStarter    activity starter
     */
    @Inject
    public MediaControlPanel(Context context,
            @Background Executor backgroundExecutor,
            @Main Executor mainExecutor,
            ActivityStarter activityStarter, BroadcastSender broadcastSender,
            MediaViewController mediaViewController, SeekBarViewModel seekBarViewModel,
            Lazy<MediaDataManager> lazyMediaDataManager,
            MediaOutputDialogFactory mediaOutputDialogFactory,
            MediaCarouselController mediaCarouselController,
            FalsingManager falsingManager, SystemClock systemClock, MediaUiEventLogger logger) {
        mContext = context;
        mBackgroundExecutor = backgroundExecutor;
        mMainExecutor = mainExecutor;
        mActivityStarter = activityStarter;
        mBroadcastSender = broadcastSender;
        mSeekBarViewModel = seekBarViewModel;
        mMediaViewController = mediaViewController;
        mMediaDataManagerLazy = lazyMediaDataManager;
        mMediaOutputDialogFactory = mediaOutputDialogFactory;
        mMediaCarouselController = mediaCarouselController;
        mFalsingManager = falsingManager;
        mSystemClock = systemClock;
        mLogger = logger;

        mSeekBarViewModel.setLogSeek(() -> {
            if (mPackageName != null && mInstanceId != null) {
                mLogger.logSeek(mUid, mPackageName, mInstanceId);
            }
            logSmartspaceCardReported(SMARTSPACE_CARD_CLICK_EVENT);
            return Unit.INSTANCE;
        });
    }

    public void onDestroy() {
        if (mSeekBarObserver != null) {
            mSeekBarViewModel.getProgress().removeObserver(mSeekBarObserver);
            mSeekBarViewModel.removeScrubbingChangeListener(mScrubbingChangeListener);
        }
        mSeekBarViewModel.onDestroy();
        mMediaViewController.onDestroy();
    }

    /**
     * Get the view holder used to display media controls.
     *
     * @return the media view holder
     */
    @Nullable
    public MediaViewHolder getMediaViewHolder() {
        return mMediaViewHolder;
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

    /** Sets whether the user is touching the seek bar to change the track position. */
    public void setIsScrubbing(boolean isScrubbing) {
        if (mMediaData == null || mMediaData.getSemanticActions() == null) {
            return;
        }
        if (isScrubbing == this.mIsScrubbing) {
            return;
        }
        this.mIsScrubbing = isScrubbing;
        mMainExecutor.execute(() ->
                updateDisplayForScrubbingChange(mMediaData.getSemanticActions()));
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
    public void attachPlayer(MediaViewHolder vh) {
        mMediaViewHolder = vh;
        TransitionLayout player = vh.getPlayer();

        mSeekBarObserver = new SeekBarObserver(vh);
        mSeekBarViewModel.getProgress().observeForever(mSeekBarObserver);
        mSeekBarViewModel.attachTouchHandlers(vh.getSeekBar());
        mSeekBarViewModel.setScrubbingChangeListener(mScrubbingChangeListener);
        mMediaViewController.attach(player, MediaViewController.TYPE.PLAYER);

        vh.getPlayer().setOnLongClickListener(v -> {
            if (!mMediaViewController.isGutsVisible()) {
                openGuts();
                return true;
            } else {
                closeGuts();
                return true;
            }
        });
        vh.getCancel().setOnClickListener(v -> {
            if (!mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                closeGuts();
            }
        });
        vh.getSettings().setOnClickListener(v -> {
            if (!mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                mLogger.logLongPressSettings(mUid, mPackageName, mInstanceId);
                mActivityStarter.startActivity(SETTINGS_INTENT, true /* dismissShade */);
            }
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
                closeGuts();
                return true;
            }
        });
        mRecommendationViewHolder.getCancel().setOnClickListener(v -> {
            if (!mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                closeGuts();
            }
        });
        mRecommendationViewHolder.getSettings().setOnClickListener(v -> {
            if (!mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                mActivityStarter.startActivity(SETTINGS_INTENT, true /* dismissShade */);
            }
        });
    }

    /** Bind this player view based on the data given. */
    public void bindPlayer(@NonNull MediaData data, String key) {
        if (mMediaViewHolder == null) {
            return;
        }
        mKey = key;
        mMediaData = data;
        MediaSession.Token token = data.getToken();
        mPackageName = data.getPackageName();
        mUid = data.getAppUid();
        // Only assigns instance id if it's unassigned.
        if (mSmartspaceId == -1) {
            mSmartspaceId = SmallHash.hash(mUid + (int) mSystemClock.currentTimeMillis());
        }
        mInstanceId = data.getInstanceId();

        mBackgroundColor = data.getBackgroundColor();
        if (mToken == null || !mToken.equals(token)) {
            mToken = token;
        }

        if (mToken != null) {
            mController = new MediaController(mContext, mToken);
        } else {
            mController = null;
        }

        // Click action
        PendingIntent clickIntent = data.getClickIntent();
        if (clickIntent != null) {
            mMediaViewHolder.getPlayer().setOnClickListener(v -> {
                if (mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) return;
                if (mMediaViewController.isGutsVisible()) return;
                mLogger.logTapContentView(mUid, mPackageName, mInstanceId);
                logSmartspaceCardReported(SMARTSPACE_CARD_CLICK_EVENT);
                mActivityStarter.postStartActivityDismissingKeyguard(clickIntent,
                        buildLaunchAnimatorController(mMediaViewHolder.getPlayer()));
            });
        }

        // Accessibility label
        mMediaViewHolder.getPlayer().setContentDescription(
                mContext.getString(
                        R.string.controls_media_playing_item_description,
                        data.getSong(), data.getArtist(), data.getApp()));

        // Song name
        TextView titleText = mMediaViewHolder.getTitleText();
        titleText.setText(data.getSong());

        // Artist name
        TextView artistText = mMediaViewHolder.getArtistText();
        artistText.setText(data.getArtist());

        // Seek Bar
        final MediaController controller = getController();
        mBackgroundExecutor.execute(() -> mSeekBarViewModel.updateController(controller));

        bindOutputSwitcherChip(data);
        bindLongPressMenu(data);
        bindActionButtons(data);
        bindScrubbingTime(data);
        bindArtworkAndColors(data);

        // TODO: We don't need to refresh this state constantly, only if the state actually changed
        // to something which might impact the measurement
        mMediaViewController.refreshState();
    }

    private void bindOutputSwitcherChip(MediaData data) {
        // Output switcher chip
        ViewGroup seamlessView = mMediaViewHolder.getSeamless();
        seamlessView.setVisibility(View.VISIBLE);
        ImageView iconView = mMediaViewHolder.getSeamlessIcon();
        TextView deviceName = mMediaViewHolder.getSeamlessText();
        final MediaDeviceData device = data.getDevice();

        // Disable clicking on output switcher for invalid devices and resumption controls
        final boolean seamlessDisabled = (device != null && !device.getEnabled())
                || data.getResumption();
        final float seamlessAlpha = seamlessDisabled ? DISABLED_ALPHA : 1.0f;
        mMediaViewHolder.getSeamlessButton().setAlpha(seamlessAlpha);
        seamlessView.setEnabled(!seamlessDisabled);
        CharSequence deviceString = mContext.getString(R.string.media_seamless_other_device);
        if (device != null) {
            Drawable icon = device.getIcon();
            if (icon instanceof AdaptiveIcon) {
                AdaptiveIcon aIcon = (AdaptiveIcon) icon;
                aIcon.setBackgroundColor(mBackgroundColor);
                iconView.setImageDrawable(aIcon);
            } else {
                iconView.setImageDrawable(icon);
            }
            deviceString = device.getName();
        } else {
            // Set to default icon
            iconView.setImageResource(R.drawable.ic_media_home_devices);
        }
        deviceName.setText(deviceString);
        seamlessView.setContentDescription(deviceString);
        seamlessView.setOnClickListener(
                v -> {
                    if (mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                        return;
                    }
                    mLogger.logOpenOutputSwitcher(mUid, mPackageName, mInstanceId);
                    if (device.getIntent() != null) {
                        if (device.getIntent().isActivity()) {
                            mActivityStarter.startActivity(
                                    device.getIntent().getIntent(), true);
                        } else {
                            try {
                                device.getIntent().send();
                            } catch (PendingIntent.CanceledException e) {
                                Log.e(TAG, "Device pending intent was canceled");
                            }
                        }
                    } else {
                        mMediaOutputDialogFactory.create(mPackageName, true,
                                mMediaViewHolder.getSeamlessButton());
                    }
                });
    }

    private void bindLongPressMenu(MediaData data) {
        boolean isDismissible = data.isClearable();
        String dismissText;
        if (isDismissible) {
            dismissText = mContext.getString(R.string.controls_media_close_session, data.getApp());
        } else {
            dismissText = mContext.getString(R.string.controls_media_active_session);
        }
        mMediaViewHolder.getLongPressText().setText(dismissText);

        // Dismiss button
        mMediaViewHolder.getDismissText().setAlpha(isDismissible ? 1 : DISABLED_ALPHA);
        mMediaViewHolder.getDismiss().setEnabled(isDismissible);
        mMediaViewHolder.getDismiss().setOnClickListener(v -> {
            if (mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) return;
            logSmartspaceCardReported(SMARTSPACE_CARD_DISMISS_EVENT);
            mLogger.logLongPressDismiss(mUid, mPackageName, mInstanceId);

            if (mKey != null) {
                closeGuts();
                if (!mMediaDataManagerLazy.get().dismissMediaData(mKey,
                        MediaViewController.GUTS_ANIMATION_DURATION + 100)) {
                    Log.w(TAG, "Manager failed to dismiss media " + mKey);
                    // Remove directly from carousel so user isn't stuck with defunct controls
                    mMediaCarouselController.removePlayer(mKey, false, false);
                }
            } else {
                Log.w(TAG, "Dismiss media with null notification. Token uid="
                        + data.getToken().getUid());
            }
        });
    }

    private void bindArtworkAndColors(MediaData data) {
        // Default colors
        int surfaceColor = mBackgroundColor;
        int accentPrimary = com.android.settingslib.Utils.getColorAttr(mContext,
                com.android.internal.R.attr.textColorPrimary).getDefaultColor();
        int textPrimary = com.android.settingslib.Utils.getColorAttr(mContext,
                com.android.internal.R.attr.textColorPrimary).getDefaultColor();
        int textPrimaryInverse = com.android.settingslib.Utils.getColorAttr(mContext,
                com.android.internal.R.attr.textColorPrimaryInverse).getDefaultColor();
        int textSecondary = com.android.settingslib.Utils.getColorAttr(mContext,
                com.android.internal.R.attr.textColorSecondary).getDefaultColor();
        int textTertiary = com.android.settingslib.Utils.getColorAttr(mContext,
                com.android.internal.R.attr.textColorTertiary).getDefaultColor();

        // Album art
        ColorScheme colorScheme = null;
        ImageView albumView = mMediaViewHolder.getAlbumView();
        boolean hasArtwork = data.getArtwork() != null;
        if (hasArtwork) {
            colorScheme = new ColorScheme(WallpaperColors.fromBitmap(data.getArtwork().getBitmap()),
                    true);

            // Scale artwork to fit background
            int width = mMediaViewHolder.getPlayer().getWidth();
            int height = mMediaViewHolder.getPlayer().getHeight();
            Drawable artwork = getScaledBackground(data.getArtwork(), width, height);
            albumView.setPadding(0, 0, 0, 0);
            albumView.setImageDrawable(artwork);
            albumView.setClipToOutline(true);
        } else {
            // If there's no artwork, use colors from the app icon
            try {
                Drawable icon = mContext.getPackageManager().getApplicationIcon(
                        data.getPackageName());
                colorScheme = new ColorScheme(WallpaperColors.fromDrawable(icon), true);
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, "Cannot find icon for package " + data.getPackageName(), e);
            }
        }

        // Get colors for player
        if (colorScheme != null) {
            surfaceColor = colorScheme.getAccent2().get(9); // A2-800
            accentPrimary = colorScheme.getAccent1().get(2); // A1-100
            textPrimary = colorScheme.getNeutral1().get(1); // N1-50
            textPrimaryInverse = colorScheme.getNeutral1().get(10); // N1-900
            textSecondary = colorScheme.getNeutral2().get(3); // N2-200
            textTertiary = colorScheme.getNeutral2().get(5); // N2-400
        }

        ColorStateList bgColorList = ColorStateList.valueOf(surfaceColor);
        ColorStateList accentColorList = ColorStateList.valueOf(accentPrimary);
        ColorStateList textColorList = ColorStateList.valueOf(textPrimary);

        // Gradient and background (visible when there is no art)
        albumView.setForegroundTintList(ColorStateList.valueOf(surfaceColor));
        albumView.setBackgroundTintList(
                ColorStateList.valueOf(surfaceColor));
        mMediaViewHolder.getPlayer().setBackgroundTintList(bgColorList);

        // App icon - use notification icon
        ImageView appIconView = mMediaViewHolder.getAppIcon();
        appIconView.clearColorFilter();
        if (data.getAppIcon() != null && !data.getResumption()) {
            appIconView.setImageIcon(data.getAppIcon());
            appIconView.setColorFilter(accentPrimary);
        } else {
            // Resume players use launcher icon
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

        // Metadata text
        mMediaViewHolder.getTitleText().setTextColor(textPrimary);
        mMediaViewHolder.getArtistText().setTextColor(textSecondary);

        // Seekbar
        SeekBar seekbar = mMediaViewHolder.getSeekBar();
        seekbar.getThumb().setTintList(textColorList);
        seekbar.setProgressTintList(textColorList);
        seekbar.setProgressBackgroundTintList(ColorStateList.valueOf(textTertiary));
        mMediaViewHolder.getScrubbingElapsedTimeView().setTextColor(textColorList);
        mMediaViewHolder.getScrubbingTotalTimeView().setTextColor(textColorList);

        // Action buttons
        mMediaViewHolder.getActionPlayPause().setBackgroundTintList(accentColorList);
        mMediaViewHolder.getActionPlayPause().setImageTintList(
                ColorStateList.valueOf(textPrimaryInverse));
        for (ImageButton button : mMediaViewHolder.getTransparentActionButtons()) {
            button.setImageTintList(textColorList);
        }

        // Output switcher
        View seamlessView = mMediaViewHolder.getSeamlessButton();
        seamlessView.setBackgroundTintList(accentColorList);
        ImageView seamlessIconView = mMediaViewHolder.getSeamlessIcon();
        seamlessIconView.setImageTintList(bgColorList);
        TextView seamlessText = mMediaViewHolder.getSeamlessText();
        seamlessText.setTextColor(surfaceColor);

        // Long press buttons
        mMediaViewHolder.getLongPressText().setTextColor(textColorList);
        mMediaViewHolder.getSettings().setImageTintList(accentColorList);
        mMediaViewHolder.getCancelText().setTextColor(textColorList);
        mMediaViewHolder.getCancelText().setBackgroundTintList(accentColorList);
        mMediaViewHolder.getDismissText().setTextColor(surfaceColor);
        mMediaViewHolder.getDismissText().setBackgroundTintList(accentColorList);
    }

    private void bindActionButtons(MediaData data) {
        MediaButton semanticActions = data.getSemanticActions();
        ImageButton[] genericButtons = new ImageButton[]{
                mMediaViewHolder.getAction0(),
                mMediaViewHolder.getAction1(),
                mMediaViewHolder.getAction2(),
                mMediaViewHolder.getAction3(),
                mMediaViewHolder.getAction4()};

        ConstraintSet expandedSet = mMediaViewController.getExpandedLayout();
        ConstraintSet collapsedSet = mMediaViewController.getCollapsedLayout();
        if (semanticActions != null) {
            // Hide all the generic buttons
            for (ImageButton b: genericButtons) {
                setVisibleAndAlpha(collapsedSet, b.getId(), false);
                setVisibleAndAlpha(expandedSet, b.getId(), false);
            }

            for (int id : SEMANTIC_ACTIONS_ALL) {
                ImageButton button = mMediaViewHolder.getAction(id);
                MediaAction action = semanticActions.getActionById(id);
                setSemanticButton(button, action, semanticActions);
            }
        } else {
            // Hide buttons that only appear for semantic actions
            for (int id : SEMANTIC_ACTIONS_COMPACT) {
                setVisibleAndAlpha(collapsedSet, id, false);
                setVisibleAndAlpha(expandedSet, id, false);
            }

            // Set all the generic buttons
            List<Integer> actionsWhenCollapsed = data.getActionsToShowInCompact();
            List<MediaAction> actions = data.getActions();
            int i = 0;
            for (; i < actions.size(); i++) {
                boolean showInCompact = actionsWhenCollapsed.contains(i);
                setGenericButton(
                        genericButtons[i],
                        actions.get(i),
                        collapsedSet,
                        expandedSet,
                        showInCompact);
            }
            for (; i < 5; i++) {
                // Hide any unused buttons
                setGenericButton(
                        genericButtons[i],
                        /* mediaAction= */ null,
                        collapsedSet,
                        expandedSet,
                        /* showInCompact= */ false);
            }
        }
        expandedSet.setVisibility(R.id.media_progress_bar, getSeekBarVisibility());
        expandedSet.setAlpha(R.id.media_progress_bar, mSeekBarViewModel.getEnabled() ? 1.0f : 0.0f);
    }

    private int getSeekBarVisibility() {
        boolean seekbarEnabled = mSeekBarViewModel.getEnabled();
        if (seekbarEnabled) {
            return ConstraintSet.VISIBLE;
        }
        // If disabled and "neighbours" are visible, set progress bar to INVISIBLE instead of GONE
        // so layout weights still work.
        return areAnyExpandedBottomActionsVisible() ? ConstraintSet.INVISIBLE : ConstraintSet.GONE;
    }

    private boolean areAnyExpandedBottomActionsVisible() {
        ConstraintSet expandedSet = mMediaViewController.getExpandedLayout();
        int[] referencedIds = mMediaViewHolder.getActionsTopBarrier().getReferencedIds();
        for (int id : referencedIds) {
            if (expandedSet.getVisibility(id) == ConstraintSet.VISIBLE) {
                return true;
            }
        }
        return false;
    }

    private void setGenericButton(
            final ImageButton button,
            @Nullable MediaAction mediaAction,
            ConstraintSet collapsedSet,
            ConstraintSet expandedSet,
            boolean showInCompact) {
        bindButtonCommon(button, mediaAction);
        boolean visible = mediaAction != null;
        setVisibleAndAlpha(expandedSet, button.getId(), visible);
        setVisibleAndAlpha(collapsedSet, button.getId(), visible && showInCompact);
    }

    private void setSemanticButton(
            final ImageButton button,
            @Nullable MediaAction mediaAction,
            MediaButton semanticActions) {
        AnimationBindHandler animHandler;
        if (button.getTag() == null) {
            animHandler = new AnimationBindHandler();
            button.setTag(animHandler);
        } else {
            animHandler = (AnimationBindHandler) button.getTag();
        }

        animHandler.tryExecute(() -> {
            bindButtonWithAnimations(button, mediaAction, animHandler);
            setSemanticButtonVisibleAndAlpha(button.getId(), mediaAction, semanticActions);
        });
    }

    private void bindButtonWithAnimations(
            final ImageButton button,
            @Nullable MediaAction mediaAction,
            @NonNull AnimationBindHandler animHandler) {
        if (mediaAction != null) {
            if (animHandler.updateRebindId(mediaAction.getRebindId())) {
                animHandler.unregisterAll();
                animHandler.tryRegister(mediaAction.getIcon());
                animHandler.tryRegister(mediaAction.getBackground());
                bindButtonCommon(button, mediaAction);
            }
        } else {
            animHandler.unregisterAll();
            clearButton(button);
        }
    }

    private void bindButtonCommon(final ImageButton button, @Nullable MediaAction mediaAction) {
        if (mediaAction != null) {
            final Drawable icon = mediaAction.getIcon();
            button.setImageDrawable(icon);
            button.setContentDescription(mediaAction.getContentDescription());
            final Drawable bgDrawable = mediaAction.getBackground();
            button.setBackground(bgDrawable);

            Runnable action = mediaAction.getAction();
            if (action == null) {
                button.setEnabled(false);
            } else {
                button.setEnabled(true);
                button.setOnClickListener(v -> {
                    if (!mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                        mLogger.logTapAction(button.getId(), mUid, mPackageName, mInstanceId);
                        logSmartspaceCardReported(SMARTSPACE_CARD_CLICK_EVENT);
                        action.run();

                        if (icon instanceof Animatable) {
                            ((Animatable) icon).start();
                        }
                        if (bgDrawable instanceof Animatable) {
                            ((Animatable) bgDrawable).start();
                        }
                    }
                });
            }
        } else {
            clearButton(button);
        }
    }

    private void clearButton(final ImageButton button) {
        button.setImageDrawable(null);
        button.setContentDescription(null);
        button.setEnabled(false);
        button.setBackground(null);
    }

    private void setSemanticButtonVisibleAndAlpha(
            int buttonId,
            @Nullable MediaAction mediaAction,
            MediaButton semanticActions) {
        ConstraintSet collapsedSet = mMediaViewController.getCollapsedLayout();
        ConstraintSet expandedSet = mMediaViewController.getExpandedLayout();
        boolean showInCompact = SEMANTIC_ACTIONS_COMPACT.contains(buttonId);
        boolean hideWhenScrubbing = SEMANTIC_ACTIONS_HIDE_WHEN_SCRUBBING.contains(buttonId);
        boolean shouldBeHiddenDueToScrubbing =
                scrubbingTimeViewsEnabled(semanticActions) && hideWhenScrubbing && mIsScrubbing;
        boolean visible = mediaAction != null && !shouldBeHiddenDueToScrubbing;

        setVisibleAndAlpha(expandedSet, buttonId, visible);
        setVisibleAndAlpha(collapsedSet, buttonId, visible && showInCompact);
    }

    /** Updates all the views that might change due to a scrubbing state change. */
    // TODO(b/209656742): Handle scenarios where actionPrev and/or actionNext aren't active.
    private void updateDisplayForScrubbingChange(@NonNull MediaButton semanticActions) {
        // Update visibilities of the scrubbing time views and the scrubbing-dependent buttons.
        bindScrubbingTime(mMediaData);
        SEMANTIC_ACTIONS_HIDE_WHEN_SCRUBBING.forEach((id) ->
                setSemanticButtonVisibleAndAlpha(
                        id, semanticActions.getActionById(id), semanticActions));
        // Trigger a state refresh so that we immediately update visibilities.
        mMediaViewController.refreshState();
    }

    private void bindScrubbingTime(MediaData data) {
        ConstraintSet expandedSet = mMediaViewController.getExpandedLayout();
        ConstraintSet collapsedSet = mMediaViewController.getCollapsedLayout();
        int elapsedTimeId = mMediaViewHolder.getScrubbingElapsedTimeView().getId();
        int totalTimeId = mMediaViewHolder.getScrubbingTotalTimeView().getId();

        boolean visible = scrubbingTimeViewsEnabled(data.getSemanticActions()) && mIsScrubbing;
        setVisibleAndAlpha(expandedSet, elapsedTimeId, visible);
        setVisibleAndAlpha(expandedSet, totalTimeId, visible);
        // Never show in collapsed
        setVisibleAndAlpha(collapsedSet, elapsedTimeId, false);
        setVisibleAndAlpha(collapsedSet, totalTimeId, false);
    }

    private boolean scrubbingTimeViewsEnabled(@Nullable MediaButton semanticActions) {
        // The scrubbing time views replace the SEMANTIC_ACTIONS_HIDE_WHEN_SCRUBBING action views,
        // so we should only allow scrubbing times to be shown if those action views are present.
        return semanticActions != null && SEMANTIC_ACTIONS_HIDE_WHEN_SCRUBBING.stream().allMatch(
                id -> semanticActions.getActionById(id) != null
        );
    }

    // AnimationBindHandler is responsible for tracking the bound animation state and preventing
    // jank and conflicts due to media notifications arriving at any time during an animation. It
    // does this in two parts.
    //  - Exit animations fired as a result of user input are tracked. When these are running, any
    //      bind actions are delayed until the animation completes (and then fired in sequence).
    //  - Continuous animations are tracked using their rebind id. Later calls using the same
    //      rebind id will be totally ignored to prevent the continuous animation from restarting.
    private static class AnimationBindHandler extends Animatable2.AnimationCallback {
        private ArrayList<Runnable> mOnAnimationsComplete = new ArrayList<>();
        private ArrayList<Animatable2> mRegistrations = new ArrayList<>();
        private Integer mRebindId = null;

        // This check prevents rebinding to the action button if the identifier has not changed. A
        // null value is always considered to be changed. This is used to prevent the connecting
        // animation from rebinding (and restarting) if multiple buffer PlaybackStates are pushed by
        // an application in a row.
        public boolean updateRebindId(Integer rebindId) {
            if (mRebindId == null || rebindId == null || !mRebindId.equals(rebindId)) {
                mRebindId = rebindId;
                return true;
            }
            return false;
        }

        public void tryRegister(Drawable drawable) {
            if (drawable instanceof Animatable2) {
                Animatable2 anim = (Animatable2) drawable;
                anim.registerAnimationCallback(this);
                mRegistrations.add(anim);
            }
        }

        public void unregisterAll() {
            for (Animatable2 anim : mRegistrations) {
                anim.unregisterAnimationCallback(this);
            }
            mRegistrations.clear();
        }

        public boolean isAnimationRunning() {
            for (Animatable2 anim : mRegistrations) {
                if (anim.isRunning()) {
                    return true;
                }
            }
            return false;
        }

        public void tryExecute(Runnable action) {
            if (isAnimationRunning()) {
                mOnAnimationsComplete.add(action);
            } else {
                action.run();
            }
        }

        @Override
        public void onAnimationEnd(Drawable drawable) {
            super.onAnimationEnd(drawable);
            if (!isAnimationRunning()) {
                for (Runnable action : mOnAnimationsComplete) {
                    action.run();
                }
                mOnAnimationsComplete.clear();
            }
        }
    }

    @Nullable
    private ActivityLaunchAnimator.Controller buildLaunchAnimatorController(
            TransitionLayout player) {
        if (!(player.getParent() instanceof ViewGroup)) {
            // TODO(b/192194319): Throw instead of just logging.
            Log.wtf(TAG, "Skipping player animation as it is not attached to a ViewGroup",
                    new Exception());
            return null;
        }

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

        mSmartspaceId = SmallHash.hash(data.getTargetId());
        mBackgroundColor = data.getBackgroundColor();
        TransitionLayout recommendationCard = mRecommendationViewHolder.getRecommendations();
        recommendationCard.setBackgroundTintList(ColorStateList.valueOf(mBackgroundColor));

        List<SmartspaceAction> mediaRecommendationList = data.getRecommendations();
        if (mediaRecommendationList == null || mediaRecommendationList.isEmpty()) {
            Log.w(TAG, "Empty media recommendations");
            return;
        }

        // Set up recommendation card's header.
        ApplicationInfo applicationInfo;
        try {
            applicationInfo = mContext.getPackageManager()
                    .getApplicationInfo(data.getPackageName(), 0 /* flags */);
            mUid = applicationInfo.uid;
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
        CharSequence appName = getAppName(data.getCardAction());
        if (TextUtils.isEmpty(appName)) {
            Intent launchIntent =
                    packageManager.getLaunchIntentForPackage(data.getPackageName());
            if (launchIntent != null) {
                ActivityInfo launchActivity = launchIntent.resolveActivityInfo(packageManager, 0);
                appName = launchActivity.loadLabel(packageManager);
            } else {
                Log.w(TAG, "Package " + data.getPackageName()
                        +  " does not have a main launcher activity. Fallback to full app name");
                appName = packageManager.getApplicationLabel(applicationInfo);
            }
        }
        // Set the app name as card's title.
        if (!TextUtils.isEmpty(appName)) {
            TextView headerTitleText = mRecommendationViewHolder.getCardText();
            headerTitleText.setText(appName);
        }

        // Set up media rec card's tap action if applicable.
        setSmartspaceRecItemOnClickListener(recommendationCard, data.getCardAction(),
                /* interactedSubcardRank */ -1);
        // Set up media rec card's accessibility label.
        recommendationCard.setContentDescription(
                mContext.getString(R.string.controls_media_smartspace_rec_description, appName));

        List<ImageView> mediaCoverItems = mRecommendationViewHolder.getMediaCoverItems();
        List<ViewGroup> mediaCoverContainers = mRecommendationViewHolder.getMediaCoverContainers();
        List<Integer> mediaCoverItemsResIds = mRecommendationViewHolder.getMediaCoverItemsResIds();
        List<Integer> mediaCoverContainersResIds =
                mRecommendationViewHolder.getMediaCoverContainersResIds();
        ConstraintSet expandedSet = mMediaViewController.getExpandedLayout();
        ConstraintSet collapsedSet = mMediaViewController.getCollapsedLayout();
        int mediaRecommendationNum = Math.min(mediaRecommendationList.size(),
                MEDIA_RECOMMENDATION_MAX_NUM);
        int uiComponentIndex = 0;
        for (int itemIndex = 0;
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
            setSmartspaceRecItemOnClickListener(mediaCoverContainer, recommendation,
                    uiComponentIndex);
            // Bubble up the long-click event to the card.
            mediaCoverContainer.setOnLongClickListener(v -> {
                View parent = (View) v.getParent();
                if (parent != null) {
                    parent.performLongClick();
                }
                return true;
            });

            // Set up the accessibility label for the media item.
            String artistName = recommendation.getExtras()
                    .getString(KEY_SMARTSPACE_ARTIST_NAME, "");
            if (artistName.isEmpty()) {
                mediaCoverImageView.setContentDescription(
                        mContext.getString(
                                R.string.controls_media_smartspace_rec_item_no_artist_description,
                                recommendation.getTitle(), appName));
            } else {
                mediaCoverImageView.setContentDescription(
                        mContext.getString(
                                R.string.controls_media_smartspace_rec_item_description,
                                recommendation.getTitle(), artistName, appName));
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

        mSmartspaceMediaItemsCount = uiComponentIndex;
        // Set up long press to show guts setting panel.
        mRecommendationViewHolder.getDismiss().setOnClickListener(v -> {
            if (mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) return;

            logSmartspaceCardReported(
                    761 // SMARTSPACE_CARD_DISMISS
            );
            closeGuts();
            mMediaDataManagerLazy.get().dismissSmartspaceRecommendation(
                    data.getTargetId(), MediaViewController.GUTS_ANIMATION_DURATION + 100L);

            Intent dismissIntent = data.getDismissIntent();
            if (dismissIntent == null) {
                Log.w(TAG, "Cannot create dismiss action click action: "
                        + "extras missing dismiss_intent.");
                return;
            }

            if (dismissIntent.getComponent() != null
                    && dismissIntent.getComponent().getClassName()
                    .equals(EXPORTED_SMARTSPACE_TRAMPOLINE_ACTIVITY_NAME)) {
                // Dismiss the card Smartspace data through Smartspace trampoline activity.
                mContext.startActivity(dismissIntent);
            } else {
                mBroadcastSender.sendBroadcast(dismissIntent);
            }
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
        if (mMediaViewHolder != null) {
            mMediaViewHolder.marquee(false, mMediaViewController.GUTS_ANIMATION_DURATION);
        } else if (mRecommendationViewHolder != null) {
            mRecommendationViewHolder.marquee(false, mMediaViewController.GUTS_ANIMATION_DURATION);
        }
        mMediaViewController.closeGuts(immediate);
    }

    private void closeGuts() {
        closeGuts(false);
    }

    private void openGuts() {
        if (mMediaViewHolder != null) {
            mMediaViewHolder.marquee(true, mMediaViewController.GUTS_ANIMATION_DURATION);
        } else if (mRecommendationViewHolder != null) {
            mRecommendationViewHolder.marquee(true, mMediaViewController.GUTS_ANIMATION_DURATION);
        }
        mMediaViewController.openGuts();
        mLogger.logLongPressOpen(mUid, mPackageName, mInstanceId);
    }

    /**
     * Scale artwork to fill the background of the panel
     */
    @UiThread
    private Drawable getScaledBackground(Icon icon, int width, int height) {
        if (icon == null) {
            return null;
        }
        Drawable drawable = icon.loadDrawable(mContext);
        Rect bounds = new Rect(0, 0, width, height);
        if (bounds.width() > width || bounds.height() > height) {
            float offsetX = (bounds.width() - width) / 2.0f;
            float offsetY = (bounds.height() - height) / 2.0f;
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
            @NonNull SmartspaceAction action,
            int interactedSubcardRank) {
        if (view == null || action == null || action.getIntent() == null
                || action.getIntent().getExtras() == null) {
            Log.e(TAG, "No tap action can be set up");
            return;
        }

        view.setOnClickListener(v -> {
            if (mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) return;

            logSmartspaceCardReported(SMARTSPACE_CARD_CLICK_EVENT,
                    interactedSubcardRank,
                    getSmartspaceSubCardCardinality());

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

    /** Returns the upstream app name if available. */
    @Nullable
    private String getAppName(SmartspaceAction action) {
        if (action == null || action.getIntent() == null
                || action.getIntent().getExtras() == null) {
            return null;
        }

        return action.getIntent().getExtras().getString(KEY_SMARTSPACE_APP_NAME);
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

    private void logSmartspaceCardReported(int eventId) {
        logSmartspaceCardReported(eventId,
                /* interactedSubcardRank */ 0,
                /* interactedSubcardCardinality */ 0);
    }

    private void logSmartspaceCardReported(int eventId,
            int interactedSubcardRank, int interactedSubcardCardinality) {
        mMediaCarouselController.logSmartspaceCardReported(eventId,
                mSmartspaceId,
                mUid,
                new int[]{getSurfaceForSmartspaceLogging()},
                interactedSubcardRank,
                interactedSubcardCardinality);
    }

    private int getSmartspaceSubCardCardinality() {
        if (!mMediaCarouselController.getMediaCarouselScrollHandler().getQsExpanded()
                && mSmartspaceMediaItemsCount > 3) {
            return 3;
        }

        return mSmartspaceMediaItemsCount;
    }
}