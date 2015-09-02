/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.devicepolicy;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.UserManager;

import static org.mockito.Mockito.mock;

public class DpmMockContext extends ContextWrapper {
    private final UserManager mMockUserManager;


    public DpmMockContext(Context context) {
        super(context);
        mMockUserManager = mock(UserManager.class);
    }

    public UserManager getMockUserManager() {
        return mMockUserManager;
    }

    @Override
    public Object getSystemService(String name) {
        switch (name) {
            case Context.USER_SERVICE:
                return mMockUserManager;
        }
        return super.getSystemService(name);
    }
}
