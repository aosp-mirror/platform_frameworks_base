/*
 * Copyright (C) 2017 The Android Open Source Project
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


package com.android.server.print;

import static com.android.internal.util.Preconditions.checkNotNull;

import android.Manifest;
import android.annotation.Nullable;
import android.companion.AssociationRequest;
import android.companion.CompanionDeviceManager;
import android.companion.ICompanionDeviceDiscoveryService;
import android.companion.ICompanionDeviceDiscoveryServiceCallback;
import android.companion.ICompanionDeviceManager;
import android.companion.IFindDeviceCallback;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.NetworkPolicyManager;
import android.os.Binder;
import android.os.Environment;
import android.os.IBinder;
import android.os.IDeviceIdleController;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.AtomicFile;
import android.util.ExceptionUtils;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.util.ArrayUtils;
import com.android.server.SystemService;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

//TODO move to own package!
//TODO un/linkToDeath & onBinderDied - unbind
//TODO onStop schedule unbind in 5 seconds
//TODO Prune association on app uninstall
/** @hide */
public class CompanionDeviceManagerService extends SystemService {

    private static final ComponentName SERVICE_TO_BIND_TO = ComponentName.createRelative(
            CompanionDeviceManager.COMPANION_DEVICE_DISCOVERY_PACKAGE_NAME,
            ".DeviceDiscoveryService");

    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "CompanionDeviceManagerService";

    private static final String XML_TAG_ASSOCIATIONS = "associations";
    private static final String XML_TAG_ASSOCIATION = "association";
    private static final String XML_ATTR_PACKAGE = "package";
    private static final String XML_ATTR_DEVICE = "device";
    private static final String XML_FILE_NAME = "companion_device_manager_associations.xml";

    private final CompanionDeviceManagerImpl mImpl;
    private final ConcurrentMap<Integer, AtomicFile> mUidToStorage = new ConcurrentHashMap<>();

    public CompanionDeviceManagerService(Context context) {
        super(context);
        mImpl = new CompanionDeviceManagerImpl();
    }

    @Override
    public void onStart() {
        publishBinderService(Context.COMPANION_DEVICE_SERVICE, mImpl);
    }

    class CompanionDeviceManagerImpl extends ICompanionDeviceManager.Stub {
        @Override
        public void associate(
                AssociationRequest request,
                IFindDeviceCallback callback,
                String callingPackage) {
            if (DEBUG) {
                Slog.i(LOG_TAG, "associate(request = " + request + ", callback = " + callback
                        + ", callingPackage = " + callingPackage + ")");
            }
            checkNotNull(request);
            checkNotNull(callback);
            final long callingIdentity = Binder.clearCallingIdentity();
            try {
                //TODO bindServiceAsUser
                getContext().bindService(
                        new Intent().setComponent(SERVICE_TO_BIND_TO),
                        getServiceConnection(request, callback, callingPackage),
                        Context.BIND_AUTO_CREATE);
            } finally {
                Binder.restoreCallingIdentity(callingIdentity);
            }
        }


        @Override
        public List<String> getAssociations(String callingPackage) {
            return ArrayUtils.map(
                    readAllAssociations(getUserId(), callingPackage),
                    (a) -> a.deviceAddress);
        }

        @Override
        public void disassociate(String deviceMacAddress, String callingPackage) {
            updateAssociations((associations) -> ArrayUtils.remove(associations,
                    new Association(getUserId(), checkNotNull(deviceMacAddress), callingPackage)));
        }
    }

    private int getUserId() {
        return UserHandle.getUserId(Binder.getCallingUid());
    }

    private ServiceConnection getServiceConnection(
            final AssociationRequest<?> request,
            final IFindDeviceCallback findDeviceCallback,
            final String callingPackage) {
        return new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                if (DEBUG) {
                    Slog.i(LOG_TAG,
                            "onServiceConnected(name = " + name + ", service = "
                                    + service + ")");
                }
                try {
                    ICompanionDeviceDiscoveryService.Stub
                            .asInterface(service)
                            .startDiscovery(
                                    request,
                                    callingPackage,
                                    findDeviceCallback,
                                    getServiceCallback());
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                if (DEBUG) Slog.i(LOG_TAG, "onServiceDisconnected(name = " + name + ")");
            }
        };
    }

    private ICompanionDeviceDiscoveryServiceCallback.Stub getServiceCallback() {
        return new ICompanionDeviceDiscoveryServiceCallback.Stub() {

            @Override
            public void onDeviceSelected(String packageName, int userId, String deviceAddress) {
                //TODO unbind
                grantSpecialAccessPermissionsIfNeeded(packageName, userId);
                recordAssociation(packageName, deviceAddress);
            }
        };
    }

    private void grantSpecialAccessPermissionsIfNeeded(String packageName, int userId) {
        final long identity = Binder.clearCallingIdentity();
        final PackageInfo packageInfo;
        try {
            try {
                packageInfo = getContext().getPackageManager().getPackageInfoAsUser(
                        packageName, PackageManager.GET_PERMISSIONS, userId);
            } catch (PackageManager.NameNotFoundException e) {
                Slog.e(LOG_TAG, "Error granting special access permissions to package:"
                        + packageName, e);
                return;
            }
            if (ArrayUtils.contains(packageInfo.requestedPermissions,
                    Manifest.permission.RUN_IN_BACKGROUND)) {
                IDeviceIdleController idleController = IDeviceIdleController.Stub.asInterface(
                        ServiceManager.getService(Context.DEVICE_IDLE_CONTROLLER));
                try {
                    idleController.addPowerSaveWhitelistApp(packageName);
                } catch (RemoteException e) {
                    /* ignore - local call */
                }
            }
            if (ArrayUtils.contains(packageInfo.requestedPermissions,
                    Manifest.permission.USE_DATA_IN_BACKGROUND)) {
                NetworkPolicyManager.from(getContext()).addUidPolicy(
                        packageInfo.applicationInfo.uid,
                        NetworkPolicyManager.POLICY_ALLOW_METERED_BACKGROUND);
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void recordAssociation(String priviledgedPackage, String deviceAddress) {
        updateAssociations((associations) -> ArrayUtils.add(associations,
                new Association(getUserId(), deviceAddress, priviledgedPackage)));
    }

    private void updateAssociations(
            Function<ArrayList<Association>, ArrayList<Association>> update) {
        final int userId = getUserId();
        final AtomicFile file = getStorageFileForUser(userId);
        synchronized (file) {
            final ArrayList<Association> old = readAllAssociations(userId);
            final ArrayList<Association> associations = update.apply(old);
            if (Objects.equals(old, associations)) return;

            file.write((out) -> {
                XmlSerializer xml = Xml.newSerializer();
                try {
                    xml.setOutput(out, StandardCharsets.UTF_8.name());
                    xml.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
                    xml.startDocument(null, true);
                    xml.startTag(null, XML_TAG_ASSOCIATIONS);

                    for (int i = 0; i < ArrayUtils.size(associations); i++) {
                        Association association = associations.get(i);
                        xml.startTag(null, XML_TAG_ASSOCIATION)
                            .attribute(null, XML_ATTR_PACKAGE, association.companionAppPackage)
                            .attribute(null, XML_ATTR_DEVICE, association.deviceAddress)
                            .endTag(null, XML_TAG_ASSOCIATION);
                    }

                    xml.endTag(null, XML_TAG_ASSOCIATIONS);
                    xml.endDocument();
                } catch (Exception e) {
                    Slog.e(LOG_TAG, "Error while writing associations file", e);
                    throw ExceptionUtils.propagate(e);
                }

            });
        }


        //TODO Show dialog before recording notification access
//        final SettingStringHelper setting =
//                new SettingStringHelper(
//                        getContext().getContentResolver(),
//                        Settings.Secure.ENABLED_NOTIFICATION_LISTENERS,
//                        getUserId());
//        setting.write(ColonDelimitedSet.OfStrings.add(setting.read(), priviledgedPackage));
    }

    private AtomicFile getStorageFileForUser(int uid) {
        return mUidToStorage.computeIfAbsent(uid, (u) ->
                new AtomicFile(new File(
                        //TODO deprecated method - what's the right replacement?
                        Environment.getUserSystemDirectory(u),
                        XML_FILE_NAME)));
    }

    @Nullable
    private ArrayList<Association> readAllAssociations(int uid) {
        return readAllAssociations(uid, null);
    }

    @Nullable
    private ArrayList<Association> readAllAssociations(int userId, @Nullable String packageFilter) {
        final AtomicFile file = getStorageFileForUser(userId);

        if (!file.getBaseFile().exists()) return null;

        ArrayList<Association> result = null;
        final XmlPullParser parser = Xml.newPullParser();
        synchronized (file) {
            try (FileInputStream in = file.openRead()) {
                parser.setInput(in, StandardCharsets.UTF_8.name());
                int type;
                while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                    if (type != XmlPullParser.START_TAG
                            && !XML_TAG_ASSOCIATIONS.equals(parser.getName())) continue;

                    final String appPackage = parser.getAttributeValue(null, XML_ATTR_PACKAGE);
                    final String deviceAddress = parser.getAttributeValue(null, XML_ATTR_DEVICE);

                    if (appPackage == null || deviceAddress == null) continue;
                    if (packageFilter != null && !packageFilter.equals(appPackage)) continue;

                    result = ArrayUtils.add(result,
                            new Association(userId, deviceAddress, appPackage));
                }
                return result;
            } catch (XmlPullParserException | IOException e) {
                Slog.e(LOG_TAG, "Error while reading associations file", e);
                return null;
            }
        }
    }

    private class Association {
        public final int uid;
        public final String deviceAddress;
        public final String companionAppPackage;

        private Association(int uid, String deviceAddress, String companionAppPackage) {
            this.uid = uid;
            this.deviceAddress = checkNotNull(deviceAddress);
            this.companionAppPackage = checkNotNull(companionAppPackage);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Association that = (Association) o;

            if (uid != that.uid) return false;
            if (!deviceAddress.equals(that.deviceAddress)) return false;
            return companionAppPackage.equals(that.companionAppPackage);

        }

        @Override
        public int hashCode() {
            int result = uid;
            result = 31 * result + deviceAddress.hashCode();
            result = 31 * result + companionAppPackage.hashCode();
            return result;
        }
    }

}
