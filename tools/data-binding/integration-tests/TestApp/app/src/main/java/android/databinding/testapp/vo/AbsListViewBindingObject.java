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

public class AbsListViewBindingObject extends BindingAdapterBindingObject {
    @Bindable
    private ColorDrawable mListSelector = new ColorDrawable(0xFFFF0000);
    @Bindable
    private boolean mScrollingCache;
    @Bindable
    private boolean mSmoothScrollbar;

    public ColorDrawable getListSelector() {
        return mListSelector;
    }

    public boolean isScrollingCache() {
        return mScrollingCache;
    }

    public boolean isSmoothScrollbar() {
        return mSmoothScrollbar;
    }

    public void changeValues() {
        mListSelector = new ColorDrawable(0xFFFFFFFF);
        mScrollingCache = true;
        mSmoothScrollbar = true;
        notifyChange();
    }
}
