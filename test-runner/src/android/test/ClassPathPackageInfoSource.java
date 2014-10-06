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
import com.google.android.collect.Maps;
import com.google.android.collect.Sets;
import dalvik.system.DexFile;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Generate {@link ClassPathPackageInfo}s by scanning apk paths.
 * 
 * {@hide} Not needed for 1.0 SDK.
 */
public class ClassPathPackageInfoSource {

    private static final String CLASS_EXTENSION = ".class";

    private static final ClassLoader CLASS_LOADER
            = ClassPathPackageInfoSource.class.getClassLoader();

    private final SimpleCache<String, ClassPathPackageInfo> cache =
            new SimpleCache<String, ClassPathPackageInfo>() {
                @Override
                protected ClassPathPackageInfo load(String pkgName) {
                    return createPackageInfo(pkgName);
                }
            };

    // The class path of the running application
    private final String[] classPath;
    private static String[] apkPaths;

    // A cache of jar file contents
    private final Map<File, Set<String>> jarFiles = Maps.newHashMap();
    private ClassLoader classLoader;

    ClassPathPackageInfoSource() {
        classPath = getClassPath();
    }


    public static void setApkPaths(String[] apkPaths) {
        ClassPathPackageInfoSource.apkPaths = apkPaths;
    }

    public ClassPathPackageInfo getPackageInfo(String pkgName) {
        return cache.get(pkgName);
    }

    private ClassPathPackageInfo createPackageInfo(String packageName) {
        Set<String> subpackageNames = new TreeSet<String>();
        Set<String> classNames = new TreeSet<String>();
        Set<Class<?>> topLevelClasses = Sets.newHashSet();
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
        return new ClassPathPackageInfo(this, packageName, subpackageNames,
                topLevelClasses);
    }

    /**
     * Finds all classes and sub packages that are below the packageName and
     * add them to the respective sets. Searches the package on the whole class
     * path.
     */
    private void findClasses(String packageName, Set<String> classNames,
            Set<String> subpackageNames) {
        String packagePrefix = packageName + '.';
        String pathPrefix = packagePrefix.replace('.', '/');

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
     * add them to the respective sets. Searches the package in a class directory.
     */
    private void findClassesInDirectory(File classDir,
            String packagePrefix, String pathPrefix, Set<String> classNames,
            Set<String> subpackageNames)
            throws IOException {
        File directory = new File(classDir, pathPrefix);

        if (directory.exists()) {
            for (File f : directory.listFiles()) {
                String name = f.getName();
                if (name.endsWith(CLASS_EXTENSION) && isToplevelClass(name)) {
                    classNames.add(packagePrefix + getClassName(name));
                } else if (f.isDirectory()) {
                    subpackageNames.add(packagePrefix + name);
                }
            }
        }
    }

    /**
     * Finds all classes and sub packages that are below the packageName and
     * add them to the respective sets. Searches the package in a single jar file.
     */
    private void findClassesInJar(File jarFile, String pathPrefix,
            Set<String> classNames, Set<String> subpackageNames)
            throws IOException {
        Set<String> entryNames = getJarEntries(jarFile);
        // check if the Jar contains the package.
        if (!entryNames.contains(pathPrefix)) {
            return;
        }
        int prefixLength = pathPrefix.length();
        for (String entryName : entryNames) {
            if (entryName.startsWith(pathPrefix)) {
                if (entryName.endsWith(CLASS_EXTENSION)) {
                    // check if the class is in the package itself or in one of its
                    // subpackages.
                    int index = entryName.indexOf('/', prefixLength);
                    if (index >= 0) {
                        String p = entryName.substring(0, index).replace('/', '.');
                        subpackageNames.add(p);
                    } else if (isToplevelClass(entryName)) {
                        classNames.add(getClassName(entryName).replace('/', '.'));
                    }
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
     * Gets the class and package entries from a Jar.
     */
    private Set<String> getJarEntries(File jarFile)
            throws IOException {
        Set<String> entryNames = jarFiles.get(jarFile);
        if (entryNames == null) {
            entryNames = Sets.newHashSet();
            ZipFile zipFile = new ZipFile(jarFile);
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                String entryName = entries.nextElement().getName();
                if (entryName.endsWith(CLASS_EXTENSION)) {
                    // add the entry name of the class
                    entryNames.add(entryName);

                    // add the entry name of the classes package, i.e. the entry name of
                    // the directory that the class is in. Used to quickly skip jar files
                    // if they do not contain a certain package.
                    //
                    // Also add parent packages so that a JAR that contains
                    // pkg1/pkg2/Foo.class will be marked as containing pkg1/ in addition
                    // to pkg1/pkg2/ and pkg1/pkg2/Foo.class.  We're still interested in
                    // JAR files that contains subpackages of a given package, even if
                    // an intermediate package contains no direct classes.
                    //
                    // Classes in the default package will cause a single package named
                    // "" to be added instead.
                    int lastIndex = entryName.lastIndexOf('/');
                    do {
                        String packageName = entryName.substring(0, lastIndex + 1);
                        entryNames.add(packageName);
                        lastIndex = entryName.lastIndexOf('/', lastIndex - 1);
                    } while (lastIndex > 0);
                }
            }
            jarFiles.put(jarFile, entryNames);
        }
        return entryNames;
    }

    /**
     * Checks if a given file name represents a toplevel class.
     */
    private static boolean isToplevelClass(String fileName) {
        return fileName.indexOf('$') < 0;
    }

    /**
     * Given the absolute path of a class file, return the class name.
     */
    private static String getClassName(String className) {
        int classNameEnd = className.length() - CLASS_EXTENSION.length();
        return className.substring(0, classNameEnd);
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

    public void setClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }
}
