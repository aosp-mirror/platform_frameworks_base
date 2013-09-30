//
// Copyright 2006 The Android Open Source Project
//
// Android Asset Packaging Tool main entry point.
//
#include "Main.h"
#include "Bundle.h"

#include <utils/Log.h>
#include <utils/threads.h>
#include <utils/List.h>
#include <utils/Errors.h>

#include <stdlib.h>
#include <getopt.h>
#include <assert.h>

using namespace android;

static const char* gProgName = "aapt";

/*
 * When running under Cygwin on Windows, this will convert slash-based
 * paths into back-slash-based ones. Otherwise the ApptAssets file comparisons
 * fail later as they use back-slash separators under Windows.
 *
 * This operates in-place on the path string.
 */
void convertPath(char *path) {
  if (path != NULL && OS_PATH_SEPARATOR != '/') {
    for (; *path; path++) {
      if (*path == '/') {
        *path = OS_PATH_SEPARATOR;
      }
    }
  }
}

/*
 * Print usage info.
 */
void usage(void)
{
    fprintf(stderr, "Android Asset Packaging Tool\n\n");
    fprintf(stderr, "Usage:\n");
    fprintf(stderr,
        " %s l[ist] [-v] [-a] file.{zip,jar,apk}\n"
        "   List contents of Zip-compatible archive.\n\n", gProgName);
    fprintf(stderr,
        " %s d[ump] [--values] WHAT file.{apk} [asset [asset ...]]\n"
        "   strings          Print the contents of the resource table string pool in the APK.\n"
        "   badging          Print the label and icon for the app declared in APK.\n"
        "   permissions      Print the permissions from the APK.\n"
        "   resources        Print the resource table from the APK.\n"
        "   configurations   Print the configurations in the APK.\n"
        "   xmltree          Print the compiled xmls in the given assets.\n"
        "   xmlstrings       Print the strings of the given compiled xml assets.\n\n", gProgName);
    fprintf(stderr,
        " %s p[ackage] [-d][-f][-m][-u][-v][-x][-z][-M AndroidManifest.xml] \\\n"
        "        [-0 extension [-0 extension ...]] [-g tolerance] [-j jarfile] \\\n"
        "        [--debug-mode] [--min-sdk-version VAL] [--target-sdk-version VAL] \\\n"
        "        [--app-version VAL] [--app-version-name TEXT] [--custom-package VAL] \\\n"
        "        [--rename-manifest-package PACKAGE] \\\n"
        "        [--rename-instrumentation-target-package PACKAGE] \\\n"
        "        [--utf16] [--auto-add-overlay] \\\n"
        "        [--max-res-version VAL] \\\n"
        "        [-I base-package [-I base-package ...]] \\\n"
        "        [-A asset-source-dir]  [-G class-list-file] [-P public-definitions-file] \\\n"
        "        [-S resource-sources [-S resource-sources ...]] \\\n"
        "        [-F apk-file] [-J R-file-dir] \\\n"
        "        [--product product1,product2,...] \\\n"
        "        [-c CONFIGS] [--preferred-configurations CONFIGS] \\\n"
        "        [raw-files-dir [raw-files-dir] ...] \\\n"
        "        [--output-text-symbols DIR]\n"
        "\n"
        "   Package the android resources.  It will read assets and resources that are\n"
        "   supplied with the -M -A -S or raw-files-dir arguments.  The -J -P -F and -R\n"
        "   options control which files are output.\n\n"
        , gProgName);
    fprintf(stderr,
        " %s r[emove] [-v] file.{zip,jar,apk} file1 [file2 ...]\n"
        "   Delete specified files from Zip-compatible archive.\n\n",
        gProgName);
    fprintf(stderr,
        " %s a[dd] [-v] file.{zip,jar,apk} file1 [file2 ...]\n"
        "   Add specified files to Zip-compatible archive.\n\n", gProgName);
    fprintf(stderr,
        " %s c[runch] [-v] -S resource-sources ... -C output-folder ...\n"
        "   Do PNG preprocessing on one or several resource folders\n"
        "   and store the results in the output folder.\n\n", gProgName);
    fprintf(stderr,
        " %s s[ingleCrunch] [-v] -i input-file -o outputfile\n"
        "   Do PNG preprocessing on a single file.\n\n", gProgName);
    fprintf(stderr,
        " %s v[ersion]\n"
        "   Print program version.\n\n", gProgName);
    fprintf(stderr,
        " Modifiers:\n"
        "   -a  print Android-specific data (resources, manifest) when listing\n"
        "   -c  specify which configurations to include.  The default is all\n"
        "       configurations.  The value of the parameter should be a comma\n"
        "       separated list of configuration values.  Locales should be specified\n"
        "       as either a language or language-region pair.  Some examples:\n"
        "            en\n"
        "            port,en\n"
        "            port,land,en_US\n"
        "       If you put the special locale, zz_ZZ on the list, it will perform\n"
        "       pseudolocalization on the default locale, modifying all of the\n"
        "       strings so you can look for strings that missed the\n"
        "       internationalization process.  For example:\n"
        "            port,land,zz_ZZ\n"
        "   -d  one or more device assets to include, separated by commas\n"
        "   -f  force overwrite of existing files\n"
        "   -g  specify a pixel tolerance to force images to grayscale, default 0\n"
        "   -j  specify a jar or zip file containing classes to include\n"
        "   -k  junk path of file(s) added\n"
        "   -m  make package directories under location specified by -J\n"
#if 0
        "   -p  pseudolocalize the default configuration\n"
#endif
        "   -u  update existing packages (add new, replace older, remove deleted files)\n"
        "   -v  verbose output\n"
        "   -x  create extending (non-application) resource IDs\n"
        "   -z  require localization of resource attributes marked with\n"
        "       localization=\"suggested\"\n"
        "   -A  additional directory in which to find raw asset files\n"
        "   -G  A file to output proguard options into.\n"
        "   -F  specify the apk file to output\n"
        "   -I  add an existing package to base include set\n"
        "   -J  specify where to output R.java resource constant definitions\n"
        "   -M  specify full path to AndroidManifest.xml to include in zip\n"
        "   -P  specify where to output public resource definitions\n"
        "   -S  directory in which to find resources.  Multiple directories will be scanned\n"
        "       and the first match found (left to right) will take precedence.\n"
        "   -0  specifies an additional extension for which such files will not\n"
        "       be stored compressed in the .apk.  An empty string means to not\n"
        "       compress any files at all.\n"
        "   --debug-mode\n"
        "       inserts android:debuggable=\"true\" in to the application node of the\n"
        "       manifest, making the application debuggable even on production devices.\n"
        "   --min-sdk-version\n"
        "       inserts android:minSdkVersion in to manifest.  If the version is 7 or\n"
        "       higher, the default encoding for resources will be in UTF-8.\n"
        "   --target-sdk-version\n"
        "       inserts android:targetSdkVersion in to manifest.\n"
        "   --max-res-version\n"
        "       ignores versioned resource directories above the given value.\n"
        "   --values\n"
        "       when used with \"dump resources\" also includes resource values.\n"
        "   --version-code\n"
        "       inserts android:versionCode in to manifest.\n"
        "   --version-name\n"
        "       inserts android:versionName in to manifest.\n"
        "   --custom-package\n"
        "       generates R.java into a different package.\n"
        "   --extra-packages\n"
        "       generate R.java for libraries. Separate libraries with ':'.\n"
        "   --generate-dependencies\n"
        "       generate dependency files in the same directories for R.java and resource package\n"
        "   --auto-add-overlay\n"
        "       Automatically add resources that are only in overlays.\n"
        "   --preferred-configurations\n"
        "       Like the -c option for filtering out unneeded configurations, but\n"
        "       only expresses a preference.  If there is no resource available with\n"
        "       the preferred configuration then it will not be stripped.\n"
        "   --rename-manifest-package\n"
        "       Rewrite the manifest so that its package name is the package name\n"
        "       given here.  Relative class names (for example .Foo) will be\n"
        "       changed to absolute names with the old package so that the code\n"
        "       does not need to change.\n"
        "   --rename-instrumentation-target-package\n"
        "       Rewrite the manifest so that all of its instrumentation\n"
        "       components target the given package.  Useful when used in\n"
        "       conjunction with --rename-manifest-package to fix tests against\n"
        "       a package that has been renamed.\n"
        "   --product\n"
        "       Specifies which variant to choose for strings that have\n"
        "       product variants\n"
        "   --utf16\n"
        "       changes default encoding for resources to UTF-16.  Only useful when API\n"
        "       level is set to 7 or higher where the default encoding is UTF-8.\n"
        "   --non-constant-id\n"
        "       Make the resources ID non constant. This is required to make an R java class\n"
        "       that does not contain the final value but is used to make reusable compiled\n"
        "       libraries that need to access resources.\n"
        "   --error-on-failed-insert\n"
        "       Forces aapt to return an error if it fails to insert values into the manifest\n"
        "       with --debug-mode, --min-sdk-version, --target-sdk-version --version-code\n"
        "       and --version-name.\n"
        "       Insertion typically fails if the manifest already defines the attribute.\n"
        "   --output-text-symbols\n"
        "       Generates a text file containing the resource symbols of the R class in the\n"
        "       specified folder.\n"
        "   --ignore-assets\n"
        "       Assets to be ignored. Default pattern is:\n"
        "       %s\n",
        gDefaultIgnoreAssets);
}

/*
 * Dispatch the command.
 */
int handleCommand(Bundle* bundle)
{
    //printf("--- command %d (verbose=%d force=%d):\n",
    //    bundle->getCommand(), bundle->getVerbose(), bundle->getForce());
    //for (int i = 0; i < bundle->getFileSpecCount(); i++)
    //    printf("  %d: '%s'\n", i, bundle->getFileSpecEntry(i));

    switch (bundle->getCommand()) {
    case kCommandVersion:      return doVersion(bundle);
    case kCommandList:         return doList(bundle);
    case kCommandDump:         return doDump(bundle);
    case kCommandAdd:          return doAdd(bundle);
    case kCommandRemove:       return doRemove(bundle);
    case kCommandPackage:      return doPackage(bundle);
    case kCommandCrunch:       return doCrunch(bundle);
    case kCommandSingleCrunch: return doSingleCrunch(bundle);
    default:
        fprintf(stderr, "%s: requested command not yet supported\n", gProgName);
        return 1;
    }
}

/*
 * Parse args.
 */
int main(int argc, char* const argv[])
{
    char *prog = argv[0];
    Bundle bundle;
    bool wantUsage = false;
    int result = 1;    // pessimistically assume an error.
    int tolerance = 0;

    /* default to compression */
    bundle.setCompressionMethod(ZipEntry::kCompressDeflated);

    if (argc < 2) {
        wantUsage = true;
        goto bail;
    }

    if (argv[1][0] == 'v')
        bundle.setCommand(kCommandVersion);
    else if (argv[1][0] == 'd')
        bundle.setCommand(kCommandDump);
    else if (argv[1][0] == 'l')
        bundle.setCommand(kCommandList);
    else if (argv[1][0] == 'a')
        bundle.setCommand(kCommandAdd);
    else if (argv[1][0] == 'r')
        bundle.setCommand(kCommandRemove);
    else if (argv[1][0] == 'p')
        bundle.setCommand(kCommandPackage);
    else if (argv[1][0] == 'c')
        bundle.setCommand(kCommandCrunch);
    else if (argv[1][0] == 's')
        bundle.setCommand(kCommandSingleCrunch);
    else {
        fprintf(stderr, "ERROR: Unknown command '%s'\n", argv[1]);
        wantUsage = true;
        goto bail;
    }
    argc -= 2;
    argv += 2;

    /*
     * Pull out flags.  We support "-fv" and "-f -v".
     */
    while (argc && argv[0][0] == '-') {
        /* flag(s) found */
        const char* cp = argv[0] +1;

        while (*cp != '\0') {
            switch (*cp) {
            case 'v':
                bundle.setVerbose(true);
                break;
            case 'a':
                bundle.setAndroidList(true);
                break;
            case 'c':
                argc--;
                argv++;
                if (!argc) {
                    fprintf(stderr, "ERROR: No argument supplied for '-c' option\n");
                    wantUsage = true;
                    goto bail;
                }
                bundle.addConfigurations(argv[0]);
                break;
            case 'f':
                bundle.setForce(true);
                break;
            case 'g':
                argc--;
                argv++;
                if (!argc) {
                    fprintf(stderr, "ERROR: No argument supplied for '-g' option\n");
                    wantUsage = true;
                    goto bail;
                }
                tolerance = atoi(argv[0]);
                bundle.setGrayscaleTolerance(tolerance);
                printf("%s: Images with deviation <= %d will be forced to grayscale.\n", prog, tolerance);
                break;
            case 'k':
                bundle.setJunkPath(true);
                break;
            case 'm':
                bundle.setMakePackageDirs(true);
                break;
#if 0
            case 'p':
                bundle.setPseudolocalize(true);
                break;
#endif
            case 'u':
                bundle.setUpdate(true);
                break;
            case 'x':
                bundle.setExtending(true);
                break;
            case 'z':
                bundle.setRequireLocalization(true);
                break;
            case 'j':
                argc--;
                argv++;
                if (!argc) {
                    fprintf(stderr, "ERROR: No argument supplied for '-j' option\n");
                    wantUsage = true;
                    goto bail;
                }
                convertPath(argv[0]);
                bundle.addJarFile(argv[0]);
                break;
            case 'A':
                argc--;
                argv++;
                if (!argc) {
                    fprintf(stderr, "ERROR: No argument supplied for '-A' option\n");
                    wantUsage = true;
                    goto bail;
                }
                convertPath(argv[0]);
                bundle.setAssetSourceDir(argv[0]);
                break;
            case 'G':
                argc--;
                argv++;
                if (!argc) {
                    fprintf(stderr, "ERROR: No argument supplied for '-G' option\n");
                    wantUsage = true;
                    goto bail;
                }
                convertPath(argv[0]);
                bundle.setProguardFile(argv[0]);
                break;
            case 'I':
                argc--;
                argv++;
                if (!argc) {
                    fprintf(stderr, "ERROR: No argument supplied for '-I' option\n");
                    wantUsage = true;
                    goto bail;
                }
                convertPath(argv[0]);
                bundle.addPackageInclude(argv[0]);
                break;
            case 'F':
                argc--;
                argv++;
                if (!argc) {
                    fprintf(stderr, "ERROR: No argument supplied for '-F' option\n");
                    wantUsage = true;
                    goto bail;
                }
                convertPath(argv[0]);
                bundle.setOutputAPKFile(argv[0]);
                break;
            case 'J':
                argc--;
                argv++;
                if (!argc) {
                    fprintf(stderr, "ERROR: No argument supplied for '-J' option\n");
                    wantUsage = true;
                    goto bail;
                }
                convertPath(argv[0]);
                bundle.setRClassDir(argv[0]);
                break;
            case 'M':
                argc--;
                argv++;
                if (!argc) {
                    fprintf(stderr, "ERROR: No argument supplied for '-M' option\n");
                    wantUsage = true;
                    goto bail;
                }
                convertPath(argv[0]);
                bundle.setAndroidManifestFile(argv[0]);
                break;
            case 'P':
                argc--;
                argv++;
                if (!argc) {
                    fprintf(stderr, "ERROR: No argument supplied for '-P' option\n");
                    wantUsage = true;
                    goto bail;
                }
                convertPath(argv[0]);
                bundle.setPublicOutputFile(argv[0]);
                break;
            case 'S':
                argc--;
                argv++;
                if (!argc) {
                    fprintf(stderr, "ERROR: No argument supplied for '-S' option\n");
                    wantUsage = true;
                    goto bail;
                }
                convertPath(argv[0]);
                bundle.addResourceSourceDir(argv[0]);
                break;
            case 'C':
                argc--;
                argv++;
                if (!argc) {
                    fprintf(stderr, "ERROR: No argument supplied for '-C' option\n");
                    wantUsage = true;
                    goto bail;
                }
                convertPath(argv[0]);
                bundle.setCrunchedOutputDir(argv[0]);
                break;
            case 'i':
                argc--;
                argv++;
                if (!argc) {
                    fprintf(stderr, "ERROR: No argument supplied for '-i' option\n");
                    wantUsage = true;
                    goto bail;
                }
                convertPath(argv[0]);
                bundle.setSingleCrunchInputFile(argv[0]);
                break;
            case 'o':
                argc--;
                argv++;
                if (!argc) {
                    fprintf(stderr, "ERROR: No argument supplied for '-o' option\n");
                    wantUsage = true;
                    goto bail;
                }
                convertPath(argv[0]);
                bundle.setSingleCrunchOutputFile(argv[0]);
                break;
            case '0':
                argc--;
                argv++;
                if (!argc) {
                    fprintf(stderr, "ERROR: No argument supplied for '-e' option\n");
                    wantUsage = true;
                    goto bail;
                }
                if (argv[0][0] != 0) {
                    bundle.addNoCompressExtension(argv[0]);
                } else {
                    bundle.setCompressionMethod(ZipEntry::kCompressStored);
                }
                break;
            case '-':
                if (strcmp(cp, "-debug-mode") == 0) {
                    bundle.setDebugMode(true);
                } else if (strcmp(cp, "-min-sdk-version") == 0) {
                    argc--;
                    argv++;
                    if (!argc) {
                        fprintf(stderr, "ERROR: No argument supplied for '--min-sdk-version' option\n");
                        wantUsage = true;
                        goto bail;
                    }
                    bundle.setMinSdkVersion(argv[0]);
                } else if (strcmp(cp, "-target-sdk-version") == 0) {
                    argc--;
                    argv++;
                    if (!argc) {
                        fprintf(stderr, "ERROR: No argument supplied for '--target-sdk-version' option\n");
                        wantUsage = true;
                        goto bail;
                    }
                    bundle.setTargetSdkVersion(argv[0]);
                } else if (strcmp(cp, "-max-sdk-version") == 0) {
                    argc--;
                    argv++;
                    if (!argc) {
                        fprintf(stderr, "ERROR: No argument supplied for '--max-sdk-version' option\n");
                        wantUsage = true;
                        goto bail;
                    }
                    bundle.setMaxSdkVersion(argv[0]);
                } else if (strcmp(cp, "-max-res-version") == 0) {
                    argc--;
                    argv++;
                    if (!argc) {
                        fprintf(stderr, "ERROR: No argument supplied for '--max-res-version' option\n");
                        wantUsage = true;
                        goto bail;
                    }
                    bundle.setMaxResVersion(argv[0]);
                } else if (strcmp(cp, "-version-code") == 0) {
                    argc--;
                    argv++;
                    if (!argc) {
                        fprintf(stderr, "ERROR: No argument supplied for '--version-code' option\n");
                        wantUsage = true;
                        goto bail;
                    }
                    bundle.setVersionCode(argv[0]);
                } else if (strcmp(cp, "-version-name") == 0) {
                    argc--;
                    argv++;
                    if (!argc) {
                        fprintf(stderr, "ERROR: No argument supplied for '--version-name' option\n");
                        wantUsage = true;
                        goto bail;
                    }
                    bundle.setVersionName(argv[0]);
                } else if (strcmp(cp, "-values") == 0) {
                    bundle.setValues(true);
                } else if (strcmp(cp, "-custom-package") == 0) {
                    argc--;
                    argv++;
                    if (!argc) {
                        fprintf(stderr, "ERROR: No argument supplied for '--custom-package' option\n");
                        wantUsage = true;
                        goto bail;
                    }
                    bundle.setCustomPackage(argv[0]);
                } else if (strcmp(cp, "-extra-packages") == 0) {
                    argc--;
                    argv++;
                    if (!argc) {
                        fprintf(stderr, "ERROR: No argument supplied for '--extra-packages' option\n");
                        wantUsage = true;
                        goto bail;
                    }
                    bundle.setExtraPackages(argv[0]);
                } else if (strcmp(cp, "-generate-dependencies") == 0) {
                    bundle.setGenDependencies(true);
                } else if (strcmp(cp, "-utf16") == 0) {
                    bundle.setWantUTF16(true);
                } else if (strcmp(cp, "-preferred-configurations") == 0) {
                    argc--;
                    argv++;
                    if (!argc) {
                        fprintf(stderr, "ERROR: No argument supplied for '--preferred-configurations' option\n");
                        wantUsage = true;
                        goto bail;
                    }
                    bundle.addPreferredConfigurations(argv[0]);
                } else if (strcmp(cp, "-rename-manifest-package") == 0) {
                    argc--;
                    argv++;
                    if (!argc) {
                        fprintf(stderr, "ERROR: No argument supplied for '--rename-manifest-package' option\n");
                        wantUsage = true;
                        goto bail;
                    }
                    bundle.setManifestPackageNameOverride(argv[0]);
                } else if (strcmp(cp, "-rename-instrumentation-target-package") == 0) {
                    argc--;
                    argv++;
                    if (!argc) {
                        fprintf(stderr, "ERROR: No argument supplied for '--rename-instrumentation-target-package' option\n");
                        wantUsage = true;
                        goto bail;
                    }
                    bundle.setInstrumentationPackageNameOverride(argv[0]);
                } else if (strcmp(cp, "-auto-add-overlay") == 0) {
                    bundle.setAutoAddOverlay(true);
                } else if (strcmp(cp, "-error-on-failed-insert") == 0) {
                    bundle.setErrorOnFailedInsert(true);
                } else if (strcmp(cp, "-output-text-symbols") == 0) {
                    argc--;
                    argv++;
                    if (!argc) {
                        fprintf(stderr, "ERROR: No argument supplied for '-output-text-symbols' option\n");
                        wantUsage = true;
                        goto bail;
                    }
                    bundle.setOutputTextSymbols(argv[0]);
                } else if (strcmp(cp, "-product") == 0) {
                    argc--;
                    argv++;
                    if (!argc) {
                        fprintf(stderr, "ERROR: No argument supplied for '--product' option\n");
                        wantUsage = true;
                        goto bail;
                    }
                    bundle.setProduct(argv[0]);
                } else if (strcmp(cp, "-non-constant-id") == 0) {
                    bundle.setNonConstantId(true);
                } else if (strcmp(cp, "-no-crunch") == 0) {
                    bundle.setUseCrunchCache(true);
                } else if (strcmp(cp, "-ignore-assets") == 0) {
                    argc--;
                    argv++;
                    if (!argc) {
                        fprintf(stderr, "ERROR: No argument supplied for '--ignore-assets' option\n");
                        wantUsage = true;
                        goto bail;
                    }
                    gUserIgnoreAssets = argv[0];
                } else {
                    fprintf(stderr, "ERROR: Unknown option '-%s'\n", cp);
                    wantUsage = true;
                    goto bail;
                }
                cp += strlen(cp) - 1;
                break;
            default:
                fprintf(stderr, "ERROR: Unknown flag '-%c'\n", *cp);
                wantUsage = true;
                goto bail;
            }

            cp++;
        }
        argc--;
        argv++;
    }

    /*
     * We're past the flags.  The rest all goes straight in.
     */
    bundle.setFileSpec(argv, argc);

    result = handleCommand(&bundle);

bail:
    if (wantUsage) {
        usage();
        result = 2;
    }

    //printf("--> returning %d\n", result);
    return result;
}
