/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.documentsui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

public class DirectoryView extends FrameLayout {
    private float mPosition = 0f;

    private int mWidth;

    public DirectoryView(Context context) {
        super(context);
    }

    public DirectoryView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mWidth = w;
        setPosition(mPosition);
    }

    public float getPosition() {
        return mPosition;
    }

    public void setPosition(float position) {
        mPosition = position;
        setX((mWidth > 0) ? (mPosition * mWidth) : 0);

        if (mPosition != 0) {
            setTranslationZ(getResources().getDimensionPixelSize(R.dimen.dir_elevation));
        } else {
            setTranslationZ(0);
        }
    }
}
