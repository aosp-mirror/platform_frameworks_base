/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.content.pm.PackageManager.TYPE_ACTIVITY;
import static android.content.pm.PackageManager.TYPE_APPLICATION;
import static android.content.pm.PackageManager.TYPE_PROVIDER;
import static android.content.pm.PackageManager.TYPE_RECEIVER;
import static android.content.pm.PackageManager.TYPE_SERVICE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.Property;
import android.content.pm.PackageManager.PropertyLocation;
import com.android.server.pm.pkg.component.ParsedComponent;
import android.os.Binder;
import android.os.UserHandle;
import android.util.ArrayMap;

import com.android.server.pm.parsing.pkg.AndroidPackage;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Manages properties defined within a package using the &lt;property&gt; tag.
 */
public class PackageProperty {
    /**
     * Mapping of property name to all defined defined properties.
     * <p>This is a mapping of property name --> package map. The package
     * map is a mapping of package name -> list of properties.
     */
    private ArrayMap<String, ArrayMap<String, ArrayList<Property>>> mApplicationProperties;
    private ArrayMap<String, ArrayMap<String, ArrayList<Property>>> mActivityProperties;
    private ArrayMap<String, ArrayMap<String, ArrayList<Property>>> mProviderProperties;
    private ArrayMap<String, ArrayMap<String, ArrayList<Property>>> mReceiverProperties;
    private ArrayMap<String, ArrayMap<String, ArrayList<Property>>> mServiceProperties;

    /**
     * If the provided component is {@code null}, returns the property defined on the
     * application. Otherwise, returns the property defined on the component.
     */
    public Property getProperty(@NonNull String propertyName, @NonNull String packageName,
            @Nullable String className) {
        if (className == null) {
            return getApplicationProperty(propertyName, packageName);
        }
        return getComponentProperty(propertyName, packageName, className);
    }

    /**
     * Returns all properties defined at the given location.
     * <p>Valid locations are {@link PackageManager#TYPE_APPLICATION},
     * {@link PackageManager#TYPE_ACTIVITY}, {@link PackageManager#TYPE_PROVIDER},
     * {@link PackageManager#TYPE_RECEIVER}, or {@link PackageManager#TYPE_SERVICE}.
     */
    public List<Property> queryProperty(@NonNull String propertyName,
            @PropertyLocation int componentType, Predicate<String> filter) {
        final ArrayMap<String, ArrayMap<String, ArrayList<Property>>> propertyMap;
        if (componentType == TYPE_APPLICATION) {
            propertyMap = mApplicationProperties;
        } else if (componentType == TYPE_ACTIVITY) {
            propertyMap = mActivityProperties;
        } else if (componentType == TYPE_PROVIDER) {
            propertyMap = mProviderProperties;
        } else if (componentType == TYPE_RECEIVER) {
            propertyMap = mReceiverProperties;
        } else if (componentType == TYPE_SERVICE) {
            propertyMap = mServiceProperties;
        } else {
            propertyMap = null;
        }
        if (propertyMap == null) {
            return null;
        }
        final ArrayMap<String, ArrayList<Property>> packagePropertyMap =
                propertyMap.get(propertyName);
        if (packagePropertyMap == null) {
            return null;
        }
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getCallingUserId();
        final int mapSize = packagePropertyMap.size();
        final List<Property> result = new ArrayList<>(mapSize);
        for (int i = 0; i < mapSize; i++) {
            final String packageName = packagePropertyMap.keyAt(i);
            if (filter.test(packageName)) {
                continue;
            }
            result.addAll(packagePropertyMap.valueAt(i));
        }
        return result;
    }

    /** Adds all properties defined for the given package */
    void addAllProperties(AndroidPackage pkg) {
        mApplicationProperties = addProperties(pkg.getProperties(), mApplicationProperties);
        mActivityProperties = addComponentProperties(pkg.getActivities(), mActivityProperties);
        mProviderProperties = addComponentProperties(pkg.getProviders(), mProviderProperties);
        mReceiverProperties = addComponentProperties(pkg.getReceivers(), mReceiverProperties);
        mServiceProperties = addComponentProperties(pkg.getServices(), mServiceProperties);
    }

    /** Adds all properties defined for the given package */
    void removeAllProperties(AndroidPackage pkg) {
        mApplicationProperties = removeProperties(pkg.getProperties(), mApplicationProperties);
        mActivityProperties = removeComponentProperties(pkg.getActivities(), mActivityProperties);
        mProviderProperties = removeComponentProperties(pkg.getProviders(), mProviderProperties);
        mReceiverProperties = removeComponentProperties(pkg.getReceivers(), mReceiverProperties);
        mServiceProperties = removeComponentProperties(pkg.getServices(), mServiceProperties);
    }

    /** Add the properties defined on the given components to the property collection */
    private static <T extends ParsedComponent>
                ArrayMap<String, ArrayMap<String, ArrayList<Property>>> addComponentProperties(
            @NonNull List<T> components,
            @Nullable ArrayMap<String, ArrayMap<String, ArrayList<Property>>> propertyCollection) {
        ArrayMap<String, ArrayMap<String, ArrayList<Property>>> returnCollection =
                propertyCollection;
        final int componentsSize = components.size();
        for (int i = 0; i < componentsSize; i++) {
            final Map<String, Property> properties = components.get(i).getProperties();
            if (properties.size() == 0) {
                continue;
            }
            returnCollection = addProperties(properties, returnCollection);
        }
        return returnCollection;
    }

    /** Add the given properties to the property collection */
    private static ArrayMap<String, ArrayMap<String, ArrayList<Property>>> addProperties(
            @NonNull Map<String, Property> properties,
            @Nullable ArrayMap<String, ArrayMap<String, ArrayList<Property>>> propertyCollection) {
        if (properties.size() == 0) {
            return propertyCollection;
        }
        final ArrayMap<String, ArrayMap<String, ArrayList<Property>>> returnCollection =
                propertyCollection == null ? new ArrayMap<>(10) : propertyCollection;
        final Iterator<Property> iter = properties.values().iterator();
        while (iter.hasNext()) {
            final Property property = iter.next();
            final String propertyName = property.getName();
            final String packageName = property.getPackageName();
            ArrayMap<String, ArrayList<Property>> propertyMap = returnCollection.get(propertyName);
            if (propertyMap == null) {
                propertyMap = new ArrayMap<>();
                returnCollection.put(propertyName, propertyMap);
            }
            ArrayList<Property> packageProperties = propertyMap.get(packageName);
            if (packageProperties == null) {
                packageProperties = new ArrayList<>(properties.size());
                propertyMap.put(packageName, packageProperties);
            }
            packageProperties.add(property);
        }
        return returnCollection;
    }

    /** Removes the properties defined on the given components from the property collection */
    private static <T extends ParsedComponent>
                ArrayMap<String, ArrayMap<String, ArrayList<Property>>> removeComponentProperties(
            @NonNull List<T> components,
            @Nullable ArrayMap<String, ArrayMap<String, ArrayList<Property>>> propertyCollection) {
        ArrayMap<String, ArrayMap<String, ArrayList<Property>>> returnCollection =
                propertyCollection;
        final int componentsSize = components.size();
        for (int i = 0; returnCollection != null && i < componentsSize; i++) {
            final Map<String, Property> properties = components.get(i).getProperties();
            if (properties.size() == 0) {
                continue;
            }
            returnCollection = removeProperties(properties, returnCollection);
        }
        return returnCollection;
    }

    /** Removes the given properties from the property collection */
    private static ArrayMap<String, ArrayMap<String, ArrayList<Property>>> removeProperties(
            @NonNull Map<String, Property> properties,
            @Nullable ArrayMap<String, ArrayMap<String, ArrayList<Property>>> propertyCollection) {
        if (propertyCollection == null) {
            return null;
        }
        final Iterator<Property> iter = properties.values().iterator();
        while (iter.hasNext()) {
            final Property property = iter.next();
            final String propertyName = property.getName();
            final String packageName = property.getPackageName();
            ArrayMap<String, ArrayList<Property>> propertyMap =
                    propertyCollection.get(propertyName);
            if (propertyMap == null) {
                // error
                continue;
            }
            ArrayList<Property> packageProperties = propertyMap.get(packageName);
            if (packageProperties == null) {
                //error
                continue;
            }
            packageProperties.remove(property);

            // clean up empty structures
            if (packageProperties.size() == 0) {
                propertyMap.remove(packageName);
            }
            if (propertyMap.size() == 0) {
                propertyCollection.remove(propertyName);
            }
        }
        if (propertyCollection.size() == 0) {
            return null;
        }
        return propertyCollection;
    }

    private static Property getProperty(String propertyName, String packageName, String className,
            ArrayMap<String, ArrayMap<String, ArrayList<Property>>> propertyMap) {
        final ArrayMap<String, ArrayList<Property>> packagePropertyMap =
                propertyMap.get(propertyName);
        if (packagePropertyMap == null) {
            return null;
        }
        final List<Property> propertyList = packagePropertyMap.get(packageName);
        if (propertyList == null) {
            return null;
        }
        for (int i = propertyList.size() - 1; i >= 0; i--) {
            final Property property = propertyList.get(i);
            if (Objects.equals(className, property.getClassName())) {
                return property;
            }
        }
        return null;
    }

    private Property getComponentProperty(
            String propertyName, String packageName, String className) {
        Property property = null;
        if (property == null && mActivityProperties != null) {
            property = getProperty(propertyName, packageName, className, mActivityProperties);
        }
        if (property == null && mProviderProperties != null) {
            property = getProperty(propertyName, packageName, className, mProviderProperties);
        }
        if (property == null && mReceiverProperties != null) {
            property = getProperty(propertyName, packageName, className, mReceiverProperties);
        }
        if (property == null && mServiceProperties != null) {
            property = getProperty(propertyName, packageName, className, mServiceProperties);
        }
        return property;
    }

    private Property getApplicationProperty(String propertyName, String packageName) {
        final ArrayMap<String, ArrayList<Property>> packagePropertyMap =
                mApplicationProperties != null ? mApplicationProperties.get(propertyName) : null;
        if (packagePropertyMap == null) {
            return null;
        }
        final List<Property> propertyList = packagePropertyMap.get(packageName);
        if (propertyList == null) {
            return null;
        }
        return propertyList.get(0);
    }
}
