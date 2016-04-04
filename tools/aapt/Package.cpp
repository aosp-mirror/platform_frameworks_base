//
// Copyright 2006 The Android Open Source Project
//
// Package assets into Zip files.
//
#include "Main.h"
#include "AaptAssets.h"
#include "OutputSet.h"
#include "ResourceTable.h"
#include "ResourceFilter.h"

#include <androidfw/misc.h>

#include <utils/Log.h>
#include <utils/threads.h>
#include <utils/List.h>
#include <utils/Errors.h>
#include <utils/misc.h>

#include <sys/types.h>
#include <dirent.h>
#include <ctype.h>
#include <errno.h>

using namespace android;

static const char* kExcludeExtension = ".EXCLUDE";

/* these formats are already compressed, or don't compress well */
static const char* kNoCompressExt[] = {
    ".jpg", ".jpeg", ".png", ".gif",
    ".wav", ".mp2", ".mp3", ".ogg", ".aac",
    ".mpg", ".mpeg", ".mid", ".midi", ".smf", ".jet",
    ".rtttl", ".imy", ".xmf", ".mp4", ".m4a",
    ".m4v", ".3gp", ".3gpp", ".3g2", ".3gpp2",
    ".amr", ".awb", ".wma", ".wmv", ".webm", ".mkv"
};

/* fwd decls, so I can write this downward */
ssize_t processAssets(Bundle* bundle, ZipFile* zip, const sp<const OutputSet>& outputSet);
bool processFile(Bundle* bundle, ZipFile* zip, String8 storageName, const sp<const AaptFile>& file);
bool okayToCompress(Bundle* bundle, const String8& pathName);
ssize_t processJarFiles(Bundle* bundle, ZipFile* zip);

/*
 * The directory hierarchy looks like this:
 * "outputDir" and "assetRoot" are existing directories.
 *
 * On success, "bundle->numPackages" will be the number of Zip packages
 * we created.
 */
status_t writeAPK(Bundle* bundle, const String8& outputFile, const sp<OutputSet>& outputSet)
{
    #if BENCHMARK
    fprintf(stdout, "BENCHMARK: Starting APK Bundling \n");
    long startAPKTime = clock();
    #endif /* BENCHMARK */

    status_t result = NO_ERROR;
    ZipFile* zip = NULL;
    int count;

    //bundle->setPackageCount(0);

    /*
     * Prep the Zip archive.
     *
     * If the file already exists, fail unless "update" or "force" is set.
     * If "update" is set, update the contents of the existing archive.
     * Else, if "force" is set, remove the existing archive.
     */
    FileType fileType = getFileType(outputFile.string());
    if (fileType == kFileTypeNonexistent) {
        // okay, create it below
    } else if (fileType == kFileTypeRegular) {
        if (bundle->getUpdate()) {
            // okay, open it below
        } else if (bundle->getForce()) {
            if (unlink(outputFile.string()) != 0) {
                fprintf(stderr, "ERROR: unable to remove '%s': %s\n", outputFile.string(),
                        strerror(errno));
                goto bail;
            }
        } else {
            fprintf(stderr, "ERROR: '%s' exists (use '-f' to force overwrite)\n",
                    outputFile.string());
            goto bail;
        }
    } else {
        fprintf(stderr, "ERROR: '%s' exists and is not a regular file\n", outputFile.string());
        goto bail;
    }

    if (bundle->getVerbose()) {
        printf("%s '%s'\n", (fileType == kFileTypeNonexistent) ? "Creating" : "Opening",
                outputFile.string());
    }

    status_t status;
    zip = new ZipFile;
    status = zip->open(outputFile.string(), ZipFile::kOpenReadWrite | ZipFile::kOpenCreate);
    if (status != NO_ERROR) {
        fprintf(stderr, "ERROR: unable to open '%s' as Zip file for writing\n",
                outputFile.string());
        goto bail;
    }

    if (bundle->getVerbose()) {
        printf("Writing all files...\n");
    }

    count = processAssets(bundle, zip, outputSet);
    if (count < 0) {
        fprintf(stderr, "ERROR: unable to process assets while packaging '%s'\n",
                outputFile.string());
        result = count;
        goto bail;
    }

    if (bundle->getVerbose()) {
        printf("Generated %d file%s\n", count, (count==1) ? "" : "s");
    }
    
    count = processJarFiles(bundle, zip);
    if (count < 0) {
        fprintf(stderr, "ERROR: unable to process jar files while packaging '%s'\n",
                outputFile.string());
        result = count;
        goto bail;
    }
    
    if (bundle->getVerbose())
        printf("Included %d file%s from jar/zip files.\n", count, (count==1) ? "" : "s");
    
    result = NO_ERROR;

    /*
     * Check for cruft.  We set the "marked" flag on all entries we created
     * or decided not to update.  If the entry isn't already slated for
     * deletion, remove it now.
     */
    {
        if (bundle->getVerbose())
            printf("Checking for deleted files\n");
        int i, removed = 0;
        for (i = 0; i < zip->getNumEntries(); i++) {
            ZipEntry* entry = zip->getEntryByIndex(i);

            if (!entry->getMarked() && entry->getDeleted()) {
                if (bundle->getVerbose()) {
                    printf("      (removing crufty '%s')\n",
                        entry->getFileName());
                }
                zip->remove(entry);
                removed++;
            }
        }
        if (bundle->getVerbose() && removed > 0)
            printf("Removed %d file%s\n", removed, (removed==1) ? "" : "s");
    }

    /* tell Zip lib to process deletions and other pending changes */
    result = zip->flush();
    if (result != NO_ERROR) {
        fprintf(stderr, "ERROR: Zip flush failed, archive may be hosed\n");
        goto bail;
    }

    /* anything here? */
    if (zip->getNumEntries() == 0) {
        if (bundle->getVerbose()) {
            printf("Archive is empty -- removing %s\n", outputFile.getPathLeaf().string());
        }
        delete zip;        // close the file so we can remove it in Win32
        zip = NULL;
        if (unlink(outputFile.string()) != 0) {
            fprintf(stderr, "warning: could not unlink '%s'\n", outputFile.string());
        }
    }

    // If we've been asked to generate a dependency file for the .ap_ package,
    // do so here
    if (bundle->getGenDependencies()) {
        // The dependency file gets output to the same directory
        // as the specified output file with an additional .d extension.
        // e.g. bin/resources.ap_.d
        String8 dependencyFile = outputFile;
        dependencyFile.append(".d");

        FILE* fp = fopen(dependencyFile.string(), "a");
        // Add this file to the dependency file
        fprintf(fp, "%s \\\n", outputFile.string());
        fclose(fp);
    }

    assert(result == NO_ERROR);

bail:
    delete zip;        // must close before remove in Win32
    if (result != NO_ERROR) {
        if (bundle->getVerbose()) {
            printf("Removing %s due to earlier failures\n", outputFile.string());
        }
        if (unlink(outputFile.string()) != 0) {
            fprintf(stderr, "warning: could not unlink '%s'\n", outputFile.string());
        }
    }

    if (result == NO_ERROR && bundle->getVerbose())
        printf("Done!\n");

    #if BENCHMARK
    fprintf(stdout, "BENCHMARK: End APK Bundling. Time Elapsed: %f ms \n",(clock() - startAPKTime)/1000.0);
    #endif /* BENCHMARK */
    return result;
}

ssize_t processAssets(Bundle* bundle, ZipFile* zip, const sp<const OutputSet>& outputSet)
{
    ssize_t count = 0;
    const std::set<OutputEntry>& entries = outputSet->getEntries();
    std::set<OutputEntry>::const_iterator iter = entries.begin();
    for (; iter != entries.end(); iter++) {
        const OutputEntry& entry = *iter;
        if (entry.getFile() == NULL) {
            fprintf(stderr, "warning: null file being processed.\n");
        } else {
            String8 storagePath(entry.getPath());
            storagePath.convertToResPath();
            if (!processFile(bundle, zip, storagePath, entry.getFile())) {
                return UNKNOWN_ERROR;
            }
            count++;
        }
    }
    return count;
}

/*
 * Process a regular file, adding it to the archive if appropriate.
 *
 * If we're in "update" mode, and the file already exists in the archive,
 * delete the existing entry before adding the new one.
 */
bool processFile(Bundle* bundle, ZipFile* zip,
                 String8 storageName, const sp<const AaptFile>& file)
{
    const bool hasData = file->hasData();

    ZipEntry* entry;
    bool fromGzip = false;
    status_t result;

    /*
     * See if the filename ends in ".EXCLUDE".  We can't use
     * String8::getPathExtension() because the length of what it considers
     * to be an extension is capped.
     *
     * The Asset Manager doesn't check for ".EXCLUDE" in Zip archives,
     * so there's no value in adding them (and it makes life easier on
     * the AssetManager lib if we don't).
     *
     * NOTE: this restriction has been removed.  If you're in this code, you
     * should clean this up, but I'm in here getting rid of Path Name, and I
     * don't want to make other potentially breaking changes --joeo
     */
    int fileNameLen = storageName.length();
    int excludeExtensionLen = strlen(kExcludeExtension);
    if (fileNameLen > excludeExtensionLen
            && (0 == strcmp(storageName.string() + (fileNameLen - excludeExtensionLen),
                            kExcludeExtension))) {
        fprintf(stderr, "warning: '%s' not added to Zip\n", storageName.string());
        return true;
    }

    if (strcasecmp(storageName.getPathExtension().string(), ".gz") == 0) {
        fromGzip = true;
        storageName = storageName.getBasePath();
    }

    if (bundle->getUpdate()) {
        entry = zip->getEntryByName(storageName.string());
        if (entry != NULL) {
            /* file already exists in archive; there can be only one */
            if (entry->getMarked()) {
                fprintf(stderr,
                        "ERROR: '%s' exists twice (check for with & w/o '.gz'?)\n",
                        file->getPrintableSource().string());
                return false;
            }
            if (!hasData) {
                const String8& srcName = file->getSourceFile();
                time_t fileModWhen;
                fileModWhen = getFileModDate(srcName.string());
                if (fileModWhen == (time_t) -1) { // file existence tested earlier,
                    return false;                 //  not expecting an error here
                }
    
                if (fileModWhen > entry->getModWhen()) {
                    // mark as deleted so add() will succeed
                    if (bundle->getVerbose()) {
                        printf("      (removing old '%s')\n", storageName.string());
                    }
    
                    zip->remove(entry);
                } else {
                    // version in archive is newer
                    if (bundle->getVerbose()) {
                        printf("      (not updating '%s')\n", storageName.string());
                    }
                    entry->setMarked(true);
                    return true;
                }
            } else {
                // Generated files are always replaced.
                zip->remove(entry);
            }
        }
    }

    //android_setMinPriority(NULL, ANDROID_LOG_VERBOSE);

    if (fromGzip) {
        result = zip->addGzip(file->getSourceFile().string(), storageName.string(), &entry);
    } else if (!hasData) {
        /* don't compress certain files, e.g. PNGs */
        int compressionMethod = bundle->getCompressionMethod();
        if (!okayToCompress(bundle, storageName)) {
            compressionMethod = ZipEntry::kCompressStored;
        }
        result = zip->add(file->getSourceFile().string(), storageName.string(), compressionMethod,
                            &entry);
    } else {
        result = zip->add(file->getData(), file->getSize(), storageName.string(),
                           file->getCompressionMethod(), &entry);
    }
    if (result == NO_ERROR) {
        if (bundle->getVerbose()) {
            printf("      '%s'%s", storageName.string(), fromGzip ? " (from .gz)" : "");
            if (entry->getCompressionMethod() == ZipEntry::kCompressStored) {
                printf(" (not compressed)\n");
            } else {
                printf(" (compressed %d%%)\n", calcPercent(entry->getUncompressedLen(),
                            entry->getCompressedLen()));
            }
        }
        entry->setMarked(true);
    } else {
        if (result == ALREADY_EXISTS) {
            fprintf(stderr, "      Unable to add '%s': file already in archive (try '-u'?)\n",
                    file->getPrintableSource().string());
        } else {
            fprintf(stderr, "      Unable to add '%s': Zip add failed (%d)\n",
                    file->getPrintableSource().string(), result);
        }
        return false;
    }

    return true;
}

/*
 * Determine whether or not we want to try to compress this file based
 * on the file extension.
 */
bool okayToCompress(Bundle* bundle, const String8& pathName)
{
    String8 ext = pathName.getPathExtension();
    int i;

    if (ext.length() == 0)
        return true;

    for (i = 0; i < NELEM(kNoCompressExt); i++) {
        if (strcasecmp(ext.string(), kNoCompressExt[i]) == 0)
            return false;
    }

    const android::Vector<const char*>& others(bundle->getNoCompressExtensions());
    for (i = 0; i < (int)others.size(); i++) {
        const char* str = others[i];
        int pos = pathName.length() - strlen(str);
        if (pos < 0) {
            continue;
        }
        const char* path = pathName.string();
        if (strcasecmp(path + pos, str) == 0) {
            return false;
        }
    }

    return true;
}

bool endsWith(const char* haystack, const char* needle)
{
    size_t a = strlen(haystack);
    size_t b = strlen(needle);
    if (a < b) return false;
    return strcasecmp(haystack+(a-b), needle) == 0;
}

ssize_t processJarFile(ZipFile* jar, ZipFile* out)
{
    size_t N = jar->getNumEntries();
    size_t count = 0;
    for (size_t i=0; i<N; i++) {
        ZipEntry* entry = jar->getEntryByIndex(i);
        const char* storageName = entry->getFileName();
        if (endsWith(storageName, ".class")) {
            int compressionMethod = entry->getCompressionMethod();
            size_t size = entry->getUncompressedLen();
            const void* data = jar->uncompress(entry);
            if (data == NULL) {
                fprintf(stderr, "ERROR: unable to uncompress entry '%s'\n",
                    storageName);
                return -1;
            }
            out->add(data, size, storageName, compressionMethod, NULL);
            free((void*)data);
        }
        count++;
    }
    return count;
}

ssize_t processJarFiles(Bundle* bundle, ZipFile* zip)
{
    status_t err;
    ssize_t count = 0;
    const android::Vector<const char*>& jars = bundle->getJarFiles();

    size_t N = jars.size();
    for (size_t i=0; i<N; i++) {
        ZipFile jar;
        err = jar.open(jars[i], ZipFile::kOpenReadOnly);
        if (err != 0) {
            fprintf(stderr, "ERROR: unable to open '%s' as a zip file: %d\n",
                jars[i], err);
            return err;
        }
        err += processJarFile(&jar, zip);
        if (err < 0) {
            fprintf(stderr, "ERROR: unable to process '%s'\n", jars[i]);
            return err;
        }
        count += err;
    }

    return count;
}
