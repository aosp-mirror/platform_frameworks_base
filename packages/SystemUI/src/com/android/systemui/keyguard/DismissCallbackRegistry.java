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
 * limitations under the License.
 */

package com.android.systemui.keyguard;

import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.UiBackground;

import java.util.ArrayList;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Registry holding the current set of {@link IKeyguardDismissCallback}s.
 */
@SysUISingleton
public class DismissCallbackRegistry {

    private final ArrayList<DismissCallbackWrapper> mDismissCallbacks = new ArrayList<>();
    private final Executor mUiBgExecutor;

    @Inject
    public DismissCallbackRegistry(@UiBackground Executor uiBgExecutor) {
        mUiBgExecutor = uiBgExecutor;
    }

    public void addCallback(IKeyguardDismissCallback callback) {
        mDismissCallbacks.add(new DismissCallbackWrapper(callback));
    }

    public void notifyDismissCancelled() {
        for (int i = mDismissCallbacks.size() - 1; i >= 0; i--) {
            DismissCallbackWrapper callback = mDismissCallbacks.get(i);
            mUiBgExecutor.execute(callback::notifyDismissCancelled);
        }
        mDismissCallbacks.clear();
    }

    public void notifyDismissSucceeded() {
        for (int i = mDismissCallbacks.size() - 1; i >= 0; i--) {
            DismissCallbackWrapper callback = mDismissCallbacks.get(i);
            mUiBgExecutor.execute(callback::notifyDismissSucceeded);
        }
        mDismissCallbacks.clear();
    }
}
