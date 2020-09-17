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

package android.content.pm.parsing.component;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.PackageManager;
import android.content.pm.parsing.ParsingPackage;
import android.content.pm.parsing.ParsingPackageUtils;
import android.content.pm.parsing.ParsingUtils;
import android.content.pm.parsing.result.ParseInput;
import android.content.pm.parsing.result.ParseResult;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;

import com.android.internal.annotations.VisibleForTesting;

/** @hide */
class ParsedComponentUtils {

    private static final String TAG = ParsingPackageUtils.TAG;

    @NonNull
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    static <Component extends ParsedComponent> ParseResult<Component> parseComponent(
            Component component, String tag, ParsingPackage pkg, TypedArray array,
            boolean useRoundIcon, ParseInput input, int bannerAttr,
            @Nullable Integer descriptionAttr, int iconAttr, int labelAttr, int logoAttr,
            int nameAttr, int roundIconAttr) {
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
        component.setName(className);
        component.setPackageName(packageName);

        int roundIconVal = useRoundIcon ? array.getResourceId(roundIconAttr, 0) : 0;
        if (roundIconVal != 0) {
            component.icon = roundIconVal;
            component.nonLocalizedLabel = null;
        } else {
            int iconVal = array.getResourceId(iconAttr, 0);
            if (iconVal != 0) {
                component.icon = iconVal;
                component.nonLocalizedLabel = null;
            }
        }

        int logoVal = array.getResourceId(logoAttr, 0);
        if (logoVal != 0) {
            component.logo = logoVal;
        }

        int bannerVal = array.getResourceId(bannerAttr, 0);
        if (bannerVal != 0) {
            component.banner = bannerVal;
        }

        if (descriptionAttr != null) {
            component.descriptionRes = array.getResourceId(descriptionAttr, 0);
        }

        TypedValue v = array.peekValue(labelAttr);
        if (v != null) {
            component.labelRes = v.resourceId;
            if (v.resourceId == 0) {
                component.nonLocalizedLabel = v.coerceToString();
            }
        }

        return input.success(component);
    }

    static ParseResult<Bundle> addMetaData(ParsedComponent component, ParsingPackage pkg,
            Resources resources, XmlResourceParser parser, ParseInput input) {
        ParseResult<Bundle> result = ParsingPackageUtils.parseMetaData(pkg, resources,
                parser, component.metaData, input);
        if (result.isError()) {
            return input.error(result);
        }
        Bundle bundle = result.getResult();
        component.metaData = bundle;
        return input.success(bundle);
    }
}
