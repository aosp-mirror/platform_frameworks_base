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

import android.app.ActivityManager;
import android.app.AppGlobals;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.UserInfo;
import android.content.res.XmlResourceParser;
import android.os.Build;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.AndroidRuntimeException;
import android.util.Log;
import android.webkit.UserPackage;
import android.webkit.WebViewFactory;
import android.webkit.WebViewProviderInfo;
import android.webkit.WebViewZygote;

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Default implementation for the WebView preparation Utility interface.
 * @hide
 */
public class SystemImpl implements SystemInterface {
    private static final String TAG = SystemImpl.class.getSimpleName();
    private static final String TAG_START = "webviewproviders";
    private static final String TAG_WEBVIEW_PROVIDER = "webviewprovider";
    private static final String TAG_PACKAGE_NAME = "packageName";
    private static final String TAG_DESCRIPTION = "description";
    // Whether or not the provider must be explicitly chosen by the user to be used.
    private static final String TAG_AVAILABILITY = "availableByDefault";
    private static final String TAG_SIGNATURE = "signature";
    private static final String TAG_FALLBACK = "isFallback";
    private final WebViewProviderInfo[] mWebViewProviderPackages;

    // Initialization-on-demand holder idiom for getting the WebView provider packages once and
    // for all in a thread-safe manner.
    private static class LazyHolder {
        private static final SystemImpl INSTANCE = new SystemImpl();
    }

    public static SystemImpl getInstance() {
        return LazyHolder.INSTANCE;
    }

    private SystemImpl() {
        int numFallbackPackages = 0;
        int numAvailableByDefaultPackages = 0;
        XmlResourceParser parser = null;
        List<WebViewProviderInfo> webViewProviders = new ArrayList<WebViewProviderInfo>();
        try {
            parser = AppGlobals.getInitialApplication().getResources().getXml(
                    com.android.internal.R.xml.config_webview_packages);
            XmlUtils.beginDocument(parser, TAG_START);
            while(true) {
                XmlUtils.nextElement(parser);
                String element = parser.getName();
                if (element == null) {
                    break;
                }
                if (element.equals(TAG_WEBVIEW_PROVIDER)) {
                    String packageName = parser.getAttributeValue(null, TAG_PACKAGE_NAME);
                    if (packageName == null) {
                        throw new AndroidRuntimeException(
                                "WebView provider in framework resources missing package name");
                    }
                    String description = parser.getAttributeValue(null, TAG_DESCRIPTION);
                    if (description == null) {
                        throw new AndroidRuntimeException(
                                "WebView provider in framework resources missing description");
                    }
                    boolean availableByDefault = "true".equals(
                            parser.getAttributeValue(null, TAG_AVAILABILITY));
                    boolean isFallback = "true".equals(
                            parser.getAttributeValue(null, TAG_FALLBACK));
                    WebViewProviderInfo currentProvider = new WebViewProviderInfo(
                            packageName, description, availableByDefault, isFallback,
                            readSignatures(parser));
                    if (currentProvider.isFallback) {
                        numFallbackPackages++;
                        if (!currentProvider.availableByDefault) {
                            throw new AndroidRuntimeException(
                                    "Each WebView fallback package must be available by default.");
                        }
                        if (numFallbackPackages > 1) {
                            throw new AndroidRuntimeException(
                                    "There can be at most one WebView fallback package.");
                        }
                    }
                    if (currentProvider.availableByDefault) {
                        numAvailableByDefaultPackages++;
                    }
                    webViewProviders.add(currentProvider);
                }
                else {
                    Log.e(TAG, "Found an element that is not a WebView provider");
                }
            }
        } catch (XmlPullParserException | IOException e) {
            throw new AndroidRuntimeException("Error when parsing WebView config " + e);
        } finally {
            if (parser != null) parser.close();
        }
        if (numAvailableByDefaultPackages == 0) {
            throw new AndroidRuntimeException("There must be at least one WebView package "
                    + "that is available by default");
        }
        mWebViewProviderPackages =
                webViewProviders.toArray(new WebViewProviderInfo[webViewProviders.size()]);
    }
    /**
     * Returns all packages declared in the framework resources as potential WebView providers.
     * @hide
     * */
    @Override
    public WebViewProviderInfo[] getWebViewPackages() {
        return mWebViewProviderPackages;
    }

    public long getFactoryPackageVersion(String packageName) throws NameNotFoundException {
        PackageManager pm = AppGlobals.getInitialApplication().getPackageManager();
        return pm.getPackageInfo(packageName, PackageManager.MATCH_FACTORY_ONLY)
                .getLongVersionCode();
    }

    /**
     * Reads all signatures at the current depth (within the current provider) from the XML parser.
     */
    private static String[] readSignatures(XmlResourceParser parser) throws IOException,
            XmlPullParserException {
        List<String> signatures = new ArrayList<String>();
        int outerDepth = parser.getDepth();
        while(XmlUtils.nextElementWithin(parser, outerDepth)) {
            if (parser.getName().equals(TAG_SIGNATURE)) {
                // Parse the value within the signature tag
                String signature = parser.nextText();
                signatures.add(signature);
            } else {
                Log.e(TAG, "Found an element in a webview provider that is not a signature");
            }
        }
        return signatures.toArray(new String[signatures.size()]);
    }

    @Override
    public int onWebViewProviderChanged(PackageInfo packageInfo) {
        return WebViewFactory.onWebViewProviderChanged(packageInfo);
    }

    @Override
    public String getUserChosenWebViewProvider(Context context) {
        return Settings.Global.getString(context.getContentResolver(),
                Settings.Global.WEBVIEW_PROVIDER);
    }

    @Override
    public void updateUserSetting(Context context, String newProviderName) {
        Settings.Global.putString(context.getContentResolver(),
                Settings.Global.WEBVIEW_PROVIDER,
                newProviderName == null ? "" : newProviderName);
    }

    @Override
    public void killPackageDependents(String packageName) {
        try {
            ActivityManager.getService().killPackageDependents(packageName,
                    UserHandle.USER_ALL);
        } catch (RemoteException e) {
        }
    }

    @Override
    public void enablePackageForAllUsers(Context context, String packageName, boolean enable) {
        UserManager userManager = (UserManager)context.getSystemService(Context.USER_SERVICE);
        for(UserInfo userInfo : userManager.getUsers()) {
            enablePackageForUser(packageName, enable, userInfo.id);
        }
    }

    private void enablePackageForUser(String packageName, boolean enable, int userId) {
        try {
            AppGlobals.getPackageManager().setApplicationEnabledSetting(
                    packageName,
                    enable ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT :
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0,
                    userId, null);
        } catch (RemoteException | IllegalArgumentException e) {
            Log.w(TAG, "Tried to " + (enable ? "enable " : "disable ") + packageName
                    + " for user " + userId + ": " + e);
        }
    }

    @Override
    public boolean systemIsDebuggable() {
        return Build.IS_DEBUGGABLE && Build.IS_ENG;
    }

    @Override
    public PackageInfo getPackageInfoForProvider(WebViewProviderInfo configInfo)
            throws NameNotFoundException {
        PackageManager pm = AppGlobals.getInitialApplication().getPackageManager();
        return pm.getPackageInfo(configInfo.packageName, PACKAGE_FLAGS);
    }

    @Override
    public List<UserPackage> getPackageInfoForProviderAllUsers(Context context,
            WebViewProviderInfo configInfo) {
        return UserPackage.getPackageInfosAllUsers(context, configInfo.packageName, PACKAGE_FLAGS);
    }

    @Override
    public int getMultiProcessSetting(Context context) {
        return Settings.Global.getInt(context.getContentResolver(),
                                      Settings.Global.WEBVIEW_MULTIPROCESS, 0);
    }

    @Override
    public void setMultiProcessSetting(Context context, int value) {
        Settings.Global.putInt(context.getContentResolver(),
                               Settings.Global.WEBVIEW_MULTIPROCESS, value);
    }

    @Override
    public void notifyZygote(boolean enableMultiProcess) {
        WebViewZygote.setMultiprocessEnabled(enableMultiProcess);
    }

    @Override
    public void ensureZygoteStarted() {
        WebViewZygote.getProcess();
    }

    @Override
    public boolean isMultiProcessDefaultEnabled() {
        // Multiprocess is enabled by default for all devices.
        return true;
    }

    // flags declaring we want extra info from the package manager for webview providers
    private final static int PACKAGE_FLAGS = PackageManager.GET_META_DATA
            | PackageManager.GET_SIGNATURES | PackageManager.GET_SHARED_LIBRARY_FILES
            | PackageManager.MATCH_DEBUG_TRIAGED_MISSING | PackageManager.MATCH_ANY_USER;
}
