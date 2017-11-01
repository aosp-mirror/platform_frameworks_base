/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.test;

import android.util.Log;
import dalvik.system.DexFile;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Generate {@link ClassPathPackageInfo}s by scanning apk paths.
 *
 * {@hide} Not needed for 1.0 SDK.
 */
@Deprecated
public class ClassPathPackageInfoSource {

    private static final ClassLoader CLASS_LOADER
            = ClassPathPackageInfoSource.class.getClassLoader();

    private static String[] apkPaths;

    private static ClassPathPackageInfoSource classPathSource;

    private final SimpleCache<String, ClassPathPackageInfo> cache =
            new SimpleCache<String, ClassPathPackageInfo>() {
                @Override
                protected ClassPathPackageInfo load(String pkgName) {
                    return createPackageInfo(pkgName);
                }
            };

    // The class path of the running application
    private final String[] classPath;

    private final ClassLoader classLoader;

    private ClassPathPackageInfoSource(ClassLoader classLoader) {
        this.classLoader = classLoader;
        classPath = getClassPath();
    }

    static void setApkPaths(String[] apkPaths) {
        ClassPathPackageInfoSource.apkPaths = apkPaths;
    }

    public static ClassPathPackageInfoSource forClassPath(ClassLoader classLoader) {
        if (classPathSource == null) {
            classPathSource = new ClassPathPackageInfoSource(classLoader);
        }
        return classPathSource;
    }

    public Set<Class<?>> getTopLevelClassesRecursive(String packageName) {
        ClassPathPackageInfo packageInfo = cache.get(packageName);
        return packageInfo.getTopLevelClassesRecursive();
    }

    private ClassPathPackageInfo createPackageInfo(String packageName) {
        Set<String> subpackageNames = new TreeSet<String>();
        Set<String> classNames = new TreeSet<String>();
        Set<Class<?>> topLevelClasses = new HashSet<>();
        findClasses(packageName, classNames, subpackageNames);
        for (String className : classNames) {
            if (className.endsWith(".R") || className.endsWith(".Manifest")) {
                // Don't try to load classes that are generated. They usually aren't in test apks.
                continue;
            }

            try {
                // We get errors in the emulator if we don't use the caller's class loader.
                topLevelClasses.add(Class.forName(className, false,
                        (classLoader != null) ? classLoader : CLASS_LOADER));
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                // Should not happen unless there is a generated class that is not included in
                // the .apk.
                Log.w("ClassPathPackageInfoSource", "Cannot load class. "
                        + "Make sure it is in your apk. Class name: '" + className
                        + "'. Message: " + e.getMessage(), e);
            }
        }
        return new ClassPathPackageInfo(packageName, subpackageNames,
                topLevelClasses);
    }

    /**
     * Finds all classes and sub packages that are below the packageName and
     * add them to the respective sets. Searches the package on the whole class
     * path.
     */
    private void findClasses(String packageName, Set<String> classNames,
            Set<String> subpackageNames) {
        for (String entryName : classPath) {
            File classPathEntry = new File(entryName);

            // Forge may not have brought over every item in the classpath. Be
            // polite and ignore missing entries.
            if (classPathEntry.exists()) {
                try {
                    if (entryName.endsWith(".apk")) {
                        findClassesInApk(entryName, packageName, classNames, subpackageNames);
                    } else {
                        // scan the directories that contain apk files.
                        for (String apkPath : apkPaths) {
                            File file = new File(apkPath);
                            scanForApkFiles(file, packageName, classNames, subpackageNames);
                        }
                    }
                } catch (IOException e) {
                    throw new AssertionError("Can't read classpath entry " +
                            entryName + ": " + e.getMessage());
                }
            }
        }
    }

    private void scanForApkFiles(File source, String packageName,
            Set<String> classNames, Set<String> subpackageNames) throws IOException {
        if (source.getPath().endsWith(".apk")) {
            findClassesInApk(source.getPath(), packageName, classNames, subpackageNames);
        } else {
            File[] files = source.listFiles();
            if (files != null) {
                for (File file : files) {
                    scanForApkFiles(file, packageName, classNames, subpackageNames);
                }
            }
        }
    }

    /**
     * Finds all classes and sub packages that are below the packageName and
     * add them to the respective sets. Searches the package in a single apk file.
     */
    private void findClassesInApk(String apkPath, String packageName,
            Set<String> classNames, Set<String> subpackageNames)
            throws IOException {

        DexFile dexFile = null;
        try {
            dexFile = new DexFile(apkPath);
            Enumeration<String> apkClassNames = dexFile.entries();
            while (apkClassNames.hasMoreElements()) {
                String className = apkClassNames.nextElement();

                if (className.startsWith(packageName)) {
                    String subPackageName = packageName;
                    int lastPackageSeparator = className.lastIndexOf('.');
                    if (lastPackageSeparator > 0) {
                        subPackageName = className.substring(0, lastPackageSeparator);
                    }
                    if (subPackageName.length() > packageName.length()) {
                        subpackageNames.add(subPackageName);
                    } else if (isToplevelClass(className)) {
                        classNames.add(className);
                    }
                }
            }
        } catch (IOException e) {
            if (false) {
                Log.w("ClassPathPackageInfoSource",
                        "Error finding classes at apk path: " + apkPath, e);
            }
        } finally {
            if (dexFile != null) {
                // Todo: figure out why closing causes a dalvik error resulting in vm shutdown.
//                dexFile.close();
            }
        }
    }

    /**
     * Checks if a given file name represents a toplevel class.
     */
    private static boolean isToplevelClass(String fileName) {
        return fileName.indexOf('$') < 0;
    }

    /**
     * Gets the class path from the System Property "java.class.path" and splits
     * it up into the individual elements.
     */
    private static String[] getClassPath() {
        String classPath = System.getProperty("java.class.path");
        String separator = System.getProperty("path.separator", ":");
        return classPath.split(Pattern.quote(separator));
    }

    /**
     * The Package object doesn't allow you to iterate over the contained
     * classes and subpackages of that package.  This is a version that does.
     */
    private class ClassPathPackageInfo {

        private final String packageName;
        private final Set<String> subpackageNames;
        private final Set<Class<?>> topLevelClasses;

        private ClassPathPackageInfo(String packageName,
                Set<String> subpackageNames, Set<Class<?>> topLevelClasses) {
            this.packageName = packageName;
            this.subpackageNames = Collections.unmodifiableSet(subpackageNames);
            this.topLevelClasses = Collections.unmodifiableSet(topLevelClasses);
        }

        private Set<ClassPathPackageInfo> getSubpackages() {
            Set<ClassPathPackageInfo> info = new HashSet<>();
            for (String name : subpackageNames) {
                info.add(cache.get(name));
            }
            return info;
        }

        private Set<Class<?>> getTopLevelClassesRecursive() {
            Set<Class<?>> set = new HashSet<>();
            addTopLevelClassesTo(set);
            return set;
        }

        private void addTopLevelClassesTo(Set<Class<?>> set) {
            set.addAll(topLevelClasses);
            for (ClassPathPackageInfo info : getSubpackages()) {
                info.addTopLevelClassesTo(set);
            }
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof ClassPathPackageInfo) {
                ClassPathPackageInfo that = (ClassPathPackageInfo) obj;
                return (this.packageName).equals(that.packageName);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return packageName.hashCode();
        }
    }
}
