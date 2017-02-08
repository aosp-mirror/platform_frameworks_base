/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.settingslib.wifi;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiSsid;
import android.os.Bundle;
import android.os.SystemClock;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.text.SpannableString;
import android.text.style.TtsSpan;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collections;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class AccessPointTest {

    private static final String TEST_SSID = "test_ssid";
    private Context mContext;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getTargetContext();
    }

    @Test
    public void testSsidIsTelephoneSpan() {
        final Bundle bundle = new Bundle();
        bundle.putString("key_ssid", TEST_SSID);
        final AccessPoint ap = new AccessPoint(InstrumentationRegistry.getTargetContext(), bundle);
        final CharSequence ssid = ap.getSsid();

        assertThat(ssid instanceof SpannableString).isTrue();

        TtsSpan[] spans = ((SpannableString) ssid).getSpans(0, TEST_SSID.length(), TtsSpan.class);

        assertThat(spans.length).isEqualTo(1);
        assertThat(spans[0].getType()).isEqualTo(TtsSpan.TYPE_TELEPHONE);
    }

    @Test
    public void testCopyAccessPoint_dataShouldMatch() {
        WifiConfiguration configuration = createWifiConfiguration();

        NetworkInfo networkInfo =
                new NetworkInfo(ConnectivityManager.TYPE_WIFI, 2, "WIFI", "WIFI_SUBTYPE");
        AccessPoint originalAccessPoint = new AccessPoint(mContext, configuration);
        WifiInfo wifiInfo = new WifiInfo();
        wifiInfo.setSSID(WifiSsid.createFromAsciiEncoded(configuration.SSID));
        wifiInfo.setBSSID(configuration.BSSID);
        originalAccessPoint.update(configuration, wifiInfo, networkInfo);
        AccessPoint copy = new AccessPoint(mContext, originalAccessPoint);

        assertThat(originalAccessPoint.getSsid().toString()).isEqualTo(copy.getSsid().toString());
        assertThat(originalAccessPoint.getBssid()).isEqualTo(copy.getBssid());
        assertThat(originalAccessPoint.getConfig()).isEqualTo(copy.getConfig());
        assertThat(originalAccessPoint.getSecurity()).isEqualTo(copy.getSecurity());
        assertThat(originalAccessPoint.compareTo(copy) == 0).isTrue();
    }

    @Test
    public void testThatCopyAccessPoint_scanCacheShouldMatch() {
        Bundle bundle = new Bundle();
        ArrayList<ScanResult> scanResults = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ScanResult scanResult = new ScanResult();
            scanResult.level = i;
            scanResult.BSSID = "bssid-" + i;
            scanResult.timestamp = SystemClock.elapsedRealtime() * 1000;
            scanResults.add(scanResult);
        }

        bundle.putParcelableArrayList("key_scanresultcache", scanResults);
        AccessPoint original = new AccessPoint(mContext, bundle);
        assertThat(original.getRssi()).isEqualTo(4);
        AccessPoint copy = new AccessPoint(mContext, createWifiConfiguration());
        assertThat(copy.getRssi()).isEqualTo(Integer.MIN_VALUE);
        copy.copyFrom(original);
        assertThat(original.getRssi()).isEqualTo(copy.getRssi());
    }

    @Test
    public void testCompareTo_GivesActiveBeforeInactive() {
        AccessPoint activeAp = new TestAccessPointBuilder(mContext).setActive(true).build();
        AccessPoint inactiveAp = new TestAccessPointBuilder(mContext).setActive(false).build();

        assertSortingWorks(activeAp, inactiveAp);
    }

    @Test
    public void testCompareTo_GivesReachableBeforeUnreachable() {
        AccessPoint nearAp = new TestAccessPointBuilder(mContext).setReachable(true).build();
        AccessPoint farAp = new TestAccessPointBuilder(mContext).setReachable(false).build();

        assertSortingWorks(nearAp, farAp);
    }

    @Test
    public void testCompareTo_GivesSavedBeforeUnsaved() {
        AccessPoint savedAp = new TestAccessPointBuilder(mContext).setSaved(true).build();
        AccessPoint notSavedAp = new TestAccessPointBuilder(mContext).setSaved(false).build();

        assertSortingWorks(savedAp, notSavedAp);
    }

    //TODO: add tests for mRankingScore sort order if ranking is exposed

    @Test
    public void testCompareTo_GivesHighLevelBeforeLowLevel() {
        final int highLevel = AccessPoint.SIGNAL_LEVELS - 1;
        final int lowLevel = 1;
        assertThat(highLevel).isGreaterThan(lowLevel);

        AccessPoint strongAp = new TestAccessPointBuilder(mContext).setLevel(highLevel).build();
        AccessPoint weakAp = new TestAccessPointBuilder(mContext).setLevel(lowLevel).build();

        assertSortingWorks(strongAp, weakAp);
    }

    @Test
    public void testCompareTo_GivesSsidAlphabetically() {

        final String firstName = "AAAAAA";
        final String secondName = "zzzzzz";

        AccessPoint firstAp = new TestAccessPointBuilder(mContext).setSsid(firstName).build();
        AccessPoint secondAp = new TestAccessPointBuilder(mContext).setSsid(secondName).build();

        assertThat(firstAp.getSsidStr().compareToIgnoreCase(secondAp.getSsidStr()) < 0).isTrue();
        assertSortingWorks(firstAp, secondAp);
    }

    @Test
    public void testCompareTo_AllSortingRulesCombined() {

        AccessPoint active = new TestAccessPointBuilder(mContext).setActive(true).build();
        AccessPoint reachableAndMinLevel = new TestAccessPointBuilder(mContext)
                .setReachable(true).build();
        AccessPoint saved = new TestAccessPointBuilder(mContext).setSaved(true).build();
        AccessPoint highLevelAndReachable = new TestAccessPointBuilder(mContext)
                .setLevel(AccessPoint.SIGNAL_LEVELS - 1).build();
        AccessPoint firstName = new TestAccessPointBuilder(mContext).setSsid("a").build();
        AccessPoint lastname = new TestAccessPointBuilder(mContext).setSsid("z").build();

        ArrayList<AccessPoint> points = new ArrayList<AccessPoint>();
        points.add(lastname);
        points.add(firstName);
        points.add(highLevelAndReachable);
        points.add(saved);
        points.add(reachableAndMinLevel);
        points.add(active);

        Collections.sort(points);
        assertThat(points.indexOf(active)).isLessThan(points.indexOf(reachableAndMinLevel));
        assertThat(points.indexOf(reachableAndMinLevel)).isLessThan(points.indexOf(saved));
        // note: the saved AP will not appear before highLevelAndReachable,
        // because all APs with a signal level are reachable,
        // and isReachable() takes higher sorting precedence than isSaved().
        assertThat(points.indexOf(saved)).isLessThan(points.indexOf(firstName));
        assertThat(points.indexOf(highLevelAndReachable)).isLessThan(points.indexOf(firstName));
        assertThat(points.indexOf(firstName)).isLessThan(points.indexOf(lastname));
    }

    private WifiConfiguration createWifiConfiguration() {
        WifiConfiguration configuration = new WifiConfiguration();
        configuration.BSSID = "bssid";
        configuration.SSID = "ssid";
        configuration.networkId = 123;
        return configuration;
    }

    /**
    * Assert that the first AccessPoint appears after the second AccessPoint
    * once sorting has been completed.
    */
    private void assertSortingWorks(AccessPoint first, AccessPoint second) {

        ArrayList<AccessPoint> points = new ArrayList<AccessPoint>();

        // add in reverse order so we can tell that sorting actually changed something
        points.add(second);
        points.add(first);
        Collections.sort(points);
        assertWithMessage(
                String.format("After sorting: second AccessPoint should have higher array index "
                    + "than the first, but found indicies second '%s' and first '%s'.",
                    points.indexOf(second), points.indexOf(first)))
            .that(points.indexOf(second)).isGreaterThan(points.indexOf(first));
    }

    @Test
    public void testBuilder_setActive() {
        AccessPoint activeAp = new TestAccessPointBuilder(mContext).setActive(true).build();
        assertThat(activeAp.isActive()).isTrue();

        AccessPoint inactiveAp = new TestAccessPointBuilder(mContext).setActive(false).build();
        assertThat(inactiveAp.isActive()).isFalse();
    }

    @Test
    public void testBuilder_setReachable() {
        AccessPoint nearAp = new TestAccessPointBuilder(mContext).setReachable(true).build();
        assertThat(nearAp.isReachable()).isTrue();

        AccessPoint farAp = new TestAccessPointBuilder(mContext).setReachable(false).build();
        assertThat(farAp.isReachable()).isFalse();
    }

    @Test
    public void testBuilder_setSaved() {
        AccessPoint savedAp = new TestAccessPointBuilder(mContext).setSaved(true).build();
        assertThat(savedAp.isSaved()).isTrue();

        AccessPoint newAp = new TestAccessPointBuilder(mContext).setSaved(false).build();
        assertThat(newAp.isSaved()).isFalse();
    }

    @Test
    public void testBuilder_setLevel() {
        AccessPoint testAp;

        for (int i = 0; i < AccessPoint.SIGNAL_LEVELS; i++) {
            testAp = new TestAccessPointBuilder(mContext).setLevel(i).build();
            assertThat(testAp.getLevel()).isEqualTo(i);
        }

        // numbers larger than the max level should be set to max
        testAp = new TestAccessPointBuilder(mContext).setLevel(AccessPoint.SIGNAL_LEVELS).build();
        assertThat(testAp.getLevel()).isEqualTo(AccessPoint.SIGNAL_LEVELS - 1);

        // numbers less than 0 should give level 0
        testAp = new TestAccessPointBuilder(mContext).setLevel(-100).build();
        assertThat(testAp.getLevel()).isEqualTo(0);
    }

    @Test
    public void testBuilder_settingReachableAfterLevelDoesNotAffectLevel() {
        int level = 1;
        assertThat(level).isLessThan(AccessPoint.SIGNAL_LEVELS - 1);

        AccessPoint testAp =
                new TestAccessPointBuilder(mContext).setLevel(level).setReachable(true).build();
        assertThat(testAp.getLevel()).isEqualTo(level);
    }

    @Test
    public void testBuilder_setSsid() {
        String name = "AmazingSsid!";
        AccessPoint namedAp = new TestAccessPointBuilder(mContext).setSsid(name).build();
        assertThat(namedAp.getSsidStr()).isEqualTo(name);
    }
}
