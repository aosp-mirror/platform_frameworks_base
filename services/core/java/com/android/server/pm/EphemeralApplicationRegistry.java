/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.pm;

import android.content.Context;
import android.content.pm.EphemeralApplicationInfo;
import android.content.pm.PackageParser;
import android.content.pm.PackageUserState;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Binder;
import android.os.Environment;
import android.provider.Settings;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.XmlUtils;
import libcore.io.IoUtils;
import libcore.util.EmptyArray;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * This class is a part of the package manager service that is responsible
 * for managing data associated with ephemeral apps such as cached uninstalled
 * ephemeral apps and ephemeral apps' cookies.
 */
class EphemeralApplicationRegistry {
    private static final boolean DEBUG = false;

    private static final boolean ENABLED = false;

    private static final String LOG_TAG = "EphemeralAppRegistry";

    private static final long DEFAULT_UNINSTALLED_EPHEMERAL_APP_CACHE_DURATION_MILLIS =
            DEBUG ? 60 * 1000L /* one min */ : 30 * 24 * 60 * 60 * 1000L; /* one month */

    private final static char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();

    private static final String EPHEMERAL_APPS_FOLDER = "ephemeral";
    private static final String EPHEMERAL_APP_ICON_FILE = "icon.png";
    private static final String EPHEMERAL_APP_COOKIE_FILE_PREFIX = "cookie_";
    private static final String EPHEMERAL_APP_COOKIE_FILE_SIFFIX = ".dat";
    private static final String EPHEMERAL_APP_METADATA_FILE = "metadata.xml";

    private static final String TAG_PACKAGE = "package";
    private static final String TAG_PERMS = "perms";
    private static final String TAG_PERM = "perm";

    private static final String ATTR_LABEL = "label";
    private static final String ATTR_NAME = "name";
    private static final String ATTR_GRANTED = "granted";

    private final PackageManagerService mService;

    @GuardedBy("mService.mPackages")
    private SparseArray<List<UninstalledEphemeralAppState>> mUninstalledEphemeralApps;

    public EphemeralApplicationRegistry(PackageManagerService service) {
        mService = service;
    }

    public byte[] getEphemeralApplicationCookieLPw(String packageName, int userId) {
        if (!ENABLED) {
            return EmptyArray.BYTE;
        }
        pruneUninstalledEphemeralAppsLPw(userId);

        File cookieFile = peekEphemeralCookieFile(packageName, userId);
        if (cookieFile != null && cookieFile.exists()) {
            try {
                return IoUtils.readFileAsByteArray(cookieFile.toString());
            } catch (IOException e) {
                Slog.w(LOG_TAG, "Error reading cookie file: " + cookieFile);
            }
        }
        return null;
    }

    public boolean setEphemeralApplicationCookieLPw(String packageName,
            byte[] cookie, int userId) {
        if (!ENABLED) {
            return false;
        }
        pruneUninstalledEphemeralAppsLPw(userId);

        PackageParser.Package pkg = mService.mPackages.get(packageName);
        if (pkg == null) {
            return false;
        }

        if (!isValidCookie(mService.mContext, cookie)) {
            return false;
        }

        File appDir = getEphemeralApplicationDir(pkg.packageName, userId);
        if (!appDir.exists() && !appDir.mkdirs()) {
            return false;
        }

        File cookieFile = computeEphemeralCookieFile(pkg, userId);
        if (cookieFile.exists() && !cookieFile.delete()) {
            return false;
        }

        try (FileOutputStream fos = new FileOutputStream(cookieFile)) {
            fos.write(cookie, 0, cookie.length);
        } catch (IOException e) {
            Slog.w(LOG_TAG, "Error writing cookie file: " + cookieFile);
            return false;
        }
        return true;
    }

    public Bitmap getEphemeralApplicationIconLPw(String packageName, int userId) {
        if (!ENABLED) {
            return null;
        }
        pruneUninstalledEphemeralAppsLPw(userId);

        File iconFile = new File(getEphemeralApplicationDir(packageName, userId),
                EPHEMERAL_APP_ICON_FILE);
        if (iconFile.exists()) {
            return BitmapFactory.decodeFile(iconFile.toString());
        }
        return null;
    }

    public List<EphemeralApplicationInfo> getEphemeralApplicationsLPw(int userId) {
        if (!ENABLED) {
            return Collections.emptyList();
        }
        pruneUninstalledEphemeralAppsLPw(userId);

        List<EphemeralApplicationInfo> result = getInstalledEphemeralApplicationsLPr(userId);
        result.addAll(getUninstalledEphemeralApplicationsLPr(userId));
        return result;
    }

    public void onPackageInstalledLPw(PackageParser.Package pkg) {
        if (!ENABLED) {
            return;
        }
        PackageSetting ps = (PackageSetting) pkg.mExtras;
        if (ps == null) {
            return;
        }
        for (int userId : UserManagerService.getInstance().getUserIds()) {
            pruneUninstalledEphemeralAppsLPw(userId);

            // Ignore not installed apps
            if (mService.mPackages.get(pkg.packageName) == null || !ps.getInstalled(userId)) {
                continue;
            }

            // Propagate permissions before removing any state
            propagateEphemeralAppPermissionsIfNeeded(pkg, userId);

            // Remove the in-memory state
            if (mUninstalledEphemeralApps != null) {
                List<UninstalledEphemeralAppState> uninstalledAppStates =
                        mUninstalledEphemeralApps.get(userId);
                if (uninstalledAppStates != null) {
                    final int appCount = uninstalledAppStates.size();
                    for (int i = 0; i < appCount; i++) {
                        UninstalledEphemeralAppState uninstalledAppState =
                                uninstalledAppStates.get(i);
                        if (uninstalledAppState.mEphemeralApplicationInfo
                                .getPackageName().equals(pkg.packageName)) {
                            uninstalledAppStates.remove(i);
                            break;
                        }
                    }
                }
            }

            // Remove the on-disk state except the cookie
            File ephemeralAppDir = getEphemeralApplicationDir(pkg.packageName, userId);
            new File(ephemeralAppDir, EPHEMERAL_APP_METADATA_FILE).delete();
            new File(ephemeralAppDir, EPHEMERAL_APP_ICON_FILE).delete();

            // If app signature changed - wipe the cookie
            File currentCookieFile = peekEphemeralCookieFile(pkg.packageName, userId);
            if (currentCookieFile == null) {
                continue;
            }
            File expectedCookeFile = computeEphemeralCookieFile(pkg, userId);
            if (!currentCookieFile.equals(expectedCookeFile)) {
                Slog.i(LOG_TAG, "Signature for package " + pkg.packageName
                        + " changed - dropping cookie");
                currentCookieFile.delete();
            }
        }
    }

    public void onPackageUninstalledLPw(PackageParser.Package pkg) {
        if (!ENABLED) {
            return;
        }
        if (pkg == null) {
            return;
        }
        PackageSetting ps = (PackageSetting) pkg.mExtras;
        if (ps == null) {
            return;
        }
        for (int userId : UserManagerService.getInstance().getUserIds()) {
            pruneUninstalledEphemeralAppsLPw(userId);

            if (mService.mPackages.get(pkg.packageName) != null && ps.getInstalled(userId)) {
                continue;
            }

            if (pkg.applicationInfo.isEphemeralApp()) {
                // Add a record for an uninstalled ephemeral app
                addUninstalledEphemeralAppLPw(pkg, userId);
            } else {
                // Deleting an app prunes all ephemeral state such as cookie
                deleteDir(getEphemeralApplicationDir(pkg.packageName, userId));
            }
        }
    }

    public void onUserRemovedLPw(int userId) {
        if (!ENABLED) {
            return;
        }
        if (mUninstalledEphemeralApps != null) {
            mUninstalledEphemeralApps.remove(userId);
        }
        deleteDir(getEphemeralApplicationsDir(userId));
    }

    private void addUninstalledEphemeralAppLPw(PackageParser.Package pkg, int userId) {
        EphemeralApplicationInfo uninstalledApp = createEphemeralAppInfoForPackage(pkg, userId);
        if (uninstalledApp == null) {
            return;
        }
        if (mUninstalledEphemeralApps == null) {
            mUninstalledEphemeralApps = new SparseArray<>();
        }
        List<UninstalledEphemeralAppState> uninstalledAppStates =
                mUninstalledEphemeralApps.get(userId);
        if (uninstalledAppStates == null) {
            uninstalledAppStates = new ArrayList<>();
            mUninstalledEphemeralApps.put(userId, uninstalledAppStates);
        }
        UninstalledEphemeralAppState uninstalledAppState = new UninstalledEphemeralAppState(
                uninstalledApp, System.currentTimeMillis());
        uninstalledAppStates.add(uninstalledAppState);

        writeUninstalledEphemeralAppMetadata(uninstalledApp, userId);
        writeEphemeralApplicationIconLPw(pkg, userId);
    }

    private void writeEphemeralApplicationIconLPw(PackageParser.Package pkg, int userId) {
        File appDir = getEphemeralApplicationDir(pkg.packageName, userId);
        if (!appDir.exists()) {
            return;
        }

        Drawable icon = pkg.applicationInfo.loadIcon(mService.mContext.getPackageManager());

        final Bitmap bitmap;
        if (icon instanceof BitmapDrawable) {
            bitmap = ((BitmapDrawable) icon).getBitmap();
        } else  {
            bitmap = Bitmap.createBitmap(icon.getIntrinsicWidth(),
                    icon.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            icon.draw(canvas);
        }

        File iconFile = new File(getEphemeralApplicationDir(pkg.packageName, userId),
                EPHEMERAL_APP_ICON_FILE);

        try (FileOutputStream out = new FileOutputStream(iconFile)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception e) {
            Slog.e(LOG_TAG, "Error writing ephemeral app icon", e);
        }
    }

    private void pruneUninstalledEphemeralAppsLPw(int userId) {
        final long maxCacheDurationMillis = Settings.Global.getLong(
                mService.mContext.getContentResolver(),
                Settings.Global.UNINSTALLED_EPHEMERAL_APP_CACHE_DURATION_MILLIS,
                DEFAULT_UNINSTALLED_EPHEMERAL_APP_CACHE_DURATION_MILLIS);

        // Prune in-memory state
        if (mUninstalledEphemeralApps != null) {
            List<UninstalledEphemeralAppState> uninstalledAppStates =
                    mUninstalledEphemeralApps.get(userId);
            if (uninstalledAppStates != null) {
                final int appCount = uninstalledAppStates.size();
                for (int j = appCount - 1; j >= 0; j--) {
                    UninstalledEphemeralAppState uninstalledAppState = uninstalledAppStates.get(j);
                    final long elapsedCachingMillis = System.currentTimeMillis()
                            - uninstalledAppState.mTimestamp;
                    if (elapsedCachingMillis > maxCacheDurationMillis) {
                        uninstalledAppStates.remove(j);
                    }
                }
                if (uninstalledAppStates.isEmpty()) {
                    mUninstalledEphemeralApps.remove(userId);
                }
            }
        }

        // Prune on-disk state
        File ephemeralAppsDir = getEphemeralApplicationsDir(userId);
        if (!ephemeralAppsDir.exists()) {
            return;
        }
        File[] files = ephemeralAppsDir.listFiles();
        if (files == null) {
            return;
        }
        for (File ephemeralDir : files) {
            if (!ephemeralDir.isDirectory()) {
                continue;
            }

            File metadataFile = new File(ephemeralDir, EPHEMERAL_APP_METADATA_FILE);
            if (!metadataFile.exists()) {
                continue;
            }

            final long elapsedCachingMillis = System.currentTimeMillis()
                    - metadataFile.lastModified();
            if (elapsedCachingMillis > maxCacheDurationMillis) {
                deleteDir(ephemeralDir);
            }
        }
    }

    private List<EphemeralApplicationInfo> getInstalledEphemeralApplicationsLPr(int userId) {
        List<EphemeralApplicationInfo> result = null;

        final int packageCount = mService.mPackages.size();
        for (int i = 0; i < packageCount; i++) {
            PackageParser.Package pkg = mService.mPackages.valueAt(i);
            if (!pkg.applicationInfo.isEphemeralApp()) {
                continue;
            }
            EphemeralApplicationInfo info = createEphemeralAppInfoForPackage(pkg, userId);
            if (info == null) {
                continue;
            }
            if (result == null) {
                result = new ArrayList<>();
            }
            result.add(info);
        }

        return result;
    }

    private EphemeralApplicationInfo createEphemeralAppInfoForPackage(
            PackageParser.Package pkg, int userId) {
        PackageSetting ps = (PackageSetting) pkg.mExtras;
        if (ps == null) {
            return null;
        }
        PackageUserState userState = ps.readUserState(userId);
        if (userState == null || !userState.installed || userState.hidden) {
            return null;
        }

        String[] requestedPermissions = new String[pkg.requestedPermissions.size()];
        pkg.requestedPermissions.toArray(requestedPermissions);

        Set<String> permissions = ps.getPermissionsState().getPermissions(userId);
        String[] grantedPermissions = new String[permissions.size()];
        permissions.toArray(grantedPermissions);

        return new EphemeralApplicationInfo(pkg.applicationInfo,
                requestedPermissions, grantedPermissions);
    }

    private List<EphemeralApplicationInfo> getUninstalledEphemeralApplicationsLPr(int userId) {
        List<UninstalledEphemeralAppState> uninstalledAppStates =
                getUninstalledEphemeralAppStatesLPr(userId);
        if (uninstalledAppStates == null || uninstalledAppStates.isEmpty()) {
            return Collections.emptyList();
        }

        List<EphemeralApplicationInfo> uninstalledApps = new ArrayList<>();
        final int stateCount = uninstalledAppStates.size();
        for (int i = 0; i < stateCount; i++) {
            UninstalledEphemeralAppState uninstalledAppState = uninstalledAppStates.get(i);
            uninstalledApps.add(uninstalledAppState.mEphemeralApplicationInfo);
        }
        return uninstalledApps;
    }

    private void propagateEphemeralAppPermissionsIfNeeded(PackageParser.Package pkg, int userId) {
        EphemeralApplicationInfo appInfo = getOrParseUninstalledEphemeralAppInfo(pkg.packageName, userId);
        if (appInfo == null) {
            return;
        }
        if (ArrayUtils.isEmpty(appInfo.getGrantedPermissions())) {
            return;
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            for (String grantedPermission : appInfo.getGrantedPermissions()) {
                mService.grantRuntimePermission(pkg.packageName, grantedPermission, userId);
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private EphemeralApplicationInfo getOrParseUninstalledEphemeralAppInfo(String packageName,
            int userId) {
        if (mUninstalledEphemeralApps != null) {
            List<UninstalledEphemeralAppState> uninstalledAppStates =
                    mUninstalledEphemeralApps.get(userId);
            if (uninstalledAppStates != null) {
                final int appCount = uninstalledAppStates.size();
                for (int i = 0; i < appCount; i++) {
                    UninstalledEphemeralAppState uninstalledAppState = uninstalledAppStates.get(i);
                    if (uninstalledAppState.mEphemeralApplicationInfo
                            .getPackageName().equals(packageName)) {
                        return uninstalledAppState.mEphemeralApplicationInfo;
                    }
                }
            }
        }

        File metadataFile = new File(getEphemeralApplicationDir(packageName, userId),
                EPHEMERAL_APP_METADATA_FILE);
        UninstalledEphemeralAppState uninstalledAppState = parseMetadataFile(metadataFile);
        if (uninstalledAppState == null) {
            return null;
        }

        return uninstalledAppState.mEphemeralApplicationInfo;
    }

    private List<UninstalledEphemeralAppState> getUninstalledEphemeralAppStatesLPr(int userId) {
        List<UninstalledEphemeralAppState> uninstalledAppStates = null;
        if (mUninstalledEphemeralApps != null) {
            uninstalledAppStates = mUninstalledEphemeralApps.get(userId);
            if (uninstalledAppStates != null) {
                return uninstalledAppStates;
            }
        }

        File ephemeralAppsDir = getEphemeralApplicationsDir(userId);
        if (ephemeralAppsDir.exists()) {
            File[] files = ephemeralAppsDir.listFiles();
            if (files != null) {
                for (File ephemeralDir : files) {
                    if (!ephemeralDir.isDirectory()) {
                        continue;
                    }
                    File metadataFile = new File(ephemeralDir,
                            EPHEMERAL_APP_METADATA_FILE);
                    UninstalledEphemeralAppState uninstalledAppState =
                            parseMetadataFile(metadataFile);
                    if (uninstalledAppState == null) {
                        continue;
                    }
                    if (uninstalledAppStates == null) {
                        uninstalledAppStates = new ArrayList<>();
                    }
                    uninstalledAppStates.add(uninstalledAppState);
                }
            }
        }

        if (uninstalledAppStates != null) {
            if (mUninstalledEphemeralApps == null) {
                mUninstalledEphemeralApps = new SparseArray<>();
            }
            mUninstalledEphemeralApps.put(userId, uninstalledAppStates);
        }

        return uninstalledAppStates;
    }

    private static boolean isValidCookie(Context context, byte[] cookie) {
        if (ArrayUtils.isEmpty(cookie)) {
            return true;
        }
        return cookie.length <= context.getPackageManager().getEphemeralCookieMaxSizeBytes();
    }

    private static UninstalledEphemeralAppState parseMetadataFile(File metadataFile) {
        if (!metadataFile.exists()) {
            return null;
        }
        FileInputStream in;
        try {
            in = new AtomicFile(metadataFile).openRead();
        } catch (FileNotFoundException fnfe) {
            Slog.i(LOG_TAG, "No ephemeral metadata file");
            return null;
        }

        final File ephemeralDir = metadataFile.getParentFile();
        final long timestamp = metadataFile.lastModified();
        final String packageName = ephemeralDir.getName();

        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, StandardCharsets.UTF_8.name());
            return new UninstalledEphemeralAppState(
                    parseMetadata(parser, packageName), timestamp);
        } catch (XmlPullParserException | IOException e) {
            throw new IllegalStateException("Failed parsing ephemeral"
                    + " metadata file: " + metadataFile, e);
        } finally {
            IoUtils.closeQuietly(in);
        }
    }

    private static File computeEphemeralCookieFile(PackageParser.Package pkg, int userId) {
        File appDir = getEphemeralApplicationDir(pkg.packageName, userId);
        String cookieFile = EPHEMERAL_APP_COOKIE_FILE_PREFIX + computePackageCertDigest(pkg)
                + EPHEMERAL_APP_COOKIE_FILE_SIFFIX;
        return new File(appDir, cookieFile);
    }

    private static File peekEphemeralCookieFile(String packageName, int userId) {
        File appDir = getEphemeralApplicationDir(packageName, userId);
        if (!appDir.exists()) {
            return null;
        }
        for (File file : appDir.listFiles()) {
            if (!file.isDirectory()
                    && file.getName().startsWith(EPHEMERAL_APP_COOKIE_FILE_PREFIX)
                    && file.getName().endsWith(EPHEMERAL_APP_COOKIE_FILE_SIFFIX)) {
                return file;
            }
        }
        return null;
    }

    private static EphemeralApplicationInfo parseMetadata(XmlPullParser parser, String packageName)
            throws IOException, XmlPullParserException {
        final int outerDepth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, outerDepth)) {
            if (TAG_PACKAGE.equals(parser.getName())) {
                return parsePackage(parser, packageName);
            }
        }
        return null;
    }

    private static EphemeralApplicationInfo parsePackage(XmlPullParser parser, String packageName)
            throws IOException, XmlPullParserException {
        String label = parser.getAttributeValue(null, ATTR_LABEL);

        List<String> outRequestedPermissions = new ArrayList<>();
        List<String> outGrantedPermissions = new ArrayList<>();

        final int outerDepth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, outerDepth)) {
            if (TAG_PERMS.equals(parser.getName())) {
                parsePermissions(parser, outRequestedPermissions, outGrantedPermissions);
            }
        }

        String[] requestedPermissions = new String[outRequestedPermissions.size()];
        outRequestedPermissions.toArray(requestedPermissions);

        String[] grantedPermissions = new String[outGrantedPermissions.size()];
        outGrantedPermissions.toArray(grantedPermissions);

        return new EphemeralApplicationInfo(packageName, label,
                requestedPermissions, grantedPermissions);
    }

    private static void parsePermissions(XmlPullParser parser, List<String> outRequestedPermissions,
            List<String> outGrantedPermissions) throws IOException, XmlPullParserException {
        final int outerDepth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser,outerDepth)) {
            if (TAG_PERM.equals(parser.getName())) {
                String permission = XmlUtils.readStringAttribute(parser, ATTR_NAME);
                outRequestedPermissions.add(permission);
                if (XmlUtils.readBooleanAttribute(parser, ATTR_GRANTED)) {
                    outGrantedPermissions.add(permission);
                }
            }
        }
    }

    private void writeUninstalledEphemeralAppMetadata(
            EphemeralApplicationInfo ephemeralApp, int userId) {
        File appDir = getEphemeralApplicationDir(ephemeralApp.getPackageName(), userId);
        if (!appDir.exists() && !appDir.mkdirs()) {
            return;
        }

        File metadataFile = new File(appDir, EPHEMERAL_APP_METADATA_FILE);

        AtomicFile destination = new AtomicFile(metadataFile);
        FileOutputStream out = null;
        try {
            out = destination.startWrite();

            XmlSerializer serializer = Xml.newSerializer();
            serializer.setOutput(out, StandardCharsets.UTF_8.name());
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

            serializer.startDocument(null, true);

            serializer.startTag(null, TAG_PACKAGE);
            serializer.attribute(null, ATTR_LABEL, ephemeralApp.loadLabel(
                    mService.mContext.getPackageManager()).toString());

            serializer.startTag(null, TAG_PERMS);
            for (String permission : ephemeralApp.getRequestedPermissions()) {
                serializer.startTag(null, TAG_PERM);
                serializer.attribute(null, ATTR_NAME, permission);
                if (ArrayUtils.contains(ephemeralApp.getGrantedPermissions(), permission)) {
                    serializer.attribute(null, ATTR_GRANTED, String.valueOf(true));
                }
                serializer.endTag(null, TAG_PERM);
            }
            serializer.endTag(null, TAG_PERMS);

            serializer.endTag(null, TAG_PACKAGE);

            serializer.endDocument();
            destination.finishWrite(out);
        } catch (Throwable t) {
            Slog.wtf(LOG_TAG, "Failed to write ephemeral state, restoring backup", t);
            destination.failWrite(out);
        } finally {
            IoUtils.closeQuietly(out);
        }
    }

    private static String computePackageCertDigest(PackageParser.Package pkg) {
        MessageDigest messageDigest;
        try {
            messageDigest = MessageDigest.getInstance("SHA256");
        } catch (NoSuchAlgorithmException e) {
            /* can't happen */
            return null;
        }

        messageDigest.update(pkg.mSignatures[0].toByteArray());

        final byte[] digest = messageDigest.digest();
        final int digestLength = digest.length;
        final int charCount = 2 * digestLength;

        final char[] chars = new char[charCount];
        for (int i = 0; i < digestLength; i++) {
            final int byteHex = digest[i] & 0xFF;
            chars[i * 2] = HEX_ARRAY[byteHex >>> 4];
            chars[i * 2 + 1] = HEX_ARRAY[byteHex & 0x0F];
        }
        return new String(chars);
    }

    private static File getEphemeralApplicationsDir(int userId) {
        return new File(Environment.getUserSystemDirectory(userId),
                EPHEMERAL_APPS_FOLDER);
    }

    private static File getEphemeralApplicationDir(String packageName, int userId) {
        return new File (getEphemeralApplicationsDir(userId), packageName);
    }

    private static void deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : dir.listFiles()) {
                deleteDir(file);
            }
        }
        dir.delete();
    }

    private static final class UninstalledEphemeralAppState {
        final EphemeralApplicationInfo mEphemeralApplicationInfo;
        final long mTimestamp;

        public UninstalledEphemeralAppState(EphemeralApplicationInfo ephemeralApp,
                long timestamp) {
            mEphemeralApplicationInfo = ephemeralApp;
            mTimestamp = timestamp;
        }
    }
}
