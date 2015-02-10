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

import android.content.Context;
import android.content.res.Resources;
import android.os.FileUtils;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.test.AndroidTestCase;
import android.util.AttributeSet;
import android.util.SparseArray;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

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
public class RegisteredServicesCacheTest extends AndroidTestCase {

    private final ResolveInfo r1 = new ResolveInfo();
    private final ResolveInfo r2 = new ResolveInfo();
    private final TestServiceType t1 = new TestServiceType("t1", "value1");
    private final TestServiceType t2 = new TestServiceType("t2", "value2");
    private File mDataDir;
    private File mSyncDir;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        File cacheDir = mContext.getCacheDir();
        mDataDir = new File(cacheDir, "testServicesCache");
        FileUtils.deleteContents(mDataDir);
        mSyncDir = new File(mDataDir, "system/registered_services");
        mSyncDir.mkdirs();
    }

    public void testGetAllServicesHappyPath() {
        TestServicesCache cache = new TestServicesCache(mContext, mDataDir);
        cache.addServiceForQuerying(0, r1, new RegisteredServicesCache.ServiceInfo<>(t1, null, 1));
        cache.addServiceForQuerying(0, r2, new RegisteredServicesCache.ServiceInfo<>(t2, null, 2));
        assertEquals(2, cache.getAllServicesSize(0));
        assertEquals(2, cache.getPersistentServicesSize(0));
        File file = new File(mSyncDir, TestServicesCache.SERVICE_INTERFACE + ".xml");
        assertTrue("File should be created at " + file, file.length() > 0);
        // Make sure all services can be loaded from xml
        cache = new TestServicesCache(mContext, mDataDir);
        assertEquals(2, cache.getPersistentServicesSize(0));
    }

    public void testGetAllServicesReplaceUid() {
        TestServicesCache cache = new TestServicesCache(mContext, mDataDir);
        cache.addServiceForQuerying(0, r1, new RegisteredServicesCache.ServiceInfo<>(t1, null, 1));
        cache.addServiceForQuerying(0, r2, new RegisteredServicesCache.ServiceInfo<>(t2, null, 2));
        cache.getAllServices(0);
        // Invalidate cache and clear update query results
        cache.invalidateCache(0);
        cache.clearServicesForQuerying();
        cache.addServiceForQuerying(0, r1, new RegisteredServicesCache.ServiceInfo<>(t1, null, 1));
        cache.addServiceForQuerying(0, r2, new RegisteredServicesCache.ServiceInfo<>(t2, null,
                TestServicesCache.SYSTEM_IMAGE_UID));
        Collection<RegisteredServicesCache.ServiceInfo<TestServiceType>> allServices = cache
                .getAllServices(0);
        assertEquals(2, allServices.size());
        Set<Integer> uids = new HashSet<>();
        for (RegisteredServicesCache.ServiceInfo<TestServiceType> srv : allServices) {
            uids.add(srv.uid);
        }
        assertTrue("UID must be updated to the new value",
                uids.contains(TestServicesCache.SYSTEM_IMAGE_UID));
        assertFalse("UID must be updated to the new value", uids.contains(2));
    }

    public void testGetAllServicesServiceRemoved() {
        TestServicesCache cache = new TestServicesCache(mContext, mDataDir);
        cache.addServiceForQuerying(0, r1, new RegisteredServicesCache.ServiceInfo<>(t1, null, 1));
        cache.addServiceForQuerying(0, r2, new RegisteredServicesCache.ServiceInfo<>(t2, null, 2));
        assertEquals(2, cache.getAllServicesSize(0));
        assertEquals(2, cache.getPersistentServicesSize(0));
        // Re-read data from disk and verify services were saved
        cache = new TestServicesCache(mContext, mDataDir);
        assertEquals(2, cache.getPersistentServicesSize(0));
        // Now register only one service and verify that another one is removed
        cache.addServiceForQuerying(0, r1, new RegisteredServicesCache.ServiceInfo<>(t1, null, 1));
        assertEquals(1, cache.getAllServicesSize(0));
        assertEquals(1, cache.getPersistentServicesSize(0));
    }

    public void testGetAllServicesMultiUser() {
        TestServicesCache cache = new TestServicesCache(mContext, mDataDir);
        int u0 = 0;
        int u1 = 1;
        int pid1 = 1;
        cache.addServiceForQuerying(u0, r1, new RegisteredServicesCache.ServiceInfo<>(t1, null,
                pid1));
        int u1uid = UserHandle.getUid(u1, 0);
        cache.addServiceForQuerying(u1, r2, new RegisteredServicesCache.ServiceInfo<>(t2, null,
                u1uid));
        assertEquals(u1, cache.getAllServicesSize(u0));
        assertEquals(u1, cache.getPersistentServicesSize(u0));
        assertEquals(u1, cache.getAllServicesSize(u1));
        assertEquals(u1, cache.getPersistentServicesSize(u1));
        assertEquals("No services should be available for user 3", 0, cache.getAllServicesSize(3));
        // Re-read data from disk and verify services were saved
        cache = new TestServicesCache(mContext, mDataDir);
        assertEquals(u1, cache.getPersistentServicesSize(u0));
        assertEquals(u1, cache.getPersistentServicesSize(u1));
    }

    /**
     * Mock implementation of {@link android.content.pm.RegisteredServicesCache} for testing
     */
    private static class TestServicesCache extends RegisteredServicesCache<TestServiceType> {
        static final String SERVICE_INTERFACE = "RegisteredServicesCacheTest";
        static final String SERVICE_META_DATA = "RegisteredServicesCacheTest";
        static final String ATTRIBUTES_NAME = "test";
        // Represents UID of a system image process
        static final int SYSTEM_IMAGE_UID = 20;
        private SparseArray<Map<ResolveInfo, ServiceInfo<TestServiceType>>> mServices
                = new SparseArray<>();

        public TestServicesCache(Context context, File dir) {
            super(context, SERVICE_INTERFACE, SERVICE_META_DATA, ATTRIBUTES_NAME,
                    new TestSerializer(), dir);
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

        void addServiceForQuerying(int userId, ResolveInfo resolveInfo,
                ServiceInfo<TestServiceType> serviceInfo) {
            Map<ResolveInfo, ServiceInfo<TestServiceType>> map = mServices.get(userId);
            if (map == null) {
                map = new HashMap<>();
                mServices.put(userId, map);
            }
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
