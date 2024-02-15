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

package android.inputmethodservice;

import static android.inputmethodservice.InputMethodService.ENABLE_HIDE_IME_CAPTION_BAR;
import static android.view.WindowInsets.Type.captionBar;
import static android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;

import android.animation.ValueAnimator;
import android.annotation.FloatRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.StatusBarManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Region;
import android.inputmethodservice.navigationbar.NavigationBarFrame;
import android.inputmethodservice.navigationbar.NavigationBarView;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController.Appearance;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;

import com.android.internal.inputmethod.InputMethodNavButtonFlags;

import java.util.Objects;

/**
 * This class hides details behind {@link InputMethodService#canImeRenderGesturalNavButtons()} from
 * {@link InputMethodService}.
 *
 * <p>All the package-private methods are no-op when
 * {@link InputMethodService#canImeRenderGesturalNavButtons()} returns {@code false}.</p>
 */
final class NavigationBarController {

    private interface Callback {
        default void updateTouchableInsets(@NonNull InputMethodService.Insets originalInsets,
                @NonNull ViewTreeObserver.InternalInsetsInfo dest) {
        }

        default void onSoftInputWindowCreated(@NonNull SoftInputWindow softInputWindow) {
        }

        default void onViewInitialized() {
        }

        default void onWindowShown() {
        }

        default void onDestroy() {
        }

        default void onNavButtonFlagsChanged(@InputMethodNavButtonFlags int navButtonFlags) {
        }

        default boolean isShown() {
            return false;
        }

        default String toDebugString() {
            return "No-op implementation";
        }

        Callback NOOP = new Callback() {
        };
    }

    private final Callback mImpl;

    NavigationBarController(@NonNull InputMethodService inputMethodService) {
        mImpl = InputMethodService.canImeRenderGesturalNavButtons()
                ? new Impl(inputMethodService) : Callback.NOOP;
    }

    void updateTouchableInsets(@NonNull InputMethodService.Insets originalInsets,
            @NonNull ViewTreeObserver.InternalInsetsInfo dest) {
        mImpl.updateTouchableInsets(originalInsets, dest);
    }

    void onSoftInputWindowCreated(@NonNull SoftInputWindow softInputWindow) {
        mImpl.onSoftInputWindowCreated(softInputWindow);
    }

    void onViewInitialized() {
        mImpl.onViewInitialized();
    }

    void onWindowShown() {
        mImpl.onWindowShown();
    }

    void onDestroy() {
        mImpl.onDestroy();
    }

    void onNavButtonFlagsChanged(@InputMethodNavButtonFlags int navButtonFlags) {
        mImpl.onNavButtonFlagsChanged(navButtonFlags);
    }

    /**
     * Returns whether the IME navigation bar is currently shown.
     */
    boolean isShown() {
        return mImpl.isShown();
    }

    String toDebugString() {
        return mImpl.toDebugString();
    }

    private static final class Impl implements Callback, Window.DecorCallback {
        private static final int DEFAULT_COLOR_ADAPT_TRANSITION_TIME = 1700;

        // Copied from com.android.systemui.animation.Interpolators#LEGACY_DECELERATE
        private static final Interpolator LEGACY_DECELERATE =
                new PathInterpolator(0f, 0f, 0.2f, 1f);

        @NonNull
        private final InputMethodService mService;

        private boolean mDestroyed = false;

        private boolean mImeDrawsImeNavBar;

        @Nullable
        private NavigationBarFrame mNavigationBarFrame;
        @Nullable
        Insets mLastInsets;

        private boolean mShouldShowImeSwitcherWhenImeIsShown;

        @Appearance
        private int mAppearance;

        @FloatRange(from = 0.0f, to = 1.0f)
        private float mDarkIntensity;

        @Nullable
        private ValueAnimator mTintAnimator;

        private boolean mDrawLegacyNavigationBarBackground;

        private final Rect mTempRect = new Rect();
        private final int[] mTempPos = new int[2];

        Impl(@NonNull InputMethodService inputMethodService) {
            mService = inputMethodService;
        }

        @Nullable
        private Insets getSystemInsets() {
            if (mService.mWindow == null) {
                return null;
            }
            final View decorView = mService.mWindow.getWindow().getDecorView();
            if (decorView == null) {
                return null;
            }
            final WindowInsets windowInsets = decorView.getRootWindowInsets();
            if (windowInsets == null) {
                return null;
            }
            final Insets stableBarInsets =
                    windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars());
            return Insets.min(windowInsets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout()), stableBarInsets);
        }

        private void installNavigationBarFrameIfNecessary() {
            if (!mImeDrawsImeNavBar) {
                return;
            }
            if (mNavigationBarFrame != null) {
                return;
            }
            final View rawDecorView = mService.mWindow.getWindow().getDecorView();
            if (!(rawDecorView instanceof ViewGroup)) {
                return;
            }
            final ViewGroup decorView = (ViewGroup) rawDecorView;
            mNavigationBarFrame = decorView.findViewByPredicate(
                    NavigationBarFrame.class::isInstance);
            final Insets systemInsets = getSystemInsets();
            if (mNavigationBarFrame == null) {
                mNavigationBarFrame = new NavigationBarFrame(mService);
                LayoutInflater.from(mService).inflate(
                        com.android.internal.R.layout.input_method_navigation_bar,
                        mNavigationBarFrame);
                if (systemInsets != null) {
                    decorView.addView(mNavigationBarFrame, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            systemInsets.bottom, Gravity.BOTTOM));
                    mLastInsets = systemInsets;
                } else {
                    decorView.addView(mNavigationBarFrame);
                }
                final NavigationBarView navigationBarView = mNavigationBarFrame.findViewByPredicate(
                        NavigationBarView.class::isInstance);
                if (navigationBarView != null) {
                    // TODO(b/213337792): Support InputMethodService#setBackDisposition().
                    // TODO(b/213337792): Set NAVIGATION_HINT_IME_SHOWN only when necessary.
                    final int hints = StatusBarManager.NAVIGATION_HINT_BACK_ALT
                            | (mShouldShowImeSwitcherWhenImeIsShown
                                    ? StatusBarManager.NAVIGATION_HINT_IME_SWITCHER_SHOWN
                                    : 0);
                    navigationBarView.setNavigationIconHints(hints);
                }
            } else {
                mNavigationBarFrame.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, systemInsets.bottom, Gravity.BOTTOM));
                mLastInsets = systemInsets;
            }

            if (mDrawLegacyNavigationBarBackground) {
                mNavigationBarFrame.setBackgroundColor(Color.BLACK);
            } else {
                mNavigationBarFrame.setBackground(null);
            }

            setIconTintInternal(calculateTargetDarkIntensity(mAppearance,
                    mDrawLegacyNavigationBarBackground));

            if (ENABLE_HIDE_IME_CAPTION_BAR) {
                mNavigationBarFrame.setOnApplyWindowInsetsListener((view, insets) -> {
                    if (mNavigationBarFrame != null) {
                        boolean visible = insets.isVisible(captionBar());
                        mNavigationBarFrame.setVisibility(visible ? View.VISIBLE : View.GONE);
                    }
                    return view.onApplyWindowInsets(insets);
                });
            }
        }

        private void uninstallNavigationBarFrameIfNecessary() {
            if (mNavigationBarFrame == null) {
                return;
            }
            final ViewParent parent = mNavigationBarFrame.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(mNavigationBarFrame);
            }
            if (ENABLE_HIDE_IME_CAPTION_BAR) {
                mNavigationBarFrame.setOnApplyWindowInsetsListener(null);
            }
            mNavigationBarFrame = null;
        }

        @Override
        public void updateTouchableInsets(@NonNull InputMethodService.Insets originalInsets,
                @NonNull ViewTreeObserver.InternalInsetsInfo dest) {
            if (!mImeDrawsImeNavBar || mNavigationBarFrame == null) {
                return;
            }

            final Insets systemInsets = getSystemInsets();
            if (systemInsets != null) {
                final Window window = mService.mWindow.getWindow();
                final View decor = window.getDecorView();

                // If the extract view is shown, everything is touchable, so no need to update
                // touchable insets, but we still update normal insets below.
                if (!mService.isExtractViewShown()) {
                    Region touchableRegion = null;
                    final View inputFrame = mService.mInputFrame;
                    switch (originalInsets.touchableInsets) {
                        case ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_FRAME:
                            if (inputFrame.getVisibility() == View.VISIBLE) {
                                inputFrame.getLocationInWindow(mTempPos);
                                mTempRect.set(mTempPos[0], mTempPos[1],
                                        mTempPos[0] + inputFrame.getWidth(),
                                        mTempPos[1] + inputFrame.getHeight());
                                touchableRegion = new Region(mTempRect);
                            }
                            break;
                        case ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_CONTENT:
                            if (inputFrame.getVisibility() == View.VISIBLE) {
                                inputFrame.getLocationInWindow(mTempPos);
                                mTempRect.set(mTempPos[0], originalInsets.contentTopInsets,
                                        mTempPos[0] + inputFrame.getWidth(),
                                        mTempPos[1] + inputFrame.getHeight());
                                touchableRegion = new Region(mTempRect);
                            }
                            break;
                        case ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_VISIBLE:
                            if (inputFrame.getVisibility() == View.VISIBLE) {
                                inputFrame.getLocationInWindow(mTempPos);
                                mTempRect.set(mTempPos[0], originalInsets.visibleTopInsets,
                                        mTempPos[0] + inputFrame.getWidth(),
                                        mTempPos[1] + inputFrame.getHeight());
                                touchableRegion = new Region(mTempRect);
                            }
                            break;
                        case ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION:
                            touchableRegion = new Region();
                            touchableRegion.set(originalInsets.touchableRegion);
                            break;
                    }
                    // Hereafter "mTempRect" means a navigation bar rect.
                    mTempRect.set(decor.getLeft(), decor.getBottom() - systemInsets.bottom,
                            decor.getRight(), decor.getBottom());
                    if (touchableRegion == null) {
                        touchableRegion = new Region(mTempRect);
                    } else {
                        touchableRegion.union(mTempRect);
                    }

                    dest.touchableRegion.set(touchableRegion);
                    dest.setTouchableInsets(
                            ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
                }

                // TODO(b/215443343): See if we can use View#OnLayoutChangeListener().
                // TODO(b/215443343): See if we can replace DecorView#mNavigationColorViewState.view
                boolean zOrderChanged = false;
                if (decor instanceof ViewGroup) {
                    ViewGroup decorGroup = (ViewGroup) decor;
                    final View navbarBackgroundView = window.getNavigationBarBackgroundView();
                    zOrderChanged = navbarBackgroundView != null
                            && decorGroup.indexOfChild(navbarBackgroundView)
                            > decorGroup.indexOfChild(mNavigationBarFrame);
                }
                final boolean insetChanged = !Objects.equals(systemInsets, mLastInsets);
                if (zOrderChanged || insetChanged) {
                    scheduleRelayout();
                }
            }
        }

        private void scheduleRelayout() {
            // Capture the current frame object in case the object is replaced or cleared later.
            final NavigationBarFrame frame = mNavigationBarFrame;
            frame.post(() -> {
                if (mDestroyed) {
                    return;
                }
                if (!frame.isAttachedToWindow()) {
                    return;
                }
                final Window window = mService.mWindow.getWindow();
                if (window == null) {
                    return;
                }
                final View decor = window.peekDecorView();
                if (decor == null) {
                    return;
                }
                if (!(decor instanceof ViewGroup)) {
                    return;
                }
                final ViewGroup decorGroup = (ViewGroup) decor;
                final Insets currentSystemInsets = getSystemInsets();
                if (!Objects.equals(currentSystemInsets, mLastInsets)) {
                    frame.setLayoutParams(new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            currentSystemInsets.bottom, Gravity.BOTTOM));
                    mLastInsets = currentSystemInsets;
                }
                final View navbarBackgroundView =
                        window.getNavigationBarBackgroundView();
                if (navbarBackgroundView != null
                        && decorGroup.indexOfChild(navbarBackgroundView)
                        > decorGroup.indexOfChild(frame)) {
                    decorGroup.bringChildToFront(frame);
                }
            });
        }

        @Override
        public void onSoftInputWindowCreated(@NonNull SoftInputWindow softInputWindow) {
            final Window window = softInputWindow.getWindow();
            mAppearance = window.getSystemBarAppearance();
            window.setDecorCallback(this);
        }

        @Override
        public void onViewInitialized() {
            if (mDestroyed) {
                return;
            }
            installNavigationBarFrameIfNecessary();
        }

        @Override
        public void onDestroy() {
            if (mDestroyed) {
                return;
            }
            if (mTintAnimator != null) {
                mTintAnimator.cancel();
                mTintAnimator = null;
            }
            mDestroyed = true;
        }

        @Override
        public void onWindowShown() {
            if (mDestroyed || !mImeDrawsImeNavBar || mNavigationBarFrame == null) {
                return;
            }
            final Insets systemInsets = getSystemInsets();
            if (systemInsets != null) {
                if (!Objects.equals(systemInsets, mLastInsets)) {
                    mNavigationBarFrame.setLayoutParams(new NavigationBarFrame.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            systemInsets.bottom, Gravity.BOTTOM));
                    mLastInsets = systemInsets;
                }
                final Window window = mService.mWindow.getWindow();
                View rawDecorView = window.getDecorView();
                if (rawDecorView instanceof ViewGroup) {
                    final ViewGroup decor = (ViewGroup) rawDecorView;
                    final View navbarBackgroundView = window.getNavigationBarBackgroundView();
                    if (navbarBackgroundView != null
                            && decor.indexOfChild(navbarBackgroundView)
                            > decor.indexOfChild(mNavigationBarFrame)) {
                        decor.bringChildToFront(mNavigationBarFrame);
                    }
                }
                if (!ENABLE_HIDE_IME_CAPTION_BAR) {
                    mNavigationBarFrame.setVisibility(View.VISIBLE);
                }
            }
        }

        @Override
        public void onNavButtonFlagsChanged(@InputMethodNavButtonFlags int navButtonFlags) {
            if (mDestroyed) {
                return;
            }

            final boolean imeDrawsImeNavBar =
                    (navButtonFlags & InputMethodNavButtonFlags.IME_DRAWS_IME_NAV_BAR) != 0;
            final boolean shouldShowImeSwitcherWhenImeIsShown =
                    (navButtonFlags & InputMethodNavButtonFlags.SHOW_IME_SWITCHER_WHEN_IME_IS_SHOWN)
                    != 0;

            mImeDrawsImeNavBar = imeDrawsImeNavBar;
            final boolean prevShouldShowImeSwitcherWhenImeIsShown =
                    mShouldShowImeSwitcherWhenImeIsShown;
            mShouldShowImeSwitcherWhenImeIsShown = shouldShowImeSwitcherWhenImeIsShown;

            if (ENABLE_HIDE_IME_CAPTION_BAR) {
                mService.mWindow.getWindow().getDecorView().getWindowInsetsController()
                        .setImeCaptionBarInsetsHeight(getImeCaptionBarHeight());
            }

            if (imeDrawsImeNavBar) {
                installNavigationBarFrameIfNecessary();
                if (mNavigationBarFrame == null) {
                    return;
                }
                if (mShouldShowImeSwitcherWhenImeIsShown
                        == prevShouldShowImeSwitcherWhenImeIsShown) {
                    return;
                }
                final NavigationBarView navigationBarView = mNavigationBarFrame.findViewByPredicate(
                        NavigationBarView.class::isInstance);
                if (navigationBarView == null) {
                    return;
                }
                final int hints = StatusBarManager.NAVIGATION_HINT_BACK_ALT
                        | (shouldShowImeSwitcherWhenImeIsShown
                                ? StatusBarManager.NAVIGATION_HINT_IME_SWITCHER_SHOWN : 0);
                navigationBarView.setNavigationIconHints(hints);
            } else {
                uninstallNavigationBarFrameIfNecessary();
            }
        }

        @Override
        public void onSystemBarAppearanceChanged(@Appearance int appearance) {
            if (mDestroyed) {
                return;
            }

            mAppearance = appearance;

            if (mNavigationBarFrame == null) {
                return;
            }

            final float targetDarkIntensity = calculateTargetDarkIntensity(mAppearance,
                    mDrawLegacyNavigationBarBackground);

            if (mTintAnimator != null) {
                mTintAnimator.cancel();
            }
            mTintAnimator = ValueAnimator.ofFloat(mDarkIntensity, targetDarkIntensity);
            mTintAnimator.addUpdateListener(
                    animation -> setIconTintInternal((Float) animation.getAnimatedValue()));
            mTintAnimator.setDuration(DEFAULT_COLOR_ADAPT_TRANSITION_TIME);
            mTintAnimator.setStartDelay(0);
            mTintAnimator.setInterpolator(LEGACY_DECELERATE);
            mTintAnimator.start();
        }

        private void setIconTintInternal(float darkIntensity) {
            mDarkIntensity = darkIntensity;
            if (mNavigationBarFrame == null) {
                return;
            }
            final NavigationBarView navigationBarView =
                    mNavigationBarFrame.findViewByPredicate(NavigationBarView.class::isInstance);
            if (navigationBarView == null) {
                return;
            }
            navigationBarView.setDarkIntensity(darkIntensity);
        }

        @FloatRange(from = 0.0f, to = 1.0f)
        private static float calculateTargetDarkIntensity(@Appearance int appearance,
                boolean drawLegacyNavigationBarBackground) {
            final boolean lightNavBar = !drawLegacyNavigationBarBackground
                    && (appearance & APPEARANCE_LIGHT_NAVIGATION_BARS) != 0;
            return lightNavBar ? 1.0f : 0.0f;
        }

        @Override
        public boolean onDrawLegacyNavigationBarBackgroundChanged(
                boolean drawLegacyNavigationBarBackground) {
            if (mDestroyed) {
                return false;
            }

            if (drawLegacyNavigationBarBackground != mDrawLegacyNavigationBarBackground) {
                mDrawLegacyNavigationBarBackground = drawLegacyNavigationBarBackground;
                if (mNavigationBarFrame != null) {
                    if (mDrawLegacyNavigationBarBackground) {
                        mNavigationBarFrame.setBackgroundColor(Color.BLACK);
                    } else {
                        mNavigationBarFrame.setBackground(null);
                    }
                    scheduleRelayout();
                }
                onSystemBarAppearanceChanged(mAppearance);
            }
            return drawLegacyNavigationBarBackground;
        }

        /**
         * Returns the height of the IME caption bar if this should be shown, or {@code 0} instead.
         */
        private int getImeCaptionBarHeight() {
            return mImeDrawsImeNavBar
                    ? mService.getResources().getDimensionPixelSize(
                            com.android.internal.R.dimen.navigation_bar_frame_height)
                    : 0;
        }

        @Override
        public boolean isShown() {
            return mNavigationBarFrame != null
                    && mNavigationBarFrame.getVisibility() == View.VISIBLE;
        }

        @Override
        public String toDebugString() {
            return "{mImeDrawsImeNavBar=" + mImeDrawsImeNavBar
                    + " mNavigationBarFrame=" + mNavigationBarFrame
                    + " mShouldShowImeSwitcherWhenImeIsShown="
                    + mShouldShowImeSwitcherWhenImeIsShown
                    + " mAppearance=0x" + Integer.toHexString(mAppearance)
                    + " mDarkIntensity=" + mDarkIntensity
                    + " mDrawLegacyNavigationBarBackground=" + mDrawLegacyNavigationBarBackground
                    + "}";
        }
    }
}
