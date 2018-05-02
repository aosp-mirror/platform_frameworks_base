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
import android.graphics.drawable.Icon;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.Display.Mode;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Space;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.plugins.PluginManager;
import com.android.systemui.plugins.statusbar.phone.NavBarButtonProvider;
import com.android.systemui.statusbar.phone.ReverseLinearLayout.ReverseFrameLayout;
import com.android.systemui.statusbar.policy.KeyButtonView;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

public class NavigationBarInflaterView extends FrameLayout
        implements Tunable, PluginListener<NavBarButtonProvider> {

    private static final String TAG = "NavBarInflater";

    public static final String NAV_BAR_VIEWS = "sysui_nav_bar";
    public static final String NAV_BAR_LEFT = "sysui_nav_bar_left";
    public static final String NAV_BAR_RIGHT = "sysui_nav_bar_right";

    public static final String MENU_IME_ROTATE = "menu_ime";
    public static final String BACK = "back";
    public static final String HOME = "home";
    public static final String RECENT = "recent";
    public static final String NAVSPACE = "space";
    public static final String CLIPBOARD = "clipboard";
    public static final String KEY = "key";
    public static final String LEFT = "left";
    public static final String RIGHT = "right";

    public static final String GRAVITY_SEPARATOR = ";";
    public static final String BUTTON_SEPARATOR = ",";

    public static final String SIZE_MOD_START = "[";
    public static final String SIZE_MOD_END = "]";

    public static final String KEY_CODE_START = "(";
    public static final String KEY_IMAGE_DELIM = ":";
    public static final String KEY_CODE_END = ")";
    private static final String WEIGHT_SUFFIX = "W";
    private static final String WEIGHT_CENTERED_SUFFIX = "WC";

    private final List<NavBarButtonProvider> mPlugins = new ArrayList<>();
    private final Display mDisplay;

    protected LayoutInflater mLayoutInflater;
    protected LayoutInflater mLandscapeInflater;

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
        createInflaters();
        mDisplay = ((WindowManager)
                context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        Mode displayMode = mDisplay.getMode();
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
    }

    protected String getDefaultLayout() {
        return mContext.getString(R.string.config_navBarLayout);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Dependency.get(TunerService.class).addTunable(this, NAV_BAR_VIEWS, NAV_BAR_LEFT,
                NAV_BAR_RIGHT);
        Dependency.get(PluginManager.class).addPluginListener(this,
                NavBarButtonProvider.class, true /* Allow multiple */);
    }

    @Override
    protected void onDetachedFromWindow() {
        Dependency.get(TunerService.class).removeTunable(this);
        Dependency.get(PluginManager.class).removePluginListener(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (NAV_BAR_VIEWS.equals(key)) {
            if (!Objects.equals(mCurrentLayout, newValue)) {
                clearViews();
                inflateLayout(newValue);
            }
        } else if (NAV_BAR_LEFT.equals(key) || NAV_BAR_RIGHT.equals(key)) {
            clearViews();
            inflateLayout(mCurrentLayout);
        }
    }

    public void setButtonDispatchers(SparseArray<ButtonDispatcher> buttonDispatchers) {
        mButtonDispatchers = buttonDispatchers;
        for (int i = 0; i < buttonDispatchers.size(); i++) {
            initiallyFill(buttonDispatchers.valueAt(i));
        }
    }

    public void updateButtonDispatchersCurrentView() {
        if (mButtonDispatchers != null) {
            final int rotation = mDisplay.getRotation();
            final View view = rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180
                    ? mRot0 : mRot90;
            for (int i = 0; i < mButtonDispatchers.size(); i++) {
                mButtonDispatchers.valueAt(i).setCurrentView(view);
            }
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
            }
            if (parent.getChildAt(i) instanceof ViewGroup) {
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
        if (sets.length != 3) {
            Log.d(TAG, "Invalid layout.");
            newLayout = getDefaultLayout();
            sets = newLayout.split(GRAVITY_SEPARATOR, 3);
        }
        String[] start = sets[0].split(BUTTON_SEPARATOR);
        String[] center = sets[1].split(BUTTON_SEPARATOR);
        String[] end = sets[2].split(BUTTON_SEPARATOR);
        // Inflate these in start to end order or accessibility traversal will be messed up.
        inflateButtons(start, mRot0.findViewById(R.id.ends_group), isRot0Landscape, true);
        inflateButtons(start, mRot90.findViewById(R.id.ends_group), !isRot0Landscape, true);

        inflateButtons(center, mRot0.findViewById(R.id.center_group), isRot0Landscape, false);
        inflateButtons(center, mRot90.findViewById(R.id.center_group), !isRot0Landscape, false);

        addGravitySpacer(mRot0.findViewById(R.id.ends_group));
        addGravitySpacer(mRot90.findViewById(R.id.ends_group));

        inflateButtons(end, mRot0.findViewById(R.id.ends_group), isRot0Landscape, false);
        inflateButtons(end, mRot90.findViewById(R.id.ends_group), !isRot0Landscape, false);

        updateButtonDispatchersCurrentView();
    }

    private void addGravitySpacer(LinearLayout layout) {
        layout.addView(new Space(mContext), new LinearLayout.LayoutParams(0, 0, 1));
    }

    private void inflateButtons(String[] buttons, ViewGroup parent, boolean landscape,
            boolean start) {
        for (int i = 0; i < buttons.length; i++) {
            inflateButton(buttons[i], parent, landscape, start);
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
    protected View inflateButton(String buttonSpec, ViewGroup parent, boolean landscape,
            boolean start) {
        LayoutInflater inflater = landscape ? mLandscapeInflater : mLayoutInflater;
        View v = createView(buttonSpec, parent, inflater);
        if (v == null) return null;

        v = applySize(v, buttonSpec, landscape, start);
        parent.addView(v);
        addToDispatchers(v);
        View lastView = landscape ? mLastLandscape : mLastPortrait;
        View accessibilityView = v;
        if (v instanceof ReverseFrameLayout) {
            accessibilityView = ((ReverseFrameLayout) v).getChildAt(0);
        }
        if (lastView != null) {
            accessibilityView.setAccessibilityTraversalAfter(lastView.getId());
        }
        if (landscape) {
            mLastLandscape = accessibilityView;
        } else {
            mLastPortrait = accessibilityView;
        }
        return v;
    }

    private View applySize(View v, String buttonSpec, boolean landscape, boolean start) {
        String sizeStr = extractSize(buttonSpec);
        if (sizeStr == null) return v;

        if (sizeStr.contains(WEIGHT_SUFFIX)) {
            float weight = Float.parseFloat(sizeStr.substring(0, sizeStr.indexOf(WEIGHT_SUFFIX)));
            FrameLayout frame = new ReverseFrameLayout(mContext);
            LayoutParams childParams = new LayoutParams(v.getLayoutParams());
            if (sizeStr.endsWith(WEIGHT_CENTERED_SUFFIX)) {
                childParams.gravity = Gravity.CENTER;
            } else {
                childParams.gravity = landscape ? (start ? Gravity.BOTTOM : Gravity.TOP)
                        : (start ? Gravity.START : Gravity.END);
            }
            frame.addView(v, childParams);
            frame.setLayoutParams(new LinearLayout.LayoutParams(0, MATCH_PARENT, weight));
            frame.setClipChildren(false);
            frame.setClipToPadding(false);
            return frame;
        }
        float size = Float.parseFloat(sizeStr);
        ViewGroup.LayoutParams params = v.getLayoutParams();
        params.width = (int) (params.width * size);
        return v;
    }

    private View createView(String buttonSpec, ViewGroup parent, LayoutInflater inflater) {
        View v = null;
        String button = extractButton(buttonSpec);
        if (LEFT.equals(button)) {
            String s = Dependency.get(TunerService.class).getValue(NAV_BAR_LEFT, NAVSPACE);
            button = extractButton(s);
        } else if (RIGHT.equals(button)) {
            String s = Dependency.get(TunerService.class).getValue(NAV_BAR_RIGHT, MENU_IME_ROTATE);
            button = extractButton(s);
        }
        // Let plugins go first so they can override a standard view if they want.
        for (NavBarButtonProvider provider : mPlugins) {
            v = provider.createView(buttonSpec, parent);
            if (v != null) return v;
        }
        if (HOME.equals(button)) {
            v = inflater.inflate(R.layout.home, parent, false);
        } else if (BACK.equals(button)) {
            v = inflater.inflate(R.layout.back, parent, false);
        } else if (RECENT.equals(button)) {
            v = inflater.inflate(R.layout.recent_apps, parent, false);
        } else if (MENU_IME_ROTATE.equals(button)) {
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
                if (uri.contains(":")) {
                    ((KeyButtonView) v).loadAsync(Icon.createWithContentUri(uri));
                } else if (uri.contains("/")) {
                    int index = uri.indexOf('/');
                    String pkg = uri.substring(0, index);
                    int id = Integer.parseInt(uri.substring(index + 1));
                    ((KeyButtonView) v).loadAsync(Icon.createWithResource(pkg, id));
                }
            }
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

    public static String extractSize(String buttonSpec) {
        if (!buttonSpec.contains(SIZE_MOD_START)) {
            return null;
        }
        final int sizeStart = buttonSpec.indexOf(SIZE_MOD_START);
        return buttonSpec.substring(sizeStart + 1, buttonSpec.indexOf(SIZE_MOD_END));
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
            }
            if (v instanceof ViewGroup) {
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
        clearAllChildren(mRot0.findViewById(R.id.nav_buttons));
        clearAllChildren(mRot90.findViewById(R.id.nav_buttons));
    }

    private void clearAllChildren(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            ((ViewGroup) group.getChildAt(i)).removeAllViews();
        }
    }

    @Override
    public void onPluginConnected(NavBarButtonProvider plugin, Context context) {
        mPlugins.add(plugin);
        clearViews();
        inflateLayout(mCurrentLayout);
    }

    @Override
    public void onPluginDisconnected(NavBarButtonProvider plugin) {
        mPlugins.remove(plugin);
        clearViews();
        inflateLayout(mCurrentLayout);
    }
}
