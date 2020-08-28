/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.keyguard;

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.dagger.KeyguardBouncerScope;
import com.android.systemui.util.ViewController;

import javax.inject.Inject;

/** Controller for a {@link KeyguardHostView}. */
@KeyguardBouncerScope
public class KeyguardHostViewController extends ViewController {
    private final KeyguardHostView mView;
    private final KeyguardSecurityContainerController mKeyguardSecurityContainerController;
    private final LockPatternUtils mLockPatternUtils;
    private final ViewMediatorCallback mViewMediatorCallback;

    @Inject
    public KeyguardHostViewController(KeyguardHostView view,
            KeyguardSecurityContainerController keyguardSecurityContainerController,
            LockPatternUtils lockPatternUtils,
            ViewMediatorCallback viewMediatorCallback) {
        super(view);
        mView = view;
        mKeyguardSecurityContainerController = keyguardSecurityContainerController;
        mLockPatternUtils = lockPatternUtils;
        mViewMediatorCallback = viewMediatorCallback;
    }

    /** Initialize the Controller. */
    public void init() {
        super.init();
        mView.setLockPatternUtils(mLockPatternUtils);
        mView.setViewMediatorCallback(mViewMediatorCallback);
        mKeyguardSecurityContainerController.init();
    }

    @Override
    protected void onViewAttached() {
    }

    @Override
    protected void onViewDetached() {
    }

    public KeyguardHostView getView() {
        return mView;
    }

     /** Called before this view is being removed. */
    public void cleanUp() {
        mKeyguardSecurityContainerController.onPause();
    }
}
