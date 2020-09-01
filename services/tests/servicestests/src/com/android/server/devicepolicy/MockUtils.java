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

import android.content.ComponentName;
import android.content.Intent;
import android.os.BaseBundle;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.ArraySet;

import com.android.server.pm.RestrictionsSet;
import com.android.server.pm.UserRestrictionsUtils;

import com.google.common.base.Objects;

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
                if (!intent.filterEquals((Intent) item)) return false;
                BaseBundle extras = intent.getExtras();
                BaseBundle itemExtras = ((Intent) item).getExtras();
                return (extras == itemExtras) || (extras != null &&
                        extras.kindofEquals(itemExtras));
            }
            @Override
            public void describeTo(Description description) {
                description.appendText(intent.toString());
            }
        };
        return MockitoHamcrest.argThat(m);
    }

    public static Bundle checkUserRestrictions(String... keys) {
        final Bundle expected = DpmTestUtils.newRestrictions(
                java.util.Objects.requireNonNull(keys));
        final Matcher<Bundle> m = new BaseMatcher<Bundle>() {
            @Override
            public boolean matches(Object item) {
                if (item == null) {
                    return false;
                }
                return UserRestrictionsUtils.areEqual((Bundle) item, expected);
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("User restrictions=" + getRestrictionsAsString(expected));
            }
        };
        return MockitoHamcrest.argThat(m);
    }

    public static RestrictionsSet checkUserRestrictions(int userId, String... keys) {
        final RestrictionsSet expected = DpmTestUtils.newRestrictions(userId,
                java.util.Objects.requireNonNull(keys));
        final Matcher<RestrictionsSet> m = new BaseMatcher<RestrictionsSet>() {
            @Override
            public boolean matches(Object item) {
                if (item == null) return false;
                RestrictionsSet actual = (RestrictionsSet) item;
                return UserRestrictionsUtils.areEqual(expected.getRestrictions(userId),
                        actual.getRestrictions(userId));
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("User restrictions=" + getRestrictionsAsString(expected));
            }
        };
        return MockitoHamcrest.argThat(m);
    }

    public static Set<String> checkApps(String... adminApps) {
        final Matcher<Set<String>> m = new BaseMatcher<Set<String>>() {
            @Override
            public boolean matches(Object item) {
                if (item == null) return false;
                final Set<String> actualApps = (Set<String>) item;
                if (adminApps.length != actualApps.size()) {
                    return false;
                }
                final Set<String> copyOfApps = new ArraySet<>(actualApps);
                for (String adminApp : adminApps) {
                    copyOfApps.remove(adminApp);
                }
                return copyOfApps.isEmpty();
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("Apps=" + Arrays.toString(adminApps));
            }
        };
        return MockitoHamcrest.argThat(m);
    }

    private static String getRestrictionsAsString(RestrictionsSet r) {
        final StringBuilder sb = new StringBuilder();
        sb.append("{");

        if (r != null) {
            String sep = "";
            for (int i = 0; i < r.size(); i++) {
                sb.append(sep);
                sep = ",";
                sb.append(
                        String.format("%s= %s", r.keyAt(i), getRestrictionsAsString(r.valueAt(i))));
            }
        }
        sb.append("}");
        return sb.toString();
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
