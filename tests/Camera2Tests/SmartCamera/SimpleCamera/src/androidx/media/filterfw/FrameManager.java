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

package androidx.media.filterfw;

import androidx.media.filterfw.BackingStore.Backing;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * The FrameManager tracks, caches, allocates and deallocates frame data.
 * All Frame instances are managed by a FrameManager, and belong to exactly one of these. Frames
 * cannot be shared across FrameManager instances, however multiple MffContexts may use the same
 * FrameManager.
 *
 * Additionally, frame managers allow attaching Frames under a specified key. This allows decoupling
 * filter-graphs by instructing one node to attach a frame under a specific key, and another to
 * fetch the frame under the same key.
 */
public class FrameManager {

    /** The default max cache size is set to 12 MB */
    public final static int DEFAULT_MAX_CACHE_SIZE = 12 * 1024 * 1024;

    /** Frame caching policy: No caching */
    public final static int FRAME_CACHE_NONE = 0;
    /** Frame caching policy: Drop least recently used frame buffers */
    public final static int FRAME_CACHE_LRU = 1;
    /** Frame caching policy: Drop least frequently used frame buffers */
    public final static int FRAME_CACHE_LFU = 2;

    /** Slot Flag: No flags set */
    public final static int SLOT_FLAGS_NONE = 0x00;
    /** Slot Flag: Sticky flag set: Frame will remain in slot after fetch. */
    public final static int SLOT_FLAG_STICKY = 0x01;

    private GraphRunner mRunner;
    private Set<Backing> mBackings = new HashSet<Backing>();
    private BackingCache mCache;

    private Map<String, FrameSlot> mFrameSlots = new HashMap<String, FrameSlot>();

    static class FrameSlot {
        private FrameType mType;
        private int mFlags;
        private Frame mFrame = null;

        public FrameSlot(FrameType type, int flags) {
            mType = type;
            mFlags = flags;
        }

        public FrameType getType() {
            return mType;
        }

        public boolean hasFrame() {
            return mFrame != null;
        }

        public void releaseFrame() {
            if (mFrame != null) {
                mFrame.release();
                mFrame = null;
            }
        }

        // TODO: Type check
        public void assignFrame(Frame frame) {
            Frame oldFrame = mFrame;
            mFrame = frame.retain();
            if (oldFrame != null) {
                oldFrame.release();
            }
        }

        public Frame getFrame() {
            Frame result = mFrame.retain();
            if ((mFlags & SLOT_FLAG_STICKY) == 0) {
                releaseFrame();
            }
            return result;
        }

        public void markWritable() {
            if (mFrame != null) {
                mFrame.setReadOnly(false);
            }
        }
    }

    private static abstract class BackingCache {

        protected int mCacheMaxSize = DEFAULT_MAX_CACHE_SIZE;

        public abstract Backing fetchBacking(int mode, int access, int[] dimensions, int elemSize);

        public abstract boolean cacheBacking(Backing backing);

        public abstract void clear();

        public abstract int getSizeLeft();

        public void setSize(int size) {
            mCacheMaxSize = size;
        }

        public int getSize() {
            return mCacheMaxSize;
        }
    }

    private static class BackingCacheNone extends BackingCache {

        @Override
        public Backing fetchBacking(int mode, int access, int[] dimensions, int elemSize) {
            return null;
        }

        @Override
        public boolean cacheBacking(Backing backing) {
            return false;
        }

        @Override
        public void clear() {
        }

        @Override
        public int getSize() {
            return 0;
        }

        @Override
        public int getSizeLeft() {
            return 0;
        }
    }

    private static abstract class PriorityBackingCache extends BackingCache {
        private int mSize = 0;
        private PriorityQueue<Backing> mQueue;

        public PriorityBackingCache() {
            mQueue = new PriorityQueue<Backing>(4, new Comparator<Backing>() {
                @Override
                public int compare(Backing left, Backing right) {
                    return left.cachePriority - right.cachePriority;
                }
            });
        }

        @Override
        public Backing fetchBacking(int mode, int access, int[] dimensions, int elemSize) {
            for (Backing backing : mQueue) {
                int backingAccess = (mode == Frame.MODE_WRITE)
                    ? backing.writeAccess()
                    : backing.readAccess();
                if ((backingAccess & access) == access
                    && dimensionsCompatible(backing.getDimensions(), dimensions)
                    && (elemSize == backing.getElementSize())) {
                    mQueue.remove(backing);
                    mSize -= backing.getSize();
                    onFetchBacking(backing);
                    return backing;
                }
            }
            //Log.w("FrameManager", "Could not find backing for dimensions " + Arrays.toString(dimensions));
            return null;
        }

        @Override
        public boolean cacheBacking(Backing backing) {
            if (reserve(backing.getSize())) {
                onCacheBacking(backing);
                mQueue.add(backing);
                return true;
            }
            return false;
        }

        @Override
        public void clear() {
            mQueue.clear();
            mSize = 0;
        }

        @Override
        public int getSizeLeft() {
            return mCacheMaxSize - mSize;
        }

        protected abstract void onCacheBacking(Backing backing);

        protected abstract void onFetchBacking(Backing backing);

        private boolean reserve(int size) {
            //Log.i("FM", "Reserving " + size + " bytes (max: " + mCacheMaxSize + " bytes).");
            //Log.i("FM", "Current size " + mSize);
            if (size > mCacheMaxSize) {
                return false;
            }
            mSize += size;
            while (mSize > mCacheMaxSize) {
                Backing dropped = mQueue.poll();
                mSize -= dropped.getSize();
                //Log.i("FM", "Dropping  " + dropped + " with priority "
                //    + dropped.cachePriority + ". New size: " + mSize + "!");
                dropped.destroy();
            }
            return true;
        }


    }

    private static class BackingCacheLru extends PriorityBackingCache {
        private int mTimestamp = 0;

        @Override
        protected void onCacheBacking(Backing backing) {
            backing.cachePriority = 0;
        }

        @Override
        protected void onFetchBacking(Backing backing) {
            ++mTimestamp;
            backing.cachePriority = mTimestamp;
        }
    }

    private static class BackingCacheLfu extends PriorityBackingCache {
        @Override
        protected void onCacheBacking(Backing backing) {
            backing.cachePriority = 0;
        }

        @Override
        protected void onFetchBacking(Backing backing) {
            ++backing.cachePriority;
        }
    }

    public static FrameManager current() {
        GraphRunner runner = GraphRunner.current();
        return runner != null ? runner.getFrameManager() : null;
    }

    /**
     * Returns the context that the FrameManager is bound to.
     *
     * @return the MffContext instance that the FrameManager is bound to.
     */
    public MffContext getContext() {
        return mRunner.getContext();
    }

    /**
     * Returns the GraphRunner that the FrameManager is bound to.
     *
     * @return the GraphRunner instance that the FrameManager is bound to.
     */
    public GraphRunner getRunner() {
        return mRunner;
    }

    /**
     * Sets the size of the cache.
     *
     * Resizes the cache to the specified size in bytes.
     *
     * @param bytes the new size in bytes.
     */
    public void setCacheSize(int bytes) {
        mCache.setSize(bytes);
    }

    /**
     * Returns the size of the cache.
     *
     * @return the size of the cache in bytes.
     */
    public int getCacheSize() {
        return mCache.getSize();
    }

    /**
     * Imports a frame from another FrameManager.
     *
     * This will return a frame with the contents of the given frame for use in this FrameManager.
     * Note, that there is a substantial cost involved in moving a Frame from one FrameManager to
     * another. This may be called from any thread. After the frame has been imported, it may be
     * used in the runner that uses this FrameManager. As the new frame may share data with the
     * provided frame, that frame must be read-only.
     *
     * @param frame The frame to import
     */
    public Frame importFrame(Frame frame) {
        if (!frame.isReadOnly()) {
            throw new IllegalArgumentException("Frame " + frame + " must be read-only to import "
                    + "into another FrameManager!");
        }
        return frame.makeCpuCopy(this);
    }

    /**
     * Adds a new frame slot to the frame manager.
     * Filters can reference frame slots to pass frames between graphs or runs. If the name
     * specified here is already taken the frame slot is overwritten. You can only
     * modify frame-slots while no graph of the frame manager is running.
     *
     * @param name The name of the slot.
     * @param type The type of Frame that will be assigned to this slot.
     * @param flags A mask of {@code SLOT} flags.
     */
    public void addFrameSlot(String name, FrameType type, int flags) {
        assertNotRunning();
        FrameSlot oldSlot = mFrameSlots.get(name);
        if (oldSlot != null) {
            removeFrameSlot(name);
        }
        FrameSlot slot = new FrameSlot(type, flags);
        mFrameSlots.put(name, slot);
    }

    /**
     * Removes a frame slot from the frame manager.
     * Any frame within the slot is released. You can only modify frame-slots while no graph
     * of the frame manager is running.
     *
     * @param name The name of the slot
     * @throws IllegalArgumentException if no such slot exists.
     */
    public void removeFrameSlot(String name) {
        assertNotRunning();
        FrameSlot slot = getSlot(name);
        slot.releaseFrame();
        mFrameSlots.remove(name);
    }

    /**
     * TODO: Document!
     */
    public void storeFrame(Frame frame, String slotName) {
        assertInGraphRun();
        getSlot(slotName).assignFrame(frame);
    }

    /**
     * TODO: Document!
     */
    public Frame fetchFrame(String slotName) {
        assertInGraphRun();
        return getSlot(slotName).getFrame();
    }

    /**
     * Clears the Frame cache.
     */
    public void clearCache() {
        mCache.clear();
    }

    /**
     * Create a new FrameManager instance.
     *
     * Creates a new FrameManager instance in the specified context and employing a cache with the
     * specified cache type (see the cache type constants defined by the FrameManager class).
     *
     * @param runner the GraphRunner to bind the FrameManager to.
     * @param cacheType the type of cache to use.
     */
    FrameManager(GraphRunner runner, int cacheType) {
        mRunner = runner;
        switch (cacheType) {
            case FRAME_CACHE_NONE:
                mCache = new BackingCacheNone();
                break;
            case FRAME_CACHE_LRU:
                mCache = new BackingCacheLru();
                break;
            case FRAME_CACHE_LFU:
                mCache = new BackingCacheLfu();
                break;
            default:
                throw new IllegalArgumentException("Unknown cache-type " + cacheType + "!");
        }
    }

    Backing fetchBacking(int mode, int access, int[] dimensions, int elemSize) {
        return mCache.fetchBacking(mode, access, dimensions, elemSize);
    }

    void onBackingCreated(Backing backing) {
        if (backing != null) {
            mBackings.add(backing);
            // Log.i("FrameManager", "RM: Now have " + mBackings.size() + " backings");
        }
    }

    void onBackingAvailable(Backing backing) {
        if (!backing.shouldCache() || !mCache.cacheBacking(backing)) {
            backing.destroy();
            mBackings.remove(backing);
            //Log.i("FrameManager", "RM: Now have " + mBackings.size() + " backings (" + mCache.getSizeLeft() + ")");
        }
    }

    /**
     * Destroying all references makes any Frames that contain them invalid.
     */
    void destroyBackings() {
        for (Backing backing : mBackings) {
            backing.destroy();
        }
        mBackings.clear();
        mCache.clear();
    }

    FrameSlot getSlot(String name) {
        FrameSlot slot = mFrameSlots.get(name);
        if (slot == null) {
            throw new IllegalArgumentException("Unknown frame slot '" + name + "'!");
        }
        return slot;
    }

    void onBeginRun() {
        for (FrameSlot slot : mFrameSlots.values()) {
            slot.markWritable();
        }
    }

    // Internals ///////////////////////////////////////////////////////////////////////////////////
    private static boolean dimensionsCompatible(int[] dimA, int[] dimB) {
        return dimA == null || dimB == null || Arrays.equals(dimA, dimB);
    }

    private void assertNotRunning() {
        if (mRunner.isRunning()) {
            throw new IllegalStateException("Attempting to modify FrameManager while graph is "
                + "running!");
        }
    }

    private void assertInGraphRun() {
        if (!mRunner.isRunning() || GraphRunner.current() != mRunner) {
            throw new IllegalStateException("Attempting to access FrameManager Frame data "
                + "outside of graph run-loop!");
        }
    }

}

