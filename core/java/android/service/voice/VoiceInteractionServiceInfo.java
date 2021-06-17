/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.service.voice;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.os.RemoteException;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/** @hide */
public class VoiceInteractionServiceInfo {
    static final String TAG = "VoiceInteractionServiceInfo";

    private String mParseError;

    private ServiceInfo mServiceInfo;
    private String mSessionService;
    private String mRecognitionService;
    private String mHotwordDetectionService;
    private String mSettingsActivity;
    private boolean mSupportsAssist;
    private boolean mSupportsLaunchFromKeyguard;
    private boolean mSupportsLocalInteraction;

    /**
     * Loads the service metadata published by the component. Success is indicated by
     * {@link #getParseError()}.
     *
     * @param pm A PackageManager from which the XML can be loaded.
     * @param comp The {@link VoiceInteractionService} component.
     */
    public VoiceInteractionServiceInfo(
            @NonNull PackageManager pm, @NonNull ComponentName comp, int userHandle)
            throws PackageManager.NameNotFoundException {
        this(pm, getServiceInfoOrThrow(comp, userHandle));
    }

    @NonNull
    private static ServiceInfo getServiceInfoOrThrow(@NonNull ComponentName comp, int userHandle)
            throws PackageManager.NameNotFoundException {
        try {
            ServiceInfo si = AppGlobals.getPackageManager().getServiceInfo(comp,
                    PackageManager.GET_META_DATA
                            | PackageManager.MATCH_DIRECT_BOOT_AWARE
                            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                    userHandle);
            if (si != null) {
                return si;
            }
        } catch (RemoteException e) {
        }
        throw new PackageManager.NameNotFoundException(comp.toString());
    }

    /**
     * Loads the service metadata published by the component. Success is indicated by
     * {@link #getParseError()}.
     *
     * @param pm A PackageManager from which the XML can be loaded; usually the PackageManager
     *           from which {@code si} was originally retrieved.
     * @param si The {@link VoiceInteractionService} info.
     */
    public VoiceInteractionServiceInfo(@NonNull PackageManager pm, @NonNull ServiceInfo si) {
        if (!Manifest.permission.BIND_VOICE_INTERACTION.equals(si.permission)) {
            mParseError = "Service does not require permission "
                    + Manifest.permission.BIND_VOICE_INTERACTION;
            return;
        }

        try (XmlResourceParser parser = si.loadXmlMetaData(pm,
                VoiceInteractionService.SERVICE_META_DATA)) {
            if (parser == null) {
                mParseError = "No " + VoiceInteractionService.SERVICE_META_DATA
                        + " meta-data for " + si.packageName;
                return;
            }

            Resources res = pm.getResourcesForApplication(si.applicationInfo);

            AttributeSet attrs = Xml.asAttributeSet(parser);

            int type;
            while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                    && type != XmlPullParser.START_TAG) {
            }

            String nodeName = parser.getName();
            if (!"voice-interaction-service".equals(nodeName)) {
                mParseError = "Meta-data does not start with voice-interaction-service tag";
                return;
            }

            TypedArray array = res.obtainAttributes(attrs,
                    com.android.internal.R.styleable.VoiceInteractionService);
            mSessionService = array.getString(
                    com.android.internal.R.styleable.VoiceInteractionService_sessionService);
            mRecognitionService = array.getString(
                    com.android.internal.R.styleable.VoiceInteractionService_recognitionService);
            mSettingsActivity = array.getString(
                    com.android.internal.R.styleable.VoiceInteractionService_settingsActivity);
            mSupportsAssist = array.getBoolean(
                    com.android.internal.R.styleable.VoiceInteractionService_supportsAssist,
                    false);
            mSupportsLaunchFromKeyguard = array.getBoolean(com.android.internal.
                    R.styleable.VoiceInteractionService_supportsLaunchVoiceAssistFromKeyguard,
                    false);
            mSupportsLocalInteraction = array.getBoolean(com.android.internal.
                    R.styleable.VoiceInteractionService_supportsLocalInteraction, false);
            mHotwordDetectionService = array.getString(com.android.internal.R.styleable
                    .VoiceInteractionService_hotwordDetectionService);
            array.recycle();
            if (mSessionService == null) {
                mParseError = "No sessionService specified";
                return;
            }
            if (mRecognitionService == null) {
                mParseError = "No recognitionService specified";
                return;
            }
        } catch (XmlPullParserException | IOException | PackageManager.NameNotFoundException e) {
            mParseError = "Error parsing voice interation service meta-data: " + e;
            Log.w(TAG, "error parsing voice interaction service meta-data", e);
            return;
        }
        mServiceInfo = si;
    }

    public String getParseError() {
        return mParseError;
    }

    public ServiceInfo getServiceInfo() {
        return mServiceInfo;
    }

    public String getSessionService() {
        return mSessionService;
    }

    public String getRecognitionService() {
        return mRecognitionService;
    }

    public String getSettingsActivity() {
        return mSettingsActivity;
    }

    public boolean getSupportsAssist() {
        return mSupportsAssist;
    }

    public boolean getSupportsLaunchFromKeyguard() {
        return mSupportsLaunchFromKeyguard;
    }

    public boolean getSupportsLocalInteraction() {
        return mSupportsLocalInteraction;
    }

    @Nullable
    public String getHotwordDetectionService() {
        return mHotwordDetectionService;
    }
}
