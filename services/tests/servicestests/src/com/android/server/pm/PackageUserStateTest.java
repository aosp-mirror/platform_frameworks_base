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

package com.android.server.pm;

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ASK;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import android.content.pm.PackageUserState;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.ArraySet;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class PackageUserStateTest {

    @Test
    public void testPackageUserState01()  {
        final PackageUserState testUserState = new PackageUserState();
        PackageUserState oldUserState;

        oldUserState = new PackageUserState();
        assertThat(testUserState.equals(null), is(false));
        assertThat(testUserState.equals(testUserState), is(true));
        assertThat(testUserState.equals(oldUserState), is(true));

        oldUserState = new PackageUserState();
        oldUserState.appLinkGeneration = 6;
        assertThat(testUserState.equals(oldUserState), is(false));

        oldUserState = new PackageUserState();
        oldUserState.ceDataInode = 4000L;
        assertThat(testUserState.equals(oldUserState), is(false));

        oldUserState = new PackageUserState();
        oldUserState.domainVerificationStatus = INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ASK;
        assertThat(testUserState.equals(oldUserState), is(false));

        oldUserState = new PackageUserState();
        oldUserState.enabled = COMPONENT_ENABLED_STATE_ENABLED;
        assertThat(testUserState.equals(oldUserState), is(false));

        oldUserState = new PackageUserState();
        oldUserState.hidden = true;
        assertThat(testUserState.equals(oldUserState), is(false));

        oldUserState = new PackageUserState();
        oldUserState.installed = false;
        assertThat(testUserState.equals(oldUserState), is(false));

        oldUserState = new PackageUserState();
        oldUserState.notLaunched = true;
        assertThat(testUserState.equals(oldUserState), is(false));

        oldUserState = new PackageUserState();
        oldUserState.stopped = true;
        assertThat(testUserState.equals(oldUserState), is(false));

        oldUserState = new PackageUserState();
        oldUserState.suspended = true;
        assertThat(testUserState.equals(oldUserState), is(false));
    }

    @Test
    public void testPackageUserState02()  {
        final PackageUserState testUserState01 = new PackageUserState();
        PackageUserState oldUserState;

        oldUserState = new PackageUserState();
        oldUserState.lastDisableAppCaller = "unit_test";
        assertThat(testUserState01.equals(oldUserState), is(false));

        final PackageUserState testUserState02 = new PackageUserState();
        testUserState02.lastDisableAppCaller = "unit_test";
        assertThat(testUserState02.equals(oldUserState), is(true));

        final PackageUserState testUserState03 = new PackageUserState();
        testUserState03.lastDisableAppCaller = "unit_test_00";
        assertThat(testUserState03.equals(oldUserState), is(false));
    }

    @Test
    public void testPackageUserState03()  {
        final PackageUserState oldUserState = new PackageUserState();

        // only new user state has array defined; different
        final PackageUserState testUserState01 = new PackageUserState();
        testUserState01.disabledComponents = new ArraySet<>();
        assertThat(testUserState01.equals(oldUserState), is(false));

        // only old user state has array defined; different
        final PackageUserState testUserState02 = new PackageUserState();
        oldUserState.disabledComponents = new ArraySet<>();
        assertThat(testUserState02.equals(oldUserState), is(false));

        // both states have array defined; not different
        final PackageUserState testUserState03 = new PackageUserState();
        testUserState03.disabledComponents = new ArraySet<>();
        assertThat(testUserState03.equals(oldUserState), is(true));
        // fewer elements in old user state; different
        testUserState03.disabledComponents.add("com.android.unit_test01");
        testUserState03.disabledComponents.add("com.android.unit_test02");
        testUserState03.disabledComponents.add("com.android.unit_test03");
        oldUserState.disabledComponents.add("com.android.unit_test03");
        oldUserState.disabledComponents.add("com.android.unit_test02");
        assertThat(testUserState03.equals(oldUserState), is(false));
        // same elements in old user state; not different
        oldUserState.disabledComponents.add("com.android.unit_test01");
        assertThat(testUserState03.equals(oldUserState), is(true));
        // more elements in old user state; different
        oldUserState.disabledComponents.add("com.android.unit_test04");
        assertThat(testUserState03.equals(oldUserState), is(false));
        // different elements in old user state; different
        testUserState03.disabledComponents.add("com.android.unit_test_04");
        assertThat(testUserState03.equals(oldUserState), is(false));
    }

    @Test
    public void testPackageUserState04()  {
        final PackageUserState oldUserState = new PackageUserState();

        // only new user state has array defined; different
        final PackageUserState testUserState01 = new PackageUserState();
        testUserState01.enabledComponents = new ArraySet<>();
        assertThat(testUserState01.equals(oldUserState), is(false));

        // only old user state has array defined; different
        final PackageUserState testUserState02 = new PackageUserState();
        oldUserState.enabledComponents = new ArraySet<>();
        assertThat(testUserState02.equals(oldUserState), is(false));

        // both states have array defined; not different
        final PackageUserState testUserState03 = new PackageUserState();
        testUserState03.enabledComponents = new ArraySet<>();
        assertThat(testUserState03.equals(oldUserState), is(true));
        // fewer elements in old user state; different
        testUserState03.enabledComponents.add("com.android.unit_test01");
        testUserState03.enabledComponents.add("com.android.unit_test02");
        testUserState03.enabledComponents.add("com.android.unit_test03");
        oldUserState.enabledComponents.add("com.android.unit_test03");
        oldUserState.enabledComponents.add("com.android.unit_test02");
        assertThat(testUserState03.equals(oldUserState), is(false));
        // same elements in old user state; not different
        oldUserState.enabledComponents.add("com.android.unit_test01");
        assertThat(testUserState03.equals(oldUserState), is(true));
        // more elements in old user state; different
        oldUserState.enabledComponents.add("com.android.unit_test04");
        assertThat(testUserState03.equals(oldUserState), is(false));
        // different elements in old user state; different
        testUserState03.enabledComponents.add("com.android.unit_test_04");
        assertThat(testUserState03.equals(oldUserState), is(false));
    }
}
