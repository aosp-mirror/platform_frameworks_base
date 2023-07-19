/*
 * Copyright (C) 2015 TeamEos project
 * Author Randall Rushing aka bigrushdog, randall.rushing@gmail.com
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
 *
 * ActionConstants.java: A helper class to assist Config.java with loading
 * and assigning default feature configurations. Nested classes implement
 * the static interface Defaults, which allows Settings and Config.java
 * to handle configurations in a non-implementation specific way, allowing
 * for more generalized code structures.
 *
 * Of strong importance is the ConfigMap pojo class. Current settings use
 * a ActionPreference which sets a single action. Therefore, we must have a
 * way to map individual actions to their associated buttons. ActionPreference
 * key MUST match the tag associated with the target ConfigMap.
 *
 */

package com.android.internal.util.hwkeys;

import java.util.HashMap;
import java.util.Map;

import com.android.internal.util.hwkeys.ActionHandler.SystemAction;
import com.android.internal.util.hwkeys.Config.ActionConfig;

import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;

public class ActionConstants {
    public static interface Defaults {
        public int getConfigType();
        public String getUri();
        public String getDefaultConfig();
        public int getMaxButtons();
        public Map<String, ConfigMap> getActionMap();
        public Bundle getConfigs(Context context);
    }

    public static final String ACTION_DELIMITER = "|";
    public static final String EMPTY = "empty";
    public static final int HWKEYS = 1;

    private static final Hwkeys hwkeys = new Hwkeys();

    public static Defaults getDefaults(int type) {
        if (type == HWKEYS) {
            return hwkeys;
        } else {
            return null;
        }
    }

    public static String dl(String s) {
        return s + ACTION_DELIMITER;
    }

    public static class Hwkeys implements Defaults {
        public static final int HWKEY_MAX_BUTTONS = 7;
        public static final String HWKEY_DEF_BUTTONS = "5";
        public static final String BACK_BUTTON_TAG = "hwkeys_button_back";
        public static final String HOME_BUTTON_TAG = "hwkeys_button_home";
        public static final String OVERVIEW_BUTTON_TAG = "hwkeys_button_overview";
        public static final String MENU_BUTTON_TAG = "hwkeys_button_menu";
        public static final String ASSIST_BUTTON_TAG = "hwkeys_button_assist";
        public static final String EXTRA1_BUTTON_TAG = "hwkeys_button_camera";
        public static final String EXTRA2_BUTTON_TAG = "hwkeys_button_extra";

        public static final String BACK_BUTTON_SINGLE_TAP_TAG = "hwkeys_button_back_single_tap";
        public static final String HOME_BUTTON_SINGLE_TAP_TAG = "hwkeys_button_home_single_tap";
        public static final String OVERVIEW_BUTTON_SINGLE_TAP_TAG = "hwkeys_button_overview_single_tap";
        public static final String MENU_BUTTON_SINGLE_TAP_TAG = "hwkeys_button_menu_single_tap";
        public static final String ASSIST_BUTTON_SINGLE_TAP_TAG = "hwkeys_button_assist_single_tap";

        public static final String BACK_BUTTON_LONG_PRESS_TAG = "hwkeys_button_back_long_press";
        public static final String HOME_BUTTON_LONG_PRESS_TAG = "hwkeys_button_home_long_press";
        public static final String OVERVIEW_BUTTON_LONG_PRESS_TAG = "hwkeys_button_overview_long_press";
        public static final String MENU_BUTTON_LONG_PRESS_TAG = "hwkeys_button_menu_long_press";
        public static final String ASSIST_BUTTON_LONG_PRESS_TAG = "hwkeys_button_assist_long_press";

        public static final String BACK_BUTTON_DOUBLE_TAP_TAG = "hwkeys_button_back_double_tap";
        public static final String HOME_BUTTON_DOUBLE_TAP_TAG = "hwkeys_button_home_double_tap";
        public static final String OVERVIEW_BUTTON_DOUBLE_TAP_TAG = "hwkeys_button_overview_double_tap";
        public static final String MENU_BUTTON_DOUBLE_TAP_TAG = "hwkeys_button_menu_double_tap";
        public static final String ASSIST_BUTTON_DOUBLE_TAP_TAG = "hwkeys_button_assist_double_tap";

        private static final Map<String, ConfigMap> configMap = new HashMap<String, ConfigMap>();

        static {
            configMap.put(BACK_BUTTON_SINGLE_TAP_TAG, new ConfigMap(0, ActionConfig.PRIMARY));
            configMap.put(HOME_BUTTON_SINGLE_TAP_TAG, new ConfigMap(1, ActionConfig.PRIMARY));
            configMap.put(OVERVIEW_BUTTON_SINGLE_TAP_TAG, new ConfigMap(2, ActionConfig.PRIMARY));
            configMap.put(MENU_BUTTON_SINGLE_TAP_TAG, new ConfigMap(3, ActionConfig.PRIMARY));
            configMap.put(ASSIST_BUTTON_SINGLE_TAP_TAG, new ConfigMap(4, ActionConfig.PRIMARY));
            configMap.put(BACK_BUTTON_LONG_PRESS_TAG, new ConfigMap(0, ActionConfig.SECOND));
            configMap.put(HOME_BUTTON_LONG_PRESS_TAG, new ConfigMap(1, ActionConfig.SECOND));
            configMap.put(OVERVIEW_BUTTON_LONG_PRESS_TAG, new ConfigMap(2, ActionConfig.SECOND));
            configMap.put(MENU_BUTTON_LONG_PRESS_TAG, new ConfigMap(3, ActionConfig.SECOND));
            configMap.put(ASSIST_BUTTON_LONG_PRESS_TAG, new ConfigMap(4, ActionConfig.SECOND));
            configMap.put(BACK_BUTTON_DOUBLE_TAP_TAG, new ConfigMap(0, ActionConfig.THIRD));
            configMap.put(HOME_BUTTON_DOUBLE_TAP_TAG, new ConfigMap(1, ActionConfig.THIRD));
            configMap.put(OVERVIEW_BUTTON_DOUBLE_TAP_TAG, new ConfigMap(2, ActionConfig.THIRD));
            configMap.put(MENU_BUTTON_DOUBLE_TAP_TAG, new ConfigMap(3, ActionConfig.THIRD));
            configMap.put(ASSIST_BUTTON_DOUBLE_TAP_TAG, new ConfigMap(4, ActionConfig.THIRD));
        }

        public static final String HWKEYS_CONFIG_DEFAULT =
                dl(HWKEY_DEF_BUTTONS)
              + dl(BACK_BUTTON_TAG)
              + dl(SystemAction.Back.mAction)        + dl(SystemAction.Back.mLabelRes)         + dl(EMPTY)  // single tap (PRIMARY)
              + dl(SystemAction.KillApp.mAction)     + dl(SystemAction.KillApp.mLabelRes)      + dl(EMPTY)  // long press (SECOND)
              + dl(SystemAction.NoAction.mAction)    + dl(SystemAction.NoAction.mLabelRes)     + dl(EMPTY)  // double tap (THIRD)

              + dl(HOME_BUTTON_TAG)
              + dl(SystemAction.Home.mAction)        + dl(SystemAction.Home.mLabelRes)         + dl(EMPTY)
              + dl(SystemAction.Overview.mAction)    + dl(SystemAction.Overview.mLabelRes)     + dl(EMPTY)
              + dl(SystemAction.NoAction.mAction)    + dl(SystemAction.NoAction.mLabelRes)     + dl(EMPTY)

              + dl(OVERVIEW_BUTTON_TAG)
              + dl(SystemAction.Overview.mAction)    + dl(SystemAction.Overview.mLabelRes)     + dl(EMPTY)
              + dl(SystemAction.NoAction.mAction)    + dl(SystemAction.NoAction.mLabelRes)     + dl(EMPTY)
              + dl(SystemAction.NoAction.mAction)    + dl(SystemAction.NoAction.mLabelRes)     + dl(EMPTY)

              + dl(MENU_BUTTON_TAG)
              + dl(SystemAction.Menu.mAction)        + dl(SystemAction.Menu.mLabelRes)         + dl(EMPTY)
              + dl(SystemAction.LastApp.mAction)     + dl(SystemAction.LastApp.mLabelRes)      + dl(EMPTY)
              + dl(SystemAction.NoAction.mAction)    + dl(SystemAction.NoAction.mLabelRes)     + dl(EMPTY)

              + dl(ASSIST_BUTTON_TAG)
              + dl(SystemAction.GoogleAssistant.mAction)   + dl(SystemAction.GoogleAssistant.mLabelRes)    + dl(EMPTY)
              + dl(SystemAction.NoAction.mAction)          + dl(SystemAction.NoAction.mLabelRes)           + dl(EMPTY)
              + dl(SystemAction.NoAction.mAction)          + dl(SystemAction.NoAction.mLabelRes)           + EMPTY;

        @Override
        public int getConfigType() {
            return HWKEYS;
        }

        @Override
        public String getUri() {
            //return Settings.System.HWKEY_BUTTON_ACTIONS;
            return "hwkey_config";
        }

        @Override
        public String getDefaultConfig() {
            return HWKEYS_CONFIG_DEFAULT;
        }

        @Override
        public int getMaxButtons() {
            return HWKEY_MAX_BUTTONS;
        }

        @Override
        public Map<String, ConfigMap> getActionMap() {
            return configMap;
        }

        @Override
        public Bundle getConfigs(Context context) {
            // TODO Auto-generated method stub
            return null;
        }
    }

    private static Bundle loadConfigsFromMap(Context ctx, Map<String, ConfigHolder> configMap) {
        Bundle b = new Bundle();
        for (Map.Entry<String, ConfigHolder> entry : configMap.entrySet()) {
            ConfigHolder holder = entry.getValue();
            Object obj = ActionUtils.getValue(ctx, holder.name, holder.type, holder.format,
                    holder.pkg);
            ActionUtils.putValue(holder.name, obj, holder.type, b);
        }
        return b;
    }

    private static class ConfigHolder {
        public String pkg;
        public String name;
        public String format;
        public String type;

        public ConfigHolder(String pkg, String name, String type) {
            this(pkg, name, null, type);
        }

        public ConfigHolder(String pkg, String name, String format, String type) {
            this.pkg = pkg;
            this.name = name;
            this.format = format;
            this.type = type;
        }
    }

    public static class ConfigMap {
        public int button = -1;
        public int action = -1;

        public ConfigMap() {
        };

        public ConfigMap(int button, int action) {
            this.button = button;
            this.action = action;
        }
    }

}
