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

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
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
import android.util.Slog;

import com.android.internal.app.ResolverActivity;
import com.android.internal.os.BackgroundThread;

import dalvik.system.DexFile;
import dalvik.system.VMRuntime;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * <p>PinnerService pins important files for key processes in memory.</p>
 * <p>Files to pin are specified in the config_defaultPinnerServiceFiles
 * overlay.</p>
 * <p>Pin the default camera application if specified in config_pinnerCameraApp.</p>
 */
public final class PinnerService extends SystemService {
    private static final boolean DEBUG = false;
    private static final String TAG = "PinnerService";

    private final Context mContext;
    private final ArrayList<PinnedFile> mPinnedFiles = new ArrayList<PinnedFile>();
    private final ArrayList<PinnedFile> mPinnedCameraFiles = new ArrayList<PinnedFile>();
    private final boolean mShouldPinCamera;

    private BinderService mBinderService;

    private final long MAX_CAMERA_PIN_SIZE = 80 * (1 << 20); //80MB max

    private PinnerHandler mPinnerHandler = null;


    public PinnerService(Context context) {
        super(context);

        mContext = context;
        mShouldPinCamera = context.getResources().getBoolean(
                com.android.internal.R.bool.config_pinnerCameraApp);
        mPinnerHandler = new PinnerHandler(BackgroundThread.get().getLooper());
    }

    @Override
    public void onStart() {
        if (DEBUG) {
            Slog.i(TAG, "Starting PinnerService");
        }
        mBinderService = new BinderService();
        publishBinderService("pinner", mBinderService);

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
     * Handler for on start pinning message
     */
    private void handlePinOnStart() {
         // Files to pin come from the overlay and can be specified per-device config
        String[] filesToPin = mContext.getResources().getStringArray(
                com.android.internal.R.array.config_defaultPinnerServiceFiles);
        synchronized(this) {
            // Continue trying to pin remaining files even if there is a failure
            for (int i = 0; i < filesToPin.length; i++){
                PinnedFile pf = pinFile(filesToPin[i], 0, 0, 0);
                if (pf != null) {
                    mPinnedFiles.add(pf);
                    if (DEBUG) {
                        Slog.i(TAG, "Pinned file = " + pf.mFilename);
                    }
                } else {
                    Slog.e(TAG, "Failed to pin file = " + filesToPin[i]);
                }
            }
        }
    }

    /**
     * Handler for camera pinning message
     */
    private void handlePinCamera(int userHandle) {
        if (mShouldPinCamera) {
            synchronized(this) {
                boolean success = pinCamera(userHandle);
                if (!success) {
                    //this is not necessarily an error
                    if (DEBUG) {
                        Slog.v(TAG, "Failed to pin camera.");
                    }
                }
            }
        }
    }

    /**
     *  determine if the camera app is already pinned by comparing the
     *  intent resolution to the pinned files list
     */
    private boolean alreadyPinned(int userHandle) {
        ApplicationInfo cameraInfo = getCameraInfo(userHandle);
        if (cameraInfo == null ) {
            return false;
        }
        for (int i = 0; i < mPinnedCameraFiles.size(); i++) {
            if (mPinnedCameraFiles.get(i).mFilename.equals(cameraInfo.sourceDir)) {
                if (DEBUG) {
                  Slog.v(TAG, "Camera is already pinned");
                }
                return true;
            }
        }
        return false;
    }

    private void unpinCameraApp() {
        for (int i = 0; i < mPinnedCameraFiles.size(); i++) {
            unpinFile(mPinnedCameraFiles.get(i));
        }
        mPinnedCameraFiles.clear();
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

    private boolean pinCamera(int userHandle){
        //we may have already pinned a camera app.  If we've pinned this
        //camera app, we're done.  otherwise, unpin and pin the new app
        if (alreadyPinned(userHandle)){
            return true;
        }

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
        PinnedFile pf = pinFile(camAPK, 0, 0, MAX_CAMERA_PIN_SIZE);
        if (pf == null) {
            Slog.e(TAG, "Failed to pin " + camAPK);
            return false;
        }
        if (DEBUG) {
            Slog.i(TAG, "Pinned " + pf.mFilename);
        }
        mPinnedCameraFiles.add(pf);

        // determine the ABI from either ApplicationInfo or Build
        String arch = "arm";
        if (cameraInfo.primaryCpuAbi != null
            && VMRuntime.is64BitAbi(cameraInfo.primaryCpuAbi)) {
            arch = arch + "64";
        } else {
            if (VMRuntime.is64BitAbi(Build.SUPPORTED_ABIS[0])) {
                arch = arch + "64";
            }
        }

        // get the path to the odex or oat file
        String baseCodePath = cameraInfo.getBaseCodePath();
        String odex = null;
        try {
            odex = DexFile.getDexFileOutputPath(baseCodePath, arch);
        } catch (IOException ioe) {}
        if (odex == null) {
            return true;
        }

        //not pinning the oat/odex is not a fatal error
        pf = pinFile(odex, 0, 0, MAX_CAMERA_PIN_SIZE);
        if (pf != null) {
            mPinnedCameraFiles.add(pf);
            if (DEBUG) {
                Slog.i(TAG, "Pinned " + pf.mFilename);
            }
        }

        return true;
    }


    /** mlock length bytes of fileToPin in memory, starting at offset
     *  length == 0 means pin from offset to end of file
     *  maxSize == 0 means infinite
     */
    private static PinnedFile pinFile(String fileToPin, long offset, long length, long maxSize) {
        FileDescriptor fd = new FileDescriptor();
        try {
            fd = Os.open(fileToPin,
                    OsConstants.O_RDONLY | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW,
                    OsConstants.O_RDONLY);

            StructStat sb = Os.fstat(fd);

            if (offset + length > sb.st_size) {
                Os.close(fd);
                Slog.e(TAG, "Failed to pin file " + fileToPin +
                        ", request extends beyond end of file.  offset + length =  "
                        + (offset + length) + ", file length = " + sb.st_size);
                return null;
            }

            if (length == 0) {
                length = sb.st_size - offset;
            }

            if (maxSize > 0 && length > maxSize) {
                Slog.e(TAG, "Could not pin file " + fileToPin +
                        ", size = " + length + ", maxSize = " + maxSize);
                Os.close(fd);
                return null;
            }

            long address = Os.mmap(0, length, OsConstants.PROT_READ,
                    OsConstants.MAP_PRIVATE, fd, offset);
            Os.close(fd);

            Os.mlock(address, length);

            return new PinnedFile(address, length, fileToPin);
        } catch (ErrnoException e) {
            Slog.e(TAG, "Could not pin file " + fileToPin + " with error " + e.getMessage());
            if(fd.valid()) {
                try {
                    Os.close(fd);
                }
                catch (ErrnoException eClose) {
                    Slog.e(TAG, "Failed to close fd, error = " + eClose.getMessage());
                }
            }
            return null;
        }
    }

    private static boolean unpinFile(PinnedFile pf) {
        try {
            Os.munlock(pf.mAddress, pf.mLength);
        } catch (ErrnoException e) {
            Slog.e(TAG, "Failed to unpin file " + pf.mFilename + " with error " + e.getMessage());
            return false;
        }
        if (DEBUG) {
            Slog.i(TAG, "Unpinned file " + pf.mFilename );
        }
        return true;
    }

    private final class BinderService extends Binder {
        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DUMP, TAG);
            pw.println("Pinned Files:");
            synchronized(this) {
                for (int i = 0; i < mPinnedFiles.size(); i++) {
                    pw.println(mPinnedFiles.get(i).mFilename);
                }
                for (int i = 0; i < mPinnedCameraFiles.size(); i++) {
                    pw.println(mPinnedCameraFiles.get(i).mFilename);
                }
            }
        }
    }

    private static class PinnedFile {
        long mAddress;
        long mLength;
        String mFilename;

        PinnedFile(long address, long length, String filename) {
             mAddress = address;
             mLength = length;
             mFilename = filename;
        }
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
