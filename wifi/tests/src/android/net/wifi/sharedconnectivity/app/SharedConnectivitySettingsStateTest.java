/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.net.wifi.sharedconnectivity.app;

import static com.google.common.truth.Truth.assertThat;

import android.content.Intent;
import android.os.Parcel;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;

import org.junit.Test;

/**
 * Unit tests for {@link SharedConnectivitySettingsState}.
 */
@SmallTest
public class SharedConnectivitySettingsStateTest {
    private static final boolean INSTANT_TETHER_STATE = true;
    private static final String INTENT_ACTION = "instant.tether.settings";

    private static final boolean INSTANT_TETHER_STATE_1 = false;
    private static final String INTENT_ACTION_1 = "instant.tether.settings1";


    /**
     * Verifies parcel serialization/deserialization.
     */
    @Test
    public void testParcelOperation() {
        SharedConnectivitySettingsState state = buildSettingsStateBuilder().build();

        Parcel parcel = Parcel.obtain();
        state.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        SharedConnectivitySettingsState fromParcel =
                SharedConnectivitySettingsState.CREATOR.createFromParcel(parcel);

        assertThat(fromParcel).isEqualTo(state);
        assertThat(fromParcel.hashCode()).isEqualTo(state.hashCode());
    }

    /**
     * Verifies the Equals operation
     */
    @Test
    public void testEqualsOperation() {
        SharedConnectivitySettingsState state1 = buildSettingsStateBuilder().build();
        SharedConnectivitySettingsState state2 = buildSettingsStateBuilder().build();
        assertThat(state1).isEqualTo(state2);

        SharedConnectivitySettingsState.Builder builder = buildSettingsStateBuilder()
                .setInstantTetherEnabled(INSTANT_TETHER_STATE_1);
        assertThat(builder.build()).isNotEqualTo(state1);

        builder = buildSettingsStateBuilder()
                .setInstantTetherSettingsPendingIntent(new Intent(INTENT_ACTION_1));
        assertThat(builder.build()).isNotEqualTo(state1);
    }

    /**
     * Verifies the get methods return the expected data.
     */
    @Test
    public void testGetMethods() {
        SharedConnectivitySettingsState state = buildSettingsStateBuilder().build();
        assertThat(state.isInstantTetherEnabled()).isEqualTo(INSTANT_TETHER_STATE);
    }

    @Test
    public void testHashCode() {
        SharedConnectivitySettingsState state1 = buildSettingsStateBuilder().build();
        SharedConnectivitySettingsState state2 = buildSettingsStateBuilder().build();

        assertThat(state1.hashCode()).isEqualTo(state2.hashCode());
    }

    private SharedConnectivitySettingsState.Builder buildSettingsStateBuilder() {
        return new SharedConnectivitySettingsState.Builder(
                ApplicationProvider.getApplicationContext())
                .setInstantTetherEnabled(INSTANT_TETHER_STATE)
                .setInstantTetherSettingsPendingIntent(new Intent(INTENT_ACTION));
    }
}
