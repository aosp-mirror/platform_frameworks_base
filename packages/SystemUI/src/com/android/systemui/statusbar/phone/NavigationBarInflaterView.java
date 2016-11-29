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

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Configuration;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.Display;
import android.view.Display.Mode;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Space;

import com.android.systemui.R;
import com.android.systemui.statusbar.policy.KeyButtonView;
import com.android.systemui.tuner.TunerService;

import java.util.Objects;

public class NavigationBarInflaterView extends FrameLayout implements TunerService.Tunable {

    private static final String TAG = "NavBarInflater";

    public static final String NAV_BAR_VIEWS = "sysui_nav_bar";

    public static final String MENU_IME = "menu_ime";
    public static final String BACK = "back";
    public static final String HOME = "home";
    public static final String RECENT = "recent";
    public static final String NAVSPACE = "space";
    public static final String CLIPBOARD = "clipboard";
    public static final String KEY = "key";

    public static final String GRAVITY_SEPARATOR = ";";
    public static final String BUTTON_SEPARATOR = ",";

    public static final String SIZE_MOD_START = "[";
    public static final String SIZE_MOD_END = "]";

    public static final String KEY_CODE_START = "(";
    public static final String KEY_IMAGE_DELIM = ":";
    public static final String KEY_CODE_END = ")";

    protected LayoutInflater mLayoutInflater;
    protected LayoutInflater mLandscapeInflater;
    private int mDensity;

    protected FrameLayout mRot0;
    protected FrameLayout mRot90;
    private boolean isRot0Landscape;

    private SparseArray<ButtonDispatcher> mButtonDispatchers;
    private String mCurrentLayout;

    private View mLastPortrait;
    private View mLastLandscape;

    private boolean mAlternativeOrder;

    public NavigationBarInflaterView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = context.getResources().getConfiguration().densityDpi;
        createInflaters();
        Display display = ((WindowManager)
                context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        Mode displayMode = display.getMode();
        isRot0Landscape = displayMode.getPhysicalWidth() > displayMode.getPhysicalHeight();
    }

    private void createInflaters() {
        mLayoutInflater = LayoutInflater.from(mContext);
        Configuration landscape = new Configuration();
        landscape.setTo(mContext.getResources().getConfiguration());
        landscape.orientation = Configuration.ORIENTATION_LANDSCAPE;
        mLandscapeInflater = LayoutInflater.from(mContext.createConfigurationContext(landscape));
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mDensity != newConfig.densityDpi) {
            mDensity = newConfig.densityDpi;
            createInflaters();
            inflateChildren();
            clearViews();
            inflateLayout(mCurrentLayout);
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        inflateChildren();
        clearViews();
        inflateLayout(getDefaultLayout());
    }

    private void inflateChildren() {
        removeAllViews();
        mRot0 = (FrameLayout) mLayoutInflater.inflate(R.layout.navigation_layout, this, false);
        mRot0.setId(R.id.rot0);
        addView(mRot0);
        mRot90 = (FrameLayout) mLayoutInflater.inflate(R.layout.navigation_layout_rot90, this,
                false);
        mRot90.setId(R.id.rot90);
        addView(mRot90);
        updateAlternativeOrder();
        if (getParent() instanceof NavigationBarView) {
            ((NavigationBarView) getParent()).updateRotatedViews();
        }
    }

    protected String getDefaultLayout() {
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

    public void setAlternativeOrder(boolean alternativeOrder) {
        if (alternativeOrder != mAlternativeOrder) {
            mAlternativeOrder = alternativeOrder;
            updateAlternativeOrder();
        }
    }

    private void updateAlternativeOrder() {
        updateAlternativeOrder(mRot0.findViewById(R.id.ends_group));
        updateAlternativeOrder(mRot0.findViewById(R.id.center_group));
        updateAlternativeOrder(mRot90.findViewById(R.id.ends_group));
        updateAlternativeOrder(mRot90.findViewById(R.id.center_group));
    }

    private void updateAlternativeOrder(View v) {
        if (v instanceof ReverseLinearLayout) {
            ((ReverseLinearLayout) v).setAlternativeOrder(mAlternativeOrder);
        }
    }

    private void initiallyFill(ButtonDispatcher buttonDispatcher) {
        addAll(buttonDispatcher, (ViewGroup) mRot0.findViewById(R.id.ends_group));
        addAll(buttonDispatcher, (ViewGroup) mRot0.findViewById(R.id.center_group));
        addAll(buttonDispatcher, (ViewGroup) mRot90.findViewById(R.id.ends_group));
        addAll(buttonDispatcher, (ViewGroup) mRot90.findViewById(R.id.center_group));
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

    protected void inflateLayout(String newLayout) {
        mCurrentLayout = newLayout;
        if (newLayout == null) {
            newLayout = getDefaultLayout();
        }
        String[] sets = newLayout.split(GRAVITY_SEPARATOR, 3);
        String[] start = sets[0].split(BUTTON_SEPARATOR);
        String[] center = sets[1].split(BUTTON_SEPARATOR);
        String[] end = sets[2].split(BUTTON_SEPARATOR);
        // Inflate these in start to end order or accessibility traversal will be messed up.
        inflateButtons(start, (ViewGroup) mRot0.findViewById(R.id.ends_group), isRot0Landscape);
        inflateButtons(start, (ViewGroup) mRot90.findViewById(R.id.ends_group), !isRot0Landscape);

        inflateButtons(center, (ViewGroup) mRot0.findViewById(R.id.center_group), isRot0Landscape);
        inflateButtons(center, (ViewGroup) mRot90.findViewById(R.id.center_group), !isRot0Landscape);

        addGravitySpacer((LinearLayout) mRot0.findViewById(R.id.ends_group));
        addGravitySpacer((LinearLayout) mRot90.findViewById(R.id.ends_group));

        inflateButtons(end, (ViewGroup) mRot0.findViewById(R.id.ends_group), isRot0Landscape);
        inflateButtons(end, (ViewGroup) mRot90.findViewById(R.id.ends_group), !isRot0Landscape);
    }

    private void addGravitySpacer(LinearLayout layout) {
        layout.addView(new Space(mContext), new LinearLayout.LayoutParams(0, 0, 1));
    }

    private void inflateButtons(String[] buttons, ViewGroup parent, boolean landscape) {
        for (int i = 0; i < buttons.length; i++) {
            inflateButton(buttons[i], parent, landscape);
        }
    }

    private ViewGroup.LayoutParams copy(ViewGroup.LayoutParams layoutParams) {
        if (layoutParams instanceof LinearLayout.LayoutParams) {
            return new LinearLayout.LayoutParams(layoutParams.width, layoutParams.height,
                    ((LinearLayout.LayoutParams) layoutParams).weight);
        }
        return new LayoutParams(layoutParams.width, layoutParams.height);
    }

    @Nullable
    protected View inflateButton(String buttonSpec, ViewGroup parent, boolean landscape) {
        LayoutInflater inflater = landscape ? mLandscapeInflater : mLayoutInflater;
        float size = extractSize(buttonSpec);
        String button = extractButton(buttonSpec);
        View v = null;
        if (HOME.equals(button)) {
            v = inflater.inflate(R.layout.home, parent, false);
        } else if (BACK.equals(button)) {
            v = inflater.inflate(R.layout.back, parent, false);
        } else if (RECENT.equals(button)) {
            v = inflater.inflate(R.layout.recent_apps, parent, false);
        } else if (MENU_IME.equals(button)) {
            v = inflater.inflate(R.layout.menu_ime, parent, false);
        } else if (NAVSPACE.equals(button)) {
            v = inflater.inflate(R.layout.nav_key_space, parent, false);
        } else if (CLIPBOARD.equals(button)) {
            v = inflater.inflate(R.layout.clipboard, parent, false);
        } else if (button.startsWith(KEY)) {
            String uri = extractImage(button);
            int code = extractKeycode(button);
            v = inflater.inflate(R.layout.custom_key, parent, false);
            ((KeyButtonView) v).setCode(code);
            if (uri != null) {
                ((KeyButtonView) v).loadAsync(uri);
            }
        } else {
            return null;
        }

        if (size != 0) {
            ViewGroup.LayoutParams params = v.getLayoutParams();
            params.width = (int) (params.width * size);
        }
        parent.addView(v);
        addToDispatchers(v);
        View lastView = landscape ? mLastLandscape : mLastPortrait;
        if (lastView != null) {
            v.setAccessibilityTraversalAfter(lastView.getId());
        }
        if (landscape) {
            mLastLandscape = v;
        } else {
            mLastPortrait = v;
        }
        return v;
    }

    public static String extractImage(String buttonSpec) {
        if (!buttonSpec.contains(KEY_IMAGE_DELIM)) {
            return null;
        }
        final int start = buttonSpec.indexOf(KEY_IMAGE_DELIM);
        String subStr = buttonSpec.substring(start + 1, buttonSpec.indexOf(KEY_CODE_END));
        return subStr;
    }

    public static int extractKeycode(String buttonSpec) {
        if (!buttonSpec.contains(KEY_CODE_START)) {
            return 1;
        }
        final int start = buttonSpec.indexOf(KEY_CODE_START);
        String subStr = buttonSpec.substring(start + 1, buttonSpec.indexOf(KEY_IMAGE_DELIM));
        return Integer.parseInt(subStr);
    }

    public static float extractSize(String buttonSpec) {
        if (!buttonSpec.contains(SIZE_MOD_START)) {
            return 1;
        }
        final int sizeStart = buttonSpec.indexOf(SIZE_MOD_START);
        String sizeStr = buttonSpec.substring(sizeStart + 1, buttonSpec.indexOf(SIZE_MOD_END));
        return Float.parseFloat(sizeStr);
    }

    public static String extractButton(String buttonSpec) {
        if (!buttonSpec.contains(SIZE_MOD_START)) {
            return buttonSpec;
        }
        return buttonSpec.substring(0, buttonSpec.indexOf(SIZE_MOD_START));
    }

    private void addToDispatchers(View v) {
        if (mButtonDispatchers != null) {
            final int indexOfKey = mButtonDispatchers.indexOfKey(v.getId());
            if (indexOfKey >= 0) {
                mButtonDispatchers.valueAt(indexOfKey).addView(v);
            } else if (v instanceof ViewGroup) {
                final ViewGroup viewGroup = (ViewGroup)v;
                final int N = viewGroup.getChildCount();
                for (int i = 0; i < N; i++) {
                    addToDispatchers(viewGroup.getChildAt(i));
                }
            }
        }
    }



    private void clearViews() {
        if (mButtonDispatchers != null) {
            for (int i = 0; i < mButtonDispatchers.size(); i++) {
                mButtonDispatchers.valueAt(i).clear();
            }
        }
        clearAllChildren((ViewGroup) mRot0.findViewById(R.id.nav_buttons));
        clearAllChildren((ViewGroup) mRot90.findViewById(R.id.nav_buttons));
    }

    private void clearAllChildren(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            ((ViewGroup) group.getChildAt(i)).removeAllViews();
        }
    }
}
