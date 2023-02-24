/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.service.notification;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.fail;

import android.net.Uri;
import android.os.Parcel;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.google.common.base.Strings;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class ConditionTest {
    private static final String CLASS = "android.service.notification.Condition";

    @Test
    public void testLongFields_inConstructors() {
        String longString = Strings.repeat("A", 65536);
        Uri longUri = Uri.parse("uri://" + Strings.repeat("A", 65530));

        // Confirm strings are truncated via short constructor
        Condition cond1 = new Condition(longUri, longString, Condition.STATE_TRUE);

        assertEquals(Condition.MAX_STRING_LENGTH, cond1.id.toString().length());
        assertEquals(Condition.MAX_STRING_LENGTH, cond1.summary.length());

        // Confirm strings are truncated via long constructor
        Condition cond2 = new Condition(longUri, longString, longString, longString,
                -1, Condition.STATE_TRUE, Condition.FLAG_RELEVANT_ALWAYS);

        assertEquals(Condition.MAX_STRING_LENGTH, cond2.id.toString().length());
        assertEquals(Condition.MAX_STRING_LENGTH, cond2.summary.length());
        assertEquals(Condition.MAX_STRING_LENGTH, cond2.line1.length());
        assertEquals(Condition.MAX_STRING_LENGTH, cond2.line2.length());
    }

    @Test
    public void testLongFields_viaParcel() {
        // Set fields via reflection to force them to be long, then parcel and unparcel to make sure
        // it gets truncated upon unparcelling.
        Condition cond = new Condition(Uri.parse("uri://placeholder"), "placeholder",
                Condition.STATE_TRUE);

        try {
            String longString = Strings.repeat("A", 65536);
            Uri longUri = Uri.parse("uri://" + Strings.repeat("A", 65530));
            Field id = Class.forName(CLASS).getDeclaredField("id");
            id.setAccessible(true);
            id.set(cond, longUri);
            Field summary = Class.forName(CLASS).getDeclaredField("summary");
            summary.setAccessible(true);
            summary.set(cond, longString);
            Field line1 = Class.forName(CLASS).getDeclaredField("line1");
            line1.setAccessible(true);
            line1.set(cond, longString);
            Field line2 = Class.forName(CLASS).getDeclaredField("line2");
            line2.setAccessible(true);
            line2.set(cond, longString);
        } catch (NoSuchFieldException e) {
            fail(e.toString());
        } catch (ClassNotFoundException e) {
            fail(e.toString());
        } catch (IllegalAccessException e) {
            fail(e.toString());
        }

        Parcel parcel = Parcel.obtain();
        cond.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        Condition fromParcel = new Condition(parcel);
        assertEquals(Condition.MAX_STRING_LENGTH, fromParcel.id.toString().length());
        assertEquals(Condition.MAX_STRING_LENGTH, fromParcel.summary.length());
        assertEquals(Condition.MAX_STRING_LENGTH, fromParcel.line1.length());
        assertEquals(Condition.MAX_STRING_LENGTH, fromParcel.line2.length());
    }
}
