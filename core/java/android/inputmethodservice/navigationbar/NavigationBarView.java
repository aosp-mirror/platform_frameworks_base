/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.inputmethodservice.navigationbar;

import static android.inputmethodservice.navigationbar.NavigationBarConstants.DARK_MODE_ICON_COLOR_SINGLE_TONE;
import static android.inputmethodservice.navigationbar.NavigationBarConstants.LIGHT_MODE_ICON_COLOR_SINGLE_TONE;
import static android.inputmethodservice.navigationbar.NavigationBarConstants.NAVBAR_BACK_BUTTON_IME_OFFSET;
import static android.inputmethodservice.navigationbar.NavigationBarUtils.dpToPx;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.annotation.DrawableRes;
import android.annotation.FloatRange;
import android.annotation.NonNull;
import android.app.StatusBarManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.Flags;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;

import java.util.function.Consumer;

/**
 * @hide
 */
public final class NavigationBarView extends FrameLayout {
    private static final boolean DEBUG = false;
    private static final String TAG = "NavBarView";

    // Copied from com.android.systemui.animation.Interpolators#FAST_OUT_SLOW_IN
    private static final Interpolator FAST_OUT_SLOW_IN = new PathInterpolator(0.4f, 0f, 0.2f, 1f);

    // The current view is always mHorizontal.
    View mCurrentView = null;
    private View mHorizontal;

    private int mCurrentRotation = -1;

    int mDisabledFlags = 0;
    int mNavigationIconHints = StatusBarManager.NAVIGATION_HINT_BACK_ALT;
    private final int mNavBarMode = NAV_BAR_MODE_GESTURAL;

    private KeyButtonDrawable mBackIcon;
    private KeyButtonDrawable mImeSwitcherIcon;
    private Context mLightContext;
    private final int mLightIconColor;
    private final int mDarkIconColor;

    private final android.inputmethodservice.navigationbar.DeadZone mDeadZone;
    private boolean mDeadZoneConsuming = false;

    private final SparseArray<ButtonDispatcher> mButtonDispatchers = new SparseArray<>();
    private Configuration mConfiguration;
    private Configuration mTmpLastConfiguration;

    private NavigationBarInflaterView mNavigationInflaterView;

    /**
     * Interface definition for callbacks to be invoked when navigation bar buttons are clicked.
     */
    public interface ButtonClickListener {

        /**
         * Called when the IME switch button is clicked.
         *
         * @param v The view that was clicked.
         */
        void onImeSwitchButtonClick(View v);

        /**
         * Called when the IME switch button has been clicked and held.
         *
         * @param v The view that was clicked and held.
         *
         * @return true if the callback consumed the long click, false otherwise.
         */
        boolean onImeSwitchButtonLongClick(View v);
    }

    public NavigationBarView(Context context, AttributeSet attrs) {
        super(context, attrs);

        mLightContext = context;
        mLightIconColor = LIGHT_MODE_ICON_COLOR_SINGLE_TONE;
        mDarkIconColor = DARK_MODE_ICON_COLOR_SINGLE_TONE;

        mConfiguration = new Configuration();
        mTmpLastConfiguration = new Configuration();
        mConfiguration.updateFrom(context.getResources().getConfiguration());

        mButtonDispatchers.put(com.android.internal.R.id.input_method_nav_back,
                new ButtonDispatcher(com.android.internal.R.id.input_method_nav_back));
        mButtonDispatchers.put(com.android.internal.R.id.input_method_nav_ime_switcher,
                new ButtonDispatcher(com.android.internal.R.id.input_method_nav_ime_switcher));
        mButtonDispatchers.put(com.android.internal.R.id.input_method_nav_home_handle,
                new ButtonDispatcher(com.android.internal.R.id.input_method_nav_home_handle));

        mDeadZone = new android.inputmethodservice.navigationbar.DeadZone(this);
    }

    /**
     * Prepares the navigation bar buttons to be used and sets the on click listeners.
     *
     * @param listener The listener used to handle the clicks on the navigation bar buttons.
     */
    public void prepareNavButtons(@NonNull ButtonClickListener listener) {
        getBackButton().setLongClickable(false);

        if (Flags.imeSwitcherRevamp()) {
            final var imeSwitchButton = getImeSwitchButton();
            imeSwitchButton.setLongClickable(true);
            imeSwitchButton.setOnClickListener(listener::onImeSwitchButtonClick);
            imeSwitchButton.setOnLongClickListener(listener::onImeSwitchButtonLongClick);
        } else {
            final ButtonDispatcher imeSwitchButton = getImeSwitchButton();
            imeSwitchButton.setLongClickable(false);
            imeSwitchButton.setOnClickListener(view -> view.getContext()
                    .getSystemService(InputMethodManager.class).showInputMethodPicker());
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return shouldDeadZoneConsumeTouchEvents(event) || super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        shouldDeadZoneConsumeTouchEvents(event);
        return super.onTouchEvent(event);
    }

    private boolean shouldDeadZoneConsumeTouchEvents(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mDeadZoneConsuming = false;
        }
        if (mDeadZone.onTouchEvent(event) || mDeadZoneConsuming) {
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    mDeadZoneConsuming = true;
                    break;
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_UP:
                    mDeadZoneConsuming = false;
                    break;
            }
            return true;
        }
        return false;
    }

    public View getCurrentView() {
        return mCurrentView;
    }

    /**
     * Applies {@code consumer} to each of the nav bar views.
     */
    public void forEachView(Consumer<View> consumer) {
        if (mHorizontal != null) {
            consumer.accept(mHorizontal);
        }
    }

    public ButtonDispatcher getBackButton() {
        return mButtonDispatchers.get(com.android.internal.R.id.input_method_nav_back);
    }

    public ButtonDispatcher getImeSwitchButton() {
        return mButtonDispatchers.get(com.android.internal.R.id.input_method_nav_ime_switcher);
    }

    public ButtonDispatcher getHomeHandle() {
        return mButtonDispatchers.get(com.android.internal.R.id.input_method_nav_home_handle);
    }

    public SparseArray<ButtonDispatcher> getButtonDispatchers() {
        return mButtonDispatchers;
    }

    private void reloadNavIcons() {
        updateIcons(Configuration.EMPTY);
    }

    private void updateIcons(Configuration oldConfig) {
        final boolean orientationChange = oldConfig.orientation != mConfiguration.orientation;
        final boolean densityChange = oldConfig.densityDpi != mConfiguration.densityDpi;
        final boolean dirChange =
                oldConfig.getLayoutDirection() != mConfiguration.getLayoutDirection();

        if (densityChange || dirChange) {
            final int switcherResId = Flags.imeSwitcherRevamp()
                    ? com.android.internal.R.drawable.ic_ime_switcher_new
                    : com.android.internal.R.drawable.ic_ime_switcher;

            mImeSwitcherIcon = getDrawable(switcherResId);
        }
        if (orientationChange || densityChange || dirChange) {
            mBackIcon = getBackDrawable();
        }
    }

    private KeyButtonDrawable getBackDrawable() {
        KeyButtonDrawable drawable = getDrawable(com.android.internal.R.drawable.ic_ime_nav_back);
        orientBackButton(drawable);
        return drawable;
    }

    /**
     * @return whether this nav bar mode is edge to edge
     */
    public static boolean isGesturalMode(int mode) {
        return mode == NAV_BAR_MODE_GESTURAL;
    }

    private void orientBackButton(KeyButtonDrawable drawable) {
        final boolean useAltBack =
                (mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) != 0;
        final boolean isRtl = mConfiguration.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        float degrees = useAltBack ? (isRtl ? 90 : -90) : 0;
        if (drawable.getRotation() == degrees) {
            return;
        }

        if (isGesturalMode(mNavBarMode)) {
            drawable.setRotation(degrees);
            return;
        }

        // Animate the back button's rotation to the new degrees and only in portrait move up the
        // back button to line up with the other buttons
        float targetY = useAltBack
                ? -dpToPx(NAVBAR_BACK_BUTTON_IME_OFFSET, getResources())
                : 0;
        ObjectAnimator navBarAnimator = ObjectAnimator.ofPropertyValuesHolder(drawable,
                PropertyValuesHolder.ofFloat(KeyButtonDrawable.KEY_DRAWABLE_ROTATE, degrees),
                PropertyValuesHolder.ofFloat(KeyButtonDrawable.KEY_DRAWABLE_TRANSLATE_Y, targetY));
        navBarAnimator.setInterpolator(FAST_OUT_SLOW_IN);
        navBarAnimator.setDuration(200);
        navBarAnimator.start();
    }

    private KeyButtonDrawable getDrawable(@DrawableRes int icon) {
        return KeyButtonDrawable.create(mLightContext, mLightIconColor, mDarkIconColor, icon,
                true /* hasShadow */, null /* ovalBackgroundColor */);
    }

    @Override
    public void setLayoutDirection(int layoutDirection) {
        reloadNavIcons();

        super.setLayoutDirection(layoutDirection);
    }

    /**
     * Updates the navigation icons based on {@code hints}.
     *
     * @param hints bit flags defined in {@link StatusBarManager}.
     */
    public void setNavigationIconHints(int hints) {
        if (hints == mNavigationIconHints) return;
        final boolean newBackAlt = (hints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) != 0;
        final boolean oldBackAlt =
                (mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) != 0;
        if (newBackAlt != oldBackAlt) {
            //onImeVisibilityChanged(newBackAlt);
        }

        if (DEBUG) {
            android.widget.Toast.makeText(getContext(), "Navigation icon hints = " + hints, 500)
                    .show();
        }
        mNavigationIconHints = hints;
        updateNavButtonIcons();
    }

    private void updateNavButtonIcons() {
        // We have to replace or restore the back and home button icons when exiting or entering
        // carmode, respectively. Recents are not available in CarMode in nav bar so change
        // to recent icon is not required.
        KeyButtonDrawable backIcon = mBackIcon;
        orientBackButton(backIcon);
        getBackButton().setImageDrawable(backIcon);

        getImeSwitchButton().setImageDrawable(mImeSwitcherIcon);

        // Update IME button visibility, a11y and rotate button always overrides the appearance
        final boolean imeSwitcherVisible =
                (mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_IME_SWITCHER_SHOWN) != 0;
        getImeSwitchButton().setVisibility(imeSwitcherVisible ? View.VISIBLE : View.INVISIBLE);

        getBackButton().setVisibility(View.VISIBLE);
        getHomeHandle().setVisibility(View.INVISIBLE);

        // We used to be reporting the touch regions via notifyActiveTouchRegions() here.
        // TODO(b/215593010): Consider taking care of this in the Launcher side.
    }

    private Display getContextDisplay() {
        return getContext().getDisplay();
    }

    @Override
    public void onFinishInflate() {
        super.onFinishInflate();
        mNavigationInflaterView = findViewById(com.android.internal.R.id.input_method_nav_inflater);
        mNavigationInflaterView.setButtonDispatchers(mButtonDispatchers);

        updateOrientationViews();
        reloadNavIcons();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        mDeadZone.onDraw(canvas);
        super.onDraw(canvas);
    }

    private void updateOrientationViews() {
        mHorizontal = findViewById(com.android.internal.R.id.input_method_nav_horizontal);

        updateCurrentView();
    }

    private void updateCurrentView() {
        resetViews();
        mCurrentView = mHorizontal;
        mCurrentView.setVisibility(View.VISIBLE);
        mCurrentRotation = getContextDisplay().getRotation();
        mNavigationInflaterView.setAlternativeOrder(mCurrentRotation == Surface.ROTATION_90);
        mNavigationInflaterView.updateButtonDispatchersCurrentView();
    }

    private void resetViews() {
        mHorizontal.setVisibility(View.GONE);
    }

    private void reorient() {
        updateCurrentView();

        final android.inputmethodservice.navigationbar.NavigationBarFrame frame =
                getRootView().findViewByPredicate(view -> view instanceof NavigationBarFrame);
        frame.setDeadZone(mDeadZone);
        mDeadZone.onConfigurationChanged(mCurrentRotation);

        if (DEBUG) {
            Log.d(TAG, "reorient(): rot=" + mCurrentRotation);
        }

        // Resolve layout direction if not resolved since components changing layout direction such
        // as changing languages will recreate this view and the direction will be resolved later
        if (!isLayoutDirectionResolved()) {
            resolveLayoutDirection();
        }
        updateNavButtonIcons();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mTmpLastConfiguration.updateFrom(mConfiguration);
        final int changes = mConfiguration.updateFrom(newConfig);

        updateIcons(mTmpLastConfiguration);
        if (mTmpLastConfiguration.densityDpi != mConfiguration.densityDpi
                || mTmpLastConfiguration.getLayoutDirection()
                        != mConfiguration.getLayoutDirection()) {
            // If car mode or density changes, we need to reset the icons.
            updateNavButtonIcons();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // This needs to happen first as it can changed the enabled state which can affect whether
        // the back button is visible
        requestApplyInsets();
        reorient();
        updateNavButtonIcons();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        for (int i = 0; i < mButtonDispatchers.size(); ++i) {
            mButtonDispatchers.valueAt(i).onDestroy();
        }
    }

    /**
     * Updates the dark intensity.
     *
     * @param intensity The intensity of darkness from {@code 0.0f} to {@code 1.0f}.
     */
    public void setDarkIntensity(@FloatRange(from = 0.0f, to = 1.0f) float intensity) {
        for (int i = 0; i < mButtonDispatchers.size(); ++i) {
            mButtonDispatchers.valueAt(i).setDarkIntensity(intensity);
        }
    }
}
