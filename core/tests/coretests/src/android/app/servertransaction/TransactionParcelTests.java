/*
 * Copyright 2017 The Android Open Source Project
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

package android.app.servertransaction;

import static android.app.servertransaction.TestUtils.config;
import static android.app.servertransaction.TestUtils.mergedConfig;
import static android.app.servertransaction.TestUtils.referrerIntentList;
import static android.app.servertransaction.TestUtils.resultInfoList;

import static org.junit.Assert.assertEquals;

import android.app.ActivityOptions;
import android.app.servertransaction.TestUtils.LaunchActivityItemBuilder;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

/**
 * Test parcelling and unparcelling of transactions and transaction items.
 *
 * <p>Build/Install/Run:
 *  atest FrameworksCoreTests:TransactionParcelTests
 *
 * <p>This test class is a part of Window Manager Service tests and specified in
 * {@link com.android.server.wm.test.filters.FrameworksTestsFilter}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class TransactionParcelTests {

    private Parcel mParcel;
    private IBinder mActivityToken;

    @Before
    public void setUp() throws Exception {
        mParcel = Parcel.obtain();
        mActivityToken = new Binder();
    }

    @Test
    public void testConfigurationChange() {
        // Write to parcel
        ConfigurationChangeItem item = ConfigurationChangeItem.obtain(config(), 1 /* deviceId */);
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        ConfigurationChangeItem result = ConfigurationChangeItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertEquals(item, result);
    }

    @Test
    public void testActivityConfigChange() {
        // Write to parcel
        ActivityConfigurationChangeItem item = ActivityConfigurationChangeItem.obtain(
                mActivityToken, config());
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        ActivityConfigurationChangeItem result =
                ActivityConfigurationChangeItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertEquals(item, result);
    }

    @Test
    public void testMoveToDisplay() {
        // Write to parcel
        MoveToDisplayItem item = MoveToDisplayItem.obtain(mActivityToken, 4 /* targetDisplayId */,
                config());
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        MoveToDisplayItem result = MoveToDisplayItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertEquals(item, result);
    }

    @Test
    public void testNewIntent() {
        // Write to parcel
        NewIntentItem item = NewIntentItem.obtain(mActivityToken, referrerIntentList(), false);
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        NewIntentItem result = NewIntentItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertEquals(item, result);
    }

    @Test
    public void testActivityResult() {
        // Write to parcel
        ActivityResultItem item = ActivityResultItem.obtain(mActivityToken, resultInfoList());
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        ActivityResultItem result = ActivityResultItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertEquals(item, result);
    }

    @Test
    public void testDestroy() {
        DestroyActivityItem item = DestroyActivityItem.obtain(mActivityToken, true /* finished */,
                135 /* configChanges */);
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        DestroyActivityItem result = DestroyActivityItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertEquals(item, result);
    }

    @Test
    public void testLaunch() {
        // Write to parcel
        final IBinder activityToken = new Binder();
        final Intent intent = new Intent("action");
        int ident = 57;
        final ActivityInfo activityInfo = new ActivityInfo();
        activityInfo.flags = 42;
        activityInfo.setMaxAspectRatio(2.4f);
        activityInfo.launchToken = "token";
        activityInfo.applicationInfo = new ApplicationInfo();
        activityInfo.packageName = "packageName";
        activityInfo.name = "name";
        final Configuration overrideConfig = new Configuration();
        overrideConfig.assetsSeq = 5;
        final String referrer = "referrer";
        int procState = 4;
        final Bundle bundle = new Bundle();
        bundle.putString("key", "value");
        bundle.putParcelable("data", new ParcelableData(1));
        final PersistableBundle persistableBundle = new PersistableBundle();
        persistableBundle.putInt("k", 4);

        final LaunchActivityItem item = new LaunchActivityItemBuilder(
                activityToken, intent, activityInfo)
                .setIdent(ident)
                .setCurConfig(config())
                .setOverrideConfig(overrideConfig)
                .setReferrer(referrer)
                .setProcState(procState)
                .setState(bundle)
                .setPersistentState(persistableBundle)
                .setPendingResults(resultInfoList())
                .setActivityOptions(ActivityOptions.makeBasic())
                .setPendingNewIntents(referrerIntentList())
                .setIsForward(true)
                .setAssistToken(new Binder())
                .setShareableActivityToken(new Binder())
                .setTaskFragmentToken(new Binder())
                .setInitialCallerInfoAccessToken(new Binder())
                .build();

        writeAndPrepareForReading(item);

        // Read from parcel and assert
        final LaunchActivityItem result = LaunchActivityItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertEquals(item, result);
    }

    @Test
    public void testRelaunch() {
        // Write to parcel
        Configuration overrideConfig = new Configuration();
        overrideConfig.assetsSeq = 5;
        ActivityRelaunchItem item = ActivityRelaunchItem.obtain(mActivityToken, resultInfoList(),
                referrerIntentList(), 35, mergedConfig(), true);
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        ActivityRelaunchItem result = ActivityRelaunchItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertEquals(item, result);
    }

    @Test
    public void testPause() {
        // Write to parcel
        PauseActivityItem item = PauseActivityItem.obtain(mActivityToken, true /* finished */,
                true /* userLeaving */, 135 /* configChanges */, true /* dontReport */,
                true /* autoEnteringPip */);
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        PauseActivityItem result = PauseActivityItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertEquals(item, result);
    }

    @Test
    public void testResume() {
        // Write to parcel
        ResumeActivityItem item = ResumeActivityItem.obtain(mActivityToken, 27 /* procState */,
                true /* isForward */, false /* shouldSendCompatFakeFocus */);
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        ResumeActivityItem result = ResumeActivityItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertEquals(item, result);
    }

    @Test
    public void testStop() {
        // Write to parcel
        StopActivityItem item = StopActivityItem.obtain(mActivityToken, 14 /* configChanges */);
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        StopActivityItem result = StopActivityItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertEquals(item, result);
    }

    @Test
    public void testStart() {
        // Write to parcel
        StartActivityItem item = StartActivityItem.obtain(mActivityToken,
                new ActivityOptions.SceneTransitionInfo());
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        StartActivityItem result = StartActivityItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertEquals(item, result);
    }

    @Test
    public void testClientTransaction() {
        // Write to parcel
        NewIntentItem callback1 = NewIntentItem.obtain(mActivityToken, new ArrayList<>(), true);
        ActivityConfigurationChangeItem callback2 = ActivityConfigurationChangeItem.obtain(
                mActivityToken, config());

        StopActivityItem lifecycleRequest = StopActivityItem.obtain(mActivityToken,
                78 /* configChanges */);

        ClientTransaction transaction = ClientTransaction.obtain(null /* client */);
        transaction.addTransactionItem(callback1);
        transaction.addTransactionItem(callback2);
        transaction.addTransactionItem(lifecycleRequest);

        writeAndPrepareForReading(transaction);

        // Read from parcel and assert
        ClientTransaction result = ClientTransaction.CREATOR.createFromParcel(mParcel);

        assertEquals(transaction.hashCode(), result.hashCode());
        assertEquals(transaction, result);
        assertEquals(mActivityToken, result.getActivityToken());
    }

    @Test
    public void testClientTransactionCallbacksOnly() {
        // Write to parcel
        NewIntentItem callback1 = NewIntentItem.obtain(mActivityToken, new ArrayList<>(), true);
        ActivityConfigurationChangeItem callback2 = ActivityConfigurationChangeItem.obtain(
                mActivityToken, config());

        ClientTransaction transaction = ClientTransaction.obtain(null /* client */);
        transaction.addCallback(callback1);
        transaction.addCallback(callback2);

        writeAndPrepareForReading(transaction);

        // Read from parcel and assert
        ClientTransaction result = ClientTransaction.CREATOR.createFromParcel(mParcel);

        assertEquals(transaction.hashCode(), result.hashCode());
        assertEquals(transaction, result);
        assertEquals(mActivityToken, result.getActivityToken());
    }

    @Test
    public void testClientTransactionLifecycleOnly() {
        // Write to parcel
        StopActivityItem lifecycleRequest = StopActivityItem.obtain(mActivityToken,
                78 /* configChanges */);

        ClientTransaction transaction = ClientTransaction.obtain(null /* client */);
        transaction.setLifecycleStateRequest(lifecycleRequest);

        writeAndPrepareForReading(transaction);

        // Read from parcel and assert
        ClientTransaction result = ClientTransaction.CREATOR.createFromParcel(mParcel);

        assertEquals(transaction.hashCode(), result.hashCode());
        assertEquals(transaction, result);
        assertEquals(mActivityToken, result.getActivityToken());
    }

    /** Write to {@link #mParcel} and reset its position to prepare for reading from the start. */
    private void writeAndPrepareForReading(Parcelable parcelable) {
        parcelable.writeToParcel(mParcel, 0 /* flags */);
        mParcel.setDataPosition(0);
    }

    /**
     * The parcelable class to make sure that when comparing the {@link LaunchActivityItem} or
     * getting its hash code, the bundle is not unparceled. System shouldn't touch the data from
     * application, otherwise it will cause exception as:
     *   android.os.BadParcelableException: ClassNotFoundException when unmarshalling:
     *   android.app.servertransaction.TransactionParcelTests$ParcelableData".
     */
    public static class ParcelableData implements Parcelable {
        int mValue;

        ParcelableData() {}

        ParcelableData(int value) {
            mValue = value;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mValue);
        }

        public static final Creator<ParcelableData> CREATOR = new Creator<ParcelableData>() {
            @Override
            public ParcelableData createFromParcel(Parcel source) {
                final ParcelableData data = new ParcelableData();
                data.mValue = source.readInt();
                return data;
            }

            @Override
            public ParcelableData[] newArray(int size) {
                return new ParcelableData[size];
            }
        };
    }
}
