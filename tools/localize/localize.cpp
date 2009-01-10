#include "SourcePos.h"
#include "ValuesFile.h"
#include "XLIFFFile.h"
#include "Perforce.h"
#include "merge_res_and_xliff.h"
#include "localize.h"
#include "file_utils.h"
#include "res_check.h"
#include "xmb.h"

#include <host/pseudolocalize.h>

#include <stdlib.h>
#include <stdarg.h>
#include <sstream>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>

using namespace std;

FILE* g_logFile = NULL;

int test();

int
read_settings(const string& filename, map<string,Settings>* result, const string& rootDir)
{
    XMLNode* root = NodeHandler::ParseFile(filename, XMLNode::PRETTY);
    if (root == NULL) {
        SourcePos(filename, -1).Error("Error reading file.");
        return 1;
    }

    // <configuration>
    vector<XMLNode*> configNodes = root->GetElementsByName("", "configuration");
    const size_t I = configNodes.size();
    for (size_t i=0; i<I; i++) {
        const XMLNode* configNode = configNodes[i];

        Settings settings;
        settings.id = configNode->GetAttribute("", "id", "");
        if (settings.id == "") {
            configNode->Position().Error("<configuration> needs an id attribute.");
            delete root;
            return 1;
        }

        settings.oldVersion = configNode->GetAttribute("", "old-cl", "");

        settings.currentVersion = configNode->GetAttribute("", "new-cl", "");
        if (settings.currentVersion == "") {
            configNode->Position().Error("<configuration> needs a new-cl attribute.");
            delete root;
            return 1;
        }

        // <app>
        vector<XMLNode*> appNodes = configNode->GetElementsByName("", "app");

        const size_t J = appNodes.size();
        for (size_t j=0; j<J; j++) {
            const XMLNode* appNode = appNodes[j];

            string dir = appNode->GetAttribute("", "dir", "");
            if (dir == "") {
                appNode->Position().Error("<app> needs a dir attribute.");
                delete root;
                return 1;
            }

            settings.apps.push_back(dir);
        }

        // <reject>
        vector<XMLNode*> rejectNodes = configNode->GetElementsByName("", "reject");

        const size_t K = rejectNodes.size();
        for (size_t k=0; k<K; k++) {
            const XMLNode* rejectNode = rejectNodes[k];

            Reject reject;

            reject.file = rejectNode->GetAttribute("", "file", "");
            if (reject.file == "") {
                rejectNode->Position().Error("<reject> needs a file attribute.");
                delete root;
                return 1;
            }
            string f =  reject.file;
            reject.file = rootDir;
            reject.file += '/';
            reject.file += f;
            
            reject.name = rejectNode->GetAttribute("", "name", "");
            if (reject.name == "") {
                rejectNode->Position().Error("<reject> needs a name attribute.");
                delete root;
                return 1;
            }

            reject.comment = trim_string(rejectNode->CollapseTextContents());

            settings.reject.push_back(reject);
        }

        (*result)[settings.id] = settings;
    }

    delete root;
    return 0;
}


static void
ValuesFile_to_XLIFFFile(const ValuesFile* values, XLIFFFile* xliff, const string& englishFilename)
{
    const set<StringResource>& strings = values->GetStrings();
    for (set<StringResource>::const_iterator it=strings.begin(); it!=strings.end(); it++) {
        StringResource res = *it;
        res.file = englishFilename;
        xliff->AddStringResource(res);
    }
}

static bool
contains_reject(const Settings& settings, const string& file, const TransUnit& tu)
{
    const string name = tu.id;
    const vector<Reject>& reject = settings.reject;
    const size_t I = reject.size();
    for (size_t i=0; i<I; i++) {
        const Reject& r = reject[i];
        if (r.file == file && r.name == name) {
            return true;
        }
    }
    return false;
}

/**
 * If it's been rejected, then we keep whatever info we have.
 *
 * Implements this truth table:
 *
 *    S   AT   AS     Keep
 *   -----------------------
 *    0    0    0      0    (this case can't happen)
 *    0    0    1      0    (it was there, never translated, and removed)
 *    0    1    0      0    (somehow it got translated, but it was removed)
 *    0    1    1      0    (it was removed after having been translated)
 *
 *    1    0    0      1    (it was just added)
 *    1    0    1      1    (it was added, has been changed, but it never got translated)
 *    1    1    0      1    (somehow it got translated, but we don't know based on what)
 *    1    1    1     0/1   (it's in both.  0 if S=AS b/c there's no need to retranslate if they're
 *                           the same.  1 if S!=AS because S changed, so it should be retranslated)
 *
 * The first four are cases where, whatever happened in the past, the string isn't there
 * now, so it shouldn't be in the XLIFF file.
 *
 * For cases 4 and 5, the string has never been translated, so get it translated.
 *
 * For case 6, it's unclear where the translated version came from, so we're conservative
 * and send it back for them to have another shot at.
 *
 * For case 7, we have some data.  We have two choices.  We could rely on the translator's
 * translation memory or tools to notice that the strings haven't changed, and populate the
 * <target> field themselves.  Or if the string hasn't changed since last time, we can just
 * not even tell them about it.  As the project nears the end, it will be convenient to see
 * the xliff files reducing in size, so we pick the latter.  Obviously, if the string has
 * changed, then we need to get it retranslated.
 */
bool
keep_this_trans_unit(const string& file, const TransUnit& unit, void* cookie)
{
    const Settings* settings = reinterpret_cast<const Settings*>(cookie);

    if (contains_reject(*settings, file, unit)) {
        return true;
    }

    if (unit.source.id == "") {
        return false;
    }
    if (unit.altTarget.id == "" || unit.altSource.id == "") {
        return true;
    }
    return unit.source.value->ContentsToString(XLIFF_NAMESPACES)
            != unit.altSource.value->ContentsToString(XLIFF_NAMESPACES);
}

int
validate_config(const string& settingsFile, const map<string,Settings>& settings,
        const string& config)
{
    if (settings.find(config) == settings.end()) {
        SourcePos(settingsFile, -1).Error("settings file does not contain setting: %s\n",
                config.c_str());
        return 1;
    }
    return 0;
}

int
validate_configs(const string& settingsFile, const map<string,Settings>& settings,
        const vector<string>& configs)
{
    int err = 0;
    for (size_t i=0; i<configs.size(); i++) {
        string config = configs[i];
        err |= validate_config(settingsFile, settings, config);
    }
    return err;
}

int
select_files(vector<string> *resFiles, const string& config,
        const map<string,Settings>& settings, const string& rootDir)
{
    int err;
    vector<vector<string> > allResFiles;
    vector<string> configs;
    configs.push_back(config);
    err = select_files(&allResFiles, configs, settings, rootDir);
    if (err == 0) {
        *resFiles = allResFiles[0];
    }
    return err;
}

int
select_files(vector<vector<string> > *allResFiles, const vector<string>& configs,
        const map<string,Settings>& settings, const string& rootDir)
{
    int err;
    printf("Selecting files...");
    fflush(stdout);

    for (size_t i=0; i<configs.size(); i++) {
        const string& config = configs[i];
        const Settings& setting = settings.find(config)->second;

        vector<string> resFiles;
        err = Perforce::GetResourceFileNames(setting.currentVersion, rootDir,
                                                setting.apps, &resFiles, true);
        if (err != 0) {
            fprintf(stderr, "error with perforce.  bailing\n");
            return err;
        }

        allResFiles->push_back(resFiles);
    }
    return 0;
}

static int
do_export(const string& settingsFile, const string& rootDir, const string& outDir,
            const string& targetLocale, const vector<string>& configs)
{
    bool success = true;
    int err;

    if (false) {
        printf("settingsFile=%s\n", settingsFile.c_str());
        printf("rootDir=%s\n", rootDir.c_str());
        printf("outDir=%s\n", outDir.c_str());
        for (size_t i=0; i<configs.size(); i++) {
            printf("config[%zd]=%s\n", i, configs[i].c_str());
        }
    }

    map<string,Settings> settings;
    err = read_settings(settingsFile, &settings, rootDir);
    if (err != 0) {
        return err;
    }

    err = validate_configs(settingsFile, settings, configs);
    if (err != 0) {
        return err;
    }

    vector<vector<string> > allResFiles;
    err = select_files(&allResFiles, configs, settings, rootDir);
    if (err != 0) {
        return err;
    }

    size_t totalFileCount = 0;
    for (size_t i=0; i<allResFiles.size(); i++) {
        totalFileCount += allResFiles[i].size();
    }
    totalFileCount *= 3; // we try all 3 versions of the file

    size_t fileProgress = 0;
    vector<Stats> stats;
    vector<pair<string,XLIFFFile*> > xliffs;

    for (size_t i=0; i<configs.size(); i++) {
        const string& config = configs[i];
        const Settings& setting = settings[config];

        if (false) {
            fprintf(stderr, "Configuration: %s (%zd of %zd)\n", config.c_str(), i+1,
                    configs.size());
            fprintf(stderr, "  Old CL:     %s\n", setting.oldVersion.c_str());
            fprintf(stderr, "  Current CL: %s\n", setting.currentVersion.c_str());
        }

        Configuration english;
            english.locale = "en_US";
        Configuration translated;
            translated.locale = targetLocale;
        XLIFFFile* xliff = XLIFFFile::Create(english, translated, setting.currentVersion);

        const vector<string>& resFiles = allResFiles[i];
        const size_t J = resFiles.size();
        for (size_t j=0; j<J; j++) {
            string resFile = resFiles[j];

            // parse the files into a ValuesFile
            // pull out the strings and add them to the XLIFFFile
            
            // current file
            print_file_status(++fileProgress, totalFileCount);
            ValuesFile* currentFile = get_values_file(resFile, english, CURRENT_VERSION,
                                                        setting.currentVersion, true);
            if (currentFile != NULL) {
                ValuesFile_to_XLIFFFile(currentFile, xliff, resFile);
                //printf("currentFile=[%s]\n", currentFile->ToString().c_str());
            } else {
                fprintf(stderr, "error reading file %s@%s\n", resFile.c_str(),
                            setting.currentVersion.c_str());
                success = false;
            }

            // old file
            print_file_status(++fileProgress, totalFileCount);
            ValuesFile* oldFile = get_values_file(resFile, english, OLD_VERSION,
                                                        setting.oldVersion, false);
            if (oldFile != NULL) {
                ValuesFile_to_XLIFFFile(oldFile, xliff, resFile);
                //printf("oldFile=[%s]\n", oldFile->ToString().c_str());
            }

            // translated version
            // (get the head of the tree for the most recent translation, but it's considered
            // the old one because the "current" one hasn't been made yet, and this goes into
            // the <alt-trans> tag if necessary
            print_file_status(++fileProgress, totalFileCount);
            string transFilename = translated_file_name(resFile, targetLocale);
            ValuesFile* transFile = get_values_file(transFilename, translated, OLD_VERSION,
                                                        setting.currentVersion, false);
            if (transFile != NULL) {
                ValuesFile_to_XLIFFFile(transFile, xliff, resFile);
            }

            delete currentFile;
            delete oldFile;
            delete transFile;
        }

        Stats beforeFilterStats = xliff->GetStats(config);

        // run through the XLIFFFile and strip out TransUnits that have identical
        // old and current source values and are not in the reject list, or just
        // old values and no source values
        xliff->Filter(keep_this_trans_unit, (void*)&setting);

        Stats afterFilterStats = xliff->GetStats(config);
        afterFilterStats.totalStrings = beforeFilterStats.totalStrings;

        // add the reject comments
        for (vector<Reject>::const_iterator reject = setting.reject.begin();
                reject != setting.reject.end(); reject++) {
            TransUnit* tu = xliff->EditTransUnit(reject->file, reject->name);
            tu->rejectComment = reject->comment;
        }

        // config-locale-current_cl.xliff
        stringstream filename;
        if (outDir != "") {
            filename << outDir << '/';
        }
        filename << config << '-' << targetLocale << '-' << setting.currentVersion << ".xliff";
        xliffs.push_back(pair<string,XLIFFFile*>(filename.str(), xliff));

        stats.push_back(afterFilterStats);
    }

    // today is a good day to die
    if (!success || SourcePos::HasErrors()) {
        return 1;
    }

    // write the XLIFF files
    printf("\nWriting %zd file%s...\n", xliffs.size(), xliffs.size() == 1 ? "" : "s");
    for (vector<pair<string,XLIFFFile*> >::iterator it = xliffs.begin(); it != xliffs.end(); it++) {
        const string& filename = it->first;
        XLIFFFile* xliff = it->second;
        string text = xliff->ToString();
        write_to_file(filename, text);
    }

    // the stats
    printf("\n"
           "                                  to          without     total\n"
           " config               files       translate   comments    strings\n"
           "-----------------------------------------------------------------------\n");
    Stats totals;
        totals.config = "total";
        totals.files = 0;
        totals.toBeTranslated = 0;
        totals.noComments = 0;
        totals.totalStrings = 0;
    for (vector<Stats>::iterator it=stats.begin(); it!=stats.end(); it++) {
        string cfg = it->config;
        if (cfg.length() > 20) {
            cfg.resize(20);
        }
        printf(" %-20s  %-9zd   %-9zd   %-9zd   %-19zd\n", cfg.c_str(), it->files,
                it->toBeTranslated, it->noComments, it->totalStrings);
        totals.files += it->files;
        totals.toBeTranslated += it->toBeTranslated;
        totals.noComments += it->noComments;
        totals.totalStrings += it->totalStrings;
    }
    if (stats.size() > 1) {
        printf("-----------------------------------------------------------------------\n"
               " %-20s  %-9zd   %-9zd   %-9zd   %-19zd\n", totals.config.c_str(), totals.files,
                    totals.toBeTranslated, totals.noComments, totals.totalStrings);
    }
    printf("\n");
    return 0;
}

struct PseudolocalizeSettings {
    XLIFFFile* xliff;
    bool expand;
};


string
pseudolocalize_string(const string& source, const PseudolocalizeSettings* settings)
{
    return pseudolocalize_string(source);
}

static XMLNode*
pseudolocalize_xml_node(const XMLNode* source, const PseudolocalizeSettings* settings)
{
    if (source->Type() == XMLNode::TEXT) {
        return XMLNode::NewText(source->Position(), pseudolocalize_string(source->Text(), settings),
                                source->Pretty());
    } else {
        XMLNode* target;
        if (source->Namespace() == XLIFF_XMLNS && source->Name() == "g") {
            // XXX don't translate these
            target = XMLNode::NewElement(source->Position(), source->Namespace(),
                                    source->Name(), source->Attributes(), source->Pretty());
        } else {
            target = XMLNode::NewElement(source->Position(), source->Namespace(),
                                    source->Name(), source->Attributes(), source->Pretty());
        }

        const vector<XMLNode*>& children = source->Children();
        const size_t I = children.size();
        for (size_t i=0; i<I; i++) {
            target->EditChildren().push_back(pseudolocalize_xml_node(children[i], settings));
        }

        return target;
    }
}

void
pseudolocalize_trans_unit(const string&file, TransUnit* unit, void* cookie)
{
    const PseudolocalizeSettings* settings = (PseudolocalizeSettings*)cookie;

    const StringResource& source = unit->source;
    StringResource* target = &unit->target;
    *target = source;

    target->config = settings->xliff->TargetConfig();

    delete target->value;
    target->value = pseudolocalize_xml_node(source.value, settings);
}

int
pseudolocalize_xliff(XLIFFFile* xliff, bool expand)
{
    PseudolocalizeSettings settings;

    settings.xliff = xliff;
    settings.expand = expand;
    xliff->Map(pseudolocalize_trans_unit, &settings);
    return 0;
}

static int
do_pseudo(const string& infile, const string& outfile, bool expand)
{
    int err;

    XLIFFFile* xliff = XLIFFFile::Parse(infile);
    if (xliff == NULL) {
        return 1;
    }

    pseudolocalize_xliff(xliff, expand);

    err = write_to_file(outfile, xliff->ToString());

    delete xliff;

    return err;
}

void
log_printf(const char *fmt, ...)
{
    int ret;
    va_list ap;

    if (g_logFile != NULL) {
        va_start(ap, fmt);
        ret = vfprintf(g_logFile, fmt, ap);
        va_end(ap);
        fflush(g_logFile);
    }
}

void
close_log_file()
{
    if (g_logFile != NULL) {
        fclose(g_logFile);
    }
}

void
open_log_file(const char* file)
{
    g_logFile = fopen(file, "w");
    printf("log file: %s -- %p\n", file, g_logFile);
    atexit(close_log_file);
}

static int
usage()
{
    fprintf(stderr,
            "usage: localize export OPTIONS CONFIGS...\n"
            "   REQUIRED OPTIONS\n"
            "     --settings SETTINGS   The settings file to use.  See CONFIGS below.\n"
            "     --root TREE_ROOT      The location in Perforce of the files.  e.g. //device\n"
            "     --target LOCALE       The target locale.  See LOCALES below.\n"
            "\n"
            "   OPTIONAL OPTIONS\n"
            "      --out DIR            Directory to put the output files.  Defaults to the\n"
            "                           current directory if not supplied.  Files are\n"
            "                           named as follows:\n"
            "                               CONFIG-LOCALE-CURRENT_CL.xliff\n"
            "\n"
            "\n"
            "usage: localize import XLIFF_FILE...\n"
            "\n"
            "Import a translated XLIFF file back into the tree.\n"
            "\n"
            "\n"
            "usage: localize xlb XMB_FILE VALUES_FILES...\n"
            "\n"
            "Read resource files from the tree file and write the corresponding XLB file\n"
            "\n"
            "Supply all of the android resource files (values files) to export after that.\n"
            "\n"
            "\n"
            "\n"
            "CONFIGS\n"
            "\n"
            "LOCALES\n"
            "Locales are specified in the form en_US  They will be processed correctly\n"
            "to locate the resouce files in the tree.\n"
            "\n"
            "\n"
            "usage: localize pseudo OPTIONS INFILE [OUTFILE]\n"
            "   OPTIONAL OPTIONS\n"
            "     --big                 Pad strings so they get longer.\n"
            "\n"
            "Read INFILE, an XLIFF file, and output a pseudotranslated version of that file.  If\n"
            "OUTFILE is specified, the results are written there; otherwise, the results are\n"
            "written back to INFILE.\n"
            "\n"
            "\n"
            "usage: localize rescheck FILES...\n"
            "\n"
            "Reads the base strings and prints warnings about bad resources from the given files.\n"
            "\n");
    return 1;
}

int
main(int argc, const char** argv)
{
    //open_log_file("log.txt");
    //g_logFile = stdout;

    if (argc == 2 && 0 == strcmp(argv[1], "--test")) {
        return test();
    }

    if (argc < 2) {
        return usage();
    }

    int index = 1;
    
    if (0 == strcmp("export", argv[index])) {
        string settingsFile;
        string rootDir;
        string outDir;
        string baseLocale = "en";
        string targetLocale;
        string language, region;
        vector<string> configs;

        index++;
        while (index < argc) {
            if (0 == strcmp("--settings", argv[index])) {
                settingsFile = argv[index+1];
                index += 2;
            }
            else if (0 == strcmp("--root", argv[index])) {
                rootDir = argv[index+1];
                index += 2;
            }
            else if (0 == strcmp("--out", argv[index])) {
                outDir = argv[index+1];
                index += 2;
            }
            else if (0 == strcmp("--target", argv[index])) {
                targetLocale = argv[index+1];
                index += 2;
            }
            else if (argv[index][0] == '-') {
                fprintf(stderr, "unknown argument %s\n", argv[index]);
                return usage();
            }
            else {
                break;
            }
        }
        for (; index<argc; index++) {
            configs.push_back(argv[index]);
        }

        if (settingsFile == "" || rootDir == "" || configs.size() == 0 || targetLocale == "") {
            return usage();
        }
        if (!split_locale(targetLocale, &language, &region)) {
            fprintf(stderr, "illegal --target locale: '%s'\n", targetLocale.c_str());
            return usage();
        }


        return do_export(settingsFile, rootDir, outDir, targetLocale, configs);
    }
    else if (0 == strcmp("import", argv[index])) {
        vector<string> xliffFilenames;

        index++;
        for (; index<argc; index++) {
            xliffFilenames.push_back(argv[index]);
        }

        return do_merge(xliffFilenames);
    }
    else if (0 == strcmp("xlb", argv[index])) {
        string outfile;
        vector<string> resFiles;

        index++;
        if (argc < index+1) {
            return usage();
        }

        outfile = argv[index];

        index++;
        for (; index<argc; index++) {
            resFiles.push_back(argv[index]);
        }

        return do_xlb_export(outfile, resFiles);
    }
    else if (0 == strcmp("pseudo", argv[index])) {
        string infile;
        string outfile;
        bool big = false;

        index++;
        while (index < argc) {
            if (0 == strcmp("--big", argv[index])) {
                big = true;
                index += 1;
            }
            else if (argv[index][0] == '-') {
                fprintf(stderr, "unknown argument %s\n", argv[index]);
                return usage();
            }
            else {
                break;
            }
        }

        if (index == argc-1) {
            infile = argv[index];
            outfile = argv[index];
        }
        else if (index == argc-2) {
            infile = argv[index];
            outfile = argv[index+1];
        }
        else {
            fprintf(stderr, "unknown argument %s\n", argv[index]);
            return usage();
        }

        return do_pseudo(infile, outfile, big);
    }
    else if (0 == strcmp("rescheck", argv[index])) {
        vector<string> files;

        index++;
        while (index < argc) {
            if (argv[index][0] == '-') {
                fprintf(stderr, "unknown argument %s\n", argv[index]);
                return usage();
            }
            else {
                break;
            }
        }
        for (; index<argc; index++) {
            files.push_back(argv[index]);
        }

        if (files.size() == 0) {
            return usage();
        }

        return do_rescheck(files);
    }
    else {
        return usage();
    }

    if (SourcePos::HasErrors()) {
        SourcePos::PrintErrors(stderr);
        return 1;
    }

    return 0;
}

