/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.privacy.television;

import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UiThread;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.transition.AutoTransition;
import android.transition.ChangeBounds;
import android.transition.Fade;
import android.transition.Transition;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.util.ArraySet;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.IWindowManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.android.systemui.CoreStartable;
import com.android.systemui.R;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.privacy.PrivacyItem;
import com.android.systemui.privacy.PrivacyItemController;
import com.android.systemui.privacy.PrivacyType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

/**
 * A SystemUI component responsible for notifying the user whenever an application is
 * recording audio, camera, the screen, or accessing the location.
 */
@SysUISingleton
public class TvPrivacyChipsController 
        implements CoreStartable, PrivacyItemController.Callback {
    private static final String TAG = "TvPrivacyChipsController";
    private static final boolean DEBUG = false;

    // This title is used in CameraMicIndicatorsPermissionTest and
    // RecognitionServiceMicIndicatorTest.
    private static final String LAYOUT_PARAMS_TITLE = "MicrophoneCaptureIndicator";

    // Chips configuration. We're not showing a location indicator on TV.
    static final List<PrivacyItemsChip.ChipConfig> CHIPS = Arrays.asList(
            new PrivacyItemsChip.ChipConfig(
                    Collections.singletonList(PrivacyType.TYPE_MEDIA_PROJECTION),
                    R.color.privacy_media_projection_chip,
                    /* collapseToDot= */ false),
            new PrivacyItemsChip.ChipConfig(
                    Arrays.asList(PrivacyType.TYPE_CAMERA, PrivacyType.TYPE_MICROPHONE),
                    R.color.privacy_mic_cam_chip,
                    /* collapseToDot= */ true)
    );

    // Avoid multiple messages after rapid changes such as starting/stopping both camera and mic.
    private static final int ACCESSIBILITY_ANNOUNCEMENT_DELAY_MS = 500;

    /**
     * Time to collect privacy item updates before applying them.
     * Since MediaProjection and AppOps come from different data sources,
     * PrivacyItem updates when screen & audio recording ends do not come at the same time.
     * Without this, if eg. MediaProjection ends first, you'd see the microphone chip expand and
     * almost immediately fade out as it is expanding. With this, the two chips disappear together.
     */
    private static final int PRIVACY_ITEM_DEBOUNCE_TIMEOUT_MS = 200;

    // How long chips stay expanded after an update.
    private static final int EXPANDED_DURATION_MS = 4000;

    private final Context mContext;
    private final Handler mUiThreadHandler = new Handler(Looper.getMainLooper());
    private final Runnable mCollapseRunnable = this::collapseChips;
    private final Runnable mUpdatePrivacyItemsRunnable = this::updateChipsAndAnnounce;
    private final Runnable mAccessibilityRunnable = this::makeAccessibilityAnnouncement;

    private final PrivacyItemController mPrivacyItemController;
    private final IWindowManager mIWindowManager;
    private final Rect[] mBounds = new Rect[4];
    private final TransitionSet mTransition;
    private final TransitionSet mCollapseTransition;
    private boolean mIsRtl;

    @Nullable
    private ViewGroup mChipsContainer;
    @Nullable
    private List<PrivacyItemsChip> mChips;
    @NonNull
    private List<PrivacyItem> mPrivacyItems = Collections.emptyList();
    @NonNull
    private final List<PrivacyItem> mItemsBeforeLastAnnouncement = new ArrayList<>();

    @Inject
    public TvPrivacyChipsController(Context context, PrivacyItemController privacyItemController,
            IWindowManager iWindowManager) {
        mContext = context;
        if (DEBUG) Log.d(TAG, "TvPrivacyChipsController running");
        mPrivacyItemController = privacyItemController;
        mIWindowManager = iWindowManager;

        Resources res = mContext.getResources();
        mIsRtl = res.getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        updateStaticPrivacyIndicatorBounds();

        Interpolator collapseInterpolator = AnimationUtils.loadInterpolator(context,
                R.interpolator.tv_privacy_chip_collapse_interpolator);
        Interpolator expandInterpolator = AnimationUtils.loadInterpolator(context,
                R.interpolator.tv_privacy_chip_expand_interpolator);

        TransitionSet chipFadeTransition = new TransitionSet()
                .addTransition(new Fade(Fade.IN))
                .addTransition(new Fade(Fade.OUT));
        chipFadeTransition.setOrdering(TransitionSet.ORDERING_TOGETHER);
        chipFadeTransition.excludeTarget(ImageView.class, true);

        Transition chipBoundsExpandTransition = new ChangeBounds();
        chipBoundsExpandTransition.excludeTarget(ImageView.class, true);
        chipBoundsExpandTransition.setInterpolator(expandInterpolator);

        Transition chipBoundsCollapseTransition = new ChangeBounds();
        chipBoundsCollapseTransition.excludeTarget(ImageView.class, true);
        chipBoundsCollapseTransition.setInterpolator(collapseInterpolator);

        TransitionSet iconCollapseTransition = new AutoTransition();
        iconCollapseTransition.setOrdering(TransitionSet.ORDERING_TOGETHER);
        iconCollapseTransition.addTarget(ImageView.class);
        iconCollapseTransition.setInterpolator(collapseInterpolator);

        TransitionSet iconExpandTransition = new AutoTransition();
        iconExpandTransition.setOrdering(TransitionSet.ORDERING_TOGETHER);
        iconExpandTransition.addTarget(ImageView.class);
        iconExpandTransition.setInterpolator(expandInterpolator);

        mTransition = new TransitionSet()
                .addTransition(chipFadeTransition)
                .addTransition(chipBoundsExpandTransition)
                .addTransition(iconExpandTransition)
                .setOrdering(TransitionSet.ORDERING_TOGETHER)
                .setDuration(res.getInteger(R.integer.privacy_chip_animation_millis));

        mCollapseTransition = new TransitionSet()
                .addTransition(chipFadeTransition)
                .addTransition(chipBoundsCollapseTransition)
                .addTransition(iconCollapseTransition)
                .setOrdering(TransitionSet.ORDERING_TOGETHER)
                .setDuration(res.getInteger(R.integer.privacy_chip_animation_millis));

        Transition.TransitionListener transitionListener = new Transition.TransitionListener() {
            @Override
            public void onTransitionStart(Transition transition) {
                if (DEBUG) Log.v(TAG, "onTransitionStart");
            }

            @Override
            public void onTransitionEnd(Transition transition) {
                if (DEBUG) Log.v(TAG, "onTransitionEnd");
                if (mChips != null) {
                    boolean hasVisibleChip = false;
                    boolean hasExpandedChip = false;
                    for (PrivacyItemsChip chip : mChips) {
                        hasVisibleChip = hasVisibleChip || chip.getVisibility() == View.VISIBLE;
                        hasExpandedChip = hasExpandedChip || chip.isExpanded();
                    }

                    if (!hasVisibleChip) {
                        if (DEBUG) Log.d(TAG, "No chips visible anymore");
                        removeIndicatorView();
                    } else if (hasExpandedChip) {
                        if (DEBUG) Log.d(TAG, "Has expanded chips");
                        collapseLater();
                    }
                }
            }

            @Override
            public void onTransitionCancel(Transition transition) {
            }

            @Override
            public void onTransitionPause(Transition transition) {
            }

            @Override
            public void onTransitionResume(Transition transition) {
            }
        };

        mTransition.addListener(transitionListener);
        mCollapseTransition.addListener(transitionListener);
    }

    @Override
    public void onConfigurationChanged(Configuration config) {
        boolean updatedRtl = config.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        if (mIsRtl == updatedRtl) {
            return;
        }
        mIsRtl = updatedRtl;

        // Update privacy chip location.
        if (mChipsContainer != null) {
            removeIndicatorView();
            createAndShowIndicator();
        }
        updateStaticPrivacyIndicatorBounds();
    }

    @Override
    public void start() {
        mPrivacyItemController.addCallback(this);
    }

    @UiThread
    @Override
    public void onPrivacyItemsChanged(List<PrivacyItem> privacyItems) {
        if (DEBUG) Log.d(TAG, "onPrivacyItemsChanged");

        List<PrivacyItem> filteredPrivacyItems = new ArrayList<>(privacyItems);
        if (filteredPrivacyItems.removeIf(
                privacyItem -> !isPrivacyTypeShown(privacyItem.getPrivacyType()))) {
            if (DEBUG) Log.v(TAG, "Removed privacy items we don't show");
        }

        // Do they have the same elements? (order doesn't matter)
        if (privacyItems.size() == mPrivacyItems.size() && mPrivacyItems.containsAll(
                privacyItems)) {
            if (DEBUG) Log.d(TAG, "No change to relevant privacy items");
            return;
        }

        mPrivacyItems = privacyItems;

        if (!mUiThreadHandler.hasCallbacks(mUpdatePrivacyItemsRunnable)) {
            mUiThreadHandler.postDelayed(mUpdatePrivacyItemsRunnable,
                    PRIVACY_ITEM_DEBOUNCE_TIMEOUT_MS);
        }
    }

    private boolean isPrivacyTypeShown(@NonNull PrivacyType type) {
        for (PrivacyItemsChip.ChipConfig chip : CHIPS) {
            if (chip.privacyTypes.contains(type)) {
                return true;
            }
        }
        return false;
    }

    @UiThread
    private void updateChipsAndAnnounce() {
        updateChips();
        postAccessibilityAnnouncement();
    }

    private void updateStaticPrivacyIndicatorBounds() {
        Resources res = mContext.getResources();
        int mMaxExpandedWidth = res.getDimensionPixelSize(R.dimen.privacy_chips_max_width);
        int mMaxExpandedHeight = res.getDimensionPixelSize(R.dimen.privacy_chip_height);
        int mChipMarginTotal = 2 * res.getDimensionPixelSize(R.dimen.privacy_chip_margin);

        final WindowManager windowManager = mContext.getSystemService(WindowManager.class);
        Rect screenBounds = windowManager.getCurrentWindowMetrics().getBounds();
        mBounds[0] = new Rect(
                mIsRtl ? screenBounds.left
                        : screenBounds.right - mMaxExpandedWidth,
                screenBounds.top,
                mIsRtl ? screenBounds.left + mMaxExpandedWidth
                        : screenBounds.right,
                screenBounds.top + mChipMarginTotal + mMaxExpandedHeight
        );

        if (DEBUG) Log.v(TAG, "privacy indicator bounds: " + mBounds[0].toShortString());

        try {
            mIWindowManager.updateStaticPrivacyIndicatorBounds(mContext.getDisplayId(), mBounds);
        } catch (RemoteException e) {
            Log.w(TAG, "could not update privacy indicator bounds");
        }
    }

    @UiThread
    private void updateChips() {
        if (DEBUG) Log.d(TAG, "updateChips: " + mPrivacyItems.size() + " privacy items");

        if (mChipsContainer == null) {
            if (!mPrivacyItems.isEmpty()) {
                createAndShowIndicator();
            }
            return;
        }

        Set<PrivacyType> activePrivacyTypes = new ArraySet<>();
        mPrivacyItems.forEach(item -> activePrivacyTypes.add(item.getPrivacyType()));

        TransitionManager.beginDelayedTransition(mChipsContainer, mTransition);
        mChips.forEach(chip -> chip.expandForTypes(activePrivacyTypes));
    }

    /**
     * Collapse the chip {@link #EXPANDED_DURATION_MS} from now.
     */
    private void collapseLater() {
        mUiThreadHandler.removeCallbacks(mCollapseRunnable);
        if (DEBUG) Log.d(TAG, "Chips will collapse in " + EXPANDED_DURATION_MS + "ms");
        mUiThreadHandler.postDelayed(mCollapseRunnable, EXPANDED_DURATION_MS);
    }

    private void collapseChips() {
        if (DEBUG) Log.d(TAG, "collapseChips");
        if (mChipsContainer == null) {
            return;
        }

        TransitionManager.beginDelayedTransition(mChipsContainer, mCollapseTransition);
        for (PrivacyItemsChip chip : mChips) {
            chip.collapse();
        }
    }

    @UiThread
    private void createAndShowIndicator() {
        if (DEBUG) Log.i(TAG, "Creating privacy indicators");

        Context privacyChipContext = new ContextThemeWrapper(mContext, R.style.PrivacyChip);
        mChips = new ArrayList<>();
        mChipsContainer = (ViewGroup) LayoutInflater.from(privacyChipContext)
                .inflate(R.layout.tv_privacy_chip_container, null);

        int chipMargins = privacyChipContext.getResources()
                .getDimensionPixelSize(R.dimen.privacy_chip_margin);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        lp.setMarginStart(chipMargins);
        lp.setMarginEnd(chipMargins);

        for (PrivacyItemsChip.ChipConfig chipConfig : CHIPS) {
            PrivacyItemsChip chip = new PrivacyItemsChip(privacyChipContext, chipConfig);
            mChipsContainer.addView(chip, lp);
            mChips.add(chip);
        }

        final WindowManager windowManager = mContext.getSystemService(WindowManager.class);
        windowManager.addView(mChipsContainer, getWindowLayoutParams());

        final ViewGroup container = mChipsContainer;
        mChipsContainer.getViewTreeObserver()
                .addOnGlobalLayoutListener(
                        new ViewTreeObserver.OnGlobalLayoutListener() {
                            @Override
                            public void onGlobalLayout() {
                                if (DEBUG) Log.v(TAG, "Chips container laid out");
                                container.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                                updateChips();
                            }
                        });
    }

    private WindowManager.LayoutParams getWindowLayoutParams() {
        final WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams(
                WRAP_CONTENT,
                WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        layoutParams.gravity = Gravity.TOP | (mIsRtl ? Gravity.LEFT : Gravity.RIGHT);
        layoutParams.setTitle(LAYOUT_PARAMS_TITLE);
        layoutParams.packageName = mContext.getPackageName();
        return layoutParams;
    }

    @UiThread
    private void removeIndicatorView() {
        if (DEBUG) Log.d(TAG, "removeIndicatorView");
        mUiThreadHandler.removeCallbacks(mCollapseRunnable);

        final WindowManager windowManager = mContext.getSystemService(WindowManager.class);
        if (windowManager != null && mChipsContainer != null) {
            windowManager.removeView(mChipsContainer);
        }

        mChipsContainer = null;
        mChips = null;
    }

    /**
     * Schedules the accessibility announcement to be made after {@link
     * #ACCESSIBILITY_ANNOUNCEMENT_DELAY_MS} (if possible). This is so that only one announcement is
     * made instead of two separate ones if both the camera and the mic are started/stopped.
     */
    @UiThread
    private void postAccessibilityAnnouncement() {
        mUiThreadHandler.removeCallbacks(mAccessibilityRunnable);

        if (mPrivacyItems.size() == 0) {
            // Announce immediately since announcement cannot be made once the chip is gone.
            makeAccessibilityAnnouncement();
        } else {
            mUiThreadHandler.postDelayed(mAccessibilityRunnable,
                    ACCESSIBILITY_ANNOUNCEMENT_DELAY_MS);
        }
    }

    private void makeAccessibilityAnnouncement() {
        if (mChipsContainer == null) {
            return;
        }

        boolean cameraWasRecording = listContainsPrivacyType(mItemsBeforeLastAnnouncement,
                PrivacyType.TYPE_CAMERA);
        boolean cameraIsRecording = listContainsPrivacyType(mPrivacyItems,
                PrivacyType.TYPE_CAMERA);
        boolean micWasRecording = listContainsPrivacyType(mItemsBeforeLastAnnouncement,
                PrivacyType.TYPE_MICROPHONE);
        boolean micIsRecording = listContainsPrivacyType(mPrivacyItems,
                PrivacyType.TYPE_MICROPHONE);

        boolean screenWasRecording = listContainsPrivacyType(mItemsBeforeLastAnnouncement,
                PrivacyType.TYPE_MEDIA_PROJECTION);
        boolean screenIsRecording = listContainsPrivacyType(mPrivacyItems,
                PrivacyType.TYPE_MEDIA_PROJECTION);

        int announcement = 0;
        if (!cameraWasRecording && cameraIsRecording && !micWasRecording && micIsRecording) {
            // Both started
            announcement = R.string.mic_and_camera_recording_announcement;
        } else if (cameraWasRecording && !cameraIsRecording && micWasRecording && !micIsRecording) {
            // Both stopped
            announcement = R.string.mic_camera_stopped_recording_announcement;
        } else {
            // Did the camera start or stop?
            if (cameraWasRecording && !cameraIsRecording) {
                announcement = R.string.camera_stopped_recording_announcement;
            } else if (!cameraWasRecording && cameraIsRecording) {
                announcement = R.string.camera_recording_announcement;
            }

            // Announce camera changes now since we might need a second announcement about the mic.
            if (announcement != 0) {
                mChipsContainer.announceForAccessibility(mContext.getString(announcement));
                announcement = 0;
            }

            // Did the mic start or stop?
            if (micWasRecording && !micIsRecording) {
                announcement = R.string.mic_stopped_recording_announcement;
            } else if (!micWasRecording && micIsRecording) {
                announcement = R.string.mic_recording_announcement;
            }
        }

        if (announcement != 0) {
            mChipsContainer.announceForAccessibility(mContext.getString(announcement));
        }

        if (!screenWasRecording && screenIsRecording) {
            mChipsContainer.announceForAccessibility(
                    mContext.getString(R.string.screen_recording_announcement));
        } else if (screenWasRecording && !screenIsRecording) {
            mChipsContainer.announceForAccessibility(
                    mContext.getString(R.string.screen_stopped_recording_announcement));
        }

        mItemsBeforeLastAnnouncement.clear();
        mItemsBeforeLastAnnouncement.addAll(mPrivacyItems);
    }

    private boolean listContainsPrivacyType(List<PrivacyItem> list, PrivacyType privacyType) {
        for (PrivacyItem item : list) {
            if (item.getPrivacyType() == privacyType) {
                return true;
            }
        }
        return false;
    }
}
