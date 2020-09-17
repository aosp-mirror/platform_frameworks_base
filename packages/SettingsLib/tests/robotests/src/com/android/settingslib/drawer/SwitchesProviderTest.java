/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.settingslib.drawer;

import static com.android.settingslib.drawer.SwitchesProvider.EXTRA_SWITCH_CHECKED_STATE;
import static com.android.settingslib.drawer.SwitchesProvider.EXTRA_SWITCH_DATA;
import static com.android.settingslib.drawer.SwitchesProvider.EXTRA_SWITCH_SET_CHECKED_ERROR;
import static com.android.settingslib.drawer.SwitchesProvider.EXTRA_SWITCH_SET_CHECKED_ERROR_MESSAGE;
import static com.android.settingslib.drawer.SwitchesProvider.METHOD_GET_DYNAMIC_SUMMARY;
import static com.android.settingslib.drawer.SwitchesProvider.METHOD_GET_DYNAMIC_TITLE;
import static com.android.settingslib.drawer.SwitchesProvider.METHOD_GET_PROVIDER_ICON;
import static com.android.settingslib.drawer.SwitchesProvider.METHOD_GET_SWITCH_DATA;
import static com.android.settingslib.drawer.SwitchesProvider.METHOD_IS_CHECKED;
import static com.android.settingslib.drawer.SwitchesProvider.METHOD_ON_CHECKED_CHANGED;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_KEYHINT;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_SUMMARY;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_TITLE;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.content.pm.ProviderInfo;
import android.os.Bundle;

import com.android.settingslib.drawer.MasterSwitchControllerTest.TestMasterSwitchController;
import com.android.settingslib.drawer.SwitchController.MetaData;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class SwitchesProviderTest {

    @Rule
    public final ExpectedException thrown = ExpectedException.none();

    private Context mContext;
    private ProviderInfo mProviderInfo;

    private TestSwitchesProvider mSwitchesProvider;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.application;
        mSwitchesProvider = new TestSwitchesProvider();
        mProviderInfo = new ProviderInfo();
        mProviderInfo.authority = "auth";
    }

    @Test
    public void attachInfo_noController_shouldThrowIllegalArgumentException() {
        thrown.expect(IllegalArgumentException.class);

        mSwitchesProvider.attachInfo(mContext, mProviderInfo);
    }

    @Test
    public void attachInfo_NoSwitchKeyInController_shouldThrowNullPointerException() {
        thrown.expect(NullPointerException.class);
        final TestSwitchController controller = new TestSwitchController();
        mSwitchesProvider.addSwitchController(controller);

        mSwitchesProvider.attachInfo(mContext, mProviderInfo);
    }

    @Test
    public void attachInfo_NoMetaDataInController_shouldThrowNullPointerException() {
        thrown.expect(NullPointerException.class);
        final TestSwitchController controller = new TestSwitchController();
        controller.setKey("123");
        mSwitchesProvider.addSwitchController(controller);

        mSwitchesProvider.attachInfo(mContext, mProviderInfo);
    }

    @Test
    public void attachInfo_duplicateSwitchKey_shouldThrowIllegalArgumentException() {
        thrown.expect(IllegalArgumentException.class);
        final TestSwitchController controller1 = new TestSwitchController();
        final TestSwitchController controller2 = new TestSwitchController();
        controller1.setKey("123");
        controller2.setKey("123");
        controller1.setMetaData(new MetaData("category"));
        controller2.setMetaData(new MetaData("category"));
        mSwitchesProvider.addSwitchController(controller1);
        mSwitchesProvider.addSwitchController(controller2);

        mSwitchesProvider.attachInfo(mContext, mProviderInfo);
    }

    @Test
    public void attachInfo_hasDifferentControllers_shouldNotThrowException() {
        final TestSwitchController controller1 = new TestSwitchController();
        final TestSwitchController controller2 = new TestSwitchController();
        controller1.setKey("123");
        controller2.setKey("456");
        controller1.setMetaData(new MetaData("category"));
        controller2.setMetaData(new MetaData("category"));
        mSwitchesProvider.addSwitchController(controller1);
        mSwitchesProvider.addSwitchController(controller2);

        mSwitchesProvider.attachInfo(mContext, mProviderInfo);
    }

    @Test
    public void getSwitchData_shouldNotReturnMasterSwitchData() {
        final SwitchController controller = new TestMasterSwitchController("123");
        mSwitchesProvider.addSwitchController(controller);
        mSwitchesProvider.attachInfo(mContext, mProviderInfo);

        final Bundle switchData = mSwitchesProvider.call(METHOD_GET_SWITCH_DATA, "uri" ,
                null /* extras*/);

        final ArrayList<Bundle> dataList = switchData.getParcelableArrayList(EXTRA_SWITCH_DATA);
        assertThat(dataList).isEmpty();
    }

    @Test
    public void getSwitchData_shouldReturnDataList() {
        final TestSwitchController controller = new TestSwitchController();
        controller.setKey("123");
        controller.setMetaData(new MetaData("category"));
        mSwitchesProvider.addSwitchController(controller);
        mSwitchesProvider.attachInfo(mContext, mProviderInfo);

        final Bundle switchData = mSwitchesProvider.call(METHOD_GET_SWITCH_DATA, "uri" ,
                null /* extras*/);

        final ArrayList<Bundle> dataList = switchData.getParcelableArrayList(EXTRA_SWITCH_DATA);
        assertThat(dataList).hasSize(1);
        assertThat(dataList.get(0).getString(META_DATA_PREFERENCE_KEYHINT)).isEqualTo("123");
    }

    @Test
    public void getSwitchDataByKey_shouldReturnData() {
        final Bundle extras = new Bundle();
        extras.putString(META_DATA_PREFERENCE_KEYHINT, "123");
        final TestSwitchController controller = new TestSwitchController();
        controller.setKey("123");
        controller.setMetaData(new MetaData("category"));
        mSwitchesProvider.addSwitchController(controller);
        mSwitchesProvider.attachInfo(mContext, mProviderInfo);

        final Bundle switchData = mSwitchesProvider.call(METHOD_GET_SWITCH_DATA, "uri" , extras);

        assertThat(switchData.getString(META_DATA_PREFERENCE_KEYHINT)).isEqualTo("123");
    }

    @Test
    public void isChecked_shouldReturnCheckedState() {
        final Bundle extras = new Bundle();
        extras.putString(META_DATA_PREFERENCE_KEYHINT, "123");
        final TestSwitchController controller = new TestSwitchController();
        controller.setKey("123");
        controller.setMetaData(new MetaData("category"));
        mSwitchesProvider.addSwitchController(controller);
        mSwitchesProvider.attachInfo(mContext, mProviderInfo);

        controller.setChecked(true);
        Bundle result = mSwitchesProvider.call(METHOD_IS_CHECKED, "uri" , extras);

        assertThat(result.getBoolean(EXTRA_SWITCH_CHECKED_STATE)).isTrue();

        controller.setChecked(false);
        result = mSwitchesProvider.call(METHOD_IS_CHECKED, "uri" , extras);

        assertThat(result.getBoolean(EXTRA_SWITCH_CHECKED_STATE)).isFalse();
    }

    @Test
    public void getProviderIcon_noImplementInterface_shouldReturnNull() {
        final Bundle extras = new Bundle();
        extras.putString(META_DATA_PREFERENCE_KEYHINT, "123");
        final TestSwitchController controller = new TestSwitchController();
        controller.setKey("123");
        controller.setMetaData(new MetaData("category"));
        mSwitchesProvider.addSwitchController(controller);
        mSwitchesProvider.attachInfo(mContext, mProviderInfo);

        final Bundle iconBundle = mSwitchesProvider.call(METHOD_GET_PROVIDER_ICON, "uri" , extras);

        assertThat(iconBundle).isNull();
    }

    @Test
    public void getProviderIcon_implementInterface_shouldReturnIcon() {
        final Bundle extras = new Bundle();
        extras.putString(META_DATA_PREFERENCE_KEYHINT, "123");
        final TestSwitchController controller = new TestDynamicSwitchController();
        controller.setKey("123");
        controller.setMetaData(new MetaData("category"));
        mSwitchesProvider.addSwitchController(controller);
        mSwitchesProvider.attachInfo(mContext, mProviderInfo);

        final Bundle iconBundle = mSwitchesProvider.call(METHOD_GET_PROVIDER_ICON, "uri" , extras);

        assertThat(iconBundle).isEqualTo(TestDynamicSwitchController.ICON_BUNDLE);
    }

    @Test
    public void getDynamicTitle_noImplementInterface_shouldReturnNull() {
        final Bundle extras = new Bundle();
        extras.putString(META_DATA_PREFERENCE_KEYHINT, "123");
        final TestSwitchController controller = new TestSwitchController();
        controller.setKey("123");
        controller.setMetaData(new MetaData("category"));
        mSwitchesProvider.addSwitchController(controller);
        mSwitchesProvider.attachInfo(mContext, mProviderInfo);

        final Bundle result = mSwitchesProvider.call(METHOD_GET_DYNAMIC_TITLE, "uri" , extras);

        assertThat(result).isNull();
    }

    @Test
    public void getDynamicTitle_implementInterface_shouldReturnTitle() {
        final Bundle extras = new Bundle();
        extras.putString(META_DATA_PREFERENCE_KEYHINT, "123");
        final TestSwitchController controller = new TestDynamicSwitchController();
        controller.setKey("123");
        controller.setMetaData(new MetaData("category"));
        mSwitchesProvider.addSwitchController(controller);
        mSwitchesProvider.attachInfo(mContext, mProviderInfo);

        final Bundle result = mSwitchesProvider.call(METHOD_GET_DYNAMIC_TITLE, "uri" , extras);

        assertThat(result.getString(META_DATA_PREFERENCE_TITLE))
                .isEqualTo(TestDynamicSwitchController.TITLE);
    }

    @Test
    public void getDynamicSummary_noImplementInterface_shouldReturnNull() {
        final Bundle extras = new Bundle();
        extras.putString(META_DATA_PREFERENCE_KEYHINT, "123");
        final TestSwitchController controller = new TestSwitchController();
        controller.setKey("123");
        controller.setMetaData(new MetaData("category"));
        mSwitchesProvider.addSwitchController(controller);
        mSwitchesProvider.attachInfo(mContext, mProviderInfo);

        final Bundle result = mSwitchesProvider.call(METHOD_GET_DYNAMIC_SUMMARY, "uri" , extras);

        assertThat(result).isNull();
    }

    @Test
    public void getDynamicSummary_implementInterface_shouldReturnSummary() {
        final Bundle extras = new Bundle();
        extras.putString(META_DATA_PREFERENCE_KEYHINT, "123");
        final TestSwitchController controller = new TestDynamicSwitchController();
        controller.setKey("123");
        controller.setMetaData(new MetaData("category"));
        mSwitchesProvider.addSwitchController(controller);
        mSwitchesProvider.attachInfo(mContext, mProviderInfo);

        final Bundle result = mSwitchesProvider.call(METHOD_GET_DYNAMIC_SUMMARY, "uri" , extras);

        assertThat(result.getString(META_DATA_PREFERENCE_SUMMARY))
                .isEqualTo(TestDynamicSwitchController.SUMMARY);
    }

    @Test
    public void onCheckedChangedSuccess_shouldReturnNoError() {
        final Bundle extras = new Bundle();
        extras.putString(META_DATA_PREFERENCE_KEYHINT, "123");
        final TestSwitchController controller = new TestSwitchController();
        controller.setKey("123");
        controller.setMetaData(new MetaData("category"));
        mSwitchesProvider.addSwitchController(controller);
        mSwitchesProvider.attachInfo(mContext, mProviderInfo);

        final Bundle result = mSwitchesProvider.call(METHOD_ON_CHECKED_CHANGED, "uri" , extras);

        assertThat(result.getBoolean(EXTRA_SWITCH_SET_CHECKED_ERROR)).isFalse();
    }

    @Test
    public void onCheckedChangedFailed_shouldReturnErrorMessage() {
        final Bundle extras = new Bundle();
        extras.putString(META_DATA_PREFERENCE_KEYHINT, "123");
        final TestSwitchController controller = new TestSwitchController();
        controller.setKey("123");
        controller.setMetaData(new MetaData("category"));
        controller.setErrorMessage("error");
        mSwitchesProvider.addSwitchController(controller);
        mSwitchesProvider.attachInfo(mContext, mProviderInfo);

        final Bundle result = mSwitchesProvider.call(METHOD_ON_CHECKED_CHANGED, "uri" , extras);

        assertThat(result.getBoolean(EXTRA_SWITCH_SET_CHECKED_ERROR)).isTrue();
        assertThat(result.getString(EXTRA_SWITCH_SET_CHECKED_ERROR_MESSAGE)).isEqualTo("error");
    }

    private class TestSwitchesProvider extends SwitchesProvider {

        private List<SwitchController> mControllers;

        @Override
        protected List<SwitchController> createSwitchControllers() {
            return mControllers;
        }

        void addSwitchController(SwitchController controller) {
            if (mControllers == null) {
                mControllers = new ArrayList<>();
            }
            mControllers.add(controller);
        }
    }

    private static class TestSwitchController extends SwitchController {

        private String mKey;
        private MetaData mMetaData;
        private boolean mChecked;
        private String mErrorMsg;

        @Override
        public String getSwitchKey() {
            return mKey;
        }

        @Override
        protected MetaData getMetaData() {
            return mMetaData;
        }

        @Override
        protected boolean isChecked() {
            return mChecked;
        }

        @Override
        protected boolean onCheckedChanged(boolean checked) {
            return mErrorMsg == null ? true : false;
        }

        @Override
        protected String getErrorMessage(boolean attemptedChecked) {
            return mErrorMsg;
        }

        void setKey(String key) {
            mKey = key;
        }

        void setMetaData(MetaData metaData) {
            mMetaData = metaData;
        }

        void setChecked(boolean checked) {
            mChecked = checked;
        }

        void setErrorMessage(String errorMsg) {
            mErrorMsg = errorMsg;
        }
    }

    private static class TestDynamicSwitchController extends TestSwitchController
            implements ProviderIcon, DynamicTitle, DynamicSummary {

        static final String TITLE = "title";
        static final String SUMMARY = "summary";
        static final Bundle ICON_BUNDLE = new Bundle();

        @Override
        public Bundle getProviderIcon() {
            return ICON_BUNDLE;
        }

        @Override
        public String getDynamicTitle() {
            return TITLE;
        }

        @Override
        public String getDynamicSummary() {
            return SUMMARY;
        }
    }
}
