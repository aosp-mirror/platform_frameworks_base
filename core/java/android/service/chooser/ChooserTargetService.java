/*
 * Copyright (C) 2015 The Android Open Source Project
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


package android.service.chooser;

import android.annotation.SdkConstant;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.List;

/**
 * A service that receives calls from the system when the user is asked to choose
 * a target for an intent explicitly by another app. The calling app must have invoked
 * {@link android.content.Intent#ACTION_CHOOSER ACTION_CHOOSER} as handled by the system;
 * applications do not have the ability to query a ChooserTargetService directly.
 *
 * <p>Which ChooserTargetServices are queried depends on a system-level policy decision
 * made at the moment the chooser is invoked, including but not limited to user time
 * spent with the app package or associated components in the foreground, recency of usage
 * or frequency of usage. These will generally correlate with the order that app targets
 * are shown in the list of intent handlers shown in the system chooser or resolver.</p>
 *
 * <p>To extend this class, you must declare the service in your manifest file with
 * the {@link android.Manifest.permission#BIND_CHOOSER_TARGET_SERVICE} permission
 * and include an intent filter with the {@link #SERVICE_INTERFACE} action. For example:</p>
 * <pre>
 *     &lt;service android:name=".MyChooserTargetService"
 *             android:label="&#64;string/service_name"
 *             android:permission="android.permission.BIND_CHOOSER_TARGET_SERVICE">
 *         &lt;intent-filter>
 *             &lt;action android:name="android.service.chooser.ChooserTargetService" />
 *         &lt;/intent-filter>
 *     &lt;/service>
 * </pre>
 *
 * <p>For the system to query your service, you must add a &lt;meta-data> element to the
 * Activity in your manifest that can handle Intents that you would also like to provide
 * optional deep links for. For example, a chat app might offer deep links to recent active
 * conversations instead of invoking a generic picker after the app itself is chosen as a target.
 * </p>
 *
 * <p>The meta-data element should have the name
 * <code>android.service.chooser.chooser_target_service</code> and a value corresponding to
 * the component name of your service. Example:</p>
 * <pre>
 *     &lt;activity android:name=".MyShareActivity"
 *             android:label="&#64;string/share_activity_label">
 *         &lt;intent-filter>
 *             &lt;action android:name="android.intent.action.SEND" />
 *         &lt;/intent-filter>
 *         &lt;meta-data android:name="android.service.chooser.chooser_target_service"
 *                 android:value=".MyChooserTargetService" />
 *     &lt;/activity>
 * </pre>
 */
public abstract class ChooserTargetService extends Service {
    // TAG = "ChooserTargetService[MySubclass]";
    private final String TAG = ChooserTargetService.class.getSimpleName()
            + '[' + getClass().getSimpleName() + ']';

    private static final boolean DEBUG = false;

    /**
     * The Intent action that a ChooserTargetService must respond to
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE = "android.service.chooser.ChooserTargetService";

    /**
     * The name of the <code>meta-data</code> element that must be present on an
     * <code>activity</code> element in a manifest to link it to a ChooserTargetService
     */
    public static final String META_DATA_NAME = "android.service.chooser.chooser_target_service";

    /**
     * The permission that a ChooserTargetService must require in order to bind to it.
     * If this permission is not enforced the system will skip that ChooserTargetService.
     */
    public static final String BIND_PERMISSION = "android.permission.BIND_CHOOSER_TARGET_SERVICE";

    private IChooserTargetServiceWrapper mWrapper = null;

    /**
     * Called by the system to retrieve a set of deep-link {@link ChooserTarget targets} that
     * can handle an intent.
     *
     * <p>The returned list should be sorted such that the most relevant targets appear first.
     * The score for each ChooserTarget will be combined with the system's score for the original
     * target Activity to sort and filter targets presented to the user.</p>
     *
     * <p><em>Important:</em> Calls to this method from other applications will occur on
     * a binder thread, not on your app's main thread. Make sure that access to relevant data
     * within your app is thread-safe.</p>
     *
     * @param targetActivityName the ComponentName of the matched activity that referred the system
     *                           to this ChooserTargetService
     * @param matchedFilter the specific IntentFilter on the component that was matched
     * @return a list of deep-link targets to fulfill the intent match, sorted by relevance
     */
    public abstract List<ChooserTarget> onGetChooserTargets(ComponentName targetActivityName,
            IntentFilter matchedFilter);

    @Override
    public IBinder onBind(Intent intent) {
        if (DEBUG) Log.d(TAG, "onBind " + intent);
        if (!SERVICE_INTERFACE.equals(intent.getAction())) {
            if (DEBUG) Log.d(TAG, "bad intent action " + intent.getAction() + "; returning null");
            return null;
        }

        if (mWrapper == null) {
            mWrapper = new IChooserTargetServiceWrapper();
        }
        return mWrapper;
    }

    private class IChooserTargetServiceWrapper extends IChooserTargetService.Stub {
        @Override
        public void getChooserTargets(ComponentName targetComponentName,
                IntentFilter matchedFilter, IChooserTargetResult result) throws RemoteException {
            List<ChooserTarget> targets = null;
            try {
                if (DEBUG) {
                    Log.d(TAG, "getChooserTargets calling onGetChooserTargets; "
                            + targetComponentName + " filter: " + matchedFilter);
                }
                targets = onGetChooserTargets(targetComponentName, matchedFilter);
            } finally {
                result.sendResult(targets);
                if (DEBUG) Log.d(TAG, "Sent results");
            }
        }
    }
}
