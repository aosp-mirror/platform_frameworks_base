/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.gadget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * A conveience class to aid in implementing a gadget provider.
 * Everything you can do with GadgetProvider, you can do with a regular {@link BroadcastReceiver}.
 * GadgetProvider merely parses the relevant fields out of the Intent that is received in
 * {@link #onReceive(Context,Intent) onReceive(Context,Intent)}, and calls hook methods
 * with the received extras.
 *
 * <p>Extend this class and override one or more of the {@link #onUpdate}, {@link #onDeleted},
 * {@link #onEnabled} or {@link #onDisabled} methods to implement your own gadget functionality.
 *
 * <h3>Sample Code</h3>
 * For an example of how to write a gadget provider, see the
 * <a href="{@toroot}reference/android/gadget/package-descr.html#providers">android.gadget
 * package overview</a>.
 */
public class GadgetProvider extends BroadcastReceiver {
    /**
     * Constructor to initialize GadgetProvider.
     */
    public GadgetProvider() {
    }

    /**
     * Implements {@link BroadcastReceiver#onReceive} to dispatch calls to the various
     * other methods on GadgetProvider.  
     *
     * @param context The Context in which the receiver is running.
     * @param intent The Intent being received.
     */
    // BEGIN_INCLUDE(onReceive)
    public void onReceive(Context context, Intent intent) {
        // Protect against rogue update broadcasts (not really a security issue,
        // just filter bad broacasts out so subclasses are less likely to crash).
        String action = intent.getAction();
        if (GadgetManager.GADGET_UPDATE_ACTION.equals(action)) {
            Bundle extras = intent.getExtras();
            if (extras != null) {
                int[] gadgetIds = extras.getIntArray(GadgetManager.EXTRA_GADGET_IDS);
                if (gadgetIds != null && gadgetIds.length > 0) {
                    this.onUpdate(context, GadgetManager.getInstance(context), gadgetIds);
                }
            }
        }
        else if (GadgetManager.GADGET_DELETED_ACTION.equals(action)) {
            Bundle extras = intent.getExtras();
            if (extras != null) {
                int[] gadgetIds = extras.getIntArray(GadgetManager.EXTRA_GADGET_IDS);
                if (gadgetIds != null && gadgetIds.length > 0) {
                    this.onDeleted(context, gadgetIds);
                }
            }
        }
        else if (GadgetManager.GADGET_ENABLED_ACTION.equals(action)) {
            this.onEnabled(context);
        }
        else if (GadgetManager.GADGET_DISABLED_ACTION.equals(action)) {
            this.onDisabled(context);
        }
    }
    // END_INCLUDE(onReceive)
    
    /**
     * Called in response to the {@link GadgetManager#GADGET_UPDATE_ACTION} broadcast when
     * this gadget provider is being asked to provide {@link android.widget.RemoteViews RemoteViews}
     * for a set of gadgets.  Override this method to implement your own gadget functionality.
     *
     * {@more}
     * <p class="note">If you want this method called, you must declare in an intent-filter in
     * your AndroidManifest.xml file that you accept the GADGET_UPDATE_ACTION intent action.
     * For example:
     * <font color=red>TODO: SAMPLE CODE GOES HERE</font>
     * </p>
     * 
     * @param context   The {@link android.content.Context Context} in which this receiver is
     *                  running.
     * @param gadgetManager A {@link GadgetManager} object you can call {@link
     *                  GadgetManager#updateGadgets} on.
     * @param gadgetIds The gadgetsIds for which an update is needed.  Note that this
     *                  may be all of the gadget instances for this provider, or just
     *                  a subset of them.
     *
     * @see GadgetManager#GADGET_UPDATE_ACTION
     */
    public void onUpdate(Context context, GadgetManager gadgetManager, int[] gadgetIds) {
    }
    
    /**
     * Called in response to the {@link GadgetManager#GADGET_DELETED_ACTION} broadcast when
     * one or more gadget instances have been deleted.  Override this method to implement
     * your own gadget functionality.
     *
     * {@more}
     * <p class="note">If you want this method called, you must declare in an intent-filter in
     * your AndroidManifest.xml file that you accept the GADGET_DELETED_ACTION intent action.
     * For example:
     * <font color=red>TODO: SAMPLE CODE GOES HERE</font>
     * </p>
     * 
     * @param context   The {@link android.content.Context Context} in which this receiver is
     *                  running.
     * @param gadgetIds The gadgetsIds that have been deleted from their host.
     *
     * @see GadgetManager#GADGET_DELETED_ACTION
     */
    public void onDeleted(Context context, int[] gadgetIds) {
    }

    /**
     * Called in response to the {@link GadgetManager#GADGET_ENABLED_ACTION} broadcast when
     * the a gadget for this provider is instantiated.  Override this method to implement your
     * own gadget functionality.
     *
     * {@more}
     * When the last gadget for this provider is deleted,
     * {@link GadgetManager#GADGET_DISABLED_ACTION} is sent and {@link #onDisabled}
     * is called.  If after that, a gadget for this provider is created again, onEnabled() will
     * be called again.
     *
     * <p class="note">If you want this method called, you must declare in an intent-filter in
     * your AndroidManifest.xml file that you accept the GADGET_ENABLED_ACTION intent action.
     * For example:
     * <font color=red>TODO: SAMPLE CODE GOES HERE</font>
     * </p>
     * 
     * @param context   The {@link android.content.Context Context} in which this receiver is
     *                  running.
     *
     * @see GadgetManager#GADGET_ENABLED_ACTION
     */
    public void onEnabled(Context context) {
    }

    /**
     * Called in response to the {@link GadgetManager#GADGET_DISABLED_ACTION} broadcast, which
     * is sent when the last gadget instance for this provider is deleted.  Override this method
     * to implement your own gadget functionality.
     *
     * {@more}
     * <p class="note">If you want this method called, you must declare in an intent-filter in
     * your AndroidManifest.xml file that you accept the GADGET_DISABLED_ACTION intent action.
     * For example:
     * <font color=red>TODO: SAMPLE CODE GOES HERE</font>
     * </p>
     * 
     * @param context   The {@link android.content.Context Context} in which this receiver is
     *                  running.
     *
     * @see GadgetManager#GADGET_DISABLED_ACTION
     */
    public void onDisabled(Context context) {
    }
}
