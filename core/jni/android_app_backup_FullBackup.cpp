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

#define LOG_TAG "FullBackup_native"
#include <utils/Log.h>
#include <utils/String8.h>

#include "JNIHelp.h"
#include <android_runtime/AndroidRuntime.h>

#include <utils/BackupHelpers.h>

#include <string.h>

namespace android
{

// android.app.backup.BackupDataOutput
static struct {
    // This is actually a native pointer to the underlying BackupDataWriter instance
    jfieldID mBackupWriter;
} sBackupDataOutput;

/*
 * Write files to the given output.  This implementation does *not* create
 * a standalone archive suitable for restore on its own.  In particular, the identification of
 * the application's name etc is not in-band here; it's assumed that the calling code has
 * taken care of supplying that information previously in the output stream.
 *
 * The file format is 'tar's, with special semantics applied by use of a "fake" directory
 * hierarchy within the tar stream:
 *
 * apps/packagename/a/Filename.apk - this is an actual application binary, which will be
 *   installed on the target device at restore time.  These need to appear first in the tar
 *   stream.
 * apps/packagename/obb/[relpath] - OBB containers belonging the app
 * apps/packagename/r/[relpath] - these are files at the root of the app's data tree
 * apps/packagename/f/[relpath] - this is a file within the app's getFilesDir() tree, stored
 *   at [relpath] relative to the top of that tree.
 * apps/packagename/db/[relpath] - as with "files" but for the getDatabasePath() tree
 * apps/packagename/sp/[relpath] - as with "files" but for the getSharedPrefsFile() tree
 * apps/packagename/c/[relpath] - as with "files" but for the getCacheDir() tree
 *
 * and for the shared storage hierarchy:
 *
 * shared/[relpaths] - files belonging in the device's shared storage location.  This will
 *    *not* include .obb files; those are saved with their owning apps.
 *
 * This method writes one file data block.  'domain' is the name of the appropriate pseudo-
 * directory to be applied for this file; 'linkdomain' is the pseudo-dir for a relative
 * symlink's antecedent.
 *
 * packagename: the package name to use as the top level directory tag
 * domain:      which semantic name the file is to be stored under (a, r, f, db, etc)
 * linkdomain:  where a symlink points for purposes of rewriting; current unused
 * rootpath:    prefix to be snipped from full path when encoding in tar
 * path:        absolute path to the file to be saved
 * dataOutput:  the BackupDataOutput object that we're saving into
 */
static int backupToTar(JNIEnv* env, jobject clazz, jstring packageNameObj,
        jstring domainObj, jstring linkdomain,
        jstring rootpathObj, jstring pathObj, jobject dataOutputObj) {
    int ret;

    // Extract the various strings, allowing for null object pointers
    const char* packagenamechars = (packageNameObj) ? env->GetStringUTFChars(packageNameObj, NULL) : NULL;
    const char* rootchars = (rootpathObj) ? env->GetStringUTFChars(rootpathObj, NULL) : NULL;
    const char* pathchars = (pathObj) ? env->GetStringUTFChars(pathObj, NULL) : NULL;
    const char* domainchars = (domainObj) ? env->GetStringUTFChars(domainObj, NULL) : NULL;

    String8 packageName(packagenamechars ? packagenamechars : "");
    String8 rootpath(rootchars ? rootchars : "");
    String8 path(pathchars ? pathchars : "");
    String8 domain(domainchars ? domainchars : "");

    if (domainchars) env->ReleaseStringUTFChars(domainObj, domainchars);
    if (pathchars) env->ReleaseStringUTFChars(pathObj, pathchars);
    if (rootchars) env->ReleaseStringUTFChars(rootpathObj, rootchars);
    if (packagenamechars) env->ReleaseStringUTFChars(packageNameObj, packagenamechars);

    // Extract the data output fd
    BackupDataWriter* writer = (BackupDataWriter*) env->GetIntField(dataOutputObj,
            sBackupDataOutput.mBackupWriter);

    // Validate
    if (!writer) {
        ALOGE("No output stream provided [%s]", path.string());
        return -1;
    }

    if (path.length() < rootpath.length()) {
        ALOGE("file path [%s] shorter than root path [%s]",
                path.string(), rootpath.string());
        return -1;
    }

    return write_tarfile(packageName, domain, rootpath, path, writer);
}

static const JNINativeMethod g_methods[] = {
    { "backupToTar",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Landroid/app/backup/BackupDataOutput;)I",
            (void*)backupToTar },
};

int register_android_app_backup_FullBackup(JNIEnv* env)
{
    jclass clazz = env->FindClass("android/app/backup/BackupDataOutput");
    LOG_FATAL_IF(clazz == NULL, "Unable to find class android.app.backup.BackupDataOutput");

    sBackupDataOutput.mBackupWriter = env->GetFieldID(clazz, "mBackupWriter", "I");
    LOG_FATAL_IF(sBackupDataOutput.mBackupwriter == NULL,
            "Unable to find mBackupWriter field in android.app.backup.BackupDataOutput");

    return AndroidRuntime::registerNativeMethods(env, "android/app/backup/FullBackup",
            g_methods, NELEM(g_methods));
}

}
