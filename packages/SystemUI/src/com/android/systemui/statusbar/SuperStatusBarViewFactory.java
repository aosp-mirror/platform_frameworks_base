/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar;

import android.content.Context;
import android.view.LayoutInflater;

import com.android.systemui.R;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.phone.StatusBarWindowView;
import com.android.systemui.util.InjectionInflationController;

import javax.inject.Inject;

/**
 * Creates a single instance of super_status_bar and super_notification_shade that can be shared
 * across various system ui objects.
 */
@SysUISingleton
public class SuperStatusBarViewFactory {

    private final Context mContext;
    private final InjectionInflationController mInjectionInflationController;

    private StatusBarWindowView mStatusBarWindowView;

    @Inject
    public SuperStatusBarViewFactory(Context context,
            InjectionInflationController injectionInflationController) {
        mContext = context;
        mInjectionInflationController = injectionInflationController;
    }

    /**
     * Gets the inflated {@link StatusBarWindowView} from {@link R.layout#super_status_bar}.
     * Returns a cached instance, if it has already been inflated.
     */
    public StatusBarWindowView getStatusBarWindowView() {
        if (mStatusBarWindowView != null) {
            return mStatusBarWindowView;
        }

        mStatusBarWindowView =
                (StatusBarWindowView) mInjectionInflationController.injectable(
                LayoutInflater.from(mContext)).inflate(R.layout.super_status_bar,
                /* root= */ null);
        if (mStatusBarWindowView == null) {
            throw new IllegalStateException(
                    "R.layout.super_status_bar could not be properly inflated");
        }
        return mStatusBarWindowView;
    }
}
