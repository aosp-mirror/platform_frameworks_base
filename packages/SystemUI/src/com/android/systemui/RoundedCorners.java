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
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.provider.Settings.Secure;
import android.support.annotation.VisibleForTesting;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnLayoutChangeListener;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.widget.ImageView;

import com.android.systemui.R.id;
import com.android.systemui.fragments.FragmentHostManager;
import com.android.systemui.fragments.FragmentHostManager.FragmentListener;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.qs.SecureSetting;
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
        if (mRoundedDefault != 0) {
            setupRounding();
        }
        int padding = mContext.getResources().getDimensionPixelSize(
                R.dimen.rounded_corner_content_padding);
        if (padding != 0) {
            setupPadding(padding);
        }
    }

    private void setupRounding() {
        mOverlay = LayoutInflater.from(mContext)
                .inflate(R.layout.rounded_corners, null);
        mOverlay.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        mOverlay.setAlpha(0);
        mOverlay.findViewById(R.id.right).setRotation(90);

        mContext.getSystemService(WindowManager.class)
                .addView(mOverlay, getWindowLayoutParams());
        mBottomOverlay = LayoutInflater.from(mContext)
                .inflate(R.layout.rounded_corners, null);
        mBottomOverlay.setAlpha(0);
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

        // Watch color inversion and invert the overlay as needed.
        SecureSetting setting = new SecureSetting(mContext, Dependency.get(Dependency.MAIN_HANDLER),
                Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED) {
            @Override
            protected void handleValueChanged(int value, boolean observedChange) {
                int tint = value != 0 ? Color.WHITE : Color.BLACK;
                ColorStateList tintList = ColorStateList.valueOf(tint);
                ((ImageView) mOverlay.findViewById(id.left)).setImageTintList(tintList);
                ((ImageView) mOverlay.findViewById(id.right)).setImageTintList(tintList);
                ((ImageView) mBottomOverlay.findViewById(id.left)).setImageTintList(tintList);
                ((ImageView) mBottomOverlay.findViewById(id.right)).setImageTintList(tintList);
            }
        };
        setting.setListening(true);
        setting.onChange(false);

        mOverlay.addOnLayoutChangeListener(new OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                    int oldLeft,
                    int oldTop, int oldRight, int oldBottom) {
                mOverlay.removeOnLayoutChangeListener(this);
                mOverlay.animate()
                        .alpha(1)
                        .setDuration(1000)
                        .start();
                mBottomOverlay.animate()
                        .alpha(1)
                        .setDuration(1000)
                        .start();
            }
        });
    }

    private void setupPadding(int padding) {
        // Add some padding to all the content near the edge of the screen.
        StatusBar sb = getComponent(StatusBar.class);
        View statusBar = (sb != null ? sb.getStatusBarWindow() : null);
        if (statusBar != null) {
            TunablePadding.addTunablePadding(statusBar.findViewById(R.id.keyguard_header), PADDING,
                    padding, FLAG_END);

            FragmentHostManager fragmentHostManager = FragmentHostManager.get(statusBar);
            fragmentHostManager.addTagListener(CollapsedStatusBarFragment.TAG,
                    new TunablePaddingTagListener(padding, R.id.status_bar));
            fragmentHostManager.addTagListener(QS.TAG,
                    new TunablePaddingTagListener(padding, R.id.header));
        }
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
        lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS
                | WindowManager.LayoutParams.PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY;
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
