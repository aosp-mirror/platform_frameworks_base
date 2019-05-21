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
 * limitations under the License
 */

package android.content.pm;

import android.content.Intent;
import android.content.res.Resources;
import android.os.FileUtils;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.support.test.filters.LargeTest;
import android.test.AndroidTestCase;
import android.util.AttributeSet;
import android.util.SparseArray;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tests for {@link android.content.pm.RegisteredServicesCache}
 */
@LargeTest
public class RegisteredServicesCacheTest extends AndroidTestCase {
    private static final int U0 = 0;
    private static final int U1 = 1;
    private static final int UID1 = 1;
    private static final int UID2 = 2;
    // Represents UID of a system image process
    private static final int SYSTEM_IMAGE_UID = 20;

    private final ResolveInfo r1 = new ResolveInfo();
    private final ResolveInfo r2 = new ResolveInfo();
    private final TestServiceType t1 = new TestServiceType("t1", "value1");
    private final TestServiceType t2 = new TestServiceType("t2", "value2");
    private File mDataDir;
    private File mSyncDir;
    private List<UserInfo> mUsers;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        File cacheDir = mContext.getCacheDir();
        mDataDir = new File(cacheDir, "testServicesCache");
        FileUtils.deleteContents(mDataDir);
        mSyncDir = new File(mDataDir, "system/"+RegisteredServicesCache.REGISTERED_SERVICES_DIR);
        mSyncDir.mkdirs();
        mUsers = new ArrayList<>();
        mUsers.add(new UserInfo(0, "Owner", UserInfo.FLAG_ADMIN));
        mUsers.add(new UserInfo(1, "User1", 0));
    }

    public void testGetAllServicesHappyPath() {
        TestServicesCache cache = new TestServicesCache();
        cache.addServiceForQuerying(U0, r1, newServiceInfo(t1, UID1));
        cache.addServiceForQuerying(U0, r2, newServiceInfo(t2, UID2));
        assertEquals(2, cache.getAllServicesSize(U0));
        assertEquals(2, cache.getPersistentServicesSize(U0));
        assertNotEmptyFileCreated(cache, U0);
        // Make sure all services can be loaded from xml
        cache = new TestServicesCache();
        assertEquals(2, cache.getPersistentServicesSize(U0));
    }

    public void testGetAllServicesReplaceUid() {
        TestServicesCache cache = new TestServicesCache();
        cache.addServiceForQuerying(U0, r1, newServiceInfo(t1, UID1));
        cache.addServiceForQuerying(U0, r2, newServiceInfo(t2, UID2));
        cache.getAllServices(U0);
        // Invalidate cache and clear update query results
        cache.invalidateCache(U0);
        cache.clearServicesForQuerying();
        cache.addServiceForQuerying(U0, r1, newServiceInfo(t1, UID1));
        cache.addServiceForQuerying(U0, r2, newServiceInfo(t2, SYSTEM_IMAGE_UID));
        Collection<RegisteredServicesCache.ServiceInfo<TestServiceType>> allServices = cache
                .getAllServices(U0);
        assertEquals(2, allServices.size());
        Set<Integer> uids = new HashSet<>();
        for (RegisteredServicesCache.ServiceInfo<TestServiceType> srv : allServices) {
            uids.add(srv.uid);
        }
        assertTrue("UID must be updated to the new value",
                uids.contains(SYSTEM_IMAGE_UID));
        assertFalse("UID must be updated to the new value", uids.contains(UID2));
    }

    public void testGetAllServicesServiceRemoved() {
        TestServicesCache cache = new TestServicesCache();
        cache.addServiceForQuerying(U0, r1, newServiceInfo(t1, UID1));
        cache.addServiceForQuerying(U0, r2, newServiceInfo(t2, UID2));
        assertEquals(2, cache.getAllServicesSize(U0));
        assertEquals(2, cache.getPersistentServicesSize(U0));
        // Re-read data from disk and verify services were saved
        cache = new TestServicesCache();
        assertEquals(2, cache.getPersistentServicesSize(U0));
        // Now register only one service and verify that another one is removed
        cache.addServiceForQuerying(U0, r1, newServiceInfo(t1, UID1));
        assertEquals(1, cache.getAllServicesSize(U0));
        assertEquals(1, cache.getPersistentServicesSize(U0));
    }

    public void testGetAllServicesMultiUser() {
        TestServicesCache cache = new TestServicesCache();
        cache.addServiceForQuerying(U0, r1, newServiceInfo(t1, UID1));
        int u1uid = UserHandle.getUid(U1, 0);
        cache.addServiceForQuerying(U1, r2, newServiceInfo(t2, u1uid));
        assertEquals(1, cache.getAllServicesSize(U0));
        assertEquals(1, cache.getPersistentServicesSize(U0));
        assertEquals(1, cache.getAllServicesSize(U1));
        assertEquals(1, cache.getPersistentServicesSize(U1));
        assertEquals("No services should be available for user 3", 0, cache.getAllServicesSize(3));
        // Re-read data from disk and verify services were saved
        cache = new TestServicesCache();
        assertEquals(1, cache.getPersistentServicesSize(U0));
        assertEquals(1, cache.getPersistentServicesSize(U1));
        assertNotEmptyFileCreated(cache, U0);
        assertNotEmptyFileCreated(cache, U1);
    }

    public void testOnRemove() {
        TestServicesCache cache = new TestServicesCache();
        cache.addServiceForQuerying(U0, r1, newServiceInfo(t1, UID1));
        int u1uid = UserHandle.getUid(U1, 0);
        cache.addServiceForQuerying(U1, r2, newServiceInfo(t2, u1uid));
        assertEquals(1, cache.getAllServicesSize(U0));
        assertEquals(1, cache.getAllServicesSize(U1));
        // Simulate ACTION_USER_REMOVED
        cache.onUserRemoved(U1);
        // Make queryIntentServices(u1) return no results for U1
        cache.clearServicesForQuerying();
        assertEquals(1, cache.getAllServicesSize(U0));
        assertEquals(0, cache.getAllServicesSize(U1));
    }

    public void testMigration() {
        // Prepare "old" file for testing
        String oldFile = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                + "<services>\n"
                + "    <service uid=\"1\" type=\"type1\" value=\"value1\" />\n"
                + "    <service uid=\"100002\" type=\"type2\" value=\"value2\" />\n"
                + "<services>\n";

        File file = new File(mSyncDir, TestServicesCache.SERVICE_INTERFACE + ".xml");
        FileUtils.copyToFile(new ByteArrayInputStream(oldFile.getBytes()), file);

        int u0 = 0;
        int u1 = 1;
        TestServicesCache cache = new TestServicesCache();
        assertEquals(1, cache.getPersistentServicesSize(u0));
        assertEquals(1, cache.getPersistentServicesSize(u1));
        assertNotEmptyFileCreated(cache, u0);
        assertNotEmptyFileCreated(cache, u1);
        // Check that marker was created
        File markerFile = new File(mSyncDir, TestServicesCache.SERVICE_INTERFACE + ".xml.migrated");
        assertTrue("Marker file should be created at " + markerFile, markerFile.exists());
        // Now introduce 2 service types for u0: t1, t2. type1 will be removed
        cache.addServiceForQuerying(0, r1, newServiceInfo(t1, 1));
        cache.addServiceForQuerying(0, r2, newServiceInfo(t2, 2));
        assertEquals(2, cache.getAllServicesSize(u0));
        assertEquals(0, cache.getAllServicesSize(u1));
        // Re-read data from disk. Verify that services were saved and old file was ignored
        cache = new TestServicesCache();
        assertEquals(2, cache.getPersistentServicesSize(u0));
        assertEquals(0, cache.getPersistentServicesSize(u1));
    }

    /**
     * Check that an optimization to skip a call to PackageManager handles an invalidated cache.
     *
     * We added an optimization in generateServicesMap to only query PackageManager for packages
     * that have been changed, because if a package is unchanged, we have already cached the
     * services info for it, so we can save a query to PackageManager (and save some memory).
     * However, if invalidateCache was called, we cannot optimize, and must do a full query.
     * The initial optimization was buggy because it failed to check for an invalidated cache, and
     * only scanned the changed packages, given in the ACTION_PACKAGE_CHANGED intent (b/122912184).
     */
    public void testParseServiceInfoOptimizationHandlesInvalidatedCache() {
        TestServicesCache cache = new TestServicesCache();
        cache.addServiceForQuerying(U0, r1, newServiceInfo(t1, UID1));
        cache.addServiceForQuerying(U0, r2, newServiceInfo(t2, UID2));
        assertEquals(2, cache.getAllServicesSize(U0));

        // simulate the client of the cache invalidating it
        cache.invalidateCache(U0);

        // there should be 0 services (userServices.services == null ) at this point, but we don't
        // call getAllServicesSize since that would force a full scan of packages,
        // instead we trigger a package change in a package that is in the list of services
        Intent intent = new Intent(Intent.ACTION_PACKAGE_CHANGED);
        intent.putExtra(Intent.EXTRA_UID, UID1);
        cache.handlePackageEvent(intent, U0);

        // check that the optimization does a full query and caches both services
        assertEquals(2, cache.getAllServicesSize(U0));
    }

    private static RegisteredServicesCache.ServiceInfo<TestServiceType> newServiceInfo(
            TestServiceType type, int uid) {
        final ComponentInfo info = new ComponentInfo();
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.uid = uid;
        return new RegisteredServicesCache.ServiceInfo<>(type, info, null);
    }

    private void assertNotEmptyFileCreated(TestServicesCache cache, int userId) {
        File dir = new File(cache.getUserSystemDirectory(userId),
                RegisteredServicesCache.REGISTERED_SERVICES_DIR);
        File file = new File(dir, TestServicesCache.SERVICE_INTERFACE+".xml");
        assertTrue("File should be created at " + file, file.length() > 0);
    }

    /**
     * Mock implementation of {@link android.content.pm.RegisteredServicesCache} for testing
     */
    private class TestServicesCache extends RegisteredServicesCache<TestServiceType> {
        static final String SERVICE_INTERFACE = "RegisteredServicesCacheTest";
        static final String SERVICE_META_DATA = "RegisteredServicesCacheTest";
        static final String ATTRIBUTES_NAME = "test";
        private SparseArray<Map<ResolveInfo, ServiceInfo<TestServiceType>>> mServices
                = new SparseArray<>();

        public TestServicesCache() {
            super(RegisteredServicesCacheTest.this.mContext,
                    SERVICE_INTERFACE, SERVICE_META_DATA, ATTRIBUTES_NAME, new TestSerializer());
        }

        @Override
        public TestServiceType parseServiceAttributes(Resources res, String packageName,
                AttributeSet attrs) {
            return null;
        }

        @Override
        protected List<ResolveInfo> queryIntentServices(int userId) {
            Map<ResolveInfo, ServiceInfo<TestServiceType>> map = mServices
                    .get(userId, new HashMap<ResolveInfo, ServiceInfo<TestServiceType>>());
            return new ArrayList<>(map.keySet());
        }

        @Override
        protected File getUserSystemDirectory(int userId) {
            File dir = new File(mDataDir, "users/" + userId);
            dir.mkdirs();
            return dir;
        }

        @Override
        protected List<UserInfo> getUsers() {
            return mUsers;
        }

        @Override
        protected UserInfo getUser(int userId) {
            for (UserInfo user : getUsers()) {
                if (user.id == userId) {
                    return user;
                }
            }
            return null;
        }

        @Override
        protected File getDataDirectory() {
            return mDataDir;
        }

        void addServiceForQuerying(int userId, ResolveInfo resolveInfo,
                ServiceInfo<TestServiceType> serviceInfo) {
            Map<ResolveInfo, ServiceInfo<TestServiceType>> map = mServices.get(userId);
            if (map == null) {
                map = new HashMap<>();
                mServices.put(userId, map);
            }
            // in actual cases, resolveInfo should always have a serviceInfo, since we specifically
            // query for intent services
            resolveInfo.serviceInfo = new android.content.pm.ServiceInfo();
            resolveInfo.serviceInfo.applicationInfo =
                new ApplicationInfo(serviceInfo.componentInfo.applicationInfo);
            map.put(resolveInfo, serviceInfo);
        }

        void clearServicesForQuerying() {
            mServices.clear();
        }

        int getPersistentServicesSize(int user) {
            return getPersistentServices(user).size();
        }

        int getAllServicesSize(int user) {
            return getAllServices(user).size();
        }

        @Override
        protected boolean inSystemImage(int callerUid) {
            return callerUid == SYSTEM_IMAGE_UID;
        }

        @Override
        protected ServiceInfo<TestServiceType> parseServiceInfo(
                ResolveInfo resolveInfo) throws XmlPullParserException, IOException {
            int size = mServices.size();
            for (int i = 0; i < size; i++) {
                Map<ResolveInfo, ServiceInfo<TestServiceType>> map = mServices.valueAt(i);
                ServiceInfo<TestServiceType> serviceInfo = map.get(resolveInfo);
                if (serviceInfo != null) {
                    return serviceInfo;
                }
            }
            throw new IllegalArgumentException("Unexpected service " + resolveInfo);
        }

        @Override
        public void onUserRemoved(int userId) {
            super.onUserRemoved(userId);
        }

        @Override
        public void handlePackageEvent(Intent intent, int userId) {
            super.handlePackageEvent(intent, userId);
        }
    }

    static class TestSerializer implements XmlSerializerAndParser<TestServiceType> {

        public void writeAsXml(TestServiceType item, XmlSerializer out) throws IOException {
            out.attribute(null, "type", item.type);
            out.attribute(null, "value", item.value);
        }

        public TestServiceType createFromXml(XmlPullParser parser)
                throws IOException, XmlPullParserException {
            final String type = parser.getAttributeValue(null, "type");
            final String value = parser.getAttributeValue(null, "value");
            return new TestServiceType(type, value);
        }
    }

    static class TestServiceType implements Parcelable {
        final String type;
        final String value;

        public TestServiceType(String type, String value) {
            this.type = type;
            this.value = value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            TestServiceType that = (TestServiceType) o;

            return type.equals(that.type) && value.equals(that.value);
        }

        @Override
        public int hashCode() {
            return 31 * type.hashCode() + value.hashCode();
        }

        @Override
        public String toString() {
            return "TestServiceType{" +
                    "type='" + type + '\'' +
                    ", value='" + value + '\'' +
                    '}';
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(type);
            dest.writeString(value);
        }

        public TestServiceType(Parcel source) {
            this(source.readString(), source.readString());
        }

        public static final Creator<TestServiceType> CREATOR = new Creator<TestServiceType>() {
            public TestServiceType createFromParcel(Parcel source) {
                return new TestServiceType(source);
            }

            public TestServiceType[] newArray(int size) {
                return new TestServiceType[size];
            }
        };
    }
}
