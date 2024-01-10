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

package android.app;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.fail;

import static org.junit.Assert.assertThrows;

import android.content.ComponentName;
import android.net.Uri;
import android.os.Parcel;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.service.notification.ZenDeviceEffects;
import android.service.notification.ZenPolicy;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.google.common.base.Strings;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class AutomaticZenRuleTest {
    private static final String CLASS = "android.app.AutomaticZenRule";

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Test
    public void testLongFields_inConstructor() {
        String longString = Strings.repeat("A", 65536);
        Uri longUri = Uri.parse("uri://" + Strings.repeat("A", 65530));

        // test both variants where there's an owner, and where there's a configuration activity
        AutomaticZenRule rule1 = new AutomaticZenRule(
                longString, // name
                new ComponentName("pkg", longString), // owner
                null,  // configuration activity
                longUri, // conditionId
                null, // zen policy
                0, // interruption filter
                true); // enabled

        assertEquals(AutomaticZenRule.MAX_STRING_LENGTH, rule1.getName().length());
        assertEquals(AutomaticZenRule.MAX_STRING_LENGTH,
                rule1.getConditionId().toString().length());
        assertEquals(AutomaticZenRule.MAX_STRING_LENGTH, rule1.getOwner().getClassName().length());

        AutomaticZenRule rule2 = new AutomaticZenRule(
                longString, // name
                null, // owner
                new ComponentName(longString, "SomeClassName"), // configuration activity
                longUri, // conditionId
                null, // zen policy
                0, // interruption filter
                false); // enabled

        assertEquals(AutomaticZenRule.MAX_STRING_LENGTH, rule2.getName().length());
        assertEquals(AutomaticZenRule.MAX_STRING_LENGTH,
                rule2.getConditionId().toString().length());
        assertEquals(AutomaticZenRule.MAX_STRING_LENGTH,
                rule2.getConfigurationActivity().getPackageName().length());
    }

    @Test
    public void testLongFields_inSetters() {
        String longString = Strings.repeat("A", 65536);
        Uri longUri = Uri.parse("uri://" + Strings.repeat("A", 65530));

        AutomaticZenRule rule = new AutomaticZenRule(
                "sensible name",
                new ComponentName("pkg", "ShortClass"),
                null,
                Uri.parse("uri://short"),
                null, 0, true);

        rule.setName(longString);
        rule.setConditionId(longUri);
        rule.setConfigurationActivity(new ComponentName(longString, longString));

        assertEquals(AutomaticZenRule.MAX_STRING_LENGTH, rule.getName().length());
        assertEquals(AutomaticZenRule.MAX_STRING_LENGTH,
                rule.getConditionId().toString().length());
        assertEquals(AutomaticZenRule.MAX_STRING_LENGTH,
                rule.getConfigurationActivity().getPackageName().length());
        assertEquals(AutomaticZenRule.MAX_STRING_LENGTH,
                rule.getConfigurationActivity().getClassName().length());
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_API)
    public void testLongInputsFromParcel() {
        // Create a rule with long fields, set directly via reflection so that we can confirm that
        // a rule with too-long fields that comes in via a parcel has its fields truncated directly.
        AutomaticZenRule rule = new AutomaticZenRule(
                "placeholder",
                new ComponentName("place", "holder"),
                null,
                Uri.parse("uri://placeholder"),
                null, 0, true);

        try {
            String longString = Strings.repeat("A", 65536);
            Uri longUri = Uri.parse("uri://" + Strings.repeat("A", 65530));
            Field name = Class.forName(CLASS).getDeclaredField("name");
            name.setAccessible(true);
            name.set(rule, longString);
            Field conditionId = Class.forName(CLASS).getDeclaredField("conditionId");
            conditionId.setAccessible(true);
            conditionId.set(rule, longUri);
            Field owner = Class.forName(CLASS).getDeclaredField("owner");
            owner.setAccessible(true);
            owner.set(rule, new ComponentName(longString, longString));
            Field configActivity = Class.forName(CLASS).getDeclaredField("configurationActivity");
            configActivity.setAccessible(true);
            configActivity.set(rule, new ComponentName(longString, longString));
            Field trigger = Class.forName(CLASS).getDeclaredField("mTriggerDescription");
            trigger.setAccessible(true);
            trigger.set(rule, longString);
        } catch (NoSuchFieldException e) {
            fail(e.toString());
        } catch (ClassNotFoundException e) {
            fail(e.toString());
        } catch (IllegalAccessException e) {
            fail(e.toString());
        }

        Parcel parcel = Parcel.obtain();
        rule.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        AutomaticZenRule fromParcel = new AutomaticZenRule(parcel);
        assertEquals(AutomaticZenRule.MAX_STRING_LENGTH, fromParcel.getName().length());
        assertEquals(AutomaticZenRule.MAX_STRING_LENGTH,
                fromParcel.getConditionId().toString().length());
        assertEquals(AutomaticZenRule.MAX_STRING_LENGTH,
                fromParcel.getConfigurationActivity().getPackageName().length());
        assertEquals(AutomaticZenRule.MAX_STRING_LENGTH,
                fromParcel.getConfigurationActivity().getClassName().length());
        assertEquals(AutomaticZenRule.MAX_STRING_LENGTH,
                fromParcel.getOwner().getPackageName().length());
        assertEquals(AutomaticZenRule.MAX_STRING_LENGTH,
                fromParcel.getOwner().getClassName().length());
        assertEquals(AutomaticZenRule.MAX_DESC_LENGTH, fromParcel.getTriggerDescription().length());
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_API)
    public void builderConstructor_nullInputs_throws() {
        assertThrows(NullPointerException.class,
                () -> new AutomaticZenRule.Builder(null, Uri.parse("condition")));
        assertThrows(NullPointerException.class,
                () -> new AutomaticZenRule.Builder("name", null));
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_API)
    public void validate_builderWithValidType_succeeds() throws Exception {
        AutomaticZenRule rule = new AutomaticZenRule.Builder("rule", Uri.parse("uri"))
                .setType(AutomaticZenRule.TYPE_BEDTIME)
                .build();
        rule.validate(); // No exception.
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_API)
    public void validate_builderWithoutType_succeeds() throws Exception {
        AutomaticZenRule rule = new AutomaticZenRule.Builder("rule", Uri.parse("uri")).build();
        rule.validate(); // No exception.
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_API)
    public void validate_constructorWithoutType_succeeds() throws Exception {
        AutomaticZenRule rule = new AutomaticZenRule("rule", new ComponentName("pkg", "cps"),
                new ComponentName("pkg", "activity"), Uri.parse("condition"), null,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
        rule.validate(); // No exception.
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_API)
    public void validate_invalidType_throws() throws Exception {
        AutomaticZenRule rule = new AutomaticZenRule.Builder("rule", Uri.parse("uri")).build();

        // Set the field via reflection.
        Field typeField = AutomaticZenRule.class.getDeclaredField("mType");
        typeField.setAccessible(true);
        typeField.set(rule, 100);

        assertThrows(IllegalArgumentException.class, rule::validate);
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_API)
    public void setType_invalidType_throws() {
        AutomaticZenRule rule = new AutomaticZenRule.Builder("rule", Uri.parse("uri")).build();

        assertThrows(IllegalArgumentException.class, () -> rule.setType(100));
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_API)
    public void setTypeBuilder_invalidType_throws() {
        AutomaticZenRule.Builder builder = new AutomaticZenRule.Builder("rule", Uri.parse("uri"));

        assertThrows(IllegalArgumentException.class, () -> builder.setType(100));
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_API)
    public void testCanUpdate_nullPolicyAndDeviceEffects() {
        AutomaticZenRule.Builder builder = new AutomaticZenRule.Builder("name",
                Uri.parse("uri://short"));

        AutomaticZenRule rule = builder.setUserModifiedFields(0)
                .setZenPolicy(null)
                .setDeviceEffects(null)
                .build();

        assertThat(rule.canUpdate()).isTrue();

        rule = builder.setUserModifiedFields(1).build();
        assertThat(rule.canUpdate()).isFalse();
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_API)
    public void testCanUpdate_policyModified() {
        ZenPolicy.Builder policyBuilder = new ZenPolicy.Builder().setUserModifiedFields(0);
        ZenPolicy policy = policyBuilder.build();

        AutomaticZenRule.Builder builder = new AutomaticZenRule.Builder("name",
                Uri.parse("uri://short"));
        AutomaticZenRule rule = builder.setUserModifiedFields(0)
                .setZenPolicy(policy)
                .setDeviceEffects(null).build();

        // Newly created ZenPolicy is not user modified.
        assertThat(policy.getUserModifiedFields()).isEqualTo(0);
        assertThat(rule.canUpdate()).isTrue();

        policy = policyBuilder.setUserModifiedFields(1).build();
        assertThat(policy.getUserModifiedFields()).isEqualTo(1);
        rule = builder.setZenPolicy(policy).build();
        assertThat(rule.canUpdate()).isFalse();
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_API)
    public void testCanUpdate_deviceEffectsModified() {
        ZenDeviceEffects.Builder deviceEffectsBuilder =
                new ZenDeviceEffects.Builder().setUserModifiedFields(0);
        ZenDeviceEffects deviceEffects = deviceEffectsBuilder.build();

        AutomaticZenRule.Builder builder = new AutomaticZenRule.Builder("name",
                Uri.parse("uri://short"));
        AutomaticZenRule rule = builder.setUserModifiedFields(0)
                .setZenPolicy(null)
                .setDeviceEffects(deviceEffects).build();

        // Newly created ZenDeviceEffects is not user modified.
        assertThat(deviceEffects.getUserModifiedFields()).isEqualTo(0);
        assertThat(rule.canUpdate()).isTrue();

        deviceEffects = deviceEffectsBuilder.setUserModifiedFields(1).build();
        assertThat(deviceEffects.getUserModifiedFields()).isEqualTo(1);
        rule = builder.setDeviceEffects(deviceEffects).build();
        assertThat(rule.canUpdate()).isFalse();
    }
}
