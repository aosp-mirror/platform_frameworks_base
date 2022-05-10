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

package com.android.server.sensorprivacy;

import android.app.AppOpsManager;
import android.content.Context;
import android.hardware.lights.Light;
import android.hardware.lights.LightState;
import android.hardware.lights.LightsManager;
import android.hardware.lights.LightsRequest;
import android.permission.PermissionManager;
import android.util.ArraySet;

import com.android.internal.R;
import com.android.server.FgThread;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

class CameraPrivacyLightController implements AppOpsManager.OnOpActiveChangedListener {

    private final Context mContext;
    private final LightsManager mLightsManager;

    private final Set<String> mActivePackages = new ArraySet<>();
    private final Set<String> mActivePhonePackages = new ArraySet<>();

    private final int mCameraPrivacyLightColor;

    private final List<Light> mCameraLights = new ArrayList<>();
    private final AppOpsManager mAppOpsManager;

    private LightsManager.LightsSession mLightsSession = null;

    CameraPrivacyLightController(Context context) {
        mContext = context;

        mAppOpsManager = mContext.getSystemService(AppOpsManager.class);
        mLightsManager = mContext.getSystemService(LightsManager.class);

        mCameraPrivacyLightColor = mContext.getColor(R.color.camera_privacy_light);

        List<Light> lights = mLightsManager.getLights();
        for (int i = 0; i < lights.size(); i++) {
            Light light = lights.get(i);
            if (light.getType() == Light.LIGHT_TYPE_CAMERA) {
                mCameraLights.add(light);
            }
        }

        if (mCameraLights.isEmpty()) {
            return;
        }

        mAppOpsManager.startWatchingActive(
                new String[] {AppOpsManager.OPSTR_CAMERA, AppOpsManager.OPSTR_PHONE_CALL_CAMERA},
                FgThread.getExecutor(), this);
    }

    @Override
    public void onOpActiveChanged(String op, int uid, String packageName, boolean active) {
        final Set<String> activePackages;
        if (AppOpsManager.OPSTR_CAMERA.equals(op)) {
            activePackages = mActivePackages;
        } else if (AppOpsManager.OPSTR_PHONE_CALL_CAMERA.equals(op)) {
            activePackages = mActivePhonePackages;
        } else {
            return;
        }

        if (active) {
            activePackages.add(packageName);
        } else {
            activePackages.remove(packageName);
        }

        updateLightSession();
    }

    private void updateLightSession() {
        Set<String> exemptedPackages = PermissionManager.getIndicatorExemptedPackages(mContext);

        boolean shouldSessionEnd = exemptedPackages.containsAll(mActivePackages)
                && exemptedPackages.containsAll(mActivePhonePackages);

        if (shouldSessionEnd) {
            if (mLightsSession == null) {
                return;
            }

            mLightsSession.close();
            mLightsSession = null;
        } else {
            if (mLightsSession != null) {
                return;
            }

            LightsRequest.Builder requestBuilder = new LightsRequest.Builder();
            for (int i = 0; i < mCameraLights.size(); i++) {
                requestBuilder.addLight(mCameraLights.get(i),
                        new LightState.Builder()
                                .setColor(mCameraPrivacyLightColor)
                                .build());
            }

            mLightsSession = mLightsManager.openSession(Integer.MAX_VALUE);
            mLightsSession.requestLights(requestBuilder.build());
        }
    }
}
