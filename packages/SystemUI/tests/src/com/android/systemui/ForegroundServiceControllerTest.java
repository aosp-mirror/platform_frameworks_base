/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui;

import static android.service.notification.NotificationListenerService.REASON_APP_CANCEL;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNull;
import static junit.framework.TestCase.fail;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.UserIdInt;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.widget.RemoteViews;

import androidx.test.filters.SmallTest;

import com.android.internal.messages.nano.SystemMessageProto;
import com.android.systemui.appops.AppOpsController;
import com.android.systemui.statusbar.notification.NotificationEntryListener;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.util.time.FakeSystemClock;

import junit.framework.Assert;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class ForegroundServiceControllerTest extends SysuiTestCase {
    private ForegroundServiceController mFsc;
    private ForegroundServiceNotificationListener mListener;
    private NotificationEntryListener mEntryListener;
    private final FakeSystemClock mClock = new FakeSystemClock();
    @Mock private NotificationEntryManager mEntryManager;
    @Mock private AppOpsController mAppOpsController;
    @Mock private Handler mMainHandler;
    @Mock private NotifPipeline mNotifPipeline;

    @Before
    public void setUp() throws Exception {
        // allow the TestLooper to be asserted as the main thread these tests
        allowTestableLooperAsMainThread();

        MockitoAnnotations.initMocks(this);
        mFsc = new ForegroundServiceController(
                mEntryManager, mAppOpsController, mMainHandler);
        mListener = new ForegroundServiceNotificationListener(
                mContext, mFsc, mEntryManager, mNotifPipeline,
                mock(ForegroundServiceLifetimeExtender.class), mClock);
        ArgumentCaptor<NotificationEntryListener> entryListenerCaptor =
                ArgumentCaptor.forClass(NotificationEntryListener.class);
        verify(mEntryManager).addNotificationEntryListener(
                entryListenerCaptor.capture());
        mEntryListener = entryListenerCaptor.getValue();
    }

    @Test
    public void testAppOpsChangedCalledFromBgThread() {
        try {
            // WHEN onAppOpChanged is called from a different thread than the MainLooper
            disallowTestableLooperAsMainThread();
            NotificationEntry entry = createFgEntry();
            mFsc.onAppOpChanged(
                    AppOpsManager.OP_CAMERA,
                    entry.getSbn().getUid(),
                    entry.getSbn().getPackageName(),
                    true);

            // This test is run on the TestableLooper, which is not the MainLooper, so
            // we expect an exception to be thrown
            fail("onAppOpChanged shouldn't be allowed to be called from a bg thread.");
        } catch (IllegalStateException e) {
            // THEN expect an exception
        }
    }

    @Test
    public void testAppOps_appOpChangedBeforeNotificationExists() {
        // GIVEN app op exists, but notification doesn't exist in NEM yet
        NotificationEntry entry = createFgEntry();
        mFsc.onAppOpChanged(
                AppOpsManager.OP_CAMERA,
                entry.getSbn().getUid(),
                entry.getSbn().getPackageName(),
                true);
        assertFalse(entry.mActiveAppOps.contains(AppOpsManager.OP_CAMERA));

        // WHEN the notification is added
        mEntryListener.onPendingEntryAdded(entry);

        // THEN the app op is added to the entry
        Assert.assertTrue(entry.mActiveAppOps.contains(AppOpsManager.OP_CAMERA));
    }

    @Test
    public void testAppOps_appOpAddedToForegroundNotif() {
        // GIVEN a notification associated with a foreground service
        NotificationEntry entry = addFgEntry();
        when(mEntryManager.getPendingOrActiveNotif(entry.getKey())).thenReturn(entry);

        // WHEN we are notified of a new app op for this notification
        mFsc.onAppOpChanged(
                AppOpsManager.OP_CAMERA,
                entry.getSbn().getUid(),
                entry.getSbn().getPackageName(),
                true);

        // THEN the app op is added to the entry
        Assert.assertTrue(entry.mActiveAppOps.contains(AppOpsManager.OP_CAMERA));

        // THEN notification views are updated since the notification is visible
        verify(mEntryManager, times(1)).updateNotifications(anyString());
    }

    @Test
    public void testAppOpsAlreadyAdded() {
        // GIVEN a foreground service associated notification that already has the correct app op
        NotificationEntry entry = addFgEntry();
        entry.mActiveAppOps.add(AppOpsManager.OP_CAMERA);
        when(mEntryManager.getPendingOrActiveNotif(entry.getKey())).thenReturn(entry);

        // WHEN we are notified of the same app op for this notification
        mFsc.onAppOpChanged(
                AppOpsManager.OP_CAMERA,
                entry.getSbn().getUid(),
                entry.getSbn().getPackageName(),
                true);

        // THEN the app op still exists in the notification entry
        Assert.assertTrue(entry.mActiveAppOps.contains(AppOpsManager.OP_CAMERA));

        // THEN notification views aren't updated since nothing changed
        verify(mEntryManager, never()).updateNotifications(anyString());
    }

    @Test
    public void testAppOps_appOpNotAddedToUnrelatedNotif() {
        // GIVEN no notification entries correspond to the newly updated appOp
        NotificationEntry entry = addFgEntry();
        when(mEntryManager.getPendingOrActiveNotif(entry.getKey())).thenReturn(null);

        // WHEN a new app op is detected
        mFsc.onAppOpChanged(
                AppOpsManager.OP_CAMERA,
                entry.getSbn().getUid(),
                entry.getSbn().getPackageName(),
                true);

        // THEN we won't see appOps on the entry
        Assert.assertFalse(entry.mActiveAppOps.contains(AppOpsManager.OP_CAMERA));

        // THEN notification views aren't updated since nothing changed
        verify(mEntryManager, never()).updateNotifications(anyString());
    }

    @Test
    public void testAppOpsCRUD() {
        // no crash on remove that doesn't exist
        mFsc.onAppOpChanged(9, 1000, "pkg1", false);
        assertNull(mFsc.getAppOps(0, "pkg1"));

        // multiuser & multipackage
        mFsc.onAppOpChanged(8, 50, "pkg1", true);
        mFsc.onAppOpChanged(1, 60, "pkg3", true);
        mFsc.onAppOpChanged(7, 500000, "pkg2", true);

        assertEquals(1, mFsc.getAppOps(0, "pkg1").size());
        assertTrue(mFsc.getAppOps(0, "pkg1").contains(8));

        assertEquals(1, mFsc.getAppOps(UserHandle.getUserId(500000), "pkg2").size());
        assertTrue(mFsc.getAppOps(UserHandle.getUserId(500000), "pkg2").contains(7));

        assertEquals(1, mFsc.getAppOps(0, "pkg3").size());
        assertTrue(mFsc.getAppOps(0, "pkg3").contains(1));

        // multiple ops for the same package
        mFsc.onAppOpChanged(9, 50, "pkg1", true);
        mFsc.onAppOpChanged(5, 50, "pkg1", true);

        assertEquals(3, mFsc.getAppOps(0, "pkg1").size());
        assertTrue(mFsc.getAppOps(0, "pkg1").contains(8));
        assertTrue(mFsc.getAppOps(0, "pkg1").contains(9));
        assertTrue(mFsc.getAppOps(0, "pkg1").contains(5));

        assertEquals(1, mFsc.getAppOps(UserHandle.getUserId(500000), "pkg2").size());
        assertTrue(mFsc.getAppOps(UserHandle.getUserId(500000), "pkg2").contains(7));

        // remove one of the multiples
        mFsc.onAppOpChanged(9, 50, "pkg1", false);
        assertEquals(2, mFsc.getAppOps(0, "pkg1").size());
        assertTrue(mFsc.getAppOps(0, "pkg1").contains(8));
        assertTrue(mFsc.getAppOps(0, "pkg1").contains(5));

        // remove last op
        mFsc.onAppOpChanged(1, 60, "pkg3", false);
        assertNull(mFsc.getAppOps(0, "pkg3"));
    }

    @Test
    public void testDisclosurePredicate() {
        StatusBarNotification sbn_user1_app1 = makeMockSBN(USERID_ONE, "com.example.app1",
                5000, "monkeys", Notification.FLAG_AUTO_CANCEL);
        StatusBarNotification sbn_user1_disclosure = makeMockSBN(USERID_ONE, "android",
                SystemMessageProto.SystemMessage.NOTE_FOREGROUND_SERVICES,
                null, Notification.FLAG_NO_CLEAR);

        assertTrue(mFsc.isDisclosureNotification(sbn_user1_disclosure));
        assertFalse(mFsc.isDisclosureNotification(sbn_user1_app1));
    }

    @Test
    public void testNeedsDisclosureAfterRemovingUnrelatedNotification() {
        final String PKG1 = "com.example.app100";

        StatusBarNotification sbn_user1_app1 = makeMockSBN(USERID_ONE, PKG1,
                5000, "monkeys", Notification.FLAG_AUTO_CANCEL);
        StatusBarNotification sbn_user1_app1_fg = makeMockFgSBN(USERID_ONE, PKG1);

        // first add a normal notification
        entryAdded(sbn_user1_app1, NotificationManager.IMPORTANCE_DEFAULT);
        // nothing required yet
        assertFalse(mFsc.isDisclosureNeededForUser(USERID_ONE));
        // now the app starts a fg service
        entryAdded(makeMockDisclosure(USERID_ONE, new String[]{PKG1}),
                NotificationManager.IMPORTANCE_DEFAULT);
        assertTrue(mFsc.isDisclosureNeededForUser(USERID_ONE)); // should be required!
        // add the fg notification
        entryAdded(sbn_user1_app1_fg, NotificationManager.IMPORTANCE_DEFAULT);
        assertFalse(mFsc.isDisclosureNeededForUser(USERID_ONE)); // app1 has got it covered
        // remove the boring notification
        entryRemoved(sbn_user1_app1);
        assertFalse(mFsc.isDisclosureNeededForUser(USERID_ONE)); // app1 has STILL got it covered
        entryRemoved(sbn_user1_app1_fg);
        assertTrue(mFsc.isDisclosureNeededForUser(USERID_ONE)); // should be required!
    }

    @Test
    public void testSimpleAddRemove() {
        final String PKG1 = "com.example.app1";
        final String PKG2 = "com.example.app2";

        StatusBarNotification sbn_user1_app1 = makeMockSBN(USERID_ONE, PKG1,
                5000, "monkeys", Notification.FLAG_AUTO_CANCEL);
        entryAdded(sbn_user1_app1, NotificationManager.IMPORTANCE_DEFAULT);

        // no services are "running"
        entryAdded(makeMockDisclosure(USERID_ONE, null),
                NotificationManager.IMPORTANCE_DEFAULT);

        assertFalse(mFsc.isDisclosureNeededForUser(USERID_ONE));
        assertFalse(mFsc.isDisclosureNeededForUser(USERID_TWO));

        entryUpdated(makeMockDisclosure(USERID_ONE, new String[]{PKG1}),
                NotificationManager.IMPORTANCE_DEFAULT);
        assertTrue(mFsc.isDisclosureNeededForUser(USERID_ONE)); // should be required!
        assertFalse(mFsc.isDisclosureNeededForUser(USERID_TWO));

        // switch to different package
        entryUpdated(makeMockDisclosure(USERID_ONE, new String[]{PKG2}),
                NotificationManager.IMPORTANCE_DEFAULT);
        assertTrue(mFsc.isDisclosureNeededForUser(USERID_ONE));
        assertFalse(mFsc.isDisclosureNeededForUser(USERID_TWO));

        entryUpdated(makeMockDisclosure(USERID_TWO, new String[]{PKG1}),
                NotificationManager.IMPORTANCE_DEFAULT);
        assertTrue(mFsc.isDisclosureNeededForUser(USERID_ONE));
        assertTrue(mFsc.isDisclosureNeededForUser(USERID_TWO)); // finally user2 needs one too

        entryUpdated(makeMockDisclosure(USERID_ONE, new String[]{PKG2, PKG1}),
                NotificationManager.IMPORTANCE_DEFAULT);
        assertTrue(mFsc.isDisclosureNeededForUser(USERID_ONE));
        assertTrue(mFsc.isDisclosureNeededForUser(USERID_TWO));

        entryRemoved(makeMockDisclosure(USERID_ONE, null /*unused*/));
        assertFalse(mFsc.isDisclosureNeededForUser(USERID_ONE));
        assertTrue(mFsc.isDisclosureNeededForUser(USERID_TWO));

        entryRemoved(makeMockDisclosure(USERID_TWO, null /*unused*/));
        assertFalse(mFsc.isDisclosureNeededForUser(USERID_ONE));
        assertFalse(mFsc.isDisclosureNeededForUser(USERID_TWO));
    }

    @Test
    public void testDisclosureBasic() {
        final String PKG1 = "com.example.app0";

        StatusBarNotification sbn_user1_app1 = makeMockSBN(USERID_ONE, PKG1,
                5000, "monkeys", Notification.FLAG_AUTO_CANCEL);
        StatusBarNotification sbn_user1_app1_fg = makeMockFgSBN(USERID_ONE, PKG1);

        entryAdded(sbn_user1_app1, NotificationManager.IMPORTANCE_DEFAULT); // not fg
        entryAdded(makeMockDisclosure(USERID_ONE, new String[]{PKG1}),
                NotificationManager.IMPORTANCE_DEFAULT);
        assertTrue(mFsc.isDisclosureNeededForUser(USERID_ONE)); // should be required!
        entryAdded(sbn_user1_app1_fg, NotificationManager.IMPORTANCE_DEFAULT);
        assertFalse(mFsc.isDisclosureNeededForUser(USERID_ONE)); // app1 has got it covered
        assertFalse(mFsc.isDisclosureNeededForUser(USERID_TWO));

        // let's take out the other notification and see what happens.

        entryRemoved(sbn_user1_app1);
        assertFalse(
                mFsc.isDisclosureNeededForUser(USERID_ONE)); // still covered by sbn_user1_app1_fg
        assertFalse(mFsc.isDisclosureNeededForUser(USERID_TWO));

        // let's attempt to downgrade the notification from FLAG_FOREGROUND and see what we get
        StatusBarNotification sbn_user1_app1_fg_sneaky = makeMockFgSBN(USERID_ONE, PKG1);
        sbn_user1_app1_fg_sneaky.getNotification().flags = 0;
        entryUpdated(sbn_user1_app1_fg_sneaky,
                NotificationManager.IMPORTANCE_DEFAULT);
        assertTrue(mFsc.isDisclosureNeededForUser(USERID_ONE)); // should be required!
        assertFalse(mFsc.isDisclosureNeededForUser(USERID_TWO));

        // ok, ok, we'll put it back
        sbn_user1_app1_fg_sneaky.getNotification().flags = Notification.FLAG_FOREGROUND_SERVICE;
        entryUpdated(sbn_user1_app1_fg, NotificationManager.IMPORTANCE_DEFAULT);
        assertFalse(mFsc.isDisclosureNeededForUser(USERID_ONE));
        assertFalse(mFsc.isDisclosureNeededForUser(USERID_TWO));

        entryRemoved(sbn_user1_app1_fg_sneaky);
        assertTrue(mFsc.isDisclosureNeededForUser(USERID_ONE)); // should be required!
        assertFalse(mFsc.isDisclosureNeededForUser(USERID_TWO));

        // now let's test an upgrade
        entryAdded(sbn_user1_app1, NotificationManager.IMPORTANCE_DEFAULT);
        assertTrue(mFsc.isDisclosureNeededForUser(USERID_ONE));
        assertFalse(mFsc.isDisclosureNeededForUser(USERID_TWO));
        sbn_user1_app1.getNotification().flags |= Notification.FLAG_FOREGROUND_SERVICE;
        entryUpdated(sbn_user1_app1,
                NotificationManager.IMPORTANCE_DEFAULT); // this is now a fg notification

        assertFalse(mFsc.isDisclosureNeededForUser(USERID_TWO));
        assertFalse(mFsc.isDisclosureNeededForUser(USERID_ONE));

        // remove it, make sure we're out of compliance again
        entryRemoved(sbn_user1_app1); // was fg, should return true
        entryRemoved(sbn_user1_app1);
        assertFalse(mFsc.isDisclosureNeededForUser(USERID_TWO));
        assertTrue(mFsc.isDisclosureNeededForUser(USERID_ONE));

        // importance upgrade
        entryAdded(sbn_user1_app1_fg, NotificationManager.IMPORTANCE_MIN);
        assertTrue(mFsc.isDisclosureNeededForUser(USERID_ONE));
        assertFalse(mFsc.isDisclosureNeededForUser(USERID_TWO));
        sbn_user1_app1.getNotification().flags |= Notification.FLAG_FOREGROUND_SERVICE;
        entryUpdated(sbn_user1_app1_fg,
                NotificationManager.IMPORTANCE_DEFAULT); // this is now a fg notification

        // finally, let's turn off the service
        entryAdded(makeMockDisclosure(USERID_ONE, null),
                NotificationManager.IMPORTANCE_DEFAULT);

        assertFalse(mFsc.isDisclosureNeededForUser(USERID_ONE));
        assertFalse(mFsc.isDisclosureNeededForUser(USERID_TWO));
    }

    @Test
    public void testOverlayPredicate() {
        StatusBarNotification sbn_user1_app1 = makeMockSBN(USERID_ONE, "com.example.app1",
                5000, "monkeys", Notification.FLAG_AUTO_CANCEL);
        StatusBarNotification sbn_user1_overlay = makeMockSBN(USERID_ONE, "android",
                0, "AlertWindowNotification", Notification.FLAG_NO_CLEAR);

        assertTrue(mFsc.isSystemAlertNotification(sbn_user1_overlay));
        assertFalse(mFsc.isSystemAlertNotification(sbn_user1_app1));
    }

    @Test
    public void testStdLayoutBasic() {
        final String PKG1 = "com.example.app0";

        StatusBarNotification sbn_user1_app1 = makeMockFgSBN(USERID_ONE, PKG1, 0, true);
        sbn_user1_app1.getNotification().flags = 0;
        StatusBarNotification sbn_user1_app1_fg = makeMockFgSBN(USERID_ONE, PKG1, 1, true);
        entryAdded(sbn_user1_app1, NotificationManager.IMPORTANCE_MIN); // not fg
        assertTrue(mFsc.isSystemAlertWarningNeeded(USERID_ONE, PKG1)); // should be required!
        entryAdded(sbn_user1_app1_fg, NotificationManager.IMPORTANCE_MIN);
        assertFalse(mFsc.isSystemAlertWarningNeeded(USERID_ONE, PKG1)); // app1 has got it covered
        assertFalse(mFsc.isSystemAlertWarningNeeded(USERID_TWO, "otherpkg"));
        // let's take out the non-fg notification and see what happens.
        entryRemoved(sbn_user1_app1);
        // still covered by sbn_user1_app1_fg
        assertFalse(mFsc.isSystemAlertWarningNeeded(USERID_ONE, PKG1));
        assertFalse(mFsc.isSystemAlertWarningNeeded(USERID_TWO, "anyPkg"));

        // let's attempt to downgrade the notification from FLAG_FOREGROUND and see what we get
        StatusBarNotification sbn_user1_app1_fg_sneaky = makeMockFgSBN(USERID_ONE, PKG1, 1, true);
        sbn_user1_app1_fg_sneaky.getNotification().flags = 0;
        entryUpdated(sbn_user1_app1_fg_sneaky, NotificationManager.IMPORTANCE_MIN);
        assertTrue(mFsc.isSystemAlertWarningNeeded(USERID_ONE, PKG1)); // should be required!
        assertFalse(mFsc.isSystemAlertWarningNeeded(USERID_TWO, "anything"));
        // ok, ok, we'll put it back
        sbn_user1_app1_fg_sneaky.getNotification().flags = Notification.FLAG_FOREGROUND_SERVICE;
        entryUpdated(sbn_user1_app1_fg, NotificationManager.IMPORTANCE_MIN);
        assertFalse(mFsc.isSystemAlertWarningNeeded(USERID_ONE, PKG1));
        assertFalse(mFsc.isSystemAlertWarningNeeded(USERID_TWO, "whatever"));

        entryRemoved(sbn_user1_app1_fg_sneaky);
        assertTrue(mFsc.isSystemAlertWarningNeeded(USERID_ONE, PKG1)); // should be required!
        assertFalse(mFsc.isSystemAlertWarningNeeded(USERID_TWO, "a"));

        // let's try a custom layout
        sbn_user1_app1_fg_sneaky = makeMockFgSBN(USERID_ONE, PKG1, 1, false);
        entryUpdated(sbn_user1_app1_fg_sneaky, NotificationManager.IMPORTANCE_MIN);
        assertTrue(mFsc.isSystemAlertWarningNeeded(USERID_ONE, PKG1)); // should be required!
        assertFalse(mFsc.isSystemAlertWarningNeeded(USERID_TWO, "anything"));
        // now let's test an upgrade (non fg to fg)
        entryAdded(sbn_user1_app1, NotificationManager.IMPORTANCE_MIN);
        assertTrue(mFsc.isSystemAlertWarningNeeded(USERID_ONE, PKG1));
        assertFalse(mFsc.isSystemAlertWarningNeeded(USERID_TWO, "b"));
        sbn_user1_app1.getNotification().flags |= Notification.FLAG_FOREGROUND_SERVICE;
        entryUpdated(sbn_user1_app1,
                NotificationManager.IMPORTANCE_MIN); // this is now a fg notification

        assertFalse(mFsc.isSystemAlertWarningNeeded(USERID_TWO, PKG1));
        assertFalse(mFsc.isSystemAlertWarningNeeded(USERID_ONE, PKG1));

        // remove it, make sure we're out of compliance again
        entryRemoved(sbn_user1_app1); // was fg, should return true
        entryRemoved(sbn_user1_app1);
        assertFalse(mFsc.isSystemAlertWarningNeeded(USERID_TWO, PKG1));
        assertTrue(mFsc.isSystemAlertWarningNeeded(USERID_ONE, PKG1));
    }

    private StatusBarNotification makeMockSBN(int userId, String pkg, int id, String tag,
            int flags) {
        final Notification n = mock(Notification.class);
        n.extras = new Bundle();
        n.flags = flags;
        return makeMockSBN(userId, pkg, id, tag, n);
    }

    private StatusBarNotification makeMockSBN(int userid, String pkg, int id, String tag,
            Notification n) {
        final StatusBarNotification sbn = mock(StatusBarNotification.class);
        when(sbn.getNotification()).thenReturn(n);
        when(sbn.getId()).thenReturn(id);
        when(sbn.getPackageName()).thenReturn(pkg);
        when(sbn.getTag()).thenReturn(tag);
        when(sbn.getUserId()).thenReturn(userid);
        when(sbn.getUser()).thenReturn(new UserHandle(userid));
        when(sbn.getKey()).thenReturn("MOCK:"+userid+"|"+pkg+"|"+id+"|"+tag);
        return sbn;
    }

    private StatusBarNotification makeMockFgSBN(int uid, String pkg, int id,
            boolean usesStdLayout) {
        StatusBarNotification sbn =
                makeMockSBN(uid, pkg, id, "foo", Notification.FLAG_FOREGROUND_SERVICE);
        if (usesStdLayout) {
            sbn.getNotification().contentView = null;
            sbn.getNotification().headsUpContentView = null;
            sbn.getNotification().bigContentView = null;
        } else {
            sbn.getNotification().contentView = mock(RemoteViews.class);
        }
        return sbn;
    }

    private StatusBarNotification makeMockFgSBN(int uid, String pkg) {
        return makeMockSBN(uid, pkg, 1000, "foo", Notification.FLAG_FOREGROUND_SERVICE);
    }

    private StatusBarNotification makeMockDisclosure(int userid, String[] pkgs) {
        final Notification n = mock(Notification.class);
        n.flags = Notification.FLAG_ONGOING_EVENT;
        final Bundle extras = new Bundle();
        if (pkgs != null) extras.putStringArray(Notification.EXTRA_FOREGROUND_APPS, pkgs);
        n.extras = extras;
        n.when = System.currentTimeMillis() - 10000; // ten seconds ago
        final StatusBarNotification sbn = makeMockSBN(userid, "android",
                SystemMessageProto.SystemMessage.NOTE_FOREGROUND_SERVICES,
                null, n);
        sbn.getNotification().extras = extras;
        return sbn;
    }

    private NotificationEntry addFgEntry() {
        NotificationEntry entry = createFgEntry();
        mEntryListener.onPendingEntryAdded(entry);
        return entry;
    }

    private NotificationEntry createFgEntry() {
        return new NotificationEntryBuilder()
                .setSbn(makeMockFgSBN(0, TEST_PACKAGE_NAME, 1000, true))
                .setImportance(NotificationManager.IMPORTANCE_DEFAULT)
                .build();
    }

    private void entryRemoved(StatusBarNotification notification) {
        mEntryListener.onEntryRemoved(
                new NotificationEntryBuilder()
                        .setSbn(notification)
                        .build(),
                null,
                false,
                REASON_APP_CANCEL);
    }

    private void entryAdded(StatusBarNotification notification, int importance) {
        NotificationEntry entry = new NotificationEntryBuilder()
                .setSbn(notification)
                .setImportance(importance)
                .build();
        mEntryListener.onPendingEntryAdded(entry);
    }

    private void entryUpdated(StatusBarNotification notification, int importance) {
        NotificationEntry entry = new NotificationEntryBuilder()
                .setSbn(notification)
                .setImportance(importance)
                .build();
        mEntryListener.onPreEntryUpdated(entry);
    }

    @UserIdInt private static final int USERID_ONE = 10; // UserManagerService.MIN_USER_ID;
    @UserIdInt private static final int USERID_TWO = USERID_ONE + 1;
    private static final String TEST_PACKAGE_NAME = "test";
}
