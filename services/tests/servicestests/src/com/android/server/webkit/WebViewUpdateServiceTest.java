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

    private void checkCertainPackageUsedAfterWebViewPreparation(String expectedProviderName,
            WebViewProviderInfo[] webviewPackages) {
        checkCertainPackageUsedAfterWebViewPreparation(expectedProviderName, webviewPackages, 1);
    }

    private void checkCertainPackageUsedAfterWebViewPreparation(String expectedProviderName,
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


    // ****************
    // Tests
    // ****************


    public void testWithSinglePackage() {
        String testPackageName = "test.package.name";
        checkCertainPackageUsedAfterWebViewPreparation(testPackageName,
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
        checkCertainPackageUsedAfterWebViewPreparation(defaultPackage, packages);
    }

    public void testSeveralRelros() {
        String singlePackage = "singlePackage";
        checkCertainPackageUsedAfterWebViewPreparation(
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

        Mockito.verify(mTestSystemImpl).onWebViewProviderChanged(
                Mockito.argThat(new IsPackageInfoWithName(validPackage)));

        mWebViewUpdateServiceImpl.notifyRelroCreationCompleted();

        WebViewProviderResponse response = mWebViewUpdateServiceImpl.waitForAndGetProvider();
        assertEquals(WebViewFactory.LIBLOAD_SUCCESS, response.status);
        assertEquals(validPackage, response.packageInfo.packageName);

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
        WebViewProviderInfo wpi = new WebViewProviderInfo("", "", true, true, null);
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {wpi};
        setupWithPackages(packages);
        mTestSystemImpl.setPackageInfo(createPackageInfo(wpi.packageName, true, false));

        mWebViewUpdateServiceImpl.prepareWebViewInSystemServer();
        WebViewProviderResponse response = mWebViewUpdateServiceImpl.waitForAndGetProvider();
        assertEquals(WebViewFactory.LIBLOAD_FAILED_LISTING_WEBVIEW_PACKAGES, response.status);
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
        checkCertainPackageUsedAfterWebViewPreparation(initialPackage, packages);

        mWebViewUpdateServiceImpl.changeProviderAndSetting(finalPackage);

        Mockito.verify(mTestSystemImpl).onWebViewProviderChanged(
                Mockito.argThat(new IsPackageInfoWithName(finalPackage)));

        mWebViewUpdateServiceImpl.notifyRelroCreationCompleted();

        WebViewProviderResponse secondResponse = mWebViewUpdateServiceImpl.waitForAndGetProvider();
        assertEquals(WebViewFactory.LIBLOAD_SUCCESS, secondResponse.status);
        assertEquals(finalPackage, secondResponse.packageInfo.packageName);

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
        Mockito.verify(mTestSystemImpl).onWebViewProviderChanged(
                Mockito.argThat(new IsPackageInfoWithName(fallbackPackage)));

        mWebViewUpdateServiceImpl.notifyRelroCreationCompleted();

        WebViewProviderResponse response = mWebViewUpdateServiceImpl.waitForAndGetProvider();
        assertEquals(WebViewFactory.LIBLOAD_SUCCESS, response.status);
        assertEquals(fallbackPackage, response.packageInfo.packageName);

        // Install primary package
        mTestSystemImpl.setPackageInfo(
                createPackageInfo(primaryPackage, true /* enabled */ , true /* valid */));
        mWebViewUpdateServiceImpl.packageStateChanged(primaryPackage,
                WebViewUpdateService.PACKAGE_ADDED);

        // Verify fallback disabled and primary package used as provider
        Mockito.verify(mTestSystemImpl).uninstallAndDisablePackageForAllUsers(
                Matchers.anyObject(), Mockito.eq(fallbackPackage));
        Mockito.verify(mTestSystemImpl).onWebViewProviderChanged(
                Mockito.argThat(new IsPackageInfoWithName(primaryPackage)));

        // Finish the webview preparation and ensure primary package used and fallback killed
        mWebViewUpdateServiceImpl.notifyRelroCreationCompleted();
        response = mWebViewUpdateServiceImpl.waitForAndGetProvider();
        assertEquals(WebViewFactory.LIBLOAD_SUCCESS, response.status);
        assertEquals(primaryPackage, response.packageInfo.packageName);
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

        mTestSystemImpl.setPackageInfo(
                createPackageInfo(primaryPackage, false /* enabled */, true /* valid */));
        mWebViewUpdateServiceImpl.packageStateChanged(primaryPackage,
                WebViewUpdateService.PACKAGE_CHANGED);

        // Verify fallback becomes enabled when primary package becomes disabled
        Mockito.verify(mTestSystemImpl).enablePackageForUser(
                Mockito.eq(fallbackPackage), Mockito.eq(true) /* enable */,
                Matchers.anyInt());

        mTestSystemImpl.setPackageInfo(
                createPackageInfo(primaryPackage, true /* enabled */, true /* valid */));
        mWebViewUpdateServiceImpl.packageStateChanged(primaryPackage,
                WebViewUpdateService.PACKAGE_CHANGED);

        // Verify fallback is disabled a second time when primary package becomes enabled
        Mockito.verify(mTestSystemImpl, Mockito.times(2)).enablePackageForUser(
                Mockito.eq(fallbackPackage), Mockito.eq(false) /* enable */,
                Matchers.anyInt());
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
                WebViewUpdateService.PACKAGE_ADDED);

        // Second time we call onWebViewProviderChanged for firstPackage
        Mockito.verify(mTestSystemImpl, Mockito.times(2)).onWebViewProviderChanged(
                Mockito.argThat(new IsPackageInfoWithName(firstPackage)));

        mWebViewUpdateServiceImpl.notifyRelroCreationCompleted();

        response = mWebViewUpdateServiceImpl.waitForAndGetProvider();
        assertEquals(WebViewFactory.LIBLOAD_SUCCESS, response.status);
        assertEquals(firstPackage, response.packageInfo.packageName);
    }

    // TODO (gsennton) add more tests for ensuring killPackageDependents is called / not called
}
