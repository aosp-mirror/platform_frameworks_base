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

package com.android.server.accessibility;

import android.Manifest;
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
import android.database.ContentObserver;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.TextUtils.SimpleStringSplitter;
import android.util.Slog;
import android.util.SparseArray;
import android.view.IWindow;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.IAccessibilityInteractionConnection;
import android.view.accessibility.IAccessibilityInteractionConnectionCallback;
import android.view.accessibility.IAccessibilityManager;
import android.view.accessibility.IAccessibilityManagerClient;

import com.android.internal.content.PackageMonitor;
import com.android.internal.os.HandlerCaller;
import com.android.internal.os.HandlerCaller.SomeArgs;
import com.android.server.wm.WindowManagerService;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

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

    private static final boolean DEBUG = false;

    private static final String LOG_TAG = "AccessibilityManagerService";

    private static final String FUNCTION_REGISTER_EVENT_LISTENER =
        "registerEventListener";

    private static int sIdCounter = 0;

    private static final int OWN_PROCESS_ID = android.os.Process.myPid();

    private static final int DO_SET_SERVICE_INFO = 10;

    private static int sNextWindowId;

    final HandlerCaller mCaller;

    final Context mContext;

    final Object mLock = new Object();

    final List<Service> mServices = new ArrayList<Service>();

    final List<IAccessibilityManagerClient> mClients =
        new ArrayList<IAccessibilityManagerClient>();

    final Map<ComponentName, Service> mComponentNameToServiceMap = new HashMap<ComponentName, Service>();

    private final List<AccessibilityServiceInfo> mInstalledServices = new ArrayList<AccessibilityServiceInfo>();

    private final Set<ComponentName> mEnabledServices = new HashSet<ComponentName>();

    private final SparseArray<IAccessibilityInteractionConnection> mWindowIdToInteractionConnectionMap =
        new SparseArray<IAccessibilityInteractionConnection>();

    private final SparseArray<IBinder> mWindowIdToWindowTokenMap = new SparseArray<IBinder>();

    private final SimpleStringSplitter mStringColonSplitter = new SimpleStringSplitter(':');

    private PackageManager mPackageManager;

    private int mHandledFeedbackTypes = 0;

    private boolean mIsAccessibilityEnabled;

    private AccessibilityInputFilter mInputFilter;

    private boolean mHasInputFilter;

    private final List<AccessibilityServiceInfo> mEnabledServicesForFeedbackTempList = new ArrayList<AccessibilityServiceInfo>();

    private boolean mIsTouchExplorationEnabled;

    private final WindowManagerService mWindowManagerService;

    private final SecurityPolicy mSecurityPolicy;

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
            }
        }
    };

    /**
     * Creates a new instance.
     *
     * @param context A {@link Context} instance.
     */
    public AccessibilityManagerService(Context context) {
        mContext = context;
        mPackageManager = mContext.getPackageManager();
        mCaller = new HandlerCaller(context, this);
        mWindowManagerService = (WindowManagerService) ServiceManager.getService(
                Context.WINDOW_SERVICE);
        mSecurityPolicy = new SecurityPolicy();

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
                        // get accessibility enabled setting on boot
                        mIsAccessibilityEnabled = Settings.Secure.getInt(
                                mContext.getContentResolver(),
                                Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1;

                        manageServicesLocked();

                        // get touch exploration enabled setting on boot
                        mIsTouchExplorationEnabled = Settings.Secure.getInt(
                                mContext.getContentResolver(),
                                Settings.Secure.TOUCH_EXPLORATION_ENABLED, 0) == 1;
                        updateInputFilterLocked();

                        sendStateToClientsLocked();
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

        Uri accessibilityEnabledUri = Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_ENABLED);
        contentResolver.registerContentObserver(accessibilityEnabledUri, false,
            new ContentObserver(new Handler()) {
                @Override
                public void onChange(boolean selfChange) {
                    super.onChange(selfChange);

                    synchronized (mLock) {
                        mIsAccessibilityEnabled = Settings.Secure.getInt(
                                mContext.getContentResolver(),
                                Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1;
                        if (mIsAccessibilityEnabled) {
                            manageServicesLocked();
                        } else {
                            unbindAllServicesLocked();
                        }
                        updateInputFilterLocked();
                        sendStateToClientsLocked();
                    }
                }
            });

        Uri touchExplorationRequestedUri = Settings.Secure.getUriFor(
                Settings.Secure.TOUCH_EXPLORATION_ENABLED);
        contentResolver.registerContentObserver(touchExplorationRequestedUri, false,
                new ContentObserver(new Handler()) {
                    @Override
                    public void onChange(boolean selfChange) {
                        super.onChange(selfChange);

                        synchronized (mLock) {
                            mIsTouchExplorationEnabled = Settings.Secure.getInt(
                                    mContext.getContentResolver(),
                                    Settings.Secure.TOUCH_EXPLORATION_ENABLED, 0) == 1;
                            updateInputFilterLocked();
                            sendStateToClientsLocked();
                        }
                    }
                });

        Uri accessibilityServicesUri =
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        contentResolver.registerContentObserver(accessibilityServicesUri, false,
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

    public int addClient(IAccessibilityManagerClient client) throws RemoteException {
        synchronized (mLock) {
            final IAccessibilityManagerClient addedClient = client;
            mClients.add(addedClient);
            // Clients are registered all the time until their process is
            // killed, therefore we do not have a corresponding unlinkToDeath.
            client.asBinder().linkToDeath(new DeathRecipient() {
                public void binderDied() {
                    synchronized (mLock) {
                        mClients.remove(addedClient);
                    }
                }
            }, 0);
            return getState();
        }
    }

    public boolean sendAccessibilityEvent(AccessibilityEvent event) {
        synchronized (mLock) {
            if (mSecurityPolicy.canDispatchAccessibilityEvent(event)) {
                mSecurityPolicy.updateRetrievalAllowingWindowAndEventSourceLocked(event);
                notifyAccessibilityServicesDelayedLocked(event, false);
                notifyAccessibilityServicesDelayedLocked(event, true);
            }
        }
        event.recycle();
        mHandledFeedbackTypes = 0;
        return (OWN_PROCESS_ID != Binder.getCallingPid());
    }

    public List<AccessibilityServiceInfo> getInstalledAccessibilityServiceList() {
        synchronized (mLock) {
            return mInstalledServices;
        }
    }

    public List<AccessibilityServiceInfo> getEnabledAccessibilityServiceList(int feedbackType) {
        List<AccessibilityServiceInfo> result = mEnabledServicesForFeedbackTempList;
        result.clear();
        List<Service> services = mServices;
        synchronized (mLock) {
            while (feedbackType != 0) {
                final int feedbackTypeBit = (1 << Integer.numberOfTrailingZeros(feedbackType));
                feedbackType &= ~feedbackTypeBit;
                final int serviceCount = services.size();
                for (int i = 0; i < serviceCount; i++) {
                    Service service = services.get(i);
                    if ((service.mFeedbackType & feedbackTypeBit) != 0) {
                        result.add(service.mAccessibilityServiceInfo);
                    }
                }
            }
        }
        return result;
    }

    public void interrupt() {
        synchronized (mLock) {
            for (int i = 0, count = mServices.size(); i < count; i++) {
                Service service = mServices.get(i);
                try {
                    service.mServiceInterface.onInterrupt();
                } catch (RemoteException re) {
                    Slog.e(LOG_TAG, "Error during sending interrupt request to "
                        + service.mService, re);
                }
            }
        }
    }

    public void executeMessage(Message message) {
        switch (message.what) {
            case DO_SET_SERVICE_INFO: {
                SomeArgs arguments = ((SomeArgs) message.obj);

                AccessibilityServiceInfo info = (AccessibilityServiceInfo) arguments.arg1;
                Service service = (Service) arguments.arg2;

                synchronized (mLock) {
                    // If the XML manifest had data to configure the service its info
                    // should be already set. In such a case update only the dynamically
                    // configurable properties.
                    AccessibilityServiceInfo oldInfo = service.mAccessibilityServiceInfo;
                    if (oldInfo != null) {
                        oldInfo.updateDynamicallyConfigurableProperties(info);
                        service.setDynamicallyConfigurableProperties(oldInfo);
                    } else {
                        service.setDynamicallyConfigurableProperties(info);
                    }
                }
            } return;
            default:
                Slog.w(LOG_TAG, "Unknown message type: " + message.what);
        }
    }

    public int addAccessibilityInteractionConnection(IWindow windowToken,
            IAccessibilityInteractionConnection connection) throws RemoteException {
        synchronized (mLock) {
            final IWindow addedWindowToken = windowToken;
            final int windowId = sNextWindowId++;
            connection.asBinder().linkToDeath(new DeathRecipient() {
                public void binderDied() {
                    synchronized (mLock) {
                        removeAccessibilityInteractionConnection(addedWindowToken);
                    }
                }
            }, 0);
            mWindowIdToWindowTokenMap.put(windowId, addedWindowToken.asBinder());
            mWindowIdToInteractionConnectionMap.put(windowId, connection);
            if (DEBUG) {
                Slog.i(LOG_TAG, "Adding interaction connection to windowId: " + windowId);
            }
            return windowId;
        }
    }

    public void removeAccessibilityInteractionConnection(IWindow windowToken) {
        synchronized (mLock) {
            final int count = mWindowIdToWindowTokenMap.size();
            for (int i = 0; i < count; i++) {
                if (mWindowIdToWindowTokenMap.valueAt(i) == windowToken.asBinder()) {
                    final int windowId = mWindowIdToWindowTokenMap.keyAt(i);
                    mWindowIdToWindowTokenMap.remove(windowId);
                    mWindowIdToInteractionConnectionMap.remove(windowId);
                    if (DEBUG) {
                        Slog.i(LOG_TAG, "Removing interaction connection to windowId: " + windowId);
                    }
                    return;
                }
            }
        }
    }

    public IAccessibilityServiceConnection registerEventListener(IEventListener listener) {
        mSecurityPolicy.enforceCallingPermission(Manifest.permission.RETRIEVE_WINDOW_CONTENT,
                FUNCTION_REGISTER_EVENT_LISTENER);
        ComponentName componentName = new ComponentName("foo.bar",
                "AutomationAccessibilityService");
        synchronized (mLock) {
            Service oldService = mComponentNameToServiceMap.get(componentName);
            if (oldService != null) {
                tryRemoveServiceLocked(oldService);
            }
            // This API is intended for testing so enable accessibility to make
            // sure clients can start poking with the window content.
            Settings.Secure.putInt(mContext.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            // Also disable all accessibility services to avoid interference
            // with the tests.
            Settings.Secure.putString(mContext.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "");
        }
        AccessibilityServiceInfo accessibilityServiceInfo = new AccessibilityServiceInfo();
        accessibilityServiceInfo.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        accessibilityServiceInfo.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        Service service = new Service(componentName, accessibilityServiceInfo, true);
        service.onServiceConnected(componentName, listener.asBinder());
        return service;
    }

    /**
     * Populates the cached list of installed {@link AccessibilityService}s.
     */
    private void populateAccessibilityServiceListLocked() {
        mInstalledServices.clear();

        List<ResolveInfo> installedServices = mPackageManager.queryIntentServices(
                new Intent(AccessibilityService.SERVICE_INTERFACE),
                PackageManager.GET_SERVICES | PackageManager.GET_META_DATA);

        for (int i = 0, count = installedServices.size(); i < count; i++) {
            ResolveInfo resolveInfo = installedServices.get(i);
            AccessibilityServiceInfo accessibilityServiceInfo;
            try {
                accessibilityServiceInfo = new AccessibilityServiceInfo(resolveInfo, mContext);
                mInstalledServices.add(accessibilityServiceInfo);
            } catch (XmlPullParserException xppe) {
                Slog.e(LOG_TAG, "Error while initializing AccessibilityServiceInfo", xppe);
            } catch (IOException ioe) {
                Slog.e(LOG_TAG, "Error while initializing AccessibilityServiceInfo", ioe);
            }
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
            final int eventType = event.getEventType();
            // Make a copy since during dispatch it is possible the event to
            // be modified to remove its source if the receiving service does
            // not have permission to access the window content.
            AccessibilityEvent newEvent = AccessibilityEvent.obtain(event);
            AccessibilityEvent oldEvent = service.mPendingEvents.get(eventType);
            service.mPendingEvents.put(eventType, newEvent);

            final int what = eventType | (service.mId << 16);
            if (oldEvent != null) {
                mHandler.removeMessages(what);
                oldEvent.recycle();
            }

            Message message = mHandler.obtainMessage(what, service);
            message.arg1 = eventType;
            mHandler.sendMessageDelayed(message, service.mNotificationTimeout);
        }
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

        // Check for null here because there is a concurrent scenario in which this
        // happens: 1) A binder thread calls notifyAccessibilityServiceDelayedLocked
        // which posts a message for dispatching an event. 2) The message is pulled
        // from the queue by the handler on the service thread and the latter is
        // just about to acquire the lock and call this method. 3) Now another binder
        // thread acquires the lock calling notifyAccessibilityServiceDelayedLocked
        // so the service thread waits for the lock; 4) The binder thread replaces
        // the event with a more recent one (assume the same event type) and posts a
        // dispatch request releasing the lock. 5) Now the main thread is unblocked and
        // dispatches the event which is removed from the pending ones. 6) And ... now
        // the service thread handles the last message posted by the last binder call
        // but the event is already dispatched and hence looking it up in the pending
        // ones yields null. This check is much simpler that keeping count for each
        // event type of each service to catch such a scenario since only one message
        // is processed at a time.
        if (event == null) {
            return;
        }

        service.mPendingEvents.remove(eventType);
        try {
            if (mSecurityPolicy.canRetrieveWindowContent(service)) {
                event.setConnection(service);
            } else {
                event.setSource(null);
            }
            event.setSealed(true);
            listener.onAccessibilityEvent(event);
            event.recycle();
            if (DEBUG) {
                Slog.i(LOG_TAG, "Event " + event + " sent to " + listener);
            }
        } catch (RemoteException re) {
            Slog.e(LOG_TAG, "Error during sending " + event + " to " + service.mService, re);
        }
    }

    /**
     * Adds a service.
     *
     * @param service The service to add.
     */
    private void tryAddServiceLocked(Service service) {
        try {
            if (mServices.contains(service) || !service.isConfigured()) {
                return;
            }
            service.linkToOwnDeath();
            mServices.add(service);
            mComponentNameToServiceMap.put(service.mComponentName, service);
            updateInputFilterLocked();
        } catch (RemoteException e) {
            /* do nothing */
        }
    }

    /**
     * Removes a service.
     *
     * @param service The service.
     * @return True if the service was removed, false otherwise.
     */
    private boolean tryRemoveServiceLocked(Service service) {
        final boolean removed = mServices.remove(service);
        if (!removed) {
            return false;
        }
        mComponentNameToServiceMap.remove(service.mComponentName);
        mHandler.removeMessages(service.mId);
        service.unlinkToOwnDeath();
        updateInputFilterLocked();
        return removed;
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
            if (service.unbind()) {
                i--;
                count--;
            }
        }
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
    private void updateServicesStateLocked(List<AccessibilityServiceInfo> installedServices,
            Set<ComponentName> enabledServices) {

        Map<ComponentName, Service> componentNameToServiceMap = mComponentNameToServiceMap;
        boolean isEnabled = mIsAccessibilityEnabled;

        for (int i = 0, count = installedServices.size(); i < count; i++) {
            AccessibilityServiceInfo installedService = installedServices.get(i);
            ComponentName componentName = ComponentName.unflattenFromString(
                    installedService.getId());
            Service service = componentNameToServiceMap.get(componentName);

            if (isEnabled) {
                if (enabledServices.contains(componentName)) {
                    if (service == null) {
                        service = new Service(componentName, installedService, false);
                    }
                    service.bind();
                } else {
                    if (service != null) {
                        service.unbind();
                    }
                }
            } else {
                if (service != null) {
                    service.unbind();
                }
            }
        }
    }

    /**
     * Sends the state to the clients.
     */
    private void sendStateToClientsLocked() {
        final int state = getState();
        for (int i = 0, count = mClients.size(); i < count; i++) {
            try {
                mClients.get(i).setState(state);
            } catch (RemoteException re) {
                mClients.remove(i);
                count--;
                i--;
            }
        }
    }

    /**
     * Gets the current state as a set of flags.
     *
     * @return The state.
     */
    private int getState() {
        int state = 0;
        if (mIsAccessibilityEnabled) {
            state |= AccessibilityManager.STATE_FLAG_ACCESSIBILITY_ENABLED;
        }
        // Touch exploration relies on enabled accessibility.
        if (mIsAccessibilityEnabled && mIsTouchExplorationEnabled) {
            state |= AccessibilityManager.STATE_FLAG_TOUCH_EXPLORATION_ENABLED;
        }
        return state;
    }

    /**
     * Updates the touch exploration state.
     */
    private void updateInputFilterLocked() {
        if (mIsAccessibilityEnabled && mIsTouchExplorationEnabled) {
            if (!mHasInputFilter) {
                mHasInputFilter = true;
                if (mInputFilter == null) {
                    mInputFilter = new AccessibilityInputFilter(mContext);
                }
                mWindowManagerService.setInputFilter(mInputFilter);
            }
            return;
        }
        if (mHasInputFilter) {
            mHasInputFilter = false;
            mWindowManagerService.setInputFilter(null);
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
    class Service extends IAccessibilityServiceConnection.Stub
            implements ServiceConnection, DeathRecipient {
        int mId = 0;

        AccessibilityServiceInfo mAccessibilityServiceInfo;

        IBinder mService;

        IEventListener mServiceInterface;

        int mEventTypes;

        int mFeedbackType;

        Set<String> mPackageNames = new HashSet<String>();

        boolean mIsDefault;

        long mNotificationTimeout;

        ComponentName mComponentName;

        Intent mIntent;

        boolean mCanRetrieveScreenContent;

        boolean mIsAutomation;

        final Callback mCallback = new Callback();

        final AtomicInteger mInteractionIdCounter = new AtomicInteger();

        final Rect mTempBounds = new Rect();

        // the events pending events to be dispatched to this service
        final SparseArray<AccessibilityEvent> mPendingEvents =
            new SparseArray<AccessibilityEvent>();

        public Service(ComponentName componentName,
                AccessibilityServiceInfo accessibilityServiceInfo, boolean isAutomation) {
            mId = sIdCounter++;
            mComponentName = componentName;
            mAccessibilityServiceInfo = accessibilityServiceInfo;
            mIsAutomation = isAutomation;
            if (!isAutomation) {
                mCanRetrieveScreenContent = accessibilityServiceInfo.getCanRetrieveWindowContent();
                mIntent = new Intent().setComponent(mComponentName);
                mIntent.putExtra(Intent.EXTRA_CLIENT_LABEL,
                        com.android.internal.R.string.accessibility_binding_label);
                mIntent.putExtra(Intent.EXTRA_CLIENT_INTENT, PendingIntent.getActivity(
                        mContext, 0, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), 0));
            } else {
                mCanRetrieveScreenContent = true;
            }
            setDynamicallyConfigurableProperties(accessibilityServiceInfo);
        }

        public void setDynamicallyConfigurableProperties(AccessibilityServiceInfo info) {
            mEventTypes = info.eventTypes;
            mFeedbackType = info.feedbackType;
            String[] packageNames = info.packageNames;
            if (packageNames != null) {
                mPackageNames.addAll(Arrays.asList(packageNames));
            }
            mNotificationTimeout = info.notificationTimeout;
            mIsDefault = (info.flags & AccessibilityServiceInfo.DEFAULT) != 0;

            synchronized (mLock) {
                tryAddServiceLocked(this);
            }
        }

        /**
         * Binds to the accessibility service.
         *
         * @return True if binding is successful.
         */
        public boolean bind() {
            if (!mIsAutomation && mService == null) {
                return mContext.bindService(mIntent, this, Context.BIND_AUTO_CREATE);
            }
            return false;
        }

        /**
         * Unbinds form the accessibility service and removes it from the data
         * structures for service management.
         *
         * @return True if unbinding is successful.
         */
        public boolean unbind() {
            if (mService != null) {
                synchronized (mLock) {
                    tryRemoveServiceLocked(this);
                }
                if (!mIsAutomation) {
                    mContext.unbindService(this);
                }
                mService = null;
                return true;
            }
            return false;
        }

        /**
         * Returns if the service is configured i.e. at least event types of interest
         * and feedback type must be set.
         *
         * @return True if the service is configured, false otherwise.
         */
        public boolean isConfigured() {
            return (mEventTypes != 0 && mFeedbackType != 0 && mService != null);
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
                    tryAddServiceLocked(this);
                }
            } catch (RemoteException re) {
                Slog.w(LOG_TAG, "Error while setting Controller for service: " + service, re);
            }
        }

        public AccessibilityNodeInfo findAccessibilityNodeInfoByViewIdInActiveWindow(int viewId)
                throws RemoteException {
            IAccessibilityInteractionConnection connection = null;
            synchronized (mLock) {
                mSecurityPolicy.enforceCanRetrieveWindowContent(this);
                final boolean permissionGranted = mSecurityPolicy.canRetrieveWindowContent(this);
                if (!permissionGranted) {
                    return null;
                } else {
                    connection = getConnectionToRetrievalAllowingWindowLocked();
                    if (connection == null) {
                        if (DEBUG) {
                            Slog.e(LOG_TAG, "No interaction connection to a retrieve "
                                    + "allowing window.");
                        }
                        return null;
                    }
                }
            }
            final long identityToken = Binder.clearCallingIdentity();
            try {
                final int interactionId = mInteractionIdCounter.getAndIncrement();
                connection.findAccessibilityNodeInfoByViewId(viewId, interactionId, mCallback);
                AccessibilityNodeInfo info = mCallback.getFindAccessibilityNodeInfoResultAndClear(
                        interactionId);
                if (info != null) {
                    applyCompatibilityScaleIfNeeded(info);
                    info.setConnection(this);
                    info.setSealed(true);
                }
                return info;
            } catch (RemoteException re) {
                if (DEBUG) {
                    Slog.e(LOG_TAG, "Error finding node.");
                }
            } finally {
                Binder.restoreCallingIdentity(identityToken);
            }
            return null;
        }

        public List<AccessibilityNodeInfo> findAccessibilityNodeInfosByViewTextInActiveWindow(
                String text) throws RemoteException {
            return findAccessibilityNodeInfosByViewText(text,
                    mSecurityPolicy.mRetrievalAlowingWindowId, View.NO_ID);
        }

        public List<AccessibilityNodeInfo> findAccessibilityNodeInfosByViewText(String text,
                int accessibilityWindowId, int accessibilityViewId) throws RemoteException {
            IAccessibilityInteractionConnection connection = null;
            synchronized (mLock) {
                mSecurityPolicy.enforceCanRetrieveWindowContent(this);
                final boolean permissionGranted =
                    mSecurityPolicy.canGetAccessibilityNodeInfoLocked(this, accessibilityWindowId);
                if (!permissionGranted) {
                    return null;
                } else {
                    connection = getConnectionToRetrievalAllowingWindowLocked();
                    if (connection == null) {
                        if (DEBUG) {
                            Slog.e(LOG_TAG, "No interaction connection to focused window.");
                        }
                        return null;
                    }
                }
            }
            final long identityToken = Binder.clearCallingIdentity();
            try {
                final int interactionId = mInteractionIdCounter.getAndIncrement();
                connection.findAccessibilityNodeInfosByViewText(text, accessibilityViewId,
                        interactionId, mCallback);
                List<AccessibilityNodeInfo> infos =
                    mCallback.getFindAccessibilityNodeInfosResultAndClear(interactionId);
                if (infos != null) {
                    final int infoCount = infos.size();
                    for (int i = 0; i < infoCount; i++) {
                        AccessibilityNodeInfo info = infos.get(i);
                        applyCompatibilityScaleIfNeeded(info);
                        info.setConnection(this);
                        info.setSealed(true);
                    }
                }
                return infos;
            } catch (RemoteException re) {
                if (DEBUG) {
                    Slog.e(LOG_TAG, "Error finding node.");
                }
            } finally {
                Binder.restoreCallingIdentity(identityToken);
            }
            return null;
        }

        public AccessibilityNodeInfo findAccessibilityNodeInfoByAccessibilityId(
                int accessibilityWindowId, int accessibilityViewId) throws RemoteException {
            IAccessibilityInteractionConnection connection = null;
            synchronized (mLock) {
                mSecurityPolicy.enforceCanRetrieveWindowContent(this);
                final boolean permissionGranted =
                    mSecurityPolicy.canGetAccessibilityNodeInfoLocked(this, accessibilityWindowId);
                if (!permissionGranted) {
                    return null;
                } else {
                    connection = mWindowIdToInteractionConnectionMap.get(accessibilityWindowId);
                    if (connection == null) {
                        if (DEBUG) {
                            Slog.e(LOG_TAG, "No interaction connection to window: "
                                    + accessibilityWindowId);
                        }
                        return null;
                    }
                }
            }
            final long identityToken = Binder.clearCallingIdentity();
            try {
                final int interactionId = mInteractionIdCounter.getAndIncrement();
                connection.findAccessibilityNodeInfoByAccessibilityId(accessibilityViewId,
                        interactionId, mCallback);
                AccessibilityNodeInfo info =
                     mCallback.getFindAccessibilityNodeInfoResultAndClear(interactionId);
                if (info != null) {
                    applyCompatibilityScaleIfNeeded(info);
                    info.setConnection(this);
                    info.setSealed(true);
                }
                return info;
            } catch (RemoteException re) {
                if (DEBUG) {
                    Slog.e(LOG_TAG, "Error requesting node with accessibilityViewId: "
                            + accessibilityViewId);
                }
            } finally {
                Binder.restoreCallingIdentity(identityToken);
            }
            return null;
        }

        public boolean performAccessibilityAction(int accessibilityWindowId,
                int accessibilityViewId, int action) {
            IAccessibilityInteractionConnection connection = null;
            synchronized (mLock) {
                final boolean permissionGranted = mSecurityPolicy.canPerformActionLocked(this,
                        accessibilityWindowId, action);
                if (!permissionGranted) {
                    return false;
                } else {
                    connection = mWindowIdToInteractionConnectionMap.get(accessibilityWindowId);
                    if (connection == null) {
                        if (DEBUG) {
                            Slog.e(LOG_TAG, "No interaction connection to window: "
                                    + accessibilityWindowId);
                        }
                        return false;
                    }
                }
            }
            final long identityToken = Binder.clearCallingIdentity();
            try {
                final int interactionId = mInteractionIdCounter.getAndIncrement();
                connection.performAccessibilityAction(accessibilityViewId, action, interactionId,
                        mCallback);
                return mCallback.getPerformAccessibilityActionResult(interactionId);
            } catch (RemoteException re) {
                if (DEBUG) {
                    Slog.e(LOG_TAG, "Error requesting node with accessibilityViewId: "
                            + accessibilityViewId);
                }
            } finally {
                Binder.restoreCallingIdentity(identityToken);
            }
            return false;
        }

        public void onServiceDisconnected(ComponentName componentName) {
            /* do nothing - #binderDied takes care */
        }

        public void linkToOwnDeath() throws RemoteException {
            mService.linkToDeath(this, 0);
        }

        public void unlinkToOwnDeath() {
            mService.unlinkToDeath(this, 0);
        }

        public void binderDied() {
            synchronized (mLock) {
                tryRemoveServiceLocked(this);
            }
        }

        private IAccessibilityInteractionConnection getConnectionToRetrievalAllowingWindowLocked() {
            final int windowId = mSecurityPolicy.getRetrievalAllowingWindowLocked();
            if (DEBUG) {
                Slog.i(LOG_TAG, "Trying to get interaction connection to windowId: " + windowId);
            }
            return mWindowIdToInteractionConnectionMap.get(windowId);
        }

        private void applyCompatibilityScaleIfNeeded(AccessibilityNodeInfo info) {
            IBinder windowToken = mWindowIdToWindowTokenMap.get(info.getWindowId());
            final float scale = mWindowManagerService.getWindowCompatibilityScale(windowToken);

            if (scale == 1.0f) {
                return;
            }

            Rect bounds = mTempBounds;
            info.getBoundsInParent(bounds);
            bounds.scale(scale);
            info.setBoundsInParent(bounds);

            info.getBoundsInScreen(bounds);
            bounds.scale(scale);
            info.setBoundsInScreen(bounds);
        }
    }

    final class SecurityPolicy {
        private static final int VALID_ACTIONS = AccessibilityNodeInfo.ACTION_FOCUS
            | AccessibilityNodeInfo.ACTION_CLEAR_FOCUS | AccessibilityNodeInfo.ACTION_SELECT
            | AccessibilityNodeInfo.ACTION_CLEAR_SELECTION;

        private static final int RETRIEVAL_ALLOWING_EVENT_TYPES =
            AccessibilityEvent.TYPE_VIEW_CLICKED | AccessibilityEvent.TYPE_VIEW_FOCUSED
            | AccessibilityEvent.TYPE_VIEW_HOVER_ENTER | AccessibilityEvent.TYPE_VIEW_HOVER_EXIT
            | AccessibilityEvent.TYPE_VIEW_LONG_CLICKED | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_VIEW_SELECTED
            | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            | AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
            | AccessibilityEvent.TYPE_VIEW_SCROLLED;

        private static final int RETRIEVAL_ALLOWING_WINDOW_CHANGE_EVENT_TYPES =
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_VIEW_HOVER_ENTER
            | AccessibilityEvent.TYPE_VIEW_HOVER_EXIT;

        private int mRetrievalAlowingWindowId;

        private boolean canDispatchAccessibilityEvent(AccessibilityEvent event) {
            // Send window changed event only for the retrieval allowing window.
            return (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    || event.getWindowId() == mRetrievalAlowingWindowId);
        }

        public void updateRetrievalAllowingWindowAndEventSourceLocked(AccessibilityEvent event) {
            final int windowId = event.getWindowId();
            final int eventType = event.getEventType();
            if ((eventType & RETRIEVAL_ALLOWING_WINDOW_CHANGE_EVENT_TYPES) != 0) {
                mRetrievalAlowingWindowId = windowId;
            }
            if ((eventType & RETRIEVAL_ALLOWING_EVENT_TYPES) == 0) {
                event.setSource(null);
            }
        }

        public int getRetrievalAllowingWindowLocked() {
            return mRetrievalAlowingWindowId;
        }

        public boolean canGetAccessibilityNodeInfoLocked(Service service, int windowId) {
            return canRetrieveWindowContent(service) && isRetrievalAllowingWindow(windowId);
        }

        public boolean canPerformActionLocked(Service service, int windowId, int action) {
            return canRetrieveWindowContent(service)
                && isRetrievalAllowingWindow(windowId)
                && isActionPermitted(action);
        }

        public boolean canRetrieveWindowContent(Service service) {
            return service.mCanRetrieveScreenContent;
        }

        public void enforceCanRetrieveWindowContent(Service service) throws RemoteException {
            // This happens due to incorrect registration so make it apparent.
            if (!canRetrieveWindowContent(service)) {
                Slog.e(LOG_TAG, "Accessibility serivce " + service.mComponentName + " does not " +
                        "declare android:canRetrieveWindowContent.");
                throw new RemoteException();
            }
        }

        private boolean isRetrievalAllowingWindow(int windowId) {
            return (mRetrievalAlowingWindowId == windowId);
        }

        private boolean isActionPermitted(int action) {
             return (VALID_ACTIONS & action) != 0;
        }

        private void enforceCallingPermission(String permission, String function) {
            if (OWN_PROCESS_ID == Binder.getCallingPid()) {
                return;
            }
            final int permissionStatus = mContext.checkCallingPermission(permission);
            if (permissionStatus != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("You do not have " + permission
                        + " required to call " + function);
            }
        }
    }

    final class Callback extends IAccessibilityInteractionConnectionCallback.Stub {
        private static final long TIMEOUT_INTERACTION_MILLIS = 5000;

        private int mInteractionId = -1;
        private AccessibilityNodeInfo mFindAccessibilityNodeInfoResult;
        private List<AccessibilityNodeInfo> mFindAccessibilityNodeInfosResult;
        private boolean mPerformAccessibilityActionResult;

        public void setFindAccessibilityNodeInfoResult(AccessibilityNodeInfo info,
                int interactionId) {
            synchronized (mLock) {
                if (interactionId > mInteractionId) {
                    mFindAccessibilityNodeInfoResult = info;
                    mInteractionId = interactionId;
                }
                mLock.notifyAll();
            }
        }

        public AccessibilityNodeInfo getFindAccessibilityNodeInfoResultAndClear(int interactionId) {
            synchronized (mLock) {
                waitForResultTimedLocked(TIMEOUT_INTERACTION_MILLIS, interactionId);
                AccessibilityNodeInfo result = mFindAccessibilityNodeInfoResult;
                clearLocked();
                return result;
            }
        }

        public void setFindAccessibilityNodeInfosResult(List<AccessibilityNodeInfo> infos,
                int interactionId) {
            synchronized (mLock) {
                if (interactionId > mInteractionId) {
                    mFindAccessibilityNodeInfosResult = infos;
                    mInteractionId = interactionId;
                }
                mLock.notifyAll();
            }
        }

        public List<AccessibilityNodeInfo> getFindAccessibilityNodeInfosResultAndClear(
                int interactionId) {
            synchronized (mLock) {
                waitForResultTimedLocked(TIMEOUT_INTERACTION_MILLIS, interactionId);
                List<AccessibilityNodeInfo> result = mFindAccessibilityNodeInfosResult;
                clearLocked();
                return result;
            }
        }

        public void setPerformAccessibilityActionResult(boolean succeeded, int interactionId) {
            synchronized (mLock) {
                if (interactionId > mInteractionId) {
                    mPerformAccessibilityActionResult = succeeded;
                    mInteractionId = interactionId;
                }
                mLock.notifyAll();
            }
        }

        public boolean getPerformAccessibilityActionResult(int interactionId) {
            synchronized (mLock) {
                waitForResultTimedLocked(TIMEOUT_INTERACTION_MILLIS, interactionId);
                final boolean result = mPerformAccessibilityActionResult;
                clearLocked();
                return result;
            }
        }

        public void clearLocked() {
            mInteractionId = -1;
            mFindAccessibilityNodeInfoResult = null;
            mFindAccessibilityNodeInfosResult = null;
            mPerformAccessibilityActionResult = false;
        }

        private void waitForResultTimedLocked(long waitTimeMillis, int interactionId) {
            final long startTimeMillis = SystemClock.uptimeMillis();
            while (true) {
                try {
                    if (mInteractionId == interactionId) {
                        return;
                    }
                    final long elapsedTimeMillis = SystemClock.uptimeMillis() - startTimeMillis;
                    waitTimeMillis = TIMEOUT_INTERACTION_MILLIS - elapsedTimeMillis;
                    if (waitTimeMillis <= 0) {
                        return;
                    }
                    mLock.wait(waitTimeMillis);
                } catch (InterruptedException ie) {
                    /* ignore */
                }
            }
        }
    }
}
