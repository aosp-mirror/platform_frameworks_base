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

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.fail;

import static org.junit.Assert.assertThrows;

import android.app.Flags;
import android.net.Uri;
import android.os.Parcel;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.google.common.base.Strings;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class ConditionTest {
    private static final String CLASS = "android.service.notification.Condition";

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Test
    public void testLongFields_inConstructors_classic() {
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
    public void testLongFields_viaParcel_classic() {
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

    @Test
    public void testLongFields_inConstructors() {
        mSetFlagsRule.enableFlags(Flags.FLAG_MODES_API);
        String longString = Strings.repeat("A", 65536);
        Uri longUri = Uri.parse("uri://" + Strings.repeat("A", 65530));

        // Confirm strings are truncated via short constructor
        Condition cond1 = new Condition(longUri, longString, Condition.STATE_TRUE,
                Condition.SOURCE_CONTEXT);

        assertThat(cond1.id.toString()).hasLength(Condition.MAX_STRING_LENGTH);
        assertThat(cond1.summary).hasLength(Condition.MAX_STRING_LENGTH);

        // Confirm strings are truncated via long constructor
        Condition cond2 = new Condition(longUri, longString, longString, longString,
                -1, Condition.STATE_TRUE, Condition.SOURCE_CONTEXT, Condition.FLAG_RELEVANT_ALWAYS);

        assertThat(cond2.id.toString()).hasLength(Condition.MAX_STRING_LENGTH);
        assertThat(cond2.summary).hasLength(Condition.MAX_STRING_LENGTH);
        assertThat(cond2.line1).hasLength(Condition.MAX_STRING_LENGTH);
        assertThat(cond2.line2).hasLength(Condition.MAX_STRING_LENGTH);
    }

    @Test
    public void testLongFields_viaParcel() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_MODES_API);
        // Set fields via reflection to force them to be long, then parcel and unparcel to make sure
        // it gets truncated upon unparcelling.
        Condition cond = new Condition(Uri.parse("uri://placeholder"), "placeholder",
                Condition.STATE_TRUE, Condition.SOURCE_CONTEXT);

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

        Parcel parcel = Parcel.obtain();
        cond.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        Condition fromParcel = new Condition(parcel);
        assertThat(fromParcel.id.toString()).hasLength(Condition.MAX_STRING_LENGTH);
        assertThat(fromParcel.summary).hasLength(Condition.MAX_STRING_LENGTH);
        assertThat(fromParcel.line1).hasLength(Condition.MAX_STRING_LENGTH);
        assertThat(fromParcel.line2).hasLength(Condition.MAX_STRING_LENGTH);
    }

    @Test
    public void testEquals() {
        mSetFlagsRule.enableFlags(Flags.FLAG_MODES_API);

        Condition cond1 = new Condition(Uri.parse("uri://placeholder"), "placeholder",
                Condition.STATE_TRUE, Condition.SOURCE_USER_ACTION);
        Condition cond2 = new Condition(Uri.parse("uri://placeholder"), "placeholder",
                "", "", -1,
                Condition.STATE_TRUE, Condition.SOURCE_SCHEDULE, Condition.FLAG_RELEVANT_ALWAYS);

        assertThat(cond1).isNotEqualTo(cond2);
        Condition cond3 = new Condition(Uri.parse("uri://placeholder"), "placeholder",
                Condition.STATE_TRUE, Condition.SOURCE_SCHEDULE);
        assertThat(cond3).isEqualTo(cond2);
    }

    @Test
    public void testParcelConstructor() {
        mSetFlagsRule.enableFlags(Flags.FLAG_MODES_API);

        Condition cond = new Condition(Uri.parse("uri://placeholder"), "placeholder",
                Condition.STATE_TRUE, Condition.SOURCE_USER_ACTION);

        Parcel parcel = Parcel.obtain();
        cond.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        Condition fromParcel = new Condition(parcel);
        assertThat(fromParcel).isEqualTo(cond);
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_API)
    public void constructor_unspecifiedSource_succeeds() {
        new Condition(Uri.parse("id"), "Summary", Condition.STATE_TRUE);
        // No exception.
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_API)
    public void constructor_validSource_succeeds() {
        new Condition(Uri.parse("id"), "Summary", Condition.STATE_TRUE, Condition.SOURCE_CONTEXT);
        // No exception.
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_API)
    public void constructor_invalidSource_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new Condition(Uri.parse("uri"), "Summary", Condition.STATE_TRUE, 1000));
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_API)
    public void constructor_parcelWithInvalidSource_throws() {
        Condition original = new Condition(Uri.parse("condition"), "Summary", Condition.STATE_TRUE,
                Condition.SOURCE_SCHEDULE);
        Parcel parcel = Parcel.obtain();
        original.writeToParcel(parcel, 0);

        // Tweak the parcel to contain and invalid source value.
        parcel.setDataPosition(parcel.dataPosition() - 8); // going back two int fields.
        parcel.writeInt(100);
        parcel.setDataPosition(0);

        assertThrows(IllegalArgumentException.class, () -> new Condition(parcel));
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_API)
    public void validate_invalidSource_throws() throws Exception {
        Condition condition = new Condition(Uri.parse("condition"), "Summary", Condition.STATE_TRUE,
                Condition.SOURCE_SCHEDULE);

        Field typeField = Condition.class.getDeclaredField("source");

        // Reflection on reflection (ugh) to make a final field non-final
        Field fieldAccessFlagsField = Field.class.getDeclaredField("accessFlags");
        fieldAccessFlagsField.setAccessible(true);
        fieldAccessFlagsField.setInt(typeField, typeField.getModifiers() & ~Modifier.FINAL);

        typeField.setInt(condition, 30);

        assertThrows(IllegalArgumentException.class, condition::validate);
    }
}
