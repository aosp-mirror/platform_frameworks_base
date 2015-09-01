#ifndef AIDL_GENERATE_JAVA_H_
#define AIDL_GENERATE_JAVA_H_

#include "aidl_language.h"
#include "AST.h"

#include <string>

using namespace std;

int generate_java(const string& filename, const string& originalSrc,
                interface_type* iface);

Class* generate_binder_interface_class(const interface_type* iface);
Class* generate_rpc_interface_class(const interface_type* iface);

string gather_comments(extra_text_type* extra);
string append(const char* a, const char* b);

class VariableFactory
{
public:
    VariableFactory(const string& base); // base must be short
    Variable* Get(Type* type);
    Variable* Get(int index);
private:
    vector<Variable*> m_vars;
    string m_base;
    int m_index;
};

#endif // AIDL_GENERATE_JAVA_H_

