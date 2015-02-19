/*
 * Copyright (C) 2014 The Android Open Source Project
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

package libcore.io;

import com.android.layoutlib.bridge.impl.DelegateManager;
import com.android.layoutlib.bridge.libcore.io.BridgeBufferIterator;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.system.ErrnoException;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel.MapMode;
import java.util.HashMap;
import java.util.Map;

/**
 * Delegate used to provide alternate implementation of select methods of {@link MemoryMappedFile}.
 */
public class MemoryMappedFile_Delegate {

    private static final DelegateManager<MemoryMappedFile_Delegate> sManager = new
            DelegateManager<MemoryMappedFile_Delegate>(MemoryMappedFile_Delegate.class);

    private static final Map<MemoryMappedFile, Long> sMemoryMappedFileMap =
            new HashMap<MemoryMappedFile, Long>();

    private final MappedByteBuffer mMappedByteBuffer;
    private final long mSize;

    /** Path on the target device where the data file is available. */
    private static final String TARGET_PATH = System.getenv("ANDROID_ROOT") + "/usr/share/zoneinfo";
    /** Path on the host (inside the SDK) where the data files are available. */
    private static File sRootPath;

    @LayoutlibDelegate
    static MemoryMappedFile mmapRO(String path) throws ErrnoException {
        if (!path.startsWith(TARGET_PATH)) {
            throw new ErrnoException("Custom timezone data files are not supported.", 1);
        }
        if (sRootPath == null) {
            throw new ErrnoException("Bridge has not been initialized properly.", 1);
        }
        path = path.substring(TARGET_PATH.length());
        try {
            File f = new File(sRootPath, path);
            if (!f.exists()) {
                throw new ErrnoException("File not found: " + f.getPath(), 1);
            }
            RandomAccessFile file = new RandomAccessFile(f, "r");
            try {
                long size = file.length();
                MemoryMappedFile_Delegate newDelegate = new MemoryMappedFile_Delegate(file);
                long filePointer = file.getFilePointer();
                MemoryMappedFile mmFile = new MemoryMappedFile(filePointer, size);
                long delegateIndex = sManager.addNewDelegate(newDelegate);
                sMemoryMappedFileMap.put(mmFile, delegateIndex);
                return mmFile;
            } finally {
                file.close();
            }
        } catch (IOException e) {
            throw new ErrnoException("mmapRO", 1, e);
        }
    }

    @LayoutlibDelegate
    static void close(MemoryMappedFile thisFile) throws ErrnoException {
        Long index = sMemoryMappedFileMap.get(thisFile);
        if (index != null) {
            sMemoryMappedFileMap.remove(thisFile);
            sManager.removeJavaReferenceFor(index);
        }
    }

    @LayoutlibDelegate
    static BufferIterator bigEndianIterator(MemoryMappedFile file) {
        MemoryMappedFile_Delegate delegate = getDelegate(file);
        return new BridgeBufferIterator(delegate.mSize, delegate.mMappedByteBuffer.duplicate());
    }

    // TODO: implement littleEndianIterator()

    public MemoryMappedFile_Delegate(RandomAccessFile file) throws IOException {
        mSize = file.length();
        // It's weird that map() takes size as long, but returns MappedByteBuffer which uses an int
        // to store the marker to the position.
        mMappedByteBuffer = file.getChannel().map(MapMode.READ_ONLY, 0, mSize);
        assert mMappedByteBuffer.order() == ByteOrder.BIG_ENDIAN;
    }

    public static void setDataDir(File path) {
        sRootPath = path;
    }

    private static MemoryMappedFile_Delegate getDelegate(MemoryMappedFile file) {
        Long index = sMemoryMappedFileMap.get(file);
        return index == null ? null : sManager.getDelegate(index);
    }

}
