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

import android.graphics.drawable.Icon;
import android.util.Pools;
import android.view.View;
import android.widget.ImageView;

import com.android.systemui.R;

/**
 * A transform state of a image view.
*/
public class ImageTransformState extends TransformState {

    public static final int ICON_TAG = R.id.image_icon_tag;
    private static Pools.SimplePool<ImageTransformState> sInstancePool
            = new Pools.SimplePool<>(40);
    private Icon mIcon;

    @Override
    public void initFrom(View view) {
        super.initFrom(view);
        if (view instanceof ImageView) {
            mIcon = (Icon) view.getTag(ICON_TAG);
        }
    }

    @Override
    protected boolean sameAs(TransformState otherState) {
        if (otherState instanceof ImageTransformState) {
            return mIcon != null && mIcon.sameAs(((ImageTransformState) otherState).getIcon());
        }
        return super.sameAs(otherState);
    }

    public Icon getIcon() {
        return mIcon;
    }

    public static ImageTransformState obtain() {
        ImageTransformState instance = sInstancePool.acquire();
        if (instance != null) {
            return instance;
        }
        return new ImageTransformState();
    }

    @Override
    protected boolean transformScale() {
        return true;
    }

    @Override
    public void recycle() {
        super.recycle();
        sInstancePool.release(this);
    }

    @Override
    protected void reset() {
        super.reset();
        mIcon = null;
    }
}
