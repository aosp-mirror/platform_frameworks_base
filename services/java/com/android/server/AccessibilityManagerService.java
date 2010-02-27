/*
 ** Copyright 2009, The Android Open Source Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

package com.android.server;

import com.android.internal.content.PackageMonitor;
import com.android.internal.os.HandlerCaller;
import com.android.internal.os.HandlerCaller.SomeArgs;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.IAccessibilityServiceConnection;
import android.accessibilityservice.IEventListener;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.TextUtils.SimpleStringSplitter;
import android.util.Config;
import android.util.Slog;
import android.util.SparseArray;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.IAccessibilityManager;
import android.view.accessibility.IAccessibilityManagerClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This class is instantiated by the system as a system level service and can be
 * accessed only by the system. The task of this service is to be a centralized
 * event dispatch for {@link AccessibilityEvent}s generated across all processes
 * on the device. Events are dispatched to {@link AccessibilityService}s.
 *
 * @hide
 */
public class AccessibilityManagerService extends IAccessibilityManager.Stub
        implements HandlerCaller.Callback {

    private static final String LOG_TAG = "AccessibilityManagerService";

    private static int sIdCounter = 0;

    private static final int OWN_PROCESS_ID = android.os.Process.myPid();

    private static final int DO_SET_SERVICE_INFO = 10;

    final HandlerCaller mCaller;

    final Context mContext;

    final Object mLock = new Object();

    final List<Service> mServices = new ArrayList<Service>();

    final List<IAccessibilityManagerClient> mClients =
        new ArrayList<IAccessibilityManagerClient>();

    final Map<ComponentName, Service> mComponentNameToServiceMap =
        new HashMap<ComponentName, Service>();

    private final List<ServiceInfo> mInstalledServices = new ArrayList<ServiceInfo>();

    private final Set<ComponentName> mEnabledServices = new HashSet<ComponentName>();

    private final SimpleStringSplitter mStringColonSplitter = new SimpleStringSplitter(':');

    private PackageManager mPackageManager;

    private int mHandledFeedbackTypes = 0;

    private boolean mIsEnabled;

    /**
     * Handler for delayed event dispatch.
     */
    private Handler mHandler = new Handler() {

        @Override
        public void handleMessage(Message message) {
            Service service = (Service) message.obj;
            int eventType = message.arg1;

            synchronized (mLock) {
                notifyEventListenerLocked(service, eventType);
                AccessibilityEvent oldEvent = service.mPendingEvents.get(eventType);
                service.mPendingEvents.remove(eventType);
                tryRecycleLocked(oldEvent);
            }
        }
    };

    /**
     * Creates a new instance.
     *
     * @param context A {@link Context} instance.
     */
    AccessibilityManagerService(Context context) {
        mContext = context;
        mPackageManager = mContext.getPackageManager();
        mCaller = new HandlerCaller(context, this);

        registerPackageChangeAndBootCompletedBroadcastReceiver();
        registerSettingsContentObservers();
    }

    /**
     * Registers a {@link BroadcastReceiver} for the events of
     * adding/changing/removing/restarting a package and boot completion.
     */
    private void registerPackageChangeAndBootCompletedBroadcastReceiver() {
        Context context = mContext;

        PackageMonitor monitor = new PackageMonitor() {
            @Override
            public void onSomePackagesChanged() {
                synchronized (mLock) {
                    populateAccessibilityServiceListLocked();
                    manageServicesLocked();
                }
            }
            
            @Override
            public boolean onHandleForceStop(Intent intent, String[] packages,
                    int uid, boolean doit) {
                synchronized (mLock) {
                    boolean changed = false;
                    Iterator<ComponentName> it = mEnabledServices.iterator();
                    while (it.hasNext()) {
                        ComponentName comp = it.next();
                        String compPkg = comp.getPackageName();
                        for (String pkg : packages) {
                            if (compPkg.equals(pkg)) {
                                if (!doit) {
                                    return true;
                                }
                                it.remove();
                                changed = true;
                            }
                        }
                    }
                    if (changed) {
                        it = mEnabledServices.iterator();
                        StringBuilder str = new StringBuilder();
                        while (it.hasNext()) {
                            if (str.length() > 0) {
                                str.append(':');
                            }
                            str.append(it.next().flattenToShortString());
                        }
                        Settings.Secure.putString(mContext.getContentResolver(),
                                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                                str.toString());
                        manageServicesLocked();
                    }
                    return false;
                }
            }
            
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getAction() == Intent.ACTION_BOOT_COMPLETED) {
                    synchronized (mLock) {
                        populateAccessibilityServiceListLocked();

                        // get the accessibility enabled setting on boot
                        mIsEnabled = Settings.Secure.getInt(mContext.getContentResolver(),
                                Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1;

                        // if accessibility is enabled inform our clients we are on
                        if (mIsEnabled) {
                            updateClientsLocked();
                        }

                        manageServicesLocked();
                    }
                    
                    return;
                }
                
                super.onReceive(context, intent);
            }
        };

        // package changes
        monitor.register(context, true);

        // boot completed
        IntentFilter bootFiler = new IntentFilter(Intent.ACTION_BOOT_COMPLETED);
        mContext.registerReceiver(monitor, bootFiler);
    }

    /**
     * {@link ContentObserver}s for {@link Settings.Secure#ACCESSIBILITY_ENABLED}
     * and {@link Settings.Secure#ENABLED_ACCESSIBILITY_SERVICES} settings.
     */
    private void registerSettingsContentObservers() {
        ContentResolver contentResolver = mContext.getContentResolver();

        Uri enabledUri = Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED);
        contentResolver.registerContentObserver(enabledUri, false,
            new ContentObserver(new Handler()) {
                @Override
                public void onChange(boolean selfChange) {
                    super.onChange(selfChange);

                    mIsEnabled = Settings.Secure.getInt(mContext.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1;

                    synchronized (mLock) {
                        if (mIsEnabled) {
                            manageServicesLocked();
                        } else {
                            unbindAllServicesLocked();
                        }
                        updateClientsLocked();
                    }
                }
            });

        Uri providersUri =
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        contentResolver.registerContentObserver(providersUri, false,
            new ContentObserver(new Handler()) {
                @Override
                public void onChange(boolean selfChange) {
                    super.onChange(selfChange);

                    synchronized (mLock) {
                        manageServicesLocked();
                    }
                }
            });
    }

    public void addClient(IAccessibilityManagerClient client) {
        synchronized (mLock) {
            try {
                client.setEnabled(mIsEnabled);
                mClients.add(client);
            } catch (RemoteException re) {
                Slog.w(LOG_TAG, "Dead AccessibilityManagerClient: " + client, re);
            }
        }
    }

    public boolean sendAccessibilityEvent(AccessibilityEvent event) {
        synchronized (mLock) {
            notifyAccessibilityServicesDelayedLocked(event, false);
            notifyAccessibilityServicesDelayedLocked(event, true);
        }
        // event not scheduled for dispatch => recycle
        if (mHandledFeedbackTypes == 0) {
            event.recycle();
        } else {
            mHandledFeedbackTypes = 0;
        }

        return (OWN_PROCESS_ID != Binder.getCallingPid());
    }

    public List<ServiceInfo> getAccessibilityServiceList() {
        synchronized (mLock) {
            return mInstalledServices;
        }
    }

    public void interrupt() {
        synchronized (mLock) {
            for (int i = 0, count = mServices.size(); i < count; i++) {
                Service service = mServices.get(i);
                try {
                    service.mServiceInterface.onInterrupt();
                } catch (RemoteException re) {
                    if (re instanceof DeadObjectException) {
                        Slog.w(LOG_TAG, "Dead " + service.mService + ". Cleaning up.");
                        if (removeDeadServiceLocked(service)) {
                            count--;
                            i--;
                        }
                    } else {
                        Slog.e(LOG_TAG, "Error during sending interrupt request to "
                                + service.mService, re);
                    }
                }
            }
        }
    }

    public void executeMessage(Message message) {
        switch (message.what) {
            case DO_SET_SERVICE_INFO:
                SomeArgs arguments = ((SomeArgs) message.obj);

                AccessibilityServiceInfo info = (AccessibilityServiceInfo) arguments.arg1;
                Service service = (Service) arguments.arg2;

                synchronized (mLock) {
                    service.mEventTypes = info.eventTypes;
                    service.mFeedbackType = info.feedbackType;
                    String[] packageNames = info.packageNames;
                    if (packageNames != null) {
                        service.mPackageNames.addAll(Arrays.asList(packageNames));
                    }
                    service.mNotificationTimeout = info.notificationTimeout;
                    service.mIsDefault = (info.flags & AccessibilityServiceInfo.DEFAULT) != 0;
                }
                return;
            default:
                Slog.w(LOG_TAG, "Unknown message type: " + message.what);
        }
    }

    /**
     * Populates the cached list of installed {@link AccessibilityService}s.
     */
    private void populateAccessibilityServiceListLocked() {
        mInstalledServices.clear();

        List<ResolveInfo> installedServices = mPackageManager.queryIntentServices(
                new Intent(AccessibilityService.SERVICE_INTERFACE), PackageManager.GET_SERVICES);

        for (int i = 0, count = installedServices.size(); i < count; i++) {
            mInstalledServices.add(installedServices.get(i).serviceInfo);
        }
    }

    /**
     * Performs {@link AccessibilityService}s delayed notification. The delay is configurable
     * and denotes the period after the last event before notifying the service.
     *
     * @param event The event.
     * @param isDefault True to notify default listeners, not default services.
     */
    private void notifyAccessibilityServicesDelayedLocked(AccessibilityEvent event,
            boolean isDefault) {
        try {
            for (int i = 0, count = mServices.size(); i < count; i++) {
                Service service = mServices.get(i);

                if (service.mIsDefault == isDefault) {
                    if (canDispathEventLocked(service, event, mHandledFeedbackTypes)) {
                        mHandledFeedbackTypes |= service.mFeedbackType;
                        notifyAccessibilityServiceDelayedLocked(service, event);
                    }
                }
            }
        } catch (IndexOutOfBoundsException oobe) {
            // An out of bounds exception can happen if services are going away
            // as the for loop is running. If that happens, just bail because
            // there are no more services to notify.
            return;
        }
    }

    /**
     * Performs an {@link AccessibilityService} delayed notification. The delay is configurable
     * and denotes the period after the last event before notifying the service.
     *
     * @param service The service.
     * @param event The event.
     */
    private void notifyAccessibilityServiceDelayedLocked(Service service,
            AccessibilityEvent event) {
        synchronized (mLock) {
            int eventType = event.getEventType();
            AccessibilityEvent oldEvent = service.mPendingEvents.get(eventType);
            service.mPendingEvents.put(eventType, event);

            int what = eventType | (service.mId << 16);
            if (oldEvent != null) {
                mHandler.removeMessages(what);
                tryRecycleLocked(oldEvent);
            }

            Message message = mHandler.obtainMessage(what, service);
            message.arg1 = event.getEventType();
            mHandler.sendMessageDelayed(message, service.mNotificationTimeout);
        }
    }

    /**
     * Recycles an event if it can be safely recycled. The condition is that no
     * not notified service is interested in the event.
     *
     * @param event The event.
     */
    private void tryRecycleLocked(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        int eventType = event.getEventType();
        List<Service> services = mServices;

        // linear in the number of service which is not large
        for (int i = 0, count = services.size(); i < count; i++) {
            Service service = services.get(i);
            if (service.mPendingEvents.get(eventType) == event) {
                return;
            }
        }
        event.recycle();
    }

    /**
     * Notifies a service for a scheduled event given the event type.
     *
     * @param service The service.
     * @param eventType The type of the event to dispatch.
     */
    private void notifyEventListenerLocked(Service service, int eventType) {
        IEventListener listener = service.mServiceInterface;
        AccessibilityEvent event = service.mPendingEvents.get(eventType);

        try {
            listener.onAccessibilityEvent(event);
            if (Config.DEBUG) {
                Slog.i(LOG_TAG, "Event " + event + " sent to " + listener);
            }
        } catch (RemoteException re) {
            if (re instanceof DeadObjectException) {
                Slog.w(LOG_TAG, "Dead " + service.mService + ". Cleaning up.");
                synchronized (mLock) {
                    removeDeadServiceLocked(service);
                }
            } else {
                Slog.e(LOG_TAG, "Error during sending " + event + " to " + service.mService, re);
            }
        }
    }

    /**
     * Removes a dead service.
     *
     * @param service The service.
     * @return True if the service was removed, false otherwise.
     */
    private boolean removeDeadServiceLocked(Service service) {
        mServices.remove(service);
        mHandler.removeMessages(service.mId);

        if (Config.DEBUG) {
            Slog.i(LOG_TAG, "Dead service " + service.mService + " removed");
        }

        if (mServices.isEmpty()) {
            mIsEnabled = false;
            updateClientsLocked();
        }

        return true;
    }

    /**
     * Determines if given event can be dispatched to a service based on the package of the
     * event source and already notified services for that event type. Specifically, a
     * service is notified if it is interested in events from the package and no other service
     * providing the same feedback type has been notified. Exception are services the
     * provide generic feedback (feedback type left as a safety net for unforeseen feedback
     * types) which are always notified.
     *
     * @param service The potential receiver.
     * @param event The event.
     * @param handledFeedbackTypes The feedback types for which services have been notified.
     * @return True if the listener should be notified, false otherwise.
     */
    private boolean canDispathEventLocked(Service service, AccessibilityEvent event,
            int handledFeedbackTypes) {

        if (!service.isConfigured()) {
            return false;
        }

        if (!service.mService.isBinderAlive()) {
            removeDeadServiceLocked(service);
            return false;
        }

        int eventType = event.getEventType();
        if ((service.mEventTypes & eventType) != eventType) {
            return false;
        }

        Set<String> packageNames = service.mPackageNames;
        CharSequence packageName = event.getPackageName();

        if (packageNames.isEmpty() || packageNames.contains(packageName)) {
            int feedbackType = service.mFeedbackType;
            if ((handledFeedbackTypes & feedbackType) != feedbackType
                    || feedbackType == AccessibilityServiceInfo.FEEDBACK_GENERIC) {
                return true;
            }
        }

        return false;
    }

    /**
     * Manages services by starting enabled ones and stopping disabled ones.
     */
    private void manageServicesLocked() {
        populateEnabledServicesLocked(mEnabledServices);
        updateServicesStateLocked(mInstalledServices, mEnabledServices);
    }

    /**
     * Unbinds all bound services.
     */
    private void unbindAllServicesLocked() {
        List<Service> services = mServices;

        for (int i = 0, count = services.size(); i < count; i++) {
            Service service = services.get(i);

            service.unbind();
            mComponentNameToServiceMap.remove(service.mComponentName);
        }
        services.clear();
    }

    /**
     * Populates a list with the {@link ComponentName}s of all enabled
     * {@link AccessibilityService}s.
     *
     * @param enabledServices The list.
     */
    private void populateEnabledServicesLocked(Set<ComponentName> enabledServices) {
        enabledServices.clear();

        String servicesValue = Settings.Secure.getString(mContext.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

        if (servicesValue != null) {
            TextUtils.SimpleStringSplitter splitter = mStringColonSplitter;
            splitter.setString(servicesValue);
            while (splitter.hasNext()) {
                String str = splitter.next();
                if (str == null || str.length() <= 0) {
                    continue;
                }
                ComponentName enabledService = ComponentName.unflattenFromString(str);
                if (enabledService != null) {
                    enabledServices.add(enabledService);
                }
            }
        }
    }

    /**
     * Updates the state of each service by starting (or keeping running) enabled ones and
     * stopping the rest.
     *
     * @param installedServices All installed {@link AccessibilityService}s.
     * @param enabledServices The {@link ComponentName}s of the enabled services.
     */
    private void updateServicesStateLocked(List<ServiceInfo> installedServices,
            Set<ComponentName> enabledServices) {

        Map<ComponentName, Service> componentNameToServiceMap = mComponentNameToServiceMap;
        List<Service> services = mServices;
        boolean isEnabled = mIsEnabled;

        for (int i = 0, count = installedServices.size(); i < count; i++) {
            ServiceInfo intalledService = installedServices.get(i);
            ComponentName componentName = new ComponentName(intalledService.packageName,
                    intalledService.name);
            Service service = componentNameToServiceMap.get(componentName);

            if (isEnabled && enabledServices.contains(componentName)) {
                if (service == null) {
                    new Service(componentName).bind();
                }
            } else {
                if (service != null) {
                    service.unbind();
                    componentNameToServiceMap.remove(componentName);
                    services.remove(service);
                }
            }
        }
    }

    /**
     * Updates the state of {@link android.view.accessibility.AccessibilityManager} clients.
     */
    private void updateClientsLocked() {
        for (int i = 0, count = mClients.size(); i < count; i++) {
            try {
                mClients.get(i).setEnabled(mIsEnabled);
            } catch (RemoteException re) {
                mClients.remove(i);
                count--;
                i--;
            }
        }
    }

    /**
     * This class represents an accessibility service. It stores all per service
     * data required for the service management, provides API for starting/stopping the
     * service and is responsible for adding/removing the service in the data structures
     * for service management. The class also exposes configuration interface that is
     * passed to the service it represents as soon it is bound. It also serves as the
     * connection for the service.
     */
    class Service extends IAccessibilityServiceConnection.Stub implements ServiceConnection {
        int mId = 0;

        IBinder mService;

        IEventListener mServiceInterface;

        int mEventTypes;

        int mFeedbackType;

        Set<String> mPackageNames = new HashSet<String>();

        boolean mIsDefault;

        long mNotificationTimeout;

        boolean mIsActive;

        ComponentName mComponentName;

        Intent mIntent;

        // the events pending events to be dispatched to this service
        final SparseArray<AccessibilityEvent> mPendingEvents =
            new SparseArray<AccessibilityEvent>();

        Service(ComponentName componentName) {
            mId = sIdCounter++;
            mComponentName = componentName;
            mIntent = new Intent().setComponent(mComponentName);
            mIntent.putExtra(Intent.EXTRA_CLIENT_LABEL,
                    com.android.internal.R.string.accessibility_binding_label);
            mIntent.putExtra(Intent.EXTRA_CLIENT_INTENT, PendingIntent.getActivity(
                    mContext, 0, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), 0));
        }

        /**
         * Binds to the accessibility service.
         */
        public void bind() {
            if (mService == null) {
                mContext.bindService(mIntent, this, Context.BIND_AUTO_CREATE);
            }
        }

        /**
         * Unbinds form the accessibility service and removes it from the data
         * structures for service management.
         */
        public void unbind() {
            if (mService != null) {
                mContext.unbindService(this);
            }
        }

        /**
         * Returns if the service is configured i.e. at least event types of interest
         * and feedback type must be set.
         *
         * @return True if the service is configured, false otherwise.
         */
        public boolean isConfigured() {
            return (mEventTypes != 0 && mFeedbackType != 0);
        }

        public void setServiceInfo(AccessibilityServiceInfo info) {
            mCaller.obtainMessageOO(DO_SET_SERVICE_INFO, info, this).sendToTarget();
        }

        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mService = service;
            mServiceInterface = IEventListener.Stub.asInterface(service);

            try {
                mServiceInterface.setConnection(this);
                synchronized (mLock) {
                    if (!mServices.contains(this)) {
                        mServices.add(this);
                        mComponentNameToServiceMap.put(componentName, this);
                    }
                }
            } catch (RemoteException re) {
                Slog.w(LOG_TAG, "Error while setting Controller for service: " + service, re);
            }
        }

        public void onServiceDisconnected(ComponentName componentName) {
            synchronized (mLock) {
                Service service = mComponentNameToServiceMap.remove(componentName);
                mServices.remove(service);
            }
        }
    }
}
