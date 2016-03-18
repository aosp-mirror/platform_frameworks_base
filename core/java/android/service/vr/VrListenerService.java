/**
 * Copyright (C) 2016 The Android Open Source Project
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

package android.service.vr;

import android.annotation.NonNull;
import android.annotation.SdkConstant;
import android.app.ActivityManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;

/**
 * A service that is bound from the system while running in virtual reality (VR) mode.
 *
 * <p>To extend this class, you must declare the service in your manifest file with
 * the {@link android.Manifest.permission#BIND_VR_LISTENER_SERVICE} permission
 * and include an intent filter with the {@link #SERVICE_INTERFACE} action. For example:</p>
 * <pre>
 * &lt;service android:name=".VrListener"
 *          android:label="&#64;string/service_name"
 *          android:permission="android.permission.BIND_VR_LISTENER_SERVICE">
 *     &lt;intent-filter>
 *         &lt;action android:name="android.service.vr.VrListenerService" />
 *     &lt;/intent-filter>
 * &lt;/service>
 * </pre>
 *
 * <p>
 * This service is bound when the system enters VR mode and is unbound when the system leaves VR
 * mode.
 * {@see android.app.Activity#setVrMode(boolean)}
 * </p>
 */
public abstract class VrListenerService extends Service {

    /**
     * The {@link Intent} that must be declared as handled by the service.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE = "android.service.vr.VrListenerService";

    private final Handler mHandler;

    private static final int MSG_ON_CURRENT_VR_ACTIVITY_CHANGED = 1;

    private final IVrListener.Stub mBinder = new IVrListener.Stub() {
        @Override
        public void focusedActivityChanged(ComponentName component) {
            mHandler.obtainMessage(MSG_ON_CURRENT_VR_ACTIVITY_CHANGED, component).sendToTarget();
        }
    };

    private final class VrListenerHandler extends Handler {
        public VrListenerHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_ON_CURRENT_VR_ACTIVITY_CHANGED: {
                    VrListenerService.this.onCurrentVrActivityChanged((ComponentName) msg.obj);
                } break;
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public VrListenerService() {
        mHandler = new VrListenerHandler(Looper.getMainLooper());
    }

    /**
     * Called when the current activity using VR mode is changed.
     * <p/>
     * This will be called immediately when this service is initially bound, but is
     * not guaranteed to be called before onUnbind.
     *
     * @param component the {@link ComponentName} of the new current VR activity.
     */
    public void onCurrentVrActivityChanged(ComponentName component) {
        // Override to implement
    }

    /**
     * Check if the given package is available to be enabled/disabled in VR mode settings.
     *
     * @param context the {@link Context} to use for looking up the requested component.
     * @param requestedComponent the name of the component that implements
     * {@link android.service.vr.VrListenerService} to check.
     *
     * @return {@code true} if this package is enabled in settings.
     */
    public static final boolean isVrModePackageEnabled(@NonNull Context context,
            @NonNull ComponentName requestedComponent) {
        ActivityManager am = context.getSystemService(ActivityManager.class);
        if (am == null) {
            return false;
        }
        return am.isVrModePackageEnabled(requestedComponent);
    }
}
