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

package com.android.settingslib.drawer;

import static com.android.settingslib.drawer.EntriesProvider.EXTRA_ENTRY_DATA;
import static com.android.settingslib.drawer.EntriesProvider.EXTRA_SWITCH_CHECKED_STATE;
import static com.android.settingslib.drawer.EntriesProvider.EXTRA_SWITCH_DATA;
import static com.android.settingslib.drawer.EntriesProvider.EXTRA_SWITCH_SET_CHECKED_ERROR;
import static com.android.settingslib.drawer.EntriesProvider.EXTRA_SWITCH_SET_CHECKED_ERROR_MESSAGE;
import static com.android.settingslib.drawer.EntriesProvider.METHOD_GET_DYNAMIC_SUMMARY;
import static com.android.settingslib.drawer.EntriesProvider.METHOD_GET_DYNAMIC_TITLE;
import static com.android.settingslib.drawer.EntriesProvider.METHOD_GET_ENTRY_DATA;
import static com.android.settingslib.drawer.EntriesProvider.METHOD_GET_PROVIDER_ICON;
import static com.android.settingslib.drawer.EntriesProvider.METHOD_GET_SWITCH_DATA;
import static com.android.settingslib.drawer.EntriesProvider.METHOD_IS_CHECKED;
import static com.android.settingslib.drawer.EntriesProvider.METHOD_ON_CHECKED_CHANGED;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_KEYHINT;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_PENDING_INTENT;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_SUMMARY;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_TITLE;

import static com.google.common.truth.Truth.assertThat;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.os.Bundle;

import com.android.settingslib.drawer.EntryController.MetaData;
import com.android.settingslib.drawer.PrimarySwitchControllerTest.TestPrimarySwitchController;

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
public class EntriesProviderTest {

    @Rule
    public final ExpectedException thrown = ExpectedException.none();

    private Context mContext;
    private ProviderInfo mProviderInfo;

    private TestEntriesProvider mEntriesProvider;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.application;
        mEntriesProvider = new TestEntriesProvider();
        mProviderInfo = new ProviderInfo();
        mProviderInfo.authority = "auth";
    }

    @Test
    public void attachInfo_noController_shouldThrowIllegalArgumentException() {
        thrown.expect(IllegalArgumentException.class);

        mEntriesProvider.attachInfo(mContext, mProviderInfo);
    }

    @Test
    public void attachInfo_NoKeyInController_shouldThrowNullPointerException() {
        thrown.expect(NullPointerException.class);
        final TestEntryController controller = new TestEntryController();
        mEntriesProvider.addController(controller);

        mEntriesProvider.attachInfo(mContext, mProviderInfo);
    }

    @Test
    public void attachInfo_NoMetaDataInController_shouldThrowNullPointerException() {
        thrown.expect(NullPointerException.class);
        final TestEntryController controller = new TestEntryController();
        controller.setKey("123");
        mEntriesProvider.addController(controller);

        mEntriesProvider.attachInfo(mContext, mProviderInfo);
    }

    @Test
    public void attachInfo_duplicateKey_shouldThrowIllegalArgumentException() {
        thrown.expect(IllegalArgumentException.class);
        final TestEntryController controller1 = new TestEntryController();
        final TestEntryController controller2 = new TestEntryController();
        controller1.setKey("123");
        controller2.setKey("123");
        controller1.setMetaData(new MetaData("category"));
        controller2.setMetaData(new MetaData("category"));
        mEntriesProvider.addController(controller1);
        mEntriesProvider.addController(controller2);

        mEntriesProvider.attachInfo(mContext, mProviderInfo);
    }

    @Test
    public void attachInfo_hasDifferentControllers_shouldNotThrowException() {
        final TestEntryController controller1 = new TestEntryController();
        final TestEntryController controller2 = new TestEntryController();
        controller1.setKey("123");
        controller2.setKey("456");
        controller1.setMetaData(new MetaData("category"));
        controller2.setMetaData(new MetaData("category"));
        mEntriesProvider.addController(controller1);
        mEntriesProvider.addController(controller2);

        mEntriesProvider.attachInfo(mContext, mProviderInfo);
    }

    @Test
    public void getEntryData_shouldNotReturnPrimarySwitchData() {
        final EntryController controller = new TestPrimarySwitchController("123");
        mEntriesProvider.addController(controller);
        mEntriesProvider.attachInfo(mContext, mProviderInfo);

        final Bundle switchData = mEntriesProvider.call(METHOD_GET_ENTRY_DATA, "uri",
                null /* extras*/);

        final ArrayList<Bundle> dataList = switchData.getParcelableArrayList(EXTRA_ENTRY_DATA);
        assertThat(dataList).isEmpty();
    }

    @Test
    public void getEntryData_shouldReturnDataList() {
        final TestEntryController controller = new TestEntryController();
        final PendingIntent pendingIntent = PendingIntent.getActivity(mContext, 0, new Intent(), 0);
        controller.setKey("123");
        controller.setMetaData(new MetaData("category").setPendingIntent(pendingIntent));
        mEntriesProvider.addController(controller);
        mEntriesProvider.attachInfo(mContext, mProviderInfo);

        final Bundle entryData = mEntriesProvider.call(METHOD_GET_ENTRY_DATA, "uri",
                null /* extras*/);

        final ArrayList<Bundle> dataList = entryData.getParcelableArrayList(EXTRA_ENTRY_DATA);
        assertThat(dataList).hasSize(1);
        assertThat(dataList.get(0).getString(META_DATA_PREFERENCE_KEYHINT)).isEqualTo("123");
        assertThat(dataList.get(0).getParcelable(META_DATA_PREFERENCE_PENDING_INTENT,
                PendingIntent.class))
                .isEqualTo(pendingIntent);
    }

    @Test
    public void getSwitchData_shouldReturnDataList() {
        final TestEntryController controller = new TestEntryController();
        final PendingIntent pendingIntent = PendingIntent.getActivity(mContext, 0, new Intent(), 0);
        controller.setKey("123");
        controller.setMetaData(new MetaData("category").setPendingIntent(pendingIntent));
        mEntriesProvider.addController(controller);
        mEntriesProvider.attachInfo(mContext, mProviderInfo);

        final Bundle entryData = mEntriesProvider.call(METHOD_GET_SWITCH_DATA, "uri",
                null /* extras*/);

        final ArrayList<Bundle> dataList = entryData.getParcelableArrayList(EXTRA_SWITCH_DATA);
        assertThat(dataList).hasSize(1);
        assertThat(dataList.get(0).getString(META_DATA_PREFERENCE_KEYHINT)).isEqualTo("123");
        assertThat(dataList.get(0).getParcelable(META_DATA_PREFERENCE_PENDING_INTENT,
                PendingIntent.class))
                .isEqualTo(pendingIntent);
    }

    @Test
    public void getEntryDataByKey_shouldReturnData() {
        final Bundle extras = new Bundle();
        extras.putString(META_DATA_PREFERENCE_KEYHINT, "123");
        final TestEntryController controller = new TestEntryController();
        controller.setKey("123");
        controller.setMetaData(new MetaData("category"));
        mEntriesProvider.addController(controller);
        mEntriesProvider.attachInfo(mContext, mProviderInfo);

        final Bundle entryData = mEntriesProvider.call(METHOD_GET_ENTRY_DATA, "uri", extras);

        assertThat(entryData.getString(META_DATA_PREFERENCE_KEYHINT)).isEqualTo("123");
    }

    @Test
    public void getSwitchDataByKey_shouldReturnData() {
        final Bundle extras = new Bundle();
        extras.putString(META_DATA_PREFERENCE_KEYHINT, "123");
        final TestEntryController controller = new TestEntryController();
        controller.setKey("123");
        controller.setMetaData(new MetaData("category"));
        mEntriesProvider.addController(controller);
        mEntriesProvider.attachInfo(mContext, mProviderInfo);

        final Bundle entryData = mEntriesProvider.call(METHOD_GET_SWITCH_DATA, "uri", extras);

        assertThat(entryData.getString(META_DATA_PREFERENCE_KEYHINT)).isEqualTo("123");
    }

    @Test
    public void isSwitchChecked_shouldReturnCheckedState() {
        final Bundle extras = new Bundle();
        extras.putString(META_DATA_PREFERENCE_KEYHINT, "123");
        final TestSwitchController controller = new TestSwitchController();
        controller.setKey("123");
        controller.setMetaData(new MetaData("category"));
        mEntriesProvider.addController(controller);
        mEntriesProvider.attachInfo(mContext, mProviderInfo);

        controller.setSwitchChecked(true);
        Bundle result = mEntriesProvider.call(METHOD_IS_CHECKED, "uri", extras);

        assertThat(result.getBoolean(EXTRA_SWITCH_CHECKED_STATE)).isTrue();

        controller.setSwitchChecked(false);
        result = mEntriesProvider.call(METHOD_IS_CHECKED, "uri", extras);

        assertThat(result.getBoolean(EXTRA_SWITCH_CHECKED_STATE)).isFalse();
    }

    @Test
    public void getProviderIcon_noImplementInterface_shouldReturnNull() {
        final Bundle extras = new Bundle();
        extras.putString(META_DATA_PREFERENCE_KEYHINT, "123");
        final TestEntryController controller = new TestEntryController();
        controller.setKey("123");
        controller.setMetaData(new MetaData("category"));
        mEntriesProvider.addController(controller);
        mEntriesProvider.attachInfo(mContext, mProviderInfo);

        final Bundle iconBundle = mEntriesProvider.call(METHOD_GET_PROVIDER_ICON, "uri", extras);

        assertThat(iconBundle).isNull();
    }

    @Test
    public void getProviderIcon_implementInterface_shouldReturnIcon() {
        final Bundle extras = new Bundle();
        extras.putString(META_DATA_PREFERENCE_KEYHINT, "123");
        final TestEntryController controller = new TestDynamicController();
        controller.setKey("123");
        controller.setMetaData(new MetaData("category"));
        mEntriesProvider.addController(controller);
        mEntriesProvider.attachInfo(mContext, mProviderInfo);

        final Bundle iconBundle = mEntriesProvider.call(METHOD_GET_PROVIDER_ICON, "uri", extras);

        assertThat(iconBundle).isEqualTo(TestDynamicController.ICON_BUNDLE);
    }

    @Test
    public void getDynamicTitle_noImplementInterface_shouldReturnNull() {
        final Bundle extras = new Bundle();
        extras.putString(META_DATA_PREFERENCE_KEYHINT, "123");
        final TestEntryController controller = new TestEntryController();
        controller.setKey("123");
        controller.setMetaData(new MetaData("category"));
        mEntriesProvider.addController(controller);
        mEntriesProvider.attachInfo(mContext, mProviderInfo);

        final Bundle result = mEntriesProvider.call(METHOD_GET_DYNAMIC_TITLE, "uri", extras);

        assertThat(result).isNull();
    }

    @Test
    public void getDynamicTitle_implementInterface_shouldReturnTitle() {
        final Bundle extras = new Bundle();
        extras.putString(META_DATA_PREFERENCE_KEYHINT, "123");
        final TestEntryController controller = new TestDynamicController();
        controller.setKey("123");
        controller.setMetaData(new MetaData("category"));
        mEntriesProvider.addController(controller);
        mEntriesProvider.attachInfo(mContext, mProviderInfo);

        final Bundle result = mEntriesProvider.call(METHOD_GET_DYNAMIC_TITLE, "uri", extras);

        assertThat(result.getString(META_DATA_PREFERENCE_TITLE))
                .isEqualTo(TestDynamicController.TITLE);
    }

    @Test
    public void getDynamicSummary_noImplementInterface_shouldReturnNull() {
        final Bundle extras = new Bundle();
        extras.putString(META_DATA_PREFERENCE_KEYHINT, "123");
        final TestEntryController controller = new TestEntryController();
        controller.setKey("123");
        controller.setMetaData(new MetaData("category"));
        mEntriesProvider.addController(controller);
        mEntriesProvider.attachInfo(mContext, mProviderInfo);

        final Bundle result = mEntriesProvider.call(METHOD_GET_DYNAMIC_SUMMARY, "uri", extras);

        assertThat(result).isNull();
    }

    @Test
    public void getDynamicSummary_implementInterface_shouldReturnSummary() {
        final Bundle extras = new Bundle();
        extras.putString(META_DATA_PREFERENCE_KEYHINT, "123");
        final TestEntryController controller = new TestDynamicController();
        controller.setKey("123");
        controller.setMetaData(new MetaData("category"));
        mEntriesProvider.addController(controller);
        mEntriesProvider.attachInfo(mContext, mProviderInfo);

        final Bundle result = mEntriesProvider.call(METHOD_GET_DYNAMIC_SUMMARY, "uri", extras);

        assertThat(result.getString(META_DATA_PREFERENCE_SUMMARY))
                .isEqualTo(TestDynamicController.SUMMARY);
    }

    @Test
    public void onSwitchCheckedChangedSuccess_shouldReturnNoError() {
        final Bundle extras = new Bundle();
        extras.putString(META_DATA_PREFERENCE_KEYHINT, "123");
        final TestSwitchController controller = new TestSwitchController();
        controller.setKey("123");
        controller.setMetaData(new MetaData("category"));
        mEntriesProvider.addController(controller);
        mEntriesProvider.attachInfo(mContext, mProviderInfo);

        final Bundle result = mEntriesProvider.call(METHOD_ON_CHECKED_CHANGED, "uri", extras);

        assertThat(result.getBoolean(EXTRA_SWITCH_SET_CHECKED_ERROR)).isFalse();
    }

    @Test
    public void onSwitchCheckedChangedFailed_shouldReturnErrorMessage() {
        final Bundle extras = new Bundle();
        extras.putString(META_DATA_PREFERENCE_KEYHINT, "123");
        final TestSwitchController controller = new TestSwitchController();
        controller.setKey("123");
        controller.setMetaData(new MetaData("category"));
        controller.setSwitchErrorMessage("error");
        mEntriesProvider.addController(controller);
        mEntriesProvider.attachInfo(mContext, mProviderInfo);

        final Bundle result = mEntriesProvider.call(METHOD_ON_CHECKED_CHANGED, "uri", extras);

        assertThat(result.getBoolean(EXTRA_SWITCH_SET_CHECKED_ERROR)).isTrue();
        assertThat(result.getString(EXTRA_SWITCH_SET_CHECKED_ERROR_MESSAGE)).isEqualTo("error");
    }

    private static class TestEntriesProvider extends EntriesProvider {

        private List<EntryController> mControllers;

        @Override
        protected List<EntryController> createEntryControllers() {
            return mControllers;
        }

        void addController(EntryController controller) {
            if (mControllers == null) {
                mControllers = new ArrayList<>();
            }
            mControllers.add(controller);
        }
    }

    private static class TestEntryController extends EntryController {

        private String mKey;
        private MetaData mMetaData;

        @Override
        public String getKey() {
            return mKey;
        }

        @Override
        protected MetaData getMetaData() {
            return mMetaData;
        }

        void setKey(String key) {
            mKey = key;
        }

        void setMetaData(MetaData metaData) {
            mMetaData = metaData;
        }
    }

    private static class TestSwitchController extends EntryController implements ProviderSwitch {

        private String mKey;
        private MetaData mMetaData;
        private boolean mChecked;
        private String mErrorMsg;

        @Override
        public String getKey() {
            return mKey;
        }

        @Override
        protected MetaData getMetaData() {
            return mMetaData;
        }

        @Override
        public boolean isSwitchChecked() {
            return mChecked;
        }

        @Override
        public boolean onSwitchCheckedChanged(boolean checked) {
            return mErrorMsg == null ? true : false;
        }

        @Override
        public String getSwitchErrorMessage(boolean attemptedChecked) {
            return mErrorMsg;
        }

        void setKey(String key) {
            mKey = key;
        }

        void setMetaData(MetaData metaData) {
            mMetaData = metaData;
        }

        void setSwitchChecked(boolean checked) {
            mChecked = checked;
        }

        void setSwitchErrorMessage(String errorMsg) {
            mErrorMsg = errorMsg;
        }
    }

    private static class TestDynamicController extends TestEntryController
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
