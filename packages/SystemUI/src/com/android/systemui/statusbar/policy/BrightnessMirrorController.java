/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.policy;

import android.annotation.NonNull;
import android.content.Context;
import android.content.res.Resources;
import android.util.ArraySet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.android.systemui.R;
import com.android.systemui.settings.brightness.BrightnessSlider;
import com.android.systemui.settings.brightness.ToggleSlider;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.phone.NotificationPanelViewController;
import com.android.systemui.statusbar.phone.NotificationShadeWindowView;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Controls showing and hiding of the brightness mirror.
 */
public class BrightnessMirrorController
        implements CallbackController<BrightnessMirrorController.BrightnessMirrorListener> {

    private final NotificationShadeWindowView mStatusBarWindow;
    private final Consumer<Boolean> mVisibilityCallback;
    private final NotificationPanelViewController mNotificationPanel;
    private final NotificationShadeDepthController mDepthController;
    private final ArraySet<BrightnessMirrorListener> mBrightnessMirrorListeners = new ArraySet<>();
    private final BrightnessSlider.Factory mToggleSliderFactory;
    private BrightnessSlider mToggleSliderController;
    private final int[] mInt2Cache = new int[2];
    private FrameLayout mBrightnessMirror;
    private int mBrightnessMirrorBackgroundPadding;
    private int mLastBrightnessSliderWidth = -1;

    public BrightnessMirrorController(NotificationShadeWindowView statusBarWindow,
            NotificationPanelViewController notificationPanelViewController,
            NotificationShadeDepthController notificationShadeDepthController,
            BrightnessSlider.Factory factory,
            @NonNull Consumer<Boolean> visibilityCallback) {
        mStatusBarWindow = statusBarWindow;
        mToggleSliderFactory = factory;
        mBrightnessMirror = statusBarWindow.findViewById(R.id.brightness_mirror_container);
        mToggleSliderController = setMirrorLayout();
        mNotificationPanel = notificationPanelViewController;
        mDepthController = notificationShadeDepthController;
        mNotificationPanel.setPanelAlphaEndAction(() -> {
            mBrightnessMirror.setVisibility(View.INVISIBLE);
        });
        mVisibilityCallback = visibilityCallback;
    }

    public void showMirror() {
        mBrightnessMirror.setVisibility(View.VISIBLE);
        mVisibilityCallback.accept(true);
        mNotificationPanel.setPanelAlpha(0, true /* animate */);
        mDepthController.setBrightnessMirrorVisible(true);
    }

    public void hideMirror() {
        mVisibilityCallback.accept(false);
        mNotificationPanel.setPanelAlpha(255, true /* animate */);
        mDepthController.setBrightnessMirrorVisible(false);
    }

    /**
     * Set the location and size of the mirror container to match that of the slider in QS
     * @param original the original view in QS
     */
    public void setLocationAndSize(View original) {
        original.getLocationInWindow(mInt2Cache);

        // Original is slightly larger than the mirror, so make sure to use the center for the
        // positioning.
        int originalX = mInt2Cache[0] - mBrightnessMirrorBackgroundPadding;
        int originalY = mInt2Cache[1] - mBrightnessMirrorBackgroundPadding;
        mBrightnessMirror.setTranslationX(0);
        mBrightnessMirror.setTranslationY(0);
        mBrightnessMirror.getLocationInWindow(mInt2Cache);
        int mirrorX = mInt2Cache[0];
        int mirrorY = mInt2Cache[1];
        mBrightnessMirror.setTranslationX(originalX - mirrorX);
        mBrightnessMirror.setTranslationY(originalY - mirrorY);

        // Set the brightness mirror container to be the width of the mirror + 2 times the padding
        int newWidth = original.getMeasuredWidth() + 2 * mBrightnessMirrorBackgroundPadding;
        if (newWidth != mLastBrightnessSliderWidth) {
            ViewGroup.LayoutParams lp = mBrightnessMirror.getLayoutParams();
            lp.width = newWidth;
            mBrightnessMirror.setLayoutParams(lp);
        }
    }

    public ToggleSlider getToggleSlider() {
        return mToggleSliderController;
    }

    public void updateResources() {
        Resources r = mBrightnessMirror.getResources();
        mBrightnessMirrorBackgroundPadding = r
                .getDimensionPixelSize(R.dimen.rounded_slider_background_padding);
        mBrightnessMirror.setPadding(
                mBrightnessMirrorBackgroundPadding,
                mBrightnessMirrorBackgroundPadding,
                mBrightnessMirrorBackgroundPadding,
                mBrightnessMirrorBackgroundPadding
        );
    }

    public void onOverlayChanged() {
        reinflate();
    }

    public void onDensityOrFontScaleChanged() {
        reinflate();
    }

    private BrightnessSlider setMirrorLayout() {
        Context context = mBrightnessMirror.getContext();
        BrightnessSlider controller = mToggleSliderFactory.create(context, mBrightnessMirror);
        controller.init();

        mBrightnessMirror.addView(controller.getRootView(), ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);

        return controller;
    }

    private void reinflate() {
        int index = mStatusBarWindow.indexOfChild(mBrightnessMirror);
        mStatusBarWindow.removeView(mBrightnessMirror);
        mBrightnessMirror = (FrameLayout) LayoutInflater.from(mStatusBarWindow.getContext())
                .inflate(R.layout.brightness_mirror_container, mStatusBarWindow, false);
        mToggleSliderController = setMirrorLayout();
        mStatusBarWindow.addView(mBrightnessMirror, index);

        for (int i = 0; i < mBrightnessMirrorListeners.size(); i++) {
            mBrightnessMirrorListeners.valueAt(i).onBrightnessMirrorReinflated(mBrightnessMirror);
        }
    }

    @Override
    public void addCallback(@NonNull BrightnessMirrorListener listener) {
        Objects.requireNonNull(listener);
        mBrightnessMirrorListeners.add(listener);
    }

    @Override
    public void removeCallback(@NonNull BrightnessMirrorListener listener) {
        mBrightnessMirrorListeners.remove(listener);
    }

    public void onUiModeChanged() {
        reinflate();
    }

    public interface BrightnessMirrorListener {
        void onBrightnessMirrorReinflated(View brightnessMirror);
    }
}
