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

package android.hardware.face;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@Presubmit
@SmallTest
public class FaceSensorConfigurationsTest {
    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();
    @Mock
    private Context mContext;
    @Mock
    private Resources mResources;

    private final String[] mAidlInstances = new String[]{"default", "virtual"};
    private String[] mHidlConfigStrings = new String[]{"0:2:15", "0:8:15"};
    private FaceSensorConfigurations mFaceSensorConfigurations;

    @Before
    public void setUp() throws RemoteException {
        when(mContext.getResources()).thenReturn(mResources);
    }

    @Test
    public void testAidlInstanceSensorProps() {
        mFaceSensorConfigurations = new FaceSensorConfigurations(false);
        mFaceSensorConfigurations.addAidlConfigs(mAidlInstances);

        assertThat(mFaceSensorConfigurations.hasSensorConfigurations()).isTrue();
        assertThat(!mFaceSensorConfigurations.isSingleSensorConfigurationPresent()).isTrue();
        assertThat(mFaceSensorConfigurations.getResetLockoutRequiresChallenge())
                .isFalse();
    }

    @Test
    public void testHidlConfigStrings() {
        mFaceSensorConfigurations = new FaceSensorConfigurations(true);
        mFaceSensorConfigurations.addHidlConfigs(mHidlConfigStrings, mContext);

        assertThat(mFaceSensorConfigurations.isSingleSensorConfigurationPresent()).isTrue();
        assertThat(mFaceSensorConfigurations.getResetLockoutRequiresChallenge())
                .isTrue();
    }

    @Test
    public void testHidlConfigStrings_incorrectFormat() {
        mHidlConfigStrings = new String[]{"0:8:15", "0:2", "0:face:15"};
        mFaceSensorConfigurations = new FaceSensorConfigurations(true);
        mFaceSensorConfigurations.addHidlConfigs(mHidlConfigStrings, mContext);

        assertThat(mFaceSensorConfigurations.isSingleSensorConfigurationPresent()).isTrue();
        assertThat(mFaceSensorConfigurations.getResetLockoutRequiresChallenge())
                .isTrue();
    }
}
