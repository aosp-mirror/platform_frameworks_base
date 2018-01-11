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

import com.google.common.base.Objects;

import com.android.internal.util.Preconditions;
import com.android.server.pm.UserRestrictionsUtils;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.ArraySet;

import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.mockito.hamcrest.MockitoHamcrest;

import java.util.Arrays;
import java.util.Set;

public class MockUtils {
    private MockUtils() {
    }

    public static UserHandle checkUserHandle(final int userId) {
        final Matcher<UserHandle> m = new BaseMatcher<UserHandle>() {
            @Override
            public boolean matches(Object item) {
                if (item == null) return false;
                return Objects.equal(((UserHandle) item).getIdentifier(), userId);
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("UserHandle: user-id= \"" + userId + "\"");
            }
        };
        return MockitoHamcrest.argThat(m);
    }

    public static Intent checkIntentComponent(final ComponentName component) {
        final Matcher<Intent> m = new BaseMatcher<Intent>() {
            @Override
            public boolean matches(Object item) {
                if (item == null) return false;
                return Objects.equal(((Intent) item).getComponent(), component);
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("Intent: component=\"" + component + "\"");
            }
        };
        return MockitoHamcrest.argThat(m);
    }

    public static Intent checkIntentAction(final String action) {
        final Matcher<Intent> m = new BaseMatcher<Intent>() {
            @Override
            public boolean matches(Object item) {
                if (item == null) return false;
                return Objects.equal(((Intent) item).getAction(), action);
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("Intent: action=\"" + action + "\"");
            }
        };
        return MockitoHamcrest.argThat(m);
    }

    public static Intent checkIntent(final Intent intent) {
        final Matcher<Intent> m = new BaseMatcher<Intent>() {
            @Override
            public boolean matches(Object item) {
                if (item == null) return false;
                return intent.filterEquals((Intent) item);
            }
            @Override
            public void describeTo(Description description) {
                description.appendText(intent.toString());
            }
        };
        return MockitoHamcrest.argThat(m);
    }

    public static Bundle checkUserRestrictions(String... keys) {
        final Bundle expected = DpmTestUtils.newRestrictions(Preconditions.checkNotNull(keys));
        final Matcher<Bundle> m = new BaseMatcher<Bundle>() {
            @Override
            public boolean matches(Object item) {
                if (item == null) return false;
                return UserRestrictionsUtils.areEqual((Bundle) item, expected);
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("User restrictions=" + getRestrictionsAsString(expected));
            }
        };
        return MockitoHamcrest.argThat(m);
    }

    public static Set<String> checkAdminApps(String... adminApps) {
        final Matcher<Set<String>> m = new BaseMatcher<Set<String>>() {
            @Override
            public boolean matches(Object item) {
                if (item == null) return false;
                final Set<String> actualAdminApps = (Set<String>) item;
                if (adminApps.length != actualAdminApps.size()) {
                    return false;
                }
                final Set<String> copyOfAdmins = new ArraySet<>(actualAdminApps);
                for (String adminApp : adminApps) {
                    copyOfAdmins.remove(adminApp);
                }
                return copyOfAdmins.isEmpty();
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("Admin apps=" + Arrays.toString(adminApps));
            }
        };
        return MockitoHamcrest.argThat(m);
    }

    private static String getRestrictionsAsString(Bundle b) {
        final StringBuilder sb = new StringBuilder();
        sb.append("[");

        if (b != null) {
            String sep = "";
            for (String key : b.keySet()) {
                sb.append(sep);
                sep = ",";
                sb.append(key);
            }
        }
        sb.append("]");
        return sb.toString();
    }
}
