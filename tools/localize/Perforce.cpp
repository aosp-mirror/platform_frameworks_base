#include "Perforce.h"
#include "log.h"
#include <string.h>
#include <cstdio>
#include <stdlib.h>
#include <sstream>
#include <sys/types.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <cstdio>

using namespace std;

extern char** environ;

int
Perforce::RunCommand(const string& cmd, string* result, bool printOnFailure)
{
    int err;
    int outPipe[2];
    int errPipe[2];
    pid_t pid;

    log_printf("Perforce::RunCommand: %s\n", cmd.c_str());

    err = pipe(outPipe);
    err |= pipe(errPipe);
    if (err == -1) {
        printf("couldn't create pipe. exiting.\n");
        exit(1);
        return -1;
    }

    pid = fork();
    if (pid == -1) {
        printf("couldn't fork. eixiting\n");
        exit(1);
        return -1;
    }
    else if (pid == 0) {
        char const* args[] = {
            "/bin/sh",
            "-c",
            cmd.c_str(),
            NULL
        };
        close(outPipe[0]);
        close(errPipe[0]);
        dup2(outPipe[1], 1);
        dup2(errPipe[1], 2);
        execve(args[0], (char* const*)args, environ);
        // done
    }

    close(outPipe[1]);
    close(errPipe[1]);

    result->clear();

    char buf[1024];

    // stdout
    while (true) {
        size_t amt = read(outPipe[0], buf, sizeof(buf));
        result->append(buf, amt);
        if (amt <= 0) {
            break;
        }
    }

    // stderr -- the messages are short so it ought to just fit in the buffer
    string error;
    while (true) {
        size_t amt = read(errPipe[0], buf, sizeof(buf));
        error.append(buf, amt);
        if (amt <= 0) {
            break;
        }
    }

    close(outPipe[0]);
    close(errPipe[0]);

    waitpid(pid, &err, 0);
    if (WIFEXITED(err)) {
        err = WEXITSTATUS(err);
    } else {
        err = -1;
    }
    if (err != 0 && printOnFailure) {
        write(2, error.c_str(), error.length());
    }
    return err;
}

int
Perforce::GetResourceFileNames(const string& version, const string& base,
                                const vector<string>& apps, vector<string>* results,
                                bool printOnFailure)
{
    int err;
    string text;
    stringstream cmd;

    cmd << "p4 files";

    const size_t I = apps.size();
    for (size_t i=0; i<I; i++) {
        cmd << " \"" << base << '/' << apps[i] << "/res/values/strings.xml@" << version << '"';
    }

    err = RunCommand(cmd.str(), &text, printOnFailure);

    const char* str = text.c_str();
    while (*str) {
        const char* lineend = strchr(str, '\n');
        if (lineend == str) {
            str++;
            continue;
        }
        if (lineend-str > 1023) {
            fprintf(stderr, "line too long!\n");
            return 1;
        }

        string s(str, lineend-str);

        char filename[1024];
        char edit[1024];
        int count = sscanf(str, "%[^#]#%*d - %s change %*d %*[^\n]\n", filename, edit);

        if (count == 2 && 0 != strcmp("delete", edit)) {
            results->push_back(string(filename));
        }

        str = lineend + 1;
    }

    return err;
}

int
Perforce::GetFile(const string& file, const string& version, string* result,
        bool printOnFailure)
{
    stringstream cmd;
    cmd << "p4 print -q \"" << file << '@' << version << '"';
    return RunCommand(cmd.str(), result, printOnFailure);
}

string
Perforce::GetCurrentChange(bool printOnFailure)
{
    int err;
    string text;

    err = RunCommand("p4 changes -m 1 \\#have", &text, printOnFailure);
    if (err != 0) {
        return "";
    }

    long long n;
    int count = sscanf(text.c_str(), "Change %lld on", &n);
    if (count != 1) {
        return "";
    }

    char result[100];
    sprintf(result, "%lld", n);

    return string(result);
}

static int
do_files(const string& op, const vector<string>& files, bool printOnFailure)
{
    string text;
    stringstream cmd;

    cmd << "p4 " << op;

    const size_t I = files.size();
    for (size_t i=0; i<I; i++) {
        cmd << " \"" << files[i] << "\"";
    }

    return Perforce::RunCommand(cmd.str(), &text, printOnFailure);
}

int
Perforce::EditFiles(const vector<string>& files, bool printOnFailure)
{
    return do_files("edit", files, printOnFailure);
}

int
Perforce::AddFiles(const vector<string>& files, bool printOnFailure)
{
    return do_files("add", files, printOnFailure);
}

int
Perforce::DeleteFiles(const vector<string>& files, bool printOnFailure)
{
    return do_files("delete", files, printOnFailure);
}

string
Perforce::Where(const string& depotPath, bool printOnFailure)
{
    int err;
    string text;
    string cmd = "p4 where ";
    cmd += depotPath;

    err = RunCommand(cmd, &text, printOnFailure);
    if (err != 0) {
        return "";
    }

    size_t index = text.find(' ');
    if (index == text.npos) {
        return "";
    }
    index = text.find(' ', index+1)+1;
    if (index == text.npos) {
        return "";
    }

    return text.substr(index, text.length()-index-1);
}

