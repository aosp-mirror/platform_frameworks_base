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

import com.google.android.collect.Sets;

import java.util.Collections;
import java.util.Set;

/**
 * The Package object doesn't allow you to iterate over the contained
 * classes and subpackages of that package.  This is a version that does.
 *
 * {@hide} Not needed for 1.0 SDK.
 */
@Deprecated
public class ClassPathPackageInfo {

    private final ClassPathPackageInfoSource source;
    private final String packageName;
    private final Set<String> subpackageNames;
    private final Set<Class<?>> topLevelClasses;

    ClassPathPackageInfo(ClassPathPackageInfoSource source, String packageName,
            Set<String> subpackageNames, Set<Class<?>> topLevelClasses) {
        this.source = source;
        this.packageName = packageName;
        this.subpackageNames = Collections.unmodifiableSet(subpackageNames);
        this.topLevelClasses = Collections.unmodifiableSet(topLevelClasses);
    }

    public Set<ClassPathPackageInfo> getSubpackages() {
        Set<ClassPathPackageInfo> info = Sets.newHashSet();
        for (String name : subpackageNames) {
            info.add(source.getPackageInfo(name));
        }
        return info;
    }

    public Set<Class<?>> getTopLevelClassesRecursive() {
        Set<Class<?>> set = Sets.newHashSet();
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
