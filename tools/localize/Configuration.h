#ifndef CONFIGURATION_H
#define CONFIGURATION_H

#include <string>

using namespace std;

struct Configuration
{
    string locale;
    string vendor;
    string orientation;
    string density;
    string touchscreen;
    string keyboard;
    string navigation;
    string screenSize;

    // Compare two configurations
    int Compare(const Configuration& that) const;

    inline bool operator<(const Configuration& that) const { return Compare(that) < 0; }
    inline bool operator<=(const Configuration& that) const { return Compare(that) <= 0; }
    inline bool operator==(const Configuration& that) const { return Compare(that) == 0; }
    inline bool operator!=(const Configuration& that) const { return Compare(that) != 0; }
    inline bool operator>=(const Configuration& that) const { return Compare(that) >= 0; }
    inline bool operator>(const Configuration& that) const { return Compare(that) > 0; }

    // Parse a directory name, like "values-en-rUS".  Return the first segment in resType.
    bool ParseDiectoryName(const string& dir, string* resType);

    string ToString() const;
};

bool split_locale(const string& in, string* language, string* region);


#endif // CONFIGURATION_H
