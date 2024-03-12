/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.server.notification;

import static android.content.pm.PackageManager.MATCH_ANY_USER;
import static android.service.notification.NotificationListenerService.FLAG_FILTER_TYPE_ALERTING;
import static android.service.notification.NotificationListenerService.FLAG_FILTER_TYPE_CONVERSATIONS;
import static android.service.notification.NotificationListenerService.FLAG_FILTER_TYPE_ONGOING;
import static android.service.notification.NotificationListenerService.FLAG_FILTER_TYPE_SILENT;

import static com.android.server.notification.NotificationManagerService.NotificationListeners.TAG_REQUESTED_LISTENERS;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.intThat;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.pm.VersionedPackage;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.notification.INotificationListener;
import android.service.notification.NotificationListenerFilter;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationRankingUpdate;
import android.service.notification.NotificationStats;
import android.service.notification.StatusBarNotification;
import android.testing.TestableContext;
import android.util.ArraySet;
import android.util.Pair;
import android.util.Xml;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.UiServiceTestCase;

import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.internal.util.reflection.FieldSetter;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class NotificationListenersTest extends UiServiceTestCase {

    @Mock
    private PackageManager mPm;
    @Mock
    private IPackageManager miPm;
    @Mock
    private Resources mResources;

    @Mock
    NotificationManagerService mNm;
    @Mock
    private INotificationManager mINm;
    private TestableContext mContext = spy(getContext());

    NotificationManagerService.NotificationListeners mListeners;

    private ComponentName mCn1 = new ComponentName("pkg", "pkg.cmp");
    private ComponentName mCn2 = new ComponentName("pkg2", "pkg2.cmp2");
    private ComponentName mUninstalledComponent = new ComponentName("pkg3",
            "pkg3.NotificationListenerService");

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        getContext().setMockPackageManager(mPm);
        doNothing().when(mContext).sendBroadcastAsUser(any(), any(), any());

        when(mNm.isInteractionVisibleToListener(any(), anyInt())).thenReturn(true);

        mListeners = spy(mNm.new NotificationListeners(
                mContext, new Object(), mock(ManagedServices.UserProfiles.class), miPm));
        when(mNm.getBinderService()).thenReturn(mINm);
    }

    @Test
    public void testReadExtraTag() throws Exception {
        String xml = "<" + TAG_REQUESTED_LISTENERS + ">"
                + "<listener component=\"" + mCn1.flattenToString() + "\" user=\"0\">"
                + "<allowed types=\"7\" />"
                + "</listener>"
                + "<listener component=\"" + mCn2.flattenToString() + "\" user=\"10\">"
                + "<allowed types=\"4\" />"
                + "<disallowed pkg=\"pkg1\" uid=\"243\"/>"
                + "</listener>"
                + "</" + TAG_REQUESTED_LISTENERS + ">";

        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(xml.getBytes())), null);
        parser.nextTag();
        mListeners.readExtraTag(TAG_REQUESTED_LISTENERS, parser);

        validateListenersFromXml();
    }

    @Test
    public void loadDefaultsFromConfig_forHeadlessSystemUser_loadUninstalled() throws Exception {
        // setup with headless system user mode
        mListeners = spy(mNm.new NotificationListeners(
                mContext, new Object(), mock(ManagedServices.UserProfiles.class), miPm,
                /* isHeadlessSystemUserMode= */ true));
        mockDefaultListenerConfigForUninstalledComponent(mUninstalledComponent);

        mListeners.loadDefaultsFromConfig();

        assertThat(mListeners.getDefaultComponents()).contains(mUninstalledComponent);
    }

    @Test
    public void loadDefaultsFromConfig_forNonHeadlessSystemUser_ignoreUninstalled()
            throws Exception {
        // setup without headless system user mode
        mListeners = spy(mNm.new NotificationListeners(
                mContext, new Object(), mock(ManagedServices.UserProfiles.class), miPm,
                /* isHeadlessSystemUserMode= */ false));
        mockDefaultListenerConfigForUninstalledComponent(mUninstalledComponent);

        mListeners.loadDefaultsFromConfig();

        assertThat(mListeners.getDefaultComponents()).doesNotContain(mUninstalledComponent);
    }

    private void mockDefaultListenerConfigForUninstalledComponent(ComponentName componentName) {
        ArraySet<ComponentName> components = new ArraySet<>(Arrays.asList(componentName));
        when(mResources
                .getString(
                        com.android.internal.R.string.config_defaultListenerAccessPackages))
                .thenReturn(componentName.getPackageName());
        when(mContext.getResources()).thenReturn(mResources);
        doReturn(components).when(mListeners).queryPackageForServices(
                eq(componentName.getPackageName()),
                intThat(hasIntBitFlag(MATCH_ANY_USER)),
                anyInt());
    }

    public static ArgumentMatcher<Integer> hasIntBitFlag(int flag) {
        return arg -> arg != null && ((arg & flag) == flag);
    }

    @Test
    public void testWriteExtraTag() throws Exception {
        NotificationListenerFilter nlf = new NotificationListenerFilter(7, new ArraySet<>());
        VersionedPackage a1 = new VersionedPackage("pkg1", 243);
        NotificationListenerFilter nlf2 =
                new NotificationListenerFilter(4, new ArraySet<>(new VersionedPackage[]{a1}));
        mListeners.setNotificationListenerFilter(Pair.create(mCn1, 0), nlf);
        mListeners.setNotificationListenerFilter(Pair.create(mCn2, 10), nlf2);

        TypedXmlSerializer serializer = Xml.newFastSerializer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        serializer.setOutput(new BufferedOutputStream(baos), "utf-8");
        serializer.startDocument(null, true);
        mListeners.writeExtraXmlTags(serializer);
        serializer.endDocument();
        serializer.flush();

        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(baos.toByteArray())), null);
        parser.nextTag();
        mListeners.readExtraTag("req_listeners", parser);

        validateListenersFromXml();
    }

    private void validateListenersFromXml() {
        assertThat(mListeners.getNotificationListenerFilter(Pair.create(mCn1, 0)).getTypes())
                .isEqualTo(7);
        assertThat(mListeners.getNotificationListenerFilter(Pair.create(mCn1, 0))
                .getDisallowedPackages())
                .isEmpty();

        assertThat(mListeners.getNotificationListenerFilter(Pair.create(mCn2, 10)).getTypes())
                .isEqualTo(4);
        VersionedPackage a1 = new VersionedPackage("pkg1", 243);
        assertThat(mListeners.getNotificationListenerFilter(Pair.create(mCn2, 10))
                .getDisallowedPackages())
                .contains(a1);
    }

    @Test
    public void testOnUserRemoved() {
        NotificationListenerFilter nlf = new NotificationListenerFilter(7, new ArraySet<>());
        VersionedPackage a1 = new VersionedPackage("pkg1", 243);
        NotificationListenerFilter nlf2 =
                new NotificationListenerFilter(4, new ArraySet<>(new VersionedPackage[] {a1}));
        mListeners.setNotificationListenerFilter(Pair.create(mCn1, 0), nlf);
        mListeners.setNotificationListenerFilter(Pair.create(mCn2, 10), nlf2);

        mListeners.onUserRemoved(0);

        assertThat(mListeners.getNotificationListenerFilter(Pair.create(mCn1, 0))).isNull();
        assertThat(mListeners.getNotificationListenerFilter(Pair.create(mCn2, 10)).getTypes())
                .isEqualTo(4);
    }

    @Test
    public void testEnsureFilters_newServiceNoMetadata() {
        ServiceInfo si = new ServiceInfo();
        si.packageName = "new2";
        si.name = "comp2";

        mListeners.ensureFilters(si, 0);

        assertThat(mListeners.getNotificationListenerFilter(Pair.create(mCn2, 0))).isNull();
    }

    @Test
    public void testEnsureFilters_preExisting() {
        // one exists already, say from xml
        VersionedPackage a1 = new VersionedPackage("pkg1", 243);
        NotificationListenerFilter nlf =
                new NotificationListenerFilter(4, new ArraySet<>(new VersionedPackage[] {a1}));
        mListeners.setNotificationListenerFilter(Pair.create(mCn2, 0), nlf);
        ServiceInfo siOld = new ServiceInfo();
        siOld.packageName = mCn2.getPackageName();
        siOld.name = mCn2.getClassName();

        mListeners.ensureFilters(siOld, 0);

        assertThat(mListeners.getNotificationListenerFilter(Pair.create(mCn2, 0))).isEqualTo(nlf);
    }

    @Test
    public void testEnsureFilters_newServiceWithMetadata() {
        ServiceInfo si = new ServiceInfo();
        si.packageName = "new";
        si.name = "comp";
        si.metaData = new Bundle();
        si.metaData.putString(NotificationListenerService.META_DATA_DEFAULT_FILTER_TYPES, "1|2");

        mListeners.ensureFilters(si, 0);

        assertThat(mListeners.getNotificationListenerFilter(
                Pair.create(si.getComponentName(), 0)).getTypes())
                .isEqualTo(FLAG_FILTER_TYPE_CONVERSATIONS | FLAG_FILTER_TYPE_ALERTING);
    }

    @Test
    public void testEnsureFilters_newServiceWithMetadata_namesNotNumbers() {
        ServiceInfo si = new ServiceInfo();
        si.packageName = "new";
        si.name = "comp";
        si.metaData = new Bundle();
        si.metaData.putString(NotificationListenerService.META_DATA_DEFAULT_FILTER_TYPES,
                "conversations|ALERTING");

        mListeners.ensureFilters(si, 0);

        assertThat(mListeners.getNotificationListenerFilter(
                Pair.create(si.getComponentName(), 0)).getTypes())
                .isEqualTo(FLAG_FILTER_TYPE_CONVERSATIONS | FLAG_FILTER_TYPE_ALERTING);
    }

    @Test
    public void testEnsureFilters_newServiceWithMetadata_onlyOneListed() {
        ServiceInfo si = new ServiceInfo();
        si.packageName = "new";
        si.name = "comp";
        si.metaData = new Bundle();
        si.metaData.putInt(NotificationListenerService.META_DATA_DEFAULT_FILTER_TYPES, 2);

        mListeners.ensureFilters(si, 0);

        assertThat(mListeners.getNotificationListenerFilter(
                Pair.create(si.getComponentName(), 0)).getTypes())
                .isEqualTo(FLAG_FILTER_TYPE_ALERTING);
    }

    @Test
    public void testEnsureFilters_newServiceWithMetadata_disabledTypes() {
        ServiceInfo si = new ServiceInfo();
        si.packageName = "new";
        si.name = "comp";
        si.metaData = new Bundle();
        si.metaData.putString(NotificationListenerService.META_DATA_DISABLED_FILTER_TYPES, "1|2");

        mListeners.ensureFilters(si, 0);

        assertThat(mListeners.getNotificationListenerFilter(
                Pair.create(si.getComponentName(), 0)).getTypes())
                .isEqualTo(FLAG_FILTER_TYPE_SILENT | FLAG_FILTER_TYPE_ONGOING);
    }

    @Test
    public void testEnsureFilters_newServiceWithMetadata_disabledTypes_mixedText() {
        ServiceInfo si = new ServiceInfo();
        si.packageName = "new";
        si.name = "comp";
        si.metaData = new Bundle();
        si.metaData.putString(NotificationListenerService.META_DATA_DISABLED_FILTER_TYPES,
                "1|alerting");

        mListeners.ensureFilters(si, 0);

        assertThat(mListeners.getNotificationListenerFilter(
                Pair.create(si.getComponentName(), 0)).getTypes())
                .isEqualTo(FLAG_FILTER_TYPE_SILENT | FLAG_FILTER_TYPE_ONGOING);
    }

    @Test
    public void testEnsureFilters_newServiceWithMetadata_metaDataDisagrees() {
        ServiceInfo si = new ServiceInfo();
        si.packageName = "new";
        si.name = "comp";
        si.metaData = new Bundle();
        si.metaData.putString(NotificationListenerService.META_DATA_DEFAULT_FILTER_TYPES, "1|2");
        si.metaData.putInt(NotificationListenerService.META_DATA_DISABLED_FILTER_TYPES, 1);

        mListeners.ensureFilters(si, 0);

        assertThat(mListeners.getNotificationListenerFilter(
                Pair.create(si.getComponentName(), 0)).getTypes())
                .isEqualTo(FLAG_FILTER_TYPE_ALERTING);
    }

    @Test
    public void testEnsureFilters_newServiceWithEmptyMetadata() {
        ServiceInfo si = new ServiceInfo();
        si.packageName = "new";
        si.name = "comp";
        si.metaData = new Bundle();
        si.metaData.putString(NotificationListenerService.META_DATA_DEFAULT_FILTER_TYPES, "");

        mListeners.ensureFilters(si, 0);

        assertThat(mListeners.getNotificationListenerFilter(
                Pair.create(si.getComponentName(), 0)).getTypes())
                .isEqualTo(0);
    }

    @Test
    public void testOnPackageChanged() {
        NotificationListenerFilter nlf = new NotificationListenerFilter(7, new ArraySet<>());
        VersionedPackage a1 = new VersionedPackage("pkg1", 243);
        NotificationListenerFilter nlf2 =
                new NotificationListenerFilter(4, new ArraySet<>(new VersionedPackage[] {a1}));
        mListeners.setNotificationListenerFilter(Pair.create(mCn1, 0), nlf);
        mListeners.setNotificationListenerFilter(Pair.create(mCn2, 10), nlf2);

        String[] pkgs = new String[] {mCn1.getPackageName()};
        int[] uids = new int[] {1};
        mListeners.onPackagesChanged(false, pkgs, uids);

        // not removing; no change
        assertThat(mListeners.getNotificationListenerFilter(Pair.create(mCn1, 0)).getTypes())
                .isEqualTo(7);
        assertThat(mListeners.getNotificationListenerFilter(Pair.create(mCn2, 10)).getTypes())
                .isEqualTo(4);
    }

    @Test
    public void testOnPackageChanged_removing() {
        NotificationListenerFilter nlf = new NotificationListenerFilter(7, new ArraySet<>());
        VersionedPackage a1 = new VersionedPackage("pkg1", 243);
        NotificationListenerFilter nlf2 =
                new NotificationListenerFilter(4, new ArraySet<>(new VersionedPackage[] {a1}));
        mListeners.setNotificationListenerFilter(Pair.create(mCn1, 0), nlf);
        mListeners.setNotificationListenerFilter(Pair.create(mCn2, 0), nlf2);

        String[] pkgs = new String[] {mCn1.getPackageName()};
        int[] uids = new int[] {1};
        mListeners.onPackagesChanged(true, pkgs, uids);

        // only mCn1 removed
        assertThat(mListeners.getNotificationListenerFilter(Pair.create(mCn1, 0))).isNull();
        assertThat(mListeners.getNotificationListenerFilter(Pair.create(mCn2, 0)).getTypes())
                .isEqualTo(4);
    }

    @Test
    public void testOnPackageChanged_removingPackage_removeFromDisallowed() {
        NotificationListenerFilter nlf = new NotificationListenerFilter(7, new ArraySet<>());
        VersionedPackage a1 = new VersionedPackage("pkg1", 243);
        NotificationListenerFilter nlf2 =
                new NotificationListenerFilter(4, new ArraySet<>(new VersionedPackage[] {a1}));
        mListeners.setNotificationListenerFilter(Pair.create(mCn1, 0), nlf);
        mListeners.setNotificationListenerFilter(Pair.create(mCn2, 0), nlf2);

        String[] pkgs = new String[] {"pkg1"};
        int[] uids = new int[] {243};
        mListeners.onPackagesChanged(true, pkgs, uids);

        assertThat(mListeners.getNotificationListenerFilter(Pair.create(mCn1, 0))
                .getDisallowedPackages()).isEmpty();
        assertThat(mListeners.getNotificationListenerFilter(Pair.create(mCn2, 0))
                .getDisallowedPackages()).isEmpty();
    }

    @Test
    public void testOnPackageChanged_notRemovingPackage_staysInDisallowed() {
        NotificationListenerFilter nlf = new NotificationListenerFilter(7, new ArraySet<>());
        VersionedPackage a1 = new VersionedPackage("pkg1", 243);
        NotificationListenerFilter nlf2 =
                new NotificationListenerFilter(4, new ArraySet<>(new VersionedPackage[] {a1}));
        mListeners.setNotificationListenerFilter(Pair.create(mCn1, 0), nlf);
        mListeners.setNotificationListenerFilter(Pair.create(mCn2, 0), nlf2);

        String[] pkgs = new String[] {"pkg1"};
        int[] uids = new int[] {243};
        mListeners.onPackagesChanged(false, pkgs, uids);

        assertThat(mListeners.getNotificationListenerFilter(Pair.create(mCn2, 0))
                .getDisallowedPackages()).contains(a1);
    }

    @Test
    public void testHasAllowedListener() {
        final int uid1 = 1, uid2 = 2;
        // enable mCn1 but not mCn2 for uid1
        mListeners.addApprovedList(mCn1.flattenToString(), uid1, true);

        // verify that:
        // the package for mCn1 has an allowed listener for uid1 and not uid2
        assertTrue(mListeners.hasAllowedListener(mCn1.getPackageName(), uid1));
        assertFalse(mListeners.hasAllowedListener(mCn1.getPackageName(), uid2));

        // and that mCn2 has no allowed listeners for either user id
        assertFalse(mListeners.hasAllowedListener(mCn2.getPackageName(), uid1));
        assertFalse(mListeners.hasAllowedListener(mCn2.getPackageName(), uid2));
    }

    @Test
    public void testBroadcastUsers() {
        int userId = 0;
        mListeners.setPackageOrComponentEnabled(mCn1.flattenToString(), userId, true, false, true);

        verify(mContext).sendBroadcastAsUser(
                any(), eq(UserHandle.of(userId)), nullable(String.class));
    }

    @Test
    public void testNotifyPostedLockedInLockdownMode() {
        NotificationRecord r0 = mock(NotificationRecord.class);
        NotificationRecord old0 = mock(NotificationRecord.class);
        UserHandle uh0 = mock(UserHandle.class);

        NotificationRecord r1 = mock(NotificationRecord.class);
        NotificationRecord old1 = mock(NotificationRecord.class);
        UserHandle uh1 = mock(UserHandle.class);

        // Neither user0 and user1 is in the lockdown mode
        when(r0.getUser()).thenReturn(uh0);
        when(uh0.getIdentifier()).thenReturn(0);
        when(mNm.isInLockDownMode(0)).thenReturn(false);

        when(r1.getUser()).thenReturn(uh1);
        when(uh1.getIdentifier()).thenReturn(1);
        when(mNm.isInLockDownMode(1)).thenReturn(false);

        mListeners.notifyPostedLocked(r0, old0, true);
        mListeners.notifyPostedLocked(r0, old0, false);
        verify(r0, atLeast(2)).getSbn();

        mListeners.notifyPostedLocked(r1, old1, true);
        mListeners.notifyPostedLocked(r1, old1, false);
        verify(r1, atLeast(2)).getSbn();

        // Reset
        reset(r0);
        reset(old0);
        reset(r1);
        reset(old1);

        // Only user 0 is in the lockdown mode
        when(r0.getUser()).thenReturn(uh0);
        when(uh0.getIdentifier()).thenReturn(0);
        when(mNm.isInLockDownMode(0)).thenReturn(true);

        when(r1.getUser()).thenReturn(uh1);
        when(uh1.getIdentifier()).thenReturn(1);
        when(mNm.isInLockDownMode(1)).thenReturn(false);

        mListeners.notifyPostedLocked(r0, old0, true);
        mListeners.notifyPostedLocked(r0, old0, false);
        verify(r0, never()).getSbn();

        mListeners.notifyPostedLocked(r1, old1, true);
        mListeners.notifyPostedLocked(r1, old1, false);
        verify(r1, atLeast(2)).getSbn();
    }

    @Test
    public void testNotifyRemovedLockedInLockdownMode() throws NoSuchFieldException {
        NotificationRecord r0 = mock(NotificationRecord.class);
        NotificationStats rs0 = mock(NotificationStats.class);
        UserHandle uh0 = mock(UserHandle.class);

        NotificationRecord r1 = mock(NotificationRecord.class);
        NotificationStats rs1 = mock(NotificationStats.class);
        UserHandle uh1 = mock(UserHandle.class);

        StatusBarNotification sbn = mock(StatusBarNotification.class);
        FieldSetter.setField(mNm,
                NotificationManagerService.class.getDeclaredField("mHandler"),
                mock(NotificationManagerService.WorkerHandler.class));

        // Neither user0 and user1 is in the lockdown mode
        when(r0.getUser()).thenReturn(uh0);
        when(uh0.getIdentifier()).thenReturn(0);
        when(mNm.isInLockDownMode(0)).thenReturn(false);
        when(r0.getSbn()).thenReturn(sbn);

        when(r1.getUser()).thenReturn(uh1);
        when(uh1.getIdentifier()).thenReturn(1);
        when(mNm.isInLockDownMode(1)).thenReturn(false);
        when(r1.getSbn()).thenReturn(sbn);

        mListeners.notifyRemovedLocked(r0, 0, rs0);
        mListeners.notifyRemovedLocked(r0, 0, rs0);
        verify(r0, atLeast(2)).getSbn();

        mListeners.notifyRemovedLocked(r1, 0, rs1);
        mListeners.notifyRemovedLocked(r1, 0, rs1);
        verify(r1, atLeast(2)).getSbn();

        // Reset
        reset(r0);
        reset(rs0);
        reset(r1);
        reset(rs1);

        // Only user 0 is in the lockdown mode
        when(r0.getUser()).thenReturn(uh0);
        when(uh0.getIdentifier()).thenReturn(0);
        when(mNm.isInLockDownMode(0)).thenReturn(true);
        when(r0.getSbn()).thenReturn(sbn);

        when(r1.getUser()).thenReturn(uh1);
        when(uh1.getIdentifier()).thenReturn(1);
        when(mNm.isInLockDownMode(1)).thenReturn(false);
        when(r1.getSbn()).thenReturn(sbn);

        mListeners.notifyRemovedLocked(r0, 0, rs0);
        mListeners.notifyRemovedLocked(r0, 0, rs0);
        verify(r0, never()).getSbn();

        mListeners.notifyRemovedLocked(r1, 0, rs1);
        mListeners.notifyRemovedLocked(r1, 0, rs1);
        verify(r1, atLeast(2)).getSbn();
    }

    @Test
    public void testImplicitGrant() {
        String pkg = "pkg";
        int uid = 9;
        NotificationChannel channel = new NotificationChannel("id", "name",
                NotificationManager.IMPORTANCE_HIGH);
        Notification.Builder nb = new Notification.Builder(mContext, channel.getId())
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setTimeoutAfter(1);

        StatusBarNotification sbn = new StatusBarNotification(pkg, pkg, 8, "tag", uid, 0,
                nb.build(), UserHandle.getUserHandleForUid(uid), null, 0);
        NotificationRecord r = new NotificationRecord(mContext, sbn, channel);

        ManagedServices.ManagedServiceInfo info = mListeners.new ManagedServiceInfo(
                null, new ComponentName("a", "a"), sbn.getUserId(), false, null, 33, 33);
        List<ManagedServices.ManagedServiceInfo> services = ImmutableList.of(info);
        when(mListeners.getServices()).thenReturn(services);

        when(mNm.isVisibleToListener(any(), anyInt(), any())).thenReturn(true);
        when(mNm.makeRankingUpdateLocked(info)).thenReturn(mock(NotificationRankingUpdate.class));
        mNm.mPackageManagerInternal = mPmi;

        mListeners.notifyPostedLocked(r, null);

        verify(mPmi).grantImplicitAccess(sbn.getUserId(), null, UserHandle.getAppId(33),
                sbn.getUid(), false, false);
    }

    @Test
    public void testUpdateGroup_notifyTwoListeners() throws Exception {
        final NotificationChannelGroup updated = new NotificationChannelGroup("id", "name");
        updated.setChannels(ImmutableList.of(
                new NotificationChannel("a", "a", 1), new NotificationChannel("b", "b", 2)));
        updated.setBlocked(true);

        ManagedServices.ManagedServiceInfo i1 = getParcelingListener(updated);
        ManagedServices.ManagedServiceInfo i2= getParcelingListener(updated);
        when(mListeners.getServices()).thenReturn(ImmutableList.of(i1, i2));
        NotificationChannelGroup existing = new NotificationChannelGroup("id", "name");

        mListeners.notifyNotificationChannelGroupChanged("pkg", UserHandle.of(0), updated, 0);
        Thread.sleep(500);

        verify(((INotificationListener) i1.getService()), times(1))
                .onNotificationChannelGroupModification(anyString(), any(), any(), anyInt());
    }

    @Test
    public void testNotificationListenerFilter_threadSafety() throws Exception {
        testThreadSafety(() -> {
            mListeners.setNotificationListenerFilter(
                    new Pair<>(new ComponentName("pkg1", "cls1"), 0),
                    new NotificationListenerFilter());
            mListeners.setNotificationListenerFilter(
                    new Pair<>(new ComponentName("pkg2", "cls2"), 10),
                    new NotificationListenerFilter());
            mListeners.setNotificationListenerFilter(
                    new Pair<>(new ComponentName("pkg3", "cls3"), 11),
                    new NotificationListenerFilter());

            mListeners.onUserRemoved(10);
            mListeners.onPackagesChanged(true, new String[]{"pkg1", "pkg2"}, new int[]{0, 0});
        }, 20, 50);
    }

    /**
     * Helper method to test the thread safety of some operations.
     *
     * <p>Runs the supplied {@code operationToTest}, {@code nRunsPerThread} times,
     * concurrently using {@code nThreads} threads, and waits for all of them to finish.
     */
    private static void testThreadSafety(Runnable operationToTest, int nThreads,
            int nRunsPerThread) throws InterruptedException {
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(nThreads);

        for (int i = 0; i < nThreads; i++) {
            Runnable threadRunnable = () -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < nRunsPerThread; j++) {
                        operationToTest.run();
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            };
            new Thread(threadRunnable, "Test Thread #" + i).start();
        }

        // Ready set go
        startLatch.countDown();

        // Wait for all test threads to be done.
        doneLatch.await();
    }

    private ManagedServices.ManagedServiceInfo getParcelingListener(
            final NotificationChannelGroup toParcel)
            throws RemoteException {
        ManagedServices.ManagedServiceInfo i1 = mock(ManagedServices.ManagedServiceInfo.class);
        when(i1.isSystem()).thenReturn(true);
        INotificationListener l1 = mock(INotificationListener.class);
        when(i1.enabledAndUserMatches(anyInt())).thenReturn(true);
        doAnswer(invocationOnMock -> {
            try {
                toParcel.writeToParcel(Parcel.obtain(), 0);
            } catch (Exception e) {
                fail("Failed to parcel group to listener");
                return e;

            }
            return null;
        }).when(l1).onNotificationChannelGroupModification(anyString(), any(), any(), anyInt());
        when(i1.getService()).thenReturn(l1);
        return i1;
    }
}
