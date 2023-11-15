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

import static com.android.server.pm.pkg.component.ComponentParseUtils.flag;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.parsing.result.ParseInput;
import android.content.pm.parsing.result.ParseInput.DeferredError;
import android.content.pm.parsing.result.ParseResult;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.os.Build;

import com.android.internal.R;
import com.android.internal.pm.pkg.component.ParsedService;
import com.android.server.pm.pkg.parsing.ParsingPackage;
import com.android.server.pm.pkg.parsing.ParsingUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.Objects;

/** @hide */
public class ParsedServiceUtils {

    @NonNull
    public static ParseResult<ParsedService> parseService(String[] separateProcesses,
            ParsingPackage pkg, Resources res, XmlResourceParser parser, int flags,
            boolean useRoundIcon, @Nullable String defaultSplitName, @NonNull ParseInput input)
            throws XmlPullParserException, IOException {
        boolean visibleToEphemeral;
        boolean setExported;

        final String packageName = pkg.getPackageName();
        final ParsedServiceImpl service = new ParsedServiceImpl();
        String tag = parser.getName();

        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestService);
        try {
            ParseResult<ParsedServiceImpl> result = ParsedMainComponentUtils.parseMainComponent(
                    service, tag, separateProcesses, pkg, sa, flags, useRoundIcon, defaultSplitName,
                    input,
                    R.styleable.AndroidManifestService_banner,
                    R.styleable.AndroidManifestService_description,
                    R.styleable.AndroidManifestService_directBootAware,
                    R.styleable.AndroidManifestService_enabled,
                    R.styleable.AndroidManifestService_icon,
                    R.styleable.AndroidManifestService_label,
                    R.styleable.AndroidManifestService_logo,
                    R.styleable.AndroidManifestService_name,
                    R.styleable.AndroidManifestService_process,
                    R.styleable.AndroidManifestService_roundIcon,
                    R.styleable.AndroidManifestService_splitName,
                    R.styleable.AndroidManifestService_attributionTags
            );

            if (result.isError()) {
                return input.error(result);
            }

            setExported = sa.hasValue(R.styleable.AndroidManifestService_exported);
            if (setExported) {
                service.setExported(sa.getBoolean(R.styleable.AndroidManifestService_exported,
                        false));
            }

            String permission = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestService_permission, 0);
            service.setPermission(permission != null ? permission : pkg.getPermission());

            service.setForegroundServiceType(sa.getInt(
                    R.styleable.AndroidManifestService_foregroundServiceType,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE))
                    .setFlags(service.getFlags() | (flag(ServiceInfo.FLAG_STOP_WITH_TASK,
                            R.styleable.AndroidManifestService_stopWithTask, sa)
                            | flag(ServiceInfo.FLAG_ISOLATED_PROCESS,
                            R.styleable.AndroidManifestService_isolatedProcess, sa)
                            | flag(ServiceInfo.FLAG_EXTERNAL_SERVICE,
                            R.styleable.AndroidManifestService_externalService, sa)
                            | flag(ServiceInfo.FLAG_USE_APP_ZYGOTE,
                            R.styleable.AndroidManifestService_useAppZygote, sa)
                            | flag(ServiceInfo.FLAG_ALLOW_SHARED_ISOLATED_PROCESS,
                            R.styleable.AndroidManifestService_allowSharedIsolatedProcess, sa)
                            | flag(ServiceInfo.FLAG_SINGLE_USER,
                            R.styleable.AndroidManifestService_singleUser, sa)));

            visibleToEphemeral = sa.getBoolean(
                    R.styleable.AndroidManifestService_visibleToInstantApps, false);
            if (visibleToEphemeral) {
                service.setFlags(service.getFlags() | ActivityInfo.FLAG_VISIBLE_TO_INSTANT_APP);
                pkg.setVisibleToInstantApps(true);
            }
        } finally {
            sa.recycle();
        }

        if (pkg.isSaveStateDisallowed()) {
            // A heavy-weight application can not have services in its main process
            // We can do direct compare because we intern all strings.
            if (Objects.equals(service.getProcessName(), packageName)) {
                return input.error("Heavy-weight applications can not have services "
                        + "in main process");
            }
        }
        final int depth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG
                || parser.getDepth() > depth)) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            final ParseResult parseResult;
            switch (parser.getName()) {
                case "intent-filter":
                    ParseResult<ParsedIntentInfoImpl> intentResult = ParsedMainComponentUtils
                            .parseIntentFilter(service, pkg, res, parser, visibleToEphemeral,
                                    true /*allowGlobs*/, false /*allowAutoVerify*/,
                                    false /*allowImplicitEphemeralVisibility*/,
                                    false /*failOnNoActions*/, input);
                    parseResult = intentResult;
                    if (intentResult.isSuccess()) {
                        ParsedIntentInfoImpl intent = intentResult.getResult();
                        IntentFilter intentFilter = intent.getIntentFilter();
                        service.setOrder(Math.max(intentFilter.getOrder(), service.getOrder()));
                        service.addIntent(intent);
                    }
                    break;
                case "meta-data":
                    parseResult = ParsedComponentUtils.addMetaData(service, pkg, res, parser, input);
                    break;
                case "property":
                    parseResult =
                            ParsedComponentUtils.addProperty(service, pkg, res, parser, input);
                    break;
                default:
                    parseResult = ParsingUtils.unknownTag(tag, pkg, parser, input);
                    break;
            }

            if (parseResult.isError()) {
                return input.error(parseResult);
            }
        }

        if (!setExported) {
            boolean hasIntentFilters = service.getIntents().size() > 0;
            if (hasIntentFilters) {
                final ParseResult exportedCheckResult = input.deferError(
                        service.getName() + ": Targeting S+ (version " + Build.VERSION_CODES.S
                        + " and above) requires that an explicit value for android:exported be"
                        + " defined when intent filters are present",
                        DeferredError.MISSING_EXPORTED_FLAG);
                if (exportedCheckResult.isError()) {
                    return input.error(exportedCheckResult);
                }
            }
            service.setExported(hasIntentFilters);
        }

        return input.success(service);
    }
}
