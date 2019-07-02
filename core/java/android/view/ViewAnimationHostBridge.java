/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.view;

import android.graphics.RenderNode;

/**
 * Maps a View to a RenderNode's AnimationHost
 *
 * @hide
 */
public class ViewAnimationHostBridge implements RenderNode.AnimationHost {
    private final View mView;

    /**
     * @param view the View to bridge to an AnimationHost
     */
    public ViewAnimationHostBridge(View view) {
        mView = view;
    }

    @Override
    public void registerAnimatingRenderNode(RenderNode animator) {
        mView.mAttachInfo.mViewRootImpl.registerAnimatingRenderNode(animator);
    }

    @Override
    public void registerVectorDrawableAnimator(NativeVectorDrawableAnimator animator) {
        mView.mAttachInfo.mViewRootImpl.registerVectorDrawableAnimator(animator);
    }

    @Override
    public boolean isAttached() {
        return mView.mAttachInfo != null;
    }
}
