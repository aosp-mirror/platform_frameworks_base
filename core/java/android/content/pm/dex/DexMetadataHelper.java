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
import static android.content.pm.PackageParser.APK_FILE_EXTENSION;

import android.content.pm.PackageParser;
import android.content.pm.PackageParser.PackageLite;
import android.content.pm.PackageParser.PackageParserException;
import android.util.ArrayMap;
import android.util.jar.StrictJarFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Helper class used to compute and validate the location of dex metadata files.
 *
 * @hide
 */
public class DexMetadataHelper {
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
    public static Map<String, String> getPackageDexMetadata(PackageParser.Package pkg) {
        return buildPackageApkToDexMetadataMap(pkg.getAllCodePaths());
    }

    /**
     * Return the dex metadata files for the given package as a map
     * [code path -> dex metadata path].
     *
     * NOTE: involves I/O checks.
     */
    private static Map<String, String> getPackageDexMetadata(PackageLite pkg) {
        return buildPackageApkToDexMetadataMap(pkg.getAllCodePaths());
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
    private static Map<String, String> buildPackageApkToDexMetadataMap(
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
        if (!PackageParser.isApkPath(codePath)) {
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
        return PackageParser.isApkFile(targetFile)
                ? buildDexMetadataPathForApk(targetFile.getPath())
                : targetFile.getPath() + DEX_METADATA_FILE_EXTENSION;
    }

    /**
     * Validate the dex metadata files installed for the given package.
     *
     * @throws PackageParserException in case of errors.
     */
    public static void validatePackageDexMetadata(PackageParser.Package pkg)
            throws PackageParserException {
        Collection<String> apkToDexMetadataList = getPackageDexMetadata(pkg).values();
        for (String dexMetadata : apkToDexMetadataList) {
            validateDexMetadataFile(dexMetadata);
        }
    }

    /**
     * Validate that the given file is a dex metadata archive.
     * This is just a sanity validation that the file is a zip archive.
     *
     * @throws PackageParserException if the file is not a .dm file.
     */
    private static void validateDexMetadataFile(String dmaPath) throws PackageParserException {
        StrictJarFile jarFile = null;
        try {
            jarFile = new StrictJarFile(dmaPath, false, false);
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

    /**
     * Validates that all dex metadata paths in the given list have a matching apk.
     * (for any foo.dm there should be either a 'foo' of a 'foo.apk' file).
     * If that's not the case it throws {@code IllegalStateException}.
     *
     * This is used to perform a basic sanity check during adb install commands.
     * (The installer does not support stand alone .dm files)
     */
    public static void validateDexPaths(String[] paths) {
        ArrayList<String> apks = new ArrayList<>();
        for (int i = 0; i < paths.length; i++) {
            if (PackageParser.isApkPath(paths[i])) {
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
