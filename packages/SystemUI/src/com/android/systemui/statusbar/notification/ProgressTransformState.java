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

import android.util.Pools;

/**
 * A transform state of a progress view.
*/
public class ProgressTransformState extends TransformState {

    private static Pools.SimplePool<ProgressTransformState> sInstancePool
            = new Pools.SimplePool<>(40);

    @Override
    protected boolean sameAs(TransformState otherState) {
        if (otherState instanceof ProgressTransformState) {
            return true;
        }
        return super.sameAs(otherState);
    }

    public static ProgressTransformState obtain() {
        ProgressTransformState instance = sInstancePool.acquire();
        if (instance != null) {
            return instance;
        }
        return new ProgressTransformState();
    }

    @Override
    public void recycle() {
        super.recycle();
        sInstancePool.release(this);
    }
}
