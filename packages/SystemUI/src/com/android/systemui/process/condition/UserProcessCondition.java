/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.process.condition;

import com.android.systemui.process.ProcessWrapper;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shared.condition.Condition;

import javax.inject.Inject;

/**
 * {@link UserProcessCondition} provides a signal when the process handle belongs to the current
 * user.
 */
public class UserProcessCondition extends Condition {
    private final ProcessWrapper mProcessWrapper;
    private final UserTracker mUserTracker;

    @Inject
    public UserProcessCondition(ProcessWrapper processWrapper, UserTracker userTracker) {
        mProcessWrapper = processWrapper;
        mUserTracker = userTracker;
    }

    @Override
    protected void start() {
        updateCondition(mUserTracker.getUserId()
                == mProcessWrapper.getUserHandleIdentifier());
    }

    @Override
    protected void stop() {
    }
}
