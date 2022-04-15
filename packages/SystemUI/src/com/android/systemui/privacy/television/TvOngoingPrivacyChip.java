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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.annotation.IntDef;
import android.annotation.UiThread;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.Gravity;
import android.view.IWindowManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import com.android.systemui.CoreStartable;
import com.android.systemui.R;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.privacy.PrivacyChipBuilder;
import com.android.systemui.privacy.PrivacyItem;
import com.android.systemui.privacy.PrivacyItemController;
import com.android.systemui.privacy.PrivacyType;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import javax.inject.Inject;

/**
 * A SystemUI component responsible for notifying the user whenever an application is
 * recording audio, accessing the camera or accessing the location.
 */
@SysUISingleton
public class TvOngoingPrivacyChip implements CoreStartable, PrivacyItemController.Callback,
        PrivacyChipDrawable.PrivacyChipDrawableListener {
    private static final String TAG = "TvOngoingPrivacyChip";
    private static final boolean DEBUG = false;

    // This title is used in CameraMicIndicatorsPermissionTest and
    // RecognitionServiceMicIndicatorTest.
    private static final String LAYOUT_PARAMS_TITLE = "MicrophoneCaptureIndicator";

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"STATE_"}, value = {
            STATE_NOT_SHOWN,
            STATE_APPEARING,
            STATE_EXPANDED,
            STATE_COLLAPSED,
            STATE_DISAPPEARING
    })
    public @interface State {
    }

    private static final int STATE_NOT_SHOWN = 0;
    private static final int STATE_APPEARING = 1;
    private static final int STATE_EXPANDED = 2;
    private static final int STATE_COLLAPSED = 3;
    private static final int STATE_DISAPPEARING = 4;

    // Avoid multiple messages after rapid changes such as starting/stopping both camera and mic.
    private static final int ACCESSIBILITY_ANNOUNCEMENT_DELAY_MS = 500;

    private static final int EXPANDED_DURATION_MS = 4000;
    public final int mAnimationDurationMs;

    private final Context mContext;
    private final PrivacyItemController mPrivacyItemController;

    private final IWindowManager mIWindowManager;
    private final Rect[] mBounds = new Rect[4];
    private boolean mIsRtl;

    private ViewGroup mIndicatorView;
    private boolean mViewAndWindowAdded;
    private ObjectAnimator mAnimator;

    private boolean mMicCameraIndicatorFlagEnabled;
    private boolean mAllIndicatorsEnabled;

    @NonNull
    private List<PrivacyItem> mPrivacyItems = Collections.emptyList();

    private LinearLayout mIconsContainer;
    private final int mIconSize;
    private final int mIconMarginStart;

    private PrivacyChipDrawable mChipDrawable;

    private final Handler mUiThreadHandler = new Handler(Looper.getMainLooper());
    private final Runnable mCollapseRunnable = this::collapseChip;

    private final Runnable mAccessibilityRunnable = this::makeAccessibilityAnnouncement;
    private final List<PrivacyItem> mItemsBeforeLastAnnouncement = new LinkedList<>();

    @State
    private int mState = STATE_NOT_SHOWN;

    @Inject
    public TvOngoingPrivacyChip(Context context, PrivacyItemController privacyItemController,
            IWindowManager iWindowManager) {
        if (DEBUG) Log.d(TAG, "Privacy chip running");
        mContext = context;
        mPrivacyItemController = privacyItemController;
        mIWindowManager = iWindowManager;

        Resources res = mContext.getResources();
        mIconMarginStart = Math.round(
                res.getDimension(R.dimen.privacy_chip_icon_margin_in_between));
        mIconSize = res.getDimensionPixelSize(R.dimen.privacy_chip_icon_size);

        mIsRtl = mContext.getResources().getConfiguration().getLayoutDirection()
                == View.LAYOUT_DIRECTION_RTL;
        updateStaticPrivacyIndicatorBounds();

        mAnimationDurationMs = res.getInteger(R.integer.privacy_chip_animation_millis);

        mMicCameraIndicatorFlagEnabled = privacyItemController.getMicCameraAvailable();
        mAllIndicatorsEnabled = privacyItemController.getAllIndicatorsAvailable();

        if (DEBUG) {
            Log.d(TAG, "micCameraIndicators: " + mMicCameraIndicatorFlagEnabled);
            Log.d(TAG, "allIndicators: " + mAllIndicatorsEnabled);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration config) {
        boolean updatedRtl = config.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        if (mIsRtl == updatedRtl) {
            return;
        }
        mIsRtl = updatedRtl;

        updateStaticPrivacyIndicatorBounds();

        // Update privacy chip location.
        if (mState == STATE_NOT_SHOWN || mIndicatorView == null) {
            return;
        }
        fadeOutIndicator();
        createAndShowIndicator();
    }

    @Override
    public void start() {
        mPrivacyItemController.addCallback(this);
    }

    @Override
    public void onPrivacyItemsChanged(@NonNull List<PrivacyItem> privacyItems) {
        if (DEBUG) Log.d(TAG, "PrivacyItemsChanged");

        List<PrivacyItem> updatedPrivacyItems = new ArrayList<>(privacyItems);
        // Never show the location indicator on tv.
        if (updatedPrivacyItems.removeIf(
                privacyItem -> privacyItem.getPrivacyType() == PrivacyType.TYPE_LOCATION)) {
            if (DEBUG) Log.v(TAG, "Removed the location item");
        }

        if (isChipDisabled()) {
            fadeOutIndicator();
            mPrivacyItems = updatedPrivacyItems;
            return;
        }

        // Do they have the same elements? (order doesn't matter)
        if (updatedPrivacyItems.size() == mPrivacyItems.size()
                && mPrivacyItems.containsAll(updatedPrivacyItems)) {
            if (DEBUG) Log.d(TAG, "List wasn't updated");
            return;
        }

        mPrivacyItems = updatedPrivacyItems;

        postAccessibilityAnnouncement();
        updateChip();
    }

    private void updateStaticPrivacyIndicatorBounds() {
        Resources res = mContext.getResources();
        int mMaxExpandedWidth = res.getDimensionPixelSize(R.dimen.privacy_chip_max_width);
        int mMaxExpandedHeight = res.getDimensionPixelSize(R.dimen.privacy_chip_height);
        int mChipMarginTotal = 2 * res.getDimensionPixelSize(R.dimen.privacy_chip_margin);

        final WindowManager windowManager = mContext.getSystemService(WindowManager.class);
        Rect screenBounds = windowManager.getCurrentWindowMetrics().getBounds();
        mBounds[0] = new Rect(
                mIsRtl ? screenBounds.left
                        : screenBounds.right - mChipMarginTotal - mMaxExpandedWidth,
                screenBounds.top,
                mIsRtl ? screenBounds.left + mChipMarginTotal + mMaxExpandedWidth
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

    private void updateChip() {
        if (DEBUG) Log.d(TAG, mPrivacyItems.size() + " privacy items");

        if (mPrivacyItems.isEmpty()) {
            if (DEBUG) Log.d(TAG, "removing indicator (state: " + stateToString(mState) + ")");
            fadeOutIndicator();
            return;
        }

        if (DEBUG) Log.d(TAG, "Current state: " + stateToString(mState));
        switch (mState) {
            case STATE_NOT_SHOWN:
                createAndShowIndicator();
                break;
            case STATE_APPEARING:
            case STATE_EXPANDED:
                updateIcons();
                collapseLater();
                break;
            case STATE_COLLAPSED:
            case STATE_DISAPPEARING:
                mState = STATE_EXPANDED;
                updateIcons();
                animateIconAppearance();
                break;
        }
    }

    /**
     * Collapse the chip EXPANDED_DURATION_MS from now.
     */
    private void collapseLater() {
        mUiThreadHandler.removeCallbacks(mCollapseRunnable);
        if (DEBUG) Log.d(TAG, "chip will collapse in " + EXPANDED_DURATION_MS + "ms");
        mUiThreadHandler.postDelayed(mCollapseRunnable, EXPANDED_DURATION_MS);
    }

    private void collapseChip() {
        if (DEBUG) Log.d(TAG, "collapseChip");

        if (mState != STATE_EXPANDED) {
            return;
        }
        mState = STATE_COLLAPSED;

        if (mChipDrawable != null) {
            mChipDrawable.collapse();
        }
        animateIconDisappearance();
    }

    @Override
    public void onFlagMicCameraChanged(boolean flag) {
        if (DEBUG) Log.d(TAG, "mic/camera indicators enabled: " + flag);
        mMicCameraIndicatorFlagEnabled = flag;
        updateChipOnFlagChanged();
    }

    @Override
    public void onFlagAllChanged(boolean flag) {
        if (DEBUG) Log.d(TAG, "all indicators enabled: " + flag);
        mAllIndicatorsEnabled = flag;
        updateChipOnFlagChanged();
    }

    private boolean isChipDisabled() {
        return !(mMicCameraIndicatorFlagEnabled || mAllIndicatorsEnabled);
    }

    private void updateChipOnFlagChanged() {
        if (isChipDisabled()) {
            fadeOutIndicator();
        } else {
            updateChip();
        }
    }

    @UiThread
    private void fadeOutIndicator() {
        if (mState == STATE_NOT_SHOWN || mState == STATE_DISAPPEARING) return;

        mUiThreadHandler.removeCallbacks(mCollapseRunnable);

        if (mViewAndWindowAdded) {
            mState = STATE_DISAPPEARING;
            animateIconDisappearance();
        } else {
            // Appearing animation has not started yet, as we were still waiting for the View to be
            // laid out.
            mState = STATE_NOT_SHOWN;
            removeIndicatorView();
        }
        if (mChipDrawable != null) {
            mChipDrawable.updateIcons(0);
        }
    }

    @UiThread
    private void createAndShowIndicator() {
        mState = STATE_APPEARING;

        if (mIndicatorView != null || mViewAndWindowAdded) {
            removeIndicatorView();
        }

        // Inflate the indicator view
        mIndicatorView = (ViewGroup) LayoutInflater.from(mContext).inflate(
                R.layout.tv_ongoing_privacy_chip, null);

        // 1. Set icon alpha to 0.
        // 2. Wait until the window is shown and the view is laid out.
        // 3. Start a "fade in" (alpha) animation.
        mIndicatorView
                .getViewTreeObserver()
                .addOnGlobalLayoutListener(
                        new ViewTreeObserver.OnGlobalLayoutListener() {
                            @Override
                            public void onGlobalLayout() {
                                // State could have changed to NOT_SHOWN (if all the recorders are
                                // already gone)
                                if (mState != STATE_APPEARING) {
                                    return;
                                }

                                mViewAndWindowAdded = true;
                                // Remove the observer
                                mIndicatorView.getViewTreeObserver().removeOnGlobalLayoutListener(
                                        this);

                                postAccessibilityAnnouncement();
                                animateIconAppearance();
                                mChipDrawable.startInitialFadeIn();
                            }
                        });

        mChipDrawable = new PrivacyChipDrawable(mContext);
        mChipDrawable.setListener(this);
        mChipDrawable.setRtl(mIsRtl);
        ImageView chipBackground = mIndicatorView.findViewById(R.id.chip_drawable);
        if (chipBackground != null) {
            chipBackground.setImageDrawable(mChipDrawable);
        }

        mIconsContainer = mIndicatorView.findViewById(R.id.icons_container);
        mIconsContainer.setAlpha(0f);
        updateIcons();

        final WindowManager windowManager = mContext.getSystemService(WindowManager.class);
        windowManager.addView(mIndicatorView, getWindowLayoutParams());
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

    private void updateIcons() {
        List<Drawable> icons = new PrivacyChipBuilder(mContext, mPrivacyItems).generateIcons();
        mIconsContainer.removeAllViews();
        for (int i = 0; i < icons.size(); i++) {
            Drawable icon = icons.get(i);
            icon.mutate().setTint(mContext.getColor(R.color.privacy_icon_tint));
            ImageView imageView = new ImageView(mContext);
            imageView.setImageDrawable(icon);
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            mIconsContainer.addView(imageView, mIconSize, mIconSize);
            if (i != 0) {
                ViewGroup.MarginLayoutParams layoutParams =
                        (ViewGroup.MarginLayoutParams) imageView.getLayoutParams();
                layoutParams.setMarginStart(mIconMarginStart);
                imageView.setLayoutParams(layoutParams);
            }
        }
        if (mChipDrawable != null) {
            mChipDrawable.updateIcons(icons.size());
        }
    }

    private void animateIconAppearance() {
        animateIconAlphaTo(1f);
    }

    private void animateIconDisappearance() {
        animateIconAlphaTo(0f);
    }

    private void animateIconAlphaTo(float endValue) {
        if (mAnimator == null) {
            if (DEBUG) Log.d(TAG, "set up animator");

            mAnimator = new ObjectAnimator();
            mAnimator.setTarget(mIconsContainer);
            mAnimator.setProperty(View.ALPHA);
            mAnimator.addListener(new AnimatorListenerAdapter() {
                boolean mCancelled;

                @Override
                public void onAnimationStart(Animator animation, boolean isReverse) {
                    if (DEBUG) Log.d(TAG, "AnimatorListenerAdapter#onAnimationStart");
                    mCancelled = false;
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                    if (DEBUG) Log.d(TAG, "AnimatorListenerAdapter#onAnimationCancel");
                    mCancelled = true;
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    if (DEBUG) Log.d(TAG, "AnimatorListenerAdapter#onAnimationEnd");
                    // When ValueAnimator#cancel() is called it always calls onAnimationCancel(...)
                    // and then onAnimationEnd(...). We, however, only want to proceed here if the
                    // animation ended "naturally".
                    if (!mCancelled) {
                        onIconAnimationFinished();
                    }
                }
            });
        } else if (mAnimator.isRunning()) {
            if (DEBUG) Log.d(TAG, "cancel running animation");
            mAnimator.cancel();
        }

        final float currentValue = mIconsContainer.getAlpha();
        if (currentValue == endValue) {
            if (DEBUG) Log.d(TAG, "alpha not changing");
            return;
        }
        if (DEBUG) Log.d(TAG, "animate alpha to " + endValue + " from " + currentValue);

        mAnimator.setDuration(mAnimationDurationMs);
        mAnimator.setFloatValues(endValue);
        mAnimator.start();
    }

    @Override
    public void onFadeOutFinished() {
        if (DEBUG) Log.d(TAG, "drawable fade-out finished");

        if (mState == STATE_DISAPPEARING) {
            removeIndicatorView();
            mState = STATE_NOT_SHOWN;
        }
    }

    private void onIconAnimationFinished() {
        if (DEBUG) Log.d(TAG, "onAnimationFinished (icon fade)");

        if (mState == STATE_APPEARING || mState == STATE_EXPANDED) {
            collapseLater();
        }

        if (mState == STATE_APPEARING) {
            mState = STATE_EXPANDED;
        } else if (mState == STATE_DISAPPEARING) {
            removeIndicatorView();
            mState = STATE_NOT_SHOWN;
        }
    }

    private void removeIndicatorView() {
        if (DEBUG) Log.d(TAG, "removeIndicatorView");

        final WindowManager windowManager = mContext.getSystemService(WindowManager.class);
        if (windowManager != null && mIndicatorView != null) {
            windowManager.removeView(mIndicatorView);
        }

        mIndicatorView = null;
        mAnimator = null;

        if (mChipDrawable != null) {
            mChipDrawable.setListener(null);
            mChipDrawable = null;
        }

        mViewAndWindowAdded = false;
    }

    /**
     * Schedules the accessibility announcement to be made after {@code
     * ACCESSIBILITY_ANNOUNCEMENT_DELAY_MS} (if possible). This is so that only one announcement is
     * made instead of two separate ones if both the camera and the mic are started/stopped.
     */
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
        if (mIndicatorView == null) {
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
                mIndicatorView.announceForAccessibility(mContext.getString(announcement));
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
            mIndicatorView.announceForAccessibility(mContext.getString(announcement));
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

    /**
     * Used in debug logs.
     */
    private String stateToString(@State int state) {
        switch (state) {
            case STATE_NOT_SHOWN:
                return "NOT_SHOWN";
            case STATE_APPEARING:
                return "APPEARING";
            case STATE_EXPANDED:
                return "EXPANDED";
            case STATE_COLLAPSED:
                return "COLLAPSED";
            case STATE_DISAPPEARING:
                return "DISAPPEARING";
            default:
                return "INVALID";
        }
    }

}
