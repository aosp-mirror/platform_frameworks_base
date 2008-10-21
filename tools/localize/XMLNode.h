#ifndef XMLNODE_H
#define XMLNODE_H

#include <string>

using namespace std;

struct XMLAttribute
{
    string ns;
    string name;
    string value;

    static string Find(const vector<XMLAttribute>& list,
                                const string& ns, const string& name, const string& def);
};


#endif // XMLNODE_H
