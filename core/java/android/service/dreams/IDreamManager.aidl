/**
 * Copyright (c) 2012, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.service.dreams;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.IBinder;

/** @hide */
interface IDreamManager {
    @UnsupportedAppUsage
    void dream();
    @UnsupportedAppUsage
    void awaken();
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void setDreamComponents(in ComponentName[] componentNames);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    ComponentName[] getDreamComponents();
    ComponentName getDefaultDreamComponentForUser(int userId);
    void testDream(int userId, in ComponentName componentName);
    @UnsupportedAppUsage
    boolean isDreaming();
    @UnsupportedAppUsage
    boolean isDreamingOrInPreview();
    boolean canStartDreaming(boolean isScreenOn);
    /** @deprecated Please use finishSelfOneway instead. */
    void finishSelf(in IBinder token, boolean immediate);
    /** @deprecated Please use startDozingOneway instead. */
    void startDozing(in IBinder token, int screenState, int reason, float screenBrightnessFloat,
            int screenBrightnessInt, boolean useNormalBrightnessForDoze);
    void stopDozing(in IBinder token);
    void forceAmbientDisplayEnabled(boolean enabled);
    ComponentName[] getDreamComponentsForUser(int userId);
    void setDreamComponentsForUser(int userId, in ComponentName[] componentNames);
    void setSystemDreamComponent(in ComponentName componentName);
    void registerDreamOverlayService(in ComponentName componentName);
    void startDreamActivity(in Intent intent);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.WRITE_DREAM_STATE)")
    oneway void setDreamIsObscured(in boolean isObscured);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.WRITE_DREAM_STATE)")
    oneway void setDevicePostured(in boolean isPostured);
    oneway void startDozingOneway(in IBinder token, int screenState, int reason,
            float screenBrightnessFloat, int screenBrightnessInt,
            boolean useNormalBrightnessForDoze);
    oneway void finishSelfOneway(in IBinder token, boolean immediate);
}
