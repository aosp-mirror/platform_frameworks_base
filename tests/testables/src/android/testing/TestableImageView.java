/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package android.testing;

import android.annotation.DrawableRes;
import android.annotation.Nullable;
import android.content.Context;
import android.util.AttributeSet;
import android.widget.ImageView;

public class TestableImageView extends ImageView {

    private int mLastResId = -1;

    public TestableImageView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void setImageResource(@DrawableRes int resId) {
        mLastResId = resId;
        super.setImageResource(resId);
    }

    public int getLastImageResource() {
        return mLastResId;
    }
}
