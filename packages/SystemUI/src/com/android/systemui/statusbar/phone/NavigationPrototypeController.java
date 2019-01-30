/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.annotation.IntDef;
import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Coordinates with the prototype settings plugin app that uses Settings.Global to allow different
 * prototypes to run in the system. The class will handle communication changes from the settings
 * app and call back to listeners.
 */
public class NavigationPrototypeController extends ContentObserver {
    private static final String HIDE_BACK_BUTTON_SETTING = "quickstepcontroller_hideback";
    private static final String HIDE_HOME_BUTTON_SETTING = "quickstepcontroller_hidehome";
    private static final String PROTOTYPE_ENABLED = "prototype_enabled";

    private static final String EDGE_SENSITIVITY_HEIGHT_SETTING =
            "quickstepcontroller_edge_height_sensitivity";
    public static final String EDGE_SENSITIVITY_WIDTH_SETTING =
            "quickstepcontroller_edge_width_sensitivity";
    private final String GESTURE_MATCH_SETTING = "quickstepcontroller_gesture_match_map";
    public static final String NAV_COLOR_ADAPT_ENABLE_SETTING = "navbar_color_adapt_enable";

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({ACTION_DEFAULT, ACTION_QUICKSTEP, ACTION_QUICKSCRUB, ACTION_BACK,
            ACTION_QUICKSWITCH, ACTION_NOTHING, ACTION_ASSISTANT, ACTION_EXPAND_NOTIFICATION})
    @interface GestureAction {}
    static final int ACTION_DEFAULT = 0;
    static final int ACTION_QUICKSTEP = 1;
    static final int ACTION_QUICKSCRUB = 2;
    static final int ACTION_BACK = 3;
    static final int ACTION_QUICKSWITCH = 4;
    static final int ACTION_NOTHING = 5;
    static final int ACTION_ASSISTANT = 6;
    static final int ACTION_EXPAND_NOTIFICATION = 7;

    private OnPrototypeChangedListener mListener;

    /**
     * Each index corresponds to a different action set in QuickStepController
     * {@see updateSwipeLTRBackSetting}
     */
    private int[] mActionMap = new int[6];

    private final Context mContext;

    public NavigationPrototypeController(Handler handler, Context context) {
        super(handler);
        mContext = context;
        updateSwipeLTRBackSetting();
    }

    public void setOnPrototypeChangedListener(OnPrototypeChangedListener listener) {
        mListener = listener;
    }

    /**
     * Observe all the settings to react to from prototype settings
     */
    public void register() {
        registerObserver(HIDE_BACK_BUTTON_SETTING);
        registerObserver(HIDE_HOME_BUTTON_SETTING);
        registerObserver(GESTURE_MATCH_SETTING);
        registerObserver(NAV_COLOR_ADAPT_ENABLE_SETTING);
        registerObserver(EDGE_SENSITIVITY_WIDTH_SETTING);
        registerObserver(EDGE_SENSITIVITY_HEIGHT_SETTING);
    }

    /**
     * Disable observing settings to react to from prototype settings
     */
    public void unregister() {
        mContext.getContentResolver().unregisterContentObserver(this);
    }

    @Override
    public void onChange(boolean selfChange, Uri uri) {
        super.onChange(selfChange, uri);
        if (!selfChange && mListener != null) {
            final String path = uri.getPath();
            if (path.endsWith(GESTURE_MATCH_SETTING)) {
                // Get the settings gesture map corresponding to each action
                // {@see updateSwipeLTRBackSetting}
                updateSwipeLTRBackSetting();
                mListener.onGestureRemap(mActionMap);
            } else if (path.endsWith(HIDE_BACK_BUTTON_SETTING)) {
                mListener.onBackButtonVisibilityChanged(
                        !getGlobalBool(HIDE_BACK_BUTTON_SETTING, false));
            } else if (path.endsWith(HIDE_HOME_BUTTON_SETTING)) {
                mListener.onHomeButtonVisibilityChanged(!hideHomeButton());
            } else if (path.endsWith(NAV_COLOR_ADAPT_ENABLE_SETTING)) {
                mListener.onColorAdaptChanged(
                        NavBarTintController.isEnabled(mContext));
            } else if (path.endsWith(EDGE_SENSITIVITY_WIDTH_SETTING)
                    || path.endsWith(EDGE_SENSITIVITY_HEIGHT_SETTING)) {
                mListener.onEdgeSensitivityChanged(getEdgeSensitivityWidth(),
                        getEdgeSensitivityHeight());
            }
        }
    }

    public int getEdgeSensitivityWidth() {
        return convertDpToPixel(getGlobalInt(EDGE_SENSITIVITY_WIDTH_SETTING, 0));
    }

    public int getEdgeSensitivityHeight() {
        return convertDpToPixel(getGlobalInt(EDGE_SENSITIVITY_HEIGHT_SETTING, 0));
    }

    public boolean isEnabled() {
        return getGlobalBool(PROTOTYPE_ENABLED, false);
    }

    /**
     * Retrieve the action map to apply to the quick step controller
     * @return an action map
     */
    int[] getGestureActionMap() {
        return mActionMap;
    }

    /**
     * @return if home button should be invisible
     */
    boolean hideHomeButton() {
        return getGlobalBool(HIDE_HOME_BUTTON_SETTING, false /* default */);
    }

    /**
     * Since Settings.Global cannot pass arrays, use a string to represent each character as a
     * gesture map to actions corresponding to {@see GestureAction}. The number is represented as:
     * Number: [up] [down] [left] [right]
     */
    private void updateSwipeLTRBackSetting() {
        String value = Settings.Global.getString(mContext.getContentResolver(),
                GESTURE_MATCH_SETTING);
        if (value != null) {
            for (int i = 0; i < mActionMap.length; ++i) {
                mActionMap[i] = Character.getNumericValue(value.charAt(i));
            }
        }
    }

    private boolean getGlobalBool(String name, boolean defaultVal) {
        return Settings.Global.getInt(mContext.getContentResolver(), name, defaultVal ? 1 : 0) == 1;
    }

    private int getGlobalInt(String name, int defaultVal) {
        return Settings.Global.getInt(mContext.getContentResolver(), name, defaultVal);
    }

    private void registerObserver(String name) {
        mContext.getContentResolver()
                .registerContentObserver(Settings.Global.getUriFor(name), false, this);
    }

    private static int convertDpToPixel(float dp) {
        return (int) (dp * Resources.getSystem().getDisplayMetrics().density);
    }

    public interface OnPrototypeChangedListener {
        void onGestureRemap(@GestureAction int[] actions);
        void onBackButtonVisibilityChanged(boolean visible);
        void onHomeButtonVisibilityChanged(boolean visible);
        void onColorAdaptChanged(boolean enabled);
        void onEdgeSensitivityChanged(int width, int height);
    }
}
