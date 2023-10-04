/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package androidx.media.filterfw;

import android.graphics.Bitmap;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Vector;

final class BackingStore {

    /** Access mode None: Frame data will not be accessed at all. */
    static final int ACCESS_NONE = 0x00;
    /** Access mode Bytes: Frame data will be accessed as a ByteBuffer. */
    static final int ACCESS_BYTES = 0x01;
    /** Access mode Texture: Frame data will be accessed as a TextureSource. */
    static final int ACCESS_TEXTURE = 0x02;
    /** Access mode RenderTarget: Frame data will be accessed as a RenderTarget. */
    static final int ACCESS_RENDERTARGET = 0x04;
    /** Access mode Object: Frame data will be accessed as a generic Object. */
    static final int ACCESS_OBJECT = 0x08;
    /** Access mode Bitmap: Frame data will be accessed as a Bitmap. */
    static final int ACCESS_BITMAP = 0x10;

    private static final int BACKING_BYTEBUFFER = 1;
    private static final int BACKING_TEXTURE = 2;
    private static final int BACKING_OBJECT = 3;
    private static final int BACKING_BITMAP = 4;

    private final FrameType mType;
    private int[] mDimensions;
    private long mTimestamp = Frame.TIMESTAMP_NOT_SET;

    private final FrameManager mFrameManager;

    private Vector<Backing> mBackings = new Vector<Backing>();

    private boolean mWriteLocked = false;
    private int mReadLocks = 0;

    private int mRefCount = 1;

    /** The most up-to-date data backing */
    private Backing mCurrentBacking = null;

    /** The currently locked backing */
    private Backing mLockedBacking = null;

    // Public Methods //////////////////////////////////////////////////////////////////////////////
    public BackingStore(FrameType type, int[] dimensions, FrameManager frameManager) {
        mType = type;
        mDimensions = dimensions != null ? Arrays.copyOf(dimensions, dimensions.length) : null;
        mFrameManager = frameManager;
    }

    public FrameType getFrameType() {
        return mType;
    }

    public Object lockData(int mode, int accessFormat) {
        return lockBacking(mode, accessFormat).lock(accessFormat);
    }

    public Backing lockBacking(int mode, int access) {
        Backing backing = fetchBacking(mode, access);
        if (backing == null) {
            throw new RuntimeException("Could not fetch frame data!");
        }
        lock(backing, mode);
        return backing;
    }

    public boolean unlock() {
        if (mWriteLocked) {
            mWriteLocked = false;
        } else if (mReadLocks > 0) {
            --mReadLocks;
        } else {
            return false;
        }
        mLockedBacking.unlock();
        mLockedBacking = null;
        return true;
    }

    public BackingStore retain() {
        if (mRefCount >= 10) {
            Log.w("BackingStore", "High ref-count of " + mRefCount + " on " + this + "!");
        }
        if (mRefCount <= 0) {
            throw new RuntimeException("RETAINING RELEASED");
        }
        ++mRefCount;
        return this;
    }

    public BackingStore release() {
        if (mRefCount <= 0) {
            throw new RuntimeException("DOUBLE-RELEASE");
        }
        --mRefCount;
        if (mRefCount == 0) {
            releaseBackings();
            return null;
        }
        return this;
    }

    /**
     * Resizes the backing store. This invalidates all data in the store.
     */
    public void resize(int[] newDimensions) {
        Vector<Backing> resized = new Vector<Backing>();
        for (Backing backing : mBackings) {
            if (backing.resize(newDimensions)) {
                resized.add(backing);
            } else {
                releaseBacking(backing);
            }
        }
        mBackings = resized;
        mDimensions = newDimensions;
    }

    public int[] getDimensions() {
        return mDimensions;
    }

    public int getElementCount() {
        int result = 1;
        if (mDimensions != null) {
            for (int dim : mDimensions) {
                result *= dim;
            }
        }
        return result;
    }

    public void importStore(BackingStore store) {
        // TODO: Better backing selection?
        if (store.mBackings.size() > 0) {
            importBacking(store.mBackings.firstElement());
        }
        mTimestamp = store.mTimestamp;
    }

    /**
     * @return the timestamp
     */
    public long getTimestamp() {
        return mTimestamp;
    }

    /**
     * @param timestamp the timestamp to set
     */
    public void setTimestamp(long timestamp) {
        mTimestamp = timestamp;
    }

    // Internal Methods ////////////////////////////////////////////////////////////////////////////
    private Backing fetchBacking(int mode, int access) {
        Backing backing = getBacking(mode, access);
        if (backing == null) {
            backing = attachNewBacking(mode, access);
        }
        syncBacking(backing);
        return backing;
    }

    private void syncBacking(Backing backing) {
        if (backing != null && backing.isDirty() && mCurrentBacking != null) {
            backing.syncTo(mCurrentBacking);
        }
    }

    private Backing getBacking(int mode, int access) {
        // [Non-iterator looping]
        for (int i = 0; i < mBackings.size(); ++i) {
            final Backing backing = mBackings.get(i);

            int backingAccess =
                    (mode == Frame.MODE_WRITE) ? backing.writeAccess() : backing.readAccess();
            if ((backingAccess & access) == access) {
                return backing;
            }
        }
        return null;
    }

    private Backing attachNewBacking(int mode, int access) {
        Backing backing = createBacking(mode, access);
        if (mBackings.size() > 0) {
            backing.markDirty();
        }
        mBackings.add(backing);
        return backing;
    }

    private Backing createBacking(int mode, int access) {
        // TODO: If the read/write access flags indicate, make/fetch a GraphicBuffer backing.
        Backing backing = null;
        int elemSize = mType.getElementSize();
        if (shouldFetchCached(access)) {
            backing = mFrameManager.fetchBacking(mode, access, mDimensions, elemSize);
        }
        if (backing == null) {
            switch (access) {
                case ACCESS_BYTES:
                    backing = new ByteBufferBacking();
                    break;
                case ACCESS_TEXTURE:
                case ACCESS_RENDERTARGET:
                    backing = new TextureBacking();
                    break;
                case ACCESS_OBJECT:
                    backing = new ObjectBacking();
                    break;
                case ACCESS_BITMAP:
                    backing = new BitmapBacking();
                    break;
            }
            if (backing == null) {
                throw new RuntimeException(
                        "Could not create backing for access type " + access + "!");
            }
            if (backing.requiresGpu() && !mFrameManager.getRunner().isOpenGLSupported()) {
                throw new RuntimeException(
                        "Cannot create backing that requires GPU in a runner that does not " +
                        "support OpenGL!");
            }
            backing.setDimensions(mDimensions);
            backing.setElementSize(elemSize);
            backing.setElementId(mType.getElementId());
            backing.allocate(mType);
            mFrameManager.onBackingCreated(backing);
        }
        return backing;
    }

    private void importBacking(Backing backing) {
        // TODO: This actually needs synchronization between the two BackingStore threads for the
        // general case
        int access = backing.requiresGpu() ? ACCESS_BYTES : backing.readAccess();
        Backing newBacking = createBacking(Frame.MODE_READ, access);
        newBacking.syncTo(backing);
        mBackings.add(newBacking);
        mCurrentBacking = newBacking;
    }

    private void releaseBackings() {
        // [Non-iterator looping]
        for (int i = 0; i < mBackings.size(); ++i) {
            releaseBacking(mBackings.get(i));
        }
        mBackings.clear();
        mCurrentBacking = null;
    }

    private void releaseBacking(Backing backing) {
        mFrameManager.onBackingAvailable(backing);
    }

    private void lock(Backing backingToLock, int mode) {
        if (mode == Frame.MODE_WRITE) {
            // Make sure frame is not read-locked
            if (mReadLocks > 0) {
                throw new RuntimeException(
                        "Attempting to write-lock the read-locked frame " + this + "!");
            } else if (mWriteLocked) {
                throw new RuntimeException(
                        "Attempting to write-lock the write-locked frame " + this + "!");
            }
            // Mark all other backings dirty
            // [Non-iterator looping]
            for (int i = 0; i < mBackings.size(); ++i) {
                final Backing backing = mBackings.get(i);
                if (backing != backingToLock) {
                    backing.markDirty();
                }
            }
            mWriteLocked = true;
            mCurrentBacking = backingToLock;
        } else {
            if (mWriteLocked) {
                throw new RuntimeException("Attempting to read-lock locked frame " + this + "!");
            }
            ++mReadLocks;
        }
        mLockedBacking = backingToLock;
    }

    private static boolean shouldFetchCached(int access) {
        return access != ACCESS_OBJECT;
    }


    // Backings ////////////////////////////////////////////////////////////////////////////////////
    static abstract class Backing {
        protected int[] mDimensions = null;
        private int mElementSize;
        private int mElementID;
        protected boolean mIsDirty = false;

        int cachePriority = 0;

        public abstract void allocate(FrameType frameType);

        public abstract int readAccess();

        public abstract int writeAccess();

        public abstract void syncTo(Backing backing);

        public abstract Object lock(int accessType);

        public abstract int getType();

        public abstract boolean shouldCache();

        public abstract boolean requiresGpu();

        public abstract void destroy();

        public abstract int getSize();

        public void unlock() {
            // Default implementation does nothing.
        }

        public void setData(Object data) {
            throw new RuntimeException("Internal error: Setting data on frame backing " + this
                    + ", which does not support setting data directly!");
        }

        public void setDimensions(int[] dimensions) {
            mDimensions = dimensions;
        }

        public void setElementSize(int elemSize) {
            mElementSize = elemSize;
        }

        public void setElementId(int elemId) {
            mElementID = elemId;
        }

        public int[] getDimensions() {
            return mDimensions;
        }

        public int getElementSize() {
            return mElementSize;
        }

        public int getElementId() {
            return mElementID;
        }

        public boolean resize(int[] newDimensions) {
            return false;
        }

        public void markDirty() {
            mIsDirty = true;
        }

        public boolean isDirty() {
            return mIsDirty;
        }

        protected void assertImageCompatible(FrameType type) {
            if (type.getElementId() != FrameType.ELEMENT_RGBA8888) {
                throw new RuntimeException("Cannot allocate texture with non-RGBA data type!");
            } else if (mDimensions == null || mDimensions.length != 2) {
                throw new RuntimeException("Cannot allocate non 2-dimensional texture!");
            }
        }

    }

    static class ObjectBacking extends Backing {

        private Object mObject = null;

        @Override
        public void allocate(FrameType frameType) {
            mObject = null;
        }

        @Override
        public int readAccess() {
            return ACCESS_OBJECT;
        }

        @Override
        public int writeAccess() {
            return ACCESS_OBJECT;
        }

        @Override
        public void syncTo(Backing backing) {
            switch (backing.getType()) {
                case BACKING_OBJECT:
                    mObject = backing.lock(ACCESS_OBJECT);
                    backing.unlock();
                    break;
                case BACKING_BITMAP:
                    mObject = backing.lock(ACCESS_BITMAP);
                    backing.unlock();
                    break;
                default:
                    mObject = null;
            }
            mIsDirty = false;
        }

        @Override
        public Object lock(int accessType) {
            return mObject;
        }

        @Override
        public int getType() {
            return BACKING_OBJECT;
        }

        @Override
        public boolean shouldCache() {
            return false;
        }

        @Override
        public boolean requiresGpu() {
            return false;
        }

        @Override
        public void destroy() {
            mObject = null;
        }

        @Override
        public int getSize() {
            return 0;
        }

        @Override
        public void setData(Object data) {
            mObject = data;
        }

    }

    static class BitmapBacking extends Backing {

        private Bitmap mBitmap = null;

        @Override
        public void allocate(FrameType frameType) {
            assertImageCompatible(frameType);
        }

        @Override
        public int readAccess() {
            return ACCESS_BITMAP;
        }

        @Override
        public int writeAccess() {
            return ACCESS_BITMAP;
        }

        @Override
        public void syncTo(Backing backing) {
            int access = backing.readAccess();
            if ((access & ACCESS_BITMAP) != 0) {
                mBitmap = (Bitmap) backing.lock(ACCESS_BITMAP);
            } else if ((access & ACCESS_BYTES) != 0) {
                createBitmap();
                ByteBuffer buffer = (ByteBuffer) backing.lock(ACCESS_BYTES);
                mBitmap.copyPixelsFromBuffer(buffer);
                buffer.rewind();
            } else if ((access & ACCESS_TEXTURE) != 0) {
                createBitmap();
                RenderTarget renderTarget = (RenderTarget) backing.lock(ACCESS_RENDERTARGET);
                mBitmap.copyPixelsFromBuffer(
                        renderTarget.getPixelData(mDimensions[0], mDimensions[1]));
            } else {
                throw new RuntimeException("Cannot sync bytebuffer backing!");
            }
            backing.unlock();
            mIsDirty = false;
        }

        @Override
        public Object lock(int accessType) {
            return mBitmap;
        }

        @Override
        public int getType() {
            return BACKING_BITMAP;
        }

        @Override
        public boolean shouldCache() {
            return false;
        }

        @Override
        public boolean requiresGpu() {
            return false;
        }

        @Override
        public void destroy() {
            // As we share the bitmap with other backings (such as object backings), we must not
            // recycle it here.
            mBitmap = null;
        }

        @Override
        public int getSize() {
            return 4 * mDimensions[0] * mDimensions[1];
        }

        @Override
        public void setData(Object data) {
            // We can assume that data will always be a Bitmap instance.
            mBitmap = (Bitmap) data;
        }

        private void createBitmap() {
            mBitmap = Bitmap.createBitmap(mDimensions[0], mDimensions[1], Bitmap.Config.ARGB_8888);
        }
    }

    static class TextureBacking extends Backing {

        private RenderTarget mRenderTarget = null;
        private TextureSource mTexture = null;

        @Override
        public void allocate(FrameType frameType) {
            assertImageCompatible(frameType);
            mTexture = TextureSource.newTexture();
        }

        @Override
        public int readAccess() {
            return ACCESS_TEXTURE;
        }

        @Override
        public int writeAccess() {
            return ACCESS_RENDERTARGET;
        }

        @Override
        public void syncTo(Backing backing) {
            int access = backing.readAccess();
            if ((access & ACCESS_BYTES) != 0) {
                ByteBuffer pixels = (ByteBuffer) backing.lock(ACCESS_BYTES);
                mTexture.allocateWithPixels(pixels, mDimensions[0], mDimensions[1]);
            } else if ((access & ACCESS_BITMAP) != 0) {
                Bitmap bitmap = (Bitmap) backing.lock(ACCESS_BITMAP);
                mTexture.allocateWithBitmapPixels(bitmap);
            } else if ((access & ACCESS_TEXTURE) != 0) {
                TextureSource texture = (TextureSource) backing.lock(ACCESS_TEXTURE);
                int w = mDimensions[0];
                int h = mDimensions[1];
                ImageShader.renderTextureToTarget(texture, getRenderTarget(), w, h);
            } else {
                throw new RuntimeException("Cannot sync bytebuffer backing!");
            }
            backing.unlock();
            mIsDirty = false;
        }

        @Override
        public Object lock(int accessType) {
            switch (accessType) {
                case ACCESS_TEXTURE:
                    return getTexture();

                case ACCESS_RENDERTARGET:
                    return getRenderTarget();

                default:
                    throw new RuntimeException("Illegal access to texture!");
            }
        }

        @Override
        public int getType() {
            return BACKING_TEXTURE;
        }

        @Override
        public boolean shouldCache() {
            return true;
        }

        @Override
        public boolean requiresGpu() {
            return true;
        }

        @Override
        public void destroy() {
            if (mRenderTarget != null) {
                mRenderTarget.release();
            }
            if (mTexture.isAllocated()) {
                mTexture.release();
            }
        }

        @Override
        public int getSize() {
            return 4 * mDimensions[0] * mDimensions[1];
        }

        private TextureSource getTexture() {
            if (!mTexture.isAllocated()) {
                mTexture.allocate(mDimensions[0], mDimensions[1]);
            }
            return mTexture;
        }

        private RenderTarget getRenderTarget() {
            if (mRenderTarget == null) {
                int w = mDimensions[0];
                int h = mDimensions[1];
                mRenderTarget = RenderTarget.currentTarget().forTexture(getTexture(), w, h);
            }
            return mRenderTarget;
        }

    }

    static class ByteBufferBacking extends Backing {

        ByteBuffer mBuffer = null;

        @Override
        public void allocate(FrameType frameType) {
            int size = frameType.getElementSize();
            for (int dim : mDimensions) {
                size *= dim;
            }
            mBuffer = ByteBuffer.allocateDirect(size);
        }

        @Override
        public int readAccess() {
            return ACCESS_BYTES;
        }

        @Override
        public int writeAccess() {
            return ACCESS_BYTES;
        }

        @Override
        public boolean requiresGpu() {
            return false;
        }

        @Override
        public void syncTo(Backing backing) {
            int access = backing.readAccess();
            if ((access & ACCESS_TEXTURE) != 0) {
                RenderTarget target = (RenderTarget) backing.lock(ACCESS_RENDERTARGET);
                GLToolbox.readTarget(target, mBuffer, mDimensions[0], mDimensions[1]);
            } else if ((access & ACCESS_BITMAP) != 0) {
                Bitmap bitmap = (Bitmap) backing.lock(ACCESS_BITMAP);
                bitmap.copyPixelsToBuffer(mBuffer);
                mBuffer.rewind();
            } else if ((access & ACCESS_BYTES) != 0) {
                ByteBuffer otherBuffer = (ByteBuffer) backing.lock(ACCESS_BYTES);
                mBuffer.put(otherBuffer);
                otherBuffer.rewind();
            } else {
                throw new RuntimeException("Cannot sync bytebuffer backing!");
            }
            backing.unlock();
            mBuffer.rewind();
            mIsDirty = false;
        }

        @Override
        public Object lock(int accessType) {
            return mBuffer.rewind();
        }

        @Override
        public void unlock() {
            mBuffer.rewind();
        }

        @Override
        public int getType() {
            return BACKING_BYTEBUFFER;
        }

        @Override
        public boolean shouldCache() {
            return true;
        }

        @Override
        public void destroy() {
            mBuffer = null;
        }

        @Override
        public int getSize() {
            return mBuffer.remaining();
        }

    }
}
