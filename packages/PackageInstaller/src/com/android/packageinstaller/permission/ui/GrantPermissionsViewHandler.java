/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.packageinstaller.permission.ui;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.IntDef;

import java.lang.annotation.Retention;

/**
 * Class for managing the presentation and user interaction of the "grant
 * permissions" user interface.
 */
public interface GrantPermissionsViewHandler {
    @Retention(SOURCE)
    @IntDef({GRANTED_ALWAYS, GRANTED_FOREGROUND_ONLY, DENIED, DENIED_DO_NOT_ASK_AGAIN})
    @interface Result {}
    int GRANTED_ALWAYS = 0;
    int GRANTED_FOREGROUND_ONLY = 1;
    int DENIED = 2;
    int DENIED_DO_NOT_ASK_AGAIN = 3;

    /**
     * Listener interface for getting notified when the user responds to a
     * permissions grant request.
     */
    interface ResultListener {
        void onPermissionGrantResult(String groupName, @Result int result);
    }

    /**
     * Creates and returns the view hierarchy that is managed by this view
     * handler. This must be called before {@link #updateUi}.
     */
    View createView();

    /**
     * Updates the layout attributes of the current window. This is an optional
     * operation; implementations only need to do work in this method if they
     * need to alter the default styles provided by the activity's theme.
     */
    void updateWindowAttributes(WindowManager.LayoutParams outLayoutParams);

    /**
     * Updates the view hierarchy to reflect the specified state.
     * <p>
     * Note that this must be called at least once before showing the UI to
     * the user to properly initialize the UI.
     *
     * @param groupName the name of the permission group
     * @param groupCount the total number of groups that are being requested
     * @param groupIndex the index of the current group being requested
     * @param icon the icon representation of the current group
     * @param message the message to display the user
     * @param detailMessage another message to display to the user. This clarifies "message" in more
     *                      detail
     * @param showForegroundChooser whether to show the "only in foreground / always" option
     * @param showDoNotAsk whether to show the "do not ask again" option
     */
    void updateUi(String groupName, int groupCount, int groupIndex, Icon icon,
            CharSequence message, CharSequence detailMessage, boolean showForegroundChooser,
            boolean showDoNotAsk);

    /**
     * Sets the result listener that will be notified when the user responds
     * to a permissions grant request.
     */
    GrantPermissionsViewHandler setResultListener(ResultListener listener);

    /**
     * Called by {@link GrantPermissionsActivity} to save the state of this
     * view handler to the specified bundle.
     */
    void saveInstanceState(Bundle outState);

    /**
     * Called by {@link GrantPermissionsActivity} to load the state of this
     * view handler from the specified bundle.
     */
    void loadInstanceState(Bundle savedInstanceState);

    /**
     * Gives a chance for handling the back key.
     */
    void onBackPressed();
}
