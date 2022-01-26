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
import android.annotation.Nullable;
import android.content.IntentFilter;
import android.content.pm.parsing.result.ParseInput;
import android.content.pm.parsing.result.ParseResult;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.os.Build;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.pm.pkg.parsing.ParsingPackage;
import com.android.server.pm.pkg.parsing.ParsingUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/** @hide */
class ParsedMainComponentUtils {

    private static final String TAG = ParsingUtils.TAG;

    @NonNull
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    static <Component extends ParsedMainComponentImpl> ParseResult<Component> parseMainComponent(
            Component component, String tag, String[] separateProcesses, ParsingPackage pkg,
            TypedArray array, int flags, boolean useRoundIcon,  @Nullable String defaultSplitName,
            @NonNull ParseInput input, int bannerAttr, int descriptionAttr, int directBootAwareAttr,
            int enabledAttr, int iconAttr, int labelAttr, int logoAttr, int nameAttr,
            int processAttr, int roundIconAttr, int splitNameAttr, int attributionTagsAttr) {
        ParseResult<Component> result = ParsedComponentUtils.parseComponent(component, tag, pkg,
                array, useRoundIcon, input, bannerAttr, descriptionAttr, iconAttr, labelAttr,
                logoAttr, nameAttr, roundIconAttr);
        if (result.isError()) {
            return result;
        }

        if (directBootAwareAttr != NOT_SET) {
            component.setDirectBootAware(array.getBoolean(directBootAwareAttr, false));
            if (component.isDirectBootAware()) {
                pkg.setPartiallyDirectBootAware(true);
            }
        }

        if (enabledAttr != NOT_SET) {
            component.setEnabled(array.getBoolean(enabledAttr, true));
        }

        if (processAttr != NOT_SET) {
            CharSequence processName;
            if (pkg.getTargetSdkVersion() >= Build.VERSION_CODES.FROYO) {
                processName = array.getNonConfigurationString(processAttr,
                        Configuration.NATIVE_CONFIG_VERSION);
            } else {
                // Some older apps have been seen to use a resource reference
                // here that on older builds was ignored (with a warning).  We
                // need to continue to do this for them so they don't break.
                processName = array.getNonResourceString(processAttr);
            }

            // Backwards-compat, ignore error
            ParseResult<String> processNameResult = ComponentParseUtils.buildProcessName(
                    pkg.getPackageName(), pkg.getProcessName(), processName, flags,
                    separateProcesses, input);
            if (processNameResult.isError()) {
                return input.error(processNameResult);
            }

            component.setProcessName(processNameResult.getResult());
        }

        if (splitNameAttr != NOT_SET) {
            component.setSplitName(array.getNonConfigurationString(splitNameAttr, 0));
        }

        if (defaultSplitName != null && component.getSplitName() == null) {
            component.setSplitName(defaultSplitName);
        }

        if (attributionTagsAttr != NOT_SET) {
            final String attributionTags = array.getNonConfigurationString(attributionTagsAttr, 0);
            if (attributionTags != null) {
                component.setAttributionTags(attributionTags.split("\\|"));
            }
        }

        return input.success(component);
    }

    static ParseResult<ParsedIntentInfoImpl> parseIntentFilter(
            ParsedMainComponent mainComponent,
            ParsingPackage pkg, Resources resources, XmlResourceParser parser,
            boolean visibleToEphemeral, boolean allowGlobs, boolean allowAutoVerify,
            boolean allowImplicitEphemeralVisibility, boolean failOnNoActions,
            ParseInput input) throws IOException, XmlPullParserException {
        ParseResult<ParsedIntentInfoImpl> intentResult = ParsedIntentInfoUtils.parseIntentInfo(
                mainComponent.getName(), pkg, resources, parser, allowGlobs,
                allowAutoVerify, input);
        if (intentResult.isError()) {
            return input.error(intentResult);
        }

        ParsedIntentInfo intent = intentResult.getResult();
        IntentFilter intentFilter = intent.getIntentFilter();
        int actionCount = intentFilter.countActions();
        if (actionCount == 0 && failOnNoActions) {
            Slog.w(TAG, "No actions in " + parser.getName() + " at " + pkg.getBaseApkPath() + " "
                    + parser.getPositionDescription());
            // Backward-compat, do not actually fail
            return input.success(null);
        }

        int intentVisibility;
        if (visibleToEphemeral) {
            intentVisibility = IntentFilter.VISIBILITY_EXPLICIT;
        } else if (allowImplicitEphemeralVisibility
                && ComponentParseUtils.isImplicitlyExposedIntent(intent)){
            intentVisibility = IntentFilter.VISIBILITY_IMPLICIT;
        } else {
            intentVisibility = IntentFilter.VISIBILITY_NONE;
        }
        intentFilter.setVisibilityToInstantApp(intentVisibility);

        return input.success(intentResult.getResult());
    }

}
