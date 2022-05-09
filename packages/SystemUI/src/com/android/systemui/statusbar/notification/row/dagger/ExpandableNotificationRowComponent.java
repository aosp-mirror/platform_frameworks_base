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

package com.android.systemui.statusbar.notification.row.dagger;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.service.notification.StatusBarNotification;

import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRowController;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.phone.CentralSurfaces;

import dagger.Binds;
import dagger.BindsInstance;
import dagger.Module;
import dagger.Provides;
import dagger.Subcomponent;

/**
 * Dagger Component for a {@link ExpandableNotificationRow}.
 */
@Subcomponent(modules = {
        ActivatableNotificationViewModule.class,
        ExpandableNotificationRowComponent.ExpandableNotificationRowModule.class,
        RemoteInputViewModule.class
})
@NotificationRowScope
public interface ExpandableNotificationRowComponent {

    /**
     * Builder for {@link NotificationRowComponent}.
     */
    @Subcomponent.Builder
    interface Builder {
        // TODO: NotificationEntry contains a reference to ExpandableNotificationRow, so it
        // should be possible to pull one from the other, but they aren't connected at the time
        // this component is constructed.
        @BindsInstance
        Builder expandableNotificationRow(ExpandableNotificationRow view);
        @BindsInstance
        Builder notificationEntry(NotificationEntry entry);
        @BindsInstance
        Builder onExpandClickListener(ExpandableNotificationRow.OnExpandClickListener presenter);
        @BindsInstance
        Builder listContainer(NotificationListContainer listContainer);
        ExpandableNotificationRowComponent build();
    }

    /**
     * Creates a ExpandableNotificationRowController.
     */
    @NotificationRowScope
    ExpandableNotificationRowController getExpandableNotificationRowController();

    /**
     * Dagger Module that extracts interesting properties from an ExpandableNotificationRow.
     */
    @Module
    abstract class ExpandableNotificationRowModule {

        /** ExpandableNotificationRow is provided as an instance of ActivatableNotificationView. */
        @Binds
        abstract ActivatableNotificationView bindExpandableView(ExpandableNotificationRow view);

        @Provides
        static StatusBarNotification provideStatusBarNotification(
                NotificationEntry notificationEntry) {
            return notificationEntry.getSbn();
        }

        @Provides
        @NotificationKey
        static String provideNotificationKey(StatusBarNotification statusBarNotification) {
            return statusBarNotification.getKey();
        }

        @Provides
        @AppName
        static String provideAppName(Context context, StatusBarNotification statusBarNotification) {
            // Get the app name.
            // Note that Notification.Builder#bindHeaderAppName has similar logic
            // but since this field is used in the guts, it must be accurate.
            // Therefore we will only show the application label, or, failing that, the
            // package name. No substitutions.
            PackageManager pmUser = CentralSurfaces.getPackageManagerForUser(
                    context, statusBarNotification.getUser().getIdentifier());
            final String pkg = statusBarNotification.getPackageName();
            try {
                final ApplicationInfo info = pmUser.getApplicationInfo(pkg,
                        PackageManager.MATCH_UNINSTALLED_PACKAGES
                                | PackageManager.MATCH_DISABLED_COMPONENTS);
                if (info != null) {
                    return String.valueOf(pmUser.getApplicationLabel(info));
                }
            } catch (PackageManager.NameNotFoundException e) {
                // Do nothing
            }

            return pkg;
        }
    }
}
