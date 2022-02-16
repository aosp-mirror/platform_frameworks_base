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
import android.view.ViewGroup;

import com.android.systemui.R;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.notification.row.dagger.NotificationShelfComponent;
import com.android.systemui.statusbar.phone.NotificationPanelView;
import com.android.systemui.statusbar.phone.NotificationShadeWindowView;
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
    private final NotificationShelfComponent.Builder mNotificationShelfComponentBuilder;

    private NotificationShadeWindowView mNotificationShadeWindowView;
    private StatusBarWindowView mStatusBarWindowView;
    private NotificationShelfController mNotificationShelfController;

    @Inject
    public SuperStatusBarViewFactory(Context context,
            InjectionInflationController injectionInflationController,
            NotificationShelfComponent.Builder notificationShelfComponentBuilder) {
        mContext = context;
        mInjectionInflationController = injectionInflationController;
        mNotificationShelfComponentBuilder = notificationShelfComponentBuilder;
    }

    /**
     * Gets the inflated {@link NotificationShadeWindowView} from
     * {@link R.layout#super_notification_shade}.
     * Returns a cached instance, if it has already been inflated.
     */
    public NotificationShadeWindowView getNotificationShadeWindowView() {
        if (mNotificationShadeWindowView != null) {
            return mNotificationShadeWindowView;
        }

        mNotificationShadeWindowView = (NotificationShadeWindowView)
                mInjectionInflationController.injectable(
                LayoutInflater.from(mContext)).inflate(R.layout.super_notification_shade,
                /* root= */ null);
        if (mNotificationShadeWindowView == null) {
            throw new IllegalStateException(
                    "R.layout.super_notification_shade could not be properly inflated");
        }

        return mNotificationShadeWindowView;
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

    /**
     * Gets the inflated {@link NotificationShelf} from
     * {@link R.layout#status_bar_notification_shelf}.
     * Returns a cached instance, if it has already been inflated.
     *
     * @param container the expected container to hold the {@link NotificationShelf}. The view
     *                  isn't immediately attached, but the layout params of this view is used
     *                  during inflation.
     */
    public NotificationShelfController getNotificationShelfController(ViewGroup container) {
        if (mNotificationShelfController != null) {
            return mNotificationShelfController;
        }

        NotificationShelf view = (NotificationShelf) LayoutInflater.from(mContext)
                .inflate(R.layout.status_bar_notification_shelf, container, /* attachToRoot= */
                        false);

        if (view == null) {
            throw new IllegalStateException(
                    "R.layout.status_bar_notification_shelf could not be properly inflated");
        }

        NotificationShelfComponent component = mNotificationShelfComponentBuilder
                .notificationShelf(view)
                .build();
        mNotificationShelfController = component.getNotificationShelfController();
        mNotificationShelfController.init();

        return mNotificationShelfController;
    }

    public NotificationPanelView getNotificationPanelView() {
        NotificationShadeWindowView notificationShadeWindowView = getNotificationShadeWindowView();
        if (notificationShadeWindowView == null) {
            return null;
        }

        return mNotificationShadeWindowView.findViewById(R.id.notification_panel);
    }
}
