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

package com.android.systemui.accessibility.floatingmenu;

import android.annotation.IntDef;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.wm.shell.bubbles.DismissView;
import com.android.wm.shell.common.magnetictarget.MagnetizedObject;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * The basic interactions with the child view {@link MenuView}.
 */
@SuppressLint("ViewConstructor")
class MenuViewLayer extends FrameLayout {
    private final MenuView mMenuView;
    private final DismissView mDismissView;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final IAccessibilityFloatingMenu mFloatingMenu;
    private final DismissAnimationController mDismissAnimationController;

    @IntDef({
            LayerIndex.MENU_VIEW,
            LayerIndex.DISMISS_VIEW
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface LayerIndex {
        int MENU_VIEW = 0;
        int DISMISS_VIEW = 1;
    }

    @VisibleForTesting
    final Runnable mDismissMenuAction = new Runnable() {
        @Override
        public void run() {
            Settings.Secure.putString(getContext().getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS, /* value= */ "");
            mFloatingMenu.hide();
        }
    };

    MenuViewLayer(@NonNull Context context, WindowManager windowManager,
            IAccessibilityFloatingMenu floatingMenu) {
        super(context);

        mFloatingMenu = floatingMenu;

        final MenuViewModel menuViewModel = new MenuViewModel(context);
        final MenuViewAppearance menuViewAppearance = new MenuViewAppearance(context,
                windowManager);
        mMenuView = new MenuView(context, menuViewModel, menuViewAppearance);
        final MenuAnimationController menuAnimationController =
                mMenuView.getMenuAnimationController();

        mDismissView = new DismissView(context);
        mDismissAnimationController = new DismissAnimationController(mDismissView, mMenuView);
        mDismissAnimationController.setMagnetListener(new MagnetizedObject.MagnetListener() {
            @Override
            public void onStuckToTarget(@NonNull MagnetizedObject.MagneticTarget target) {
                mDismissAnimationController.animateDismissMenu(/* scaleUp= */ true);
            }

            @Override
            public void onUnstuckFromTarget(@NonNull MagnetizedObject.MagneticTarget target,
                    float velocityX, float velocityY, boolean wasFlungOut) {
                mDismissAnimationController.animateDismissMenu(/* scaleUp= */ false);
            }

            @Override
            public void onReleasedInTarget(@NonNull MagnetizedObject.MagneticTarget target) {
                menuAnimationController.startShrinkAnimation(() -> {
                    mMenuView.hide();

                    mHandler.post(mDismissMenuAction);
                });

                mDismissView.hide();
                mDismissAnimationController.animateDismissMenu(/* scaleUp= */ false);
            }
        });

        final MenuListViewTouchHandler menuListViewTouchHandler = new MenuListViewTouchHandler(
                menuAnimationController, mDismissAnimationController);
        mMenuView.addOnItemTouchListenerToList(menuListViewTouchHandler);

        addView(mMenuView, LayerIndex.MENU_VIEW);
        addView(mDismissView, LayerIndex.DISMISS_VIEW);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mDismissView.updateResources();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (mMenuView.maybeMoveOutEdgeAndShow((int) event.getX(), (int) event.getY())) {
            return true;
        }

        return super.onInterceptTouchEvent(event);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        mMenuView.show();
        mContext.registerComponentCallbacks(mDismissAnimationController);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        mMenuView.hide();
        mHandler.removeCallbacksAndMessages(/* token= */ null);
        mContext.unregisterComponentCallbacks(mDismissAnimationController);
    }
}
