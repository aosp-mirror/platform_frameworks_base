/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.voiceinteraction;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.speech.RecognitionService;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

// TODO: Move this class somewhere else, along with the default recognizer logic in
//  VoiceInteractionManagerService.
// TODO: Use this class in com.android.settings.applications.assist.VoiceInputHelper.

/**
 * {@link ServiceInfo} and parsed metadata for a {@link RecognitionService}.
 */
class RecognitionServiceInfo {
    private static final String TAG = "RecognitionServiceInfo";

    private final String mParseError;
    private final ServiceInfo mServiceInfo;
    private final boolean mSelectableAsDefault;

    /**
     * Queries the valid recognition services available for the user.
     */
    static List<RecognitionServiceInfo> getAvailableServices(
            @NonNull Context context, @UserIdInt int user) {
        List<RecognitionServiceInfo> services = new ArrayList<>();

        List<ResolveInfo> resolveInfos =
                context.getPackageManager().queryIntentServicesAsUser(
                        new Intent(RecognitionService.SERVICE_INTERFACE),
                        PackageManager.MATCH_DIRECT_BOOT_AWARE
                                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                        user);
        for (ResolveInfo resolveInfo : resolveInfos) {
            RecognitionServiceInfo service =
                    parseInfo(context.getPackageManager(), resolveInfo.serviceInfo);
            if (!TextUtils.isEmpty(service.mParseError)) {
                Log.w(TAG, "Parse error in getAvailableServices: " + service.mParseError);
                // We still use the recognizer to preserve pre-existing behavior.
            }
            services.add(service);
        }
        return services;
    }

    /**
     * Loads the service metadata published by the component. Success is indicated by {@link
     * #getParseError()}.
     *
     * @param pm A PackageManager from which the XML can be loaded; usually the
     *         PackageManager from which {@code si} was originally retrieved.
     * @param si The {@link android.speech.RecognitionService} info.
     */
    static RecognitionServiceInfo parseInfo(@NonNull PackageManager pm, @NonNull ServiceInfo si) {
        String parseError = "";
        boolean selectableAsDefault = true; // default
        try (XmlResourceParser parser = si.loadXmlMetaData(
                pm,
                RecognitionService.SERVICE_META_DATA)) {
            if (parser == null) {
                parseError = "No " + RecognitionService.SERVICE_META_DATA
                        + " meta-data for " + si.packageName;
                return new RecognitionServiceInfo(si, selectableAsDefault, parseError);
            }
            Resources res = pm.getResourcesForApplication(si.applicationInfo);
            AttributeSet attrs = Xml.asAttributeSet(parser);

            int type = 0;
            while (type != XmlPullParser.END_DOCUMENT && type != XmlPullParser.START_TAG) {
                type = parser.next();
            }

            String nodeName = parser.getName();
            if (!"recognition-service".equals(nodeName)) {
                throw new XmlPullParserException(
                        "Meta-data does not start with recognition-service tag");
            }

            TypedArray values =
                    res.obtainAttributes(
                            attrs, com.android.internal.R.styleable.RecognitionService);
            selectableAsDefault =
                    values.getBoolean(
                            com.android.internal.R.styleable.RecognitionService_selectableAsDefault,
                            selectableAsDefault);
            values.recycle();
        } catch (XmlPullParserException | IOException | PackageManager.NameNotFoundException e) {
            parseError = "Error parsing recognition service meta-data: " + e;
        }
        return new RecognitionServiceInfo(si, selectableAsDefault, parseError);
    }

    private RecognitionServiceInfo(
            @NonNull ServiceInfo si, boolean selectableAsDefault, @NonNull String parseError) {
        mServiceInfo = si;
        mSelectableAsDefault = selectableAsDefault;
        mParseError = parseError;
    }

    @NonNull
    public String getParseError() {
        return mParseError;
    }

    @NonNull
    public ServiceInfo getServiceInfo() {
        return mServiceInfo;
    }

    public boolean isSelectableAsDefault() {
        return mSelectableAsDefault;
    }
}
