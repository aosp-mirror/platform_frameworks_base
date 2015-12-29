/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification;

import android.text.TextUtils;
import android.util.Pools;
import android.view.View;
import android.widget.TextView;

/**
 * A transform state of a mText view.
*/
public class TextViewTransformState extends TransformState {

    private static Pools.SimplePool<TextViewTransformState> sInstancePool
            = new Pools.SimplePool<>(40);
    private CharSequence mText;

    @Override
    public void initFrom(View view) {
        super.initFrom(view);
        if (view instanceof TextView) {
            TextView txt = (TextView) view;
            mText = txt.getText();
        }
    }

    @Override
    protected boolean sameAs(TransformState otherState) {
        if (otherState instanceof TextViewTransformState) {
            TextViewTransformState otherTvs = (TextViewTransformState) otherState;
            return TextUtils.equals(otherTvs.mText, mText);
        }
        return super.sameAs(otherState);
    }

    public static TextViewTransformState obtain() {
        TextViewTransformState instance = sInstancePool.acquire();
        if (instance != null) {
            return instance;
        }
        return new TextViewTransformState();
    }

    @Override
    public void recycle() {
        sInstancePool.release(this);
    }

    @Override
    protected void reset() {
        super.reset();
        mText = null;
    }
}
