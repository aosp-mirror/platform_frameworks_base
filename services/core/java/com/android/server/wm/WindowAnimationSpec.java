/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.wm;

import android.graphics.Point;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;
import android.view.animation.Animation;
import android.view.animation.Transformation;

import com.android.server.wm.LocalAnimationAdapter.AnimationSpec;

/**
 * Animation spec for regular window animations.
 */
public class WindowAnimationSpec implements AnimationSpec {

    private Animation mAnimation;
    private final Point mPosition = new Point();
    private final ThreadLocal<Tmp> mThreadLocalTmps = ThreadLocal.withInitial(Tmp::new);

    public WindowAnimationSpec(Animation animation, Point position)  {
        mAnimation = animation;
        mPosition.set(position.x, position.y);
    }

    @Override
    public boolean getDetachWallpaper() {
        return mAnimation.getDetachWallpaper();
    }

    @Override
    public int getBackgroundColor() {
        return mAnimation.getBackgroundColor();
    }

    @Override
    public long getDuration() {
        return mAnimation.computeDurationHint();
    }

    @Override
    public void apply(Transaction t, SurfaceControl leash, long currentPlayTime) {
        final Tmp tmp = mThreadLocalTmps.get();
        tmp.transformation.clear();
        mAnimation.getTransformation(currentPlayTime, tmp.transformation);
        tmp.transformation.getMatrix().postTranslate(mPosition.x, mPosition.y);
        t.setMatrix(leash, tmp.transformation.getMatrix(), tmp.floats);
        t.setAlpha(leash, tmp.transformation.getAlpha());
    }

    private static class Tmp {
        final Transformation transformation = new Transformation();
        final float[] floats = new float[9];
    }
}
