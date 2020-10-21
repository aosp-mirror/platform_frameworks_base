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

import android.view.ViewGroup;

import com.android.keyguard.dagger.KeyguardBouncerScope;
import com.android.systemui.dagger.qualifiers.RootView;
import com.android.systemui.statusbar.phone.KeyguardBouncer;
import com.android.systemui.util.ViewController;

import javax.inject.Inject;
/** Controller for a {@link KeyguardBouncer}'s Root view. */
@KeyguardBouncerScope
public class KeyguardRootViewController extends ViewController<ViewGroup> {
    @Inject
    public KeyguardRootViewController(@RootView ViewGroup view) {
        super(view);
    }

    public ViewGroup getView() {
        return mView;
    }

    @Override
    protected void onViewAttached() {

    }

    @Override
    protected void onViewDetached() {

    }
}
