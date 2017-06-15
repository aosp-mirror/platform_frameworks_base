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

package com.android.systemui;

import static com.android.systemui.tuner.TunablePadding.FLAG_START;
import static com.android.systemui.tuner.TunablePadding.FLAG_END;

import android.app.Fragment;
import android.graphics.PixelFormat;
import android.support.annotation.VisibleForTesting;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;

import com.android.systemui.R.id;
import com.android.systemui.fragments.FragmentHostManager;
import com.android.systemui.fragments.FragmentHostManager.FragmentListener;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.statusbar.phone.CollapsedStatusBarFragment;
import com.android.systemui.statusbar.phone.NavigationBarFragment;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.tuner.TunablePadding;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;

public class RoundedCorners extends SystemUI implements Tunable {
    public static final String SIZE = "sysui_rounded_size";
    public static final String PADDING = "sysui_rounded_content_padding";

    private int mRoundedDefault;
    private View mOverlay;
    private View mBottomOverlay;
    private float mDensity;
    private TunablePadding mQsPadding;
    private TunablePadding mStatusBarPadding;
    private TunablePadding mNavBarPadding;

    @Override
    public void start() {
        mRoundedDefault = mContext.getResources().getDimensionPixelSize(
                R.dimen.rounded_corner_radius);
        if (mRoundedDefault == 0) {
            // No rounded corners on this device.
            return;
        }

        mOverlay = LayoutInflater.from(mContext)
                .inflate(R.layout.rounded_corners, null);
        mOverlay.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        mOverlay.findViewById(R.id.right).setRotation(90);

        mContext.getSystemService(WindowManager.class)
                .addView(mOverlay, getWindowLayoutParams());
        mBottomOverlay = LayoutInflater.from(mContext)
                .inflate(R.layout.rounded_corners, null);
        mBottomOverlay.findViewById(R.id.right).setRotation(180);
        mBottomOverlay.findViewById(R.id.left).setRotation(270);
        WindowManager.LayoutParams layoutParams = getWindowLayoutParams();
        layoutParams.gravity = Gravity.BOTTOM;
        mContext.getSystemService(WindowManager.class)
                .addView(mBottomOverlay, layoutParams);

        DisplayMetrics metrics = new DisplayMetrics();
        mContext.getSystemService(WindowManager.class)
                .getDefaultDisplay().getMetrics(metrics);
        mDensity = metrics.density;

        Dependency.get(TunerService.class).addTunable(this, SIZE);

        // Add some padding to all the content near the edge of the screen.
        int padding = mContext.getResources().getDimensionPixelSize(
                R.dimen.rounded_corner_content_padding);
        StatusBar sb = getComponent(StatusBar.class);
        View statusBar = sb.getStatusBarWindow();

        TunablePadding.addTunablePadding(statusBar.findViewById(R.id.keyguard_header), PADDING,
                padding, FLAG_END);

        FragmentHostManager.get(sb.getNavigationBarWindow()).addTagListener(
                NavigationBarFragment.TAG,
                new TunablePaddingTagListener(padding, 0));

        FragmentHostManager fragmentHostManager = FragmentHostManager.get(statusBar);
        fragmentHostManager.addTagListener(CollapsedStatusBarFragment.TAG,
                new TunablePaddingTagListener(padding, R.id.status_bar));
        fragmentHostManager.addTagListener(QS.TAG,
                new TunablePaddingTagListener(padding, R.id.header));
    }

    private WindowManager.LayoutParams getWindowLayoutParams() {
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                        | WindowManager.LayoutParams.FLAG_SLIPPERY
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
        lp.setTitle("RoundedOverlay");
        lp.gravity = Gravity.TOP;
        return lp;
    }


    @Override
    public void onTuningChanged(String key, String newValue) {
        if (mOverlay == null) return;
        if (SIZE.equals(key)) {
            int size = mRoundedDefault;
            try {
                size = (int) (Integer.parseInt(newValue) * mDensity);
            } catch (Exception e) {
            }
            setSize(mOverlay.findViewById(R.id.left), size);
            setSize(mOverlay.findViewById(R.id.right), size);
            setSize(mBottomOverlay.findViewById(R.id.left), size);
            setSize(mBottomOverlay.findViewById(R.id.right), size);
        }
    }

    private void setSize(View view, int pixelSize) {
        LayoutParams params = view.getLayoutParams();
        params.width = pixelSize;
        params.height = pixelSize;
        view.setLayoutParams(params);
    }

    @VisibleForTesting
    static class TunablePaddingTagListener implements FragmentListener {

        private final int mPadding;
        private final int mId;
        private TunablePadding mTunablePadding;

        public TunablePaddingTagListener(int padding, int id) {
            mPadding = padding;
            mId = id;
        }

        @Override
        public void onFragmentViewCreated(String tag, Fragment fragment) {
            if (mTunablePadding != null) {
                mTunablePadding.destroy();
            }
            View view = fragment.getView();
            if (mId != 0) {
                view = view.findViewById(mId);
            }
            mTunablePadding = TunablePadding.addTunablePadding(view, PADDING, mPadding,
                    FLAG_START | FLAG_END);
        }
    }
}
