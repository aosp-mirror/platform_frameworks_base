package com.android.server.content;

import android.os.Bundle;

import junit.framework.TestCase;

public class SyncManagerTest extends TestCase {

    final String KEY_1 = "key_1";
    final String KEY_2 = "key_2";

    public void testSyncExtrasEquals_WithNull() throws Exception {
        Bundle b1 = new Bundle();
        Bundle b2 = new Bundle();

        b1.putString(KEY_1, null);
        b2.putString(KEY_1, null);

        assertTrue("Null extra not properly compared between bundles.",
                SyncManager.syncExtrasEquals(b1, b2, false /* don't care about system extras */));
    }

    public void testSyncExtrasEqualsBigger_WithNull() throws Exception {
        Bundle b1 = new Bundle();
        Bundle b2 = new Bundle();

        b1.putString(KEY_1, null);
        b2.putString(KEY_1, null);

        b1.putString(KEY_2, "bla");
        b2.putString(KEY_2, "bla");

        assertTrue("Extras not properly compared between bundles.",
                SyncManager.syncExtrasEquals(b1, b2, false /* don't care about system extras */));
    }

    public void testSyncExtrasEqualsFails_differentValues() throws Exception {
        Bundle b1 = new Bundle();
        Bundle b2 = new Bundle();

        b1.putString(KEY_1, null);
        b2.putString(KEY_1, null);

        b1.putString(KEY_2, "bla");
        b2.putString(KEY_2, "ble");  // different key

        assertFalse("Extras considered equal when they are different.",
                SyncManager.syncExtrasEquals(b1, b2, false /* don't care about system extras */));
    }

    public void testSyncExtrasEqualsFails_differentNulls() throws Exception {
        Bundle b1 = new Bundle();
        Bundle b2 = new Bundle();

        b1.putString(KEY_1, null);
        b2.putString(KEY_1, "bla");  // different key

        b1.putString(KEY_2, "ble");
        b2.putString(KEY_2, "ble");

        assertFalse("Extras considered equal when they are different.",
                SyncManager.syncExtrasEquals(b1, b2, false /* don't care about system extras */));
    }
}
