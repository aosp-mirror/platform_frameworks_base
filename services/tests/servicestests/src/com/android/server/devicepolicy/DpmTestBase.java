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
import android.test.AndroidTestCase;

import java.io.File;

public abstract class DpmTestBase extends AndroidTestCase {
    public static final String TAG = "DpmTest";

    protected Context mRealTestContext;
    protected DpmMockContext mMockContext;

    public File dataDir;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mRealTestContext = super.getContext();
        mMockContext = new DpmMockContext(super.getContext());

        dataDir = new File(mRealTestContext.getCacheDir(), "test-data");
        DpmTestUtils.clearDir(dataDir);
    }

    @Override
    public DpmMockContext getContext() {
        return mMockContext;
    }
}
