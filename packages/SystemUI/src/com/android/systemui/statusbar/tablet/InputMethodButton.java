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
import android.os.IBinder;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.view.View;
import android.widget.ImageView;

import com.android.systemui.R;

import java.util.List;

public class InputMethodButton extends ImageView {

    private static final String  TAG = "StatusBar/InputMethodButton";
    private static final boolean DEBUG = false;

    // These values are defined in Settings application.
    private static final int ID_IME_BUTTON_VISIBILITY_AUTO = 0;
    private static final int ID_IME_BUTTON_VISIBILITY_ALWAYS_SHOW = 1;
    private static final int ID_IME_BUTTON_VISIBILITY_ALWAYS_HIDE = 2;

    // other services we wish to talk to
    private final InputMethodManager mImm;
    private final int mId;
    private ImageView mIcon;
    private IBinder mToken;
    private boolean mShowButton = false;
    private boolean mScreenLocked = false;
    private boolean mHardKeyboardAvailable;

    // Please refer to InputMethodManagerService.TAG_TRY_SUPPRESSING_IME_SWITCHER
    private static final String TAG_TRY_SUPPRESSING_IME_SWITCHER = "TrySuppressingImeSwitcher";

    public InputMethodButton(Context context, AttributeSet attrs) {
        super(context, attrs);

        // Resource Id of the input method button. This id is defined in status_bar.xml
        mId = getId();
        // IME hookup
        mImm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
    }

    @Override
    protected void onAttachedToWindow() {
        mIcon = (ImageView) findViewById(mId);

        refreshStatusIcon();
    }

    // Refer to InputMethodManagerService.needsToShowImeSwitchOngoingNotification()
    private boolean needsToShowIMEButtonWhenVisibilityAuto() {
        List<InputMethodInfo> imis = mImm.getEnabledInputMethodList();
        final int N = imis.size();
        if (N > 2) return true;
        if (N < 1) return false;
        int nonAuxCount = 0;
        int auxCount = 0;
        InputMethodSubtype nonAuxSubtype = null;
        InputMethodSubtype auxSubtype = null;
        for(int i = 0; i < N; ++i) {
            final InputMethodInfo imi = imis.get(i);
            final List<InputMethodSubtype> subtypes = mImm.getEnabledInputMethodSubtypeList(
                    imi, true);
            final int subtypeCount = subtypes.size();
            if (subtypeCount == 0) {
                ++nonAuxCount;
            } else {
                for (int j = 0; j < subtypeCount; ++j) {
                    final InputMethodSubtype subtype = subtypes.get(j);
                    if (!subtype.isAuxiliary()) {
                        ++nonAuxCount;
                        nonAuxSubtype = subtype;
                    } else {
                        ++auxCount;
                        auxSubtype = subtype;
                    }
                }
            }
        }
        if (nonAuxCount > 1 || auxCount > 1) {
            return true;
        } else if (nonAuxCount == 1 && auxCount == 1) {
            if (nonAuxSubtype != null && auxSubtype != null
                    && nonAuxSubtype.getLocale().equals(auxSubtype.getLocale())
                    && nonAuxSubtype.containsExtraValueKey(TAG_TRY_SUPPRESSING_IME_SWITCHER)) {
                return false;
            }
            return true;
        }
        return false;
    }

    private boolean needsToShowIMEButton() {
        if (!mShowButton || mScreenLocked) return false;

        if (mHardKeyboardAvailable) {
            return true;
        }

        final int visibility = loadInputMethodSelectorVisibility();
        switch (visibility) {
            case ID_IME_BUTTON_VISIBILITY_AUTO:
                return needsToShowIMEButtonWhenVisibilityAuto();
            case ID_IME_BUTTON_VISIBILITY_ALWAYS_SHOW:
                return true;
            case ID_IME_BUTTON_VISIBILITY_ALWAYS_HIDE:
                return false;
        }
        return false;
    }

    private void refreshStatusIcon() {
        if (mIcon == null) {
            return;
        }
        if (!needsToShowIMEButton()) {
            setVisibility(View.GONE);
            return;
        } else {
            setVisibility(View.VISIBLE);
        }
        mIcon.setImageResource(R.drawable.ic_sysbar_ime);
    }

    private int loadInputMethodSelectorVisibility() {
        return Settings.Secure.getInt(getContext().getContentResolver(),
                Settings.Secure.INPUT_METHOD_SELECTOR_VISIBILITY, ID_IME_BUTTON_VISIBILITY_AUTO);
    }

    public void setIconImage(int resId) {
        if (mIcon != null) {
            mIcon.setImageResource(resId);
        }
    }

    public void setImeWindowStatus(IBinder token, boolean showButton) {
        mToken = token;
        mShowButton = showButton;
        refreshStatusIcon();
    }

    public void setHardKeyboardStatus(boolean available) {
        if (mHardKeyboardAvailable != available) {
            mHardKeyboardAvailable = available;
            refreshStatusIcon();
        }
    }

    public void setScreenLocked(boolean locked) {
        mScreenLocked = locked;
        refreshStatusIcon();
    }
}
