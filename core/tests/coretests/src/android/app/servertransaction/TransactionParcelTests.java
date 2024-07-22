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
import android.graphics.Rect;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.platform.test.annotations.Presubmit;
import android.window.ActivityWindowInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

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
        final ConfigurationChangeItem item =
                new ConfigurationChangeItem(config(), 1 /* deviceId */);
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        final ConfigurationChangeItem result =
                ConfigurationChangeItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertEquals(item, result);
    }

    @Test
    public void testActivityConfigChange() {
        // Write to parcel
        final ActivityWindowInfo activityWindowInfo = new ActivityWindowInfo();
        activityWindowInfo.set(true /* isEmbedded */, new Rect(0, 0, 500, 1000),
                new Rect(0, 0, 500, 500));
        final ActivityConfigurationChangeItem item = new ActivityConfigurationChangeItem(
                mActivityToken, config(), activityWindowInfo);
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
        final ActivityWindowInfo activityWindowInfo = new ActivityWindowInfo();
        activityWindowInfo.set(true /* isEmbedded */, new Rect(0, 0, 500, 1000),
                new Rect(0, 0, 500, 500));
        final MoveToDisplayItem item = new MoveToDisplayItem(mActivityToken,
                4 /* targetDisplayId */, config(), activityWindowInfo);
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        final MoveToDisplayItem result = MoveToDisplayItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertEquals(item, result);
    }

    @Test
    public void testNewIntent() {
        // Write to parcel
        final NewIntentItem item =
                new NewIntentItem(mActivityToken, referrerIntentList(), false /* resume */);
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        final NewIntentItem result = NewIntentItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertEquals(item, result);
    }

    @Test
    public void testActivityResult() {
        // Write to parcel
        final ActivityResultItem item = new ActivityResultItem(mActivityToken, resultInfoList());
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        final ActivityResultItem result = ActivityResultItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertEquals(item, result);
    }

    @Test
    public void testDestroy() {
        final DestroyActivityItem item =
                new DestroyActivityItem(mActivityToken, true /* finished */);
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        final DestroyActivityItem result = DestroyActivityItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertEquals(item, result);
    }

    @Test
    public void testLaunch() {
        // Write to parcel
        final IBinder activityToken = new Binder();
        final Intent intent = new Intent("action");
        final int ident = 57;
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
        final int procState = 4;
        final Bundle bundle = new Bundle();
        bundle.putString("key", "value");
        bundle.putParcelable("data", new ParcelableData(1));
        final PersistableBundle persistableBundle = new PersistableBundle();
        persistableBundle.putInt("k", 4);
        final ActivityWindowInfo activityWindowInfo = new ActivityWindowInfo();
        activityWindowInfo.set(true /* isEmbedded */, new Rect(0, 0, 500, 1000),
                new Rect(0, 0, 500, 500));

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
                .setActivityWindowInfo(activityWindowInfo)
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
        final Configuration overrideConfig = new Configuration();
        overrideConfig.assetsSeq = 5;
        final ActivityWindowInfo activityWindowInfo = new ActivityWindowInfo();
        activityWindowInfo.set(true /* isEmbedded */, new Rect(0, 0, 500, 1000),
                new Rect(0, 0, 500, 500));
        final ActivityRelaunchItem item = new ActivityRelaunchItem(mActivityToken, resultInfoList(),
                referrerIntentList(), 35, mergedConfig(), true, activityWindowInfo);
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        final ActivityRelaunchItem result = ActivityRelaunchItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertEquals(item, result);
    }

    @Test
    public void testPause() {
        // Write to parcel
        final PauseActivityItem item = new PauseActivityItem(mActivityToken, true /* finished */,
                true /* userLeaving */, true /* dontReport */, true /* autoEnteringPip */);
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        final PauseActivityItem result = PauseActivityItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertEquals(item, result);
    }

    @Test
    public void testResume() {
        // Write to parcel
        final ResumeActivityItem item = new ResumeActivityItem(mActivityToken, 27 /* procState */,
                true /* isForward */, false /* shouldSendCompatFakeFocus */);
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        final ResumeActivityItem result = ResumeActivityItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertEquals(item, result);
    }

    @Test
    public void testStop() {
        // Write to parcel
        final StopActivityItem item = new StopActivityItem(mActivityToken);
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        final StopActivityItem result = StopActivityItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertEquals(item, result);
    }

    @Test
    public void testStart() {
        // Write to parcel
        final StartActivityItem item = new StartActivityItem(mActivityToken,
                new ActivityOptions.SceneTransitionInfo());
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        final StartActivityItem result = StartActivityItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertEquals(item, result);
    }

    @Test
    public void testClientTransaction() {
        // Write to parcel
        final NewIntentItem callback1 =
                new NewIntentItem(mActivityToken, new ArrayList<>(), true /* resume */);
        final ActivityConfigurationChangeItem callback2 = new ActivityConfigurationChangeItem(
                mActivityToken, config(), new ActivityWindowInfo());

        final StopActivityItem lifecycleRequest = new StopActivityItem(mActivityToken);

        final ClientTransaction transaction = new ClientTransaction();
        transaction.addTransactionItem(callback1);
        transaction.addTransactionItem(callback2);
        transaction.addTransactionItem(lifecycleRequest);

        writeAndPrepareForReading(transaction);

        // Read from parcel and assert
        final ClientTransaction result = ClientTransaction.CREATOR.createFromParcel(mParcel);

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
        private final int mValue;

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

        public static final Creator<ParcelableData> CREATOR = new Creator<>() {
            @Override
            public ParcelableData createFromParcel(Parcel source) {
                return new ParcelableData(source.readInt());
            }

            @Override
            public ParcelableData[] newArray(int size) {
                return new ParcelableData[size];
            }
        };
    }
}
