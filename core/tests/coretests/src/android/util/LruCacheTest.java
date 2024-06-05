/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
public final class LruCacheTest {
    private int expectedCreateCount;
    private int expectedPutCount;
    private int expectedHitCount;
    private int expectedMissCount;
    private int expectedEvictionCount;

    @Test
    public void testStatistics() {
        LruCache<String, String> cache = new LruCache<String, String>(3);
        assertStatistics(cache);

        assertEquals(null, cache.put("a", "A"));
        expectedPutCount++;
        assertStatistics(cache);
        assertHit(cache, "a", "A");
        assertSnapshot(cache, "a", "A");

        assertEquals(null, cache.put("b", "B"));
        expectedPutCount++;
        assertStatistics(cache);
        assertHit(cache, "a", "A");
        assertHit(cache, "b", "B");
        assertSnapshot(cache, "a", "A", "b", "B");

        assertEquals(null, cache.put("c", "C"));
        expectedPutCount++;
        assertStatistics(cache);
        assertHit(cache, "a", "A");
        assertHit(cache, "b", "B");
        assertHit(cache, "c", "C");
        assertSnapshot(cache, "a", "A", "b", "B", "c", "C");

        assertEquals(null, cache.put("d", "D"));
        expectedPutCount++;
        expectedEvictionCount++; // a should have been evicted
        assertStatistics(cache);
        assertMiss(cache, "a");
        assertHit(cache, "b", "B");
        assertHit(cache, "c", "C");
        assertHit(cache, "d", "D");
        assertHit(cache, "b", "B");
        assertHit(cache, "c", "C");
        assertSnapshot(cache, "d", "D", "b", "B", "c", "C");

        assertEquals(null, cache.put("e", "E"));
        expectedPutCount++;
        expectedEvictionCount++; // d should have been evicted
        assertStatistics(cache);
        assertMiss(cache, "d");
        assertMiss(cache, "a");
        assertHit(cache, "e", "E");
        assertHit(cache, "b", "B");
        assertHit(cache, "c", "C");
        assertSnapshot(cache, "e", "E", "b", "B", "c", "C");
    }

    @Test
    public void testStatisticsWithCreate() {
        LruCache<String, String> cache = newCreatingCache();
        assertStatistics(cache);

        assertCreated(cache, "aa", "created-aa");
        assertHit(cache, "aa", "created-aa");
        assertSnapshot(cache, "aa", "created-aa");

        assertCreated(cache, "bb", "created-bb");
        assertMiss(cache, "c");
        assertSnapshot(cache, "aa", "created-aa", "bb", "created-bb");

        assertCreated(cache, "cc", "created-cc");
        assertSnapshot(cache, "aa", "created-aa", "bb", "created-bb", "cc", "created-cc");

        expectedEvictionCount++; // aa will be evicted
        assertCreated(cache, "dd", "created-dd");
        assertSnapshot(cache, "bb", "created-bb",  "cc", "created-cc", "dd", "created-dd");

        expectedEvictionCount++; // bb will be evicted
        assertCreated(cache, "aa", "created-aa");
        assertSnapshot(cache, "cc", "created-cc", "dd", "created-dd", "aa", "created-aa");
    }

    @Test
    public void testCreateOnCacheMiss() {
        LruCache<String, String> cache = newCreatingCache();
        String created = cache.get("aa");
        assertEquals("created-aa", created);
    }

    @Test
    public void testNoCreateOnCacheHit() {
        LruCache<String, String> cache = newCreatingCache();
        cache.put("aa", "put-aa");
        assertEquals("put-aa", cache.get("aa"));
    }

    @Test
    public void testConstructorDoesNotAllowZeroCacheSize() {
        try {
            new LruCache<String, String>(0);
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testCannotPutNullKey() {
        LruCache<String, String> cache = new LruCache<String, String>(3);
        try {
            cache.put(null, "A");
            fail();
        } catch (NullPointerException expected) {
        }
    }

    @Test
    public void testCannotPutNullValue() {
        LruCache<String, String> cache = new LruCache<String, String>(3);
        try {
            cache.put("a", null);
            fail();
        } catch (NullPointerException expected) {
        }
    }

    @Test
    public void testToString() {
        LruCache<String, String> cache = new LruCache<String, String>(3);
        assertEquals("LruCache[maxSize=3,hits=0,misses=0,hitRate=0%]", cache.toString());

        cache.put("a", "A");
        cache.put("b", "B");
        cache.put("c", "C");
        cache.put("d", "D");

        cache.get("a"); // miss
        cache.get("b"); // hit
        cache.get("c"); // hit
        cache.get("d"); // hit
        cache.get("e"); // miss

        assertEquals("LruCache[maxSize=3,hits=3,misses=2,hitRate=60%]", cache.toString());
    }

    @Test
    public void testEvictionWithSingletonCache() {
        LruCache<String, String> cache = new LruCache<String, String>(1);
        cache.put("a", "A");
        cache.put("b", "B");
        assertSnapshot(cache, "b", "B");
    }

    @Test
    public void testEntryEvictedWhenFull() {
        List<String> log = new ArrayList<String>();
        LruCache<String, String> cache = newRemovalLogCache(log);

        cache.put("a", "A");
        cache.put("b", "B");
        cache.put("c", "C");
        assertEquals(Collections.<String>emptyList(), log);

        cache.put("d", "D");
        assertEquals(Arrays.asList("a=A"), log);
    }

    /**
     * Replacing the value for a key doesn't cause an eviction but it does bring
     * the replaced entry to the front of the queue.
     */
    @Test
    public void testPutCauseEviction() {
        List<String> log = new ArrayList<String>();
        LruCache<String, String> cache = newRemovalLogCache(log);

        cache.put("a", "A");
        cache.put("b", "B");
        cache.put("c", "C");
        cache.put("b", "B2");
        assertEquals(Arrays.asList("b=B>B2"), log);
        assertSnapshot(cache, "a", "A", "c", "C", "b", "B2");
    }

    @Test
    public void testCustomSizesImpactsSize() {
        LruCache<String, String> cache = new LruCache<String, String>(10) {
            @Override protected int sizeOf(String key, String value) {
                return key.length() + value.length();
            }
        };

        assertEquals(0, cache.size());
        cache.put("a", "AA");
        assertEquals(3, cache.size());
        cache.put("b", "BBBB");
        assertEquals(8, cache.size());
        cache.put("a", "");
        assertEquals(6, cache.size());
    }

    @Test
    public void testEvictionWithCustomSizes() {
        LruCache<String, String> cache = new LruCache<String, String>(4) {
            @Override protected int sizeOf(String key, String value) {
                return value.length();
            }
        };

        cache.put("a", "AAAA");
        assertSnapshot(cache, "a", "AAAA");
        cache.put("b", "BBBB"); // should evict a
        assertSnapshot(cache, "b", "BBBB");
        cache.put("c", "CC"); // should evict b
        assertSnapshot(cache, "c", "CC");
        cache.put("d", "DD");
        assertSnapshot(cache, "c", "CC", "d", "DD");
        cache.put("e", "E"); // should evict c
        assertSnapshot(cache, "d", "DD", "e", "E");
        cache.put("f", "F");
        assertSnapshot(cache, "d", "DD", "e", "E", "f", "F");
        cache.put("g", "G"); // should evict d
        assertSnapshot(cache, "e", "E", "f", "F", "g", "G");
        cache.put("h", "H");
        assertSnapshot(cache, "e", "E", "f", "F", "g", "G", "h", "H");
        cache.put("i", "III"); // should evict e, f, and g
        assertSnapshot(cache, "h", "H", "i", "III");
        cache.put("j", "JJJ"); // should evict h and i
        assertSnapshot(cache, "j", "JJJ");
    }

    @Test
    public void testEvictionThrowsWhenSizesAreInconsistent() {
        LruCache<String, int[]> cache = new LruCache<String, int[]>(4) {
            @Override protected int sizeOf(String key, int[] value) {
                return value[0];
            }
        };

        int[] a = { 4 };
        cache.put("a", a);

        // get the cache size out of sync
        a[0] = 1;
        assertEquals(4, cache.size());

        // evict something
        try {
            cache.put("b", new int[] { 2 });
            fail();
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void testEvictionThrowsWhenSizesAreNegative() {
        LruCache<String, String> cache = new LruCache<String, String>(4) {
            @Override protected int sizeOf(String key, String value) {
                return -1;
            }
        };

        try {
            cache.put("a", "A");
            fail();
        } catch (IllegalStateException expected) {
        }
    }

    /**
     * Naive caches evict at most one element at a time. This is problematic
     * because evicting a small element may be insufficient to make room for a
     * large element.
     */
    @Test
    public void testDifferentElementSizes() {
        LruCache<String, String> cache = new LruCache<String, String>(10) {
            @Override protected int sizeOf(String key, String value) {
                return value.length();
            }
        };

        cache.put("a", "1");
        cache.put("b", "12345678");
        cache.put("c", "1");
        assertSnapshot(cache, "a", "1", "b", "12345678", "c", "1");
        cache.put("d", "12345678"); // should evict a and b
        assertSnapshot(cache, "c", "1", "d", "12345678");
        cache.put("e", "12345678"); // should evict c and d
        assertSnapshot(cache, "e", "12345678");
    }

    @Test
    public void testEvictAll() {
        List<String> log = new ArrayList<String>();
        LruCache<String, String> cache = newRemovalLogCache(log);
        cache.put("a", "A");
        cache.put("b", "B");
        cache.put("c", "C");
        cache.evictAll();
        assertEquals(0, cache.size());
        assertEquals(Arrays.asList("a=A", "b=B", "c=C"), log);
    }

    @Test
    public void testEvictAllEvictsSizeZeroElements() {
        LruCache<String, String> cache = new LruCache<String, String>(10) {
            @Override protected int sizeOf(String key, String value) {
                return 0;
            }
        };

        cache.put("a", "A");
        cache.put("b", "B");
        cache.evictAll();
        assertSnapshot(cache);
    }

    @Test
    public void testRemoveWithCustomSizes() {
        LruCache<String, String> cache = new LruCache<String, String>(10) {
            @Override protected int sizeOf(String key, String value) {
                return value.length();
            }
        };
        cache.put("a", "123456");
        cache.put("b", "1234");
        cache.remove("a");
        assertEquals(4, cache.size());
    }

    @Test
    public void testRemoveAbsentElement() {
        LruCache<String, String> cache = new LruCache<String, String>(10);
        cache.put("a", "A");
        cache.put("b", "B");
        assertEquals(null, cache.remove("c"));
        assertEquals(2, cache.size());
    }

    @Test
    public void testRemoveNullThrows() {
        LruCache<String, String> cache = new LruCache<String, String>(10);
        try {
            cache.remove(null);
            fail();
        } catch (NullPointerException expected) {
        }
    }

    @Test
    public void testRemoveCallsEntryRemoved() {
        List<String> log = new ArrayList<String>();
        LruCache<String, String> cache = newRemovalLogCache(log);
        cache.put("a", "A");
        cache.remove("a");
        assertEquals(Arrays.asList("a=A>null"), log);
    }

    @Test
    public void testPutCallsEntryRemoved() {
        List<String> log = new ArrayList<String>();
        LruCache<String, String> cache = newRemovalLogCache(log);
        cache.put("a", "A");
        cache.put("a", "A2");
        assertEquals(Arrays.asList("a=A>A2"), log);
    }

    @Test
    public void testEntryRemovedIsCalledWithoutSynchronization() {
        LruCache<String, String> cache = new LruCache<String, String>(3) {
            @Override protected void entryRemoved(
                    boolean evicted, String key, String oldValue, String newValue) {
                assertFalse(Thread.holdsLock(this));
            }
        };

        cache.put("a", "A");
        cache.put("a", "A2"); // replaced
        cache.put("b", "B");
        cache.put("c", "C");
        cache.put("d", "D");  // single eviction
        cache.remove("a");    // removed
        cache.evictAll();     // multiple eviction
    }

    @Test
    public void testCreateIsCalledWithoutSynchronization() {
        LruCache<String, String> cache = new LruCache<String, String>(3) {
            @Override protected String create(String key) {
                assertFalse(Thread.holdsLock(this));
                return null;
            }
        };

        cache.get("a");
    }

    /**
     * Test what happens when a value is added to the map while create is
     * working. The map value should be returned by get(), and the created value
     * should be released with entryRemoved().
     */
    @Test
    public void testCreateWithConcurrentPut() {
        final List<String> log = new ArrayList<String>();
        LruCache<String, String> cache = new LruCache<String, String>(3) {
            @Override protected String create(String key) {
                put(key, "B");
                return "A";
            }
            @Override protected void entryRemoved(
                    boolean evicted, String key, String oldValue, String newValue) {
                log.add(key + "=" + oldValue + ">" + newValue);
            }
        };

        assertEquals("B", cache.get("a"));
        assertEquals(Arrays.asList("a=A>B"), log);
    }

    /**
     * Test what happens when two creates happen concurrently. The result from
     * the first create to return is returned by both gets. The other created
     * values should be released with entryRemove().
     */
    @Test
    public void testCreateWithConcurrentCreate() {
        final List<String> log = new ArrayList<String>();
        LruCache<String, Integer> cache = new LruCache<String, Integer>(3) {
            int callCount = 0;
            @Override protected Integer create(String key) {
                if (callCount++ == 0) {
                    assertEquals(2, get(key).intValue());
                    return 1;
                } else {
                    return 2;
                }
            }
            @Override protected void entryRemoved(
                    boolean evicted, String key, Integer oldValue, Integer newValue) {
                log.add(key + "=" + oldValue + ">" + newValue);
            }
        };

        assertEquals(2, cache.get("a").intValue());
        assertEquals(Arrays.asList("a=1>2"), log);
    }

    private LruCache<String, String> newCreatingCache() {
        return new LruCache<String, String>(3) {
            @Override protected String create(String key) {
                return (key.length() > 1) ? ("created-" + key) : null;
            }
        };
    }

    private LruCache<String, String> newRemovalLogCache(final List<String> log) {
        return new LruCache<String, String>(3) {
            @Override protected void entryRemoved(
                    boolean evicted, String key, String oldValue, String newValue) {
                String message = evicted
                        ? (key + "=" + oldValue)
                        : (key + "=" + oldValue + ">" + newValue);
                log.add(message);
            }
        };
    }

    private void assertHit(LruCache<String, String> cache, String key, String value) {
        assertEquals(value, cache.get(key));
        expectedHitCount++;
        assertStatistics(cache);
    }

    private void assertMiss(LruCache<String, String> cache, String key) {
        assertEquals(null, cache.get(key));
        expectedMissCount++;
        assertStatistics(cache);
    }

    private void assertCreated(LruCache<String, String> cache, String key, String value) {
        assertEquals(value, cache.get(key));
        expectedMissCount++;
        expectedCreateCount++;
        assertStatistics(cache);
    }

    private void assertStatistics(LruCache<?, ?> cache) {
        assertEquals("create count", expectedCreateCount, cache.createCount());
        assertEquals("put count", expectedPutCount, cache.putCount());
        assertEquals("hit count", expectedHitCount, cache.hitCount());
        assertEquals("miss count", expectedMissCount, cache.missCount());
        assertEquals("eviction count", expectedEvictionCount, cache.evictionCount());
    }

    private <T> void assertSnapshot(LruCache<T, T> cache, T... keysAndValues) {
        List<T> actualKeysAndValues = new ArrayList<T>();
        for (Map.Entry<T, T> entry : cache.snapshot().entrySet()) {
            actualKeysAndValues.add(entry.getKey());
            actualKeysAndValues.add(entry.getValue());
        }

        // assert using lists because order is important for LRUs
        assertEquals(Arrays.asList(keysAndValues), actualKeysAndValues);
    }
}
