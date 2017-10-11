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
 * limitations under the License.
 */

package android.content;

import android.content.pm.UserInfo;
import android.os.RemoteException;
import android.support.test.filters.LargeTest;

/**
 * To run the tests, use
 *
 * runtest -c android.content.SecondaryUserContentResolverTest frameworks-core
 *
 * or the following steps:
 *
 * Build: m FrameworksCoreTests
 * Install: adb install -r \
 *     ${ANDROID_PRODUCT_OUT}/data/app/FrameworksCoreTests/FrameworksCoreTests.apk
 * Run: adb shell am instrument -e class android.content.SecondaryUserContentResolverTest -w \
 *     com.android.frameworks.coretests/android.support.test.runner.AndroidJUnitRunner
 */
@LargeTest
public class SecondaryUserContentResolverTest extends AbstractCrossUserContentResolverTest {
    @Override
    protected UserInfo createUser() throws RemoteException {
        return mUm.createUser("Secondary user", 0);
    }
}
