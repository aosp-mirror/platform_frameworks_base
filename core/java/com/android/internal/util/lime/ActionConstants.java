/*
* Copyright (C) 2013 SlimRoms Project
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package com.android.internal.util.lime;

public class ActionConstants {

    // key must fit with the values arrays from Settings to use
    // SlimActions.java actions
    public static final String ACTION_HOME                 = "**home**";
    public static final String ACTION_BACK                 = "**back**";
    public static final String ACTION_SEARCH               = "**search**";
    public static final String ACTION_VOICE_SEARCH         = "**voice_search**";
    public static final String ACTION_MENU                 = "**menu**";
    public static final String ACTION_MENU_BIG             = "**menu_big**";
    public static final String ACTION_POWER                = "**power**";
    public static final String ACTION_NOTIFICATIONS        = "**notifications**";
    public static final String ACTION_RECENTS              = "**recents**";
    public static final String ACTION_SCREENSHOT           = "**screenshot**";
    public static final String ACTION_IME                  = "**ime**";
    public static final String ACTION_LAST_APP             = "**lastapp**";
    public static final String ACTION_KILL                 = "**kill**";
    public static final String ACTION_ASSIST               = "**assist**";
    public static final String ACTION_VIB                  = "**ring_vib**";
    public static final String ACTION_SILENT               = "**ring_silent**";
    public static final String ACTION_VIB_SILENT           = "**ring_vib_silent**";
    public static final String ACTION_POWER_MENU           = "**power_menu**";
    public static final String ACTION_TORCH                = "**torch**";
    public static final String ACTION_EXPANDED_DESKTOP     = "**expanded_desktop**";
    public static final String ACTION_THEME_SWITCH         = "**theme_switch**";
    public static final String ACTION_KEYGUARD_SEARCH      = "**keyguard_search**";
    public static final String ACTION_PIE                  = "**pie**";
    public static final String ACTION_NAVBAR               = "**nav_bar**";
    public static final String ACTION_IME_NAVIGATION_LEFT  = "**ime_nav_left**";
    public static final String ACTION_IME_NAVIGATION_RIGHT = "**ime_nav_right**";
    public static final String ACTION_IME_NAVIGATION_UP    = "**ime_nav_up**";
    public static final String ACTION_IME_NAVIGATION_DOWN  = "**ime_nav_down**";
    public static final String ACTION_CAMERA               = "**camera**";
    public static final String ACTION_MEDIA_PREVIOUS       = "**media_previous**";
    public static final String ACTION_MEDIA_NEXT           = "**media_next**";
    public static final String ACTION_MEDIA_PLAY_PAUSE     = "**media_play_pause**";
    public static final String ACTION_WAKE_DEVICE          = "**wake_device**";

    // no action
    public static final String ACTION_NULL            = "**null**";

    // this shorcut constant is only used to identify if the user
    // selected in settings a custom app...after it is choosed intent uri
    // is saved in the ButtonConfig object
    public static final String ACTION_APP          = "**app**";

    public static final String ICON_EMPTY = "empty";
    public static final String SYSTEM_ICON_IDENTIFIER = "system_shortcut=";
    public static final String ACTION_DELIMITER = "|";

    public static final String NAVIGATION_CONFIG_DEFAULT =
          ACTION_BACK    + ACTION_DELIMITER
        + ACTION_NULL    + ACTION_DELIMITER
        + ICON_EMPTY     + ACTION_DELIMITER
        + ACTION_HOME    + ACTION_DELIMITER
        + ACTION_NULL    + ACTION_DELIMITER
        + ICON_EMPTY     + ACTION_DELIMITER
        + ACTION_RECENTS + ACTION_DELIMITER
        + ACTION_NULL    + ACTION_DELIMITER
        + ICON_EMPTY;

    public static final String NAV_RING_CONFIG_DEFAULT =
          ACTION_ASSIST + ACTION_DELIMITER
        + ACTION_NULL   + ACTION_DELIMITER
        + ICON_EMPTY;

    public static final String PIE_SECOND_LAYER_CONFIG_DEFAULT =
          ACTION_POWER_MENU    + ACTION_DELIMITER
        + ACTION_NULL          + ACTION_DELIMITER
        + ICON_EMPTY           + ACTION_DELIMITER
        + ACTION_NOTIFICATIONS + ACTION_DELIMITER
        + ACTION_NULL          + ACTION_DELIMITER
        + ICON_EMPTY           + ACTION_DELIMITER
        + ACTION_SEARCH        + ACTION_DELIMITER
        + ACTION_NULL          + ACTION_DELIMITER
        + ICON_EMPTY           + ACTION_DELIMITER
        + ACTION_SCREENSHOT    + ACTION_DELIMITER
        + ACTION_NULL          + ACTION_DELIMITER
        + ICON_EMPTY           + ACTION_DELIMITER
        + ACTION_IME           + ACTION_DELIMITER
        + ACTION_NULL          + ACTION_DELIMITER
        + ICON_EMPTY;

}
