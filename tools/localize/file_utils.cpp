#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include "file_utils.h"
#include "Perforce.h"
#include <utils/String8.h>
#include <sys/fcntl.h>
#include <sys/stat.h>
#include <errno.h>
#include <string.h>
#include <stdlib.h>
#include <cstdio>
#include "log.h"

using namespace android;
using namespace std;

static string
parent_dir(const string& path)
{
    return string(String8(path.c_str()).getPathDir().string());
}

static int
mkdirs(const char* last)
{
    String8 dest;
    const char* s = last-1;
    int err;
    do {
        s++;
        if (s > last && (*s == '.' || *s == 0)) {
            String8 part(last, s-last);
            dest.appendPath(part);
#ifdef HAVE_MS_C_RUNTIME
            err = _mkdir(dest.string());
#else                    
            err = mkdir(dest.string(), S_IRUSR|S_IWUSR|S_IXUSR|S_IRGRP|S_IXGRP);
#endif                    
            if (err != 0) {
                return err;
            }
            last = s+1;
        }
    } while (*s);
    return 0;
}

string
translated_file_name(const string& file, const string& locale)
{
    const char* str = file.c_str();
    const char* p = str + file.length();
    const char* rest = NULL;
    const char* values = p;

    while (p > str) {
        p--;
        if (*p == '/') {
            rest = values;
            values = p;
            if (0 == strncmp("values", values+1, rest-values-1)) {
                break;
            }
        }
    }
    values++;

    string result(str, values-str);
    result.append(values, rest-values);

    string language, region;
    if (locale == "") {
        language = "";
        region = "";
    }
    else if (!split_locale(locale, &language, &region)) {
        return "";
    }

    if (language != "") {
        result += '-';
        result += language;
    }
    if (region != "") {
        result += "-r";
        result += region;
    }

    result += rest;

    return result;
}

ValuesFile*
get_values_file(const string& filename, const Configuration& configuration,
                int version, const string& versionString, bool printOnFailure)
{
    int err;
    string text;

    log_printf("get_values_file filename=%s\n", filename.c_str());
    err = Perforce::GetFile(filename, versionString, &text, printOnFailure);
    if (err != 0 || text == "") {
        return NULL;
    }

    ValuesFile* result = ValuesFile::ParseString(filename, text, configuration, version,
                                                    versionString);
    if (result == NULL) {
        fprintf(stderr, "unable to parse file: %s\n", filename.c_str());
        exit(1);
    }
    return result;
}

ValuesFile*
get_local_values_file(const string& filename, const Configuration& configuration,
                int version, const string& versionString, bool printOnFailure)
{
    int err;
    string text;
    char buf[2049];
    int fd;
    ssize_t amt;
    
    fd = open(filename.c_str(), O_RDONLY);
    if (fd == -1) {
        fprintf(stderr, "unable to open file: %s\n", filename.c_str());
        return NULL;
    }

    while ((amt = read(fd, buf, sizeof(buf)-1)) > 0) {
        text.append(buf, amt);
    }

    close(fd);
    
    if (text == "") {
        return NULL;
    }
        
    ValuesFile* result = ValuesFile::ParseString(filename, text, configuration, version,
                                                    versionString);
    if (result == NULL) {
        fprintf(stderr, "unable to parse file: %s\n", filename.c_str());
        exit(1);
    }
    return result;
}

void
print_file_status(size_t j, size_t J, const string& message)
{
    printf("\r%s file %zd of %zd...", message.c_str(), j, J);
    fflush(stdout);
}

int
write_to_file(const string& filename, const string& text)
{
    mkdirs(parent_dir(filename).c_str());
    int fd = open(filename.c_str(), O_RDWR | O_CREAT | O_TRUNC, 0666);
    if (fd < 0) {
        fprintf(stderr, "unable to open file for write (%s): %s\n", strerror(errno),
                filename.c_str());
        return -1;
    }

    ssize_t amt = write(fd, text.c_str(), text.length());

    close(fd);

    if (amt < 0) {
        return amt;
    }
    return amt == (ssize_t)text.length() ? 0 : -1;
}


