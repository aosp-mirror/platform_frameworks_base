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

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNull;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.annotation.UserIdInt;
import android.app.Notification;
import android.app.NotificationManager;
import android.os.Bundle;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.widget.RemoteViews;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.messages.nano.SystemMessageProto;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ForegroundServiceControllerTest extends SysuiTestCase {
    public static @UserIdInt int USERID_ONE = 10; // UserManagerService.MIN_USER_ID;
    public static @UserIdInt int USERID_TWO = USERID_ONE + 1;

    private ForegroundServiceController fsc;

    @Before
    public void setUp() throws Exception {
        fsc = new ForegroundServiceControllerImpl(mContext);
    }

    @Test
    public void testNotificationCRUD_dungeon() {
        StatusBarNotification sbn_user1_app1_fg = makeMockFgSBN(USERID_ONE, "com.example.app1");
        StatusBarNotification sbn_user2_app2_fg = makeMockFgSBN(USERID_TWO, "com.example.app2");
        StatusBarNotification sbn_user1_app3_fg = makeMockFgSBN(USERID_ONE, "com.example.app3");
        StatusBarNotification sbn_user1_app1 = makeMockSBN(USERID_ONE, "com.example.app1",
                5000, "monkeys", Notification.FLAG_AUTO_CANCEL);
        StatusBarNotification sbn_user2_app1 = makeMockSBN(USERID_TWO, "com.example.app1",
                5000, "monkeys", Notification.FLAG_AUTO_CANCEL);

        assertFalse(fsc.removeNotification(sbn_user1_app3_fg));
        assertFalse(fsc.removeNotification(sbn_user2_app2_fg));
        assertFalse(fsc.removeNotification(sbn_user1_app1_fg));
        assertFalse(fsc.removeNotification(sbn_user1_app1));
        assertFalse(fsc.removeNotification(sbn_user2_app1));

        fsc.addNotification(sbn_user1_app1_fg, NotificationManager.IMPORTANCE_DEFAULT);
        fsc.addNotification(sbn_user2_app2_fg, NotificationManager.IMPORTANCE_DEFAULT);
        fsc.addNotification(sbn_user1_app3_fg, NotificationManager.IMPORTANCE_DEFAULT);
        fsc.addNotification(sbn_user1_app1, NotificationManager.IMPORTANCE_DEFAULT);
        fsc.addNotification(sbn_user2_app1, NotificationManager.IMPORTANCE_DEFAULT);

        // these are never added to the tracker
        assertFalse(fsc.removeNotification(sbn_user1_app1));
        assertFalse(fsc.removeNotification(sbn_user2_app1));

        fsc.updateNotification(sbn_user1_app1, NotificationManager.IMPORTANCE_DEFAULT);
        fsc.updateNotification(sbn_user2_app1, NotificationManager.IMPORTANCE_DEFAULT);
        // should still not be there
        assertFalse(fsc.removeNotification(sbn_user1_app1));
        assertFalse(fsc.removeNotification(sbn_user2_app1));

        fsc.updateNotification(sbn_user2_app2_fg, NotificationManager.IMPORTANCE_DEFAULT);
        fsc.updateNotification(sbn_user1_app3_fg, NotificationManager.IMPORTANCE_DEFAULT);
        fsc.updateNotification(sbn_user1_app1_fg, NotificationManager.IMPORTANCE_DEFAULT);

        assertTrue(fsc.removeNotification(sbn_user1_app3_fg));
        assertFalse(fsc.removeNotification(sbn_user1_app3_fg));

        assertTrue(fsc.removeNotification(sbn_user2_app2_fg));
        assertFalse(fsc.removeNotification(sbn_user2_app2_fg));

        assertTrue(fsc.removeNotification(sbn_user1_app1_fg));
        assertFalse(fsc.removeNotification(sbn_user1_app1_fg));

        assertFalse(fsc.removeNotification(sbn_user1_app1));
        assertFalse(fsc.removeNotification(sbn_user2_app1));
    }

    @Test
    public void testNotificationCRUD_stdLayout() {
        StatusBarNotification sbn_user1_app1_fg =
                makeMockFgSBN(USERID_ONE, "com.example.app1", 0, true);
        StatusBarNotification sbn_user2_app2_fg =
                makeMockFgSBN(USERID_TWO, "com.example.app2", 1, true);
        StatusBarNotification sbn_user1_app3_fg =
                makeMockFgSBN(USERID_ONE, "com.example.app3", 2, true);
        StatusBarNotification sbn_user1_app1 = makeMockSBN(USERID_ONE, "com.example.app1",
                5000, "monkeys", Notification.FLAG_AUTO_CANCEL);
        StatusBarNotification sbn_user2_app1 = makeMockSBN(USERID_TWO, "com.example.app1",
                5000, "monkeys", Notification.FLAG_AUTO_CANCEL);

        assertFalse(fsc.removeNotification(sbn_user1_app3_fg));
        assertFalse(fsc.removeNotification(sbn_user2_app2_fg));
        assertFalse(fsc.removeNotification(sbn_user1_app1_fg));
        assertFalse(fsc.removeNotification(sbn_user1_app1));
        assertFalse(fsc.removeNotification(sbn_user2_app1));

        fsc.addNotification(sbn_user1_app1_fg, NotificationManager.IMPORTANCE_MIN);
        fsc.addNotification(sbn_user2_app2_fg, NotificationManager.IMPORTANCE_MIN);
        fsc.addNotification(sbn_user1_app3_fg, NotificationManager.IMPORTANCE_MIN);
        fsc.addNotification(sbn_user1_app1, NotificationManager.IMPORTANCE_MIN);
        fsc.addNotification(sbn_user2_app1, NotificationManager.IMPORTANCE_MIN);

        // these are never added to the tracker
        assertFalse(fsc.removeNotification(sbn_user1_app1));
        assertFalse(fsc.removeNotification(sbn_user2_app1));

        fsc.updateNotification(sbn_user1_app1, NotificationManager.IMPORTANCE_MIN);
        fsc.updateNotification(sbn_user2_app1, NotificationManager.IMPORTANCE_MIN);
        // should still not be there
        assertFalse(fsc.removeNotification(sbn_user1_app1));
        assertFalse(fsc.removeNotification(sbn_user2_app1));

        fsc.updateNotification(sbn_user2_app2_fg, NotificationManager.IMPORTANCE_MIN);
        fsc.updateNotification(sbn_user1_app3_fg, NotificationManager.IMPORTANCE_MIN);
        fsc.updateNotification(sbn_user1_app1_fg, NotificationManager.IMPORTANCE_MIN);

        assertTrue(fsc.removeNotification(sbn_user1_app3_fg));
        assertFalse(fsc.removeNotification(sbn_user1_app3_fg));

        assertTrue(fsc.removeNotification(sbn_user2_app2_fg));
        assertFalse(fsc.removeNotification(sbn_user2_app2_fg));

        assertTrue(fsc.removeNotification(sbn_user1_app1_fg));
        assertFalse(fsc.removeNotification(sbn_user1_app1_fg));

        assertFalse(fsc.removeNotification(sbn_user1_app1));
        assertFalse(fsc.removeNotification(sbn_user2_app1));
    }

    @Test
    public void testAppOpsCRUD() {
        // no crash on remove that doesn't exist
        fsc.onAppOpChanged(9, 1000, "pkg1", false);
        assertNull(fsc.getAppOps(0, "pkg1"));

        // multiuser & multipackage
        fsc.onAppOpChanged(8, 50, "pkg1", true);
        fsc.onAppOpChanged(1, 60, "pkg3", true);
        fsc.onAppOpChanged(7, 500000, "pkg2", true);

        assertEquals(1, fsc.getAppOps(0, "pkg1").size());
        assertTrue(fsc.getAppOps(0, "pkg1").contains(8));

        assertEquals(1, fsc.getAppOps(UserHandle.getUserId(500000), "pkg2").size());
        assertTrue(fsc.getAppOps(UserHandle.getUserId(500000), "pkg2").contains(7));

        assertEquals(1, fsc.getAppOps(0, "pkg3").size());
        assertTrue(fsc.getAppOps(0, "pkg3").contains(1));

        // multiple ops for the same package
        fsc.onAppOpChanged(9, 50, "pkg1", true);
        fsc.onAppOpChanged(5, 50, "pkg1", true);

        assertEquals(3, fsc.getAppOps(0, "pkg1").size());
        assertTrue(fsc.getAppOps(0, "pkg1").contains(8));
        assertTrue(fsc.getAppOps(0, "pkg1").contains(9));
        assertTrue(fsc.getAppOps(0, "pkg1").contains(5));

        assertEquals(1, fsc.getAppOps(UserHandle.getUserId(500000), "pkg2").size());
        assertTrue(fsc.getAppOps(UserHandle.getUserId(500000), "pkg2").contains(7));

        // remove one of the multiples
        fsc.onAppOpChanged(9, 50, "pkg1", false);
        assertEquals(2, fsc.getAppOps(0, "pkg1").size());
        assertTrue(fsc.getAppOps(0, "pkg1").contains(8));
        assertTrue(fsc.getAppOps(0, "pkg1").contains(5));

        // remove last op
        fsc.onAppOpChanged(1, 60, "pkg3", false);
        assertNull(fsc.getAppOps(0, "pkg3"));
    }

    @Test
    public void testDungeonPredicate() {
        StatusBarNotification sbn_user1_app1 = makeMockSBN(USERID_ONE, "com.example.app1",
                5000, "monkeys", Notification.FLAG_AUTO_CANCEL);
        StatusBarNotification sbn_user1_dungeon = makeMockSBN(USERID_ONE, "android",
                SystemMessageProto.SystemMessage.NOTE_FOREGROUND_SERVICES,
                null, Notification.FLAG_NO_CLEAR);

        assertTrue(fsc.isDungeonNotification(sbn_user1_dungeon));
        assertFalse(fsc.isDungeonNotification(sbn_user1_app1));
    }

    @Test
    public void testDungeonCRUD() {
        StatusBarNotification sbn_user1_app1 = makeMockSBN(USERID_ONE, "com.example.app1",
                5000, "monkeys", Notification.FLAG_AUTO_CANCEL);
        StatusBarNotification sbn_user1_dungeon = makeMockSBN(USERID_ONE, "android",
                SystemMessageProto.SystemMessage.NOTE_FOREGROUND_SERVICES,
                null, Notification.FLAG_NO_CLEAR);

        fsc.addNotification(sbn_user1_app1, NotificationManager.IMPORTANCE_DEFAULT);
        fsc.addNotification(sbn_user1_dungeon, NotificationManager.IMPORTANCE_DEFAULT);

        fsc.removeNotification(sbn_user1_dungeon);
        assertFalse(fsc.removeNotification(sbn_user1_app1));
    }

    @Test
    public void testNeedsDungeonAfterRemovingUnrelatedNotification() {
        final String PKG1 = "com.example.app100";

        StatusBarNotification sbn_user1_app1 = makeMockSBN(USERID_ONE, PKG1,
                5000, "monkeys", Notification.FLAG_AUTO_CANCEL);
        StatusBarNotification sbn_user1_app1_fg = makeMockFgSBN(USERID_ONE, PKG1);

        // first add a normal notification
        fsc.addNotification(sbn_user1_app1, NotificationManager.IMPORTANCE_DEFAULT);
        // nothing required yet
        assertFalse(fsc.isDungeonNeededForUser(USERID_ONE));
        // now the app starts a fg service
        fsc.addNotification(makeMockDungeon(USERID_ONE, new String[]{ PKG1 }),
                NotificationManager.IMPORTANCE_DEFAULT);
        assertTrue(fsc.isDungeonNeededForUser(USERID_ONE)); // should be required!
        // add the fg notification
        fsc.addNotification(sbn_user1_app1_fg, NotificationManager.IMPORTANCE_DEFAULT);
        assertFalse(fsc.isDungeonNeededForUser(USERID_ONE)); // app1 has got it covered
        // remove the boring notification
        fsc.removeNotification(sbn_user1_app1);
        assertFalse(fsc.isDungeonNeededForUser(USERID_ONE)); // app1 has STILL got it covered
        assertTrue(fsc.removeNotification(sbn_user1_app1_fg));
        assertTrue(fsc.isDungeonNeededForUser(USERID_ONE)); // should be required!
    }

    @Test
    public void testSimpleAddRemove() {
        final String PKG1 = "com.example.app1";
        final String PKG2 = "com.example.app2";

        StatusBarNotification sbn_user1_app1 = makeMockSBN(USERID_ONE, PKG1,
                5000, "monkeys", Notification.FLAG_AUTO_CANCEL);
        fsc.addNotification(sbn_user1_app1, NotificationManager.IMPORTANCE_DEFAULT);

        // no services are "running"
        fsc.addNotification(makeMockDungeon(USERID_ONE, null),
                NotificationManager.IMPORTANCE_DEFAULT);

        assertFalse(fsc.isDungeonNeededForUser(USERID_ONE));
        assertFalse(fsc.isDungeonNeededForUser(USERID_TWO));

        fsc.updateNotification(makeMockDungeon(USERID_ONE, new String[]{PKG1}),
                NotificationManager.IMPORTANCE_DEFAULT);
        assertTrue(fsc.isDungeonNeededForUser(USERID_ONE)); // should be required!
        assertFalse(fsc.isDungeonNeededForUser(USERID_TWO));

        // switch to different package
        fsc.updateNotification(makeMockDungeon(USERID_ONE, new String[]{PKG2}),
                NotificationManager.IMPORTANCE_DEFAULT);
        assertTrue(fsc.isDungeonNeededForUser(USERID_ONE));
        assertFalse(fsc.isDungeonNeededForUser(USERID_TWO));

        fsc.updateNotification(makeMockDungeon(USERID_TWO, new String[]{PKG1}),
                NotificationManager.IMPORTANCE_DEFAULT);
        assertTrue(fsc.isDungeonNeededForUser(USERID_ONE));
        assertTrue(fsc.isDungeonNeededForUser(USERID_TWO)); // finally user2 needs one too

        fsc.updateNotification(makeMockDungeon(USERID_ONE, new String[]{PKG2, PKG1}),
                NotificationManager.IMPORTANCE_DEFAULT);
        assertTrue(fsc.isDungeonNeededForUser(USERID_ONE));
        assertTrue(fsc.isDungeonNeededForUser(USERID_TWO));

        fsc.removeNotification(makeMockDungeon(USERID_ONE, null /*unused*/));
        assertFalse(fsc.isDungeonNeededForUser(USERID_ONE));
        assertTrue(fsc.isDungeonNeededForUser(USERID_TWO));

        fsc.removeNotification(makeMockDungeon(USERID_TWO, null /*unused*/));
        assertFalse(fsc.isDungeonNeededForUser(USERID_ONE));
        assertFalse(fsc.isDungeonNeededForUser(USERID_TWO));
    }

    @Test
    public void testDungeonBasic() {
        final String PKG1 = "com.example.app0";

        StatusBarNotification sbn_user1_app1 = makeMockSBN(USERID_ONE, PKG1,
                5000, "monkeys", Notification.FLAG_AUTO_CANCEL);
        StatusBarNotification sbn_user1_app1_fg = makeMockFgSBN(USERID_ONE, PKG1);

        fsc.addNotification(sbn_user1_app1, NotificationManager.IMPORTANCE_DEFAULT); // not fg
        fsc.addNotification(makeMockDungeon(USERID_ONE, new String[]{ PKG1 }),
                NotificationManager.IMPORTANCE_DEFAULT);
        assertTrue(fsc.isDungeonNeededForUser(USERID_ONE)); // should be required!
        fsc.addNotification(sbn_user1_app1_fg, NotificationManager.IMPORTANCE_DEFAULT);
        assertFalse(fsc.isDungeonNeededForUser(USERID_ONE)); // app1 has got it covered
        assertFalse(fsc.isDungeonNeededForUser(USERID_TWO));

        // let's take out the other notification and see what happens.

        fsc.removeNotification(sbn_user1_app1);
        assertFalse(fsc.isDungeonNeededForUser(USERID_ONE)); // still covered by sbn_user1_app1_fg
        assertFalse(fsc.isDungeonNeededForUser(USERID_TWO));

        // let's attempt to downgrade the notification from FLAG_FOREGROUND and see what we get
        StatusBarNotification sbn_user1_app1_fg_sneaky = makeMockFgSBN(USERID_ONE, PKG1);
        sbn_user1_app1_fg_sneaky.getNotification().flags = 0;
        fsc.updateNotification(sbn_user1_app1_fg_sneaky, NotificationManager.IMPORTANCE_DEFAULT);
        assertTrue(fsc.isDungeonNeededForUser(USERID_ONE)); // should be required!
        assertFalse(fsc.isDungeonNeededForUser(USERID_TWO));

        // ok, ok, we'll put it back
        sbn_user1_app1_fg_sneaky.getNotification().flags = Notification.FLAG_FOREGROUND_SERVICE;
        fsc.updateNotification(sbn_user1_app1_fg, NotificationManager.IMPORTANCE_DEFAULT);
        assertFalse(fsc.isDungeonNeededForUser(USERID_ONE));
        assertFalse(fsc.isDungeonNeededForUser(USERID_TWO));

        assertTrue(fsc.removeNotification(sbn_user1_app1_fg_sneaky));
        assertTrue(fsc.isDungeonNeededForUser(USERID_ONE)); // should be required!
        assertFalse(fsc.isDungeonNeededForUser(USERID_TWO));

        // now let's test an upgrade
        fsc.addNotification(sbn_user1_app1, NotificationManager.IMPORTANCE_DEFAULT);
        assertTrue(fsc.isDungeonNeededForUser(USERID_ONE));
        assertFalse(fsc.isDungeonNeededForUser(USERID_TWO));
        sbn_user1_app1.getNotification().flags |= Notification.FLAG_FOREGROUND_SERVICE;
        fsc.updateNotification(sbn_user1_app1,
                NotificationManager.IMPORTANCE_DEFAULT); // this is now a fg notification

        assertFalse(fsc.isDungeonNeededForUser(USERID_TWO));
        assertFalse(fsc.isDungeonNeededForUser(USERID_ONE));

        // remove it, make sure we're out of compliance again
        assertTrue(fsc.removeNotification(sbn_user1_app1)); // was fg, should return true
        assertFalse(fsc.removeNotification(sbn_user1_app1));
        assertFalse(fsc.isDungeonNeededForUser(USERID_TWO));
        assertTrue(fsc.isDungeonNeededForUser(USERID_ONE));

        // importance upgrade
        fsc.addNotification(sbn_user1_app1_fg, NotificationManager.IMPORTANCE_MIN);
        assertTrue(fsc.isDungeonNeededForUser(USERID_ONE));
        assertFalse(fsc.isDungeonNeededForUser(USERID_TWO));
        sbn_user1_app1.getNotification().flags |= Notification.FLAG_FOREGROUND_SERVICE;
        fsc.updateNotification(sbn_user1_app1_fg,
                NotificationManager.IMPORTANCE_DEFAULT); // this is now a fg notification

        // finally, let's turn off the service
        fsc.addNotification(makeMockDungeon(USERID_ONE, null),
                NotificationManager.IMPORTANCE_DEFAULT);

        assertFalse(fsc.isDungeonNeededForUser(USERID_ONE));
        assertFalse(fsc.isDungeonNeededForUser(USERID_TWO));
    }

    @Test
    public void testOverlayPredicate() {
        StatusBarNotification sbn_user1_app1 = makeMockSBN(USERID_ONE, "com.example.app1",
                5000, "monkeys", Notification.FLAG_AUTO_CANCEL);
        StatusBarNotification sbn_user1_overlay = makeMockSBN(USERID_ONE, "android",
                0, "AlertWindowNotification", Notification.FLAG_NO_CLEAR);

        assertTrue(fsc.isSystemAlertNotification(sbn_user1_overlay));
        assertFalse(fsc.isSystemAlertNotification(sbn_user1_app1));
    }

    @Test
    public void testStdLayoutBasic() {
        final String PKG1 = "com.example.app0";

        StatusBarNotification sbn_user1_app1 = makeMockFgSBN(USERID_ONE, PKG1, 0, true);
        sbn_user1_app1.getNotification().flags = 0;
        StatusBarNotification sbn_user1_app1_fg = makeMockFgSBN(USERID_ONE, PKG1, 1, true);
        fsc.addNotification(sbn_user1_app1, NotificationManager.IMPORTANCE_MIN); // not fg
        assertTrue(fsc.isSystemAlertWarningNeeded(USERID_ONE, PKG1)); // should be required!
        fsc.addNotification(sbn_user1_app1_fg, NotificationManager.IMPORTANCE_MIN);
        assertFalse(fsc.isSystemAlertWarningNeeded(USERID_ONE, PKG1)); // app1 has got it covered
        assertFalse(fsc.isSystemAlertWarningNeeded(USERID_TWO, "otherpkg"));
        // let's take out the non-fg notification and see what happens.
        fsc.removeNotification(sbn_user1_app1);
        // still covered by sbn_user1_app1_fg
        assertFalse(fsc.isSystemAlertWarningNeeded(USERID_ONE, PKG1));
        assertFalse(fsc.isSystemAlertWarningNeeded(USERID_TWO, "anyPkg"));

        // let's attempt to downgrade the notification from FLAG_FOREGROUND and see what we get
        StatusBarNotification sbn_user1_app1_fg_sneaky = makeMockFgSBN(USERID_ONE, PKG1, 1, true);
        sbn_user1_app1_fg_sneaky.getNotification().flags = 0;
        fsc.updateNotification(sbn_user1_app1_fg_sneaky, NotificationManager.IMPORTANCE_MIN);
        assertTrue(fsc.isSystemAlertWarningNeeded(USERID_ONE, PKG1)); // should be required!
        assertFalse(fsc.isSystemAlertWarningNeeded(USERID_TWO, "anything"));
        // ok, ok, we'll put it back
        sbn_user1_app1_fg_sneaky.getNotification().flags = Notification.FLAG_FOREGROUND_SERVICE;
        fsc.updateNotification(sbn_user1_app1_fg, NotificationManager.IMPORTANCE_MIN);
        assertFalse(fsc.isSystemAlertWarningNeeded(USERID_ONE, PKG1));
        assertFalse(fsc.isSystemAlertWarningNeeded(USERID_TWO, "whatever"));

        assertTrue(fsc.removeNotification(sbn_user1_app1_fg_sneaky));
        assertTrue(fsc.isSystemAlertWarningNeeded(USERID_ONE, PKG1)); // should be required!
        assertFalse(fsc.isSystemAlertWarningNeeded(USERID_TWO, "a"));

        // let's try a custom layout
        sbn_user1_app1_fg_sneaky = makeMockFgSBN(USERID_ONE, PKG1, 1, false);
        fsc.updateNotification(sbn_user1_app1_fg_sneaky, NotificationManager.IMPORTANCE_MIN);
        assertTrue(fsc.isSystemAlertWarningNeeded(USERID_ONE, PKG1)); // should be required!
        assertFalse(fsc.isSystemAlertWarningNeeded(USERID_TWO, "anything"));
        // now let's test an upgrade (non fg to fg)
        fsc.addNotification(sbn_user1_app1, NotificationManager.IMPORTANCE_MIN);
        assertTrue(fsc.isSystemAlertWarningNeeded(USERID_ONE, PKG1));
        assertFalse(fsc.isSystemAlertWarningNeeded(USERID_TWO, "b"));
        sbn_user1_app1.getNotification().flags |= Notification.FLAG_FOREGROUND_SERVICE;
        fsc.updateNotification(sbn_user1_app1,
                NotificationManager.IMPORTANCE_MIN); // this is now a fg notification

        assertFalse(fsc.isSystemAlertWarningNeeded(USERID_TWO, PKG1));
        assertFalse(fsc.isSystemAlertWarningNeeded(USERID_ONE, PKG1));

        // remove it, make sure we're out of compliance again
        assertTrue(fsc.removeNotification(sbn_user1_app1)); // was fg, should return true
        assertFalse(fsc.removeNotification(sbn_user1_app1));
        assertFalse(fsc.isSystemAlertWarningNeeded(USERID_TWO, PKG1));
        assertTrue(fsc.isSystemAlertWarningNeeded(USERID_ONE, PKG1));
    }

    private StatusBarNotification makeMockSBN(int userid, String pkg, int id, String tag,
            int flags) {
        final Notification n = mock(Notification.class);
        n.extras = new Bundle();
        n.flags = flags;
        return makeMockSBN(userid, pkg, id, tag, n);
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

    private StatusBarNotification makeMockFgSBN(int userid, String pkg, int id,
            boolean usesStdLayout) {
        StatusBarNotification sbn =
                makeMockSBN(userid, pkg, id, "foo", Notification.FLAG_FOREGROUND_SERVICE);
        if (usesStdLayout) {
            sbn.getNotification().contentView = null;
            sbn.getNotification().headsUpContentView = null;
            sbn.getNotification().bigContentView = null;
        } else {
            sbn.getNotification().contentView = mock(RemoteViews.class);
        }
        return sbn;
    }

    private StatusBarNotification makeMockFgSBN(int userid, String pkg) {
        return makeMockSBN(userid, pkg, 1000, "foo", Notification.FLAG_FOREGROUND_SERVICE);
    }

    private StatusBarNotification makeMockDungeon(int userid, String[] pkgs) {
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
}
