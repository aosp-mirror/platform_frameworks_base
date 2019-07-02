/*
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

package com.android.server.pm.dex;

import android.util.AtomicFile;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FastPrintWriter;
import com.android.server.pm.AbstractStatsBase;

import libcore.io.IoUtils;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stats file which stores information about secondary code files that are dynamically loaded.
 */
class PackageDynamicCodeLoading extends AbstractStatsBase<Void> {
    // Type code to indicate a secondary file containing DEX code. (The char value is how it
    // is represented in the text file format.)
    static final int FILE_TYPE_DEX = 'D';

    // Type code to indicate a secondary file containing native code.
    static final int FILE_TYPE_NATIVE = 'N';

    private static final String TAG = "PackageDynamicCodeLoading";

    private static final String FILE_VERSION_HEADER = "DCL1";
    private static final String PACKAGE_PREFIX = "P:";

    private static final char FIELD_SEPARATOR = ':';
    private static final String PACKAGE_SEPARATOR = ",";

    /**
     * Limit on how many files we store for a single owner, to avoid one app causing
     * unbounded memory consumption.
     */
    @VisibleForTesting
    static final int MAX_FILES_PER_OWNER = 100;

    /**
     * Regular expression to match the expected format of an input line describing one file.
     * <p>Example: {@code D:10:package.name1,package.name2:/escaped/path}
     * <p>The capturing groups are the file type, user ID, loading packages and escaped file path
     * (in that order).
     * <p>See {@link #write(OutputStream, Map)} below for more details of the format.
     */
    private static final Pattern PACKAGE_LINE_PATTERN =
            Pattern.compile("([A-Z]):([0-9]+):([^:]*):(.*)");

    private final Object mLock = new Object();

    // Map from package name to data about loading of dynamic code files owned by that package.
    // (Apps may load code files owned by other packages, subject to various access
    // constraints.)
    // Any PackageDynamicCode in this map will be non-empty.
    @GuardedBy("mLock")
    private Map<String, PackageDynamicCode> mPackageMap = new HashMap<>();

    PackageDynamicCodeLoading() {
        super("package-dcl.list", "PackageDynamicCodeLoading_DiskWriter", false);
    }

    /**
     * Record dynamic code loading from a file.
     *
     * Note this is called when an app loads dex files and as such it should return
     * as fast as possible.
     *
     * @param owningPackageName the package owning the file path
     * @param filePath the path of the dex files being loaded
     * @param fileType the type of code loading
     * @param ownerUserId the user id which runs the code loading the file
     * @param loadingPackageName the package performing the load
     * @return whether new information has been recorded
     * @throws IllegalArgumentException if clearly invalid information is detected
     */
    boolean record(String owningPackageName, String filePath, int fileType, int ownerUserId,
            String loadingPackageName) {
        if (!isValidFileType(fileType)) {
            throw new IllegalArgumentException("Bad file type: " + fileType);
        }
        synchronized (mLock) {
            PackageDynamicCode packageInfo = mPackageMap.get(owningPackageName);
            if (packageInfo == null) {
                packageInfo = new PackageDynamicCode();
                mPackageMap.put(owningPackageName, packageInfo);
            }
            return packageInfo.add(filePath, (char) fileType, ownerUserId, loadingPackageName);
        }
    }

    private static boolean isValidFileType(int fileType) {
        return fileType == FILE_TYPE_DEX || fileType == FILE_TYPE_NATIVE;
    }

    /**
     * Return all packages that contain records of secondary dex files. (Note that data updates
     * asynchronously, so {@link #getPackageDynamicCodeInfo} may still return null if passed
     * one of these package names.)
     */
    Set<String> getAllPackagesWithDynamicCodeLoading() {
        synchronized (mLock) {
            return new HashSet<>(mPackageMap.keySet());
        }
    }

    /**
     * Return information about the dynamic code file usage of the specified package,
     * or null if there is currently no usage information. The object returned is a copy of the
     * live information that is not updated.
     */
    PackageDynamicCode getPackageDynamicCodeInfo(String packageName) {
        synchronized (mLock) {
            PackageDynamicCode info = mPackageMap.get(packageName);
            return info == null ? null : new PackageDynamicCode(info);
        }
    }

    /**
     * Remove all information about all packages.
     */
    void clear() {
        synchronized (mLock) {
            mPackageMap.clear();
        }
    }

    /**
     * Remove the data associated with package {@code packageName}. Affects all users.
     * @return true if the package usage was found and removed successfully
     */
    boolean removePackage(String packageName) {
        synchronized (mLock) {
            return mPackageMap.remove(packageName) != null;
        }
    }

    /**
     * Remove all the records about package {@code packageName} belonging to user {@code userId}.
     * @return whether any data was actually removed
     */
    boolean removeUserPackage(String packageName, int userId) {
        synchronized (mLock) {
            PackageDynamicCode packageDynamicCode = mPackageMap.get(packageName);
            if (packageDynamicCode == null) {
                return false;
            }
            if (packageDynamicCode.removeUser(userId)) {
                if (packageDynamicCode.mFileUsageMap.isEmpty()) {
                    mPackageMap.remove(packageName);
                }
                return true;
            } else {
                return false;
            }
        }
    }

    /**
     * Remove the specified dynamic code file record belonging to the package {@code packageName}
     * and user {@code userId}.
     * @return whether data was actually removed
     */
    boolean removeFile(String packageName, String filePath, int userId) {
        synchronized (mLock) {
            PackageDynamicCode packageDynamicCode = mPackageMap.get(packageName);
            if (packageDynamicCode == null) {
                return false;
            }
            if (packageDynamicCode.removeFile(filePath, userId)) {
                if (packageDynamicCode.mFileUsageMap.isEmpty()) {
                    mPackageMap.remove(packageName);
                }
                return true;
            } else {
                return false;
            }
        }
    }

    /**
     * Syncs data with the set of installed packages. Data about packages that are no longer
     * installed is removed.
     * @param packageToUsersMap a map from all existing package names to the users who have the
     *                          package installed
     */
    void syncData(Map<String, Set<Integer>> packageToUsersMap) {
        synchronized (mLock) {
            Iterator<Entry<String, PackageDynamicCode>> it = mPackageMap.entrySet().iterator();
            while (it.hasNext()) {
                Entry<String, PackageDynamicCode> entry = it.next();
                Set<Integer> packageUsers = packageToUsersMap.get(entry.getKey());
                if (packageUsers == null) {
                    it.remove();
                } else {
                    PackageDynamicCode packageDynamicCode = entry.getValue();
                    packageDynamicCode.syncData(packageToUsersMap, packageUsers);
                    if (packageDynamicCode.mFileUsageMap.isEmpty()) {
                        it.remove();
                    }
                }
            }
        }
    }

    /**
     * Request that data be written to persistent file at the next time allowed by write-limiting.
     */
    void maybeWriteAsync() {
        super.maybeWriteAsync(null);
    }

    /**
     * Writes data to persistent file immediately.
     */
    void writeNow() {
        super.writeNow(null);
    }

    @Override
    protected final void writeInternal(Void data) {
        AtomicFile file = getFile();
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            write(output);
            file.finishWrite(output);
        } catch (IOException e) {
            file.failWrite(output);
            Slog.e(TAG, "Failed to write dynamic usage for secondary code files.", e);
        }
    }

    @VisibleForTesting
    void write(OutputStream output) throws IOException {
        // Make a deep copy to avoid holding the lock while writing to disk.
        Map<String, PackageDynamicCode> copiedMap;
        synchronized (mLock) {
            copiedMap = new HashMap<>(mPackageMap.size());
            for (Entry<String, PackageDynamicCode> entry : mPackageMap.entrySet()) {
                PackageDynamicCode copiedValue = new PackageDynamicCode(entry.getValue());
                copiedMap.put(entry.getKey(), copiedValue);
            }
        }

        write(output, copiedMap);
    }

    /**
     * Write the dynamic code loading data as a text file to {@code output}. The file format begins
     * with a line indicating the file type and version - {@link #FILE_VERSION_HEADER}.
     * <p>There is then one section for each owning package, introduced by a line beginning "P:".
     * This is followed by a line for each file owned by the package this is dynamically loaded,
     * containing the file type, user ID, loading package names and full path (with newlines and
     * backslashes escaped - see {@link #escape}).
     * <p>For example:
     * <pre>{@code
     * DCL1
     * P:first.owning.package
     * D:0:loading.package_1,loading.package_2:/path/to/file
     * D:10:loading.package_1:/another/file
     * P:second.owning.package
     * D:0:loading.package:/third/file
     * }</pre>
     */
    private static void write(OutputStream output, Map<String, PackageDynamicCode> packageMap)
            throws IOException {
        PrintWriter writer = new FastPrintWriter(output);

        writer.println(FILE_VERSION_HEADER);
        for (Entry<String, PackageDynamicCode> packageEntry : packageMap.entrySet()) {
            writer.print(PACKAGE_PREFIX);
            writer.println(packageEntry.getKey());

            Map<String, DynamicCodeFile> mFileUsageMap = packageEntry.getValue().mFileUsageMap;
            for (Entry<String, DynamicCodeFile> fileEntry : mFileUsageMap.entrySet()) {
                String path = fileEntry.getKey();
                DynamicCodeFile dynamicCodeFile = fileEntry.getValue();

                writer.print(dynamicCodeFile.mFileType);
                writer.print(FIELD_SEPARATOR);
                writer.print(dynamicCodeFile.mUserId);
                writer.print(FIELD_SEPARATOR);

                String prefix = "";
                for (String packageName : dynamicCodeFile.mLoadingPackages) {
                    writer.print(prefix);
                    writer.print(packageName);
                    prefix = PACKAGE_SEPARATOR;
                }

                writer.print(FIELD_SEPARATOR);
                writer.println(escape(path));
            }
        }

        writer.flush();
        if (writer.checkError()) {
            throw new IOException("Writer failed");
        }
    }

    /**
     * Read data from the persistent file. Replaces existing data completely if successful.
     */
    void read() {
        super.read(null);
    }

    @Override
    protected final void readInternal(Void data) {
        AtomicFile file = getFile();

        FileInputStream stream = null;
        try {
            stream = file.openRead();
            read(stream);
        } catch (FileNotFoundException expected) {
            // The file may not be there. E.g. When we first take the OTA with this feature.
        } catch (IOException e) {
            Slog.w(TAG, "Failed to parse dynamic usage for secondary code files.", e);
        } finally {
            IoUtils.closeQuietly(stream);
        }
    }

    @VisibleForTesting
    void read(InputStream stream) throws IOException {
        Map<String, PackageDynamicCode> newPackageMap = new HashMap<>();
        read(stream, newPackageMap);
        synchronized (mLock) {
            mPackageMap = newPackageMap;
        }
    }

    private static void read(InputStream stream, Map<String, PackageDynamicCode> packageMap)
            throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream));

        String versionLine = reader.readLine();
        if (!FILE_VERSION_HEADER.equals(versionLine)) {
            throw new IOException("Incorrect version line: " + versionLine);
        }

        String line = reader.readLine();
        if (line != null && !line.startsWith(PACKAGE_PREFIX)) {
            throw new IOException("Malformed line: " + line);
        }

        while (line != null) {
            String packageName = line.substring(PACKAGE_PREFIX.length());

            PackageDynamicCode packageInfo = new PackageDynamicCode();
            while (true) {
                line = reader.readLine();
                if (line == null || line.startsWith(PACKAGE_PREFIX)) {
                    break;
                }
                readFileInfo(line, packageInfo);
            }

            if (!packageInfo.mFileUsageMap.isEmpty()) {
                packageMap.put(packageName, packageInfo);
            }
        }
    }

    private static void readFileInfo(String line, PackageDynamicCode output) throws IOException {
        try {
            Matcher matcher = PACKAGE_LINE_PATTERN.matcher(line);
            if (!matcher.matches()) {
                throw new IOException("Malformed line: " + line);
            }

            char type = matcher.group(1).charAt(0);
            int user = Integer.parseInt(matcher.group(2));
            String[] packages = matcher.group(3).split(PACKAGE_SEPARATOR);
            String path = unescape(matcher.group(4));

            if (packages.length == 0) {
                throw new IOException("Malformed line: " + line);
            }
            if (!isValidFileType(type)) {
                throw new IOException("Unknown file type: " + line);
            }

            output.mFileUsageMap.put(path, new DynamicCodeFile(type, user, packages));
        } catch (RuntimeException e) {
            // Just in case we get NumberFormatException, or various
            // impossible out of bounds errors happen.
            throw new IOException("Unable to parse line: " + line, e);
        }
    }

    /**
     * Escape any newline and backslash characters in path. A newline in a path is legal if unusual,
     * and it would break our line-based file parsing.
     */
    @VisibleForTesting
    static String escape(String path) {
        if (path.indexOf('\\') == -1 && path.indexOf('\n') == -1 && path.indexOf('\r') == -1) {
            return path;
        }

        StringBuilder result = new StringBuilder(path.length() + 10);
        for (int i = 0; i < path.length(); i++) {
            // Surrogates will never match the characters we care about, so it's ok to use chars
            // not code points here.
            char c = path.charAt(i);
            switch (c) {
                case '\\':
                    result.append("\\\\");
                    break;
                case '\n':
                    result.append("\\n");
                    break;
                case '\r':
                    result.append("\\r");
                    break;
                default:
                    result.append(c);
                    break;
            }
        }
        return result.toString();
    }

    /**
     * Reverse the effect of {@link #escape}.
     * @throws IOException if the input string is malformed
     */
    @VisibleForTesting
    static String unescape(String escaped) throws IOException {
        // As we move through the input string, start is the position of the first character
        // after the previous escape sequence and finish is the position of the following backslash.
        int start = 0;
        int finish = escaped.indexOf('\\');
        if (finish == -1) {
            return escaped;
        }

        StringBuilder result = new StringBuilder(escaped.length());
        while (true) {
            if (finish >= escaped.length() - 1) {
                // Backslash mustn't be the last character
                throw new IOException("Unexpected \\ in: " + escaped);
            }
            result.append(escaped, start, finish);
            switch (escaped.charAt(finish + 1)) {
                case '\\':
                    result.append('\\');
                    break;
                case 'r':
                    result.append('\r');
                    break;
                case 'n':
                    result.append('\n');
                    break;
                default:
                    throw new IOException("Bad escape in: " + escaped);
            }

            start = finish + 2;
            finish = escaped.indexOf('\\', start);
            if (finish == -1) {
                result.append(escaped, start, escaped.length());
                break;
            }
        }
        return result.toString();
    }

    /**
     * Represents the dynamic code usage of a single package.
     */
    static class PackageDynamicCode {
        /**
         * Map from secondary code file path to information about which packages dynamically load
         * that file.
         */
        final Map<String, DynamicCodeFile> mFileUsageMap;

        private PackageDynamicCode() {
            mFileUsageMap = new HashMap<>();
        }

        private PackageDynamicCode(PackageDynamicCode original) {
            mFileUsageMap = new HashMap<>(original.mFileUsageMap.size());
            for (Entry<String, DynamicCodeFile> entry : original.mFileUsageMap.entrySet()) {
                DynamicCodeFile newValue = new DynamicCodeFile(entry.getValue());
                mFileUsageMap.put(entry.getKey(), newValue);
            }
        }

        private boolean add(String path, char fileType, int userId, String loadingPackage) {
            DynamicCodeFile fileInfo = mFileUsageMap.get(path);
            if (fileInfo == null) {
                if (mFileUsageMap.size() >= MAX_FILES_PER_OWNER) {
                    return false;
                }
                fileInfo = new DynamicCodeFile(fileType, userId, loadingPackage);
                mFileUsageMap.put(path, fileInfo);
                return true;
            } else {
                if (fileInfo.mUserId != userId) {
                    // This should be impossible: private app files are always user-specific and
                    // can't be accessed from different users.
                    throw new IllegalArgumentException("Cannot change userId for '" + path
                            + "' from " + fileInfo.mUserId + " to " + userId);
                }
                // Changing file type (i.e. loading the same file in different ways is possible if
                // unlikely. We allow it but ignore it.
                return fileInfo.mLoadingPackages.add(loadingPackage);
            }
        }

        private boolean removeUser(int userId) {
            boolean updated = false;
            Iterator<DynamicCodeFile> it = mFileUsageMap.values().iterator();
            while (it.hasNext()) {
                DynamicCodeFile fileInfo = it.next();
                if (fileInfo.mUserId == userId) {
                    it.remove();
                    updated = true;
                }
            }
            return updated;
        }

        private boolean removeFile(String filePath, int userId) {
            DynamicCodeFile fileInfo = mFileUsageMap.get(filePath);
            if (fileInfo == null || fileInfo.mUserId != userId) {
                return false;
            } else {
                mFileUsageMap.remove(filePath);
                return true;
            }
        }

        private void syncData(Map<String, Set<Integer>> packageToUsersMap,
                Set<Integer> owningPackageUsers) {
            Iterator<DynamicCodeFile> fileIt = mFileUsageMap.values().iterator();
            while (fileIt.hasNext()) {
                DynamicCodeFile fileInfo = fileIt.next();
                int fileUserId = fileInfo.mUserId;
                if (!owningPackageUsers.contains(fileUserId)) {
                    fileIt.remove();
                } else {
                    // Also remove information about any loading packages that are no longer
                    // installed for this user.
                    Iterator<String> loaderIt = fileInfo.mLoadingPackages.iterator();
                    while (loaderIt.hasNext()) {
                        String loader = loaderIt.next();
                        Set<Integer> loadingPackageUsers = packageToUsersMap.get(loader);
                        if (loadingPackageUsers == null
                                || !loadingPackageUsers.contains(fileUserId)) {
                            loaderIt.remove();
                        }
                    }
                    if (fileInfo.mLoadingPackages.isEmpty()) {
                        fileIt.remove();
                    }
                }
            }
        }
    }

    /**
     * Represents a single dynamic code file loaded by one or more packages. Note that it is
     * possible for one app to dynamically load code from a different app's home dir, if the
     * owning app:
     * <ul>
     *     <li>Targets API 27 or lower and has shared its home dir.
     *     <li>Is a system app.
     *     <li>Has a shared UID with the loading app.
     * </ul>
     */
    static class DynamicCodeFile {
        final char mFileType;
        final int mUserId;
        final Set<String> mLoadingPackages;

        private DynamicCodeFile(char type, int user, String... packages) {
            mFileType = type;
            mUserId = user;
            mLoadingPackages = new HashSet<>(Arrays.asList(packages));
        }

        private DynamicCodeFile(DynamicCodeFile original) {
            mFileType = original.mFileType;
            mUserId = original.mUserId;
            mLoadingPackages = new HashSet<>(original.mLoadingPackages);
        }
    }
}
