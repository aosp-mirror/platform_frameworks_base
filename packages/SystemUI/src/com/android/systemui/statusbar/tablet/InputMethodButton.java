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
import android.os.IBinder;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InputMethodButton extends ImageView {

    private static final String  TAG = "StatusBar/InputMethodButton";
    private static final boolean DEBUG = false;

    private static final int ID_IME_SWITCH_BUTTON = R.id.imeSwitchButton;
    // IME shortcut button is disabled.
    private static final int ID_IME_SHORTCUT_BUTTON = 0;

    // These values are defined in Settings application.
    private static final int ID_IME_BUTTON_VISIBILITY_AUTO = 0;
    private static final int ID_IME_BUTTON_VISIBILITY_ALWAYS_SHOW = 1;
    private static final int ID_IME_BUTTON_VISIBILITY_ALWAYS_HIDE = 2;

    // other services we wish to talk to
    private final InputMethodManager mImm;
    private final int mId;
    // Cache of InputMethodsInfo
    private final HashMap<String, InputMethodInfo> mInputMethodsInfo =
            new HashMap<String, InputMethodInfo>();
    private ImageView mIcon;
    private IBinder mToken;
    private boolean mKeyboardVisible = false;
    private boolean mScreenLocked = false;
    private InputMethodInfo mShortcutInfo;
    private InputMethodSubtype mShortcutSubtype;

    public InputMethodButton(Context context, AttributeSet attrs) {
        super(context, attrs);

        // Resource Id of the input method button. This id is defined in status_bar.xml
        mId = getId();
        // IME hookup
        mImm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        // TODO: read the current icon & visibility state directly from the service

        // TODO: register for notifications about changes to visibility & subtype from service

        setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switch (mId) {
                    case ID_IME_SWITCH_BUTTON:
                        mImm.showInputMethodPicker();
                        break;
                    case ID_IME_SHORTCUT_BUTTON:
                        if (mToken != null && mShortcutInfo != null) {
                            mImm.setInputMethodAndSubtype(
                                    mToken, mShortcutInfo.getId(), mShortcutSubtype);
                        }
                        break;
                }
            }
        });
    }

    @Override
    protected void onAttachedToWindow() {
        mIcon = (ImageView) findViewById(mId);

        refreshStatusIcon();
    }

    private InputMethodInfo getCurrentInputMethodInfo() {
        String curInputMethodId = Settings.Secure.getString(getContext()
                .getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (!mInputMethodsInfo.containsKey(curInputMethodId)) {
            mInputMethodsInfo.clear();
            List<InputMethodInfo> imis = mImm.getInputMethodList();
            for (int i = 0; i < imis.size(); ++i) {
                InputMethodInfo imi = imis.get(i);
                mInputMethodsInfo.put(imi.getId(), imi);
            }
        }
        return mInputMethodsInfo.get(curInputMethodId);
    }

    // TODO: Need to show an appropriate drawable for this shortcut button,
    // if there are two or more shortcut input methods contained in this button.
    // And need to add other methods to handle multiple shortcuts as appropriate.
    private Drawable getShortcutInputMethodAndSubtypeDrawable() {
        Map<InputMethodInfo, List<InputMethodSubtype>> shortcuts =
                mImm.getShortcutInputMethodsAndSubtypes();
        if (shortcuts.size() > 0) {
            for (InputMethodInfo imi: shortcuts.keySet()) {
                List<InputMethodSubtype> subtypes = shortcuts.get(imi);
                // TODO: Returns the first found IMI for now. Should handle all shortcuts as
                // appropriate.
                mShortcutInfo = imi;
                // TODO: Pick up the first found subtype for now. Should handle all subtypes
                // as appropriate.
                mShortcutSubtype = subtypes.size() > 0 ? subtypes.get(0) : null;
                return getSubtypeIcon(mShortcutInfo, mShortcutSubtype);
            }
        }
        return null;
    }

    private Drawable getSubtypeIcon(InputMethodInfo imi, InputMethodSubtype subtype) {
        final PackageManager pm = getContext().getPackageManager();
        if (imi != null) {
            if (DEBUG) {
                Log.d(TAG, "Update icons of IME: " + imi.getPackageName() + ","
                        + subtype.getLocale() + "," + subtype.getMode());
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
                    Log.w(TAG, "IME can't be found: " + imi.getPackageName());
                }
            }
        }
        return null;
    }

    // Display IME switcher icon only when all of the followings are true:
    // * There is only one enabled IME on the device.  (Note that the IME should be the system IME)
    // * There are no explicitly enabled (by the user) subtypes of the IME, or the IME doesn't have
    // its subtypes at all
    private boolean needsToShowIMEButton() {
        if (!mKeyboardVisible || mScreenLocked) return false;
        List<InputMethodInfo> imis = mImm.getEnabledInputMethodList();
        final int size = imis.size();
        final int visibility = loadInputMethodSelectorVisibility();
        switch (visibility) {
            case ID_IME_BUTTON_VISIBILITY_AUTO:
                return size > 1 || (size == 1
                        && mImm.getEnabledInputMethodSubtypeList(imis.get(0), false).size() > 1);
            case ID_IME_BUTTON_VISIBILITY_ALWAYS_SHOW:
                return true;
            case ID_IME_BUTTON_VISIBILITY_ALWAYS_HIDE:
                return false;
        }
        return false;
    }

    private void refreshStatusIcon() {
        if (!needsToShowIMEButton()) {
            setVisibility(View.INVISIBLE);
            return;
        } else {
            setVisibility(View.VISIBLE);
        }
        Drawable icon = null;
        switch (mId) {
            case ID_IME_SWITCH_BUTTON:
                // TODO: Just showing the first shortcut IME subtype for now. Should handle all
                // shortcuts as appropriate.
                icon = getSubtypeIcon(getCurrentInputMethodInfo(),
                        mImm.getCurrentInputMethodSubtype());
                break;
            case ID_IME_SHORTCUT_BUTTON:
                icon = getShortcutInputMethodAndSubtypeDrawable();
                break;
        }
        if (mIcon != null) {
            if (icon == null) {
                mIcon.setImageResource(R.drawable.ic_sysbar_ime_default);
            } else {
                mIcon.setImageDrawable(icon);
            }
        }
    }

    private int loadInputMethodSelectorVisibility() {
        return Settings.Secure.getInt(getContext().getContentResolver(),
                Settings.Secure.INPUT_METHOD_SELECTOR_VISIBILITY, ID_IME_BUTTON_VISIBILITY_AUTO);
    }

    public void setIMEButtonVisible(IBinder token, boolean keyboardVisible) {
        mToken = token;
        mKeyboardVisible = keyboardVisible;
        refreshStatusIcon();
    }

    public void setScreenLocked(boolean locked) {
        mScreenLocked = locked;
        refreshStatusIcon();
    }
}
