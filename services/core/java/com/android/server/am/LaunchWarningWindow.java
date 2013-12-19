/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server.am;

import com.android.internal.R;

import android.app.Dialog;
import android.content.Context;
import android.util.TypedValue;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

public final class LaunchWarningWindow extends Dialog {
    public LaunchWarningWindow(Context context, ActivityRecord cur, ActivityRecord next) {
        super(context, R.style.Theme_Toast);

        requestWindowFeature(Window.FEATURE_LEFT_ICON);
        getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        
        setContentView(R.layout.launch_warning);
        setTitle(context.getText(R.string.launch_warning_title));

        TypedValue out = new TypedValue();
        getContext().getTheme().resolveAttribute(android.R.attr.alertDialogIcon, out, true);
        getWindow().setFeatureDrawableResource(Window.FEATURE_LEFT_ICON, out.resourceId);

        ImageView icon = (ImageView)findViewById(R.id.replace_app_icon);
        icon.setImageDrawable(next.info.applicationInfo.loadIcon(context.getPackageManager()));
        TextView text = (TextView)findViewById(R.id.replace_message);
        text.setText(context.getResources().getString(R.string.launch_warning_replace,
                next.info.applicationInfo.loadLabel(context.getPackageManager()).toString()));
        icon = (ImageView)findViewById(R.id.original_app_icon);
        icon.setImageDrawable(cur.info.applicationInfo.loadIcon(context.getPackageManager()));
        text = (TextView)findViewById(R.id.original_message);
        text.setText(context.getResources().getString(R.string.launch_warning_original,
                cur.info.applicationInfo.loadLabel(context.getPackageManager()).toString()));
    }
}
