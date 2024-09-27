package com.android.server.locksettings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.locksettings.LockSettingsStorage.PersistentData;

import com.google.android.collect.Sets;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class WeaverBasedSyntheticPasswordTests extends SyntheticPasswordTests {

    @Before
    public void enableWeaver() throws Exception {
        mSpManager.enableWeaver();
    }

    // Tests that if the device is not yet provisioned and the FRP credential uses Weaver, then the
    // Weaver slot of the FRP credential is not reused.  Assumes that Weaver slots are allocated
    // sequentially, starting at slot 0.
    @Test
    public void testFrpWeaverSlotNotReused() {
        final int userId = SECONDARY_USER_ID;
        final int frpWeaverSlot = 0;

        setDeviceProvisioned(false);
        assertEquals(Sets.newHashSet(), mPasswordSlotManager.getUsedSlots());
        mStorage.writePersistentDataBlock(PersistentData.TYPE_SP_WEAVER, frpWeaverSlot, 0,
                new byte[1]);
        mService.initializeSyntheticPassword(userId); // This should allocate a Weaver slot.
        assertEquals(Sets.newHashSet(1), mPasswordSlotManager.getUsedSlots());
    }

    // Tests that if the device is already provisioned and the FRP credential uses Weaver, then the
    // Weaver slot of the FRP credential is reused.  This is not a very interesting test by itself;
    // it's here as a control for testFrpWeaverSlotNotReused().
    @Test
    public void testFrpWeaverSlotReused() {
        final int userId = SECONDARY_USER_ID;
        final int frpWeaverSlot = 0;

        setDeviceProvisioned(true);
        assertEquals(Sets.newHashSet(), mPasswordSlotManager.getUsedSlots());
        mStorage.writePersistentDataBlock(PersistentData.TYPE_SP_WEAVER, frpWeaverSlot, 0,
                new byte[1]);
        mService.initializeSyntheticPassword(userId); // This should allocate a Weaver slot.
        assertEquals(Sets.newHashSet(0), mPasswordSlotManager.getUsedSlots());
    }

    private int getNumUsedWeaverSlots() {
        return mPasswordSlotManager.getUsedSlots().size();
    }

    @Test
    public void testDisableWeaverOnUnsecuredUsers_false() {
        final int userId = PRIMARY_USER_ID;
        when(mResources.getBoolean(eq(
                        com.android.internal.R.bool.config_disableWeaverOnUnsecuredUsers)))
                .thenReturn(false);
        assertEquals(0, getNumUsedWeaverSlots());
        mService.initializeSyntheticPassword(userId);
        assertEquals(1, getNumUsedWeaverSlots());
        assertTrue(mService.setLockCredential(newPassword("password"), nonePassword(), userId));
        assertEquals(1, getNumUsedWeaverSlots());
        assertTrue(mService.setLockCredential(nonePassword(), newPassword("password"), userId));
        assertEquals(1, getNumUsedWeaverSlots());
    }

    @Test
    public void testDisableWeaverOnUnsecuredUsers_true() {
        final int userId = PRIMARY_USER_ID;
        when(mResources.getBoolean(eq(
                        com.android.internal.R.bool.config_disableWeaverOnUnsecuredUsers)))
                .thenReturn(true);
        assertEquals(0, getNumUsedWeaverSlots());
        mService.initializeSyntheticPassword(userId);
        assertEquals(0, getNumUsedWeaverSlots());
        assertTrue(mService.setLockCredential(newPassword("password"), nonePassword(), userId));
        assertEquals(1, getNumUsedWeaverSlots());
        assertTrue(mService.setLockCredential(nonePassword(), newPassword("password"), userId));
        assertEquals(0, getNumUsedWeaverSlots());
    }

    @Test
    public void testDisableWeaverOnUnsecuredUsers_defaultsToFalse() {
        assertFalse(mResources.getBoolean(
                    com.android.internal.R.bool.config_disableWeaverOnUnsecuredUsers));
    }
}
