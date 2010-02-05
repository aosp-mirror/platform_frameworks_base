package com.android.defcontainer;

import com.android.internal.app.IMediaContainerService;

import android.content.Intent;
import android.net.Uri;
import android.os.Debug;
import android.os.IBinder;
import android.os.storage.IMountService;
import android.os.storage.StorageResultCode;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.app.Service;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import android.os.FileUtils;


/*
 * This service copies a downloaded apk to a file passed in as
 * a ParcelFileDescriptor or to a newly created container specified
 * by parameters. The DownloadManager gives access to this process
 * based on its uid. This process also needs the ACCESS_DOWNLOAD_MANAGER
 * permission to access apks downloaded via the download manager.
 */
public class DefaultContainerService extends Service {
    private static final String TAG = "DefContainer";
    private static final boolean localLOGV = false;

    private IMediaContainerService.Stub mBinder = new IMediaContainerService.Stub() {
        /*
         * Creates a new container and copies resource there.
         * @param paackageURI the uri of resource to be copied. Can be either
         * a content uri or a file uri
         * @param containerId the id of the secure container that should
         * be used for creating a secure container into which the resource
         * will be copied.
         * @param key Refers to key used for encrypting the secure container
         * @param resFileName Name of the target resource file(relative to newly
         * created secure container)
         * @return Returns the new cache path where the resource has been copied into
         *
         */
        public String copyResourceToContainer(final Uri packageURI,
                final String containerId,
                final String key, final String resFileName) {
            if (packageURI == null || containerId == null) {
                return null;
            }
            return copyResourceInner(packageURI, containerId, key, resFileName);
        }

        /*
         * Copy specified resource to output stream
         * @param packageURI the uri of resource to be copied. Should be a
         * file uri
         * @param outStream Remote file descriptor to be used for copying
         * @return Returns true if copy succeded or false otherwise.
         */
        public boolean copyResource(final Uri packageURI,
                ParcelFileDescriptor outStream) {
            if (packageURI == null ||  outStream == null) {
                return false;
            }
            ParcelFileDescriptor.AutoCloseOutputStream
            autoOut = new ParcelFileDescriptor.AutoCloseOutputStream(outStream);
            return copyFile(packageURI, autoOut);
        }
    };

    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private IMountService getMountService() {
        return IMountService.Stub.asInterface(ServiceManager.getService("mount"));
    }

    private String copyResourceInner(Uri packageURI, String newCacheId, String key, String resFileName) {
        // Create new container at newCachePath
        String codePath = packageURI.getPath();
        String newCachePath = null;
        final int CREATE_FAILED = 1;
        final int COPY_FAILED = 2;
        final int FINALIZE_FAILED = 3;
        final int PASS = 4;
        int errCode = CREATE_FAILED;
        // Create new container
        if ((newCachePath = createSdDir(packageURI, newCacheId, key)) != null) {
            if (localLOGV) Log.i(TAG, "Created container for " + newCacheId
                    + " at path : " + newCachePath);
            File resFile = new File(newCachePath, resFileName);
            errCode = COPY_FAILED;
            // Copy file from codePath
            if (FileUtils.copyFile(new File(codePath), resFile)) {
                if (localLOGV) Log.i(TAG, "Copied " + codePath + " to " + resFile);
                errCode = FINALIZE_FAILED;
                if (finalizeSdDir(newCacheId)) {
                    errCode = PASS;
                }
            }
        }
        // Print error based on errCode
        String errMsg = "";
        switch (errCode) {
            case CREATE_FAILED:
                errMsg = "CREATE_FAILED";
                break;
            case COPY_FAILED:
                errMsg = "COPY_FAILED";
                if (localLOGV) Log.i(TAG, "Destroying " + newCacheId +
                        " at path " + newCachePath + " after " + errMsg);
                destroySdDir(newCacheId);
                break;
            case FINALIZE_FAILED:
                errMsg = "FINALIZE_FAILED";
                if (localLOGV) Log.i(TAG, "Destroying " + newCacheId +
                        " at path " + newCachePath + " after " + errMsg);
                destroySdDir(newCacheId);
                break;
            default:
                errMsg = "PASS";
            if (localLOGV) Log.i(TAG, "Unmounting " + newCacheId +
                    " at path " + newCachePath + " after " + errMsg);
                unMountSdDir(newCacheId);
                break;
        }
        if (errCode != PASS) {
            return null;
        }
        return newCachePath;
    }

    private String createSdDir(final Uri packageURI,
            String containerId, String sdEncKey) {
        File tmpPackageFile = new File(packageURI.getPath());
        // Create mount point via MountService
        IMountService mountService = getMountService();
        long len = tmpPackageFile.length();
        int mbLen = (int) (len/(1024*1024));
        if ((len - (mbLen * 1024 * 1024)) > 0) {
            mbLen++;
        }
        if (localLOGV) Log.i(TAG, "mbLen=" + mbLen);
        String cachePath = null;
        int ownerUid = Process.myUid();
        try {
            int rc = mountService.createSecureContainer(
                    containerId, mbLen, "vfat", sdEncKey, ownerUid);

            if (rc != StorageResultCode.OperationSucceeded) {
                Log.e(TAG, String.format("Container creation failed (%d)", rc));

                // XXX: This destroy should not be necessary
                rc = mountService.destroySecureContainer(containerId);
                if (rc != StorageResultCode.OperationSucceeded) {
                    Log.e(TAG, String.format("Container creation-cleanup failed (%d)", rc));
                    return null;
                }

                // XXX: Does this ever actually succeed?
                rc = mountService.createSecureContainer(
                        containerId, mbLen, "vfat", sdEncKey, ownerUid);
                if (rc != StorageResultCode.OperationSucceeded) {
                    Log.e(TAG, String.format("Container creation retry failed (%d)", rc));
                }
            }

            cachePath = mountService.getSecureContainerPath(containerId);
            if (localLOGV) Log.i(TAG, "Trying to create secure container for  "
                    + containerId + ", cachePath =" + cachePath);
            return cachePath;
        } catch(RemoteException e) {
            Log.e(TAG, "MountService not running?");
            return null;
        }
    }

    private boolean destroySdDir(String containerId) {
        try {
            // We need to destroy right away
            getMountService().destroySecureContainer(containerId);
            return true;
        } catch (IllegalStateException e) {
            Log.i(TAG, "Failed to destroy container : " + containerId);
        } catch(RemoteException e) {
            Log.e(TAG, "MountService not running?");
        }
        return false;
    }

    private boolean finalizeSdDir(String containerId){
        try {
            getMountService().finalizeSecureContainer(containerId);
            return true;
        } catch (IllegalStateException e) {
            Log.i(TAG, "Failed to finalize container for pkg : " + containerId);
        } catch(RemoteException e) {
            Log.e(TAG, "MountService not running?");
        }
        return false;
    }

    private boolean unMountSdDir(String containerId) {
        try {
            getMountService().unmountSecureContainer(containerId);
            return true;
        } catch (IllegalStateException e) {
            Log.e(TAG, "Failed to unmount id:  " + containerId + " with exception " + e);
        } catch(RemoteException e) {
            Log.e(TAG, "MountService not running?");
        }
        return false;
    }

    private String mountSdDir(String containerId, String key) {
        try {
            int rc = getMountService().mountSecureContainer(containerId, key, Process.myUid());
            if (rc == StorageResultCode.OperationSucceeded) {
                return getMountService().getSecureContainerPath(containerId);
            } else {
                Log.e(TAG, String.format("Failed to mount id %s with rc %d ", containerId, rc));
            }
        } catch(RemoteException e) {
            Log.e(TAG, "MountService not running?");
        }
        return null;
    }

    public static boolean copyToFile(InputStream inputStream, FileOutputStream out) {
        try {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) >= 0) {
                out.write(buffer, 0, bytesRead);
            }
            return true;
        } catch (IOException e) {
            Log.i(TAG, "Exception : " + e + " when copying file");
            return false;
        }
    }

    public static boolean copyToFile(File srcFile, FileOutputStream out) {
        InputStream inputStream = null;
        try {
            inputStream = new FileInputStream(srcFile);
            return copyToFile(inputStream, out);
        } catch (IOException e) {
            return false;
        } finally {
            try { if (inputStream != null) inputStream.close(); } catch (IOException e) {}
        }
    }

    private  boolean copyFile(Uri pPackageURI, FileOutputStream outStream) {
        if (pPackageURI.getScheme().equals("file")) {
            final File srcPackageFile = new File(pPackageURI.getPath());
            // We copy the source package file to a temp file and then rename it to the
            // destination file in order to eliminate a window where the package directory
            // scanner notices the new package file but it's not completely copied yet.
            if (!copyToFile(srcPackageFile, outStream)) {
                Log.e(TAG, "Couldn't copy file: " + srcPackageFile);
                return false;
            }
        } else if (pPackageURI.getScheme().equals("content")) {
            ParcelFileDescriptor fd = null;
            try {
                fd = getContentResolver().openFileDescriptor(pPackageURI, "r");
            } catch (FileNotFoundException e) {
                Log.e(TAG, "Couldn't open file descriptor from download service. Failed with exception " + e);
                return false;
            }
            if (fd == null) {
                Log.e(TAG, "Couldn't open file descriptor from download service (null).");
                return false;
            } else {
                if (localLOGV) {
                    Log.v(TAG, "Opened file descriptor from download service.");
                }
                ParcelFileDescriptor.AutoCloseInputStream
                dlStream = new ParcelFileDescriptor.AutoCloseInputStream(fd);
                // We copy the source package file to a temp file and then rename it to the
                // destination file in order to eliminate a window where the package directory
                // scanner notices the new package file but it's not completely copied yet.
                if (!copyToFile(dlStream, outStream)) {
                    Log.e(TAG, "Couldn't copy " + pPackageURI + " to temp file.");
                    return false;
                }
            }
        } else {
            Log.e(TAG, "Package URI is not 'file:' or 'content:' - " + pPackageURI);
            return false;
        }
        return true;
    }
}
