#include "Configuration.h"
#include <string.h>

int
Configuration::Compare(const Configuration& that) const
{
    int n;

    n = locale.compare(that.locale);
    if (n != 0) return n;

    n = vendor.compare(that.vendor);
    if (n != 0) return n;

    n = orientation.compare(that.orientation);
    if (n != 0) return n;

    n = density.compare(that.density);
    if (n != 0) return n;

    n = touchscreen.compare(that.touchscreen);
    if (n != 0) return n;

    n = keyboard.compare(that.keyboard);
    if (n != 0) return n;

    n = navigation.compare(that.navigation);
    if (n != 0) return n;

    n = screenSize.compare(that.screenSize);
    if (n != 0) return n;

    return 0;
}

string
Configuration::ToString() const
{
    string s;
    if (locale.length() > 0) {
        if (s.length() > 0) {
            s += "-";
        }
        s += locale;
    }
    return s;
}

bool
split_locale(const string& in, string* language, string* region)
{
    const int len = in.length();
    if (len == 2) {
        if (isalpha(in[0]) && isalpha(in[1])) {
            *language = in;
            region->clear();
            return true;
        } else {
            return false;
        }
    }
    else if (len == 5) {
        if (isalpha(in[0]) && isalpha(in[1]) && (in[2] == '_' || in[2] == '-')
                && isalpha(in[3]) && isalpha(in[4])) {
            language->assign(in.c_str(), 2);
            region->assign(in.c_str()+3, 2);
            return true;
        } else {
            return false;
        }
    }
    else {
        return false;
    }
}

