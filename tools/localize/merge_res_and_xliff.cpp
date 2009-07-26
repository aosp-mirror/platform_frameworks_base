#include "merge_res_and_xliff.h"

#include "file_utils.h"
#include "Perforce.h"
#include "log.h"
#include <stdio.h>

static set<StringResource>::const_iterator
find_id(const set<StringResource>& s, const string& id, int index)
{
    for (set<StringResource>::const_iterator it = s.begin(); it != s.end(); it++) {
        if (it->id == id && it->index == index) {
            return it;
        }
    }
    return s.end();
}

static set<StringResource>::const_iterator
find_in_xliff(const set<StringResource>& s, const string& filename, const string& id, int index,
                int version, const Configuration& config)
{
    for (set<StringResource>::const_iterator it = s.begin(); it != s.end(); it++) {
        if (it->file == filename && it->id == id && it->index == index && it->version == version
                && it->config == config) {
            return it;
        }
    }
    return s.end();
}


static void
printit(const set<StringResource>& s, const set<StringResource>::const_iterator& it)
{
    if (it == s.end()) {
        printf("(none)\n");
    } else {
        printf("id=%s index=%d config=%s file=%s value='%s'\n", it->id.c_str(), it->index,
                it->config.ToString().c_str(), it->file.c_str(),
                it->value->ToString(ANDROID_NAMESPACES).c_str());
    }
}

StringResource
convert_resource(const StringResource& s, const string& file, const Configuration& config,
                    int version, const string& versionString)
{
    return StringResource(s.pos, file, config, s.id, s.index, s.value ? s.value->Clone() : NULL,
            version, versionString, s.comment);
}

static bool
resource_has_contents(const StringResource& res)
{
    XMLNode* value = res.value;
    if (value == NULL) {
        return false;
    }
    string contents = value->ContentsToString(ANDROID_NAMESPACES);
    return contents != "";
}

ValuesFile*
merge_res_and_xliff(const ValuesFile* en_currentFile,
        const ValuesFile* xx_currentFile, const ValuesFile* xx_oldFile,
        const string& filename, const XLIFFFile* xliffFile)
{
    bool success = true;

    Configuration en_config = xliffFile->SourceConfig();
    Configuration xx_config = xliffFile->TargetConfig();
    string currentVersion = xliffFile->CurrentVersion();

    ValuesFile* result = new ValuesFile(xx_config);

    set<StringResource> en_cur = en_currentFile->GetStrings();
    set<StringResource> xx_cur = xx_currentFile->GetStrings();
    set<StringResource> xx_old = xx_oldFile->GetStrings();
    set<StringResource> xliff = xliffFile->GetStringResources();

    // for each string in en_current
    for (set<StringResource>::const_iterator en_c = en_cur.begin();
            en_c != en_cur.end(); en_c++) {
        set<StringResource>::const_iterator xx_c = find_id(xx_cur, en_c->id, en_c->index);
        set<StringResource>::const_iterator xx_o = find_id(xx_old, en_c->id, en_c->index);
        set<StringResource>::const_iterator xlf = find_in_xliff(xliff, en_c->file, en_c->id,
                                                        en_c->index, CURRENT_VERSION, xx_config);

        if (false) {
            printf("\nen_c: "); printit(en_cur, en_c);
            printf("xx_c: "); printit(xx_cur, xx_c);
            printf("xx_o: "); printit(xx_old, xx_o);
            printf("xlf:  "); printit(xliff, xlf);
        }

        // if it changed between xx_old and xx_current, use xx_current
        // (someone changed it by hand)
        if (xx_o != xx_old.end() && xx_c != xx_cur.end()) {
            string xx_o_value = xx_o->value->ToString(ANDROID_NAMESPACES);
            string xx_c_value = xx_c->value->ToString(ANDROID_NAMESPACES);
            if (xx_o_value != xx_c_value && xx_c_value != "") {
                StringResource r(convert_resource(*xx_c, filename, xx_config,
                                                    CURRENT_VERSION, currentVersion));
                if (resource_has_contents(r)) {
                    result->AddString(r);
                }
                continue;
            }
        }

        // if it is present in xliff, use that
        // (it just got translated)
        if (xlf != xliff.end() && xlf->value->ToString(ANDROID_NAMESPACES) != "") {
            StringResource r(convert_resource(*xlf, filename, xx_config,
                                                CURRENT_VERSION, currentVersion));
            if (resource_has_contents(r)) {
                result->AddString(r);
            }
        }

        // if it is present in xx_current, use that
        // (it was already translated, and not retranslated)
        // don't filter out empty strings if they were added by hand, the above code just
        // guarantees that this tool never adds an empty one.
        if (xx_c != xx_cur.end()) {
            StringResource r(convert_resource(*xx_c, filename, xx_config,
                                                CURRENT_VERSION, currentVersion));
            result->AddString(r);
        }

        // othwerwise, leave it out.  The resource fall-through code will use the English
        // one at runtime, and the xliff export code will pick it up for translation next time.
    }

    if (success) {
        return result;
    } else {
        delete result;
        return NULL;
    }
}


struct MergedFile {
    XLIFFFile* xliff;
    string xliffFilename;
    string original;
    string translated;
    ValuesFile* en_current;
    ValuesFile* xx_current;
    ValuesFile* xx_old;
    ValuesFile* xx_new;
    string xx_new_text;
    string xx_new_filename;
    bool new_file;
    bool deleted_file;

    MergedFile();
    MergedFile(const MergedFile&);
};

struct compare_filenames {
    bool operator()(const MergedFile& lhs, const MergedFile& rhs) const
    {
        return lhs.original < rhs.original;
    }
};

MergedFile::MergedFile()
    :xliff(NULL),
     xliffFilename(),
     original(),
     translated(),
     en_current(NULL),
     xx_current(NULL),
     xx_old(NULL),
     xx_new(NULL),
     xx_new_text(),
     xx_new_filename(),
     new_file(false),
     deleted_file(false)
{
}

MergedFile::MergedFile(const MergedFile& that)
    :xliff(that.xliff),
     xliffFilename(that.xliffFilename),
     original(that.original),
     translated(that.translated),
     en_current(that.en_current),
     xx_current(that.xx_current),
     xx_old(that.xx_old),
     xx_new(that.xx_new),
     xx_new_text(that.xx_new_text),
     xx_new_filename(that.xx_new_filename),
     new_file(that.new_file),
     deleted_file(that.deleted_file)
{
}


typedef set<MergedFile, compare_filenames> MergedFileSet;

int
do_merge(const vector<string>& xliffFilenames)
{
    int err = 0;
    MergedFileSet files;

    printf("\rPreparing..."); fflush(stdout);
    string currentChange = Perforce::GetCurrentChange(true);

    // for each xliff, make a MergedFile record and do a little error checking
    for (vector<string>::const_iterator xliffFilename=xliffFilenames.begin();
            xliffFilename!=xliffFilenames.end(); xliffFilename++) {
        XLIFFFile* xliff = XLIFFFile::Parse(*xliffFilename);
        if (xliff == NULL) {
            fprintf(stderr, "localize import: unable to read file %s\n", xliffFilename->c_str());
            err = 1;
            continue;
        }

        set<string> xf = xliff->Files();
        for (set<string>::const_iterator f=xf.begin(); f!=xf.end(); f++) {
            MergedFile mf;
            mf.xliff = xliff;
            mf.xliffFilename = *xliffFilename;
            mf.original = *f;
            mf.translated = translated_file_name(mf.original, xliff->TargetConfig().locale);
            log_printf("mf.translated=%s mf.original=%s locale=%s\n", mf.translated.c_str(),
                    mf.original.c_str(), xliff->TargetConfig().locale.c_str());

            if (files.find(mf) != files.end()) {
                fprintf(stderr, "%s: duplicate string resources for file %s\n",
                        xliffFilename->c_str(), f->c_str());
                fprintf(stderr, "%s: previously defined here.\n",
                        files.find(mf)->xliffFilename.c_str());
                err = 1;
                continue;
            }
            files.insert(mf);
        }
    }

    size_t deletedFileCount = 0;
    size_t J = files.size() * 3;
    size_t j = 1;
    // Read all of the files from perforce.
    for (MergedFileSet::iterator mf = files.begin(); mf != files.end(); mf++) {
        MergedFile* file = const_cast<MergedFile*>(&(*mf));
        // file->en_current
        print_file_status(j++, J);
        file->en_current = get_values_file(file->original, file->xliff->SourceConfig(),
                                            CURRENT_VERSION, currentChange, true);
        if (file->en_current == NULL) {
            // deleted file
            file->deleted_file = true;
            deletedFileCount++;
            continue;
        }

        // file->xx_current;
        print_file_status(j++, J);
        file->xx_current = get_values_file(file->translated, file->xliff->TargetConfig(),
                                            CURRENT_VERSION, currentChange, false);
        if (file->xx_current == NULL) {
            file->xx_current = new ValuesFile(file->xliff->TargetConfig());
            file->new_file = true;
        }

        // file->xx_old (note that the xliff's current version is our old version, because that
        // was the current version when it was exported)
        print_file_status(j++, J);
        file->xx_old = get_values_file(file->translated, file->xliff->TargetConfig(),
                                            OLD_VERSION, file->xliff->CurrentVersion(), false);
        if (file->xx_old == NULL) {
            file->xx_old = new ValuesFile(file->xliff->TargetConfig());
            file->new_file = true;
        }
    }

    // merge them
    for (MergedFileSet::iterator mf = files.begin(); mf != files.end(); mf++) {
        MergedFile* file = const_cast<MergedFile*>(&(*mf));
        if (file->deleted_file) {
            continue;
        }
        file->xx_new = merge_res_and_xliff(file->en_current, file->xx_current, file->xx_old,
                                            file->original, file->xliff);
    }

    // now is a good time to stop if there was an error
    if (err != 0) {
        return err;
    }

    // locate the files
    j = 1;
    for (MergedFileSet::iterator mf = files.begin(); mf != files.end(); mf++) {
        MergedFile* file = const_cast<MergedFile*>(&(*mf));
        print_file_status(j++, J, "Locating");

        file->xx_new_filename = Perforce::Where(file->translated, true);
        if (file->xx_new_filename == "") {
            fprintf(stderr, "\nWas not able to determine the location of depot file %s\n",
                    file->translated.c_str());
            err = 1;
        }
    }

    if (err != 0) {
        return err;
    }

    // p4 edit the files
    // only do this if it changed - no need to submit files that haven't changed meaningfully
    vector<string> filesToEdit;
    vector<string> filesToAdd;
    vector<string> filesToDelete;
    for (MergedFileSet::iterator mf = files.begin(); mf != files.end(); mf++) {
        MergedFile* file = const_cast<MergedFile*>(&(*mf));
        if (file->deleted_file) {
            filesToDelete.push_back(file->xx_new_filename);
            continue;
        }
        string xx_current_text = file->xx_current->ToString();
        string xx_new_text = file->xx_new->ToString();
        if (xx_current_text != xx_new_text) {
            if (file->xx_new->GetStrings().size() == 0) {
                file->deleted_file = true;
                filesToDelete.push_back(file->xx_new_filename);
            } else {
                file->xx_new_text = xx_new_text;
                if (file->new_file) {
                    filesToAdd.push_back(file->xx_new_filename);
                } else {
                    filesToEdit.push_back(file->xx_new_filename);
                }
            }
        }
    }
    if (filesToAdd.size() == 0 && filesToEdit.size() == 0 && deletedFileCount == 0) {
        printf("\nAll of the files are the same.  Nothing to change.\n");
        return 0;
    }
    if (filesToEdit.size() > 0) {
        printf("\np4 editing files...\n");
        if (0 != Perforce::EditFiles(filesToEdit, true)) {
            return 1;
        }
    }


    printf("\n");

    for (MergedFileSet::iterator mf = files.begin(); mf != files.end(); mf++) {
        MergedFile* file = const_cast<MergedFile*>(&(*mf));
        if (file->deleted_file) {
            continue;
        }
        if (file->xx_new_text != "" && file->xx_new_filename != "") {
            if (0 != write_to_file(file->xx_new_filename, file->xx_new_text)) {
                err = 1;
            }
        }
    }

    if (err != 0) {
        return err;
    }

    if (filesToAdd.size() > 0) {
        printf("p4 adding %zd new files...\n", filesToAdd.size());
        err = Perforce::AddFiles(filesToAdd, true);
    }

    if (filesToDelete.size() > 0) {
        printf("p4 deleting %zd removed files...\n", filesToDelete.size());
        err = Perforce::DeleteFiles(filesToDelete, true);
    }

    if (err != 0) {
        return err;
    }

    printf("\n"
           "Theoretically, this merge was successfull.  Next you should\n"
           "review the diffs, get a code review, and submit it.  Enjoy.\n\n");
    return 0;
}

