/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.pm.pkg.component;

import static com.android.server.pm.pkg.parsing.ParsingUtils.NOT_SET;

import android.annotation.NonNull;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.Property;
import android.content.pm.parsing.result.ParseInput;
import android.content.pm.parsing.result.ParseResult;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.pm.pkg.parsing.ParsingPackage;
import com.android.server.pm.pkg.parsing.ParsingPackageUtils;
import com.android.server.pm.pkg.parsing.ParsingUtils;

/** @hide */
class ParsedComponentUtils {

    @NonNull
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    static <Component extends ParsedComponentImpl> ParseResult<Component> parseComponent(
            Component component, String tag, ParsingPackage pkg, TypedArray array,
            boolean useRoundIcon, ParseInput input, int bannerAttr, int descriptionAttr,
            int iconAttr, int labelAttr, int logoAttr, int nameAttr, int roundIconAttr) {
        String name = array.getNonConfigurationString(nameAttr, 0);
        if (TextUtils.isEmpty(name)) {
            return input.error(tag + " does not specify android:name");
        }

        String packageName = pkg.getPackageName();
        String className = ParsingUtils.buildClassName(packageName, name);
        if (PackageManager.APP_DETAILS_ACTIVITY_CLASS_NAME.equals(className)) {
            return input.error(tag + " invalid android:name");
        }

        //noinspection ConstantConditions; null check done above with isEmpty
        component.setName(className)
                .setPackageName(packageName);

        int roundIconVal = useRoundIcon ? array.getResourceId(roundIconAttr, 0) : 0;
        if (roundIconVal != 0) {
            component.setIcon(roundIconVal)
                    .setNonLocalizedLabel(null);
        } else {
            int iconVal = array.getResourceId(iconAttr, 0);
            if (iconVal != 0) {
                component.setIcon(iconVal);
                component.setNonLocalizedLabel(null);
            }
        }

        int logoVal = array.getResourceId(logoAttr, 0);
        if (logoVal != 0) {
            component.setLogo(logoVal);
        }

        int bannerVal = array.getResourceId(bannerAttr, 0);
        if (bannerVal != 0) {
            component.setBanner(bannerVal);
        }

        if (descriptionAttr != NOT_SET) {
            component.setDescriptionRes(array.getResourceId(descriptionAttr, 0));
        }

        TypedValue v = array.peekValue(labelAttr);
        if (v != null) {
            component.setLabelRes(v.resourceId);
            if (v.resourceId == 0) {
                component.setNonLocalizedLabel(v.coerceToString());
            }
        }

        return input.success(component);
    }

    static ParseResult<Bundle> addMetaData(ParsedComponentImpl component, ParsingPackage pkg,
            Resources resources, XmlResourceParser parser, ParseInput input) {
        ParseResult<Property> result = ParsingPackageUtils.parseMetaData(pkg, component,
                resources, parser, "<meta-data>", input);
        if (result.isError()) {
            return input.error(result);
        }
        final Property property = result.getResult();
        if (property != null) {
            component.setMetaData(property.toBundle(component.getMetaData()));
        }
        return input.success(component.getMetaData());
    }

    static ParseResult<Property> addProperty(ParsedComponentImpl component, ParsingPackage pkg,
            Resources resources, XmlResourceParser parser, ParseInput input) {
        ParseResult<Property> result = ParsingPackageUtils.parseMetaData(pkg, component,
                resources, parser, "<property>", input);
        if (result.isError()) {
            return input.error(result);
        }
        final Property property = result.getResult();
        if (property != null) {
            component.addProperty(property);
        }
        return input.success(property);
    }
}
