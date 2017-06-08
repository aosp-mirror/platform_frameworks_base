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
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

/**
 * @hide
 */
public class MbmsDownloadReceiver extends BroadcastReceiver {
    private static final String LOG_TAG = "MbmsDownloadReceiver";
    private static final String TEMP_FILE_SUFFIX = ".embms.temp";
    private static final int MAX_TEMP_FILE_RETRIES = 5;

    public static final String MBMS_FILE_PROVIDER_META_DATA_KEY = "mbms-file-provider-authority";

    private String mFileProviderAuthorityCache = null;
    private String mMiddlewarePackageNameCache = null;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!verifyIntentContents(intent)) {
            setResultCode(1 /* TODO: define error constants */);
            return;
        }

        if (MbmsDownloadManager.ACTION_DOWNLOAD_RESULT_INTERNAL.equals(intent.getAction())) {
            moveDownloadedFile(context, intent);
            cleanupPostMove(context, intent);
        } else if (MbmsDownloadManager.ACTION_FILE_DESCRIPTOR_REQUEST.equals(intent.getAction())) {
            generateTempFiles(context, intent);
        }
        // TODO: Add handling for ACTION_CLEANUP
    }

    private boolean verifyIntentContents(Intent intent) {
        if (MbmsDownloadManager.ACTION_DOWNLOAD_RESULT_INTERNAL.equals(intent.getAction())) {
            if (!intent.hasExtra(MbmsDownloadManager.EXTRA_RESULT)) {
                Log.w(LOG_TAG, "Download result did not include a result code. Ignoring.");
                return false;
            }
            if (!intent.hasExtra(MbmsDownloadManager.EXTRA_REQUEST)) {
                Log.w(LOG_TAG, "Download result did not include the associated request. Ignoring.");
                return false;
            }
            if (!intent.hasExtra(MbmsDownloadManager.EXTRA_INFO)) {
                Log.w(LOG_TAG, "Download result did not include the associated file info. " +
                        "Ignoring.");
                return false;
            }
            if (!intent.hasExtra(MbmsDownloadManager.EXTRA_FINAL_URI)) {
                Log.w(LOG_TAG, "Download result did not include the path to the final " +
                        "temp file. Ignoring.");
                return false;
            }
            return true;
        } else if (MbmsDownloadManager.ACTION_FILE_DESCRIPTOR_REQUEST.equals(intent.getAction())) {
            if (!intent.hasExtra(MbmsDownloadManager.EXTRA_REQUEST)) {
                Log.w(LOG_TAG, "Temp file request not include the associated request. Ignoring.");
                return false;
            }
            return true;
        }

        Log.w(LOG_TAG, "Received intent with unknown action: " + intent.getAction());
        return false;
    }

    private void moveDownloadedFile(Context context, Intent intent) {
        DownloadRequest request = intent.getParcelableExtra(MbmsDownloadManager.EXTRA_REQUEST);
        // TODO: check request against token
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
        if (!verifyTempFilePath(context, request, finalTempFile)) {
            Log.w(LOG_TAG, "Download result specified an invalid temp file " + finalTempFile);
            setResultCode(1);
            return;
        }

        String relativePath = calculateDestinationFileRelativePath(request,
                (FileInfo) intent.getParcelableExtra(MbmsDownloadManager.EXTRA_INFO));

        if (!moveTempFile(finalTempFile, destinationUri, relativePath)) {
            Log.w(LOG_TAG, "Failed to move temp file to final destination");
            setResultCode(1);
        }

        context.sendBroadcast(intentForApp);
        setResultCode(0);
    }

    private void cleanupPostMove(Context context, Intent intent) {
        // TODO: account for in-use temp files
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
            if (verifyTempFilePath(context, request, tempFileUri)) {
                File tempFile = new File(tempFileUri.getSchemeSpecificPart());
                tempFile.delete();
            }
        }
    }

    private void generateTempFiles(Context context, Intent intent) {
        // TODO: update pursuant to final decision on temp file locations
        DownloadRequest request = intent.getParcelableExtra(MbmsDownloadManager.EXTRA_REQUEST);
        if (request == null) {
            Log.w(LOG_TAG, "Temp file request did not include the associated request. Ignoring.");
            setResultCode(1 /* TODO: define error constants */);
            return;
        }
        int fdCount = intent.getIntExtra(MbmsDownloadManager.EXTRA_FD_COUNT, 0);
        List<Uri> pausedList = intent.getParcelableExtra(MbmsDownloadManager.EXTRA_PAUSED_LIST);

        if (fdCount == 0 && (pausedList == null || pausedList.size() == 0)) {
            Log.i(LOG_TAG, "No temp files actually requested. Ending.");
            setResultCode(0);
            setResultExtras(Bundle.EMPTY);
            return;
        }

        ArrayList<UriPathPair> freshTempFiles = generateFreshTempFiles(context, request, fdCount);
        ArrayList<UriPathPair> pausedFiles =
                generateUrisForPausedFiles(context, request, pausedList);

        Bundle result = new Bundle();
        result.putParcelableArrayList(MbmsDownloadManager.EXTRA_FREE_URI_LIST, freshTempFiles);
        result.putParcelableArrayList(MbmsDownloadManager.EXTRA_PAUSED_URI_LIST, pausedFiles);
        setResultExtras(result);
    }

    private ArrayList<UriPathPair> generateFreshTempFiles(Context context, DownloadRequest request,
            int freshFdCount) {
        File tempFileDir = getEmbmsTempFileDirForRequest(context, request);
        if (!tempFileDir.exists()) {
            tempFileDir.mkdirs();
        }

        // Name the files with the template "N-UUID", where N is the request ID and UUID is a
        // random uuid.
        ArrayList<UriPathPair> result = new ArrayList<>(freshFdCount);
        for (int i = 0; i < freshFdCount; i++) {
            File tempFile = generateSingleTempFile(tempFileDir);
            if (tempFile == null) {
                setResultCode(2 /* TODO: define error constants */);
                Log.w(LOG_TAG, "Failed to generate a temp file. Moving on.");
                continue;
            }
            Uri fileUri = Uri.fromParts(ContentResolver.SCHEME_FILE, tempFile.getPath(), null);
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
            DownloadRequest request, List<Uri> pausedFiles) {
        if (pausedFiles == null) {
            return new ArrayList<>(0);
        }
        ArrayList<UriPathPair> result = new ArrayList<>(pausedFiles.size());

        for (Uri fileUri : pausedFiles) {
            if (!verifyTempFilePath(context, request, fileUri)) {
                Log.w(LOG_TAG, "Supplied file " + fileUri + " is not a valid temp file to resume");
                setResultCode(2 /* TODO: define error codes */);
                continue;
            }
            File tempFile = new File(fileUri.getSchemeSpecificPart());
            if (!tempFile.exists()) {
                Log.w(LOG_TAG, "Supplied file " + fileUri + " does not exist.");
                setResultCode(2 /* TODO: define error codes */);
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

    private static String calculateDestinationFileRelativePath(DownloadRequest request,
            FileInfo info) {
        // TODO: determine whether this is actually the path determination scheme we want to use
        List<String> filePathComponents = info.uri.getPathSegments();
        List<String> requestPathComponents = request.getSourceUri().getPathSegments();
        Iterator<String> filePathIter = filePathComponents.iterator();
        Iterator<String> requestPathIter = requestPathComponents.iterator();

        LinkedList<String> relativePathComponents = new LinkedList<>();
        while (filePathIter.hasNext()) {
            String currFilePathComponent = filePathIter.next();
            if (requestPathIter.hasNext()) {
                String requestFilePathComponent = requestPathIter.next();
                if (requestFilePathComponent.equals(currFilePathComponent)) {
                    continue;
                }
            }
            relativePathComponents.add(currFilePathComponent);
        }
        return String.join("/", relativePathComponents);
    }

    private static boolean moveTempFile(Uri fromPath, Uri toPath, String relativePath) {
        if (!ContentResolver.SCHEME_FILE.equals(fromPath.getScheme())) {
            Log.w(LOG_TAG, "Moving source uri " + fromPath+ " does not have a file scheme");
            return false;
        }
        if (!ContentResolver.SCHEME_FILE.equals(toPath.getScheme())) {
            Log.w(LOG_TAG, "Moving destination uri " + toPath + " does not have a file scheme");
            return false;
        }

        File fromFile = new File(fromPath.getSchemeSpecificPart());
        File toFile = new File(toPath.getSchemeSpecificPart(), relativePath);
        toFile.getParentFile().mkdirs();

        // TODO: This may not work if the two files are on different filesystems. Should we
        // enforce that the temp file storage and the permanent storage are both in the same fs?
        return fromFile.renameTo(toFile);
    }

    private static boolean verifyTempFilePath(Context context, DownloadRequest request,
            Uri filePath) {
        // TODO: modify pursuant to final decision on temp file path scheme
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

        if (!MbmsUtils.isContainedIn(getEmbmsTempFileDirForRequest(context, request), tempFile)) {
            return false;
        }

        return true;
    }

    /**
     * Returns a File linked to the directory used to store temp files for this request
     */
    private static File getEmbmsTempFileDirForRequest(Context context, DownloadRequest request) {
        File embmsTempFileDir = MbmsTempFileProvider.getEmbmsTempFileDir(
                context, getFileProviderAuthority(context));

        // TODO: better naming scheme for temp file dirs
        String tempFileDirName = String.valueOf(request.getFileServiceInfo().getServiceId());
        return new File(embmsTempFileDir, tempFileDirName);
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
}
