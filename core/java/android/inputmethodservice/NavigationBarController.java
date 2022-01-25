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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.StatusBarManager;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Region;
import android.inputmethodservice.navigationbar.NavigationBarFrame;
import android.inputmethodservice.navigationbar.NavigationBarView;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManagerPolicyConstants;
import android.widget.FrameLayout;

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

        default void onViewInitialized() {
        }

        default void onWindowShown() {
        }

        default void onDestroy() {
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

    void onViewInitialized() {
        mImpl.onViewInitialized();
    }

    void onWindowShown() {
        mImpl.onWindowShown();
    }

    void onDestroy() {
        mImpl.onDestroy();
    }

    String toDebugString() {
        return mImpl.toDebugString();
    }

    private static final class Impl implements Callback {
        @NonNull
        private final InputMethodService mService;

        private boolean mDestroyed = false;

        private boolean mRenderGesturalNavButtons;

        @Nullable
        private NavigationBarFrame mNavigationBarFrame;
        @Nullable
        Insets mLastInsets;

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
            if (!mRenderGesturalNavButtons) {
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
                            | StatusBarManager.NAVIGATION_HINT_IME_SHOWN;
                    navigationBarView.setNavigationIconHints(hints);
                }
            } else {
                mNavigationBarFrame.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, systemInsets.bottom, Gravity.BOTTOM));
                mLastInsets = systemInsets;
            }

            mNavigationBarFrame.setBackground(null);
        }

        @Override
        public void updateTouchableInsets(@NonNull InputMethodService.Insets originalInsets,
                @NonNull ViewTreeObserver.InternalInsetsInfo dest) {
            if (!mRenderGesturalNavButtons || mNavigationBarFrame == null
                    || mService.isExtractViewShown()) {
                return;
            }

            final Insets systemInsets = getSystemInsets();
            if (systemInsets != null) {
                final Window window = mService.mWindow.getWindow();
                final View decor = window.getDecorView();
                Region touchableRegion = null;
                final View inputFrame = mService.mInputFrame;
                switch (originalInsets.touchableInsets) {
                    case ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_FRAME:
                        if (inputFrame.getVisibility() == View.VISIBLE) {
                            touchableRegion = new Region(inputFrame.getLeft(),
                                    inputFrame.getTop(), inputFrame.getRight(),
                                    inputFrame.getBottom());
                        }
                        break;
                    case ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_CONTENT:
                        if (inputFrame.getVisibility() == View.VISIBLE) {
                            touchableRegion = new Region(inputFrame.getLeft(),
                                    originalInsets.contentTopInsets, inputFrame.getRight(),
                                    inputFrame.getBottom());
                        }
                        break;
                    case ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_VISIBLE:
                        if (inputFrame.getVisibility() == View.VISIBLE) {
                            touchableRegion = new Region(inputFrame.getLeft(),
                                    originalInsets.visibleTopInsets, inputFrame.getRight(),
                                    inputFrame.getBottom());
                        }
                        break;
                    case ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION:
                        touchableRegion = new Region();
                        touchableRegion.set(originalInsets.touchableRegion);
                        break;
                }
                final Rect navBarRect = new Rect(decor.getLeft(),
                        decor.getBottom() - systemInsets.bottom,
                        decor.getRight(), decor.getBottom());
                if (touchableRegion == null) {
                    touchableRegion = new Region(navBarRect);
                } else {
                    touchableRegion.union(navBarRect);
                }

                dest.touchableRegion.set(touchableRegion);
                dest.setTouchableInsets(
                        ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);

                // TODO(b/205803355): See if we can use View#OnLayoutChangeListener().
                // TODO(b/205803355): See if we can replace DecorView#mNavigationColorViewState.view
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
                    final NavigationBarFrame that = mNavigationBarFrame;
                    that.post(() -> {
                        if (!that.isAttachedToWindow()) {
                            return;
                        }
                        final Insets currentSystemInsets = getSystemInsets();
                        if (!Objects.equals(currentSystemInsets, mLastInsets)) {
                            that.setLayoutParams(
                                    new FrameLayout.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            currentSystemInsets.bottom, Gravity.BOTTOM));
                            mLastInsets = currentSystemInsets;
                        }
                        if (decor instanceof ViewGroup) {
                            ViewGroup decorGroup = (ViewGroup) decor;
                            final View navbarBackgroundView =
                                    window.getNavigationBarBackgroundView();
                            if (navbarBackgroundView != null
                                    && decorGroup.indexOfChild(navbarBackgroundView)
                                    > decorGroup.indexOfChild(that)) {
                                decorGroup.bringChildToFront(that);
                            }
                        }
                    });
                }
            }
        }

        private boolean isGesturalNavigationEnabled() {
            final Resources resources = mService.getResources();
            if (resources == null) {
                return false;
            }
            return resources.getInteger(com.android.internal.R.integer.config_navBarInteractionMode)
                    == WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL;
        }

        @Override
        public void onViewInitialized() {
            if (mDestroyed) {
                return;
            }
            mRenderGesturalNavButtons = isGesturalNavigationEnabled();
            installNavigationBarFrameIfNecessary();
        }

        @Override
        public void onDestroy() {
            mDestroyed = true;
        }

        @Override
        public void onWindowShown() {
            if (mDestroyed || !mRenderGesturalNavButtons || mNavigationBarFrame == null) {
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
                mNavigationBarFrame.setVisibility(View.VISIBLE);
            }
        }

        @Override
        public String toDebugString() {
            return "{mRenderGesturalNavButtons=" + mRenderGesturalNavButtons + "}";
        }
    }
}
