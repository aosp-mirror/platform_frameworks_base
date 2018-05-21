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

package android.app.timezone;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.hamcrest.MockitoHamcrest.argThat;

import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;

import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.Test;

/**
 * Tests for {@link RulesUpdaterContract}.
 */
public class RulesUpdaterContractTest {

    @Test
    public void createUpdaterIntent() throws Exception {
        String packageName = "foobar";
        Intent intent = RulesUpdaterContract.createUpdaterIntent(packageName);

        assertEquals(RulesUpdaterContract.ACTION_TRIGGER_RULES_UPDATE_CHECK, intent.getAction());
        assertEquals(packageName, intent.getPackage());
        assertEquals(Intent.FLAG_INCLUDE_STOPPED_PACKAGES, intent.getFlags());
    }

    @Test
    public void sendBroadcast() throws Exception {
        String packageName = "foobar";
        byte[] tokenBytes = new byte[] { 1, 2, 3, 4, 5 };

        Intent expectedIntent = RulesUpdaterContract.createUpdaterIntent(packageName);
        expectedIntent.putExtra(RulesUpdaterContract.EXTRA_CHECK_TOKEN, tokenBytes);

        Context mockContext = mock(Context.class);

        RulesUpdaterContract.sendBroadcast(mockContext, packageName, tokenBytes);

        verify(mockContext).sendBroadcastAsUser(
                filterEquals(expectedIntent),
                eq(UserHandle.SYSTEM),
                eq(RulesUpdaterContract.UPDATE_TIME_ZONE_RULES_PERMISSION));
    }

    /**
     * Registers a mockito parameter matcher that uses {@link Intent#filterEquals(Intent)}. to
     * check the parameter against the intent supplied.
     */
    private static Intent filterEquals(final Intent expected) {
        final Matcher<Intent> m = new BaseMatcher<Intent>() {
            @Override
            public boolean matches(Object actual) {
                return actual != null && expected.filterEquals((Intent) actual);
            }
            @Override
            public void describeTo(Description description) {
                description.appendText(expected.toString());
            }
        };
        return argThat(m);
    }
}
