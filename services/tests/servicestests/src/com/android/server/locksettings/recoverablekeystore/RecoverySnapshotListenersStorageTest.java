package com.android.server.locksettings.recoverablekeystore;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class RecoverySnapshotListenersStorageTest {

    private static final String TEST_INTENT_ACTION =
            "com.android.server.locksettings.recoverablekeystore.RECOVERY_SNAPSHOT_TEST_ACTION";

    private static final int TEST_TIMEOUT_SECONDS = 1;

    private final RecoverySnapshotListenersStorage mStorage =
            new RecoverySnapshotListenersStorage();

    @Test
    public void hasListener_isFalseForUnregisteredUid() {
        assertFalse(mStorage.hasListener(1000));
    }

    @Test
    public void hasListener_isTrueForRegisteredUid() {
        int recoveryAgentUid = 1000;
        PendingIntent intent = PendingIntent.getBroadcast(
                InstrumentationRegistry.getTargetContext(), /*requestCode=*/ 1,
                new Intent()
                        .setPackage(InstrumentationRegistry.getTargetContext().getPackageName()),
                /*flags=*/ PendingIntent.FLAG_MUTABLE);
        mStorage.setSnapshotListener(recoveryAgentUid, intent);

        assertTrue(mStorage.hasListener(recoveryAgentUid));
    }

    @Test
    public void setSnapshotListener_invokesIntentImmediatelyIfPreviouslyNotified()
            throws Exception {
        Context context = InstrumentationRegistry.getTargetContext();
        int recoveryAgentUid = 1000;
        mStorage.recoverySnapshotAvailable(recoveryAgentUid);
        PendingIntent intent = PendingIntent.getBroadcast(
                context, /*requestCode=*/ 0,
                new Intent(TEST_INTENT_ACTION).setPackage(context.getPackageName()),
                /*flags=*/PendingIntent.FLAG_MUTABLE);
        CountDownLatch latch = new CountDownLatch(1);
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                context.unregisterReceiver(this);
                latch.countDown();
            }
        }, new IntentFilter(TEST_INTENT_ACTION), Context.RECEIVER_EXPORTED_UNAUDITED);

        mStorage.setSnapshotListener(recoveryAgentUid, intent);

        assertTrue(latch.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    public void setSnapshotListener_doesNotRepeatedlyInvokeListener() throws Exception {
        Context context = InstrumentationRegistry.getTargetContext();
        int recoveryAgentUid = 1000;
        mStorage.recoverySnapshotAvailable(recoveryAgentUid);
        PendingIntent intent = PendingIntent.getBroadcast(
                context, /*requestCode=*/ 0,
                new Intent(TEST_INTENT_ACTION).setPackage(context.getPackageName()),
                /*flags=*/PendingIntent.FLAG_MUTABLE);
        CountDownLatch latch = new CountDownLatch(2);
        BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                latch.countDown();
            }
        };
        context.registerReceiver(broadcastReceiver, new IntentFilter(TEST_INTENT_ACTION),
                Context.RECEIVER_EXPORTED_UNAUDITED);

        mStorage.setSnapshotListener(recoveryAgentUid, intent);
        mStorage.setSnapshotListener(recoveryAgentUid, intent);

        assertFalse(latch.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        context.unregisterReceiver(broadcastReceiver);
    }
}
