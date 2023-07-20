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
 * Config.java: A helper class for loading/setting feature button configurations
 * to SettingsProvider. We start with defining a "action" in the nested
 * ActionConfig class. A action holds a raw action string, a label for that action,
 * and helper functions for loading an associated drawable for that action.
 * 
 * The nested static class ButtonConfig is a convenience class that holds three
 * ActionConfig objects. Those ActionConfig objects are labeled as "PRIMARY",
 * "SECOND", and "THIRD", which will typically refer to a single tap, long press,
 * and double tap, respectively. However, this is not always the case, thus the
 * more generalized naming convention.
 * 
 * ActionConfig and ButtonConfig implement the private Stringable interface to allow
 * for easy loading and setting
 * 
 */
package com.android.internal.util.hwkeys;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.android.internal.util.hwkeys.ActionHandler;
import com.android.internal.util.hwkeys.ActionConstants.ConfigMap;
import com.android.internal.util.hwkeys.ActionConstants.Defaults;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;

public class Config {
    private interface Stringable {
        public String toDelimitedString();
        public void fromList(Context ctx, List<String> items);
    }

    /**
     * For use with action/button based features that don't require a defaut configuration
     */
    public static ArrayList<ButtonConfig> getConfig(Context ctx, String uri,
            boolean fromSecureSettings) {
        if (ctx == null || uri == null) {
            return null;
        }
        String config;
        if (fromSecureSettings) {
            config = Settings.Secure.getStringForUser(
                    ctx.getContentResolver(), uri,
                    UserHandle.USER_CURRENT);
        } else {
            config = Settings.System.getStringForUser(
                    ctx.getContentResolver(), uri,
                    UserHandle.USER_CURRENT);
        }
        if (TextUtils.isEmpty(config)) {
            return null;
        }
        ArrayList<String> items = new ArrayList<String>();
        items.addAll(Arrays.asList(config.split("\\|"))); // split string into array elements
        int numConfigs = Integer.parseInt(items.get(0)); // first element is always the number of
                                                         // ButtonConfigs to parse
        items.remove(0); // remove button count for a clean list of buttons

        ArrayList<ButtonConfig> buttonList = new ArrayList<ButtonConfig>();
        ButtonConfig buttonConfig;

        for (int i = 0; i < numConfigs; i++) {
            int from = i * ButtonConfig.NUM_ELEMENTS; // (0, 10), (10, 20)...
            int to = from + ButtonConfig.NUM_ELEMENTS;
            buttonConfig = new ButtonConfig(ctx);
            buttonConfig.fromList(ctx, items.subList(from, to)); // initialize button from list
                                                                 // elements
            buttonList.add(buttonConfig);
        }
        return buttonList;
    }

    public static ArrayList<ButtonConfig> getConfig(Context ctx, Defaults defaults) {
        if (ctx == null || defaults == null) {
            return null;
        }
        String config = Settings.Secure.getStringForUser(
                ctx.getContentResolver(), defaults.getUri(),
                UserHandle.USER_CURRENT);
        if (TextUtils.isEmpty(config)) {
            config = defaults.getDefaultConfig();
        }

        ArrayList<String> items = new ArrayList<String>();
        items.addAll(Arrays.asList(config.split("\\|")));  // split string into array elements
        int numConfigs = Integer.parseInt(items.get(0));   // first element is always the number of ButtonConfigs to parse
        items.remove(0);                                   // remove button count for a clean list of buttons

        ArrayList<ButtonConfig> buttonList = new ArrayList<ButtonConfig>();
        ButtonConfig buttonConfig;

        for (int i = 0; i < numConfigs; i++) {
            int from = i * ButtonConfig.NUM_ELEMENTS;  // (0, 10), (10, 20)...
            int to = from + ButtonConfig.NUM_ELEMENTS;
            buttonConfig = new ButtonConfig(ctx);
            buttonConfig.fromList(ctx, items.subList(from, to));  // initialize button from list elements
            buttonList.add(buttonConfig);
        }
        return buttonList;
    }

    public static ArrayList<ButtonConfig> getDefaultConfig(Context ctx, Defaults defaults) {
        if (ctx == null || defaults == null) {
            return null;
        }
        String config = defaults.getDefaultConfig();
        ArrayList<String> items = new ArrayList<String>();
        items.addAll(Arrays.asList(config.split("\\|")));
        int numConfigs = Integer.parseInt(items.get(0));
        items.remove(0);
        ArrayList<ButtonConfig> buttonList = new ArrayList<ButtonConfig>();
        ButtonConfig buttonConfig;

        for (int i = 0; i < numConfigs; i++) {
            int from = i * ButtonConfig.NUM_ELEMENTS;
            int to = from + ButtonConfig.NUM_ELEMENTS;
            buttonConfig = new ButtonConfig(ctx);
            buttonConfig.fromList(ctx, items.subList(from, to));
            buttonList.add(buttonConfig);
        }
        return buttonList;
    }

    public static void setConfig(Context ctx, Defaults defaults, ArrayList<ButtonConfig> config) {
        if (ctx == null || defaults == null || config == null) {
            return;
        }
        int numConfigs = config.size();
        if (numConfigs <= 0) {
            return;
        }
        StringBuilder b = new StringBuilder();
        b.append(String.valueOf(numConfigs));
        b.append(ActionConstants.ACTION_DELIMITER);  // size of list is always first element
        for (ButtonConfig button : config) {
            b.append(button.toDelimitedString());   // this is just beautiful ;D
        }
        String s = b.toString();
        if (s.endsWith(ActionConstants.ACTION_DELIMITER)) {
            s = removeLastChar(s);  // trim final delimiter if need be
        }
        Settings.Secure.putStringForUser(ctx.getContentResolver(), defaults.getUri(), s,
                UserHandle.USER_CURRENT);
    }

    public static ButtonConfig getButtonConfigFromTag(ArrayList<ButtonConfig> configs, String tag) {
        if (configs == null || tag == null) {
            return null;
        }
        ButtonConfig config = null;
        for (ButtonConfig b : configs) {
            if (TextUtils.equals(b.getTag(), tag)) {
                config = b;
                break;
            }
        }
        return config;
    }

    public static ArrayList<ButtonConfig> replaceButtonAtPosition(ArrayList<ButtonConfig> configs,
            ButtonConfig button, ConfigMap map) {
        if (configs == null || button == null || map == null) {
            return null;
        }
        configs.remove(map.button);
        configs.add(map.button, button);
        return configs;
    }

    public static String removeLastChar(String s) {
        if (s == null || s.length() == 0) {
            return s;
        }
        return s.substring(0, s.length() - 1);
    }

    public static class ButtonConfig implements Stringable, Parcelable {
        public static final int NUM_ELEMENTS = 10;
        protected ActionConfig[] configs = new ActionConfig[3];
        private String tag = ActionConstants.EMPTY;

        // internal use only
        private ButtonConfig() {
        }

        public ButtonConfig(Context ctx) {
            configs[ActionConfig.PRIMARY] = new ActionConfig(ctx);
            configs[ActionConfig.SECOND] = new ActionConfig(ctx);
            configs[ActionConfig.THIRD] = new ActionConfig(ctx);
        }

        public String getTag() {
            return tag;
        }

        public void setTag(String tag) {
            this.tag = tag;
        }

        public boolean hasCustomIcon() {
            return configs[ActionConfig.PRIMARY].hasCustomIcon();
        }

        public void clearCustomIconIconUri() {
            configs[ActionConfig.PRIMARY].clearCustomIconIconUri();
        }

        public void setCustomIconUri(String type, String packageName, String iconName) {
            configs[ActionConfig.PRIMARY].setCustomIconUri(type, packageName, iconName);
        }

        public void setCustomImageUri(Uri uri) {
            configs[ActionConfig.PRIMARY].setCustomImageUri(uri);
        }

        public Drawable getDefaultIcon(Context ctx) {
            return configs[ActionConfig.PRIMARY].getDefaultIcon(ctx);
        }

        public Drawable getCurrentIcon(Context ctx) {
            return configs[ActionConfig.PRIMARY].getCurrentIcon(ctx);
        }

        public boolean isSystemAction() {
            return configs[ActionConfig.PRIMARY].isSystemAction();
        }

        public String getSystemActionIconName() {
            return configs[ActionConfig.PRIMARY].getSystemActionIconName();
        }

        public ActionConfig getActionConfig(int which) {
            if (which < ActionConfig.PRIMARY || which > ActionConfig.THIRD) {
                return null;
            }
            return configs[which];
        }

        public void setActionConfig(ActionConfig config, int which) {
            if (which < ActionConfig.PRIMARY || which > ActionConfig.THIRD || config == null) {
                return;
            }
            configs[which] = config;
        }

        public static void setButton(Context ctx, ButtonConfig button, String uri, boolean isSecure) {
            StringBuilder b = new StringBuilder();
            b.append(button.toDelimitedString()); // this is just beautiful ;D
            String s = b.toString();
            if (s.endsWith(ActionConstants.ACTION_DELIMITER)) {
                s = removeLastChar(s); // trim final delimiter if need be
            }

            if (isSecure) {
                Settings.Secure.putStringForUser(ctx.getContentResolver(), uri, s,
                        UserHandle.USER_CURRENT);
            } else {
                Settings.System.putStringForUser(ctx.getContentResolver(), uri, s,
                        UserHandle.USER_CURRENT);
            }
        }

        public static ButtonConfig getButton(Context ctx, String uri, boolean isSecure) {
            if (ctx == null || TextUtils.isEmpty(uri)) {
                return null;
            }
            String config;
            if (isSecure) {
                config = Settings.Secure.getStringForUser(
                        ctx.getContentResolver(), uri,
                        UserHandle.USER_CURRENT);
            } else {
                config = Settings.System.getStringForUser(
                        ctx.getContentResolver(), uri,
                        UserHandle.USER_CURRENT);
            }
            if (TextUtils.isEmpty(config)) {
                return new ButtonConfig(ctx);
            }
            ArrayList<String> items = new ArrayList<String>();
            items.addAll(Arrays.asList(config.split("\\|")));
            ButtonConfig buttonConfig = new ButtonConfig(ctx);
            buttonConfig.fromList(ctx, items.subList(0, NUM_ELEMENTS)); // initialize button from
                                                                        // list elements
            return buttonConfig;
        }

        @Override
        public String toDelimitedString() {
            return tag + ActionConstants.ACTION_DELIMITER
                    + configs[ActionConfig.PRIMARY].toDelimitedString()
                    + configs[ActionConfig.SECOND].toDelimitedString()
                    + configs[ActionConfig.THIRD].toDelimitedString();
        }

        @Override
        public void fromList(Context ctx, List<String> items) {
            ArrayList<String> buttons = new ArrayList<String>();
            buttons.addAll(items);
            tag = buttons.get(0);

            ActionConfig config = new ActionConfig();
            config.fromList(ctx, buttons.subList(1, 4));
            configs[ActionConfig.PRIMARY] = config;

            config = new ActionConfig();
            config.fromList(ctx, buttons.subList(4, 7));
            configs[ActionConfig.SECOND] = config;

            config = new ActionConfig();
            config.fromList(ctx, buttons.subList(7, 10));
            configs[ActionConfig.THIRD] = config;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(tag);
            dest.writeParcelable(configs[ActionConfig.PRIMARY], flags);
            dest.writeParcelable(configs[ActionConfig.SECOND], flags);
            dest.writeParcelable(configs[ActionConfig.THIRD], flags);
        }

        public static final Parcelable.Creator<ButtonConfig> CREATOR = new Parcelable.Creator<ButtonConfig>() {
            public ButtonConfig createFromParcel(Parcel in) {
                return new ButtonConfig(in);
            }

            public ButtonConfig[] newArray(int size) {
                return new ButtonConfig[size];
            }
        };

        private ButtonConfig(Parcel in) {
            tag = in.readString();
            configs[ActionConfig.PRIMARY] = (ActionConfig) in.readParcelable(Config.ActionConfig.class
                    .getClassLoader());
            configs[ActionConfig.SECOND] = (ActionConfig) in.readParcelable(Config.ActionConfig.class
                    .getClassLoader());
            configs[ActionConfig.THIRD] = (ActionConfig) in.readParcelable(Config.ActionConfig.class
                    .getClassLoader());
        }
    }

    public static class ActionConfig implements Stringable, Comparable<ActionConfig>, Parcelable {
        public static final int PRIMARY = 0;
        public static final int SECOND = 1;
        public static final int THIRD = 2;

        private String action = ActionHandler.SYSTEMUI_TASK_NO_ACTION;
        private String label = ActionConstants.EMPTY;
        private String iconUri = ActionConstants.EMPTY;

        // internal use only
        private ActionConfig() {
        }

        public static ActionConfig create(Context ctx) {
            return new ActionConfig(ctx);
        }

        public static ActionConfig create(Context ctx, String action) {
            return new ActionConfig(ctx, action);
        }

        public static ActionConfig create(Context ctx, String action, String iconUri) {
            return new ActionConfig(ctx, action, iconUri);
        }

        public ActionConfig(Context ctx) {
            label = ActionUtils.getFriendlyNameForUri(ctx, action);
        }

        public ActionConfig(Context ctx, String action) {
            this.action = action;
            label = ActionUtils.getFriendlyNameForUri(ctx, action);
        }

        public ActionConfig(Context ctx, String action, String iconUri) {
            this(ctx, action);
            this.iconUri = iconUri;
        }

        public String getAction() {
            return action;
        }

        public String getLabel() {
            return label;
        }

        public String getIconUri() {
            if (!hasCustomIcon()) {
                return action;
            } else {
                return iconUri;
            }
        }

        public boolean hasCustomIcon() {
            return !TextUtils.equals(ActionConstants.EMPTY, iconUri);
        }

        public void clearCustomIconIconUri() {
            iconUri = ActionConstants.EMPTY;
        }

        public boolean isSystemAction() {
            return action.startsWith(ActionHandler.SYSTEM_PREFIX);
        }

        public String getSystemActionIconName() {
            if (action.startsWith(ActionHandler.SYSTEM_PREFIX)) {
                for (int i = 0; i < ActionHandler.systemActions.length; i++) {
                    if (ActionHandler.systemActions[i].mAction.equals(action)) {
                        return ActionHandler.systemActions[i].mIconRes;
                    }
                }
            }
            return null;
        }

        public void setCustomImageUri(Uri uri) {
            iconUri = "image$" + uri.toString();
        }

        public void setCustomIconUri(String type, String packageName, String iconName) {
            StringBuilder b = new StringBuilder()
            .append(type)
            .append("$")
            .append(packageName)
            .append("$")
            .append(iconName);
            iconUri = b.toString();
        }

        public Drawable getDefaultIcon(Context ctx) {
            return ActionUtils.getDrawableForAction(ctx, action);
        }

        /**
         * Returns custom icon (if exists)
         * @param ctx app's context
         * @return drawable when custom icon exists, null otherwise
         */
        public Drawable getCurrentCustomIcon(Context ctx) {
            if (hasCustomIcon()) {
                List<String> items = Arrays.asList(iconUri.split("\\$"));
                String type = items.get(0);
                if (type.equals("iconpack") && items.size() == 3) {
                    String packageName = items.get(1);
                    String iconName = items.get(2);
                    return ActionUtils.getDrawable(ctx, iconName, packageName);
                } else if (type.equals("image") && items.size() == 2) {
                    String uri = items.get(1);
                    return ActionUtils.getDrawable(ctx, Uri.parse(uri));
                }
            }
            return null;
        }

        public Drawable getCurrentIcon(Context ctx) {

            Drawable drawable = getCurrentCustomIcon(ctx);

            //If icon doesn't exist (or is not set) fallback to action one
            if (drawable == null) {
                drawable = ActionUtils.getDrawableForAction(ctx, action);
            }

            return drawable;

        }

        public boolean hasNoAction() {
            return TextUtils.equals(action, ActionHandler.SYSTEMUI_TASK_NO_ACTION)
                    || TextUtils.equals(action, ActionConstants.EMPTY);
        }

        public boolean isActionRecents() {
            return TextUtils.equals(action, ActionHandler.SYSTEMUI_TASK_RECENTS);
        }

        @Override
        public int compareTo(ActionConfig another) {
            int result = label.toString().compareToIgnoreCase(another.label.toString());
            return result;
        }

        @Override
        public String toDelimitedString() {
            return action + ActionConstants.ACTION_DELIMITER
                    + label + ActionConstants.ACTION_DELIMITER
                    + iconUri + ActionConstants.ACTION_DELIMITER;
        }

        @Override
        public void fromList(Context ctx, List<String> items) {
            ArrayList<String> actionStrings = new ArrayList<String>();
            actionStrings.addAll(items);
            action = items.get(0);
            label = ActionUtils.getFriendlyNameForUri(ctx, action);
            iconUri = items.get(2);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(action);
            dest.writeString(label);
            dest.writeString(iconUri);
        }

        public static final Parcelable.Creator<ActionConfig> CREATOR = new Parcelable.Creator<ActionConfig>() {
            public ActionConfig createFromParcel(Parcel in) {
                return new ActionConfig(in);
            }

            public ActionConfig[] newArray(int size) {
                return new ActionConfig[size];
            }
        };

        private ActionConfig(Parcel in) {
            action = in.readString();
            label = in.readString();
            iconUri = in.readString();
        }
    }
}
