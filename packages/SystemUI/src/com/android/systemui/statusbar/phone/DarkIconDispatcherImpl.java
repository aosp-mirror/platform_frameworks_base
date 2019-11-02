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

import static com.android.systemui.plugins.DarkIconDispatcher.DEFAULT_ICON_TINT;
import static com.android.systemui.plugins.DarkIconDispatcher.getTint;

import android.animation.ArgbEvaluator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.util.ArrayMap;
import android.widget.ImageView;

import com.android.systemui.R;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.statusbar.CommandQueue;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 */
@Singleton
public class DarkIconDispatcherImpl implements SysuiDarkIconDispatcher,
        LightBarTransitionsController.DarkIntensityApplier {

    private final LightBarTransitionsController mTransitionsController;
    private final Rect mTintArea = new Rect();
    private final ArrayMap<Object, DarkReceiver> mReceivers = new ArrayMap<>();

    private int mIconTint = DEFAULT_ICON_TINT;
    private float mDarkIntensity;
    private int mDarkModeIconColorSingleTone;
    private int mLightModeIconColorSingleTone;

    /**
     */
    @Inject
    public DarkIconDispatcherImpl(Context context, CommandQueue commandQueue) {
        mDarkModeIconColorSingleTone = context.getColor(R.color.dark_mode_icon_color_single_tone);
        mLightModeIconColorSingleTone = context.getColor(R.color.light_mode_icon_color_single_tone);

        mTransitionsController = new LightBarTransitionsController(context, this, commandQueue);
    }

    public LightBarTransitionsController getTransitionsController() {
        return mTransitionsController;
    }

    public void addDarkReceiver(DarkReceiver receiver) {
        mReceivers.put(receiver, receiver);
        receiver.onDarkChanged(mTintArea, mDarkIntensity, mIconTint);
    }

    public void addDarkReceiver(ImageView imageView) {
        DarkReceiver receiver = (area, darkIntensity, tint) -> imageView.setImageTintList(
                ColorStateList.valueOf(getTint(mTintArea, imageView, mIconTint)));
        mReceivers.put(imageView, receiver);
        receiver.onDarkChanged(mTintArea, mDarkIntensity, mIconTint);
    }

    public void removeDarkReceiver(DarkReceiver object) {
        mReceivers.remove(object);
    }

    public void removeDarkReceiver(ImageView object) {
        mReceivers.remove(object);
    }

    public void applyDark(DarkReceiver object) {
        mReceivers.get(object).onDarkChanged(mTintArea, mDarkIntensity, mIconTint);
    }

    /**
     * Sets the dark area so {@link #applyDark} only affects the icons in the specified area.
     *
     * @param darkArea the area in which icons should change it's tint, in logical screen
     *                 coordinates
     */
    public void setIconsDarkArea(Rect darkArea) {
        if (darkArea == null && mTintArea.isEmpty()) {
            return;
        }
        if (darkArea == null) {
            mTintArea.setEmpty();
        } else {
            mTintArea.set(darkArea);
        }
        applyIconTint();
    }

    @Override
    public void applyDarkIntensity(float darkIntensity) {
        mDarkIntensity = darkIntensity;
        mIconTint = (int) ArgbEvaluator.getInstance().evaluate(darkIntensity,
                mLightModeIconColorSingleTone, mDarkModeIconColorSingleTone);
        applyIconTint();
    }

    @Override
    public int getTintAnimationDuration() {
        return LightBarTransitionsController.DEFAULT_TINT_ANIMATION_DURATION;
    }

    private void applyIconTint() {
        for (int i = 0; i < mReceivers.size(); i++) {
            mReceivers.valueAt(i).onDarkChanged(mTintArea, mDarkIntensity, mIconTint);
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("DarkIconDispatcher: ");
        pw.println("  mIconTint: 0x" + Integer.toHexString(mIconTint));
        pw.println("  mDarkIntensity: " + mDarkIntensity + "f");
        pw.println("  mTintArea: " + mTintArea);
    }
}
