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

package com.android.wm.shell.pip.tv;

import android.annotation.DrawableRes;
import android.annotation.NonNull;
import android.annotation.StringRes;
import android.app.PendingIntent;
import android.os.Handler;

import com.android.wm.shell.common.TvWindowMenuActionButton;

/**
 * A TvPipAction for actions that the system provides, i.e. fullscreen, default close, move,
 * expand/collapse.
 */
public class TvPipSystemAction extends TvPipAction {

    @StringRes
    private int mTitleResource;
    @DrawableRes
    private int mIconResource;

    TvPipSystemAction(@ActionType int actionType, @StringRes int title, @DrawableRes int icon) {
        super(actionType);
        update(title, icon);
    }

    void update(@StringRes int title, @DrawableRes int icon) {
        mTitleResource = title;
        mIconResource = icon;
    }

    void populateButton(@NonNull TvWindowMenuActionButton button, Handler mainHandler) {
        button.setTextAndDescription(mTitleResource);
        button.setImageResource(mIconResource);
        button.setEnabled(true);
    }

    PendingIntent getPendingIntent() {
        return null;
    }

}
