package com.android.server.locksettings.recoverablekeystore;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.PendingIntent;
import android.content.Intent;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class RecoverySnapshotListenersStorageTest {

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
                InstrumentationRegistry.getTargetContext(), /*requestCode=*/1,
                new Intent(), /*flags=*/ 0);
        mStorage.setSnapshotListener(recoveryAgentUid, intent);

        assertTrue(mStorage.hasListener(recoveryAgentUid));
    }
}
