#ifndef HOST_PSEUDOLOCALIZE_H
#define HOST_PSEUDOLOCALIZE_H

#include "StringPool.h"

#include <string>

String16 pseudolocalize_string(const String16& source);
// Surrounds every word in the sentance with specific characters that makes
// the word directionality RTL.
String16 pseudobidi_string(const String16& source);
// Generates expansion string based on the specified lenght.
// Generated string could not be shorter that length, but it could be slightly
// longer.
String16 pseudo_generate_expansion(const unsigned int length);

#endif // HOST_PSEUDOLOCALIZE_H

