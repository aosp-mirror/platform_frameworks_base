/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony.gsm.stk;

import android.graphics.Bitmap;

import java.util.List;

/**
 * Container class for proactive command parameters. 
 *
 */
class CommandParams {
    public CtlvCommandDetails cmdDet;

    CommandParams(CtlvCommandDetails cmdDet) {
        this.cmdDet = cmdDet;
    }
}

class CommonUIParams extends CommandParams {
    String mText;
    Bitmap mIcon;
    boolean mIconSelfExplanatory;
    TextAttribute mTextAttrs;

    CommonUIParams(CtlvCommandDetails cmdDet, String text,
            TextAttribute textAttrs) {
        super(cmdDet);

        mText = text;
        mTextAttrs = textAttrs;
        mIconSelfExplanatory = false;
        mIcon = null;
    }

    void setIcon(Bitmap icon) {
        mIcon = icon;
    }

    void setIconSelfExplanatory(boolean iconSelfExplanatory) {
        mIconSelfExplanatory = iconSelfExplanatory;
    }
}

class DisplayTextParams extends CommandParams {
    String text = null;
    Bitmap icon = null;
    List<TextAttribute> textAttrs = null;
    boolean immediateResponse = false;
    boolean userClear = false;
    boolean isHighPriority = false;

    DisplayTextParams(CtlvCommandDetails cmdDet) {
        super(cmdDet);
    }
}

class GetInkeyParams extends CommandParams {
    boolean isYesNo;
    boolean isUcs2;

    GetInkeyParams(CtlvCommandDetails cmdDet, boolean isYesNo,
            boolean isUcs2) {
        super(cmdDet);

        this.isYesNo = isYesNo;
        this.isUcs2 = isUcs2;
    }
}

class GetInputParams extends CommandParams {
    boolean isUcs2;
    boolean isPacked;

    GetInputParams(CtlvCommandDetails cmdDet, boolean isUcs2,
            boolean isPacked) {
        super(cmdDet);

        this.isUcs2 = isUcs2;
        this.isPacked = isPacked;
    }
}

class SelectItemParams extends CommandParams {
    Menu mMenu = null;
    PresentationType mPresentationType;
    int mIconLoadState = LOAD_NO_ICON;

    // loading icons state parameters.
    static final int LOAD_NO_ICON           = 0;
    static final int LOAD_TITLE_ICON        = 1;
    static final int LOAD_ITEMS_ICONS       = 2;
    static final int LOAD_TITLE_ITEMS_ICONS = 3;

    SelectItemParams(CtlvCommandDetails cmdDet, Menu menu,
            PresentationType presentationType, int iconLoadState) {
        super(cmdDet);

        mMenu = menu;
        mPresentationType = presentationType;
        mIconLoadState = iconLoadState;
    }
}

