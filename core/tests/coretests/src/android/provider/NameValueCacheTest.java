/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.provider;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.ContentProvider;
import android.content.IContentProvider;
import android.os.Bundle;
import android.platform.test.annotations.Presubmit;
import android.test.mock.MockContentResolver;
import android.util.MemoryIntArray;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * These tests are verifying that Settings#NameValueCache is working as expected with DeviceConfig.
 * Due to how the classes are structured, we have to test it in a somewhat roundabout way. We're
 * mocking out the contentProvider and are handcrafting very specific Bundles to answer the queries.
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
@SmallTest
public class NameValueCacheTest {

    private static final String NAMESPACE = "namespace";
    private static final String NAMESPACE2 = "namespace2";

    @Mock
    private IContentProvider mMockIContentProvider;
    @Mock
    private ContentProvider mMockContentProvider;
    private MockContentResolver mMockContentResolver;
    private MemoryIntArray mCacheGenerationStore;
    private int mCurrentGeneration = 123;

    private HashMap<String, HashMap<String, String>> mStorage;

    @Before
    public void setUp() throws Exception {
        Settings.Config.clearProviderForTest();
        MockitoAnnotations.initMocks(this);
        when(mMockContentProvider.getIContentProvider()).thenReturn(mMockIContentProvider);
        mMockContentResolver = new MockContentResolver(InstrumentationRegistry
                .getInstrumentation().getContext());
        mMockContentResolver.addProvider(DeviceConfig.CONTENT_URI.getAuthority(),
                mMockContentProvider);
        mCacheGenerationStore = new MemoryIntArray(1);
        mStorage = new HashMap<>();

        // Stores keyValues for a given prefix and increments the generation. (Note that this
        // increments the generation no matter what, it doesn't pay attention to if anything
        // actually changed).
        when(mMockIContentProvider.call(any(), eq(DeviceConfig.CONTENT_URI.getAuthority()),
                eq(Settings.CALL_METHOD_SET_ALL_CONFIG),
                any(), any(Bundle.class))).thenAnswer(invocationOnMock -> {
                    Bundle incomingBundle = invocationOnMock.getArgument(4);
                    HashMap<String, String> keyValues =
                            (HashMap<String, String>) incomingBundle.getSerializable(
                                    Settings.CALL_METHOD_FLAGS_KEY);
                    String prefix = incomingBundle.getString(Settings.CALL_METHOD_PREFIX_KEY);
                    mStorage.put(prefix, keyValues);
                    mCacheGenerationStore.set(0, ++mCurrentGeneration);

                    Bundle result = new Bundle();
                    result.putBoolean(Settings.KEY_CONFIG_SET_RETURN, true);
                    return result;
                });

        // Returns the keyValues corresponding to a namespace, or an empty map if the namespace
        // doesn't have anything stored for it. Returns the generation key if the caller asked
        // for one.
        when(mMockIContentProvider.call(any(), eq(DeviceConfig.CONTENT_URI.getAuthority()),
                eq(Settings.CALL_METHOD_LIST_CONFIG),
                any(), any(Bundle.class))).thenAnswer(invocationOnMock -> {
                    Bundle incomingBundle = invocationOnMock.getArgument(4);

                    String prefix = incomingBundle.getString(Settings.CALL_METHOD_PREFIX_KEY);

                    if (!mStorage.containsKey(prefix)) {
                        mStorage.put(prefix, new HashMap<>());
                    }
                    HashMap<String, String> keyValues = mStorage.get(prefix);

                    Bundle bundle = new Bundle();
                    bundle.putSerializable(Settings.NameValueTable.VALUE, keyValues);

                    if (incomingBundle.containsKey(Settings.CALL_METHOD_TRACK_GENERATION_KEY)) {
                        bundle.putParcelable(Settings.CALL_METHOD_TRACK_GENERATION_KEY,
                                mCacheGenerationStore);
                        bundle.putInt(Settings.CALL_METHOD_GENERATION_INDEX_KEY, 0);
                        bundle.putInt(Settings.CALL_METHOD_GENERATION_KEY,
                                mCacheGenerationStore.get(0));
                    }
                    return bundle;
                });
    }

    @Test
    public void testCaching_singleNamespace() throws Exception {
        HashMap<String, String> keyValues = new HashMap<>();
        keyValues.put("a", "b");
        Settings.Config.setStrings(mMockContentResolver, NAMESPACE, keyValues);
        verify(mMockIContentProvider).call(any(), any(),
                eq(Settings.CALL_METHOD_SET_ALL_CONFIG),
                any(), any(Bundle.class));

        Map<String, String> returnedValues = Settings.Config.getStrings(mMockContentResolver,
                NAMESPACE,
                Collections.emptyList());
        verify(mMockIContentProvider).call(any(), any(),
                eq(Settings.CALL_METHOD_LIST_CONFIG),
                any(), any(Bundle.class));
        assertThat(returnedValues).containsExactlyEntriesIn(keyValues);

        Map<String, String> cachedKeyValues = Settings.Config.getStrings(mMockContentResolver,
                NAMESPACE, Collections.emptyList());
        verifyNoMoreInteractions(mMockIContentProvider);
        assertThat(cachedKeyValues).containsExactlyEntriesIn(keyValues);

        // Modify the value to invalidate the cache.
        keyValues.put("a", "c");
        Settings.Config.setStrings(mMockContentResolver, NAMESPACE, keyValues);
        verify(mMockIContentProvider, times(2)).call(any(), any(),
                eq(Settings.CALL_METHOD_SET_ALL_CONFIG),
                any(), any(Bundle.class));

        Map<String, String> returnedValues2 = Settings.Config.getStrings(mMockContentResolver,
                NAMESPACE, Collections.emptyList());
        verify(mMockIContentProvider, times(2)).call(any(), any(),
                eq(Settings.CALL_METHOD_LIST_CONFIG),
                any(), any(Bundle.class));
        assertThat(returnedValues2).containsExactlyEntriesIn(keyValues);

        Map<String, String> cachedKeyValues2 = Settings.Config.getStrings(mMockContentResolver,
                NAMESPACE, Collections.emptyList());
        verifyNoMoreInteractions(mMockIContentProvider);
        assertThat(cachedKeyValues2).containsExactlyEntriesIn(keyValues);
    }

    @Test
    public void testCaching_multipleNamespaces() throws Exception {
        HashMap<String, String> keyValues = new HashMap<>();
        keyValues.put("a", "b");
        Settings.Config.setStrings(mMockContentResolver, NAMESPACE, keyValues);
        verify(mMockIContentProvider).call(any(), any(),
                eq(Settings.CALL_METHOD_SET_ALL_CONFIG),
                any(), any(Bundle.class));

        HashMap<String, String> keyValues2 = new HashMap<>();
        keyValues2.put("c", "d");
        keyValues2.put("e", "f");
        Settings.Config.setStrings(mMockContentResolver, NAMESPACE2, keyValues2);
        verify(mMockIContentProvider, times(2)).call(any(), any(),
                eq(Settings.CALL_METHOD_SET_ALL_CONFIG),
                any(), any(Bundle.class));

        Map<String, String> returnedValues = Settings.Config.getStrings(mMockContentResolver,
                NAMESPACE,
                Collections.emptyList());
        verify(mMockIContentProvider).call(any(), any(),
                eq(Settings.CALL_METHOD_LIST_CONFIG),
                any(), any(Bundle.class));
        assertThat(returnedValues).containsExactlyEntriesIn(keyValues);

        Map<String, String> returnedValues2 = Settings.Config.getStrings(mMockContentResolver,
                NAMESPACE2,
                Collections.emptyList());
        verify(mMockIContentProvider, times(2)).call(any(), any(),
                eq(Settings.CALL_METHOD_LIST_CONFIG),
                any(), any(Bundle.class));
        assertThat(returnedValues2).containsExactlyEntriesIn(keyValues2);

        Map<String, String> cachedKeyValues = Settings.Config.getStrings(mMockContentResolver,
                NAMESPACE, Collections.emptyList());
        verifyNoMoreInteractions(mMockIContentProvider);
        assertThat(cachedKeyValues).containsExactlyEntriesIn(keyValues);

        Map<String, String> cachedKeyValues2 = Settings.Config.getStrings(mMockContentResolver,
                NAMESPACE2, Collections.emptyList());
        verifyNoMoreInteractions(mMockIContentProvider);
        assertThat(cachedKeyValues2).containsExactlyEntriesIn(keyValues2);
    }

    @Test
    public void testCaching_emptyNamespace() throws Exception {
        Map<String, String> returnedValues = Settings.Config.getStrings(mMockContentResolver,
                NAMESPACE,
                Collections.emptyList());
        verify(mMockIContentProvider).call(any(), any(),
                eq(Settings.CALL_METHOD_LIST_CONFIG),
                any(), any(Bundle.class));
        assertThat(returnedValues).isEmpty();

        Map<String, String> cachedKeyValues = Settings.Config.getStrings(mMockContentResolver,
                NAMESPACE, Collections.emptyList());
        verifyNoMoreInteractions(mMockIContentProvider);
        assertThat(cachedKeyValues).isEmpty();
    }

}
