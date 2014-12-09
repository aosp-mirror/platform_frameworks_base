/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import com.android.systemui.recent.Recents;


/**
 * A proxy for Recents events which happens strictly for non-owner users.
 */
public class RecentsUserEventProxyReceiver extends BroadcastReceiver {
    final public static String ACTION_PROXY_SHOW_RECENTS_TO_USER =
            "com.android.systemui.recents.action.SHOW_RECENTS_FOR_USER";
    final public static String ACTION_PROXY_HIDE_RECENTS_TO_USER =
            "com.android.systemui.recents.action.HIDE_RECENTS_FOR_USER";
    final public static String ACTION_PROXY_TOGGLE_RECENTS_TO_USER =
            "com.android.systemui.recents.action.TOGGLE_RECENTS_FOR_USER";
    final public static String ACTION_PROXY_PRELOAD_RECENTS_TO_USER =
            "com.android.systemui.recents.action.PRELOAD_RECENTS_FOR_USER";
    final public static String ACTION_PROXY_CONFIG_CHANGE_TO_USER =
            "com.android.systemui.recents.action.CONFIG_CHANGED_FOR_USER";

    @Override
    public void onReceive(Context context, Intent intent) {
        AlternateRecentsComponent recents = Recents.getRecentsComponent(
                context.getApplicationContext(), true);
        switch (intent.getAction()) {
            case ACTION_PROXY_SHOW_RECENTS_TO_USER: {
                boolean triggeredFromAltTab = intent.getBooleanExtra(
                        AlternateRecentsComponent.EXTRA_TRIGGERED_FROM_ALT_TAB, false);
                recents.showRecents(triggeredFromAltTab);
                break;
            }
            case ACTION_PROXY_HIDE_RECENTS_TO_USER: {
                boolean triggeredFromAltTab = intent.getBooleanExtra(
                        AlternateRecentsComponent.EXTRA_TRIGGERED_FROM_ALT_TAB, false);
                boolean triggeredFromHome = intent.getBooleanExtra(
                        AlternateRecentsComponent.EXTRA_TRIGGERED_FROM_HOME_KEY, false);
                recents.hideRecents(triggeredFromAltTab, triggeredFromHome);
                break;
            }
            case ACTION_PROXY_TOGGLE_RECENTS_TO_USER:
                recents.toggleRecents();
                break;
            case ACTION_PROXY_PRELOAD_RECENTS_TO_USER:
                recents.preloadRecents();
                break;
            case ACTION_PROXY_CONFIG_CHANGE_TO_USER:
                recents.configurationChanged();
                break;
        }
    }
}
