/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.service.incremental;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Service;
import android.content.Intent;
import android.content.pm.IDataLoader;
import android.content.pm.IDataLoaderStatusListener;
import android.content.pm.InstallationFile;
import android.os.Bundle;
import android.os.IBinder;
import android.os.incremental.IncrementalDataLoaderParams;
import android.os.incremental.IncrementalDataLoaderParamsParcel;
import android.os.incremental.IncrementalFileSystemControlParcel;
import android.os.incremental.NamedParcelFileDescriptor;
import android.util.Slog;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collection;
import java.util.List;


/**
 * The base class for implementing data loader service to control data loaders. Expecting
 * Incremental Service to bind to a children class of this.
 *
 * @hide
 *
 * Hide for now, should be @SystemApi
 * TODO(b/136132412): update with latest API design
 */
public abstract class IncrementalDataLoaderService extends Service {
    private static final String TAG = "IncrementalDataLoaderService";
    private final DataLoaderBinderService mBinder = new DataLoaderBinderService();

    public static final int DATA_LOADER_READY =
            IDataLoaderStatusListener.DATA_LOADER_READY;
    public static final int DATA_LOADER_NOT_READY =
            IDataLoaderStatusListener.DATA_LOADER_NOT_READY;
    public static final int DATA_LOADER_RUNNING =
            IDataLoaderStatusListener.DATA_LOADER_RUNNING;
    public static final int DATA_LOADER_STOPPED =
            IDataLoaderStatusListener.DATA_LOADER_STOPPED;
    public static final int DATA_LOADER_SLOW_CONNECTION =
            IDataLoaderStatusListener.DATA_LOADER_SLOW_CONNECTION;
    public static final int DATA_LOADER_NO_CONNECTION =
            IDataLoaderStatusListener.DATA_LOADER_NO_CONNECTION;
    public static final int DATA_LOADER_CONNECTION_OK =
            IDataLoaderStatusListener.DATA_LOADER_CONNECTION_OK;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"DATA_LOADER_"}, value = {
            DATA_LOADER_READY,
            DATA_LOADER_NOT_READY,
            DATA_LOADER_RUNNING,
            DATA_LOADER_STOPPED,
            DATA_LOADER_SLOW_CONNECTION,
            DATA_LOADER_NO_CONNECTION,
            DATA_LOADER_CONNECTION_OK
    })
    public @interface DataLoaderStatus {
    }

    /**
     * Incremental FileSystem block size.
     **/
    public static final int BLOCK_SIZE = 4096;

    /**
     * Data compression types
     */
    public static final int COMPRESSION_NONE = 0;
    public static final int COMPRESSION_LZ4 = 1;

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({COMPRESSION_NONE, COMPRESSION_LZ4})
    public @interface CompressionType {
    }

    /**
     * Managed DataLoader interface. Each instance corresponds to a single Incremental File System
     * instance.
     */
    public abstract static class DataLoader {
        /**
         * A virtual constructor used to do simple initialization. Not ready to serve any data yet.
         * All heavy-lifting has to be done in onStart.
         *
         * @param params    Data loader configuration parameters.
         * @param connector IncFS API wrapper.
         * @param listener  Used for reporting internal state to IncrementalService.
         * @return True if initialization of a Data Loader was successful. False will be reported to
         * IncrementalService and can cause an unmount of an IFS instance.
         */
        public abstract boolean onCreate(@NonNull IncrementalDataLoaderParams params,
                @NonNull FileSystemConnector connector,
                @NonNull StatusListener listener);

        /**
         * Start the data loader. After this method returns data loader is considered to be ready to
         * receive callbacks from IFS, supply data via connector and send status updates via
         * callbacks.
         *
         * @return True if Data Loader was able to start. False will be reported to
         * IncrementalService and can cause an unmount of an IFS instance.
         */
        public abstract boolean onStart();

        /**
         * Stop the data loader. Use to stop any additional threads and free up resources. Data
         * loader is not longer responsible for supplying data. Start/Stop pair can be called
         * multiple times e.g. if IFS detects corruption and data needs to be re-loaded.
         */
        public abstract void onStop();

        /**
         * Virtual destructor. Use to cleanup all internal state. After this method returns, the
         * data loader can no longer use connector or callbacks. For any additional operations with
         * this instance of IFS a new DataLoader will be created using createDataLoader method.
         */
        public abstract void onDestroy();

        /**
         * IFS reports a pending read each time the page needs to be loaded, e.g. missing.
         *
         * @param pendingReads array of blocks to load.
         *
         * TODO(b/136132412): avoid using collections
         */
        public abstract void onPendingReads(
                @NonNull Collection<FileSystemConnector.PendingReadInfo> pendingReads);

        /**
         * IFS tracks all reads and reports them using onPageReads.
         *
         * @param reads array of blocks.
         *
         * TODO(b/136132412): avoid using collections
         */
        public abstract void onPageReads(@NonNull Collection<FileSystemConnector.ReadInfo> reads);

        /**
         * IFS informs data loader that a new file has been created.
         * <p>
         * This can be used to prepare the data loader before it starts loading data. For example,
         * the data loader can keep a list of newly created files, so that it knows what files to
         * download from the server.
         *
         * @param inode    The inode value of the new file.
         * @param metadata The metadata of the new file.
         */
        public abstract void onFileCreated(long inode, byte[] metadata);
    }

    /**
     * DataLoader factory method.
     *
     * @return An instance of a DataLoader.
     */
    public abstract @Nullable DataLoader onCreateDataLoader();

    /**
     * @hide
     */
    public final @NonNull IBinder onBind(@NonNull Intent intent) {
        return (IBinder) mBinder;
    }

    private class DataLoaderBinderService extends IDataLoader.Stub {
        private int mId;

        @Override
        public void create(int id, @NonNull Bundle options,
                @NonNull IDataLoaderStatusListener listener)
                    throws IllegalArgumentException, RuntimeException {
            mId = id;
            final IncrementalDataLoaderParamsParcel params =  options.getParcelable("params");
            if (params == null) {
                throw new IllegalArgumentException("Must specify Incremental data loader params");
            }
            final IncrementalFileSystemControlParcel control =
                    options.getParcelable("control");
            if (control == null) {
                throw new IllegalArgumentException("Must specify Incremental control parcel");
            }
            mStatusListener = listener;
            try {
                if (!nativeCreateDataLoader(id, control, params, listener)) {
                    Slog.e(TAG, "Failed to create native loader for " + mId);
                }
            } catch (Exception ex) {
                destroy();
                throw new RuntimeException(ex);
            } finally {
                // Closing FDs.
                if (control.cmd != null) {
                    try {
                        control.cmd.close();
                    } catch (IOException e) {
                        Slog.e(TAG, "Failed to close IncFs CMD file descriptor " + e);
                    }
                }
                if (control.log != null) {
                    try {
                        control.log.close();
                    } catch (IOException e) {
                        Slog.e(TAG, "Failed to close IncFs LOG file descriptor " + e);
                    }
                }
                NamedParcelFileDescriptor[] fds = params.dynamicArgs;
                for (NamedParcelFileDescriptor nfd : fds) {
                    try {
                        nfd.fd.close();
                    } catch (IOException e) {
                        Slog.e(TAG,
                                "Failed to close DynamicArgs parcel file descriptor " + e);
                    }
                }
            }
        }

        @Override
        public void start(List<InstallationFile> fileInfos) {
            if (!nativeStartDataLoader(mId)) {
                Slog.e(TAG, "Failed to start loader: loader not found for " + mId);
            }
        }

        @Override
        public void stop() {
            if (!nativeStopDataLoader(mId)) {
                Slog.w(TAG, "Failed to stop loader: loader not found for " + mId);
            }
        }

        @Override
        public void destroy() {
            if (!nativeDestroyDataLoader(mId)) {
                Slog.w(TAG, "Failed to destroy loader: loader not found for " + mId);
            }
        }

        @Override
        // TODO(b/136132412): remove this
        public void onFileCreated(long inode, byte[] metadata) {
            if (!nativeOnFileCreated(mId, inode, metadata)) {
                Slog.w(TAG, "Failed to handle onFileCreated for storage:" + mId
                        + " inode:" + inode);
            }
        }
    }

    /**
     * IncFs API wrapper for writing pages and getting page missing info. Non-hidden methods are
     * expected to be called by the IncrementalDataLoaderService implemented by developers.
     *
     * @hide
     *
     * TODO(b/136132412) Should be @SystemApi
     */
    public static final class FileSystemConnector {
        /**
         * Defines a block address. A block is the unit of data chunk that IncFs operates with.
         *
         * @hide
         */
        public static class BlockAddress {
            /**
             * Linux inode uniquely identifies file within a single IFS instance.
             */
            private final long mFileIno;
            /**
             * Index of a 4K block within a file.
             */
            private final int mBlockIndex;

            public BlockAddress(long fileIno, int blockIndex) {
                this.mFileIno = fileIno;
                this.mBlockIndex = blockIndex;
            }

            public long getFileIno() {
                return mFileIno;
            }

            public int getBlockIndex() {
                return mBlockIndex;
            }
        }

        /**
         * A block is the unit of data chunk that IncFs operates with.
         *
         * @hide
         */
        public static class Block extends BlockAddress {
            /**
             * Data content of the block.
             */
            private final @NonNull byte[] mDataBytes;

            public Block(long fileIno, int blockIndex, @NonNull byte[] dataBytes) {
                super(fileIno, blockIndex);
                this.mDataBytes = dataBytes;
            }
        }

        /**
         * Defines a page/block inside a file.
         */
        public static class DataBlock extends Block {
            /**
             * Compression type of the data block.
             */
            private final @CompressionType int mCompressionType;

            public DataBlock(long fileIno, int blockIndex, @NonNull byte[] dataBytes,
                    @CompressionType int compressionType) {
                super(fileIno, blockIndex, dataBytes);
                this.mCompressionType = compressionType;
            }
        }

        /**
         * Defines a hash block for a certain file. A hash block index is the index in an array of
         * hashes which is the 1-d representation of the hash tree. One DataBlock might be
         * associated with multiple HashBlocks.
         */
        public static class HashBlock extends Block {
            public HashBlock(long fileIno, int blockIndex, @NonNull byte[] dataBytes) {
                super(fileIno, blockIndex, dataBytes);
            }
        }

        /**
         * Information about a page that is pending to be read.
         */
        public static class PendingReadInfo extends BlockAddress {
            PendingReadInfo(long fileIno, int blockIndex) {
                super(fileIno, blockIndex);
            }
        }

        /**
         * Information about a page that is read.
         */
        public static class ReadInfo extends BlockAddress {
            /**
             * A monotonically increasing read timestamp.
             */
            private final long mTimePoint;
            /**
             * Number of blocks read starting from blockIndex.
             */
            private final int mBlockCount;

            ReadInfo(long timePoint, long fileIno, int firstBlockIndex, int blockCount) {
                super(fileIno, firstBlockIndex);
                this.mTimePoint = timePoint;
                this.mBlockCount = blockCount;
            }

            public long getTimePoint() {
                return mTimePoint;
            }

            public int getBlockCount() {
                return mBlockCount;
            }
        }

        /**
         * Defines the dynamic information about an IncFs file.
         */
        public static class FileInfo {
            /**
             * BitSet to show if any block is available at each block index.
             */
            private final @NonNull
            byte[] mBlockBitmap;

            /**
             * @hide
             */
            public FileInfo(@NonNull byte[] blockBitmap) {
                this.mBlockBitmap = blockBitmap;
            }
        }

        /**
         * Creates a wrapper for a native instance.
         */
        FileSystemConnector(long nativeInstance) {
            mNativeInstance = nativeInstance;
        }

        /**
         * Checks whether a range in a file if loaded.
         *
         * @param node  inode of the file.
         * @param start The starting offset of the range.
         * @param end   The ending offset of the range.
         * @return True if the file is fully loaded.
         */
        public boolean isFileRangeLoaded(long node, long start, long end) {
            return nativeIsFileRangeLoadedNode(mNativeInstance, node, start, end);
        }

        /**
         * Gets the metadata of a file.
         *
         * @param node inode of the file.
         * @return The metadata object.
         */
        @NonNull
        public byte[] getFileMetadata(long node) throws IOException {
            final byte[] metadata = nativeGetFileMetadataNode(mNativeInstance, node);
            if (metadata == null || metadata.length == 0) {
                throw new IOException(
                        "IncrementalFileSystem failed to obtain metadata for node: " + node);
            }
            return metadata;
        }

        /**
         * Gets the dynamic information of a file, such as page bitmaps. Can be used to get missing
         * page indices by the FileSystemConnector.
         *
         * @param node inode of the file.
         * @return Dynamic file info.
         */
        @NonNull
        public FileInfo getDynamicFileInfo(long node) throws IOException {
            final byte[] blockBitmap = nativeGetFileInfoNode(mNativeInstance, node);
            if (blockBitmap == null || blockBitmap.length == 0) {
                throw new IOException(
                        "IncrementalFileSystem failed to obtain dynamic file info for node: "
                                + node);
            }
            return new FileInfo(blockBitmap);
        }

        /**
         * Writes a page's data and/or hashes.
         *
         * @param dataBlocks the DataBlock objects that contain data block index and data bytes.
         * @param hashBlocks the HashBlock objects that contain hash indices and hash bytes.
         *
         * TODO(b/136132412): change API to avoid dynamic allocation of data block objects
         */
        public void writeMissingData(@NonNull DataBlock[] dataBlocks,
                @Nullable HashBlock[] hashBlocks) throws IOException {
            if (!nativeWriteMissingData(mNativeInstance, dataBlocks, hashBlocks)) {
                throw new IOException("IncrementalFileSystem failed to write missing data.");
            }
        }

        /**
         * Writes the signer block of a file. Expecting the connector to call this when it got
         * signing data from data loader.
         *
         * @param node       the file to be written to.
         * @param signerData the raw signer data byte array.
         */
        public void writeSignerData(long node, @NonNull byte[] signerData)
                throws IOException {
            if (!nativeWriteSignerDataNode(mNativeInstance, node, signerData)) {
                throw new IOException(
                        "IncrementalFileSystem failed to write signer data of node " + node);
            }
        }

        private final long mNativeInstance;
    }

    /**
     * Wrapper for native reporting DataLoader statuses.
     *
     * @hide
     *
     * TODO(b/136132412) Should be @SystemApi
     */
    public static final class StatusListener {
        /**
         * Creates a wrapper for a native instance.
         *
         * @hide
         */
        StatusListener(long nativeInstance) {
            mNativeInstance = nativeInstance;
        }

        /**
         * Report the status of DataLoader. Used for system-wide notifications e.g., disabling
         * applications which rely on this data loader to function properly.
         *
         * @param status status to report.
         * @return True if status was reported successfully.
         */
        public boolean onStatusChanged(@DataLoaderStatus int status) {
            return nativeReportStatus(mNativeInstance, status);
        }

        private final long mNativeInstance;
    }

    private IDataLoaderStatusListener mStatusListener = null;

    /* Native methods */
    private native boolean nativeCreateDataLoader(int storageId,
            @NonNull IncrementalFileSystemControlParcel control,
            @NonNull IncrementalDataLoaderParamsParcel params,
            IDataLoaderStatusListener listener);

    private native boolean nativeStartDataLoader(int storageId);

    private native boolean nativeStopDataLoader(int storageId);

    private native boolean nativeDestroyDataLoader(int storageId);

    private static native boolean nativeOnFileCreated(int storageId,
            long inode, byte[] metadata);

    private static native boolean nativeIsFileRangeLoadedNode(
            long nativeInstance, long node, long start, long end);

    private static native boolean nativeWriteMissingData(
            long nativeInstance, FileSystemConnector.DataBlock[] dataBlocks,
            FileSystemConnector.HashBlock[] hashBlocks);

    private static native boolean nativeWriteSignerDataNode(
            long nativeInstance, long node, byte[] signerData);

    private static native byte[] nativeGetFileMetadataNode(
            long nativeInstance, long node);

    private static native byte[] nativeGetFileInfoNode(
            long nativeInstance, long node);

    private static native boolean nativeReportStatus(long nativeInstance, int status);
}
