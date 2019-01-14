/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.wallpaper;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManager.DisplayListener;
import android.util.AttributeSet;
import android.util.FeatureFlagUtils;
import android.util.Log;
import android.view.Display;
import android.view.DisplayInfo;
import android.widget.ImageView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.notification.AnimatableProperty;
import com.android.systemui.statusbar.notification.PropertyAnimator;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.phone.ScrimState;

/**
 * A view that draws mask upon either image wallpaper or music album art in AOD.
 */
public class AodMaskView extends ImageView implements StatusBarStateController.StateListener,
        ImageWallpaperTransformer.TransformationListener {
    private static final String TAG = AodMaskView.class.getSimpleName();
    private static final int TRANSITION_DURATION = 1000;

    private static final AnimatableProperty TRANSITION_PROGRESS = AnimatableProperty.from(
            "transition_progress",
            AodMaskView::setTransitionAmount,
            AodMaskView::getTransitionAmount,
            R.id.aod_mask_transition_progress_tag,
            R.id.aod_mask_transition_progress_start_tag,
            R.id.aod_mask_transition_progress_end_tag
    );

    private final AnimationProperties mTransitionProperties = new AnimationProperties();
    private final ImageWallpaperTransformer mTransformer;
    private final RectF mBounds = new RectF();
    private boolean mChangingStates;
    private boolean mNeedMask;
    private float mTransitionAmount;
    private final WallpaperManager mWallpaperManager;
    private final DisplayManager mDisplayManager;
    private DisplayListener mDisplayListener = new DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
        }

        @Override
        public void onDisplayChanged(int displayId) {
            // We just support DEFAULT_DISPLAY currently.
            if (displayId == Display.DEFAULT_DISPLAY) {
                mTransformer.updateDisplayInfo(getDisplayInfo(displayId));
            }
        }
    };

    public AodMaskView(Context context) {
        this(context, null);
    }

    public AodMaskView(Context context, AttributeSet attrs) {
        this(context, attrs, null);
    }

    @VisibleForTesting
    public AodMaskView(Context context, AttributeSet attrs, ImageWallpaperTransformer transformer) {
        super(context, attrs);
        setClickable(false);

        StatusBarStateController controller = Dependency.get(StatusBarStateController.class);
        if (controller != null) {
            controller.addCallback(this);
        } else {
            Log.d(TAG, "Can not get StatusBarStateController!");
        }

        mDisplayManager = (DisplayManager) getContext().getSystemService(Context.DISPLAY_SERVICE);
        mDisplayManager.registerDisplayListener(mDisplayListener, null);
        mWallpaperManager =
                (WallpaperManager) getContext().getSystemService(Context.WALLPAPER_SERVICE);

        if (transformer == null) {
            mTransformer = new ImageWallpaperTransformer(this);
            mTransformer.addFilter(new ScrimFilter());
            mTransformer.addFilter(new VignetteFilter());
            mTransformer.updateOffsets();
            mTransformer.updateDisplayInfo(getDisplayInfo(Display.DEFAULT_DISPLAY));

            mTransitionProperties.setDuration(TRANSITION_DURATION);
            mTransitionProperties.setAnimationFinishListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mTransformer.setIsTransiting(false);
                }

                @Override
                public void onAnimationStart(Animator animation) {
                    mTransformer.setIsTransiting(true);
                }
            });
        } else {
            // This part should only be hit by test cases.
            mTransformer = transformer;
        }
    }

    private DisplayInfo getDisplayInfo(int displayId) {
        DisplayInfo displayInfo = new DisplayInfo();
        mDisplayManager.getDisplay(displayId).getDisplayInfo(displayInfo);
        return displayInfo;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mBounds.set(0, 0, w, h);
        mTransformer.updateOffsets();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mNeedMask) {
            mTransformer.drawTransformedImage(canvas, null /* target */, null /* src */, mBounds);
        }
    }

    private boolean checkIfNeedMask() {
        // We need mask for ImageWallpaper / LockScreen Wallpaper (Music album art).
        // Because of conflicting with another wallpaper feature,
        // we only support LockScreen wallpaper currently.
        return mWallpaperManager.getWallpaperInfo() == null || ScrimState.AOD.hasBackdrop();
    }

    @Override
    public void onStatePreChange(int oldState, int newState) {
        mChangingStates = oldState != newState;
        mNeedMask = checkIfNeedMask();
    }

    @Override
    public void onStatePostChange() {
        mChangingStates = false;
    }

    @Override
    public void onStateChanged(int newState) {
    }

    @Override
    public void onDozingChanged(boolean isDozing) {
        if (!mNeedMask) {
            return;
        }

        boolean enabled = checkFeatureIsEnabled();
        mTransformer.updateAmbientModeState(enabled && isDozing);

        if (enabled && !mChangingStates) {
            setAnimatorProperty(isDozing);
        } else {
            invalidate();
        }
    }

    private boolean checkFeatureIsEnabled() {
        return FeatureFlagUtils.isEnabled(
                getContext(), FeatureFlagUtils.AOD_IMAGEWALLPAPER_ENABLED);
    }

    @VisibleForTesting
    void setAnimatorProperty(boolean isDozing) {
        PropertyAnimator.setProperty(
                this,
                TRANSITION_PROGRESS,
                isDozing ? 1f : 0f /* newEndValue */,
                mTransitionProperties,
                true /* animated */);
    }

    @Override
    public void onTransformationUpdated() {
        invalidate();
    }

    private void setTransitionAmount(float amount) {
        mTransitionAmount = amount;
        mTransformer.updateTransitionAmount(amount);
    }

    private float getTransitionAmount() {
        return mTransitionAmount;
    }

}
