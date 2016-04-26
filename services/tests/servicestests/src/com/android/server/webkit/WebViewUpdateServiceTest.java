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
                        true /* valid */));
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
            String packageName, boolean enabled, boolean valid) {
        PackageInfo p = new PackageInfo();
        p.packageName = packageName;
        p.applicationInfo = new ApplicationInfo();
        p.applicationInfo.enabled = enabled;
        p.applicationInfo.metaData = new Bundle();
        if (valid) {
            // no flag means invalid
            p.applicationInfo.metaData.putString(WEBVIEW_LIBRARY_FLAG, "blah");
        }
        return p;
    }

    private static PackageInfo createPackageInfo(
            String packageName, boolean enabled, boolean valid, Signature[] signatures) {
        PackageInfo p = createPackageInfo(packageName, enabled, valid);
        p.signatures = signatures;
        return p;
    }

    private static PackageInfo createPackageInfo(String packageName, boolean enabled, boolean valid,
            Signature[] signatures, int versionCode, boolean isSystemApp) {
        PackageInfo p = createPackageInfo(packageName, enabled, valid, signatures);
        p.versionCode = versionCode;
        p.applicationInfo.versionCode = versionCode;
        if (isSystemApp) p.applicationInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
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
                    true /* valid */, new Signature[]{invalidPackageSignature}));
        mTestSystemImpl.setPackageInfo(createPackageInfo(validPackage, true /* enabled */,
                    true /* valid */, new Signature[]{validSignature}));

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
                createPackageInfo(wpi.packageName, true /* enabled */, false /* valid */));

        mWebViewUpdateServiceImpl.prepareWebViewInSystemServer();

        Mockito.verify(mTestSystemImpl, Mockito.never()).onWebViewProviderChanged(
                Matchers.anyObject());

        WebViewProviderResponse response = mWebViewUpdateServiceImpl.waitForAndGetProvider();
        assertEquals(WebViewFactory.LIBLOAD_FAILED_LISTING_WEBVIEW_PACKAGES, response.status);

        // Verify that we can recover from failing to list webview packages.
        mTestSystemImpl.setPackageInfo(
                createPackageInfo(wpi.packageName, true /* enabled */, true /* valid */));
        mWebViewUpdateServiceImpl.packageStateChanged(wpi.packageName,
                WebViewUpdateService.PACKAGE_ADDED_REPLACED);

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
                            false /* enabled */, true /* valid */));
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
            Thread.sleep(1000); // Let the new thread run / be blocked
        } catch (InterruptedException e) {
        }

        if (settingsChange) {
            mWebViewUpdateServiceImpl.changeProviderAndSetting(secondPackage);
        } else {
            // Switch provider by enabling the second one
            mTestSystemImpl.setPackageInfo(createPackageInfo(secondPackage, true /* enabled */,
                        true /* valid */));
            mWebViewUpdateServiceImpl.packageStateChanged(
                    secondPackage, WebViewUpdateService.PACKAGE_CHANGED);
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
                        true /* valid */));
        mWebViewUpdateServiceImpl.packageStateChanged(
                fallbackPackage, WebViewUpdateService.PACKAGE_CHANGED);

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
                createPackageInfo(fallbackPackage, true /* enabled */ , true /* valid */));

        mWebViewUpdateServiceImpl.prepareWebViewInSystemServer();
        Mockito.verify(mTestSystemImpl, Mockito.never()).uninstallAndDisablePackageForAllUsers(
                Matchers.anyObject(), Matchers.anyObject());

        checkPreparationPhasesForPackage(fallbackPackage,
                1 /* first preparation for this package*/);

        // Install primary package
        mTestSystemImpl.setPackageInfo(
                createPackageInfo(primaryPackage, true /* enabled */ , true /* valid */));
        mWebViewUpdateServiceImpl.packageStateChanged(primaryPackage,
                WebViewUpdateService.PACKAGE_ADDED_REPLACED);

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
                createPackageInfo(primaryPackage, false /* enabled */, true /* valid */));
        mWebViewUpdateServiceImpl.packageStateChanged(primaryPackage,
                WebViewUpdateService.PACKAGE_CHANGED);

        Mockito.verify(mTestSystemImpl).enablePackageForUser(
                Mockito.eq(fallbackPackage), Mockito.eq(true) /* enable */,
                Matchers.anyInt());

        checkPreparationPhasesForPackage(fallbackPackage, 1);


        // Again enable primary package and verify primary is used and fallback becomes disabled
        mTestSystemImpl.setPackageInfo(
                createPackageInfo(primaryPackage, true /* enabled */, true /* valid */));
        mWebViewUpdateServiceImpl.packageStateChanged(primaryPackage,
                WebViewUpdateService.PACKAGE_CHANGED);

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
                    false /* valid */));
        mTestSystemImpl.setPackageInfo(createPackageInfo(secondPackage, true /* enabled */,
                    false /* valid */));

        // This shouldn't throw an exception!
        mWebViewUpdateServiceImpl.notifyRelroCreationCompleted();

        WebViewProviderResponse response = mWebViewUpdateServiceImpl.waitForAndGetProvider();
        assertEquals(WebViewFactory.LIBLOAD_FAILED_LISTING_WEBVIEW_PACKAGES, response.status);

        // Now make a package valid again and verify that we can switch back to that
        mTestSystemImpl.setPackageInfo(createPackageInfo(firstPackage, true /* enabled */,
                    true /* valid */));

        mWebViewUpdateServiceImpl.packageStateChanged(firstPackage,
                WebViewUpdateService.PACKAGE_ADDED_REPLACED);

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
                    false /* valid */));
        mWebViewUpdateServiceImpl.packageStateChanged(secondPackage,
                WebViewUpdateService.PACKAGE_ADDED);
        checkPreparationPhasesForPackage(firstPackage, 2 /* second time for this package */);

        // Now make the second package valid again and verify that it is used again
        mTestSystemImpl.setPackageInfo(createPackageInfo(secondPackage, true /* enabled */,
                    true /* valid */));
        mWebViewUpdateServiceImpl.packageStateChanged(secondPackage,
                WebViewUpdateService.PACKAGE_ADDED);
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
                createPackageInfo(nonChosenPackage, true /* enabled */, true /* valid */));

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
                    false /* valid */));
        mTestSystemImpl.setPackageInfo(createPackageInfo(secondPackage, true /* enabled */,
                    false /* valid */));

        // Change package to hit the webview packages listing problem.
        if (settingsChange) {
            mWebViewUpdateServiceImpl.changeProviderAndSetting(secondPackage);
        } else {
            mWebViewUpdateServiceImpl.packageStateChanged(secondPackage,
                    WebViewUpdateService.PACKAGE_ADDED_REPLACED);
        }

        WebViewProviderResponse response = mWebViewUpdateServiceImpl.waitForAndGetProvider();
        assertEquals(WebViewFactory.LIBLOAD_FAILED_LISTING_WEBVIEW_PACKAGES, response.status);

        // Make second package valid and verify that we can load it again
        mTestSystemImpl.setPackageInfo(createPackageInfo(secondPackage, true /* enabled */,
                    true /* valid */));

        mWebViewUpdateServiceImpl.packageStateChanged(secondPackage,
                WebViewUpdateService.PACKAGE_ADDED_REPLACED);


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
                    createPackageInfo(firstPackage, true /* enabled */, false /* valid */));
            mWebViewUpdateServiceImpl.packageStateChanged(firstPackage,
                    WebViewUpdateService.PACKAGE_ADDED_REPLACED);
        } else {
            mTestSystemImpl.removePackageInfo(firstPackage);
            mWebViewUpdateServiceImpl.packageStateChanged(firstPackage,
                    WebViewUpdateService.PACKAGE_REMOVED);
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
                createPackageInfo(secondPackage, true /* enabled */, false /* valid */));

        // Try to switch to the invalid second package, this should result in switching to the first
        // package, since that is more preferred than the third one.
        assertEquals(firstPackage,
                mWebViewUpdateServiceImpl.changeProviderAndSetting(secondPackage));

        checkPreparationPhasesForPackage(firstPackage, 1);

        Mockito.verify(mTestSystemImpl).killPackageDependents(Mockito.eq(thirdPackage));
    }

    public void testLowerPackageVersionNotValid() {
        checkPackageVersions(new int[]{200000} /* system version */, 100000/* candidate version */,
                false /* expected validity */);
    }

    public void testEqualPackageVersionValid() {
        checkPackageVersions(new int[]{100000} /* system version */, 100000 /* candidate version */,
                true /* expected validity */);
    }

    public void testGreaterPackageVersionValid() {
        checkPackageVersions(new int[]{100000} /* system versions */, 200000 /* candidate version */,
                true /* expected validity */);
    }

    public void testLastFiveDigitsIgnored() {
        checkPackageVersions(new int[]{654321} /* system version */, 612345 /* candidate version */,
                true /* expected validity */);
    }

    public void testMinimumSystemVersionUsedTwoDefaultsCandidateValid() {
        checkPackageVersions(new int[]{300000, 100000} /* system versions */,
                200000 /* candidate version */, true /* expected validity */);
    }

    public void testMinimumSystemVersionUsedTwoDefaultsCandidateInvalid() {
        checkPackageVersions(new int[]{300000, 200000} /* system versions */,
                 100000 /* candidate version */, false /* expected validity */);
    }

    public void testMinimumSystemVersionUsedSeveralDefaultsCandidateValid() {
        checkPackageVersions(new int[]{100000, 200000, 300000, 400000, 500000} /* system versions */,
                100000 /* candidate version */, true /* expected validity */);
    }

    public void testMinimumSystemVersionUsedSeveralDefaultsCandidateInvalid() {
        checkPackageVersions(new int[]{200000, 300000, 400000, 500000, 600000} /* system versions */,
                100000 /* candidate version */, false /* expected validity */);
    }

    public void testMinimumSystemVersionUsedFallbackIgnored() {
        checkPackageVersions(new int[]{300000, 400000, 500000, 600000, 700000} /* system versions */,
                200000 /* candidate version */, false /* expected validity */, true /* add fallback */,
                100000 /* fallback version */, false /* expected validity of fallback */);
    }

    public void testFallbackValid() {
        checkPackageVersions(new int[]{300000, 400000, 500000, 600000, 700000} /* system versions */,
                200000/* candidate version */, false /* expected validity */, true /* add fallback */,
                300000 /* fallback version */, true /* expected validity of fallback */);
    }

    private void checkPackageVersions(int[] systemVersions, int candidateVersion,
            boolean candidateShouldBeValid) {
        checkPackageVersions(systemVersions, candidateVersion, candidateShouldBeValid,
                false, 0, false);
    }

    /**
     * Utility method for checking that package version restriction works as it should.
     * I.e. that a package with lower version than the system-default is not valid and that a
     * package with greater than or equal version code is considered valid.
     */
    private void checkPackageVersions(int[] systemVersions, int candidateVersion,
            boolean candidateShouldBeValid, boolean addFallback, int fallbackVersion,
            boolean fallbackShouldBeValid) {
        int numSystemPackages = systemVersions.length;
        int numFallbackPackages = (addFallback ? 1 : 0);
        int numPackages = systemVersions.length + 1 + numFallbackPackages;
        String candidatePackage = "candidatePackage";
        String systemPackage = "systemPackage";
        String fallbackPackage = "fallbackPackage";

        // Each package needs a valid signature since we set isDebuggable to false
        Signature signature = new Signature("11");
        String encodedSignatureString =
            Base64.encodeToString(signature.toByteArray(), Base64.DEFAULT);

        // Set up config
        // 1. candidatePackage
        // 2-N. default available non-fallback packages
        // N+1. default available fallback package
        WebViewProviderInfo[] packages = new WebViewProviderInfo[numPackages];
        packages[0] = new WebViewProviderInfo(candidatePackage, "",
                false /* available by default */, false /* fallback */,
                new String[]{encodedSignatureString});
        for(int n = 1; n < numSystemPackages + 1; n++) {
            packages[n] = new WebViewProviderInfo(systemPackage + n, "",
                    true /* available by default */, false /* fallback */,
                    new String[]{encodedSignatureString});
        }
        if (addFallback) {
            packages[packages.length-1] = new WebViewProviderInfo(fallbackPackage, "",
                    true /* available by default */, true /* fallback */,
                    new String[]{encodedSignatureString});
        }

        setupWithPackages(packages, true /* fallback logic enabled */, 1 /* numRelros */,
                false /* isDebuggable */);

        // Set package infos
        mTestSystemImpl.setPackageInfo(
                createPackageInfo(candidatePackage, true /* enabled */, true /* valid */,
                    new Signature[]{signature}, candidateVersion,
                    false /* isSystemApp */));
        for(int n = 1; n < numSystemPackages + 1; n++) {
            mTestSystemImpl.setPackageInfo(
                    createPackageInfo(systemPackage + n, true /* enabled */, true /* valid */,
                        new Signature[]{signature}, systemVersions[n-1],
                        true /* isSystemApp */));
        }
        if (addFallback) {
            mTestSystemImpl.setPackageInfo(
                    createPackageInfo(fallbackPackage, true /* enabled */, true /* valid */,
                        new Signature[]{signature}, fallbackVersion, true /* isSystemApp */));
        }

        WebViewProviderInfo[] validPackages = mWebViewUpdateServiceImpl.getValidWebViewPackages();
        int expectedNumValidPackages = numSystemPackages;
        if (candidateShouldBeValid) {
            expectedNumValidPackages++;
        } else {
            // Ensure the candidate package is not one of the valid packages
            for(int n = 0; n < validPackages.length; n++) {
                assertFalse(candidatePackage.equals(validPackages[n].packageName));
            }
        }

        if (fallbackShouldBeValid) {
            expectedNumValidPackages += numFallbackPackages;
        } else {
            // Ensure the fallback package is not one of the valid packages
            for(int n = 0; n < validPackages.length; n++) {
                assertFalse(fallbackPackage.equals(validPackages[n].packageName));
            }
        }

        assertEquals(expectedNumValidPackages, validPackages.length);

        mWebViewUpdateServiceImpl.prepareWebViewInSystemServer();

        // The non-system package is not available by default so it shouldn't be used here
        checkPreparationPhasesForPackage(systemPackage + "1", 1);

        // Try explicitly switching to the candidate package
        String packageChange = mWebViewUpdateServiceImpl.changeProviderAndSetting(candidatePackage);
        if (candidateShouldBeValid) {
            assertEquals(candidatePackage, packageChange);
            checkPreparationPhasesForPackage(candidatePackage, 1);
        } else {
            assertEquals(systemPackage + "1", packageChange);
            // We didn't change package so the webview preparation won't run here
        }
    }
}
