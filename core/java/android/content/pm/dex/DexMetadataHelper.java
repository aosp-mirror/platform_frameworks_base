/**
 * Copyright 2018 The Android Open Source Project
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

package android.content.pm.dex;

import static android.content.pm.PackageManager.INSTALL_FAILED_BAD_DEX_METADATA;
import static android.content.pm.parsing.ApkLiteParseUtils.APK_FILE_EXTENSION;

import android.content.pm.PackageParser.PackageParserException;
import android.content.pm.parsing.ApkLiteParseUtils;
import android.content.pm.parsing.PackageLite;
import android.os.SystemProperties;
import android.util.ArrayMap;
import android.util.JsonReader;
import android.util.Log;
import android.util.jar.StrictJarFile;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.security.VerityUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;

/**
 * Helper class used to compute and validate the location of dex metadata files.
 *
 * @hide
 */
public class DexMetadataHelper {
    public static final String TAG = "DexMetadataHelper";
    /** $> adb shell 'setprop log.tag.DexMetadataHelper VERBOSE' */
    public static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    /** $> adb shell 'setprop pm.dexopt.dm.require_manifest true' */
    private static final String PROPERTY_DM_JSON_MANIFEST_REQUIRED =
            "pm.dexopt.dm.require_manifest";
    /** $> adb shell 'setprop pm.dexopt.dm.require_fsverity true' */
    private static final String PROPERTY_DM_FSVERITY_REQUIRED = "pm.dexopt.dm.require_fsverity";

    private static final String DEX_METADATA_FILE_EXTENSION = ".dm";

    private DexMetadataHelper() {}

    /** Return true if the given file is a dex metadata file. */
    public static boolean isDexMetadataFile(File file) {
        return isDexMetadataPath(file.getName());
    }

    /** Return true if the given path is a dex metadata path. */
    private static boolean isDexMetadataPath(String path) {
        return path.endsWith(DEX_METADATA_FILE_EXTENSION);
    }

    /**
     * Returns whether fs-verity is required to install a dex metadata
     */
    public static boolean isFsVerityRequired() {
        return VerityUtils.isFsVeritySupported()
                && SystemProperties.getBoolean(PROPERTY_DM_FSVERITY_REQUIRED, false);
    }

    /**
     * Return the size (in bytes) of all dex metadata files associated with the given package.
     */
    public static long getPackageDexMetadataSize(PackageLite pkg) {
        long sizeBytes = 0;
        Collection<String> dexMetadataList = DexMetadataHelper.getPackageDexMetadata(pkg).values();
        for (String dexMetadata : dexMetadataList) {
            sizeBytes += new File(dexMetadata).length();
        }
        return sizeBytes;
    }

    /**
     * Search for the dex metadata file associated with the given target file.
     * If it exists, the method returns the dex metadata file; otherwise it returns null.
     *
     * Note that this performs a loose matching suitable to be used in the InstallerSession logic.
     * i.e. the method will attempt to match the {@code dmFile} regardless of {@code targetFile}
     * extension (e.g. 'foo.dm' will match 'foo' or 'foo.apk').
     */
    public static File findDexMetadataForFile(File targetFile) {
        String dexMetadataPath = buildDexMetadataPathForFile(targetFile);
        File dexMetadataFile = new File(dexMetadataPath);
        return dexMetadataFile.exists() ? dexMetadataFile : null;
    }

    /**
     * Return the dex metadata files for the given package as a map
     * [code path -> dex metadata path].
     *
     * NOTE: involves I/O checks.
     */
    private static Map<String, String> getPackageDexMetadata(PackageLite pkg) {
        return buildPackageApkToDexMetadataMap(pkg.getAllApkPaths());
    }

    /**
     * Look up the dex metadata files for the given code paths building the map
     * [code path -> dex metadata].
     *
     * For each code path (.apk) the method checks if a matching dex metadata file (.dm) exists.
     * If it does it adds the pair to the returned map.
     *
     * Note that this method will do a loose
     * matching based on the extension ('foo.dm' will match 'foo.apk' or 'foo').
     *
     * This should only be used for code paths extracted from a package structure after the naming
     * was enforced in the installer.
     */
    public static Map<String, String> buildPackageApkToDexMetadataMap(
            List<String> codePaths) {
        ArrayMap<String, String> result = new ArrayMap<>();
        for (int i = codePaths.size() - 1; i >= 0; i--) {
            String codePath = codePaths.get(i);
            String dexMetadataPath = buildDexMetadataPathForFile(new File(codePath));

            if (Files.exists(Paths.get(dexMetadataPath))) {
                result.put(codePath, dexMetadataPath);
            }
        }

        return result;
    }

    /**
     * Return the dex metadata path associated with the given code path.
     * (replaces '.apk' extension with '.dm')
     *
     * @throws IllegalArgumentException if the code path is not an .apk.
     */
    public static String buildDexMetadataPathForApk(String codePath) {
        if (!ApkLiteParseUtils.isApkPath(codePath)) {
            throw new IllegalStateException(
                    "Corrupted package. Code path is not an apk " + codePath);
        }
        return codePath.substring(0, codePath.length() - APK_FILE_EXTENSION.length())
                + DEX_METADATA_FILE_EXTENSION;
    }

    /**
     * Return the dex metadata path corresponding to the given {@code targetFile} using a loose
     * matching.
     * i.e. the method will attempt to match the {@code dmFile} regardless of {@code targetFile}
     * extension (e.g. 'foo.dm' will match 'foo' or 'foo.apk').
     */
    private static String buildDexMetadataPathForFile(File targetFile) {
        return ApkLiteParseUtils.isApkFile(targetFile)
                ? buildDexMetadataPathForApk(targetFile.getPath())
                : targetFile.getPath() + DEX_METADATA_FILE_EXTENSION;
    }

    /**
     * Validate that the given file is a dex metadata archive.
     * This is just a validation that the file is a zip archive that contains a manifest.json
     * with the package name and version code.
     *
     * @throws PackageParserException if the file is not a .dm file.
     */
    public static void validateDexMetadataFile(String dmaPath, String packageName, long versionCode)
            throws PackageParserException {
        validateDexMetadataFile(dmaPath, packageName, versionCode,
               SystemProperties.getBoolean(PROPERTY_DM_JSON_MANIFEST_REQUIRED, false));
    }

    @VisibleForTesting
    public static void validateDexMetadataFile(String dmaPath, String packageName, long versionCode,
            boolean requireManifest) throws PackageParserException {
        StrictJarFile jarFile = null;

        if (DEBUG) {
            Log.v(TAG, "validateDexMetadataFile: " + dmaPath + ", " + packageName +
                    ", " + versionCode);
        }

        try {
            jarFile = new StrictJarFile(dmaPath, false, false);
            validateDexMetadataManifest(dmaPath, jarFile, packageName, versionCode,
                    requireManifest);
        } catch (IOException e) {
            throw new PackageParserException(INSTALL_FAILED_BAD_DEX_METADATA,
                    "Error opening " + dmaPath, e);
        } finally {
            if (jarFile != null) {
                try {
                    jarFile.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** Ensure that packageName and versionCode match the manifest.json in the .dm file */
    private static void validateDexMetadataManifest(String dmaPath, StrictJarFile jarFile,
            String packageName, long versionCode, boolean requireManifest)
            throws IOException, PackageParserException {
        if (!requireManifest) {
            if (DEBUG) {
                Log.v(TAG, "validateDexMetadataManifest: " + dmaPath
                        + " manifest.json check skipped");
            }
            return;
        }

        ZipEntry zipEntry = jarFile.findEntry("manifest.json");
        if (zipEntry == null) {
              throw new PackageParserException(INSTALL_FAILED_BAD_DEX_METADATA,
                      "Missing manifest.json in " + dmaPath);
        }
        InputStream inputStream = jarFile.getInputStream(zipEntry);

        JsonReader reader;
        try {
          reader = new JsonReader(new InputStreamReader(inputStream, "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            throw new PackageParserException(INSTALL_FAILED_BAD_DEX_METADATA,
                    "Error opening manifest.json in " + dmaPath, e);
        }
        String jsonPackageName = null;
        long jsonVersionCode = -1;

        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (name.equals("packageName")) {
                jsonPackageName = reader.nextString();
            } else if (name.equals("versionCode")) {
                jsonVersionCode = reader.nextLong();
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();

        if (jsonPackageName == null || jsonVersionCode == -1) {
            throw new PackageParserException(INSTALL_FAILED_BAD_DEX_METADATA,
                    "manifest.json in " + dmaPath
                    + " is missing 'packageName' and/or 'versionCode'");
        }

        if (!jsonPackageName.equals(packageName)) {
            throw new PackageParserException(INSTALL_FAILED_BAD_DEX_METADATA,
                    "manifest.json in " + dmaPath + " has invalid packageName: " + jsonPackageName
                    + ", expected: " + packageName);
        }

        if (versionCode != jsonVersionCode) {
            throw new PackageParserException(INSTALL_FAILED_BAD_DEX_METADATA,
                    "manifest.json in " + dmaPath + " has invalid versionCode: " + jsonVersionCode
                    + ", expected: " + versionCode);
        }

        if (DEBUG) {
            Log.v(TAG, "validateDexMetadataManifest: " + dmaPath + ", " + packageName +
                    ", " + versionCode + ": successful");
        }
    }

    /**
     * Validates that all dex metadata paths in the given list have a matching apk.
     * (for any foo.dm there should be either a 'foo' of a 'foo.apk' file).
     * If that's not the case it throws {@code IllegalStateException}.
     *
     * This is used to perform a basic check during adb install commands.
     * (The installer does not support stand alone .dm files)
     */
    public static void validateDexPaths(String[] paths) {
        ArrayList<String> apks = new ArrayList<>();
        for (int i = 0; i < paths.length; i++) {
            if (ApkLiteParseUtils.isApkPath(paths[i])) {
                apks.add(paths[i]);
            }
        }
        ArrayList<String> unmatchedDmFiles = new ArrayList<>();
        for (int i = 0; i < paths.length; i++) {
            String dmPath = paths[i];
            if (isDexMetadataPath(dmPath)) {
                boolean valid = false;
                for (int j = apks.size() - 1; j >= 0; j--) {
                    if (dmPath.equals(buildDexMetadataPathForFile(new File(apks.get(j))))) {
                        valid = true;
                        break;
                    }
                }
                if (!valid) {
                    unmatchedDmFiles.add(dmPath);
                }
            }
        }
        if (!unmatchedDmFiles.isEmpty()) {
            throw new IllegalStateException("Unmatched .dm files: " + unmatchedDmFiles);
        }
    }

}
