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
 * limitations under the License
 */

package android.telephony.mbms;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.telephony.MbmsDownloadManager;
import android.util.Log;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * @hide
 */
public class MbmsDownloadReceiver extends BroadcastReceiver {
    public static final String DOWNLOAD_TOKEN_SUFFIX = ".download_token";
    public static final String MBMS_FILE_PROVIDER_META_DATA_KEY = "mbms-file-provider-authority";

    /**
     * TODO: @SystemApi all these result codes
     * Indicates that the requested operation completed without error.
     */
    public static final int RESULT_OK = 0;

    /**
     * Indicates that the intent sent had an invalid action. This will be the result if
     * {@link Intent#getAction()} returns anything other than
     * {@link MbmsDownloadManager#ACTION_DOWNLOAD_RESULT_INTERNAL},
     * {@link MbmsDownloadManager#ACTION_FILE_DESCRIPTOR_REQUEST}, or
     * {@link MbmsDownloadManager#ACTION_CLEANUP}.
     * This is a fatal result code and no result extras should be expected.
     */
    public static final int RESULT_INVALID_ACTION = 1;

    /**
     * Indicates that the intent was missing some required extras.
     * This is a fatal result code and no result extras should be expected.
     */
    public static final int RESULT_MALFORMED_INTENT = 2;

    /**
     * Indicates that the supplied value for {@link MbmsDownloadManager#EXTRA_TEMP_FILE_ROOT}
     * does not match what the app has stored.
     * This is a fatal result code and no result extras should be expected.
     */
    public static final int RESULT_BAD_TEMP_FILE_ROOT = 3;

    /**
     * Indicates that the manager was unable to move the completed download to its final location.
     * This is a fatal result code and no result extras should be expected.
     */
    public static final int RESULT_DOWNLOAD_FINALIZATION_ERROR = 4;

    /**
     * Indicates that the manager was unable to generate one or more of the requested file
     * descriptors.
     * This is a non-fatal result code -- some file descriptors may still be generated, but there
     * is no guarantee that they will be the same number as requested.
     */
    public static final int RESULT_TEMP_FILE_GENERATION_ERROR = 5;

    private static final String LOG_TAG = "MbmsDownloadReceiver";
    private static final String TEMP_FILE_SUFFIX = ".embms.temp";
    private static final int MAX_TEMP_FILE_RETRIES = 5;


    private String mFileProviderAuthorityCache = null;
    private String mMiddlewarePackageNameCache = null;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!verifyIntentContents(context, intent)) {
            setResultCode(RESULT_MALFORMED_INTENT);
            return;
        }
        if (!Objects.equals(intent.getStringExtra(MbmsDownloadManager.EXTRA_TEMP_FILE_ROOT),
                MbmsTempFileProvider.getEmbmsTempFileDir(context).getPath())) {
            setResultCode(RESULT_BAD_TEMP_FILE_ROOT);
            return;
        }

        if (MbmsDownloadManager.ACTION_DOWNLOAD_RESULT_INTERNAL.equals(intent.getAction())) {
            moveDownloadedFile(context, intent);
            cleanupPostMove(context, intent);
        } else if (MbmsDownloadManager.ACTION_FILE_DESCRIPTOR_REQUEST.equals(intent.getAction())) {
            generateTempFiles(context, intent);
        } else if (MbmsDownloadManager.ACTION_CLEANUP.equals(intent.getAction())) {
            cleanupTempFiles(context, intent);
        } else {
            setResultCode(RESULT_INVALID_ACTION);
        }
    }

    private boolean verifyIntentContents(Context context, Intent intent) {
        if (MbmsDownloadManager.ACTION_DOWNLOAD_RESULT_INTERNAL.equals(intent.getAction())) {
            if (!intent.hasExtra(MbmsDownloadManager.EXTRA_RESULT)) {
                Log.w(LOG_TAG, "Download result did not include a result code. Ignoring.");
                return false;
            }
            if (!intent.hasExtra(MbmsDownloadManager.EXTRA_REQUEST)) {
                Log.w(LOG_TAG, "Download result did not include the associated request. Ignoring.");
                return false;
            }
            if (!intent.hasExtra(MbmsDownloadManager.EXTRA_TEMP_FILE_ROOT)) {
                Log.w(LOG_TAG, "Download result did not include the temp file root. Ignoring.");
                return false;
            }
            if (!intent.hasExtra(MbmsDownloadManager.EXTRA_FILE_INFO)) {
                Log.w(LOG_TAG, "Download result did not include the associated file info. " +
                        "Ignoring.");
                return false;
            }
            if (!intent.hasExtra(MbmsDownloadManager.EXTRA_FINAL_URI)) {
                Log.w(LOG_TAG, "Download result did not include the path to the final " +
                        "temp file. Ignoring.");
                return false;
            }
            DownloadRequest request = intent.getParcelableExtra(MbmsDownloadManager.EXTRA_REQUEST);
            String expectedTokenFileName = request.getHash() + DOWNLOAD_TOKEN_SUFFIX;
            File expectedTokenFile = new File(
                    MbmsUtils.getEmbmsTempFileDirForService(context, request.getFileServiceId()),
                    expectedTokenFileName);
            if (!expectedTokenFile.exists()) {
                Log.w(LOG_TAG, "Supplied download request does not match a token that we have. " +
                        "Expected " + expectedTokenFile);
                return false;
            }
        } else if (MbmsDownloadManager.ACTION_FILE_DESCRIPTOR_REQUEST.equals(intent.getAction())) {
            if (!intent.hasExtra(MbmsDownloadManager.EXTRA_SERVICE_INFO)) {
                Log.w(LOG_TAG, "Temp file request did not include the associated service info." +
                        " Ignoring.");
                return false;
            }
            if (!intent.hasExtra(MbmsDownloadManager.EXTRA_TEMP_FILE_ROOT)) {
                Log.w(LOG_TAG, "Download result did not include the temp file root. Ignoring.");
                return false;
            }
        } else if (MbmsDownloadManager.ACTION_CLEANUP.equals(intent.getAction())) {
            if (!intent.hasExtra(MbmsDownloadManager.EXTRA_SERVICE_INFO)) {
                Log.w(LOG_TAG, "Cleanup request did not include the associated service info." +
                        " Ignoring.");
                return false;
            }
            if (!intent.hasExtra(MbmsDownloadManager.EXTRA_TEMP_FILE_ROOT)) {
                Log.w(LOG_TAG, "Cleanup request did not include the temp file root. Ignoring.");
                return false;
            }
            if (!intent.hasExtra(MbmsDownloadManager.EXTRA_TEMP_FILES_IN_USE)) {
                Log.w(LOG_TAG, "Cleanup request did not include the list of temp files in use. " +
                        "Ignoring.");
                return false;
            }
        }
        return true;
    }

    private void moveDownloadedFile(Context context, Intent intent) {
        DownloadRequest request = intent.getParcelableExtra(MbmsDownloadManager.EXTRA_REQUEST);
        Intent intentForApp = request.getIntentForApp();

        int result = intent.getIntExtra(MbmsDownloadManager.EXTRA_RESULT,
                MbmsDownloadManager.RESULT_CANCELLED);
        intentForApp.putExtra(MbmsDownloadManager.EXTRA_RESULT, result);

        if (result != MbmsDownloadManager.RESULT_SUCCESSFUL) {
            Log.i(LOG_TAG, "Download request indicated a failed download. Aborting.");
            context.sendBroadcast(intentForApp);
            return;
        }

        Uri destinationUri = request.getDestinationUri();
        Uri finalTempFile = intent.getParcelableExtra(MbmsDownloadManager.EXTRA_FINAL_URI);
        if (!verifyTempFilePath(context, request.getFileServiceId(), finalTempFile)) {
            Log.w(LOG_TAG, "Download result specified an invalid temp file " + finalTempFile);
            setResultCode(RESULT_DOWNLOAD_FINALIZATION_ERROR);
            return;
        }

        FileInfo completedFileInfo =
                (FileInfo) intent.getParcelableExtra(MbmsDownloadManager.EXTRA_FILE_INFO);
        String relativePath = calculateDestinationFileRelativePath(request, completedFileInfo);

        Uri finalFileLocation = moveTempFile(finalTempFile, destinationUri, relativePath);
        if (finalFileLocation == null) {
            Log.w(LOG_TAG, "Failed to move temp file to final destination");
            setResultCode(RESULT_DOWNLOAD_FINALIZATION_ERROR);
            return;
        }
        intentForApp.putExtra(MbmsDownloadManager.EXTRA_COMPLETED_FILE_URI, finalFileLocation);
        intentForApp.putExtra(MbmsDownloadManager.EXTRA_FILE_INFO, completedFileInfo);

        context.sendBroadcast(intentForApp);
        setResultCode(RESULT_OK);
    }

    private void cleanupPostMove(Context context, Intent intent) {
        DownloadRequest request = intent.getParcelableExtra(MbmsDownloadManager.EXTRA_REQUEST);
        if (request == null) {
            Log.w(LOG_TAG, "Intent does not include a DownloadRequest. Ignoring.");
            return;
        }

        List<Uri> tempFiles = intent.getParcelableExtra(MbmsDownloadManager.EXTRA_TEMP_LIST);
        if (tempFiles == null) {
            return;
        }

        for (Uri tempFileUri : tempFiles) {
            if (verifyTempFilePath(context, request.getFileServiceId(), tempFileUri)) {
                File tempFile = new File(tempFileUri.getSchemeSpecificPart());
                tempFile.delete();
            }
        }
    }

    private void generateTempFiles(Context context, Intent intent) {
        FileServiceInfo serviceInfo =
                intent.getParcelableExtra(MbmsDownloadManager.EXTRA_SERVICE_INFO);
        if (serviceInfo == null) {
            Log.w(LOG_TAG, "Temp file request did not include the associated service info. " +
                    "Ignoring.");
            setResultCode(RESULT_MALFORMED_INTENT);
            return;
        }
        int fdCount = intent.getIntExtra(MbmsDownloadManager.EXTRA_FD_COUNT, 0);
        List<Uri> pausedList = intent.getParcelableExtra(MbmsDownloadManager.EXTRA_PAUSED_LIST);

        if (fdCount == 0 && (pausedList == null || pausedList.size() == 0)) {
            Log.i(LOG_TAG, "No temp files actually requested. Ending.");
            setResultCode(RESULT_OK);
            setResultExtras(Bundle.EMPTY);
            return;
        }

        ArrayList<UriPathPair> freshTempFiles =
                generateFreshTempFiles(context, serviceInfo, fdCount);
        ArrayList<UriPathPair> pausedFiles =
                generateUrisForPausedFiles(context, serviceInfo, pausedList);

        Bundle result = new Bundle();
        result.putParcelableArrayList(MbmsDownloadManager.EXTRA_FREE_URI_LIST, freshTempFiles);
        result.putParcelableArrayList(MbmsDownloadManager.EXTRA_PAUSED_URI_LIST, pausedFiles);
        setResultCode(RESULT_OK);
        setResultExtras(result);
    }

    private ArrayList<UriPathPair> generateFreshTempFiles(Context context,
            FileServiceInfo serviceInfo,
            int freshFdCount) {
        File tempFileDir = MbmsUtils.getEmbmsTempFileDirForService(context,
                serviceInfo.getServiceId());
        if (!tempFileDir.exists()) {
            tempFileDir.mkdirs();
        }

        // Name the files with the template "N-UUID", where N is the request ID and UUID is a
        // random uuid.
        ArrayList<UriPathPair> result = new ArrayList<>(freshFdCount);
        for (int i = 0; i < freshFdCount; i++) {
            File tempFile = generateSingleTempFile(tempFileDir);
            if (tempFile == null) {
                setResultCode(RESULT_TEMP_FILE_GENERATION_ERROR);
                Log.w(LOG_TAG, "Failed to generate a temp file. Moving on.");
                continue;
            }
            Uri fileUri = Uri.fromFile(tempFile);
            Uri contentUri = MbmsTempFileProvider.getUriForFile(
                    context, getFileProviderAuthorityCached(context), tempFile);
            context.grantUriPermission(getMiddlewarePackageCached(context), contentUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            result.add(new UriPathPair(fileUri, contentUri));
        }

        return result;
    }

    private static File generateSingleTempFile(File tempFileDir) {
        int numTries = 0;
        while (numTries < MAX_TEMP_FILE_RETRIES) {
            numTries++;
            String fileName =  UUID.randomUUID() + TEMP_FILE_SUFFIX;
            File tempFile = new File(tempFileDir, fileName);
            try {
                if (tempFile.createNewFile()) {
                    return tempFile.getCanonicalFile();
                }
            } catch (IOException e) {
                continue;
            }
        }
        return null;
    }

    private ArrayList<UriPathPair> generateUrisForPausedFiles(Context context,
            FileServiceInfo serviceInfo, List<Uri> pausedFiles) {
        if (pausedFiles == null) {
            return new ArrayList<>(0);
        }
        ArrayList<UriPathPair> result = new ArrayList<>(pausedFiles.size());

        for (Uri fileUri : pausedFiles) {
            if (!verifyTempFilePath(context, serviceInfo.getServiceId(), fileUri)) {
                Log.w(LOG_TAG, "Supplied file " + fileUri + " is not a valid temp file to resume");
                setResultCode(RESULT_TEMP_FILE_GENERATION_ERROR);
                continue;
            }
            File tempFile = new File(fileUri.getSchemeSpecificPart());
            if (!tempFile.exists()) {
                Log.w(LOG_TAG, "Supplied file " + fileUri + " does not exist.");
                setResultCode(RESULT_TEMP_FILE_GENERATION_ERROR);
                continue;
            }
            Uri contentUri = MbmsTempFileProvider.getUriForFile(
                    context, getFileProviderAuthorityCached(context), tempFile);
            context.grantUriPermission(getMiddlewarePackageCached(context), contentUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

            result.add(new UriPathPair(fileUri, contentUri));
        }
        return result;
    }

    private void cleanupTempFiles(Context context, Intent intent) {
        FileServiceInfo serviceInfo =
                intent.getParcelableExtra(MbmsDownloadManager.EXTRA_SERVICE_INFO);
        File tempFileDir = MbmsUtils.getEmbmsTempFileDirForService(context,
                serviceInfo.getServiceId());
        List<Uri> filesInUse =
                intent.getParcelableArrayListExtra(MbmsDownloadManager.EXTRA_TEMP_FILES_IN_USE);
        File[] filesToDelete = tempFileDir.listFiles(new FileFilter() {
            @Override
            public boolean accept(File file) {
                File canonicalFile;
                try {
                    canonicalFile = file.getCanonicalFile();
                } catch (IOException e) {
                    Log.w(LOG_TAG, "Got IOException canonicalizing " + file + ", not deleting.");
                    return false;
                }
                // Reject all files that don't match what we think a temp file should look like
                // e.g. download tokens
                if (!canonicalFile.getName().endsWith(TEMP_FILE_SUFFIX)) {
                    return false;
                }
                // If any of the files in use match the uri, return false to reject it from the
                // list to delete.
                Uri fileInUseUri = Uri.fromFile(canonicalFile);
                return !filesInUse.contains(fileInUseUri);
            }
        });
        for (File fileToDelete : filesToDelete) {
            fileToDelete.delete();
        }
    }

    private static String calculateDestinationFileRelativePath(DownloadRequest request,
            FileInfo info) {
        List<String> filePathComponents = info.getUri().getPathSegments();
        List<String> requestPathComponents = request.getSourceUri().getPathSegments();
        Iterator<String> filePathIter = filePathComponents.iterator();
        Iterator<String> requestPathIter = requestPathComponents.iterator();

        StringBuilder pathBuilder = new StringBuilder();
        // Iterate through the segments of the carrier's URI to the file, along with the segments
        // of the source URI specified in the download request. The relative path is calculated
        // as the tail of the file's URI that does not match the path segments in the source URI.
        while (filePathIter.hasNext()) {
            String currFilePathComponent = filePathIter.next();
            if (requestPathIter.hasNext()) {
                String requestFilePathComponent = requestPathIter.next();
                if (requestFilePathComponent.equals(currFilePathComponent)) {
                    continue;
                }
            }
            pathBuilder.append(currFilePathComponent);
            pathBuilder.append('/');
        }
        // remove the trailing slash
        if (pathBuilder.length() > 0) {
            pathBuilder.deleteCharAt(pathBuilder.length() - 1);
        }
        return pathBuilder.toString();
    }

    /*
     * Moves a tempfile located at fromPath to a new location at toPath. If
     * toPath is a directory, the destination file will be located at  relativePath
     * underneath toPath.
     */
    private static Uri moveTempFile(Uri fromPath, Uri toPath, String relativePath) {
        if (!ContentResolver.SCHEME_FILE.equals(fromPath.getScheme())) {
            Log.w(LOG_TAG, "Moving source uri " + fromPath+ " does not have a file scheme");
            return null;
        }
        if (!ContentResolver.SCHEME_FILE.equals(toPath.getScheme())) {
            Log.w(LOG_TAG, "Moving destination uri " + toPath + " does not have a file scheme");
            return null;
        }

        File fromFile = new File(fromPath.getSchemeSpecificPart());
        File toFile = new File(toPath.getSchemeSpecificPart());
        if (toFile.isDirectory()) {
            toFile = new File(toFile, relativePath);
        }
        toFile.getParentFile().mkdirs();

        if (fromFile.renameTo(toFile)) {
            return Uri.fromFile(toFile);
        } else if (manualMove(fromFile, toFile)) {
            return Uri.fromFile(toFile);
        }
        return null;
    }

    private static boolean verifyTempFilePath(Context context, String serviceId,
            Uri filePath) {
        if (!ContentResolver.SCHEME_FILE.equals(filePath.getScheme())) {
            Log.w(LOG_TAG, "Uri " + filePath + " does not have a file scheme");
            return false;
        }

        String path = filePath.getSchemeSpecificPart();
        File tempFile = new File(path);
        if (!tempFile.exists()) {
            Log.w(LOG_TAG, "File at " + path + " does not exist.");
            return false;
        }

        if (!MbmsUtils.isContainedIn(
                MbmsUtils.getEmbmsTempFileDirForService(context, serviceId), tempFile)) {
            return false;
        }

        return true;
    }

    private String getFileProviderAuthorityCached(Context context) {
        if (mFileProviderAuthorityCache != null) {
            return mFileProviderAuthorityCache;
        }

        mFileProviderAuthorityCache = getFileProviderAuthority(context);
        return mFileProviderAuthorityCache;
    }

    private static String getFileProviderAuthority(Context context) {
        ApplicationInfo appInfo;
        try {
            appInfo = context.getPackageManager()
                    .getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException("Package manager couldn't find " + context.getPackageName());
        }
        String authority = appInfo.metaData.getString(MBMS_FILE_PROVIDER_META_DATA_KEY);
        if (authority == null) {
            throw new RuntimeException("Must declare the file provider authority as meta data");
        }
        return authority;
    }

    private String getMiddlewarePackageCached(Context context) {
        if (mMiddlewarePackageNameCache == null) {
            mMiddlewarePackageNameCache = MbmsUtils.getMiddlewareServiceInfo(context,
                    MbmsDownloadManager.MBMS_DOWNLOAD_SERVICE_ACTION).packageName;
        }
        return mMiddlewarePackageNameCache;
    }

    private static boolean manualMove(File src, File dst) {
        InputStream in = null;
        OutputStream out = null;
        try {
            if (!dst.exists()) {
                dst.createNewFile();
            }
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buffer = new byte[2048];
            int len;
            do {
                len = in.read(buffer);
                out.write(buffer, 0, len);
            } while (len > 0);
        } catch (IOException e) {
            Log.w(LOG_TAG, "Manual file move failed due to exception "  + e);
            if (dst.exists()) {
                dst.delete();
            }
            return false;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
                if (out != null) {
                    out.close();
                }
            } catch (IOException e) {
                Log.w(LOG_TAG, "Error closing streams: " + e);
            }
        }
        return true;
    }
}
