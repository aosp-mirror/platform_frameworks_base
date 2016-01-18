/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Space;
import com.android.systemui.R;
import com.android.systemui.tuner.TunerService;

import java.util.Objects;

public class NavigationBarInflaterView extends FrameLayout implements TunerService.Tunable {

    private static final String TAG = "NavBarInflater";

    public static final String NAV_BAR_VIEWS = "sysui_nav_bar";

    private static final String MENU_IME = "menu_ime";
    private static final String BACK = "back";
    private static final String HOME = "home";
    private static final String RECENT = "recent";
    private static final String NAVSPACE = "space";

    public static final String GRAVITY_SEPARATOR = ";";
    public static final String BUTTON_SEPARATOR = ",";

    private final LayoutInflater mLayoutInflater;
    private final LayoutInflater mLandscapeInflater;

    private FrameLayout mRot0;
    private FrameLayout mRot90;
    private SparseArray<ButtonDispatcher> mButtonDispatchers;
    private String mCurrentLayout;

    public NavigationBarInflaterView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mLayoutInflater = LayoutInflater.from(context);
        Configuration landscape = new Configuration();
        landscape.setTo(context.getResources().getConfiguration());
        landscape.orientation = Configuration.ORIENTATION_LANDSCAPE;
        mLandscapeInflater = LayoutInflater.from(context.createConfigurationContext(landscape));
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mRot0 = (FrameLayout) findViewById(R.id.rot0);
        mRot90 = (FrameLayout) findViewById(R.id.rot90);
        clearViews();
        inflateLayout(getDefaultLayout());
    }

    private String getDefaultLayout() {
        return mContext.getString(R.string.config_navBarLayout);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        TunerService.get(getContext()).addTunable(this, NAV_BAR_VIEWS);
    }

    @Override
    protected void onDetachedFromWindow() {
        TunerService.get(getContext()).removeTunable(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (NAV_BAR_VIEWS.equals(key)) {
            if (newValue == null) {
                newValue = getDefaultLayout();
            }
            if (!Objects.equals(mCurrentLayout, newValue)) {
                clearViews();
                inflateLayout(newValue);
            }
        }
    }

    public void setButtonDispatchers(SparseArray<ButtonDispatcher> buttonDisatchers) {
        mButtonDispatchers = buttonDisatchers;
        for (int i = 0; i < buttonDisatchers.size(); i++) {
            initiallyFill(buttonDisatchers.valueAt(i));
        }
    }

    private void initiallyFill(ButtonDispatcher buttonDispatcher) {
        addAll(buttonDispatcher, (ViewGroup) mRot0.findViewById(R.id.start_group));
        addAll(buttonDispatcher, (ViewGroup) mRot0.findViewById(R.id.center_group));
        addAll(buttonDispatcher, (ViewGroup) mRot0.findViewById(R.id.end_group));
        addAll(buttonDispatcher, (ViewGroup) mRot90.findViewById(R.id.start_group));
        addAll(buttonDispatcher, (ViewGroup) mRot90.findViewById(R.id.center_group));
        addAll(buttonDispatcher, (ViewGroup) mRot90.findViewById(R.id.end_group));
    }

    private void addAll(ButtonDispatcher buttonDispatcher, ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            // Need to manually search for each id, just in case each group has more than one
            // of a single id.  It probably mostly a waste of time, but shouldn't take long
            // and will only happen once.
            if (parent.getChildAt(i).getId() == buttonDispatcher.getId()) {
                buttonDispatcher.addView(parent.getChildAt(i));
            } else if (parent.getChildAt(i) instanceof ViewGroup) {
                addAll(buttonDispatcher, (ViewGroup) parent.getChildAt(i));
            }
        }
    }

    private void inflateLayout(String newLayout) {
        mCurrentLayout = newLayout;
        String[] sets = newLayout.split(GRAVITY_SEPARATOR);
        String[] start = sets[0].split(BUTTON_SEPARATOR);
        String[] center = sets[1].split(BUTTON_SEPARATOR);
        String[] end = sets[2].split(BUTTON_SEPARATOR);
        inflateButtons(start, (ViewGroup) mRot0.findViewById(R.id.start_group),
                (ViewGroup) mRot0.findViewById(R.id.start_group_lightsout), false);
        inflateButtons(start, (ViewGroup) mRot90.findViewById(R.id.start_group),
                (ViewGroup) mRot90.findViewById(R.id.start_group_lightsout), true);
        inflateButtons(center, (ViewGroup) mRot0.findViewById(R.id.center_group),
                (ViewGroup) mRot0.findViewById(R.id.start_group_lightsout), false);
        inflateButtons(center, (ViewGroup) mRot90.findViewById(R.id.center_group),
                (ViewGroup) mRot90.findViewById(R.id.start_group_lightsout), true);
        inflateButtons(end, (ViewGroup) mRot0.findViewById(R.id.end_group),
                (ViewGroup) mRot0.findViewById(R.id.start_group_lightsout), false);
        inflateButtons(end, (ViewGroup) mRot90.findViewById(R.id.end_group),
                (ViewGroup) mRot90.findViewById(R.id.start_group_lightsout), true);
    }

    private void inflateButtons(String[] buttons, ViewGroup parent, ViewGroup lightsOutParent,
            boolean landscape) {
        for (int i = 0; i < buttons.length; i++) {
            copyToLightsout(inflateButton(buttons[i], parent, landscape), lightsOutParent);
        }
    }

    private void copyToLightsout(View view, ViewGroup lightsOutParent) {
        if (view instanceof FrameLayout) {
            // The only ViewGroup we support in here is a FrameLayout, so copy those manually.
            FrameLayout original = (FrameLayout) view;
            FrameLayout layout = new FrameLayout(view.getContext());
            for (int i = 0; i < original.getChildCount(); i++) {
                copyToLightsout(original.getChildAt(i), layout);
            }
            lightsOutParent.addView(layout, copy(view.getLayoutParams()));
        } else if (view instanceof Space) {
            lightsOutParent.addView(new Space(view.getContext()), copy(view.getLayoutParams()));
        } else {
            lightsOutParent.addView(generateLightsOutView(view), copy(view.getLayoutParams()));
        }
    }

    private View generateLightsOutView(View view) {
        ImageView imageView = new ImageView(view.getContext());
        // Copy everything we can about the original view.
        imageView.setPadding(view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(),
                view.getPaddingBottom());
        imageView.setContentDescription(view.getContentDescription());
        imageView.setId(view.getId());
        // Only home gets a big dot, everything else will be little.
        imageView.setImageResource(view.getId() == R.id.home
                ? R.drawable.ic_sysbar_lights_out_dot_large
                : R.drawable.ic_sysbar_lights_out_dot_small);
        return imageView;
    }

    private ViewGroup.LayoutParams copy(ViewGroup.LayoutParams layoutParams) {
        return new LayoutParams(layoutParams.width, layoutParams.height);
    }

    private View inflateButton(String button, ViewGroup parent, boolean landscape) {
        View v = null;
        if (HOME.equals(button)) {
            v = (landscape ? mLandscapeInflater : mLayoutInflater)
                    .inflate(R.layout.home, parent, false);
            if (landscape && isSw600Dp()) {
                setupLandButton(v);
            }
        } else if (BACK.equals(button)) {
            v = (landscape ? mLandscapeInflater : mLayoutInflater)
                    .inflate(R.layout.back, parent, false);
            if (landscape && isSw600Dp()) {
                setupLandButton(v);
            }
        } else if (RECENT.equals(button)) {
            v = (landscape ? mLandscapeInflater : mLayoutInflater)
                    .inflate(R.layout.recent_apps, parent, false);
            if (landscape && isSw600Dp()) {
                setupLandButton(v);
            }
        } else if (MENU_IME.equals(button)) {
            v = (landscape ? mLandscapeInflater : mLayoutInflater)
                    .inflate(R.layout.menu_ime, parent, false);
        } else if (NAVSPACE.equals(button)) {
            v = (landscape ? mLandscapeInflater : mLayoutInflater)
                    .inflate(R.layout.nav_key_space, parent, false);
        } else {
            throw new IllegalArgumentException("Unknown button " + button);
        }
        parent.addView(v);
        if (mButtonDispatchers != null) {
            final int indexOfKey = mButtonDispatchers.indexOfKey(v.getId());
            if (indexOfKey >= 0) {
                mButtonDispatchers.valueAt(indexOfKey).addView(v);
            }
        }
        return v;
    }

    private boolean isSw600Dp() {
        Configuration configuration = mContext.getResources().getConfiguration();
        return (configuration.smallestScreenWidthDp >= 600);
    }

    /**
     * This manually sets the width of sw600dp landscape buttons because despite
     * overriding the configuration from the overridden resources aren't loaded currently.
     */
    private void setupLandButton(View v) {
        Resources res = mContext.getResources();
        v.getLayoutParams().width = res.getDimensionPixelOffset(
                R.dimen.navigation_key_width_sw600dp_land);
        int padding = res.getDimensionPixelOffset(R.dimen.navigation_key_padding_sw600dp_land);
        v.setPadding(padding, v.getPaddingTop(), padding, v.getPaddingBottom());
    }

    private void clearViews() {
        if (mButtonDispatchers != null) {
            for (int i = 0; i < mButtonDispatchers.size(); i++) {
                mButtonDispatchers.valueAt(i).clear();
            }
        }
        clearAllChildren((ViewGroup) mRot0.findViewById(R.id.nav_buttons));
        clearAllChildren((ViewGroup) mRot0.findViewById(R.id.lights_out));
        clearAllChildren((ViewGroup) mRot90.findViewById(R.id.nav_buttons));
        clearAllChildren((ViewGroup) mRot90.findViewById(R.id.lights_out));
    }

    private void clearAllChildren(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            ((ViewGroup) group.getChildAt(i)).removeAllViews();
        }
    }
}
