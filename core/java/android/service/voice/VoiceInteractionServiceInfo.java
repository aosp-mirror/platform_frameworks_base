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
    private String mSettingsActivity;

    public VoiceInteractionServiceInfo(PackageManager pm, ComponentName comp)
            throws PackageManager.NameNotFoundException {
        this(pm, pm.getServiceInfo(comp, PackageManager.GET_META_DATA));
    }

    public VoiceInteractionServiceInfo(PackageManager pm, ComponentName comp, int userHandle)
            throws PackageManager.NameNotFoundException, RemoteException {
        this(pm, AppGlobals.getPackageManager().getServiceInfo(comp,
                PackageManager.GET_META_DATA, userHandle));
    }

    public VoiceInteractionServiceInfo(PackageManager pm, ServiceInfo si) {
        if (!Manifest.permission.BIND_VOICE_INTERACTION.equals(si.permission)) {
            mParseError = "Service does not require permission "
                    + Manifest.permission.BIND_VOICE_INTERACTION;
            return;
        }

        XmlResourceParser parser = null;
        try {
            parser = si.loadXmlMetaData(pm, VoiceInteractionService.SERVICE_META_DATA);
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
            array.recycle();
            if (mSessionService == null) {
                mParseError = "No sessionService specified";
                return;
            }
        } catch (XmlPullParserException e) {
            mParseError = "Error parsing voice interation service meta-data: " + e;
            Log.w(TAG, "error parsing voice interaction service meta-data", e);
            return;
        } catch (IOException e) {
            mParseError = "Error parsing voice interation service meta-data: " + e;
            Log.w(TAG, "error parsing voice interaction service meta-data", e);
            return;
        } catch (PackageManager.NameNotFoundException e) {
            mParseError = "Error parsing voice interation service meta-data: " + e;
            Log.w(TAG, "error parsing voice interaction service meta-data", e);
            return;
        } finally {
            if (parser != null) parser.close();
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
}
