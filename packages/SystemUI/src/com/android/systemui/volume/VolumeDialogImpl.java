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

import static android.app.ActivityManager.LOCK_TASK_MODE_NONE;
import static android.media.AudioManager.RINGER_MODE_NORMAL;
import static android.media.AudioManager.RINGER_MODE_SILENT;
import static android.media.AudioManager.RINGER_MODE_VIBRATE;
import static android.media.AudioManager.STREAM_ACCESSIBILITY;
import static android.media.AudioManager.STREAM_ALARM;
import static android.media.AudioManager.STREAM_MUSIC;
import static android.media.AudioManager.STREAM_RING;
import static android.media.AudioManager.STREAM_VOICE_CALL;
import static android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE;
import static android.view.View.GONE;
import static android.view.View.INVISIBLE;
import static android.view.View.LAYOUT_DIRECTION_RTL;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import static com.android.internal.jank.InteractionJankMonitor.CUJ_VOLUME_CONTROL;
import static com.android.internal.jank.InteractionJankMonitor.Configuration.Builder;
import static com.android.systemui.volume.Events.DISMISS_REASON_SETTINGS_CLICKED;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.Dialog;
import android.app.KeyguardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RotateDrawable;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.Trace;
import android.os.VibrationEffect;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.text.InputFilter;
import android.util.FeatureFlagUtils;
import android.util.Log;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.AccessibilityDelegate;
import android.view.View.OnAttachStateChangeListener;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewPropertyAnimator;
import android.view.ViewStub;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.internal.graphics.drawable.BackgroundBlurDrawable;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.view.RotationPolicy;
import com.android.settingslib.Utils;
import com.android.systemui.Dumpable;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.media.dialog.MediaOutputDialogFactory;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.VolumeDialog;
import com.android.systemui.plugins.VolumeDialogController;
import com.android.systemui.plugins.VolumeDialogController.State;
import com.android.systemui.plugins.VolumeDialogController.StreamState;
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.util.AlphaTintDrawableWrapper;
import com.android.systemui.util.DeviceConfigProxy;
import com.android.systemui.util.RoundedCornerProgressDrawable;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Visual presentation of the volume dialog.
 *
 * A client of VolumeDialogControllerImpl and its state model.
 *
 * Methods ending in "H" must be called on the (ui) handler.
 */
public class VolumeDialogImpl implements VolumeDialog, Dumpable,
        ConfigurationController.ConfigurationListener,
        ViewTreeObserver.OnComputeInternalInsetsListener {
    private static final String TAG = Util.logTag(VolumeDialogImpl.class);

    private static final long USER_ATTEMPT_GRACE_PERIOD = 1000;
    private static final int UPDATE_ANIMATION_DURATION = 80;

    static final int DIALOG_TIMEOUT_MILLIS = 3000;
    static final int DIALOG_SAFETYWARNING_TIMEOUT_MILLIS = 5000;
    static final int DIALOG_ODI_CAPTIONS_TOOLTIP_TIMEOUT_MILLIS = 5000;
    static final int DIALOG_HOVERING_TIMEOUT_MILLIS = 16000;

    private static final int DRAWER_ANIMATION_DURATION_SHORT = 175;
    private static final int DRAWER_ANIMATION_DURATION = 250;

    /** Shows volume dialog show animation. */
    private static final String TYPE_SHOW = "show";
    /** Dismiss volume dialog animation.  */
    private static final String TYPE_DISMISS = "dismiss";
    /** Volume dialog slider animation. */
    private static final String TYPE_UPDATE = "update";

    private final int mDialogShowAnimationDurationMs;
    private final int mDialogHideAnimationDurationMs;
    private int mDialogWidth;
    private int mDialogCornerRadius;
    private int mRingerDrawerItemSize;
    private int mRingerRowsPadding;
    private boolean mShowVibrate;
    private int mRingerCount;
    private final boolean mShowLowMediaVolumeIcon;
    private final boolean mChangeVolumeRowTintWhenInactive;

    private final Context mContext;
    private final H mHandler = new H();
    private final VolumeDialogController mController;
    private final DeviceProvisionedController mDeviceProvisionedController;
    private final Region mTouchableRegion = new Region();

    private Window mWindow;
    private CustomDialog mDialog;
    private ViewGroup mDialogView;
    private ViewGroup mDialogRowsViewContainer;
    private ViewGroup mDialogRowsView;
    private ViewGroup mRinger;

    private DeviceConfigProxy mDeviceConfigProxy;
    private Executor mExecutor;

    /**
     * Container for the top part of the dialog, which contains the ringer, the ringer drawer, the
     * volume rows, and the ellipsis button. This does not include the live caption button.
     */
    @Nullable private View mTopContainer;

    /** Container for the ringer icon, and for the (initially hidden) ringer drawer view. */
    @Nullable private View mRingerAndDrawerContainer;

    /**
     * Background drawable for the ringer and drawer container. The background's top bound is
     * initially inset by the height of the (hidden) ringer drawer. When the drawer is animated in,
     * this top bound is animated to accommodate it.
     */
    @Nullable private Drawable mRingerAndDrawerContainerBackground;

    private ViewGroup mSelectedRingerContainer;
    private ImageView mSelectedRingerIcon;

    private ViewGroup mRingerDrawerContainer;
    private ViewGroup mRingerDrawerMute;
    private ViewGroup mRingerDrawerVibrate;
    private ViewGroup mRingerDrawerNormal;
    private ImageView mRingerDrawerMuteIcon;
    private ImageView mRingerDrawerVibrateIcon;
    private ImageView mRingerDrawerNormalIcon;

    /**
     * View that draws the 'selected' background behind one of the three ringer choices in the
     * drawer.
     */
    private ViewGroup mRingerDrawerNewSelectionBg;

    private final ValueAnimator mRingerDrawerIconColorAnimator = ValueAnimator.ofFloat(0f, 1f);
    private ImageView mRingerDrawerIconAnimatingSelected;
    private ImageView mRingerDrawerIconAnimatingDeselected;

    /**
     * Animates the volume dialog's background drawable bounds upwards, to match the height of the
     * expanded ringer drawer.
     */
    private final ValueAnimator mAnimateUpBackgroundToMatchDrawer = ValueAnimator.ofFloat(1f, 0f);

    private boolean mIsRingerDrawerOpen = false;
    private float mRingerDrawerClosedAmount = 1f;

    private ImageButton mRingerIcon;
    private ViewGroup mODICaptionsView;
    private CaptionsToggleImageButton mODICaptionsIcon;
    private View mSettingsView;
    private ImageButton mSettingsIcon;
    private FrameLayout mZenIcon;
    private final List<VolumeRow> mRows = new ArrayList<>();
    private ConfigurableTexts mConfigurableTexts;
    private final SparseBooleanArray mDynamic = new SparseBooleanArray();
    private final KeyguardManager mKeyguard;
    private final ActivityManager mActivityManager;
    private final AccessibilityManagerWrapper mAccessibilityMgr;
    private final Object mSafetyWarningLock = new Object();
    private final Accessibility mAccessibility = new Accessibility();

    private final ConfigurationController mConfigurationController;
    private final MediaOutputDialogFactory mMediaOutputDialogFactory;
    private final VolumePanelFactory mVolumePanelFactory;
    private final ActivityStarter mActivityStarter;

    private boolean mShowing;
    private boolean mShowA11yStream;

    private int mActiveStream;
    private int mPrevActiveStream;
    private boolean mAutomute = VolumePrefs.DEFAULT_ENABLE_AUTOMUTE;
    private boolean mSilentMode = VolumePrefs.DEFAULT_ENABLE_SILENT_MODE;
    private State mState;
    private SafetyWarningDialog mSafetyWarning;
    private boolean mHovering = false;
    private final boolean mShowActiveStreamOnly;
    private boolean mConfigChanged = false;
    private boolean mIsAnimatingDismiss = false;
    private boolean mHasSeenODICaptionsTooltip;
    private ViewStub mODICaptionsTooltipViewStub;
    private View mODICaptionsTooltipView = null;

    private final boolean mUseBackgroundBlur;
    private Consumer<Boolean> mCrossWindowBlurEnabledListener;
    private BackgroundBlurDrawable mDialogRowsViewBackground;
    private final InteractionJankMonitor mInteractionJankMonitor;

    private boolean mSeparateNotification;

    @VisibleForTesting
    int mVolumeRingerIconDrawableId;
    @VisibleForTesting
    int mVolumeRingerMuteIconDrawableId;

    public VolumeDialogImpl(
            Context context,
            VolumeDialogController volumeDialogController,
            AccessibilityManagerWrapper accessibilityManagerWrapper,
            DeviceProvisionedController deviceProvisionedController,
            ConfigurationController configurationController,
            MediaOutputDialogFactory mediaOutputDialogFactory,
            VolumePanelFactory volumePanelFactory,
            ActivityStarter activityStarter,
            InteractionJankMonitor interactionJankMonitor,
            DeviceConfigProxy deviceConfigProxy,
            Executor executor,
            DumpManager dumpManager) {
        mContext =
                new ContextThemeWrapper(context, R.style.volume_dialog_theme);
        mController = volumeDialogController;
        mKeyguard = (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
        mActivityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        mAccessibilityMgr = accessibilityManagerWrapper;
        mDeviceProvisionedController = deviceProvisionedController;
        mConfigurationController = configurationController;
        mMediaOutputDialogFactory = mediaOutputDialogFactory;
        mVolumePanelFactory = volumePanelFactory;
        mActivityStarter = activityStarter;
        mShowActiveStreamOnly = showActiveStreamOnly();
        mHasSeenODICaptionsTooltip =
                Prefs.getBoolean(context, Prefs.Key.HAS_SEEN_ODI_CAPTIONS_TOOLTIP, false);
        mShowLowMediaVolumeIcon =
            mContext.getResources().getBoolean(R.bool.config_showLowMediaVolumeIcon);
        mChangeVolumeRowTintWhenInactive =
            mContext.getResources().getBoolean(R.bool.config_changeVolumeRowTintWhenInactive);
        mDialogShowAnimationDurationMs =
            mContext.getResources().getInteger(R.integer.config_dialogShowAnimationDurationMs);
        mDialogHideAnimationDurationMs =
            mContext.getResources().getInteger(R.integer.config_dialogHideAnimationDurationMs);
        mUseBackgroundBlur =
            mContext.getResources().getBoolean(R.bool.config_volumeDialogUseBackgroundBlur);
        mInteractionJankMonitor = interactionJankMonitor;

        dumpManager.registerDumpable("VolumeDialogImpl", this);

        if (mUseBackgroundBlur) {
            final int dialogRowsViewColorAboveBlur = mContext.getColor(
                    R.color.volume_dialog_background_color_above_blur);
            final int dialogRowsViewColorNoBlur = mContext.getColor(
                    R.color.volume_dialog_background_color);
            mCrossWindowBlurEnabledListener = (enabled) -> {
                mDialogRowsViewBackground.setColor(
                        enabled ? dialogRowsViewColorAboveBlur : dialogRowsViewColorNoBlur);
                mDialogRowsView.invalidate();
            };
        }

        initDimens();

        mDeviceConfigProxy = deviceConfigProxy;
        mExecutor = executor;
        mSeparateNotification = mDeviceConfigProxy.getBoolean(DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.VOLUME_SEPARATE_NOTIFICATION, false);
        updateRingerModeIconSet();
    }

    /**
     * If ringer and notification are the same stream (T and earlier), use notification-like bell
     * icon set.
     * If ringer and notification are separated, then use generic speaker icons.
     */
    private void updateRingerModeIconSet() {
        if (mSeparateNotification) {
            mVolumeRingerIconDrawableId = R.drawable.ic_speaker_on;
            mVolumeRingerMuteIconDrawableId = R.drawable.ic_speaker_mute;
        } else {
            mVolumeRingerIconDrawableId = R.drawable.ic_volume_ringer;
            mVolumeRingerMuteIconDrawableId = R.drawable.ic_volume_ringer_mute;
        }

        if (mRingerDrawerMuteIcon != null) {
            mRingerDrawerMuteIcon.setImageResource(mVolumeRingerMuteIconDrawableId);
        }
        if (mRingerDrawerNormalIcon != null) {
            mRingerDrawerNormalIcon.setImageResource(mVolumeRingerIconDrawableId);
        }
    }

    /**
     * Change icon for ring stream (not ringer mode icon)
     */
    private void updateRingRowIcon() {
        Optional<VolumeRow> volumeRow = mRows.stream().filter(row -> row.stream == STREAM_RING)
                .findFirst();
        if (volumeRow.isPresent()) {
            VolumeRow volRow = volumeRow.get();
            volRow.iconRes = mSeparateNotification ? R.drawable.ic_ring_volume
                    : R.drawable.ic_volume_ringer;
            volRow.iconMuteRes = mSeparateNotification ? R.drawable.ic_ring_volume_off
                    : R.drawable.ic_volume_ringer_mute;
            volRow.setIcon(volRow.iconRes, mContext.getTheme());
        }
    }

    @Override
    public void onUiModeChanged() {
        mContext.getTheme().applyStyle(mContext.getThemeResId(), true);
    }

    public void init(int windowType, Callback callback) {
        initDialog(mActivityManager.getLockTaskModeState());

        mAccessibility.init();

        mController.addCallback(mControllerCallbackH, mHandler);
        mController.getState();

        mConfigurationController.addCallback(this);

        mDeviceConfigProxy.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_SYSTEMUI,
                mExecutor, this::onDeviceConfigChange);
    }

    @Override
    public void destroy() {
        mController.removeCallback(mControllerCallbackH);
        mHandler.removeCallbacksAndMessages(null);
        mConfigurationController.removeCallback(this);
        mDeviceConfigProxy.removeOnPropertiesChangedListener(this::onDeviceConfigChange);
    }

    /**
     * Update ringer mode icon based on the config
     */
    private void onDeviceConfigChange(DeviceConfig.Properties properties) {
        Set<String> changeSet = properties.getKeyset();
        if (changeSet.contains(SystemUiDeviceConfigFlags.VOLUME_SEPARATE_NOTIFICATION)) {
            boolean newVal = properties.getBoolean(
                    SystemUiDeviceConfigFlags.VOLUME_SEPARATE_NOTIFICATION, false);
            if (newVal != mSeparateNotification) {
                mSeparateNotification = newVal;
                updateRingerModeIconSet();
                updateRingRowIcon();

            }
        }
    }

    @Override
    public void onComputeInternalInsets(ViewTreeObserver.InternalInsetsInfo internalInsetsInfo) {
        // Set touchable region insets on the root dialog view. This tells WindowManager that
        // touches outside of this region should not be delivered to the volume window, and instead
        // go to the window below. This is the only way to do this - returning false in
        // onDispatchTouchEvent results in the event being ignored entirely, rather than passed to
        // the next window.
        internalInsetsInfo.setTouchableInsets(
                ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);

        mTouchableRegion.setEmpty();

        // Set the touchable region to the union of all child view bounds and the live caption
        // tooltip. We don't use touches on the volume dialog container itself, so this is fine.
        for (int i = 0; i < mDialogView.getChildCount(); i++) {
            unionViewBoundstoTouchableRegion(mDialogView.getChildAt(i));
        }

        if (mODICaptionsTooltipView != null && mODICaptionsTooltipView.getVisibility() == VISIBLE) {
            unionViewBoundstoTouchableRegion(mODICaptionsTooltipView);
        }

        internalInsetsInfo.touchableRegion.set(mTouchableRegion);
    }

    private void unionViewBoundstoTouchableRegion(final View view) {
        final int[] locInWindow = new int[2];
        view.getLocationInWindow(locInWindow);

        float x = locInWindow[0];
        float y = locInWindow[1];

        // The ringer and rows container has extra height at the top to fit the expanded ringer
        // drawer. This area should not be touchable unless the ringer drawer is open.
        // In landscape the ringer expands to the left and it has to be ensured that if there
        // are multiple rows they are touchable.
        if (view == mTopContainer && !mIsRingerDrawerOpen) {
            if (!isLandscape()) {
                y += getRingerDrawerOpenExtraSize();
            } else if (getRingerDrawerOpenExtraSize() > getVisibleRowsExtraSize()) {
                x += (getRingerDrawerOpenExtraSize() - getVisibleRowsExtraSize());
            }
        }

        mTouchableRegion.op(
                (int) x,
                (int) y,
                locInWindow[0] + view.getWidth(),
                locInWindow[1] + view.getHeight(),
                Region.Op.UNION);
    }

    private void initDialog(int lockTaskModeState) {
        mDialog = new CustomDialog(mContext);

        initDimens();

        mConfigurableTexts = new ConfigurableTexts(mContext);
        mHovering = false;
        mShowing = false;
        mWindow = mDialog.getWindow();
        mWindow.requestFeature(Window.FEATURE_NO_TITLE);
        mWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        mWindow.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND
                | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR);
        mWindow.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        mWindow.addPrivateFlags(WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY);
        mWindow.setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY);
        mWindow.setWindowAnimations(com.android.internal.R.style.Animation_Toast);
        WindowManager.LayoutParams lp = mWindow.getAttributes();
        lp.format = PixelFormat.TRANSLUCENT;
        lp.setTitle(VolumeDialogImpl.class.getSimpleName());
        lp.windowAnimations = -1;
        lp.gravity = mContext.getResources().getInteger(R.integer.volume_dialog_gravity);
        mWindow.setAttributes(lp);
        mWindow.setLayout(WRAP_CONTENT, WRAP_CONTENT);

        mDialog.setContentView(R.layout.volume_dialog);
        mDialogView = mDialog.findViewById(R.id.volume_dialog);
        mDialogView.setAlpha(0);
        mDialog.setCanceledOnTouchOutside(true);
        mDialog.setOnShowListener(dialog -> {
            mDialogView.getViewTreeObserver().addOnComputeInternalInsetsListener(this);
            if (!shouldSlideInVolumeTray()) {
                mDialogView.setTranslationX(mDialogView.getWidth() / 2.0f);
            }
            mDialogView.setAlpha(0);
            mDialogView.animate()
                    .alpha(1)
                    .translationX(0)
                    .setDuration(mDialogShowAnimationDurationMs)
                    .setListener(getJankListener(getDialogView(), TYPE_SHOW, DIALOG_TIMEOUT_MILLIS))
                    .setInterpolator(new SystemUIInterpolators.LogDecelerateInterpolator())
                    .withEndAction(() -> {
                        if (!Prefs.getBoolean(mContext, Prefs.Key.TOUCHED_RINGER_TOGGLE, false)) {
                            if (mRingerIcon != null) {
                                mRingerIcon.postOnAnimationDelayed(
                                        getSinglePressFor(mRingerIcon), 1500);
                            }
                        }
                    })
                    .start();
        });

        mDialog.setOnDismissListener(dialogInterface ->
                mDialogView
                        .getViewTreeObserver()
                        .removeOnComputeInternalInsetsListener(VolumeDialogImpl.this));

        mDialogView.setOnHoverListener((v, event) -> {
            int action = event.getActionMasked();
            mHovering = (action == MotionEvent.ACTION_HOVER_ENTER)
                    || (action == MotionEvent.ACTION_HOVER_MOVE);
            rescheduleTimeoutH();
            return true;
        });

        mDialogRowsView = mDialog.findViewById(R.id.volume_dialog_rows);
        if (mUseBackgroundBlur) {
            mDialogView.addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    mWindow.getWindowManager().addCrossWindowBlurEnabledListener(
                            mCrossWindowBlurEnabledListener);

                    mDialogRowsViewBackground = v.getViewRootImpl().createBackgroundBlurDrawable();

                    final Resources resources = mContext.getResources();
                    mDialogRowsViewBackground.setCornerRadius(
                            mContext.getResources().getDimensionPixelSize(Utils.getThemeAttr(
                                    mContext, android.R.attr.dialogCornerRadius)));
                    mDialogRowsViewBackground.setBlurRadius(resources.getDimensionPixelSize(
                            R.dimen.volume_dialog_background_blur_radius));
                    mDialogRowsView.setBackground(mDialogRowsViewBackground);
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    mWindow.getWindowManager().removeCrossWindowBlurEnabledListener(
                            mCrossWindowBlurEnabledListener);
                }
            });
        }

        mDialogRowsViewContainer = mDialogView.findViewById(R.id.volume_dialog_rows_container);
        mTopContainer = mDialogView.findViewById(R.id.volume_dialog_top_container);
        mRingerAndDrawerContainer = mDialogView.findViewById(
                R.id.volume_ringer_and_drawer_container);

        if (mRingerAndDrawerContainer != null) {
            if (isLandscape()) {
                // In landscape, we need to add padding to the bottom of the ringer drawer so that
                // when it expands to the left, it doesn't overlap any additional volume rows.
                mRingerAndDrawerContainer.setPadding(
                        mRingerAndDrawerContainer.getPaddingLeft(),
                        mRingerAndDrawerContainer.getPaddingTop(),
                        mRingerAndDrawerContainer.getPaddingRight(),
                        mRingerRowsPadding);

                // Since the ringer drawer is expanding to the left, outside of the background of
                // the dialog, it needs its own rounded background drawable. We also need that
                // background to be rounded on all sides. We'll use a background rounded on all four
                // corners, and then extend the container's background later to fill in the bottom
                // corners when the drawer is closed.
                mRingerAndDrawerContainer.setBackgroundDrawable(
                        mContext.getDrawable(R.drawable.volume_background_top_rounded));
            }

            // Post to wait for layout so that the background bounds are set.
            mRingerAndDrawerContainer.post(() -> {
                final LayerDrawable ringerAndDrawerBg =
                        (LayerDrawable) mRingerAndDrawerContainer.getBackground();

                // Retrieve the ShapeDrawable from within the background - this is what we will
                // animate up and down when the drawer is opened/closed.
                if (ringerAndDrawerBg != null && ringerAndDrawerBg.getNumberOfLayers() > 0) {
                    mRingerAndDrawerContainerBackground = ringerAndDrawerBg.getDrawable(0);

                    updateBackgroundForDrawerClosedAmount();
                    setTopContainerBackgroundDrawable();
                }
            });
        }

        mRinger = mDialog.findViewById(R.id.ringer);
        if (mRinger != null) {
            mRingerIcon = mRinger.findViewById(R.id.ringer_icon);
            mZenIcon = mRinger.findViewById(R.id.dnd_icon);
        }

        mSelectedRingerIcon = mDialog.findViewById(R.id.volume_new_ringer_active_icon);
        mSelectedRingerContainer = mDialog.findViewById(
                R.id.volume_new_ringer_active_icon_container);

        mRingerDrawerMute = mDialog.findViewById(R.id.volume_drawer_mute);
        mRingerDrawerNormal = mDialog.findViewById(R.id.volume_drawer_normal);
        mRingerDrawerVibrate = mDialog.findViewById(R.id.volume_drawer_vibrate);
        mRingerDrawerMuteIcon = mDialog.findViewById(R.id.volume_drawer_mute_icon);
        mRingerDrawerVibrateIcon = mDialog.findViewById(R.id.volume_drawer_vibrate_icon);
        mRingerDrawerNormalIcon = mDialog.findViewById(R.id.volume_drawer_normal_icon);
        mRingerDrawerNewSelectionBg = mDialog.findViewById(R.id.volume_drawer_selection_background);

        updateRingerModeIconSet();

        setupRingerDrawer();

        mODICaptionsView = mDialog.findViewById(R.id.odi_captions);
        if (mODICaptionsView != null) {
            mODICaptionsIcon = mODICaptionsView.findViewById(R.id.odi_captions_icon);
        }
        mODICaptionsTooltipViewStub = mDialog.findViewById(R.id.odi_captions_tooltip_stub);
        if (mHasSeenODICaptionsTooltip && mODICaptionsTooltipViewStub != null) {
            mDialogView.removeView(mODICaptionsTooltipViewStub);
            mODICaptionsTooltipViewStub = null;
        }

        mSettingsView = mDialog.findViewById(R.id.settings_container);
        mSettingsIcon = mDialog.findViewById(R.id.settings);

        if (mRows.isEmpty()) {
            if (!AudioSystem.isSingleVolume(mContext)) {
                addRow(STREAM_ACCESSIBILITY, R.drawable.ic_volume_accessibility,
                        R.drawable.ic_volume_accessibility, true, false);
            }
            addRow(AudioManager.STREAM_MUSIC,
                    R.drawable.ic_volume_media, R.drawable.ic_volume_media_mute, true, true);
            if (!AudioSystem.isSingleVolume(mContext)) {
                if (mSeparateNotification) {
                    addRow(AudioManager.STREAM_RING, R.drawable.ic_ring_volume,
                            R.drawable.ic_ring_volume_off, true, false);
                } else {
                    addRow(AudioManager.STREAM_RING, R.drawable.ic_volume_ringer,
                            R.drawable.ic_volume_ringer, true, false);
                }

                addRow(STREAM_ALARM,
                        R.drawable.ic_alarm, R.drawable.ic_volume_alarm_mute, true, false);
                addRow(AudioManager.STREAM_VOICE_CALL,
                        com.android.internal.R.drawable.ic_phone,
                        com.android.internal.R.drawable.ic_phone, false, false);
                addRow(AudioManager.STREAM_BLUETOOTH_SCO,
                        R.drawable.ic_volume_bt_sco, R.drawable.ic_volume_bt_sco, false, false);
                addRow(AudioManager.STREAM_SYSTEM, R.drawable.ic_volume_system,
                        R.drawable.ic_volume_system_mute, false, false);
            }
        } else {
            addExistingRows();
        }

        updateRowsH(getActiveRow());
        initRingerH();
        initSettingsH(lockTaskModeState);
        initODICaptionsH();
    }

    private void initDimens() {
        mDialogWidth = mContext.getResources().getDimensionPixelSize(
                R.dimen.volume_dialog_panel_width);
        mDialogCornerRadius = mContext.getResources().getDimensionPixelSize(
                R.dimen.volume_dialog_panel_width_half);
        mRingerDrawerItemSize = mContext.getResources().getDimensionPixelSize(
                R.dimen.volume_ringer_drawer_item_size);
        mRingerRowsPadding = mContext.getResources().getDimensionPixelSize(
                R.dimen.volume_dialog_ringer_rows_padding);
        mShowVibrate = mController.hasVibrator();

        // Normal, mute, and possibly vibrate.
        mRingerCount = mShowVibrate ? 3 : 2;
    }

    protected ViewGroup getDialogView() {
        return mDialogView;
    }

    private int getAlphaAttr(int attr) {
        TypedArray ta = mContext.obtainStyledAttributes(new int[]{attr});
        float alpha = ta.getFloat(0, 0);
        ta.recycle();
        return (int) (alpha * 255);
    }

    private boolean shouldSlideInVolumeTray() {
        return mContext.getDisplay().getRotation() != RotationPolicy.NATURAL_ROTATION;
    }

    private boolean isLandscape() {
        return mContext.getResources().getConfiguration().orientation ==
                Configuration.ORIENTATION_LANDSCAPE;
    }

    private boolean isRtl() {
        return mContext.getResources().getConfiguration().getLayoutDirection()
                == LAYOUT_DIRECTION_RTL;
    }

    public void setStreamImportant(int stream, boolean important) {
        mHandler.obtainMessage(H.SET_STREAM_IMPORTANT, stream, important ? 1 : 0).sendToTarget();
    }

    public void setAutomute(boolean automute) {
        if (mAutomute == automute) return;
        mAutomute = automute;
        mHandler.sendEmptyMessage(H.RECHECK_ALL);
    }

    public void setSilentMode(boolean silentMode) {
        if (mSilentMode == silentMode) return;
        mSilentMode = silentMode;
        mHandler.sendEmptyMessage(H.RECHECK_ALL);
    }

    private void addRow(int stream, int iconRes, int iconMuteRes, boolean important,
            boolean defaultStream) {
        addRow(stream, iconRes, iconMuteRes, important, defaultStream, false);
    }

    private void addRow(int stream, int iconRes, int iconMuteRes, boolean important,
            boolean defaultStream, boolean dynamic) {
        if (D.BUG) Slog.d(TAG, "Adding row for stream " + stream);
        VolumeRow row = new VolumeRow();
        initRow(row, stream, iconRes, iconMuteRes, important, defaultStream);
        mDialogRowsView.addView(row.view);
        mRows.add(row);
    }

    private void addExistingRows() {
        int N = mRows.size();
        for (int i = 0; i < N; i++) {
            final VolumeRow row = mRows.get(i);
            initRow(row, row.stream, row.iconRes, row.iconMuteRes, row.important,
                    row.defaultStream);
            mDialogRowsView.addView(row.view);
            updateVolumeRowH(row);
        }
    }

    private VolumeRow getActiveRow() {
        for (VolumeRow row : mRows) {
            if (row.stream == mActiveStream) {
                return row;
            }
        }
        for (VolumeRow row : mRows) {
            if (row.stream == STREAM_MUSIC) {
                return row;
            }
        }
        return mRows.get(0);
    }

    private VolumeRow findRow(int stream) {
        for (VolumeRow row : mRows) {
            if (row.stream == stream) return row;
        }
        return null;
    }

    /**
     * Print dump info for debugging.
     */
    public void dump(PrintWriter writer, String[] unusedArgs) {
        writer.println(VolumeDialogImpl.class.getSimpleName() + " state:");
        writer.print("  mShowing: "); writer.println(mShowing);
        writer.print("  mActiveStream: "); writer.println(mActiveStream);
        writer.print("  mDynamic: "); writer.println(mDynamic);
        writer.print("  mAutomute: "); writer.println(mAutomute);
        writer.print("  mSilentMode: "); writer.println(mSilentMode);
    }

    private static int getImpliedLevel(SeekBar seekBar, int progress) {
        final int m = seekBar.getMax();
        final int n = m / 100 - 1;
        final int level = progress == 0 ? 0
                : progress == m ? (m / 100) : (1 + (int) ((progress / (float) m) * n));
        return level;
    }

    @SuppressLint("InflateParams")
    private void initRow(final VolumeRow row, final int stream, int iconRes, int iconMuteRes,
            boolean important, boolean defaultStream) {
        row.stream = stream;
        row.iconRes = iconRes;
        row.iconMuteRes = iconMuteRes;
        row.important = important;
        row.defaultStream = defaultStream;
        row.view = mDialog.getLayoutInflater().inflate(R.layout.volume_dialog_row, null);
        row.view.setId(row.stream);
        row.view.setTag(row);
        row.header = row.view.findViewById(R.id.volume_row_header);
        row.header.setId(20 * row.stream);
        if (stream == STREAM_ACCESSIBILITY) {
            row.header.setFilters(new InputFilter[] {new InputFilter.LengthFilter(13)});
        }
        row.dndIcon = row.view.findViewById(R.id.dnd_icon);
        row.slider = row.view.findViewById(R.id.volume_row_slider);
        row.slider.setOnSeekBarChangeListener(new VolumeSeekBarChangeListener(row));
        row.number = row.view.findViewById(R.id.volume_number);

        row.anim = null;

        final LayerDrawable seekbarDrawable =
                (LayerDrawable) mContext.getDrawable(R.drawable.volume_row_seekbar);

        final LayerDrawable seekbarProgressDrawable = (LayerDrawable)
                ((RoundedCornerProgressDrawable) seekbarDrawable.findDrawableByLayerId(
                        android.R.id.progress)).getDrawable();

        row.sliderProgressSolid = seekbarProgressDrawable.findDrawableByLayerId(
                R.id.volume_seekbar_progress_solid);
        final Drawable sliderProgressIcon = seekbarProgressDrawable.findDrawableByLayerId(
                        R.id.volume_seekbar_progress_icon);
        row.sliderProgressIcon = sliderProgressIcon != null ? (AlphaTintDrawableWrapper)
                ((RotateDrawable) sliderProgressIcon).getDrawable() : null;

        row.slider.setProgressDrawable(seekbarDrawable);

        row.icon = row.view.findViewById(R.id.volume_row_icon);

        row.setIcon(iconRes, mContext.getTheme());

        if (row.icon != null) {
            if (row.stream != AudioSystem.STREAM_ACCESSIBILITY) {
                row.icon.setOnClickListener(v -> {
                    Events.writeEvent(Events.EVENT_ICON_CLICK, row.stream, row.iconState);
                    mController.setActiveStream(row.stream);
                    if (row.stream == AudioManager.STREAM_RING) {
                        final boolean hasVibrator = mController.hasVibrator();
                        if (mState.ringerModeInternal == AudioManager.RINGER_MODE_NORMAL) {
                            if (hasVibrator) {
                                mController.setRingerMode(AudioManager.RINGER_MODE_VIBRATE, false);
                            } else {
                                final boolean wasZero = row.ss.level == 0;
                                mController.setStreamVolume(stream,
                                        wasZero ? row.lastAudibleLevel : 0);
                            }
                        } else {
                            mController.setRingerMode(
                                    AudioManager.RINGER_MODE_NORMAL, false);
                            if (row.ss.level == 0) {
                                mController.setStreamVolume(stream, 1);
                            }
                        }
                    } else {
                        final boolean vmute = row.ss.level == row.ss.levelMin;
                        mController.setStreamVolume(stream,
                                vmute ? row.lastAudibleLevel : row.ss.levelMin);
                    }
                    row.userAttempt = 0;  // reset the grace period, slider updates immediately
                });
            } else {
                row.icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            }
        }
    }

    private void setRingerMode(int newRingerMode) {
        Events.writeEvent(Events.EVENT_RINGER_TOGGLE, newRingerMode);
        incrementManualToggleCount();
        updateRingerH();
        provideTouchFeedbackH(newRingerMode);
        mController.setRingerMode(newRingerMode, false);
        maybeShowToastH(newRingerMode);
    }

    private void setupRingerDrawer() {
        mRingerDrawerContainer = mDialog.findViewById(R.id.volume_drawer_container);

        if (mRingerDrawerContainer == null) {
            return;
        }

        if (!mShowVibrate) {
            mRingerDrawerVibrate.setVisibility(GONE);
        }

        // In portrait, add padding to the bottom to account for the height of the open ringer
        // drawer.
        if (!isLandscape()) {
            mDialogView.setPadding(
                    mDialogView.getPaddingLeft(),
                    mDialogView.getPaddingTop(),
                    mDialogView.getPaddingRight(),
                    mDialogView.getPaddingBottom() + getRingerDrawerOpenExtraSize());
        } else {
            mDialogView.setPadding(
                    mDialogView.getPaddingLeft() + getRingerDrawerOpenExtraSize(),
                    mDialogView.getPaddingTop(),
                    mDialogView.getPaddingRight(),
                    mDialogView.getPaddingBottom());
        }

        ((LinearLayout) mRingerDrawerContainer.findViewById(R.id.volume_drawer_options))
                .setOrientation(isLandscape() ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);

        mSelectedRingerContainer.setOnClickListener(view -> {
            if (mIsRingerDrawerOpen) {
                hideRingerDrawer();
            } else {
                showRingerDrawer();
            }
        });

        mRingerDrawerVibrate.setOnClickListener(
                new RingerDrawerItemClickListener(RINGER_MODE_VIBRATE));
        mRingerDrawerMute.setOnClickListener(
                new RingerDrawerItemClickListener(RINGER_MODE_SILENT));
        mRingerDrawerNormal.setOnClickListener(
                new RingerDrawerItemClickListener(RINGER_MODE_NORMAL));

        final int unselectedColor = Utils.getColorAccentDefaultColor(mContext);
        final int selectedColor = Utils.getColorAttrDefaultColor(
                mContext, android.R.attr.colorBackgroundFloating);

        // Add an update listener that animates the deselected icon to the unselected color, and the
        // selected icon to the selected color.
        mRingerDrawerIconColorAnimator.addUpdateListener(
                anim -> {
                    final float currentValue = (float) anim.getAnimatedValue();
                    final int curUnselectedColor = (int) ArgbEvaluator.getInstance().evaluate(
                            currentValue, selectedColor, unselectedColor);
                    final int curSelectedColor = (int) ArgbEvaluator.getInstance().evaluate(
                            currentValue, unselectedColor, selectedColor);

                    mRingerDrawerIconAnimatingDeselected.setColorFilter(curUnselectedColor);
                    mRingerDrawerIconAnimatingSelected.setColorFilter(curSelectedColor);
                });
        mRingerDrawerIconColorAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mRingerDrawerIconAnimatingDeselected.clearColorFilter();
                mRingerDrawerIconAnimatingSelected.clearColorFilter();
            }
        });
        mRingerDrawerIconColorAnimator.setDuration(DRAWER_ANIMATION_DURATION_SHORT);

        mAnimateUpBackgroundToMatchDrawer.addUpdateListener(valueAnimator -> {
            mRingerDrawerClosedAmount = (float) valueAnimator.getAnimatedValue();
            updateBackgroundForDrawerClosedAmount();
        });
    }

    private ImageView getDrawerIconViewForMode(int mode) {
        if (mode == RINGER_MODE_VIBRATE) {
            return mRingerDrawerVibrateIcon;
        } else if (mode == RINGER_MODE_SILENT) {
            return mRingerDrawerMuteIcon;
        } else {
            return mRingerDrawerNormalIcon;
        }
    }

    /**
     * Translation to apply form the origin (either top or left) to overlap the selection background
     * with the given mode in the drawer.
     */
    private float getTranslationInDrawerForRingerMode(int mode) {
        return mode == RINGER_MODE_VIBRATE
                ? -mRingerDrawerItemSize * 2
                : mode == RINGER_MODE_SILENT
                        ? -mRingerDrawerItemSize
                        : 0;
    }

    /** Animates in the ringer drawer. */
    private void showRingerDrawer() {
        if (mIsRingerDrawerOpen) {
            return;
        }

        // Show all ringer icons except the currently selected one, since we're going to animate the
        // ringer button to that position.
        mRingerDrawerVibrateIcon.setVisibility(
                mState.ringerModeInternal == RINGER_MODE_VIBRATE ? INVISIBLE : VISIBLE);
        mRingerDrawerMuteIcon.setVisibility(
                mState.ringerModeInternal == RINGER_MODE_SILENT ? INVISIBLE : VISIBLE);
        mRingerDrawerNormalIcon.setVisibility(
                mState.ringerModeInternal == RINGER_MODE_NORMAL ? INVISIBLE : VISIBLE);

        // Hide the selection background - we use this to show a selection when one is
        // tapped, so it should be invisible until that happens. However, position it below
        // the currently selected ringer so that it's ready to animate.
        mRingerDrawerNewSelectionBg.setAlpha(0f);

        if (!isLandscape()) {
            mRingerDrawerNewSelectionBg.setTranslationY(
                    getTranslationInDrawerForRingerMode(mState.ringerModeInternal));
        } else {
            mRingerDrawerNewSelectionBg.setTranslationX(
                    getTranslationInDrawerForRingerMode(mState.ringerModeInternal));
        }

        // Move the drawer so that the top/rightmost ringer choice overlaps with the selected ringer
        // icon.
        if (!isLandscape()) {
            mRingerDrawerContainer.setTranslationY(mRingerDrawerItemSize * (mRingerCount - 1));
        } else {
            mRingerDrawerContainer.setTranslationX(mRingerDrawerItemSize * (mRingerCount - 1));
        }
        mRingerDrawerContainer.setAlpha(0f);
        mRingerDrawerContainer.setVisibility(VISIBLE);

        final int ringerDrawerAnimationDuration = mState.ringerModeInternal == RINGER_MODE_VIBRATE
                ? DRAWER_ANIMATION_DURATION_SHORT
                : DRAWER_ANIMATION_DURATION;

        // Animate the drawer up and visible.
        mRingerDrawerContainer.animate()
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                // Vibrate is way farther up, so give the selected ringer icon a head start if
                // vibrate is selected.
                .setDuration(ringerDrawerAnimationDuration)
                .setStartDelay(mState.ringerModeInternal == RINGER_MODE_VIBRATE
                        ? DRAWER_ANIMATION_DURATION - DRAWER_ANIMATION_DURATION_SHORT
                        : 0)
                .alpha(1f)
                .translationX(0f)
                .translationY(0f)
                .start();

        // Animate the selected ringer view up to that ringer's position in the drawer.
        mSelectedRingerContainer.animate()
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .setDuration(DRAWER_ANIMATION_DURATION)
                .withEndAction(() ->
                        getDrawerIconViewForMode(mState.ringerModeInternal).setVisibility(VISIBLE));

        mAnimateUpBackgroundToMatchDrawer.setDuration(ringerDrawerAnimationDuration);
        mAnimateUpBackgroundToMatchDrawer.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        mAnimateUpBackgroundToMatchDrawer.start();

        if (!isLandscape()) {
            mSelectedRingerContainer.animate()
                    .translationY(getTranslationInDrawerForRingerMode(mState.ringerModeInternal))
                    .start();
        } else {
            mSelectedRingerContainer.animate()
                    .translationX(getTranslationInDrawerForRingerMode(mState.ringerModeInternal))
                    .start();
        }

        // When the ringer drawer is open, tapping the currently selected ringer will set the ringer
        // to the current ringer mode. Change the content description to that, instead of the 'tap
        // to change ringer mode' default.
        mSelectedRingerContainer.setContentDescription(
                mContext.getString(getStringDescriptionResourceForRingerMode(
                        mState.ringerModeInternal)));

        mIsRingerDrawerOpen = true;
    }

    /** Animates away the ringer drawer. */
    private void hideRingerDrawer() {

        // If the ringer drawer isn't present, don't try to hide it.
        if (mRingerDrawerContainer == null) {
            return;
        }

        if (!mIsRingerDrawerOpen) {
            return;
        }

        // Hide the drawer icon for the selected ringer - it's visible in the ringer button and we
        // don't want to be able to see it while it animates away.
        getDrawerIconViewForMode(mState.ringerModeInternal).setVisibility(INVISIBLE);

        mRingerDrawerContainer.animate()
                .alpha(0f)
                .setDuration(DRAWER_ANIMATION_DURATION)
                .setStartDelay(0)
                .withEndAction(() -> mRingerDrawerContainer.setVisibility(INVISIBLE));

        if (!isLandscape()) {
            mRingerDrawerContainer.animate()
                    .translationY(mRingerDrawerItemSize * 2)
                    .start();
        } else {
            mRingerDrawerContainer.animate()
                    .translationX(mRingerDrawerItemSize * 2)
                    .start();
        }

        mAnimateUpBackgroundToMatchDrawer.setDuration(DRAWER_ANIMATION_DURATION);
        mAnimateUpBackgroundToMatchDrawer.setInterpolator(Interpolators.FAST_OUT_SLOW_IN_REVERSE);
        mAnimateUpBackgroundToMatchDrawer.reverse();

        mSelectedRingerContainer.animate()
                .translationX(0f)
                .translationY(0f)
                .start();

        // When the drawer is closed, tapping the selected ringer drawer will open it, allowing the
        // user to change the ringer.
        mSelectedRingerContainer.setContentDescription(
                mContext.getString(R.string.volume_ringer_change));

        mIsRingerDrawerOpen = false;
    }

    private void initSettingsH(int lockTaskModeState) {
        if (mSettingsView != null) {
            mSettingsView.setVisibility(
                    mDeviceProvisionedController.isCurrentUserSetup() &&
                            lockTaskModeState == LOCK_TASK_MODE_NONE ? VISIBLE : GONE);
        }
        if (mSettingsIcon != null) {
            mSettingsIcon.setOnClickListener(v -> {
                Events.writeEvent(Events.EVENT_SETTINGS_CLICK);
                dismissH(DISMISS_REASON_SETTINGS_CLICKED);
                mMediaOutputDialogFactory.dismiss();
                if (FeatureFlagUtils.isEnabled(mContext,
                        FeatureFlagUtils.SETTINGS_VOLUME_PANEL_IN_SYSTEMUI)) {
                    mVolumePanelFactory.create(true /* aboveStatusBar */, null);
                } else {
                    mActivityStarter.startActivity(new Intent(Settings.Panel.ACTION_VOLUME),
                            true /* dismissShade */);
                }
            });
        }
    }

    public void initRingerH() {
        if (mRingerIcon != null) {
            mRingerIcon.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
            mRingerIcon.setOnClickListener(v -> {
                Prefs.putBoolean(mContext, Prefs.Key.TOUCHED_RINGER_TOGGLE, true);
                final StreamState ss = mState.states.get(AudioManager.STREAM_RING);
                if (ss == null) {
                    return;
                }
                // normal -> vibrate -> silent -> normal (skip vibrate if device doesn't have
                // a vibrator.
                int newRingerMode;
                final boolean hasVibrator = mController.hasVibrator();
                if (mState.ringerModeInternal == AudioManager.RINGER_MODE_NORMAL) {
                    if (hasVibrator) {
                        newRingerMode = AudioManager.RINGER_MODE_VIBRATE;
                    } else {
                        newRingerMode = AudioManager.RINGER_MODE_SILENT;
                    }
                } else if (mState.ringerModeInternal == AudioManager.RINGER_MODE_VIBRATE) {
                    newRingerMode = AudioManager.RINGER_MODE_SILENT;
                } else {
                    newRingerMode = AudioManager.RINGER_MODE_NORMAL;
                    if (ss.level == 0) {
                        mController.setStreamVolume(AudioManager.STREAM_RING, 1);
                    }
                }

                setRingerMode(newRingerMode);
            });
        }
        updateRingerH();
    }

    private void initODICaptionsH() {
        if (mODICaptionsIcon != null) {
            mODICaptionsIcon.setOnConfirmedTapListener(() -> {
                onCaptionIconClicked();
                Events.writeEvent(Events.EVENT_ODI_CAPTIONS_CLICK);
            }, mHandler);
        }

        mController.getCaptionsComponentState(false);
    }

    private void checkODICaptionsTooltip(boolean fromDismiss) {
        if (!mHasSeenODICaptionsTooltip && !fromDismiss && mODICaptionsTooltipViewStub != null) {
            mController.getCaptionsComponentState(true);
        } else {
            if (mHasSeenODICaptionsTooltip && fromDismiss && mODICaptionsTooltipView != null) {
                hideCaptionsTooltip();
            }
        }
    }

    protected void showCaptionsTooltip() {
        if (!mHasSeenODICaptionsTooltip && mODICaptionsTooltipViewStub != null) {
            mODICaptionsTooltipView = mODICaptionsTooltipViewStub.inflate();
            mODICaptionsTooltipView.findViewById(R.id.dismiss).setOnClickListener(v -> {
                hideCaptionsTooltip();
                Events.writeEvent(Events.EVENT_ODI_CAPTIONS_TOOLTIP_CLICK);
            });
            mODICaptionsTooltipViewStub = null;
            rescheduleTimeoutH();
        }

        if (mODICaptionsTooltipView != null) {
            mODICaptionsTooltipView.setAlpha(0.0f);

            // We need to wait for layout and then center the caption view. Since the height of the
            // dialog is now dynamic (with the variable ringer drawer height changing the height of
            // the dialog), we need to do this here in code vs. in XML.
            mHandler.post(() -> {
                final int[] odiTooltipLocation = mODICaptionsTooltipView.getLocationOnScreen();
                final int[] odiButtonLocation = mODICaptionsIcon.getLocationOnScreen();

                final float heightDiffForCentering =
                        (mODICaptionsTooltipView.getHeight() - mODICaptionsIcon.getHeight()) / 2f;

                mODICaptionsTooltipView.setTranslationY(
                        odiButtonLocation[1] - odiTooltipLocation[1] - heightDiffForCentering);

                mODICaptionsTooltipView.animate()
                        .alpha(1.0f)
                        .setStartDelay(mDialogShowAnimationDurationMs)
                        .withEndAction(() -> {
                            if (D.BUG) {
                                Log.d(TAG, "tool:checkODICaptionsTooltip() putBoolean true");
                            }
                            Prefs.putBoolean(mContext,
                                    Prefs.Key.HAS_SEEN_ODI_CAPTIONS_TOOLTIP, true);
                            mHasSeenODICaptionsTooltip = true;
                            if (mODICaptionsIcon != null) {
                                mODICaptionsIcon
                                        .postOnAnimation(getSinglePressFor(mODICaptionsIcon));
                            }
                        })
                        .start();
            });
        }
    }

    private void hideCaptionsTooltip() {
        if (mODICaptionsTooltipView != null && mODICaptionsTooltipView.getVisibility() == VISIBLE) {
            mODICaptionsTooltipView.animate().cancel();
            mODICaptionsTooltipView.setAlpha(1.f);
            mODICaptionsTooltipView.animate()
                    .alpha(0.f)
                    .setStartDelay(0)
                    .setDuration(mDialogHideAnimationDurationMs)
                    .withEndAction(() -> {
                        // It might have been nulled out by tryToRemoveCaptionsTooltip.
                        if (mODICaptionsTooltipView != null) {
                            mODICaptionsTooltipView.setVisibility(INVISIBLE);
                        }
                    })
                    .start();
        }
    }

    protected void tryToRemoveCaptionsTooltip() {
        if (mHasSeenODICaptionsTooltip && mODICaptionsTooltipView != null) {
            ViewGroup container = mDialog.findViewById(R.id.volume_dialog_container);
            container.removeView(mODICaptionsTooltipView);
            mODICaptionsTooltipView = null;
        }
    }

    private void updateODICaptionsH(boolean isServiceComponentEnabled, boolean fromTooltip) {
        if (mODICaptionsView != null) {
            mODICaptionsView.setVisibility(isServiceComponentEnabled ? VISIBLE : GONE);
        }

        if (!isServiceComponentEnabled) return;

        updateCaptionsIcon();
        if (fromTooltip) showCaptionsTooltip();
    }

    private void updateCaptionsIcon() {
        boolean captionsEnabled = mController.areCaptionsEnabled();
        if (mODICaptionsIcon.getCaptionsEnabled() != captionsEnabled) {
            mHandler.post(mODICaptionsIcon.setCaptionsEnabled(captionsEnabled));
        }
    }

    private void onCaptionIconClicked() {
        boolean isEnabled = mController.areCaptionsEnabled();
        mController.setCaptionsEnabled(!isEnabled);
        updateCaptionsIcon();
    }

    private void incrementManualToggleCount() {
        ContentResolver cr = mContext.getContentResolver();
        int ringerCount = Settings.Secure.getInt(cr, Settings.Secure.MANUAL_RINGER_TOGGLE_COUNT, 0);
        Settings.Secure.putInt(cr, Settings.Secure.MANUAL_RINGER_TOGGLE_COUNT, ringerCount + 1);
    }

    private void provideTouchFeedbackH(int newRingerMode) {
        VibrationEffect effect = null;
        switch (newRingerMode) {
            case RINGER_MODE_NORMAL:
                mController.scheduleTouchFeedback();
                break;
            case RINGER_MODE_SILENT:
                effect = VibrationEffect.get(VibrationEffect.EFFECT_CLICK);
                break;
            case RINGER_MODE_VIBRATE:
                // Feedback handled by onStateChange, for feedback both when user toggles
                // directly in volume dialog, or drags slider to a value of 0 in settings.
                break;
            default:
                effect = VibrationEffect.get(VibrationEffect.EFFECT_DOUBLE_CLICK);
        }
        if (effect != null) {
            mController.vibrate(effect);
        }
    }

    private void maybeShowToastH(int newRingerMode) {
        int seenToastCount = Prefs.getInt(mContext, Prefs.Key.SEEN_RINGER_GUIDANCE_COUNT, 0);

        if (seenToastCount > VolumePrefs.SHOW_RINGER_TOAST_COUNT) {
            return;
        }
        CharSequence toastText = null;
        switch (newRingerMode) {
            case RINGER_MODE_NORMAL:
                final StreamState ss = mState.states.get(AudioManager.STREAM_RING);
                if (ss != null) {
                    toastText = mContext.getString(
                            R.string.volume_dialog_ringer_guidance_ring,
                            Utils.formatPercentage(ss.level, ss.levelMax));
                }
                break;
            case RINGER_MODE_SILENT:
                toastText = mContext.getString(
                        com.android.internal.R.string.volume_dialog_ringer_guidance_silent);
                break;
            case RINGER_MODE_VIBRATE:
            default:
                toastText = mContext.getString(
                        com.android.internal.R.string.volume_dialog_ringer_guidance_vibrate);
        }

        Toast.makeText(mContext, toastText, Toast.LENGTH_SHORT).show();
        seenToastCount++;
        Prefs.putInt(mContext, Prefs.Key.SEEN_RINGER_GUIDANCE_COUNT, seenToastCount);
    }

    public void show(int reason) {
        mHandler.obtainMessage(H.SHOW, reason, 0).sendToTarget();
    }

    public void dismiss(int reason) {
        mHandler.obtainMessage(H.DISMISS, reason, 0).sendToTarget();
    }

    private Animator.AnimatorListener getJankListener(View v, String type, long timeout) {
        return new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(@NonNull Animator animation) {
                if (!v.isAttachedToWindow()) {
                    if (D.BUG) Log.d(TAG, "onAnimationStart view do not attached to window:" + v);
                    return;
                }
                mInteractionJankMonitor.begin(Builder.withView(CUJ_VOLUME_CONTROL, v).setTag(type)
                        .setTimeout(timeout));
            }

            @Override
            public void onAnimationEnd(@NonNull Animator animation) {
                mInteractionJankMonitor.end(CUJ_VOLUME_CONTROL);
            }

            @Override
            public void onAnimationCancel(@NonNull Animator animation) {
                mInteractionJankMonitor.cancel(CUJ_VOLUME_CONTROL);
            }

            @Override
            public void onAnimationRepeat(@NonNull Animator animation) {
                // no-op
            }
        };
    }

    private void showH(int reason, boolean keyguardLocked, int lockTaskModeState) {
        Trace.beginSection("VolumeDialogImpl#showH");
        Log.i(TAG, "showH r=" + Events.SHOW_REASONS[reason]);
        mHandler.removeMessages(H.SHOW);
        mHandler.removeMessages(H.DISMISS);
        rescheduleTimeoutH();

        if (mConfigChanged) {
            initDialog(lockTaskModeState); // resets mShowing to false
            mConfigurableTexts.update();
            mConfigChanged = false;
        }

        initSettingsH(lockTaskModeState);
        mShowing = true;
        mIsAnimatingDismiss = false;
        mDialog.show();
        Events.writeEvent(Events.EVENT_SHOW_DIALOG, reason, keyguardLocked);
        mController.notifyVisible(true);
        mController.getCaptionsComponentState(false);
        checkODICaptionsTooltip(false);
        updateBackgroundForDrawerClosedAmount();
        Trace.endSection();
    }

    protected void rescheduleTimeoutH() {
        mHandler.removeMessages(H.DISMISS);
        final int timeout = computeTimeoutH();
        mHandler.sendMessageDelayed(mHandler
                .obtainMessage(H.DISMISS, Events.DISMISS_REASON_TIMEOUT, 0), timeout);
        Log.i(TAG, "rescheduleTimeout " + timeout + " " + Debug.getCaller());
        mController.userActivity();
    }

    private int computeTimeoutH() {
        if (mHovering) {
            return mAccessibilityMgr.getRecommendedTimeoutMillis(DIALOG_HOVERING_TIMEOUT_MILLIS,
                    AccessibilityManager.FLAG_CONTENT_CONTROLS);
        }
        if (mSafetyWarning != null) {
            return mAccessibilityMgr.getRecommendedTimeoutMillis(
                    DIALOG_SAFETYWARNING_TIMEOUT_MILLIS,
                    AccessibilityManager.FLAG_CONTENT_TEXT
                            | AccessibilityManager.FLAG_CONTENT_CONTROLS);
        }
        if (!mHasSeenODICaptionsTooltip && mODICaptionsTooltipView != null) {
            return mAccessibilityMgr.getRecommendedTimeoutMillis(
                    DIALOG_ODI_CAPTIONS_TOOLTIP_TIMEOUT_MILLIS,
                    AccessibilityManager.FLAG_CONTENT_TEXT
                            | AccessibilityManager.FLAG_CONTENT_CONTROLS);
        }
        return mAccessibilityMgr.getRecommendedTimeoutMillis(DIALOG_TIMEOUT_MILLIS,
                AccessibilityManager.FLAG_CONTENT_CONTROLS);
    }

    protected void dismissH(int reason) {
        Trace.beginSection("VolumeDialogImpl#dismissH");

        Log.i(TAG, "mDialog.dismiss() reason: " + Events.DISMISS_REASONS[reason]
                + " from: " + Debug.getCaller());

        mHandler.removeMessages(H.DISMISS);
        mHandler.removeMessages(H.SHOW);
        if (mIsAnimatingDismiss) {
            return;
        }
        mIsAnimatingDismiss = true;
        mDialogView.animate().cancel();
        if (mShowing) {
            mShowing = false;
            // Only logs when the volume dialog visibility is changed.
            Events.writeEvent(Events.EVENT_DISMISS_DIALOG, reason);
        }
        mDialogView.setTranslationX(0);
        mDialogView.setAlpha(1);
        ViewPropertyAnimator animator = mDialogView.animate()
                .alpha(0)
                .setDuration(mDialogHideAnimationDurationMs)
                .setInterpolator(new SystemUIInterpolators.LogAccelerateInterpolator())
                .withEndAction(() -> mHandler.postDelayed(() -> {
                    mDialog.dismiss();
                    tryToRemoveCaptionsTooltip();
                    mIsAnimatingDismiss = false;

                    hideRingerDrawer();
                }, 50));
        if (!shouldSlideInVolumeTray()) animator.translationX(mDialogView.getWidth() / 2.0f);
        animator.setListener(getJankListener(getDialogView(), TYPE_DISMISS,
                mDialogHideAnimationDurationMs)).start();
        checkODICaptionsTooltip(true);
        mController.notifyVisible(false);
        synchronized (mSafetyWarningLock) {
            if (mSafetyWarning != null) {
                if (D.BUG) Log.d(TAG, "SafetyWarning dismissed");
                mSafetyWarning.dismiss();
            }
        }
        Trace.endSection();
    }

    private boolean showActiveStreamOnly() {
        return mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                || mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEVISION);
    }

    private boolean shouldBeVisibleH(VolumeRow row, VolumeRow activeRow) {
        boolean isActive = row.stream == activeRow.stream;

        if (isActive) {
            return true;
        }

        if (!mShowActiveStreamOnly) {
            if (row.stream == AudioSystem.STREAM_ACCESSIBILITY) {
                return mShowA11yStream;
            }

            // if the active row is accessibility, then continue to display previous
            // active row since accessibility is displayed under it
            if (activeRow.stream == AudioSystem.STREAM_ACCESSIBILITY &&
                    row.stream == mPrevActiveStream) {
                return true;
            }

            if (row.defaultStream) {
                return activeRow.stream == STREAM_RING
                        || activeRow.stream == STREAM_ALARM
                        || activeRow.stream == STREAM_VOICE_CALL
                        || activeRow.stream == STREAM_ACCESSIBILITY
                        || mDynamic.get(activeRow.stream);
            }
        }

        return false;
    }

    private void updateRowsH(final VolumeRow activeRow) {
        Trace.beginSection("VolumeDialogImpl#updateRowsH");
        if (D.BUG) Log.d(TAG, "updateRowsH");
        if (!mShowing) {
            trimObsoleteH();
        }

        // Index of the last row that is actually visible.
        int rightmostVisibleRowIndex = !isRtl() ? -1 : Short.MAX_VALUE;

        // apply changes to all rows
        for (final VolumeRow row : mRows) {
            final boolean isActive = row == activeRow;
            final boolean shouldBeVisible = shouldBeVisibleH(row, activeRow);
            Util.setVisOrGone(row.view, shouldBeVisible);

            if (shouldBeVisible && mRingerAndDrawerContainerBackground != null) {
                // For RTL, the rightmost row has the lowest index since child views are laid out
                // from right to left.
                rightmostVisibleRowIndex =
                        !isRtl()
                                ? Math.max(rightmostVisibleRowIndex,
                                mDialogRowsView.indexOfChild(row.view))
                                : Math.min(rightmostVisibleRowIndex,
                                        mDialogRowsView.indexOfChild(row.view));

                // Add spacing between each of the visible rows - we'll remove the spacing from the
                // last row after the loop.
                final ViewGroup.LayoutParams layoutParams = row.view.getLayoutParams();
                if (layoutParams instanceof LinearLayout.LayoutParams) {
                    final LinearLayout.LayoutParams linearLayoutParams =
                            ((LinearLayout.LayoutParams) layoutParams);
                    if (!isRtl()) {
                        linearLayoutParams.setMarginEnd(mRingerRowsPadding);
                    } else {
                        linearLayoutParams.setMarginStart(mRingerRowsPadding);
                    }
                }

                // Set the background on each of the rows. We'll remove this from the last row after
                // the loop, since the last row's background is drawn by the main volume container.
                row.view.setBackgroundDrawable(
                        mContext.getDrawable(R.drawable.volume_row_rounded_background));
            }

            if (row.view.isShown()) {
                updateVolumeRowTintH(row, isActive);
            }
        }

        if (rightmostVisibleRowIndex > -1 && rightmostVisibleRowIndex < Short.MAX_VALUE) {
            final View lastVisibleChild = mDialogRowsView.getChildAt(rightmostVisibleRowIndex);
            final ViewGroup.LayoutParams layoutParams = lastVisibleChild.getLayoutParams();
            // Remove the spacing on the last row, and remove its background since the container is
            // drawing a background for this row.
            if (layoutParams instanceof LinearLayout.LayoutParams) {
                final LinearLayout.LayoutParams linearLayoutParams =
                        ((LinearLayout.LayoutParams) layoutParams);
                linearLayoutParams.setMarginStart(0);
                linearLayoutParams.setMarginEnd(0);
                lastVisibleChild.setBackgroundColor(Color.TRANSPARENT);
            }
        }

        updateBackgroundForDrawerClosedAmount();
        Trace.endSection();
    }

    protected void updateRingerH() {
        if (mRinger != null && mState != null) {
            final StreamState ss = mState.states.get(AudioManager.STREAM_RING);
            if (ss == null) {
                return;
            }

            boolean isZenMuted = mState.zenMode == Global.ZEN_MODE_ALARMS
                    || mState.zenMode == Global.ZEN_MODE_NO_INTERRUPTIONS
                    || (mState.zenMode == Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS
                        && mState.disallowRinger);
            enableRingerViewsH(!isZenMuted);
            switch (mState.ringerModeInternal) {
                case AudioManager.RINGER_MODE_VIBRATE:
                    mRingerIcon.setImageResource(R.drawable.ic_volume_ringer_vibrate);
                    mSelectedRingerIcon.setImageResource(R.drawable.ic_volume_ringer_vibrate);
                    addAccessibilityDescription(mRingerIcon, RINGER_MODE_VIBRATE,
                            mContext.getString(R.string.volume_ringer_hint_mute));
                    mRingerIcon.setTag(Events.ICON_STATE_VIBRATE);
                    break;
                case AudioManager.RINGER_MODE_SILENT:
                    mRingerIcon.setImageResource(mVolumeRingerMuteIconDrawableId);
                    mSelectedRingerIcon.setImageResource(mVolumeRingerMuteIconDrawableId);
                    mRingerIcon.setTag(Events.ICON_STATE_MUTE);
                    addAccessibilityDescription(mRingerIcon, RINGER_MODE_SILENT,
                            mContext.getString(R.string.volume_ringer_hint_unmute));
                    break;
                case AudioManager.RINGER_MODE_NORMAL:
                default:
                    boolean muted = (mAutomute && ss.level == 0) || ss.muted;
                    if (!isZenMuted && muted) {
                        mRingerIcon.setImageResource(mVolumeRingerMuteIconDrawableId);
                        mSelectedRingerIcon.setImageResource(mVolumeRingerMuteIconDrawableId);
                        addAccessibilityDescription(mRingerIcon, RINGER_MODE_NORMAL,
                                mContext.getString(R.string.volume_ringer_hint_unmute));
                        mRingerIcon.setTag(Events.ICON_STATE_MUTE);
                    } else {
                        mRingerIcon.setImageResource(mVolumeRingerIconDrawableId);
                        mSelectedRingerIcon.setImageResource(mVolumeRingerIconDrawableId);
                        if (mController.hasVibrator()) {
                            addAccessibilityDescription(mRingerIcon, RINGER_MODE_NORMAL,
                                    mContext.getString(R.string.volume_ringer_hint_vibrate));
                        } else {
                            addAccessibilityDescription(mRingerIcon, RINGER_MODE_NORMAL,
                                    mContext.getString(R.string.volume_ringer_hint_mute));
                        }
                        mRingerIcon.setTag(Events.ICON_STATE_UNMUTE);
                    }
                    break;
            }
        }
    }

    private void addAccessibilityDescription(View view, int currState, String hintLabel) {
        view.setContentDescription(
                mContext.getString(getStringDescriptionResourceForRingerMode(currState)));
        view.setAccessibilityDelegate(new AccessibilityDelegate() {
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                                AccessibilityNodeInfo.ACTION_CLICK, hintLabel));
            }
        });
    }

    private int getStringDescriptionResourceForRingerMode(int mode) {
        switch (mode) {
            case RINGER_MODE_SILENT:
                return R.string.volume_ringer_status_silent;
            case RINGER_MODE_VIBRATE:
                return R.string.volume_ringer_status_vibrate;
            case RINGER_MODE_NORMAL:
            default:
                return R.string.volume_ringer_status_normal;
        }
    }

    /**
     * Toggles enable state of views in a VolumeRow (not including seekbar or icon)
     * Hides/shows zen icon
     * @param enable whether to enable volume row views and hide dnd icon
     */
    private void enableVolumeRowViewsH(VolumeRow row, boolean enable) {
        boolean showDndIcon = !enable;
        row.dndIcon.setVisibility(showDndIcon ? VISIBLE : GONE);
    }

    /**
     * Toggles enable state of footer/ringer views
     * Hides/shows zen icon
     * @param enable whether to enable ringer views and hide dnd icon
     */
    private void enableRingerViewsH(boolean enable) {
        if (mRingerIcon != null) {
            mRingerIcon.setEnabled(enable);
        }
        if (mZenIcon != null) {
            mZenIcon.setVisibility(enable ? GONE : VISIBLE);
        }
    }

    private void trimObsoleteH() {
        if (D.BUG) Log.d(TAG, "trimObsoleteH");
        for (int i = mRows.size() - 1; i >= 0; i--) {
            final VolumeRow row = mRows.get(i);
            if (row.ss == null || !row.ss.dynamic) continue;
            if (!mDynamic.get(row.stream)) {
                mRows.remove(i);
                mDialogRowsView.removeView(row.view);
                mConfigurableTexts.remove(row.header);
            }
        }
    }

    protected void onStateChangedH(State state) {
        if (D.BUG) Log.d(TAG, "onStateChangedH() state: " + state.toString());
        if (mState != null && state != null
                && mState.ringerModeInternal != -1
                && mState.ringerModeInternal != state.ringerModeInternal
                && state.ringerModeInternal == AudioManager.RINGER_MODE_VIBRATE) {
            mController.vibrate(VibrationEffect.get(VibrationEffect.EFFECT_DOUBLE_CLICK));
        }
        mState = state;
        mDynamic.clear();
        // add any new dynamic rows
        for (int i = 0; i < state.states.size(); i++) {
            final int stream = state.states.keyAt(i);
            final StreamState ss = state.states.valueAt(i);
            if (!ss.dynamic) continue;
            mDynamic.put(stream, true);
            if (findRow(stream) == null) {
                addRow(stream, R.drawable.ic_volume_remote, R.drawable.ic_volume_remote_mute, true,
                        false, true);
            }
        }

        if (mActiveStream != state.activeStream) {
            mPrevActiveStream = mActiveStream;
            mActiveStream = state.activeStream;
            VolumeRow activeRow = getActiveRow();
            updateRowsH(activeRow);
            if (mShowing) rescheduleTimeoutH();
        }
        for (VolumeRow row : mRows) {
            updateVolumeRowH(row);
        }
        updateRingerH();
        mWindow.setTitle(composeWindowTitle());
    }

    CharSequence composeWindowTitle() {
        return mContext.getString(R.string.volume_dialog_title, getStreamLabelH(getActiveRow().ss));
    }

    private void updateVolumeRowH(VolumeRow row) {
        if (D.BUG) Log.i(TAG, "updateVolumeRowH s=" + row.stream);
        if (mState == null) return;
        final StreamState ss = mState.states.get(row.stream);
        if (ss == null) return;
        row.ss = ss;
        if (ss.level > 0) {
            row.lastAudibleLevel = ss.level;
        }
        if (ss.level == row.requestedLevel) {
            row.requestedLevel = -1;
        }
        final boolean isVoiceCallStream = row.stream == AudioManager.STREAM_VOICE_CALL;
        final boolean isA11yStream = row.stream == STREAM_ACCESSIBILITY;
        final boolean isRingStream = row.stream == AudioManager.STREAM_RING;
        final boolean isSystemStream = row.stream == AudioManager.STREAM_SYSTEM;
        final boolean isAlarmStream = row.stream == STREAM_ALARM;
        final boolean isMusicStream = row.stream == AudioManager.STREAM_MUSIC;
        final boolean isRingVibrate = isRingStream
                && mState.ringerModeInternal == AudioManager.RINGER_MODE_VIBRATE;
        final boolean isRingSilent = isRingStream
                && mState.ringerModeInternal == AudioManager.RINGER_MODE_SILENT;
        final boolean isZenPriorityOnly = mState.zenMode == Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        final boolean isZenAlarms = mState.zenMode == Global.ZEN_MODE_ALARMS;
        final boolean isZenNone = mState.zenMode == Global.ZEN_MODE_NO_INTERRUPTIONS;
        final boolean zenMuted = isZenAlarms ? (isRingStream || isSystemStream)
                : isZenNone ? (isRingStream || isSystemStream || isAlarmStream || isMusicStream)
                : isZenPriorityOnly ? ((isAlarmStream && mState.disallowAlarms) ||
                        (isMusicStream && mState.disallowMedia) ||
                        (isRingStream && mState.disallowRinger) ||
                        (isSystemStream && mState.disallowSystem))
                : false;

        // update slider max
        final int max = ss.levelMax * 100;
        if (max != row.slider.getMax()) {
            row.slider.setMax(max);
        }
        // update slider min
        final int min = ss.levelMin * 100;
        if (min != row.slider.getMin()) {
            row.slider.setMin(min);
        }

        // update header text
        Util.setText(row.header, getStreamLabelH(ss));
        row.slider.setContentDescription(row.header.getText());
        mConfigurableTexts.add(row.header, ss.name);

        // update icon
        final boolean iconEnabled = (mAutomute || ss.muteSupported) && !zenMuted;
        final int iconRes;
        if (isRingVibrate) {
            iconRes = R.drawable.ic_volume_ringer_vibrate;
        } else if (isRingSilent || zenMuted) {
            iconRes = row.iconMuteRes;
        } else if (ss.routedToBluetooth) {
            if (isVoiceCallStream) {
                iconRes = R.drawable.ic_volume_bt_sco;
            } else {
                iconRes = isStreamMuted(ss) ? R.drawable.ic_volume_media_bt_mute
                                            : R.drawable.ic_volume_media_bt;
            }
        } else if (isStreamMuted(ss)) {
            iconRes = ss.muted ? R.drawable.ic_volume_media_off : row.iconMuteRes;
        } else {
            iconRes = mShowLowMediaVolumeIcon && ss.level * 2 < (ss.levelMax + ss.levelMin)
                      ? R.drawable.ic_volume_media_low : row.iconRes;
        }

        row.setIcon(iconRes, mContext.getTheme());
        row.iconState =
                iconRes == R.drawable.ic_volume_ringer_vibrate ? Events.ICON_STATE_VIBRATE
                : (iconRes == R.drawable.ic_volume_media_bt_mute || iconRes == row.iconMuteRes)
                        ? Events.ICON_STATE_MUTE
                : (iconRes == R.drawable.ic_volume_media_bt || iconRes == row.iconRes
                        || iconRes == R.drawable.ic_volume_media_low)
                        ? Events.ICON_STATE_UNMUTE
                : Events.ICON_STATE_UNKNOWN;

        if (row.icon != null) {
            if (iconEnabled) {
                if (isRingStream) {
                    if (isRingVibrate) {
                        row.icon.setContentDescription(mContext.getString(
                                R.string.volume_stream_content_description_unmute,
                                getStreamLabelH(ss)));
                    } else {
                        if (mController.hasVibrator()) {
                            row.icon.setContentDescription(mContext.getString(
                                    mShowA11yStream
                                            ? R.string.volume_stream_content_description_vibrate_a11y
                                            : R.string.volume_stream_content_description_vibrate,
                                    getStreamLabelH(ss)));
                        } else {
                            row.icon.setContentDescription(mContext.getString(
                                    mShowA11yStream
                                            ? R.string.volume_stream_content_description_mute_a11y
                                            : R.string.volume_stream_content_description_mute,
                                    getStreamLabelH(ss)));
                        }
                    }
                } else if (isA11yStream) {
                    row.icon.setContentDescription(getStreamLabelH(ss));
                } else {
                    if (ss.muted || mAutomute && ss.level == 0) {
                        row.icon.setContentDescription(mContext.getString(
                                R.string.volume_stream_content_description_unmute,
                                getStreamLabelH(ss)));
                    } else {
                        row.icon.setContentDescription(mContext.getString(
                                mShowA11yStream
                                        ? R.string.volume_stream_content_description_mute_a11y
                                        : R.string.volume_stream_content_description_mute,
                                getStreamLabelH(ss)));
                    }
                }
            } else {
                row.icon.setContentDescription(getStreamLabelH(ss));
            }
        }

        // ensure tracking is disabled if zenMuted
        if (zenMuted) {
            row.tracking = false;
        }
        enableVolumeRowViewsH(row, !zenMuted);

        // update slider
        final boolean enableSlider = !zenMuted;
        final int vlevel = row.ss.muted && (!isRingStream && !zenMuted) ? 0
                : row.ss.level;
        Trace.beginSection("VolumeDialogImpl#updateVolumeRowSliderH");
        updateVolumeRowSliderH(row, enableSlider, vlevel);
        Trace.endSection();
        if (row.number != null) row.number.setText(Integer.toString(vlevel));
    }

    private boolean isStreamMuted(final StreamState streamState) {
        return (mAutomute && streamState.level == 0) || streamState.muted;
    }

    private void updateVolumeRowTintH(VolumeRow row, boolean isActive) {
        if (isActive) {
            row.slider.requestFocus();
        }
        boolean useActiveColoring = isActive && row.slider.isEnabled();
        if (!useActiveColoring && !mChangeVolumeRowTintWhenInactive) {
            return;
        }
        final ColorStateList colorTint = useActiveColoring
                ? Utils.getColorAccent(mContext)
                : Utils.getColorAttr(mContext, com.android.internal.R.attr.colorAccentSecondary);
        final int alpha = useActiveColoring
                ? Color.alpha(colorTint.getDefaultColor())
                : getAlphaAttr(android.R.attr.secondaryContentAlpha);

        final ColorStateList bgTint = Utils.getColorAttr(
                mContext, android.R.attr.colorBackgroundFloating);

        final ColorStateList inverseTextTint = Utils.getColorAttr(
                mContext, com.android.internal.R.attr.textColorOnAccent);

        row.sliderProgressSolid.setTintList(colorTint);
        if (row.sliderProgressIcon != null) {
            row.sliderProgressIcon.setTintList(bgTint);
        }

        if (row.icon != null) {
            row.icon.setImageTintList(inverseTextTint);
            row.icon.setImageAlpha(alpha);
        }

        if (row.number != null) {
            row.number.setTextColor(colorTint);
            row.number.setAlpha(alpha);
        }
    }

    private void updateVolumeRowSliderH(VolumeRow row, boolean enable, int vlevel) {
        row.slider.setEnabled(enable);
        updateVolumeRowTintH(row, row.stream == mActiveStream);
        if (row.tracking) {
            return;  // don't update if user is sliding
        }
        final int progress = row.slider.getProgress();
        final int level = getImpliedLevel(row.slider, progress);
        final boolean rowVisible = row.view.getVisibility() == VISIBLE;
        final boolean inGracePeriod = (SystemClock.uptimeMillis() - row.userAttempt)
                < USER_ATTEMPT_GRACE_PERIOD;
        mHandler.removeMessages(H.RECHECK, row);
        if (mShowing && rowVisible && inGracePeriod) {
            if (D.BUG) Log.d(TAG, "inGracePeriod");
            mHandler.sendMessageAtTime(mHandler.obtainMessage(H.RECHECK, row),
                    row.userAttempt + USER_ATTEMPT_GRACE_PERIOD);
            return;  // don't update if visible and in grace period
        }
        if (vlevel == level) {
            if (mShowing && rowVisible) {
                return;  // don't clamp if visible
            }
        }
        final int newProgress = vlevel * 100;
        if (progress != newProgress) {
            if (mShowing && rowVisible) {
                // animate!
                if (row.anim != null && row.anim.isRunning()
                        && row.animTargetProgress == newProgress) {
                    return;  // already animating to the target progress
                }
                // start/update animation
                if (row.anim == null) {
                    row.anim = ObjectAnimator.ofInt(row.slider, "progress", progress, newProgress);
                    row.anim.setInterpolator(new DecelerateInterpolator());
                } else {
                    row.anim.cancel();
                    row.anim.setIntValues(progress, newProgress);
                }
                row.animTargetProgress = newProgress;
                row.anim.setDuration(UPDATE_ANIMATION_DURATION);
                row.anim.addListener(
                        getJankListener(row.view, TYPE_UPDATE, UPDATE_ANIMATION_DURATION));
                row.anim.start();
            } else {
                // update slider directly to clamped value
                if (row.anim != null) {
                    row.anim.cancel();
                }
                row.slider.setProgress(newProgress, true);
            }
        }
    }

    private void recheckH(VolumeRow row) {
        if (row == null) {
            if (D.BUG) Log.d(TAG, "recheckH ALL");
            trimObsoleteH();
            for (VolumeRow r : mRows) {
                updateVolumeRowH(r);
            }
        } else {
            if (D.BUG) Log.d(TAG, "recheckH " + row.stream);
            updateVolumeRowH(row);
        }
    }

    private void setStreamImportantH(int stream, boolean important) {
        for (VolumeRow row : mRows) {
            if (row.stream == stream) {
                row.important = important;
                return;
            }
        }
    }

    private void showSafetyWarningH(int flags) {
        if ((flags & (AudioManager.FLAG_SHOW_UI | AudioManager.FLAG_SHOW_UI_WARNINGS)) != 0
                || mShowing) {
            synchronized (mSafetyWarningLock) {
                if (mSafetyWarning != null) {
                    return;
                }
                mSafetyWarning = new SafetyWarningDialog(mContext, mController.getAudioManager()) {
                    @Override
                    protected void cleanUp() {
                        synchronized (mSafetyWarningLock) {
                            mSafetyWarning = null;
                        }
                        recheckH(null);
                    }
                };
                mSafetyWarning.show();
            }
            recheckH(null);
        }
        rescheduleTimeoutH();
    }

    private String getStreamLabelH(StreamState ss) {
        if (ss == null) {
            return "";
        }
        if (ss.remoteLabel != null) {
            return ss.remoteLabel;
        }
        try {
            return mContext.getResources().getString(ss.name);
        } catch (Resources.NotFoundException e) {
            Slog.e(TAG, "Can't find translation for stream " + ss);
            return "";
        }
    }

    private Runnable getSinglePressFor(ImageButton button) {
        return () -> {
            if (button != null) {
                button.setPressed(true);
                button.postOnAnimationDelayed(getSingleUnpressFor(button), 200);
            }
        };
    }

    private Runnable getSingleUnpressFor(ImageButton button) {
        return () -> {
            if (button != null) {
                button.setPressed(false);
            }
        };
    }

    /**
     * Return the size of the 1-2 extra ringer options that are made visible when the ringer drawer
     * is opened. The drawer options are square so this can be used for height calculations (when in
     * portrait, and the drawer opens upward) or for width (when opening sideways in landscape).
     */
    private int getRingerDrawerOpenExtraSize() {
        return (mRingerCount - 1) * mRingerDrawerItemSize;
    }

    /**
     * Return the size of the additionally visible rows next to the default stream.
     * An additional row is visible for example while receiving a voice call.
     */
    private int getVisibleRowsExtraSize() {
        VolumeRow activeRow = getActiveRow();
        int visibleRows = 0;
        for (final VolumeRow row : mRows) {
            if (shouldBeVisibleH(row, activeRow)) {
                visibleRows++;
            }
        }
        return (visibleRows - 1) * (mDialogWidth + mRingerRowsPadding);
    }

    private void updateBackgroundForDrawerClosedAmount() {
        if (mRingerAndDrawerContainerBackground == null) {
            return;
        }

        final Rect bounds = mRingerAndDrawerContainerBackground.copyBounds();
        if (!isLandscape()) {
            bounds.top = (int) (mRingerDrawerClosedAmount * getRingerDrawerOpenExtraSize());
        } else {
            bounds.left = (int) (mRingerDrawerClosedAmount * getRingerDrawerOpenExtraSize());
        }
        mRingerAndDrawerContainerBackground.setBounds(bounds);
    }

    /*
     * The top container is responsible for drawing the solid color background behind the rightmost
     * (primary) volume row. This is because the volume drawer animates in from below, initially
     * overlapping the primary row. We need the drawer to draw below the row's SeekBar, since it
     * looks strange to overlap it, but above the row's background color, since otherwise it will be
     * clipped.
     *
     * Since we can't be both above and below the volume row view, we'll be below it, and render the
     * background color in the container since they're both above that.
     */
    private void setTopContainerBackgroundDrawable() {
        if (mTopContainer == null) {
            return;
        }

        final ColorDrawable solidDrawable = new ColorDrawable(
                Utils.getColorAttrDefaultColor(mContext, com.android.internal.R.attr.colorSurface));

        final LayerDrawable background = new LayerDrawable(new Drawable[] { solidDrawable });

        // Size the solid color to match the primary volume row. In landscape, extend it upwards
        // slightly so that it fills in the bottom corners of the ringer icon, whose background is
        // rounded on all sides so that it can expand to the left, outside the dialog's background.
        background.setLayerSize(0, mDialogWidth,
                !isLandscape()
                        ? mDialogRowsView.getHeight()
                        : mDialogRowsView.getHeight() + mDialogCornerRadius);
        // Inset the top so that the color only renders below the ringer drawer, which has its own
        // background. In landscape, reduce the inset slightly since we are using the background to
        // fill in the corners of the closed ringer drawer.
        background.setLayerInsetTop(0,
                !isLandscape()
                        ? mDialogRowsViewContainer.getTop()
                        : mDialogRowsViewContainer.getTop() - mDialogCornerRadius);

        // Set gravity to top-right, since additional rows will be added on the left.
        background.setLayerGravity(0, Gravity.TOP | Gravity.RIGHT);

        // In landscape, the ringer drawer animates out to the left (instead of down). Since the
        // drawer comes from the right (beyond the bounds of the dialog), we should clip it so it
        // doesn't draw outside the dialog background. This isn't an issue in portrait, since the
        // drawer animates downward, below the volume row.
        if (isLandscape()) {
            mRingerAndDrawerContainer.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(
                            0, 0, view.getWidth(), view.getHeight(), mDialogCornerRadius);
                }
            });
            mRingerAndDrawerContainer.setClipToOutline(true);
        }

        mTopContainer.setBackground(background);
    }

    private final VolumeDialogController.Callbacks mControllerCallbackH
            = new VolumeDialogController.Callbacks() {
        @Override
        public void onShowRequested(int reason, boolean keyguardLocked, int lockTaskModeState) {
            showH(reason, keyguardLocked, lockTaskModeState);
        }

        @Override
        public void onDismissRequested(int reason) {
            dismissH(reason);
        }

        @Override
        public void onScreenOff() {
            dismissH(Events.DISMISS_REASON_SCREEN_OFF);
        }

        @Override
        public void onStateChanged(State state) {
            onStateChangedH(state);
        }

        @Override
        public void onLayoutDirectionChanged(int layoutDirection) {
            mDialogView.setLayoutDirection(layoutDirection);
        }

        @Override
        public void onConfigurationChanged() {
            mDialog.dismiss();
            mConfigChanged = true;
        }

        @Override
        public void onShowVibrateHint() {
            if (mSilentMode) {
                mController.setRingerMode(AudioManager.RINGER_MODE_SILENT, false);
            }
        }

        @Override
        public void onShowSilentHint() {
            if (mSilentMode) {
                mController.setRingerMode(AudioManager.RINGER_MODE_NORMAL, false);
            }
        }

        @Override
        public void onShowSafetyWarning(int flags) {
            showSafetyWarningH(flags);
        }

        @Override
        public void onAccessibilityModeChanged(Boolean showA11yStream) {
            mShowA11yStream = showA11yStream == null ? false : showA11yStream;
            VolumeRow activeRow = getActiveRow();
            if (!mShowA11yStream && STREAM_ACCESSIBILITY == activeRow.stream) {
                dismissH(Events.DISMISS_STREAM_GONE);
            } else {
                updateRowsH(activeRow);
            }

        }

        @Override
        public void onCaptionComponentStateChanged(
                Boolean isComponentEnabled, Boolean fromTooltip) {
            updateODICaptionsH(isComponentEnabled, fromTooltip);
        }
    };

    private final class H extends Handler {
        private static final int SHOW = 1;
        private static final int DISMISS = 2;
        private static final int RECHECK = 3;
        private static final int RECHECK_ALL = 4;
        private static final int SET_STREAM_IMPORTANT = 5;
        private static final int RESCHEDULE_TIMEOUT = 6;
        private static final int STATE_CHANGED = 7;

        public H() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case SHOW: showH(msg.arg1, VolumeDialogImpl.this.mKeyguard.isKeyguardLocked(),
                        VolumeDialogImpl.this.mActivityManager.getLockTaskModeState()); break;
                case DISMISS: dismissH(msg.arg1); break;
                case RECHECK: recheckH((VolumeRow) msg.obj); break;
                case RECHECK_ALL: recheckH(null); break;
                case SET_STREAM_IMPORTANT: setStreamImportantH(msg.arg1, msg.arg2 != 0); break;
                case RESCHEDULE_TIMEOUT: rescheduleTimeoutH(); break;
                case STATE_CHANGED: onStateChangedH(mState); break;
            }
        }
    }

    private final class CustomDialog extends Dialog implements DialogInterface {
        public CustomDialog(Context context) {
            super(context, R.style.volume_dialog_theme);
        }

        /**
         * NOTE: This will only be called for touches within the touchable region of the volume
         * dialog, as returned by {@link #onComputeInternalInsets}. Other touches, even if they are
         * within the bounds of the volume dialog, will fall through to the window below.
         */
        @Override
        public boolean dispatchTouchEvent(@NonNull MotionEvent ev) {
            rescheduleTimeoutH();
            return super.dispatchTouchEvent(ev);
        }

        @Override
        protected void onStart() {
            super.setCanceledOnTouchOutside(true);
            super.onStart();
        }

        @Override
        protected void onStop() {
            super.onStop();
            mHandler.sendEmptyMessage(H.RECHECK_ALL);
        }

        /**
         * NOTE: This will be called with ACTION_OUTSIDE MotionEvents for touches that occur outside
         * of the touchable region of the volume dialog (as returned by
         * {@link #onComputeInternalInsets}) even if those touches occurred within the bounds of the
         * volume dialog.
         */
        @Override
        public boolean onTouchEvent(@NonNull MotionEvent event) {
            if (mShowing) {
                if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
                    dismissH(Events.DISMISS_REASON_TOUCH_OUTSIDE);
                    return true;
                }
            }
            return false;
        }
    }

    private final class VolumeSeekBarChangeListener implements OnSeekBarChangeListener {
        private final VolumeRow mRow;

        private VolumeSeekBarChangeListener(VolumeRow row) {
            mRow = row;
        }

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (mRow.ss == null) return;
            if (D.BUG) Log.d(TAG, AudioSystem.streamToString(mRow.stream)
                    + " onProgressChanged " + progress + " fromUser=" + fromUser);
            if (!fromUser) return;
            if (mRow.ss.levelMin > 0) {
                final int minProgress = mRow.ss.levelMin * 100;
                if (progress < minProgress) {
                    seekBar.setProgress(minProgress);
                    progress = minProgress;
                }
            }
            final int userLevel = getImpliedLevel(seekBar, progress);
            if (mRow.ss.level != userLevel || mRow.ss.muted && userLevel > 0) {
                mRow.userAttempt = SystemClock.uptimeMillis();
                if (mRow.requestedLevel != userLevel) {
                    mController.setActiveStream(mRow.stream);
                    mController.setStreamVolume(mRow.stream, userLevel);
                    mRow.requestedLevel = userLevel;
                    Events.writeEvent(Events.EVENT_TOUCH_LEVEL_CHANGED, mRow.stream,
                            userLevel);
                }
            }
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
            if (D.BUG) Log.d(TAG, "onStartTrackingTouch"+ " " + mRow.stream);
            mController.setActiveStream(mRow.stream);
            mRow.tracking = true;
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            if (D.BUG) Log.d(TAG, "onStopTrackingTouch"+ " " + mRow.stream);
            mRow.tracking = false;
            mRow.userAttempt = SystemClock.uptimeMillis();
            final int userLevel = getImpliedLevel(seekBar, seekBar.getProgress());
            Events.writeEvent(Events.EVENT_TOUCH_LEVEL_DONE, mRow.stream, userLevel);
            if (mRow.ss.level != userLevel) {
                mHandler.sendMessageDelayed(mHandler.obtainMessage(H.RECHECK, mRow),
                        USER_ATTEMPT_GRACE_PERIOD);
            }
        }
    }

    private final class Accessibility extends AccessibilityDelegate {
        public void init() {
            mDialogView.setAccessibilityDelegate(this);
        }

        @Override
        public boolean dispatchPopulateAccessibilityEvent(View host, AccessibilityEvent event) {
            // Activities populate their title here. Follow that example.
            event.getText().add(composeWindowTitle());
            return true;
        }

        @Override
        public boolean onRequestSendAccessibilityEvent(ViewGroup host, View child,
                AccessibilityEvent event) {
            rescheduleTimeoutH();
            return super.onRequestSendAccessibilityEvent(host, child, event);
        }
    }

    private static class VolumeRow {
        private View view;
        private TextView header;
        private ImageButton icon;
        private Drawable sliderProgressSolid;
        private AlphaTintDrawableWrapper sliderProgressIcon;
        private SeekBar slider;
        private TextView number;
        private int stream;
        private StreamState ss;
        private long userAttempt;  // last user-driven slider change
        private boolean tracking;  // tracking slider touch
        private int requestedLevel = -1;  // pending user-requested level via progress changed
        private int iconRes;
        private int iconMuteRes;
        private boolean important;
        private boolean defaultStream;
        private int iconState;  // from Events
        private ObjectAnimator anim;  // slider progress animation for non-touch-related updates
        private int animTargetProgress;
        private int lastAudibleLevel = 1;
        private FrameLayout dndIcon;

        void setIcon(int iconRes, Resources.Theme theme) {
            if (icon != null) {
                icon.setImageResource(iconRes);
            }

            if (sliderProgressIcon != null) {
                sliderProgressIcon.setDrawable(view.getResources().getDrawable(iconRes, theme));
            }
        }
    }

    /**
     * Click listener added to each ringer option in the drawer. This will initiate the animation to
     * select and then close the ringer drawer, and actually change the ringer mode.
     */
    private class RingerDrawerItemClickListener implements View.OnClickListener {
        private final int mClickedRingerMode;

        RingerDrawerItemClickListener(int clickedRingerMode) {
            mClickedRingerMode = clickedRingerMode;
        }

        @Override
        public void onClick(View view) {
            // If the ringer drawer isn't open, don't let anything in it be clicked.
            if (!mIsRingerDrawerOpen) {
                return;
            }

            setRingerMode(mClickedRingerMode);

            mRingerDrawerIconAnimatingSelected = getDrawerIconViewForMode(mClickedRingerMode);
            mRingerDrawerIconAnimatingDeselected = getDrawerIconViewForMode(
                    mState.ringerModeInternal);

            // Begin switching the selected icon and deselected icon colors since the background is
            // going to animate behind the new selection.
            mRingerDrawerIconColorAnimator.start();

            mSelectedRingerContainer.setVisibility(View.INVISIBLE);
            mRingerDrawerNewSelectionBg.setAlpha(1f);
            mRingerDrawerNewSelectionBg.animate()
                    .setInterpolator(Interpolators.ACCELERATE_DECELERATE)
                    .setDuration(DRAWER_ANIMATION_DURATION_SHORT)
                    .withEndAction(() -> {
                        mRingerDrawerNewSelectionBg.setAlpha(0f);

                        if (!isLandscape()) {
                            mSelectedRingerContainer.setTranslationY(
                                    getTranslationInDrawerForRingerMode(mClickedRingerMode));
                        } else {
                            mSelectedRingerContainer.setTranslationX(
                                    getTranslationInDrawerForRingerMode(mClickedRingerMode));
                        }

                        mSelectedRingerContainer.setVisibility(VISIBLE);
                        hideRingerDrawer();
                    });

            if (!isLandscape()) {
                mRingerDrawerNewSelectionBg.animate()
                        .translationY(getTranslationInDrawerForRingerMode(mClickedRingerMode))
                        .start();
            } else {
                mRingerDrawerNewSelectionBg.animate()
                        .translationX(getTranslationInDrawerForRingerMode(mClickedRingerMode))
                        .start();
            }
        }
    }
}
