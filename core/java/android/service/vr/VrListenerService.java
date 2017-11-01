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
 * <p>This service is bound when the system enters VR mode and is unbound when the system leaves VR
 * mode.</p>
 * <p>The system will enter VR mode when an application that has previously called
 * {@link android.app.Activity#setVrModeEnabled} gains user focus.  The system will only start this
 * service if the VR application has specifically targeted this service by specifying
 * its {@link ComponentName} in the call to {@link android.app.Activity#setVrModeEnabled} and if
 * this service is installed and enabled in the current user's settings.</p>
 *
 * @see android.provider.Settings#ACTION_VR_LISTENER_SETTINGS
 * @see android.app.Activity#setVrModeEnabled
 * @see android.R.attr#enableVrMode
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
        public void focusedActivityChanged(
                ComponentName component, boolean running2dInVr, int pid) {
            mHandler.obtainMessage(MSG_ON_CURRENT_VR_ACTIVITY_CHANGED, running2dInVr ? 1 : 0,
                    pid, component).sendToTarget();
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
                    VrListenerService.this.onCurrentVrActivityChanged(
                            (ComponentName) msg.obj, msg.arg1 == 1, msg.arg2);
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
     * Called when the current activity using VR mode has changed.
     *
     * <p>This will be called when this service is initially bound, but is not
     * guaranteed to be called before onUnbind.  In general, this is intended to be used to
     * determine when user focus has transitioned between two VR activities.  If both activities
     * have declared {@link android.R.attr#enableVrMode} with this service (and this
     * service is present and enabled), this service will not be unbound during the activity
     * transition.</p>
     *
     * @param component the {@link ComponentName} of the VR activity that the system has
     *    switched to, or null if the system is displaying a 2D activity in VR compatibility mode.
     *
     * @see android.app.Activity#setVrModeEnabled
     * @see android.R.attr#enableVrMode
     */
    public void onCurrentVrActivityChanged(ComponentName component) {
        // Override to implement
    }

    /**
     * An extended version of onCurrentVrActivityChanged
     *
     * <p>This will be called when this service is initially bound, but is not
     * guaranteed to be called before onUnbind.  In general, this is intended to be used to
     * determine when user focus has transitioned between two VR activities, or between a
     * VR activity and a 2D activity. This should be overridden instead of the above
     * onCurrentVrActivityChanged as that version is deprecated.</p>
     *
     * @param component the {@link ComponentName} of the VR activity or the 2D intent.
     * @param running2dInVr true if the component is a 2D component.
     * @param pid the process the component is running in.
     *
     * @see android.app.Activity#setVrModeEnabled
     * @see android.R.attr#enableVrMode
     * @hide
     */
    public void onCurrentVrActivityChanged(
            ComponentName component, boolean running2dInVr, int pid) {
        // Override to implement. Default to old behaviour of sending null for 2D.
        onCurrentVrActivityChanged(running2dInVr ? null : component);
    }

    /**
     * Checks if the given component is enabled in user settings.
     *
     * <p>If this component is not enabled in the user's settings, it will not be started when
     * the system enters VR mode.  The user interface for enabling VrListenerService components
     * can be started by sending the {@link android.provider.Settings#ACTION_VR_LISTENER_SETTINGS}
     * intent.</p>
     *
     * @param context the {@link Context} to use for looking up the requested component.
     * @param requestedComponent the name of the component that implements
     * {@link android.service.vr.VrListenerService} to check.
     *
     * @return {@code true} if this component is enabled in settings.
     *
     * @see android.provider.Settings#ACTION_VR_LISTENER_SETTINGS
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
