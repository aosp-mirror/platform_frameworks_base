/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server;

import android.annotation.Nullable;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.app.ResolverActivity;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.DumpUtils;

import dalvik.system.DexFile;
import dalvik.system.VMRuntime;

import java.io.FileDescriptor;
import java.io.Closeable;
import java.io.InputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.EOFException;
import java.io.PrintWriter;
import java.util.ArrayList;

import java.util.zip.ZipFile;
import java.util.zip.ZipException;
import java.util.zip.ZipEntry;

/**
 * <p>PinnerService pins important files for key processes in memory.</p>
 * <p>Files to pin are specified in the config_defaultPinnerServiceFiles
 * overlay.</p>
 * <p>Pin the default camera application if specified in config_pinnerCameraApp.</p>
 */
public final class PinnerService extends SystemService {
    private static final boolean DEBUG = false;
    private static final String TAG = "PinnerService";
    private static final int MAX_CAMERA_PIN_SIZE = 80 * (1 << 20); //80MB max
    private static final String PIN_META_FILENAME = "pinlist.meta";
    private static final int PAGE_SIZE = (int) Os.sysconf(OsConstants._SC_PAGESIZE);

    private final Context mContext;
    private final boolean mShouldPinCamera;

    /* These lists protected by PinnerService monitor lock */
    private final ArrayList<PinnedFile> mPinnedFiles = new ArrayList<PinnedFile>();
    private final ArrayList<PinnedFile> mPinnedCameraFiles = new ArrayList<PinnedFile>();

    private BinderService mBinderService;
    private PinnerHandler mPinnerHandler = null;

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          // If this user's camera app has been updated, update pinned files accordingly.
          if (intent.getAction() == Intent.ACTION_PACKAGE_REPLACED) {
                Uri packageUri = intent.getData();
                String packageName = packageUri.getSchemeSpecificPart();
                ArraySet<String> updatedPackages = new ArraySet<>();
                updatedPackages.add(packageName);
                update(updatedPackages);
            }
        }
    };

    public PinnerService(Context context) {
        super(context);

        mContext = context;
        mShouldPinCamera = context.getResources().getBoolean(
                com.android.internal.R.bool.config_pinnerCameraApp);
        mPinnerHandler = new PinnerHandler(BackgroundThread.get().getLooper());

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        mContext.registerReceiver(mBroadcastReceiver, filter);
    }

    @Override
    public void onStart() {
        if (DEBUG) {
            Slog.i(TAG, "Starting PinnerService");
        }
        mBinderService = new BinderService();
        publishBinderService("pinner", mBinderService);
        publishLocalService(PinnerService.class, this);

        mPinnerHandler.obtainMessage(PinnerHandler.PIN_ONSTART_MSG).sendToTarget();
        mPinnerHandler.obtainMessage(PinnerHandler.PIN_CAMERA_MSG, UserHandle.USER_SYSTEM, 0)
                .sendToTarget();
    }

    /**
     * Pin camera on user switch.
     * If more than one user is using the device
     * each user may set a different preference for the camera app.
     * Make sure that user's preference is pinned into memory.
     */
    @Override
    public void onSwitchUser(int userHandle) {
        mPinnerHandler.obtainMessage(PinnerHandler.PIN_CAMERA_MSG, userHandle, 0).sendToTarget();
    }

    /**
     * Update the currently pinned files.
     * Specifically, this only updates camera pinning.
     * The other files pinned in onStart will not need to be updated.
     */
    public void update(ArraySet<String> updatedPackages) {
        ApplicationInfo cameraInfo = getCameraInfo(UserHandle.USER_SYSTEM);
        if (cameraInfo != null && updatedPackages.contains(cameraInfo.packageName)) {
            Slog.i(TAG, "Updating pinned files.");
            mPinnerHandler.obtainMessage(PinnerHandler.PIN_CAMERA_MSG, UserHandle.USER_SYSTEM, 0)
                    .sendToTarget();
        }
    }

    /**
     * Handler for on start pinning message
     */
    private void handlePinOnStart() {
         // Files to pin come from the overlay and can be specified per-device config
        String[] filesToPin = mContext.getResources().getStringArray(
                com.android.internal.R.array.config_defaultPinnerServiceFiles);
        // Continue trying to pin each file even if we fail to pin some of them
        for (String fileToPin : filesToPin) {
            PinnedFile pf = pinFile(fileToPin,
                                    Integer.MAX_VALUE,
                                    /*attemptPinIntrospection=*/false);
            if (pf == null) {
                Slog.e(TAG, "Failed to pin file = " + fileToPin);
                continue;
            }

            synchronized (this) {
                mPinnedFiles.add(pf);
            }
        }
    }

    /**
     * Handler for camera pinning message
     */
    private void handlePinCamera(int userHandle) {
        if (!mShouldPinCamera) return;
        if (!pinCamera(userHandle)) {
            if (DEBUG) {
                Slog.v(TAG, "Failed to pin camera.");
            }
        }
    }

    private void unpinCameraApp() {
        ArrayList<PinnedFile> pinnedCameraFiles;
        synchronized (this) {
            pinnedCameraFiles = new ArrayList<>(mPinnedCameraFiles);
            mPinnedCameraFiles.clear();
        }
        for (PinnedFile pinnedFile : pinnedCameraFiles) {
            pinnedFile.close();
        }
    }

    private boolean isResolverActivity(ActivityInfo info) {
        return ResolverActivity.class.getName().equals(info.name);
    }

    private ApplicationInfo getCameraInfo(int userHandle) {
        //  find the camera via an intent
        //  use INTENT_ACTION_STILL_IMAGE_CAMERA instead of _SECURE.  On a
        //  device without a fbe enabled, the _SECURE intent will never get set.
        Intent cameraIntent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
        PackageManager pm = mContext.getPackageManager();
        ResolveInfo cameraResolveInfo = pm.resolveActivityAsUser(cameraIntent,
                PackageManager.MATCH_DEFAULT_ONLY | PackageManager.MATCH_DIRECT_BOOT_AWARE
                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                userHandle);
        if (cameraResolveInfo == null ) {
            //this is not necessarily an error
            if (DEBUG) {
              Slog.v(TAG, "Unable to resolve camera intent");
            }
            return null;
        }

        if (isResolverActivity(cameraResolveInfo.activityInfo))
        {
            if (DEBUG) {
              Slog.v(TAG, "cameraIntent returned resolverActivity");
            }
            return null;
        }

        return cameraResolveInfo.activityInfo.applicationInfo;
    }

    /**
     * If the camera app is already pinned, unpin and repin it.
     */
    private boolean pinCamera(int userHandle){
        ApplicationInfo cameraInfo = getCameraInfo(userHandle);
        if (cameraInfo == null) {
            return false;
        }

        //unpin after checking that the camera intent has resolved
        //this prevents us from thrashing when switching users with
        //FBE enabled, because the intent won't resolve until the unlock
        unpinCameraApp();

        //pin APK
        String camAPK = cameraInfo.sourceDir;
        PinnedFile pf = pinFile(camAPK,
                                MAX_CAMERA_PIN_SIZE,
                                /*attemptPinIntrospection=*/true);
        if (pf == null) {
            Slog.e(TAG, "Failed to pin " + camAPK);
            return false;
        }
        if (DEBUG) {
            Slog.i(TAG, "Pinned " + pf.fileName);
        }
        synchronized (this) {
            mPinnedCameraFiles.add(pf);
        }

        // determine the ABI from either ApplicationInfo or Build
        String arch = "arm";
        if (cameraInfo.primaryCpuAbi != null) {
            if (VMRuntime.is64BitAbi(cameraInfo.primaryCpuAbi)) {
                arch = arch + "64";
            }
        } else {
            if (VMRuntime.is64BitAbi(Build.SUPPORTED_ABIS[0])) {
                arch = arch + "64";
            }
        }

        // get the path to the odex or oat file
        String baseCodePath = cameraInfo.getBaseCodePath();
        String[] files = null;
        try {
            files = DexFile.getDexFileOutputPaths(baseCodePath, arch);
        } catch (IOException ioe) {}
        if (files == null) {
            return true;
        }

        //not pinning the oat/odex is not a fatal error
        for (String file : files) {
            pf = pinFile(file, MAX_CAMERA_PIN_SIZE, /*attemptPinIntrospection=*/false);
            if (pf != null) {
                synchronized (this) {
                    mPinnedCameraFiles.add(pf);
                }
                if (DEBUG) {
                    Slog.i(TAG, "Pinned " + pf.fileName);
                }
            }
        }

        return true;
    }


    /** mlock length bytes of fileToPin in memory
     *
     * If attemptPinIntrospection is true, then treat the file to pin as a zip file and
     * look for a "pinlist.meta" file in the archive root directory. The structure of this
     * file is a PINLIST_META as described below:
     *
     * <pre>
     *   PINLIST_META: PIN_RANGE*
     *   PIN_RANGE: PIN_START PIN_LENGTH
     *   PIN_START: big endian i32: offset in bytes of pin region from file start
     *   PIN_LENGTH: big endian i32: length of pin region in bytes
     * </pre>
     *
     * (We use big endian because that's what DataInputStream is hardcoded to use.)
     *
     * If attemptPinIntrospection is false, then we use a single implicit PIN_RANGE of (0,
     * maxBytesToPin); that is, we attempt to pin the first maxBytesToPin bytes of the file.
     *
     * After we open a file, we march through the list of pin ranges and attempt to pin
     * each one, stopping after we've pinned maxBytesToPin bytes. (We may truncate the last
     * pinned range to fit.)  In this way, by choosing to emit certain PIN_RANGE pairs
     * before others, file generators can express pins in priority order, making most
     * effective use of the pinned-page quota.
     *
     * N.B. Each PIN_RANGE is clamped to the actual bounds of the file; all inputs have a
     * meaningful interpretation. Also, a range locking a single byte of a page locks the
     * whole page. Any truncated PIN_RANGE at EOF is ignored. Overlapping pinned entries
     * are legal, but each pin of a byte counts toward the pin quota regardless of whether
     * that byte has already been pinned, so the generator of PINLIST_META ought to ensure
     * that ranges are non-overlapping.
     *
     * @param fileToPin Path to file to pin
     * @param maxBytesToPin Maximum number of bytes to pin
     * @param attemptPinIntrospection If true, try to open file as a
     *   zip in order to extract the
     * @return Pinned memory resource owner thing or null on error
     */
    private static PinnedFile pinFile(String fileToPin,
                                      int maxBytesToPin,
                                      boolean attemptPinIntrospection) {
        ZipFile fileAsZip = null;
        InputStream pinRangeStream = null;
        try {
            if (attemptPinIntrospection) {
                fileAsZip = maybeOpenZip(fileToPin);
            }

            if (fileAsZip != null) {
                pinRangeStream = maybeOpenPinMetaInZip(fileAsZip, fileToPin);
            }

            Slog.d(TAG, "pinRangeStream: " + pinRangeStream);

            PinRangeSource pinRangeSource = (pinRangeStream != null)
                ? new PinRangeSourceStream(pinRangeStream)
                : new PinRangeSourceStatic(0, Integer.MAX_VALUE /* will be clipped */);
            return pinFileRanges(fileToPin, maxBytesToPin, pinRangeSource);
        } finally {
            safeClose(pinRangeStream);
            safeClose(fileAsZip);  // Also closes any streams we've opened
        }
    }

    /**
     * Attempt to open a file as a zip file. On any sort of corruption, log, swallow the
     * error, and return null.
     */
    private static ZipFile maybeOpenZip(String fileName) {
        ZipFile zip = null;
        try {
            zip = new ZipFile(fileName);
        } catch (IOException ex) {
            Slog.w(TAG,
                   String.format(
                       "could not open \"%s\" as zip: pinning as blob",
                                 fileName),
                   ex);
        }
        return zip;
    }

    /**
     * Open a pin metadata file in the zip if one is present.
     *
     * @param zipFile Zip file to search
     * @return Open input stream or null on any error
     */
    private static InputStream maybeOpenPinMetaInZip(ZipFile zipFile, String fileName) {
        ZipEntry pinMetaEntry = zipFile.getEntry(PIN_META_FILENAME);
        InputStream pinMetaStream = null;
        if (pinMetaEntry != null) {
            try {
                pinMetaStream = zipFile.getInputStream(pinMetaEntry);
            } catch (IOException ex) {
                Slog.w(TAG,
                       String.format("error reading pin metadata \"%s\": pinning as blob",
                                     fileName),
                       ex);
            }
        }
        return pinMetaStream;
    }

    private static abstract class PinRangeSource {
        /** Retrive a range to pin.
         *
         * @param outPinRange Receives the pin region
         * @return True if we filled in outPinRange or false if we're out of pin entries
         */
        abstract boolean read(PinRange outPinRange);
    }

    private static final class PinRangeSourceStatic extends PinRangeSource {
        private final int mPinStart;
        private final int mPinLength;
        private boolean mDone = false;

        PinRangeSourceStatic(int pinStart, int pinLength) {
            mPinStart = pinStart;
            mPinLength = pinLength;
        }

        @Override
        boolean read(PinRange outPinRange) {
            outPinRange.start = mPinStart;
            outPinRange.length = mPinLength;
            boolean done = mDone;
            mDone = true;
            return !done;
        }
    }

    private static final class PinRangeSourceStream extends PinRangeSource {
        private final DataInputStream mStream;
        private boolean mDone = false;

        PinRangeSourceStream(InputStream stream) {
            mStream = new DataInputStream(stream);
        }

        @Override
        boolean read(PinRange outPinRange) {
            if (!mDone) {
                try {
                    outPinRange.start = mStream.readInt();
                    outPinRange.length = mStream.readInt();
                } catch (IOException ex) {
                    mDone = true;
                }
            }
            return !mDone;
        }
    }

    /**
     * Helper for pinFile.
     *
     * @param fileToPin Name of file to pin
     * @param maxBytesToPin Maximum number of bytes to pin
     * @param pinRangeSource Read PIN_RANGE entries from this stream to tell us what bytes
     *   to pin.
     * @return PinnedFile or null on error
     */
    private static PinnedFile pinFileRanges(
        String fileToPin,
        int maxBytesToPin,
        PinRangeSource pinRangeSource)
    {
        FileDescriptor fd = new FileDescriptor();
        long address = -1;
        int mapSize = 0;

        try {
            int openFlags = (OsConstants.O_RDONLY |
                             OsConstants.O_CLOEXEC |
                             OsConstants.O_NOFOLLOW);
            fd = Os.open(fileToPin, openFlags, 0);
            mapSize = (int) Math.min(Os.fstat(fd).st_size, Integer.MAX_VALUE);
            address = Os.mmap(0, mapSize,
                              OsConstants.PROT_READ,
                              OsConstants.MAP_SHARED,
                              fd, /*offset=*/0);

            PinRange pinRange = new PinRange();
            int bytesPinned = 0;

            // We pin at page granularity, so make sure the limit is page-aligned
            if (maxBytesToPin % PAGE_SIZE != 0) {
                maxBytesToPin -= maxBytesToPin % PAGE_SIZE;
            }

            while (bytesPinned < maxBytesToPin && pinRangeSource.read(pinRange)) {
                int pinStart = pinRange.start;
                int pinLength = pinRange.length;
                pinStart = clamp(0, pinStart, mapSize);
                pinLength = clamp(0, pinLength, mapSize - pinStart);
                pinLength = Math.min(maxBytesToPin - bytesPinned, pinLength);

                // mlock doesn't require the region to be page-aligned, but we snap the
                // lock region to page boundaries anyway so that we don't under-count
                // locking a single byte of a page as a charge of one byte even though the
                // OS will retain the whole page. Thanks to this adjustment, we slightly
                // over-count the pin charge of back-to-back pins touching the same page,
                // but better that than undercounting. Besides: nothing stops pin metafile
                // creators from making the actual regions page-aligned.
                pinLength += pinStart % PAGE_SIZE;
                pinStart -= pinStart % PAGE_SIZE;
                if (pinLength % PAGE_SIZE != 0) {
                    pinLength += PAGE_SIZE - pinLength % PAGE_SIZE;
                }
                pinLength = clamp(0, pinLength, maxBytesToPin - bytesPinned);

                if (pinLength > 0) {
                    if (DEBUG) {
                        Slog.d(TAG,
                               String.format(
                                   "pinning at %s %s bytes of %s",
                                   pinStart, pinLength, fileToPin));
                    }
                    Os.mlock(address + pinStart, pinLength);
                }
                bytesPinned += pinLength;
            }

            PinnedFile pinnedFile = new PinnedFile(address, mapSize, fileToPin, bytesPinned);
            address = -1;  // Ownership transferred
            return pinnedFile;
        } catch (ErrnoException ex) {
            Slog.e(TAG, "Could not pin file " + fileToPin, ex);
            return null;
        } finally {
            safeClose(fd);
            if (address >= 0) {
                safeMunmap(address, mapSize);
            }
        }
    }

    private static int clamp(int min, int value, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static void safeMunmap(long address, long mapSize) {
        try {
            Os.munmap(address, mapSize);
        } catch (ErrnoException ex) {
            Slog.w(TAG, "ignoring error in unmap", ex);
        }
    }

    /**
     * Close FD, swallowing irrelevant errors.
     */
    private static void safeClose(@Nullable FileDescriptor fd) {
        if (fd != null && fd.valid()) {
            try {
                Os.close(fd);
            } catch (ErrnoException ex) {
                // Swallow the exception: non-EBADF errors in close(2)
                // indicate deferred paging write errors, which we
                // don't care about here. The underlying file
                // descriptor is always closed.
                if (ex.errno == OsConstants.EBADF) {
                    throw new AssertionError(ex);
                }
            }
        }
    }

    /**
     * Close closeable thing, swallowing errors.
     */
    private static void safeClose(@Nullable Closeable thing) {
        if (thing != null) {
            try {
                thing.close();
            } catch (IOException ex) {
                Slog.w(TAG, "ignoring error closing resource: " + thing, ex);
            }
        }
    }

    private synchronized ArrayList<PinnedFile> snapshotPinnedFiles() {
        int nrPinnedFiles = mPinnedFiles.size() + mPinnedCameraFiles.size();
        ArrayList<PinnedFile> pinnedFiles = new ArrayList<>(nrPinnedFiles);
        pinnedFiles.addAll(mPinnedFiles);
        pinnedFiles.addAll(mPinnedCameraFiles);
        return pinnedFiles;
    }

    private final class BinderService extends Binder {
        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
            long totalSize = 0;
            for (PinnedFile pinnedFile : snapshotPinnedFiles()) {
                pw.format("%s %s\n", pinnedFile.fileName, pinnedFile.bytesPinned);
                totalSize += pinnedFile.bytesPinned;
            }
            pw.format("Total size: %s\n", totalSize);
        }
    }

    private static final class PinnedFile implements AutoCloseable {
        private long mAddress;
        final int mapSize;
        final String fileName;
        final int bytesPinned;

        PinnedFile(long address, int mapSize, String fileName, int bytesPinned) {
             mAddress = address;
             this.mapSize = mapSize;
             this.fileName = fileName;
             this.bytesPinned = bytesPinned;
        }

        @Override
        public void close() {
            if (mAddress >= 0) {
                safeMunmap(mAddress, mapSize);
                mAddress = -1;
            }
        }

        @Override
        public void finalize() {
            close();
        }
    }

    final static class PinRange {
        int start;
        int length;
    }

    final class PinnerHandler extends Handler {
        static final int PIN_CAMERA_MSG  = 4000;
        static final int PIN_ONSTART_MSG = 4001;

        public PinnerHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {

                case PIN_CAMERA_MSG:
                {
                    handlePinCamera(msg.arg1);
                }
                break;

                case PIN_ONSTART_MSG:
                {
                    handlePinOnStart();
                }
                break;

                default:
                    super.handleMessage(msg);
            }
        }
    }

}
