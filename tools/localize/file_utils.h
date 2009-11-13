#ifndef FILE_UTILS_H
#define FILE_UTILS_H

#include "ValuesFile.h"
#include "Configuration.h"
#include <string>
#include <cstdio>

using namespace std;

string translated_file_name(const string& file, const string& locale);

ValuesFile* get_values_file(const string& filename, const Configuration& configuration,
                int version, const string& versionString, bool printOnFailure);
ValuesFile* get_local_values_file(const string& filename, const Configuration& configuration,
                int version, const string& versionString, bool printOnFailure);

void print_file_status(size_t j, size_t J, const string& message = "Reading");
int write_to_file(const string& filename, const string& text);


#endif // FILE_UTILS_H
