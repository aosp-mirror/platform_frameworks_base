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

package android.ext.services.storage;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.app.usage.CacheQuotaHint;
import android.app.usage.UsageStats;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.test.ServiceTestCase;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class CacheQuotaServiceImplTest extends ServiceTestCase<CacheQuotaServiceImpl> {
    private static final String sTestVolUuid = "uuid";
    private static final String sSecondTestVolUuid = "otherUuid";

    @Mock private Context mContext;
    @Mock private File mFile;
    @Mock private VolumeInfo mVolume;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS) private StorageManager mStorageManager;

    public CacheQuotaServiceImplTest() {
        super(CacheQuotaServiceImpl.class);
    }

    @Before
    public void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);
        mContext = Mockito.spy(new ContextWrapper(getSystemContext()));
        setContext(mContext);
        when(mContext.getSystemService(Context.STORAGE_SERVICE)).thenReturn(mStorageManager);

        when(mFile.getUsableSpace()).thenReturn(10000L);
        when(mVolume.getPath()).thenReturn(mFile);
        when(mStorageManager.findVolumeByUuid(sTestVolUuid)).thenReturn(mVolume);
        when(mStorageManager.findVolumeByUuid(sSecondTestVolUuid)).thenReturn(mVolume);

        Intent intent = new Intent(getContext(), CacheQuotaServiceImpl.class);
        startService(intent);
    }

    @Test
    public void testNoApps() {
        CacheQuotaServiceImpl service = getService();
        assertEquals(service.onComputeCacheQuotaHints(new ArrayList()).size(), 0);
    }

    @Test
    public void testOneApp() throws Exception {
        ArrayList<CacheQuotaHint> requests = new ArrayList<>();
        CacheQuotaHint request = makeNewRequest("com.test", sTestVolUuid, 1001, 100L);
        requests.add(request);

        List<CacheQuotaHint> output = getService().onComputeCacheQuotaHints(requests);

        assertThat(output).hasSize(1);
        assertThat(output.get(0).getQuota()).isEqualTo(1500L);
    }

    @Test
    public void testTwoAppsOneVolume() throws Exception {
        ArrayList<CacheQuotaHint> requests = new ArrayList<>();
        requests.add(makeNewRequest("com.test", sTestVolUuid, 1001, 100L));
        requests.add(makeNewRequest("com.test2", sTestVolUuid, 1002, 99L));

        List<CacheQuotaHint> output = getService().onComputeCacheQuotaHints(requests);

        // Note that the sizes are just the cache area split up.
        assertThat(output).hasSize(2);
        assertThat(output.get(0).getQuota()).isEqualTo(883);
        assertThat(output.get(1).getQuota()).isEqualTo(1500 - 883);
    }

    @Test
    public void testTwoAppsTwoVolumes() throws Exception {
        ArrayList<CacheQuotaHint> requests = new ArrayList<>();
        requests.add(makeNewRequest("com.test", sTestVolUuid, 1001, 100L));
        requests.add(makeNewRequest("com.test2", sSecondTestVolUuid, 1002, 99L));

        List<CacheQuotaHint> output = getService().onComputeCacheQuotaHints(requests);

        assertThat(output).hasSize(2);
        assertThat(output.get(0).getQuota()).isEqualTo(1500);
        assertThat(output.get(1).getQuota()).isEqualTo(1500);
    }

    @Test
    public void testMultipleAppsPerUidIsCollated() throws Exception {
        ArrayList<CacheQuotaHint> requests = new ArrayList<>();
        requests.add(makeNewRequest("com.test", sTestVolUuid, 1001, 100L));
        requests.add(makeNewRequest("com.test2", sTestVolUuid, 1001, 99L));

        List<CacheQuotaHint> output = getService().onComputeCacheQuotaHints(requests);

        assertThat(output).hasSize(1);
        assertThat(output.get(0).getQuota()).isEqualTo(1500);
    }

    @Test
    public void testTwoAppsTwoVolumesTwoUuidsShouldBESeparate() throws Exception {
        ArrayList<CacheQuotaHint> requests = new ArrayList<>();
        requests.add(makeNewRequest("com.test", sTestVolUuid, 1001, 100L));
        requests.add(makeNewRequest("com.test2", sSecondTestVolUuid, 1001, 99L));

        List<CacheQuotaHint> output = getService().onComputeCacheQuotaHints(requests);

        assertThat(output).hasSize(2);
        assertThat(output.get(0).getQuota()).isEqualTo(1500);
        assertThat(output.get(1).getQuota()).isEqualTo(1500);
    }

    private CacheQuotaHint makeNewRequest(String packageName, String uuid, int uid, long foregroundTime) {
        UsageStats stats = new UsageStats();
        stats.mPackageName = packageName;
        stats.mTotalTimeInForeground = foregroundTime;
        return new CacheQuotaHint.Builder()
                .setVolumeUuid(uuid).setUid(uid).setUsageStats(stats).setQuota(-1).build();
    }
}
