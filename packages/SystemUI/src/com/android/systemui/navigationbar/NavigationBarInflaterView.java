/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.navigationbar;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.Icon;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Space;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.navigationbar.buttons.ButtonDispatcher;
import com.android.systemui.navigationbar.buttons.KeyButtonView;
import com.android.systemui.navigationbar.buttons.ReverseLinearLayout;
import com.android.systemui.navigationbar.buttons.ReverseLinearLayout.ReverseRelativeLayout;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.shared.system.QuickStepContract;

import java.io.PrintWriter;
import java.util.Objects;

public class NavigationBarInflaterView extends FrameLayout
        implements NavigationModeController.ModeChangedListener {

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
    public static final String HOME_HANDLE = "home_handle";
    public static final String KEY = "key";
    public static final String LEFT = "left";
    public static final String RIGHT = "right";
    public static final String CONTEXTUAL = "contextual";
    public static final String IME_SWITCHER = "ime_switcher";

    public static final String GRAVITY_SEPARATOR = ";";
    public static final String BUTTON_SEPARATOR = ",";

    public static final String SIZE_MOD_START = "[";
    public static final String SIZE_MOD_END = "]";

    public static final String KEY_CODE_START = "(";
    public static final String KEY_IMAGE_DELIM = ":";
    public static final String KEY_CODE_END = ")";
    private static final String WEIGHT_SUFFIX = "W";
    private static final String WEIGHT_CENTERED_SUFFIX = "WC";
    private static final String ABSOLUTE_SUFFIX = "A";
    private static final String ABSOLUTE_VERTICAL_CENTERED_SUFFIX = "C";

    protected LayoutInflater mLayoutInflater;
    protected LayoutInflater mLandscapeInflater;

    protected FrameLayout mHorizontal;
    protected FrameLayout mVertical;

    @VisibleForTesting
    SparseArray<ButtonDispatcher> mButtonDispatchers;
    private String mCurrentLayout;

    private View mLastPortrait;
    private View mLastLandscape;

    private boolean mIsVertical;
    private boolean mAlternativeOrder;

    private OverviewProxyService mOverviewProxyService;
    private int mNavBarMode = NAV_BAR_MODE_3BUTTON;

    public NavigationBarInflaterView(Context context, AttributeSet attrs) {
        super(context, attrs);
        createInflaters();
        mOverviewProxyService = Dependency.get(OverviewProxyService.class);
        mNavBarMode = Dependency.get(NavigationModeController.class).addListener(this);
    }

    @VisibleForTesting
    void createInflaters() {
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
        mHorizontal = (FrameLayout) mLayoutInflater.inflate(R.layout.navigation_layout,
                this /* root */, false /* attachToRoot */);
        addView(mHorizontal);
        mVertical = (FrameLayout) mLayoutInflater.inflate(R.layout.navigation_layout_vertical,
                this /* root */, false /* attachToRoot */);
        addView(mVertical);
        updateAlternativeOrder();
    }

    protected String getDefaultLayout() {
        final int defaultResource = QuickStepContract.isGesturalMode(mNavBarMode)
                ? R.string.config_navBarLayoutHandle
                : mOverviewProxyService.shouldShowSwipeUpUI()
                        ? R.string.config_navBarLayoutQuickstep
                        : R.string.config_navBarLayout;
        return getContext().getString(defaultResource);
    }

    @Override
    public void onNavigationModeChanged(int mode) {
        mNavBarMode = mode;
    }

    @Override
    protected void onDetachedFromWindow() {
        Dependency.get(NavigationModeController.class).removeListener(this);
        super.onDetachedFromWindow();
    }

    public void onLikelyDefaultLayoutChange() {
        // Reevaluate new layout
        final String newValue = getDefaultLayout();
        if (!Objects.equals(mCurrentLayout, newValue)) {
            clearViews();
            inflateLayout(newValue);
        }
    }

    public void setButtonDispatchers(SparseArray<ButtonDispatcher> buttonDispatchers) {
        mButtonDispatchers = buttonDispatchers;
        clearDispatcherViews();
        for (int i = 0; i < buttonDispatchers.size(); i++) {
            initiallyFill(buttonDispatchers.valueAt(i));
        }
    }

    void updateButtonDispatchersCurrentView() {
        if (mButtonDispatchers != null) {
            View view = mIsVertical ? mVertical : mHorizontal;
            for (int i = 0; i < mButtonDispatchers.size(); i++) {
                final ButtonDispatcher dispatcher = mButtonDispatchers.valueAt(i);
                dispatcher.setCurrentView(view);
            }
        }
    }

    void setVertical(boolean vertical) {
        if (vertical != mIsVertical) {
            mIsVertical = vertical;
        }
    }

    void setAlternativeOrder(boolean alternativeOrder) {
        if (alternativeOrder != mAlternativeOrder) {
            mAlternativeOrder = alternativeOrder;
            updateAlternativeOrder();
        }
    }

    private void updateAlternativeOrder() {
        updateAlternativeOrder(mHorizontal.findViewById(R.id.ends_group));
        updateAlternativeOrder(mHorizontal.findViewById(R.id.center_group));
        updateAlternativeOrder(mVertical.findViewById(R.id.ends_group));
        updateAlternativeOrder(mVertical.findViewById(R.id.center_group));
    }

    private void updateAlternativeOrder(View v) {
        if (v instanceof ReverseLinearLayout) {
            ((ReverseLinearLayout) v).setAlternativeOrder(mAlternativeOrder);
        }
    }

    private void initiallyFill(ButtonDispatcher buttonDispatcher) {
        addAll(buttonDispatcher, mHorizontal.findViewById(R.id.ends_group));
        addAll(buttonDispatcher, mHorizontal.findViewById(R.id.center_group));
        addAll(buttonDispatcher, mVertical.findViewById(R.id.ends_group));
        addAll(buttonDispatcher, mVertical.findViewById(R.id.center_group));
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
        inflateButtons(start, mHorizontal.findViewById(R.id.ends_group),
                false /* landscape */, true /* start */);
        inflateButtons(start, mVertical.findViewById(R.id.ends_group),
                true /* landscape */, true /* start */);

        inflateButtons(center, mHorizontal.findViewById(R.id.center_group),
                false /* landscape */, false /* start */);
        inflateButtons(center, mVertical.findViewById(R.id.center_group),
                true /* landscape */, false /* start */);

        addGravitySpacer(mHorizontal.findViewById(R.id.ends_group));
        addGravitySpacer(mVertical.findViewById(R.id.ends_group));

        inflateButtons(end, mHorizontal.findViewById(R.id.ends_group),
                false /* landscape */, false /* start */);
        inflateButtons(end, mVertical.findViewById(R.id.ends_group),
                true /* landscape */, false /* start */);

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
        if (v instanceof ReverseRelativeLayout) {
            accessibilityView = ((ReverseRelativeLayout) v).getChildAt(0);
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

        if (sizeStr.contains(WEIGHT_SUFFIX) || sizeStr.contains(ABSOLUTE_SUFFIX)) {
            // To support gravity, wrap in RelativeLayout and apply gravity to it.
            // Children wanting to use gravity must be smaller then the frame.
            ReverseRelativeLayout frame = new ReverseRelativeLayout(mContext);
            LayoutParams childParams = new LayoutParams(v.getLayoutParams());

            // Compute gravity to apply
            int gravity = (landscape) ? (start ? Gravity.TOP : Gravity.BOTTOM)
                    : (start ? Gravity.START : Gravity.END);
            if (sizeStr.endsWith(WEIGHT_CENTERED_SUFFIX)) {
                gravity = Gravity.CENTER;
            } else if (sizeStr.endsWith(ABSOLUTE_VERTICAL_CENTERED_SUFFIX)) {
                gravity = Gravity.CENTER_VERTICAL;
            }

            // Set default gravity, flipped if needed in reversed layouts (270 RTL and 90 LTR)
            frame.setDefaultGravity(gravity);
            frame.setGravity(gravity); // Apply gravity to root

            frame.addView(v, childParams);

            if (sizeStr.contains(WEIGHT_SUFFIX)) {
                // Use weighting to set the width of the frame
                float weight = Float.parseFloat(
                        sizeStr.substring(0, sizeStr.indexOf(WEIGHT_SUFFIX)));
                frame.setLayoutParams(new LinearLayout.LayoutParams(0, MATCH_PARENT, weight));
            } else {
                int width = (int) convertDpToPx(mContext,
                        Float.parseFloat(sizeStr.substring(0, sizeStr.indexOf(ABSOLUTE_SUFFIX))));
                frame.setLayoutParams(new LinearLayout.LayoutParams(width, MATCH_PARENT));
            }

            // Ensure ripples can be drawn outside bounds
            frame.setClipChildren(false);
            frame.setClipToPadding(false);

            return frame;
        }

        float size = Float.parseFloat(sizeStr);
        ViewGroup.LayoutParams params = v.getLayoutParams();
        params.width = (int) (params.width * size);
        return v;
    }

    View createView(String buttonSpec, ViewGroup parent, LayoutInflater inflater) {
        View v = null;
        String button = extractButton(buttonSpec);
        if (LEFT.equals(button)) {
            button = extractButton(NAVSPACE);
        } else if (RIGHT.equals(button)) {
            button = extractButton(MENU_IME_ROTATE);
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
        } else if (CONTEXTUAL.equals(button)) {
            v = inflater.inflate(R.layout.contextual, parent, false);
        } else if (HOME_HANDLE.equals(button)) {
            v = inflater.inflate(R.layout.home_handle, parent, false);
        } else if (IME_SWITCHER.equals(button)) {
            v = inflater.inflate(R.layout.ime_switcher, parent, false);
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

    private void clearDispatcherViews() {
        if (mButtonDispatchers != null) {
            for (int i = 0; i < mButtonDispatchers.size(); i++) {
                mButtonDispatchers.valueAt(i).clear();
            }
        }
    }

    private void clearViews() {
        clearDispatcherViews();
        clearAllChildren(mHorizontal.findViewById(R.id.nav_buttons));
        clearAllChildren(mVertical.findViewById(R.id.nav_buttons));
    }

    private void clearAllChildren(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            ((ViewGroup) group.getChildAt(i)).removeAllViews();
        }
    }

    private static float convertDpToPx(Context context, float dp) {
        return dp * context.getResources().getDisplayMetrics().density;
    }

    public void dump(PrintWriter pw) {
        pw.println("NavigationBarInflaterView");
        pw.println("  mCurrentLayout: " + mCurrentLayout);
    }
}
