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

import android.content.ComponentName;
import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.widget.RemoteViews;

import com.android.internal.gadget.IGadgetService;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.WeakHashMap;

/**
 * Updates gadget state; gets information about installed gadget providers and other
 * gadget related state.
 */
public class GadgetManager {
    static final String TAG = "GadgetManager";

    /**
     * Send this from your gadget host activity when you want to pick a gadget to display.
     * The gadget picker activity will be launched.
     * <p>
     * You must supply the following extras:
     * <table>
     *   <tr>
     *     <td>{@link #EXTRA_GADGET_ID}</td>
     *     <td>A newly allocated gadgetId, which will be bound to the gadget provider
     *         once the user has selected one.</td>
     *  </tr>
     * </table>
     *
     * <p>
     * The system will respond with an onActivityResult call with the following extras in
     * the intent:
     * <table>
     *   <tr>
     *     <td>{@link #EXTRA_GADGET_ID}</td>
     *     <td>The gadgetId that you supplied in the original intent.</td>
     *  </tr>
     * </table>
     * <p>
     * When you receive the result from the gadget pick activity, if the resultCode is
     * {@link android.app.Activity#RESULT_OK}, a gadget has been selected.  You should then
     * check the GadgetProviderInfo for the returned gadget, and if it has one, launch its configuration
     * activity.  If {@link android.app.Activity#RESULT_CANCELED} is returned, you should delete
     * the gadgetId.
     *
     * @see #ACTION_GADGET_CONFIGURE
     */
    public static final String ACTION_GADGET_PICK = "android.gadget.action.GADGET_PICK";

    /**
     * Sent when it is time to configure your gadget while it is being added to a host.
     * This action is not sent as a broadcast to the gadget provider, but as a startActivity
     * to the activity specified in the {@link GadgetProviderInfo GadgetProviderInfo meta-data}.
     *
     * <p>
     * The intent will contain the following extras:
     * <table>
     *   <tr>
     *     <td>{@link #EXTRA_GADGET_ID}</td>
     *     <td>The gadgetId to configure.</td>
     *  </tr>
     * </table>
     *
     * <p>If you return {@link android.app.Activity#RESULT_OK} using
     * {@link android.app.Activity#setResult Activity.setResult()}, the gadget will be added,
     * and you will receive an {@link #ACTION_GADGET_UPDATE} broadcast for this gadget.
     * If you return {@link android.app.Activity#RESULT_CANCELED}, the host will cancel the add
     * and not display this gadget, and you will receive a {@link #ACTION_GADGET_DELETED} broadcast.
     */
    public static final String ACTION_GADGET_CONFIGURE = "android.gadget.action.GADGET_CONFIGURE";

    /**
     * An intent extra that contains one gadgetId.
     * <p>
     * The value will be an int that can be retrieved like this:
     * {@sample frameworks/base/tests/gadgets/GadgetHostTest/src/com/android/tests/gadgethost/GadgetHostActivity.java getExtra_EXTRA_GADGET_ID}
     */
    public static final String EXTRA_GADGET_ID = "gadgetId";

    /**
     * An intent extra that contains multiple gadgetIds.
     * <p>
     * The value will be an int array that can be retrieved like this:
     * {@sample frameworks/base/tests/gadgets/GadgetHostTest/src/com/android/tests/gadgethost/TestGadgetProvider.java getExtra_EXTRA_GADGET_IDS}
     */
    public static final String EXTRA_GADGET_IDS = "gadgetIds";

    /**
     * A sentiel value that the gadget manager will never return as a gadgetId.
     */
    public static final int INVALID_GADGET_ID = 0;

    /**
     * Sent when it is time to update your gadget.
     *
     * <p>This may be sent in response to a new instance for this gadget provider having
     * been instantiated, the requested {@link GadgetProviderInfo#updatePeriodMillis update interval}
     * having lapsed, or the system booting.
     *
     * <p>
     * The intent will contain the following extras:
     * <table>
     *   <tr>
     *     <td>{@link #EXTRA_GADGET_IDS}</td>
     *     <td>The gadgetIds to update.  This may be all of the gadgets created for this
     *     provider, or just a subset.  The system tries to send updates for as few gadget
     *     instances as possible.</td>
     *  </tr>
     * </table>
     * 
     * @see GadgetProvider#onUpdate GadgetProvider.onUpdate(Context context, GadgetManager gadgetManager, int[] gadgetIds)
     */
    public static final String ACTION_GADGET_UPDATE = "android.gadget.action.GADGET_UPDATE";

    /**
     * Sent when an instance of a gadget is deleted from its host.
     *
     * @see GadgetProvider#onDeleted GadgetProvider.onDeleted(Context context, int[] gadgetIds)
     */
    public static final String ACTION_GADGET_DELETED = "android.gadget.action.GADGET_DELETED";

    /**
     * Sent when an instance of a gadget is removed from the last host.
     * 
     * @see GadgetProvider#onEnabled GadgetProvider.onEnabled(Context context)
     */
    public static final String ACTION_GADGET_DISABLED = "android.gadget.action.GADGET_DISABLED";

    /**
     * Sent when an instance of a gadget is added to a host for the first time.
     * This broadcast is sent at boot time if there is a gadget host installed with
     * an instance for this provider.
     * 
     * @see GadgetProvider#onEnabled GadgetProvider.onEnabled(Context context)
     */
    public static final String ACTION_GADGET_ENABLED = "android.gadget.action.GADGET_ENABLED";

    /**
     * Field for the manifest meta-data tag.
     *
     * @see GadgetProviderInfo
     */
    public static final String META_DATA_GADGET_PROVIDER = "android.gadget.provider";

    static WeakHashMap<Context, WeakReference<GadgetManager>> sManagerCache = new WeakHashMap();
    static IGadgetService sService;
    
    Context mContext;

    /**
     * Get the GadgetManager instance to use for the supplied {@link android.content.Context
     * Context} object.
     */
    public static GadgetManager getInstance(Context context) {
        synchronized (sManagerCache) {
            if (sService == null) {
                IBinder b = ServiceManager.getService(Context.GADGET_SERVICE);
                sService = IGadgetService.Stub.asInterface(b);
            }

            WeakReference<GadgetManager> ref = sManagerCache.get(context);
            GadgetManager result = null;
            if (ref != null) {
                result = ref.get();
            }
            if (result == null) {
                result = new GadgetManager(context);
                sManagerCache.put(context, new WeakReference(result));
            }
            return result;
        }
    }

    private GadgetManager(Context context) {
        mContext = context;
    }

    /**
     * Set the RemoteViews to use for the specified gadgetIds.
     *
     * <p>
     * It is okay to call this method both inside an {@link #ACTION_GADGET_UPDATE} broadcast,
     * and outside of the handler.
     * This method will only work when called from the uid that owns the gadget provider.
     *
     * @param gadgetIds     The gadget instances for which to set the RemoteViews.
     * @param views         The RemoteViews object to show.
     */
    public void updateGadget(int[] gadgetIds, RemoteViews views) {
        try {
            sService.updateGadgetIds(gadgetIds, views);
        }
        catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }
    }

    /**
     * Set the RemoteViews to use for the specified gadgetId.
     *
     * <p>
     * It is okay to call this method both inside an {@link #ACTION_GADGET_UPDATE} broadcast,
     * and outside of the handler.
     * This method will only work when called from the uid that owns the gadget provider.
     *
     * @param gadgetId      The gadget instance for which to set the RemoteViews.
     * @param views         The RemoteViews object to show.
     */
    public void updateGadget(int gadgetId, RemoteViews views) {
        updateGadget(new int[] { gadgetId }, views);
    }

    /**
     * Set the RemoteViews to use for all gadget instances for the supplied gadget provider.
     *
     * <p>
     * It is okay to call this method both inside an {@link #ACTION_GADGET_UPDATE} broadcast,
     * and outside of the handler.
     * This method will only work when called from the uid that owns the gadget provider.
     *
     * @param provider      The {@link ComponentName} for the {@link
     * android.content.BroadcastReceiver BroadcastReceiver} provider
     *                      for your gadget.
     * @param views         The RemoteViews object to show.
     */
    public void updateGadget(ComponentName provider, RemoteViews views) {
        try {
            sService.updateGadgetProvider(provider, views);
        }
        catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }
    }

    /**
     * Return a list of the gadget providers that are currently installed.
     */
    public List<GadgetProviderInfo> getInstalledProviders() {
        try {
            return sService.getInstalledProviders();
        }
        catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }
    }

    /**
     * Get the available info about the gadget.
     *
     * @return A gadgetId.  If the gadgetId has not been bound to a provider yet, or
     * you don't have access to that gadgetId, null is returned.
     */
    public GadgetProviderInfo getGadgetInfo(int gadgetId) {
        try {
            return sService.getGadgetInfo(gadgetId);
        }
        catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }
    }

    /**
     * Set the component for a given gadgetId.
     *
     * <p class="note">You need the GADGET_LIST permission.  This method is to be used by the
     * gadget picker.
     *
     * @param gadgetId     The gadget instance for which to set the RemoteViews.
     * @param provider      The {@link android.content.BroadcastReceiver} that will be the gadget
     *                      provider for this gadget.
     */
    public void bindGadgetId(int gadgetId, ComponentName provider) {
        try {
            sService.bindGadgetId(gadgetId, provider);
        }
        catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }
    }
}

