#ifndef GENERATE_JAVA_H
#define GENERATE_JAVA_H

#include "aidl_language.h"

#include <string>

using namespace std;

int generate_java(const string& filename, const string& originalSrc,
                interface_type* iface);

#endif // GENERATE_JAVA_H

