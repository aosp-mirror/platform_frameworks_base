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

import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import com.android.internal.gadget.IGadgetHost;
import com.android.internal.gadget.IGadgetService;

/**
 * GadgetHost provides the interaction with the Gadget Service for apps,
 * like the home screen, that want to embed gadgets in their UI.
 */
public class GadgetHost {

    static final int HANDLE_UPDATE = 1;
    static final int HANDLE_PROVIDER_CHANGED = 2;

    static Object sServiceLock = new Object();
    static IGadgetService sService;

    Context mContext;
    String mPackageName;

    class Callbacks extends IGadgetHost.Stub {
        public void updateGadget(int gadgetId, RemoteViews views) {
            Message msg = mHandler.obtainMessage(HANDLE_UPDATE);
            msg.arg1 = gadgetId;
            msg.obj = views;
            msg.sendToTarget();
        }

        public void providerChanged(int gadgetId, GadgetProviderInfo info) {
            Message msg = mHandler.obtainMessage(HANDLE_PROVIDER_CHANGED);
            msg.arg1 = gadgetId;
            msg.obj = info;
            msg.sendToTarget();
        }
    }

    Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case HANDLE_UPDATE: {
                    updateGadgetView(msg.arg1, (RemoteViews)msg.obj);
                    break;
                }
                case HANDLE_PROVIDER_CHANGED: {
                    onProviderChanged(msg.arg1, (GadgetProviderInfo)msg.obj);
                    break;
                }
            }
        }
    };

    int mHostId;
    Callbacks mCallbacks = new Callbacks();
    HashMap<Integer,GadgetHostView> mViews = new HashMap();

    public GadgetHost(Context context, int hostId) {
        mContext = context;
        mHostId = hostId;
        synchronized (sServiceLock) {
            if (sService == null) {
                IBinder b = ServiceManager.getService(Context.GADGET_SERVICE);
                sService = IGadgetService.Stub.asInterface(b);
            }
        }
    }

    /**
     * Start receiving onGadgetChanged calls for your gadgets.  Call this when your activity
     * becomes visible, i.e. from onStart() in your Activity.
     */
    public void startListening() {
        int[] updatedIds = null;
        ArrayList<RemoteViews> updatedViews = new ArrayList();
        
        try {
            if (mPackageName == null) {
                mPackageName = mContext.getPackageName();
            }
            updatedIds = sService.startListening(mCallbacks, mPackageName, mHostId, updatedViews);
        }
        catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }

        final int N = updatedIds.length;
        for (int i=0; i<N; i++) {
            updateGadgetView(updatedIds[i], updatedViews.get(i));
        }
    }

    /**
     * Stop receiving onGadgetChanged calls for your gadgets.  Call this when your activity is
     * no longer visible, i.e. from onStop() in your Activity.
     */
    public void stopListening() {
        try {
            sService.stopListening(mHostId);
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
    public int allocateGadgetId() {
        try {
            if (mPackageName == null) {
                mPackageName = mContext.getPackageName();
            }
            return sService.allocateGadgetId(mPackageName, mHostId);
        }
        catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }
    }

    /**
     * Stop listening to changes for this gadget.  
     */
    public void deleteGadgetId(int gadgetId) {
        synchronized (mViews) {
            mViews.remove(gadgetId);
            try {
                sService.deleteGadgetId(gadgetId);
            }
            catch (RemoteException e) {
                throw new RuntimeException("system server dead?", e);
            }
        }
    }

    /**
     * Remove all records about this host from the gadget manager.
     * <ul>
     *   <li>Call this when initializing your database, as it might be because of a data wipe.</li>
     *   <li>Call this to have the gadget manager release all resources associated with your
     *   host.  Any future calls about this host will cause the records to be re-allocated.</li>
     * </ul>
     */
    public void deleteHost() {
        try {
            sService.deleteHost(mHostId);
        }
        catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }
    }

    /**
     * Remove all records about all hosts for your package.
     * <ul>
     *   <li>Call this when initializing your database, as it might be because of a data wipe.</li>
     *   <li>Call this to have the gadget manager release all resources associated with your
     *   host.  Any future calls about this host will cause the records to be re-allocated.</li>
     * </ul>
     */
    public static void deleteAllHosts() {
        try {
            sService.deleteAllHosts();
        }
        catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }
    }

    public final GadgetHostView createView(Context context, int gadgetId,
            GadgetProviderInfo gadget) {
        GadgetHostView view = onCreateView(context, gadgetId, gadget);
        view.setGadget(gadgetId, gadget);
        synchronized (mViews) {
            mViews.put(gadgetId, view);
        }
        RemoteViews views = null;
        try {
            views = sService.getGadgetViews(gadgetId);
        } catch (RemoteException e) {
            throw new RuntimeException("system server dead?", e);
        }
        view.updateGadget(views);
        return view;
    }

    /**
     * Called to create the GadgetHostView.  Override to return a custom subclass if you
     * need it.  {@more}
     */
    protected GadgetHostView onCreateView(Context context, int gadgetId,
            GadgetProviderInfo gadget) {
        return new GadgetHostView(context);
    }
    
    /**
     * Called when the gadget provider for a gadget has been upgraded to a new apk.
     */
    protected void onProviderChanged(int gadgetId, GadgetProviderInfo gadget) {
    }

    void updateGadgetView(int gadgetId, RemoteViews views) {
        GadgetHostView v;
        synchronized (mViews) {
            v = mViews.get(gadgetId);
        }
        if (v != null) {
            v.updateGadget(views);
        }
    }
}


