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
package android.databinding.testapp.vo;

import android.databinding.Bindable;
import android.graphics.drawable.ColorDrawable;

public class TabWidgetBindingObject extends BindingAdapterBindingObject {
    @Bindable
    private ColorDrawable mDivider = new ColorDrawable(0xFF0000FF);
    @Bindable
    private boolean mTabStripEnabled;
    @Bindable
    private ColorDrawable mTabStripLeft = new ColorDrawable(0xFF00FF00);
    @Bindable
    private ColorDrawable mTabStripRight = new ColorDrawable(0xFFFF0000);

    public ColorDrawable getDivider() {
        return mDivider;
    }

    public ColorDrawable getTabStripLeft() {
        return mTabStripLeft;
    }

    public ColorDrawable getTabStripRight() {
        return mTabStripRight;
    }

    public boolean isTabStripEnabled() {
        return mTabStripEnabled;
    }

    public void changeValues() {
        mDivider = new ColorDrawable(0xFF111111);
        mTabStripEnabled = true;
        mTabStripLeft = new ColorDrawable(0xFF222222);
        mTabStripRight = new ColorDrawable(0xFF333333);
        notifyChange();
    }
}
