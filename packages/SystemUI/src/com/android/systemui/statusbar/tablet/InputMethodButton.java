/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.systemui.statusbar.tablet;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.util.Slog;
import android.util.AttributeSet;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.view.View;
import android.widget.ImageView;

import com.android.server.InputMethodManagerService;
import com.android.systemui.R;

import java.util.List;

public class InputMethodButton extends ImageView {

    private static final String  TAG = "StatusBar/InputMethodButton";
    private static final boolean DEBUG = false;

    private boolean mKeyboardShown;
    private ImageView mIcon;
    // other services we wish to talk to
    private InputMethodManager mImm;

    public InputMethodButton(Context context, AttributeSet attrs) {
        super(context, attrs);

        mKeyboardShown = false;
        // IME hookup
        mImm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        // TODO: read the current icon & visibility state directly from the service

        // TODO: register for notifications about changes to visibility & subtype from service

        setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mImm.showInputMethodSubtypePicker();
            }
        });
    }

    @Override
    protected void onAttachedToWindow() {
        mIcon = (ImageView) findViewById(R.id.imeButton);

        refreshStatusIcon(mKeyboardShown);
    }

    private InputMethodInfo getCurrentInputMethodInfo() {
        String curInputMethodId = Settings.Secure.getString(getContext()
                .getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        List<InputMethodInfo> imis = mImm.getEnabledInputMethodList();
        if (curInputMethodId != null) {
            for (InputMethodInfo imi: imis) {
                if (imi.getId().equals(curInputMethodId)) {
                    return imi;
                }
            }
        }
        return null;
    }

    private Drawable getCurrentSubtypeIcon() {
        final PackageManager pm = getContext().getPackageManager();
        InputMethodInfo imi = getCurrentInputMethodInfo();
        InputMethodSubtype subtype = mImm.getCurrentInputMethodSubtype();
        Drawable icon = null;
        if (imi != null) {
            if (DEBUG) {
                Log.d(TAG, "--- Update icons of IME: " + imi.getPackageName() + "," + subtype);
            }
            if (subtype != null) {
                return pm.getDrawable(imi.getPackageName(), subtype.getIconResId(),
                        imi.getServiceInfo().applicationInfo);
            } else if (imi.getSubtypes().size() > 0) {
                return pm.getDrawable(imi.getPackageName(),
                        imi.getSubtypes().get(0).getIconResId(),
                        imi.getServiceInfo().applicationInfo);
            } else {
                try {
                    return pm.getApplicationInfo(imi.getPackageName(), 0).loadIcon(pm);
                } catch (PackageManager.NameNotFoundException e) {
                    Log.w(TAG, "Current IME cann't be found: " + imi.getPackageName());
                }
            }
        }
        return null;
    }

    private void refreshStatusIcon(boolean keyboardShown) {
        if (!keyboardShown) {
            setVisibility(View.INVISIBLE);
            return;
        } else {
            setVisibility(View.VISIBLE);
        }
        Drawable icon = getCurrentSubtypeIcon();
        if (icon == null) {
            mIcon.setImageResource(R.drawable.ic_sysbar_ime_default);
        } else {
            mIcon.setImageDrawable(icon);
        }
    }

    public void setIMEButtonVisible(boolean visible) {
        mKeyboardShown = visible;
        refreshStatusIcon(mKeyboardShown);
    }
}
