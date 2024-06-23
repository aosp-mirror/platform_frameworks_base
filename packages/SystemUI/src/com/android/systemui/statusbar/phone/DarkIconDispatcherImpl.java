/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import static com.android.systemui.plugins.DarkIconDispatcher.getTint;
import static com.android.settingslib.flags.Flags.newStatusBarIcons;

import android.animation.ArgbEvaluator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.util.ArrayMap;
import android.widget.ImageView;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dump.DumpManager;

import java.io.PrintWriter;
import java.util.ArrayList;

import javax.inject.Inject;

import kotlinx.coroutines.flow.FlowKt;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

/**
 */
@SysUISingleton
public class DarkIconDispatcherImpl implements SysuiDarkIconDispatcher,
        LightBarTransitionsController.DarkIntensityApplier {

    private final LightBarTransitionsController mTransitionsController;
    private final ArrayList<Rect> mTintAreas = new ArrayList<>();
    private final ArrayMap<Object, DarkReceiver> mReceivers = new ArrayMap<>();

    private int mIconTint = DEFAULT_ICON_TINT;
    private int mContrastTint = DEFAULT_INVERSE_ICON_TINT;

    private int mDarkModeContrastColor = DEFAULT_ICON_TINT;
    private int mLightModeContrastColor = DEFAULT_INVERSE_ICON_TINT;

    private float mDarkIntensity;
    private int mDarkModeIconColorSingleTone;
    private int mLightModeIconColorSingleTone;

    private final MutableStateFlow<DarkChange> mDarkChangeFlow = StateFlowKt.MutableStateFlow(
            DarkChange.EMPTY);

    /**
     */
    @Inject
    public DarkIconDispatcherImpl(
            Context context,
            LightBarTransitionsController.Factory lightBarTransitionsControllerFactory,
            DumpManager dumpManager) {

        if (newStatusBarIcons()) {
            mDarkModeIconColorSingleTone = Color.BLACK;
            mLightModeIconColorSingleTone = Color.WHITE;
        } else {
            mDarkModeIconColorSingleTone = context.getColor(
                    com.android.settingslib.R.color.dark_mode_icon_color_single_tone);
            mLightModeIconColorSingleTone = context.getColor(
                    com.android.settingslib.R.color.light_mode_icon_color_single_tone);
        }

        mTransitionsController = lightBarTransitionsControllerFactory.create(this);

        dumpManager.registerDumpable(getClass().getSimpleName(), this);
    }

    public LightBarTransitionsController getTransitionsController() {
        return mTransitionsController;
    }

    @Override
    public StateFlow<DarkChange> darkChangeFlow() {
        return FlowKt.asStateFlow(mDarkChangeFlow);
    }

    public void addDarkReceiver(DarkReceiver receiver) {
        mReceivers.put(receiver, receiver);
        receiver.onDarkChanged(mTintAreas, mDarkIntensity, mIconTint);
        receiver.onDarkChangedWithContrast(mTintAreas, mIconTint, mContrastTint);
    }

    public void addDarkReceiver(ImageView imageView) {
        DarkReceiver receiver = (area, darkIntensity, tint) -> imageView.setImageTintList(
                ColorStateList.valueOf(getTint(mTintAreas, imageView, mIconTint)));
        mReceivers.put(imageView, receiver);
        receiver.onDarkChanged(mTintAreas, mDarkIntensity, mIconTint);
        receiver.onDarkChangedWithContrast(mTintAreas, mIconTint, mContrastTint);
    }

    public void removeDarkReceiver(DarkReceiver object) {
        mReceivers.remove(object);
    }

    public void removeDarkReceiver(ImageView object) {
        mReceivers.remove(object);
    }

    public void applyDark(DarkReceiver object) {
        mReceivers.get(object).onDarkChanged(mTintAreas, mDarkIntensity, mIconTint);
        mReceivers.get(object).onDarkChangedWithContrast(mTintAreas, mIconTint, mContrastTint);
    }

    /**
     * Sets the dark area so {@link #applyDark} only affects the icons in the specified area.
     *
     * @param darkAreas the areas in which icons should change it's tint, in logical screen
     *                  coordinates
     */
    public void setIconsDarkArea(ArrayList<Rect> darkAreas) {
        if (darkAreas == null && mTintAreas.isEmpty()) {
            return;
        }

        mTintAreas.clear();
        if (darkAreas != null) {
            mTintAreas.addAll(darkAreas);
        }
        applyIconTint();
    }

    @Override
    public void applyDarkIntensity(float darkIntensity) {
        mDarkIntensity = darkIntensity;
        ArgbEvaluator evaluator = ArgbEvaluator.getInstance();

        mIconTint = (int) evaluator.evaluate(darkIntensity,
                mLightModeIconColorSingleTone, mDarkModeIconColorSingleTone);
        mContrastTint = (int) evaluator
                .evaluate(darkIntensity, mLightModeContrastColor, mDarkModeContrastColor);

        applyIconTint();
    }

    @Override
    public int getTintAnimationDuration() {
        return LightBarTransitionsController.DEFAULT_TINT_ANIMATION_DURATION;
    }

    private void applyIconTint() {
        mDarkChangeFlow.setValue(new DarkChange(mTintAreas, mDarkIntensity, mIconTint));
        for (int i = 0; i < mReceivers.size(); i++) {
            mReceivers.valueAt(i).onDarkChanged(mTintAreas, mDarkIntensity, mIconTint);
            mReceivers.valueAt(i).onDarkChangedWithContrast(mTintAreas, mIconTint, mContrastTint);
        }
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println("DarkIconDispatcher: ");
        pw.println("  mIconTint: 0x" + Integer.toHexString(mIconTint));
        pw.println("  mContrastTint: 0x" + Integer.toHexString(mContrastTint));

        pw.println("  mDarkModeIconColorSingleTone: 0x"
                + Integer.toHexString(mDarkModeIconColorSingleTone));
        pw.println("  mLightModeIconColorSingleTone: 0x"
                + Integer.toHexString(mLightModeIconColorSingleTone));

        pw.println("  mDarkModeContrastColor: 0x" + Integer.toHexString(mDarkModeContrastColor));
        pw.println("  mLightModeContrastColor: 0x" + Integer.toHexString(mLightModeContrastColor));

        pw.println("  mDarkIntensity: " + mDarkIntensity + "f");
        pw.println("  mTintAreas: " + mTintAreas);
    }
}
