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

public class GadgetManager {
    static final String TAG = "GadgetManager";

    /**
     * Send this when you want to pick a gadget to display.
     *
     * <p>
     * The system will respond with an onActivityResult call with the following extras in
     * the intent:
     * <ul>
     *   <li><b>gadgetId</b></li>
     *   <li><b>gadgetId</b></li>
     *   <li><b>gadgetId</b></li>
     * </ul>
     * TODO: Add constants for these.
     * TODO: Where does this go?
     */
    public static final String GADGET_PICK_ACTION = "android.gadget.action.PICK_GADGET";

    public static final String EXTRA_GADGET_ID = "gadgetId";

    /**
     * Sent when it is time to update your gadget.
     */
    public static final String GADGET_UPDATE_ACTION = "android.gadget.action.GADGET_UPDATE";

    /**
     * Sent when the gadget is added to a host for the first time. TODO: Maybe we don't want this.
     */
    public static final String GADGET_ENABLE_ACTION = "android.gadget.action.GADGET_ENABLE";

    /**
     * Sent when the gadget is removed from the last host. TODO: Maybe we don't want this.
     */
    public static final String GADGET_DISABLE_ACTION = "android.gadget.action.GADGET_DISABLE";

    /**
     * Field for the manifest meta-data tag.
     */
    public static final String GADGET_PROVIDER_META_DATA = "android.gadget.provider";

    static WeakHashMap<Context, WeakReference<GadgetManager>> sManagerCache = new WeakHashMap();
    static IGadgetService sService;
    
    Context mContext;

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
     * Call this with the new RemoteViews for your gadget whenever you need to.
     *
     * <p>
     * This method will only work when called from the uid that owns the gadget provider.
     *
     * @param gadgetId      The gadget instance for which to set the RemoteViews.
     * @param views         The RemoteViews object to show.
     */
    public void updateGadget(int gadgetId, RemoteViews views) {
    }

    /**
     * Return a list of the gadget providers that are currently installed.
     */
    public List<GadgetInfo> getInstalledProviders() {
        try {
            return sService.getInstalledProviders();
        }
        catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }
    }

    /**
     * Get the available info about the gadget.  If the gadgetId has not been bound yet,
     * this method will return null.
     *
     * TODO: throws GadgetNotFoundException ??? if not valid
     */
    public GadgetInfo getGadgetInfo(int gadgetId) {
        try {
            return sService.getGadgetInfo(gadgetId);
        }
        catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }
    }

    /**
     * Get a gadgetId for a host in the calling process.
     *
     * @return a gadgetId
     */
    public int allocateGadgetId(String hostPackage) {
        try {
            return sService.allocateGadgetId(hostPackage);
        }
        catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }
    }

    /**
     * Delete the gadgetId.  Same as removeGadget on GadgetHost.
     */
    public void deleteGadgetId(int gadgetId) {
        try {
            sService.deleteGadgetId(gadgetId);
        }
        catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }
    }

    /**
     * Set the component for a given gadgetId.  You need the GADGET_LIST permission.
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

