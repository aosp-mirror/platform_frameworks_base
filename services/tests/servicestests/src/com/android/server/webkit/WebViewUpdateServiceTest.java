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
 * limitations under the License.
 */

package com.android.server.webkit;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.os.Bundle;
import android.util.Base64;
import android.test.AndroidTestCase;

import android.webkit.WebViewFactory;
import android.webkit.WebViewProviderInfo;
import android.webkit.WebViewProviderResponse;

import java.util.concurrent.CountDownLatch;

import org.hamcrest.Description;

import org.mockito.Mockito;
import org.mockito.Matchers;
import org.mockito.ArgumentMatcher;


/**
 * Tests for WebViewUpdateService
 */
public class WebViewUpdateServiceTest extends AndroidTestCase {
    private final static String TAG = WebViewUpdateServiceTest.class.getSimpleName();

    private WebViewUpdateServiceImpl mWebViewUpdateServiceImpl;
    private TestSystemImpl mTestSystemImpl;

    private static final String WEBVIEW_LIBRARY_FLAG = "com.android.webview.WebViewLibrary";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
    }

    /**
     * Creates a new instance.
     */
    public WebViewUpdateServiceTest() {
    }

    private void setupWithPackages(WebViewProviderInfo[] packages) {
        setupWithPackages(packages, true);
    }

    private void setupWithPackages(WebViewProviderInfo[] packages,
            boolean fallbackLogicEnabled) {
        setupWithPackages(packages, fallbackLogicEnabled, 1);
    }

    private void setupWithPackages(WebViewProviderInfo[] packages,
            boolean fallbackLogicEnabled, int numRelros) {
        setupWithPackages(packages, fallbackLogicEnabled, numRelros,
                true /* isDebuggable == true -> don't check package signatures */);
    }

    private void setupWithPackages(WebViewProviderInfo[] packages,
            boolean fallbackLogicEnabled, int numRelros, boolean isDebuggable) {
        TestSystemImpl testing = new TestSystemImpl(packages, fallbackLogicEnabled, numRelros,
                isDebuggable);
        mTestSystemImpl = Mockito.spy(testing);
        mWebViewUpdateServiceImpl =
            new WebViewUpdateServiceImpl(null /*Context*/, mTestSystemImpl);
    }

    private void setEnabledAndValidPackageInfos(WebViewProviderInfo[] providers) {
        for(WebViewProviderInfo wpi : providers) {
            mTestSystemImpl.setPackageInfo(createPackageInfo(wpi.packageName, true /* enabled */,
                        true /* valid */, true /* installed */));
        }
    }

    private void checkCertainPackageUsedAfterWebViewBootPreparation(String expectedProviderName,
            WebViewProviderInfo[] webviewPackages) {
        checkCertainPackageUsedAfterWebViewBootPreparation(
                expectedProviderName, webviewPackages, 1);
    }

    private void checkCertainPackageUsedAfterWebViewBootPreparation(String expectedProviderName,
            WebViewProviderInfo[] webviewPackages, int numRelros) {
        setupWithPackages(webviewPackages, true, numRelros);
        // Add (enabled and valid) package infos for each provider
        setEnabledAndValidPackageInfos(webviewPackages);

        mWebViewUpdateServiceImpl.prepareWebViewInSystemServer();

        Mockito.verify(mTestSystemImpl).onWebViewProviderChanged(
                Mockito.argThat(new IsPackageInfoWithName(expectedProviderName)));

        for (int n = 0; n < numRelros; n++) {
            mWebViewUpdateServiceImpl.notifyRelroCreationCompleted();
        }

        WebViewProviderResponse response = mWebViewUpdateServiceImpl.waitForAndGetProvider();
        assertEquals(WebViewFactory.LIBLOAD_SUCCESS, response.status);
        assertEquals(expectedProviderName, response.packageInfo.packageName);
    }

    // For matching the package name of a PackageInfo
    private class IsPackageInfoWithName extends ArgumentMatcher<PackageInfo> {
        private final String mPackageName;

        IsPackageInfoWithName(String name) {
            mPackageName = name;
        }

        @Override
        public boolean matches(Object p) {
            return ((PackageInfo) p).packageName.equals(mPackageName);
        }

        // Provide a more useful description in case of mismatch
        @Override
        public void describeTo (Description description) {
            description.appendText(String.format("PackageInfo with name '%s'", mPackageName));
        }
    }

    private static PackageInfo createPackageInfo(
            String packageName, boolean enabled, boolean valid, boolean installed) {
        PackageInfo p = new PackageInfo();
        p.packageName = packageName;
        p.applicationInfo = new ApplicationInfo();
        p.applicationInfo.enabled = enabled;
        p.applicationInfo.metaData = new Bundle();
        if (installed) {
            p.applicationInfo.flags |= ApplicationInfo.FLAG_INSTALLED;
        } else {
            p.applicationInfo.flags &= ~ApplicationInfo.FLAG_INSTALLED;
        }
        if (valid) {
            // no flag means invalid
            p.applicationInfo.metaData.putString(WEBVIEW_LIBRARY_FLAG, "blah");
        }
        return p;
    }

    private static PackageInfo createPackageInfo(String packageName, boolean enabled, boolean valid,
            boolean installed, Signature[] signatures, long updateTime) {
        PackageInfo p = createPackageInfo(packageName, enabled, valid, installed);
        p.signatures = signatures;
        p.lastUpdateTime = updateTime;
        return p;
    }

    private static PackageInfo createPackageInfo(String packageName, boolean enabled, boolean valid,
            boolean installed, Signature[] signatures, long updateTime, boolean hidden) {
        PackageInfo p =
            createPackageInfo(packageName, enabled, valid, installed, signatures, updateTime);
        if (hidden) {
            p.applicationInfo.privateFlags |= ApplicationInfo.PRIVATE_FLAG_HIDDEN;
        } else {
            p.applicationInfo.privateFlags &= ~ApplicationInfo.PRIVATE_FLAG_HIDDEN;
        }
        return p;
    }

    private void checkPreparationPhasesForPackage(String expectedPackage, int numPreparation) {
        // Verify that onWebViewProviderChanged was called for the numPreparation'th time for the
        // expected package
        Mockito.verify(mTestSystemImpl, Mockito.times(numPreparation)).onWebViewProviderChanged(
                Mockito.argThat(new IsPackageInfoWithName(expectedPackage)));

        mWebViewUpdateServiceImpl.notifyRelroCreationCompleted();

        WebViewProviderResponse response = mWebViewUpdateServiceImpl.waitForAndGetProvider();
        assertEquals(WebViewFactory.LIBLOAD_SUCCESS, response.status);
        assertEquals(expectedPackage, response.packageInfo.packageName);
    }


    // ****************
    // Tests
    // ****************


    public void testWithSinglePackage() {
        String testPackageName = "test.package.name";
        checkCertainPackageUsedAfterWebViewBootPreparation(testPackageName,
                new WebViewProviderInfo[] {
                    new WebViewProviderInfo(testPackageName, "",
                            true /*default available*/, false /* fallback */, null)});
    }

    public void testDefaultPackageUsedOverNonDefault() {
        String defaultPackage = "defaultPackage";
        String nonDefaultPackage = "nonDefaultPackage";
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(nonDefaultPackage, "", false, false, null),
            new WebViewProviderInfo(defaultPackage, "", true, false, null)};
        checkCertainPackageUsedAfterWebViewBootPreparation(defaultPackage, packages);
    }

    public void testSeveralRelros() {
        String singlePackage = "singlePackage";
        checkCertainPackageUsedAfterWebViewBootPreparation(
                singlePackage,
                new WebViewProviderInfo[] {
                    new WebViewProviderInfo(singlePackage, "", true /*def av*/, false, null)},
                2);
    }

    // Ensure that package with valid signatures is chosen rather than package with invalid
    // signatures.
    public void testWithSignatures() {
        String validPackage = "valid package";
        String invalidPackage = "invalid package";

        Signature validSignature = new Signature("11");
        Signature invalidExpectedSignature = new Signature("22");
        Signature invalidPackageSignature = new Signature("33");

        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(invalidPackage, "", true, false, new String[]{
                        Base64.encodeToString(
                                invalidExpectedSignature.toByteArray(), Base64.DEFAULT)}),
            new WebViewProviderInfo(validPackage, "", true, false, new String[]{
                        Base64.encodeToString(
                                validSignature.toByteArray(), Base64.DEFAULT)})
        };
        setupWithPackages(packages, true /* fallback logic enabled */, 1 /* numRelros */,
                false /* isDebuggable */);
        mTestSystemImpl.setPackageInfo(createPackageInfo(invalidPackage, true /* enabled */,
                    true /* valid */, true /* installed */, new Signature[]{invalidPackageSignature}
                    , 0 /* updateTime */));
        mTestSystemImpl.setPackageInfo(createPackageInfo(validPackage, true /* enabled */,
                    true /* valid */, true /* installed */, new Signature[]{validSignature}
                    , 0 /* updateTime */));

        mWebViewUpdateServiceImpl.prepareWebViewInSystemServer();


        checkPreparationPhasesForPackage(validPackage, 1 /* first preparation for this package */);

        WebViewProviderInfo[] validPackages = mWebViewUpdateServiceImpl.getValidWebViewPackages();
        assertEquals(1, validPackages.length);
        assertEquals(validPackage, validPackages[0].packageName);
    }

    public void testFailWaitingForRelro() {
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo("packagename", "", true, true, null)};
        setupWithPackages(packages);
        setEnabledAndValidPackageInfos(packages);

        mWebViewUpdateServiceImpl.prepareWebViewInSystemServer();

        Mockito.verify(mTestSystemImpl).onWebViewProviderChanged(
                Mockito.argThat(new IsPackageInfoWithName(packages[0].packageName)));

        // Never call notifyRelroCreation()

        WebViewProviderResponse response = mWebViewUpdateServiceImpl.waitForAndGetProvider();
        assertEquals(WebViewFactory.LIBLOAD_FAILED_WAITING_FOR_RELRO, response.status);
    }

    public void testFailListingEmptyWebviewPackages() {
        WebViewProviderInfo[] packages = new WebViewProviderInfo[0];
        setupWithPackages(packages);
        setEnabledAndValidPackageInfos(packages);

        mWebViewUpdateServiceImpl.prepareWebViewInSystemServer();

        Mockito.verify(mTestSystemImpl, Mockito.never()).onWebViewProviderChanged(
                Matchers.anyObject());

        WebViewProviderResponse response = mWebViewUpdateServiceImpl.waitForAndGetProvider();
        assertEquals(WebViewFactory.LIBLOAD_FAILED_LISTING_WEBVIEW_PACKAGES, response.status);
    }

    public void testFailListingInvalidWebviewPackage() {
        WebViewProviderInfo wpi = new WebViewProviderInfo("package", "", true, true, null);
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {wpi};
        setupWithPackages(packages);
        mTestSystemImpl.setPackageInfo(
                createPackageInfo(wpi.packageName, true /* enabled */, false /* valid */,
                    true /* installed */));

        mWebViewUpdateServiceImpl.prepareWebViewInSystemServer();

        Mockito.verify(mTestSystemImpl, Mockito.never()).onWebViewProviderChanged(
                Matchers.anyObject());

        WebViewProviderResponse response = mWebViewUpdateServiceImpl.waitForAndGetProvider();
        assertEquals(WebViewFactory.LIBLOAD_FAILED_LISTING_WEBVIEW_PACKAGES, response.status);

        // Verify that we can recover from failing to list webview packages.
        mTestSystemImpl.setPackageInfo(
                createPackageInfo(wpi.packageName, true /* enabled */, true /* valid */,
                    true /* installed */));
        mWebViewUpdateServiceImpl.packageStateChanged(wpi.packageName,
                WebViewUpdateService.PACKAGE_ADDED_REPLACED, 0);

        checkPreparationPhasesForPackage(wpi.packageName, 1);
    }

    // Test that switching provider using changeProviderAndSetting works.
    public void testSwitchingProvider() {
        String firstPackage = "first";
        String secondPackage = "second";
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(firstPackage, "", true, false, null),
            new WebViewProviderInfo(secondPackage, "", true, false, null)};
        checkSwitchingProvider(packages, firstPackage, secondPackage);
    }

    public void testSwitchingProviderToNonDefault() {
        String defaultPackage = "defaultPackage";
        String nonDefaultPackage = "nonDefaultPackage";
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(defaultPackage, "", true, false, null),
            new WebViewProviderInfo(nonDefaultPackage, "", false, false, null)};
        checkSwitchingProvider(packages, defaultPackage, nonDefaultPackage);
    }

    private void checkSwitchingProvider(WebViewProviderInfo[] packages, String initialPackage,
            String finalPackage) {
        checkCertainPackageUsedAfterWebViewBootPreparation(initialPackage, packages);

        mWebViewUpdateServiceImpl.changeProviderAndSetting(finalPackage);
        checkPreparationPhasesForPackage(finalPackage, 1 /* first preparation for this package */);

        Mockito.verify(mTestSystemImpl).killPackageDependents(Mockito.eq(initialPackage));
    }

    // Change provider during relro creation by using changeProviderAndSetting
    public void testSwitchingProviderDuringRelroCreation() {
        checkChangingProviderDuringRelroCreation(true);
    }

    // Change provider during relro creation by enabling a provider
    public void testChangingProviderThroughEnablingDuringRelroCreation() {
        checkChangingProviderDuringRelroCreation(false);
    }

    private void checkChangingProviderDuringRelroCreation(boolean settingsChange) {
        String firstPackage = "first";
        String secondPackage = "second";
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(firstPackage, "", true, false, null),
            new WebViewProviderInfo(secondPackage, "", true, false, null)};
        setupWithPackages(packages);
        if (settingsChange) {
            // Have all packages be enabled, so that we can change provider however we want to
            setEnabledAndValidPackageInfos(packages);
        } else {
            // Have all packages be disabled so that we can change one to enabled later
            for(WebViewProviderInfo wpi : packages) {
                mTestSystemImpl.setPackageInfo(createPackageInfo(wpi.packageName,
                            false /* enabled */, true /* valid */, true /* installed */));
            }
        }

        CountDownLatch countdown = new CountDownLatch(1);

        mWebViewUpdateServiceImpl.prepareWebViewInSystemServer();

        Mockito.verify(mTestSystemImpl).onWebViewProviderChanged(
                Mockito.argThat(new IsPackageInfoWithName(firstPackage)));

        assertEquals(firstPackage, mWebViewUpdateServiceImpl.getCurrentWebViewPackageName());

        new Thread(new Runnable() {
            @Override
            public void run() {
                WebViewProviderResponse threadResponse =
                    mWebViewUpdateServiceImpl.waitForAndGetProvider();
                assertEquals(WebViewFactory.LIBLOAD_SUCCESS, threadResponse.status);
                assertEquals(secondPackage, threadResponse.packageInfo.packageName);
                // Verify that we killed the first package
                Mockito.verify(mTestSystemImpl).killPackageDependents(Mockito.eq(firstPackage));
                countdown.countDown();
            }
        }).start();
        try {
            Thread.sleep(500); // Let the new thread run / be blocked
        } catch (InterruptedException e) {
        }

        if (settingsChange) {
            mWebViewUpdateServiceImpl.changeProviderAndSetting(secondPackage);
        } else {
            // Switch provider by enabling the second one
            mTestSystemImpl.setPackageInfo(createPackageInfo(secondPackage, true /* enabled */,
                        true /* valid */, true /* installed */));
            mWebViewUpdateServiceImpl.packageStateChanged(
                    secondPackage, WebViewUpdateService.PACKAGE_CHANGED, 0);
        }
        mWebViewUpdateServiceImpl.notifyRelroCreationCompleted();
        // first package done, should start on second

        Mockito.verify(mTestSystemImpl).onWebViewProviderChanged(
                Mockito.argThat(new IsPackageInfoWithName(secondPackage)));

        mWebViewUpdateServiceImpl.notifyRelroCreationCompleted();
        // second package done, the other thread should now be unblocked
        try {
            countdown.await();
        } catch (InterruptedException e) {
        }
    }

    public void testRunFallbackLogicIfEnabled() {
        checkFallbackLogicBeingRun(true);
    }

    public void testDontRunFallbackLogicIfDisabled() {
        checkFallbackLogicBeingRun(false);
    }

    private void checkFallbackLogicBeingRun(boolean fallbackLogicEnabled) {
        String primaryPackage = "primary";
        String fallbackPackage = "fallback";
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(
                    primaryPackage, "", true /* default available */, false /* fallback */, null),
            new WebViewProviderInfo(
                    fallbackPackage, "", true /* default available */, true /* fallback */, null)};
        setupWithPackages(packages, fallbackLogicEnabled);
        setEnabledAndValidPackageInfos(packages);

        mWebViewUpdateServiceImpl.prepareWebViewInSystemServer();
        // Verify that we disable the fallback package if fallback logic enabled, and don't disable
        // the fallback package if that logic is disabled
        if (fallbackLogicEnabled) {
            Mockito.verify(mTestSystemImpl).uninstallAndDisablePackageForAllUsers(
                    Matchers.anyObject(), Mockito.eq(fallbackPackage));
        } else {
            Mockito.verify(mTestSystemImpl, Mockito.never()).uninstallAndDisablePackageForAllUsers(
                    Matchers.anyObject(), Matchers.anyObject());
        }
        Mockito.verify(mTestSystemImpl).onWebViewProviderChanged(
                Mockito.argThat(new IsPackageInfoWithName(primaryPackage)));

        // Enable fallback package
        mTestSystemImpl.setPackageInfo(createPackageInfo(fallbackPackage, true /* enabled */,
                        true /* valid */, true /* installed */));
        mWebViewUpdateServiceImpl.packageStateChanged(
                fallbackPackage, WebViewUpdateService.PACKAGE_CHANGED, 0);

        if (fallbackLogicEnabled) {
            // Check that we have now disabled the fallback package twice
            Mockito.verify(mTestSystemImpl, Mockito.times(2)).uninstallAndDisablePackageForAllUsers(
                    Matchers.anyObject(), Mockito.eq(fallbackPackage));
        } else {
            // Check that we still haven't disabled any package
            Mockito.verify(mTestSystemImpl, Mockito.never()).uninstallAndDisablePackageForAllUsers(
                    Matchers.anyObject(), Matchers.anyObject());
        }
    }

    /**
     * Scenario for installing primary package when fallback enabled.
     * 1. Start with only fallback installed
     * 2. Install non-fallback
     * 3. Fallback should be disabled
     */
    public void testInstallingNonFallbackPackage() {
        String primaryPackage = "primary";
        String fallbackPackage = "fallback";
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(
                    primaryPackage, "", true /* default available */, false /* fallback */, null),
            new WebViewProviderInfo(
                    fallbackPackage, "", true /* default available */, true /* fallback */, null)};
        setupWithPackages(packages, true /* isFallbackLogicEnabled */);
        mTestSystemImpl.setPackageInfo(
                createPackageInfo(fallbackPackage, true /* enabled */ , true /* valid */,
                    true /* installed */));

        mWebViewUpdateServiceImpl.prepareWebViewInSystemServer();
        Mockito.verify(mTestSystemImpl, Mockito.never()).uninstallAndDisablePackageForAllUsers(
                Matchers.anyObject(), Matchers.anyObject());

        checkPreparationPhasesForPackage(fallbackPackage,
                1 /* first preparation for this package*/);

        // Install primary package
        mTestSystemImpl.setPackageInfo(
                createPackageInfo(primaryPackage, true /* enabled */ , true /* valid */,
                    true /* installed */));
        mWebViewUpdateServiceImpl.packageStateChanged(primaryPackage,
                WebViewUpdateService.PACKAGE_ADDED_REPLACED, 0);

        // Verify fallback disabled, primary package used as provider, and fallback package killed
        Mockito.verify(mTestSystemImpl).uninstallAndDisablePackageForAllUsers(
                Matchers.anyObject(), Mockito.eq(fallbackPackage));
        checkPreparationPhasesForPackage(primaryPackage, 1 /* first preparation for this package*/);
        Mockito.verify(mTestSystemImpl).killPackageDependents(Mockito.eq(fallbackPackage));
    }

    public void testFallbackChangesEnabledState() {
        String primaryPackage = "primary";
        String fallbackPackage = "fallback";
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(
                    primaryPackage, "", true /* default available */, false /* fallback */, null),
            new WebViewProviderInfo(
                    fallbackPackage, "", true /* default available */, true /* fallback */, null)};
        setupWithPackages(packages, true /* fallbackLogicEnabled */);
        setEnabledAndValidPackageInfos(packages);

        mWebViewUpdateServiceImpl.prepareWebViewInSystemServer();

        // Verify fallback disabled at boot when primary package enabled
        Mockito.verify(mTestSystemImpl).enablePackageForUser(
                Mockito.eq(fallbackPackage), Mockito.eq(false) /* enable */,
                Matchers.anyInt());

        checkPreparationPhasesForPackage(primaryPackage, 1);

        // Disable primary package and ensure fallback becomes enabled and used
        mTestSystemImpl.setPackageInfo(
                createPackageInfo(primaryPackage, false /* enabled */, true /* valid */,
                    true /* installed */));
        mWebViewUpdateServiceImpl.packageStateChanged(primaryPackage,
                WebViewUpdateService.PACKAGE_CHANGED, 0);

        Mockito.verify(mTestSystemImpl).enablePackageForUser(
                Mockito.eq(fallbackPackage), Mockito.eq(true) /* enable */,
                Matchers.anyInt());

        checkPreparationPhasesForPackage(fallbackPackage, 1);


        // Again enable primary package and verify primary is used and fallback becomes disabled
        mTestSystemImpl.setPackageInfo(
                createPackageInfo(primaryPackage, true /* enabled */, true /* valid */,
                    true /* installed */));
        mWebViewUpdateServiceImpl.packageStateChanged(primaryPackage,
                WebViewUpdateService.PACKAGE_CHANGED, 0);

        // Verify fallback is disabled a second time when primary package becomes enabled
        Mockito.verify(mTestSystemImpl, Mockito.times(2)).enablePackageForUser(
                Mockito.eq(fallbackPackage), Mockito.eq(false) /* enable */,
                Matchers.anyInt());

        checkPreparationPhasesForPackage(primaryPackage, 2);
    }

    public void testAddUserWhenFallbackLogicEnabled() {
        checkAddingNewUser(true);
    }

    public void testAddUserWhenFallbackLogicDisabled() {
        checkAddingNewUser(false);
    }

    public void checkAddingNewUser(boolean fallbackLogicEnabled) {
        String primaryPackage = "primary";
        String fallbackPackage = "fallback";
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(
                    primaryPackage, "", true /* default available */, false /* fallback */, null),
            new WebViewProviderInfo(
                    fallbackPackage, "", true /* default available */, true /* fallback */, null)};
        setupWithPackages(packages, fallbackLogicEnabled);
        setEnabledAndValidPackageInfos(packages);
        int newUser = 100;
        mWebViewUpdateServiceImpl.handleNewUser(newUser);
        if (fallbackLogicEnabled) {
            // Verify fallback package becomes disabled for new user
            Mockito.verify(mTestSystemImpl).enablePackageForUser(
                    Mockito.eq(fallbackPackage), Mockito.eq(false) /* enable */,
                    Mockito.eq(newUser));
        } else {
            // Verify that we don't disable fallback for new user
            Mockito.verify(mTestSystemImpl, Mockito.never()).enablePackageForUser(
                    Mockito.anyObject(), Matchers.anyBoolean() /* enable */,
                    Matchers.anyInt() /* user */);
        }
    }

    /**
     * Timing dependent test where we verify that the list of valid webview packages becoming empty
     * at a certain point doesn't crash us or break our state.
     */
    public void testNotifyRelroDoesntCrashIfNoPackages() {
        String firstPackage = "first";
        String secondPackage = "second";
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(firstPackage, "", true /* default available */,
                    false /* fallback */, null),
            new WebViewProviderInfo(secondPackage, "", true /* default available */,
                    false /* fallback */, null)};
        setupWithPackages(packages);
        // Add (enabled and valid) package infos for each provider
        setEnabledAndValidPackageInfos(packages);

        mWebViewUpdateServiceImpl.prepareWebViewInSystemServer();

        Mockito.verify(mTestSystemImpl).onWebViewProviderChanged(
                Mockito.argThat(new IsPackageInfoWithName(firstPackage)));

        // Change provider during relro creation to enter a state where we are
        // waiting for relro creation to complete just to re-run relro creation.
        // (so that in next notifyRelroCreationCompleted() call we have to list webview packages)
        mWebViewUpdateServiceImpl.changeProviderAndSetting(secondPackage);

        // Make packages invalid to cause exception to be thrown
        mTestSystemImpl.setPackageInfo(createPackageInfo(firstPackage, true /* enabled */,
                    false /* valid */, true /* installed */, null /* signatures */,
                    0 /* updateTime */));
        mTestSystemImpl.setPackageInfo(createPackageInfo(secondPackage, true /* enabled */,
                    false /* valid */, true /* installed */));

        // This shouldn't throw an exception!
        mWebViewUpdateServiceImpl.notifyRelroCreationCompleted();

        WebViewProviderResponse response = mWebViewUpdateServiceImpl.waitForAndGetProvider();
        assertEquals(WebViewFactory.LIBLOAD_FAILED_LISTING_WEBVIEW_PACKAGES, response.status);

        // Now make a package valid again and verify that we can switch back to that
        mTestSystemImpl.setPackageInfo(createPackageInfo(firstPackage, true /* enabled */,
                    true /* valid */, true /* installed */, null /* signatures */,
                    1 /* updateTime */ ));

        mWebViewUpdateServiceImpl.packageStateChanged(firstPackage,
                WebViewUpdateService.PACKAGE_ADDED_REPLACED, 0);

        // Ensure we use firstPackage
        checkPreparationPhasesForPackage(firstPackage, 2 /* second preparation for this package */);
    }

    /**
     * Verify that even if a user-chosen package is removed temporarily we start using it again when
     * it is added back.
     */
    public void testTempRemovePackageDoesntSwitchProviderPermanently() {
        String firstPackage = "first";
        String secondPackage = "second";
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(firstPackage, "", true /* default available */,
                    false /* fallback */, null),
            new WebViewProviderInfo(secondPackage, "", true /* default available */,
                    false /* fallback */, null)};
        checkCertainPackageUsedAfterWebViewBootPreparation(firstPackage, packages);

        // Explicitly use the second package
        mWebViewUpdateServiceImpl.changeProviderAndSetting(secondPackage);
        checkPreparationPhasesForPackage(secondPackage, 1 /* first time for this package */);

        // Remove second package (invalidate it) and verify that first package is used
        mTestSystemImpl.setPackageInfo(createPackageInfo(secondPackage, true /* enabled */,
                    false /* valid */, true /* installed */));
        mWebViewUpdateServiceImpl.packageStateChanged(secondPackage,
                WebViewUpdateService.PACKAGE_ADDED, 0);
        checkPreparationPhasesForPackage(firstPackage, 2 /* second time for this package */);

        // Now make the second package valid again and verify that it is used again
        mTestSystemImpl.setPackageInfo(createPackageInfo(secondPackage, true /* enabled */,
                    true /* valid */, true /* installed */));
        mWebViewUpdateServiceImpl.packageStateChanged(secondPackage,
                WebViewUpdateService.PACKAGE_ADDED, 0);
        checkPreparationPhasesForPackage(secondPackage, 2 /* second time for this package */);
    }

    /**
     * Ensure that we update the user-chosen setting across boots if the chosen package is no
     * longer installed and valid.
     */
    public void testProviderSettingChangedDuringBootIfProviderNotAvailable() {
        String chosenPackage = "chosenPackage";
        String nonChosenPackage = "non-chosenPackage";
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(chosenPackage, "", true /* default available */,
                    false /* fallback */, null),
            new WebViewProviderInfo(nonChosenPackage, "", true /* default available */,
                    false /* fallback */, null)};

        setupWithPackages(packages);
        // Only 'install' nonChosenPackage
        mTestSystemImpl.setPackageInfo(
                createPackageInfo(nonChosenPackage, true /* enabled */, true /* valid */, true /* installed */));

        // Set user-chosen package
        mTestSystemImpl.updateUserSetting(null, chosenPackage);

        mWebViewUpdateServiceImpl.prepareWebViewInSystemServer();

        // Verify that we switch the setting to point to the current package
        Mockito.verify(mTestSystemImpl).updateUserSetting(
                Mockito.anyObject(), Mockito.eq(nonChosenPackage));
        assertEquals(nonChosenPackage, mTestSystemImpl.getUserChosenWebViewProvider(null));

        checkPreparationPhasesForPackage(nonChosenPackage, 1);
    }

    public void testRecoverFailedListingWebViewPackagesSettingsChange() {
        checkRecoverAfterFailListingWebviewPackages(true);
    }

    public void testRecoverFailedListingWebViewPackagesAddedPackage() {
        checkRecoverAfterFailListingWebviewPackages(false);
    }

    /**
     * Test that we can recover correctly from failing to list WebView packages.
     * settingsChange: whether to fail during changeProviderAndSetting or packageStateChanged
     */
    public void checkRecoverAfterFailListingWebviewPackages(boolean settingsChange) {
        String firstPackage = "first";
        String secondPackage = "second";
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(firstPackage, "", true /* default available */,
                    false /* fallback */, null),
            new WebViewProviderInfo(secondPackage, "", true /* default available */,
                    false /* fallback */, null)};
        checkCertainPackageUsedAfterWebViewBootPreparation(firstPackage, packages);

        // Make both packages invalid so that we fail listing WebView packages
        mTestSystemImpl.setPackageInfo(createPackageInfo(firstPackage, true /* enabled */,
                    false /* valid */, true /* installed */));
        mTestSystemImpl.setPackageInfo(createPackageInfo(secondPackage, true /* enabled */,
                    false /* valid */, true /* installed */));

        // Change package to hit the webview packages listing problem.
        if (settingsChange) {
            mWebViewUpdateServiceImpl.changeProviderAndSetting(secondPackage);
        } else {
            mWebViewUpdateServiceImpl.packageStateChanged(secondPackage,
                    WebViewUpdateService.PACKAGE_ADDED_REPLACED, 0);
        }

        WebViewProviderResponse response = mWebViewUpdateServiceImpl.waitForAndGetProvider();
        assertEquals(WebViewFactory.LIBLOAD_FAILED_LISTING_WEBVIEW_PACKAGES, response.status);

        // Make second package valid and verify that we can load it again
        mTestSystemImpl.setPackageInfo(createPackageInfo(secondPackage, true /* enabled */,
                    true /* valid */, true /* installed */));

        mWebViewUpdateServiceImpl.packageStateChanged(secondPackage,
                WebViewUpdateService.PACKAGE_ADDED_REPLACED, 0);


        checkPreparationPhasesForPackage(secondPackage, 1);
    }

    public void testDontKillIfPackageReplaced() {
        checkDontKillIfPackageRemoved(true);
    }

    public void testDontKillIfPackageRemoved() {
        checkDontKillIfPackageRemoved(false);
    }

    public void checkDontKillIfPackageRemoved(boolean replaced) {
        String firstPackage = "first";
        String secondPackage = "second";
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(firstPackage, "", true /* default available */,
                    false /* fallback */, null),
            new WebViewProviderInfo(secondPackage, "", true /* default available */,
                    false /* fallback */, null)};
        checkCertainPackageUsedAfterWebViewBootPreparation(firstPackage, packages);

        // Replace or remove the current webview package
        if (replaced) {
            mTestSystemImpl.setPackageInfo(
                    createPackageInfo(firstPackage, true /* enabled */, false /* valid */,
                        true /* installed */));
            mWebViewUpdateServiceImpl.packageStateChanged(firstPackage,
                    WebViewUpdateService.PACKAGE_ADDED_REPLACED, 0);
        } else {
            mTestSystemImpl.removePackageInfo(firstPackage);
            mWebViewUpdateServiceImpl.packageStateChanged(firstPackage,
                    WebViewUpdateService.PACKAGE_REMOVED, 0);
        }

        checkPreparationPhasesForPackage(secondPackage, 1);

        Mockito.verify(mTestSystemImpl, Mockito.never()).killPackageDependents(
                Mockito.anyObject());
    }

    public void testKillIfSettingChanged() {
        String firstPackage = "first";
        String secondPackage = "second";
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(firstPackage, "", true /* default available */,
                    false /* fallback */, null),
            new WebViewProviderInfo(secondPackage, "", true /* default available */,
                    false /* fallback */, null)};
        checkCertainPackageUsedAfterWebViewBootPreparation(firstPackage, packages);

        mWebViewUpdateServiceImpl.changeProviderAndSetting(secondPackage);

        checkPreparationPhasesForPackage(secondPackage, 1);

        Mockito.verify(mTestSystemImpl).killPackageDependents(Mockito.eq(firstPackage));
    }

    /**
     * Test that we kill apps using an old provider when we change the provider setting, even if the
     * new provider is not the one we intended to change to.
     */
    public void testKillIfChangeProviderIncorrectly() {
        String firstPackage = "first";
        String secondPackage = "second";
        String thirdPackage = "third";
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(firstPackage, "", true /* default available */,
                    false /* fallback */, null),
            new WebViewProviderInfo(secondPackage, "", true /* default available */,
                    false /* fallback */, null),
            new WebViewProviderInfo(thirdPackage, "", true /* default available */,
                    false /* fallback */, null)};
        setupWithPackages(packages);
        setEnabledAndValidPackageInfos(packages);

        // Start with the setting pointing to the third package
        mTestSystemImpl.updateUserSetting(null, thirdPackage);

        mWebViewUpdateServiceImpl.prepareWebViewInSystemServer();
        checkPreparationPhasesForPackage(thirdPackage, 1);

        mTestSystemImpl.setPackageInfo(
                createPackageInfo(secondPackage, true /* enabled */, false /* valid */, true /* installed */));

        // Try to switch to the invalid second package, this should result in switching to the first
        // package, since that is more preferred than the third one.
        assertEquals(firstPackage,
                mWebViewUpdateServiceImpl.changeProviderAndSetting(secondPackage));

        checkPreparationPhasesForPackage(firstPackage, 1);

        Mockito.verify(mTestSystemImpl).killPackageDependents(Mockito.eq(thirdPackage));
    }

    // Ensure that the update service uses an uninstalled package if that is the only package
    // available.
    public void testWithSingleUninstalledPackage() {
        String testPackageName = "test.package.name";
        WebViewProviderInfo[] webviewPackages = new WebViewProviderInfo[] {
                new WebViewProviderInfo(testPackageName, "",
                        true /*default available*/, false /* fallback */, null)};
        setupWithPackages(webviewPackages, true /* fallback logic enabled */, 1 /* numRelros */);
        mTestSystemImpl.setPackageInfo(createPackageInfo(testPackageName, true /* enabled */,
                    true /* valid */, false /* installed */));

        mWebViewUpdateServiceImpl.prepareWebViewInSystemServer();

        checkPreparationPhasesForPackage(testPackageName, 1 /* first preparation phase */);
    }

    public void testNonhiddenPackageUserOverHidden() {
        checkVisiblePackageUserOverNonVisible(false /* true == uninstalled, false == hidden */);
    }

    public void testInstalledPackageUsedOverUninstalled() {
        checkVisiblePackageUserOverNonVisible(true /* true == uninstalled, false == hidden */);
    }

    private void checkVisiblePackageUserOverNonVisible(boolean uninstalledNotHidden) {
        boolean testUninstalled = uninstalledNotHidden;
        boolean testHidden = !uninstalledNotHidden;
        String installedPackage = "installedPackage";
        String uninstalledPackage = "uninstalledPackage";
        WebViewProviderInfo[] webviewPackages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(uninstalledPackage, "", true /* available by default */,
                    false /* fallback */, null),
            new WebViewProviderInfo(installedPackage, "", true /* available by default */,
                    false /* fallback */, null)};

        setupWithPackages(webviewPackages, true /* fallback logic enabled */, 1 /* numRelros */);
        mTestSystemImpl.setPackageInfo(createPackageInfo(installedPackage, true /* enabled */,
                    true /* valid */, true /* installed */));
        mTestSystemImpl.setPackageInfo(createPackageInfo(uninstalledPackage, true /* enabled */,
                    true /* valid */, (testUninstalled ? false : true) /* installed */,
                    null /* signatures */, 0 /* updateTime */, (testHidden ? true : false)));

        mWebViewUpdateServiceImpl.prepareWebViewInSystemServer();

        checkPreparationPhasesForPackage(installedPackage, 1 /* first preparation phase */);
    }

    public void testCantSwitchToHiddenPackage () {
        checkCantSwitchToNonVisiblePackage(false /* true == uninstalled, false == hidden */);
    }


    public void testCantSwitchToUninstalledPackage () {
        checkCantSwitchToNonVisiblePackage(true /* true == uninstalled, false == hidden */);
    }

    /**
     * Ensure that we won't prioritize an uninstalled (or hidden) package even if it is user-chosen,
     * and that an uninstalled (or hidden) package is not considered valid (in the
     * getValidWebViewPackages() API).
     */
    private void checkCantSwitchToNonVisiblePackage(boolean uninstalledNotHidden) {
        boolean testUninstalled = uninstalledNotHidden;
        boolean testHidden = !uninstalledNotHidden;
        String installedPackage = "installedPackage";
        String uninstalledPackage = "uninstalledPackage";
        WebViewProviderInfo[] webviewPackages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(uninstalledPackage, "", true /* available by default */,
                    false /* fallback */, null),
            new WebViewProviderInfo(installedPackage, "", true /* available by default */,
                    false /* fallback */, null)};

        setupWithPackages(webviewPackages, true /* fallback logic enabled */, 1 /* numRelros */);
        mTestSystemImpl.setPackageInfo(createPackageInfo(installedPackage, true /* enabled */,
                    true /* valid */, true /* installed */));
        mTestSystemImpl.setPackageInfo(createPackageInfo(uninstalledPackage, true /* enabled */,
                    true /* valid */, (testUninstalled ? false : true) /* installed */,
                    null /* signatures */, 0 /* updateTime */,
                    (testHidden ? true : false) /* hidden */));

        mWebViewUpdateServiceImpl.prepareWebViewInSystemServer();

        checkPreparationPhasesForPackage(installedPackage, 1 /* first preparation phase */);

        // Ensure that only the installed package is considered valid
        WebViewProviderInfo[] validPackages = mWebViewUpdateServiceImpl.getValidWebViewPackages();
        assertEquals(1, validPackages.length);
        assertEquals(installedPackage, validPackages[0].packageName);

        // ensure that we don't switch to the uninstalled package (it will be used if it becomes
        // installed later)
        assertEquals(installedPackage,
                mWebViewUpdateServiceImpl.changeProviderAndSetting(uninstalledPackage));

        // We should only have called onWebViewProviderChanged once (before calling
        // changeProviderAndSetting
        Mockito.verify(mTestSystemImpl, Mockito.times(1)).onWebViewProviderChanged(
                Mockito.argThat(new IsPackageInfoWithName(installedPackage)));
    }

    public void testHiddenPackageNotPrioritizedEvenIfChosen() {
        checkNonvisiblePackageNotPrioritizedEvenIfChosen(
                false /* true == uninstalled, false == hidden */);
    }

    public void testUninstalledPackageNotPrioritizedEvenIfChosen() {
        checkNonvisiblePackageNotPrioritizedEvenIfChosen(
                true /* true == uninstalled, false == hidden */);
    }

    public void checkNonvisiblePackageNotPrioritizedEvenIfChosen(boolean uninstalledNotHidden) {
        boolean testUninstalled = uninstalledNotHidden;
        boolean testHidden = !uninstalledNotHidden;
        String installedPackage = "installedPackage";
        String uninstalledPackage = "uninstalledPackage";
        WebViewProviderInfo[] webviewPackages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(uninstalledPackage, "", true /* available by default */,
                    false /* fallback */, null),
            new WebViewProviderInfo(installedPackage, "", true /* available by default */,
                    false /* fallback */, null)};

        setupWithPackages(webviewPackages, true /* fallback logic enabled */, 1 /* numRelros */);
        mTestSystemImpl.setPackageInfo(createPackageInfo(installedPackage, true /* enabled */,
                    true /* valid */, true /* installed */));
        mTestSystemImpl.setPackageInfo(createPackageInfo(uninstalledPackage, true /* enabled */,
                    true /* valid */, (testUninstalled ? false : true) /* installed */,
                    null /* signatures */, 0 /* updateTime */,
                    (testHidden ? true : false) /* hidden */));

        // Start with the setting pointing to the uninstalled package
        mTestSystemImpl.updateUserSetting(null, uninstalledPackage);

        mWebViewUpdateServiceImpl.prepareWebViewInSystemServer();

        checkPreparationPhasesForPackage(installedPackage, 1 /* first preparation phase */);
    }

    /**
     * Ensures that fallback becomes enabled if the primary package is uninstalled for the current
     * user.
     */
    public void testFallbackEnabledIfPrimaryUninstalled() {
        String primaryPackage = "primary";
        String fallbackPackage = "fallback";
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(
                    primaryPackage, "", true /* default available */, false /* fallback */, null),
            new WebViewProviderInfo(
                    fallbackPackage, "", true /* default available */, true /* fallback */, null)};
        setupWithPackages(packages, true /* fallback logic enabled */);
        mTestSystemImpl.setPackageInfo(createPackageInfo(primaryPackage, true /* enabled */,
                    true /* valid */, false /* installed */));
        mTestSystemImpl.setPackageInfo(createPackageInfo(fallbackPackage, true /* enabled */,
                    true /* valid */, true /* installed */));

        mWebViewUpdateServiceImpl.prepareWebViewInSystemServer();
        // Verify that we enable the fallback package
        Mockito.verify(mTestSystemImpl).enablePackageForAllUsers(
                Mockito.anyObject(), Mockito.eq(fallbackPackage), Mockito.eq(true) /* enable */);

        checkPreparationPhasesForPackage(fallbackPackage, 1 /* first preparation phase */);
    }

    public void testPreparationRunsIffNewPackage() {
        String primaryPackage = "primary";
        String fallbackPackage = "fallback";
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(
                    primaryPackage, "", true /* default available */, false /* fallback */, null),
            new WebViewProviderInfo(
                    fallbackPackage, "", true /* default available */, true /* fallback */, null)};
        setupWithPackages(packages, true /* fallback logic enabled */);
        mTestSystemImpl.setPackageInfo(createPackageInfo(primaryPackage, true /* enabled */,
                    true /* valid */, true /* installed */, null /* signatures */,
                    10 /* lastUpdateTime*/ ));
        mTestSystemImpl.setPackageInfo(createPackageInfo(fallbackPackage, true /* enabled */,
                    true /* valid */, true /* installed */));

        mWebViewUpdateServiceImpl.prepareWebViewInSystemServer();

        checkPreparationPhasesForPackage(primaryPackage, 1 /* first preparation phase */);
        Mockito.verify(mTestSystemImpl, Mockito.times(1)).enablePackageForUser(
                Mockito.eq(fallbackPackage), Mockito.eq(false) /* enable */,
                Matchers.anyInt() /* user */);


        mWebViewUpdateServiceImpl.packageStateChanged(primaryPackage,
                WebViewUpdateService.PACKAGE_ADDED_REPLACED, 0 /* userId */);
        mWebViewUpdateServiceImpl.packageStateChanged(primaryPackage,
                WebViewUpdateService.PACKAGE_ADDED_REPLACED, 1 /* userId */);
        mWebViewUpdateServiceImpl.packageStateChanged(primaryPackage,
                WebViewUpdateService.PACKAGE_ADDED_REPLACED, 2 /* userId */);
        // package still has the same update-time so we shouldn't run preparation here
        Mockito.verify(mTestSystemImpl, Mockito.times(1)).onWebViewProviderChanged(
                Mockito.argThat(new IsPackageInfoWithName(primaryPackage)));
        Mockito.verify(mTestSystemImpl, Mockito.times(1)).enablePackageForUser(
                Mockito.eq(fallbackPackage), Mockito.eq(false) /* enable */,
                Matchers.anyInt() /* user */);

        // Ensure we can still load the package
        WebViewProviderResponse response = mWebViewUpdateServiceImpl.waitForAndGetProvider();
        assertEquals(WebViewFactory.LIBLOAD_SUCCESS, response.status);
        assertEquals(primaryPackage, response.packageInfo.packageName);


        mTestSystemImpl.setPackageInfo(createPackageInfo(primaryPackage, true /* enabled */,
                    true /* valid */, true /* installed */, null /* signatures */,
                    20 /* lastUpdateTime*/ ));
        mWebViewUpdateServiceImpl.packageStateChanged(primaryPackage,
                WebViewUpdateService.PACKAGE_ADDED_REPLACED, 0);
        // The package has now changed - ensure that we have run the preparation phase a second time
        checkPreparationPhasesForPackage(primaryPackage, 2 /* second preparation phase */);


        mTestSystemImpl.setPackageInfo(createPackageInfo(primaryPackage, true /* enabled */,
                    true /* valid */, true /* installed */, null /* signatures */,
                    50 /* lastUpdateTime*/ ));
        // Receive intent for different user
        mWebViewUpdateServiceImpl.packageStateChanged(primaryPackage,
                WebViewUpdateService.PACKAGE_ADDED_REPLACED, 2);

        checkPreparationPhasesForPackage(primaryPackage, 3 /* third preparation phase */);
    }

}
