#ifndef AIDL_TYPE_H
#define AIDL_TYPE_H

#include "AST.h"
#include <string>
#include <vector>

using namespace std;

class Type
{
public:
    // kinds
    enum {
        BUILT_IN,
        PARCELABLE,
        INTERFACE,
        GENERATED
    };
    
    // WriteToParcel flags
    enum {
        PARCELABLE_WRITE_RETURN_VALUE = 0x0001
    };

                    Type(const string& name, int kind, bool canWriteToParcel,
                            bool canBeOut);
                    Type(const string& package, const string& name,
                            int kind, bool canWriteToParcel, bool canBeOut,
                            const string& declFile = "", int declLine = -1);
    virtual         ~Type();

    inline string   Package() const             { return m_package; }
    inline string   Name() const                { return m_name; }
    inline string   QualifiedName() const       { return m_qualifiedName; }
    inline int      Kind() const                { return m_kind; }
    inline string   DeclFile() const            { return m_declFile; }
    inline int      DeclLine() const            { return m_declLine; }
    inline bool     CanBeMarshalled() const     { return m_canWriteToParcel; }
    inline bool     CanBeOutParameter() const   { return m_canBeOut; }
    
    virtual string  ImportType() const;
    virtual string  CreatorName() const;
    virtual string  InstantiableName() const;

    virtual void    WriteToParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, int flags);
    virtual void    CreateFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);
    virtual void    ReadFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);

    virtual bool    CanBeArray() const;

    virtual void    WriteArrayToParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, int flags);
    virtual void    CreateArrayFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);
    virtual void    ReadArrayFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);

    virtual void    WriteToRpcData(StatementBlock* addTo, Expression* k, Variable* v,
                                    Variable* data, int flags);
    virtual void    CreateFromRpcData(StatementBlock* addTo, Expression* k, Variable* v,
                                    Variable* data, Variable** cl);
    virtual void    ReadFromRpcData(StatementBlock* addTo, Expression* k, Variable* v,
                                    Variable* data, Variable** cl);


protected:
    void SetQualifiedName(const string& qualified);
    Expression* BuildWriteToParcelFlags(int flags);

private:
    Type();
    Type(const Type&);

    string m_package;
    string m_name;
    string m_qualifiedName;
    string m_declFile;
    int m_declLine;
    int m_kind;
    bool m_canWriteToParcel;
    bool m_canBeOut;
};

class BasicType : public Type
{
public:
                    BasicType(const string& name, const string& marshallMethod,
                              const string& unmarshallMethod,
                              const string& writeArray,
                              const string& createArray,
                              const string& readArray);

    virtual void    WriteToParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, int flags);
    virtual void    CreateFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);

    virtual bool    CanBeArray() const;

    virtual void    WriteArrayToParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, int flags);
    virtual void    CreateArrayFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);
    virtual void    ReadArrayFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);

private:
    string m_marshallMethod;
    string m_unmarshallMethod;
    string m_writeArrayMethod;
    string m_createArrayMethod;
    string m_readArrayMethod;
};

class BooleanType : public Type
{
public:
                    BooleanType();

    virtual void    WriteToParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, int flags);
    virtual void    CreateFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);

    virtual bool    CanBeArray() const;

    virtual void    WriteArrayToParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, int flags);
    virtual void    CreateArrayFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);
    virtual void    ReadArrayFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);
};

class CharType : public Type
{
public:
                    CharType();

    virtual void    WriteToParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, int flags);
    virtual void    CreateFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);

    virtual bool    CanBeArray() const;

    virtual void    WriteArrayToParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, int flags);
    virtual void    CreateArrayFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);
    virtual void    ReadArrayFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);
};


class StringType : public Type
{
public:
                    StringType();

    virtual string  CreatorName() const;

    virtual void    WriteToParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, int flags);
    virtual void    CreateFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);

    virtual bool    CanBeArray() const;

    virtual void    WriteArrayToParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, int flags);
    virtual void    CreateArrayFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);
    virtual void    ReadArrayFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);

    virtual void    WriteToRpcData(StatementBlock* addTo, Expression* k, Variable* v,
                                    Variable* data, int flags);
    virtual void    CreateFromRpcData(StatementBlock* addTo, Expression* k, Variable* v,
                                    Variable* data, Variable** cl);
};

class CharSequenceType : public Type
{
public:
                    CharSequenceType();

    virtual string  CreatorName() const;

    virtual void    WriteToParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, int flags);
    virtual void    CreateFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);
};

class RemoteExceptionType : public Type
{
public:
                    RemoteExceptionType();

    virtual void    WriteToParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, int flags);
    virtual void    CreateFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);
};

class RuntimeExceptionType : public Type
{
public:
                    RuntimeExceptionType();

    virtual void    WriteToParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, int flags);
    virtual void    CreateFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);
};

class IBinderType : public Type
{
public:
                    IBinderType();

    virtual void    WriteToParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, int flags);
    virtual void    CreateFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);

    virtual void    WriteArrayToParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, int flags);
    virtual void    CreateArrayFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);
    virtual void    ReadArrayFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);
};

class IInterfaceType : public Type
{
public:
                    IInterfaceType();

    virtual void    WriteToParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, int flags);
    virtual void    CreateFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);
};

class BinderType : public Type
{
public:
                    BinderType();

    virtual void    WriteToParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, int flags);
    virtual void    CreateFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);
};

class BinderProxyType : public Type
{
public:
                    BinderProxyType();

    virtual void    WriteToParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, int flags);
    virtual void    CreateFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);
};

class ParcelType : public Type
{
public:
                    ParcelType();

    virtual void    WriteToParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, int flags);
    virtual void    CreateFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);
};

class ParcelableInterfaceType : public Type
{
public:
                    ParcelableInterfaceType();

    virtual void    WriteToParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, int flags);
    virtual void    CreateFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);
};

class MapType : public Type
{
public:
                    MapType();

    virtual void    WriteToParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, int flags);
    virtual void    CreateFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);
    virtual void    ReadFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);
};

class ListType : public Type
{
public:
                    ListType();

    virtual string  InstantiableName() const;

    virtual void    WriteToParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, int flags);
    virtual void    CreateFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);
    virtual void    ReadFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);
};

class ParcelableType : public Type
{
public:
                    ParcelableType(const string& package, const string& name,
                            bool builtIn, const string& declFile, int declLine);

    virtual string  CreatorName() const;

    virtual void    WriteToParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, int flags);
    virtual void    CreateFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);
    virtual void    ReadFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);

    virtual bool    CanBeArray() const;

    virtual void    WriteArrayToParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, int flags);
    virtual void    CreateArrayFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);
    virtual void    ReadArrayFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);
};

class InterfaceType : public Type
{
public:
                    InterfaceType(const string& package, const string& name,
                            bool builtIn, bool oneway,
                            const string& declFile, int declLine);

    bool            OneWay() const;
    
    virtual void    WriteToParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, int flags);
    virtual void    CreateFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);
                                    
private:
    bool m_oneway;
};


class GenericType : public Type
{
public:
                    GenericType(const string& package, const string& name,
                                 const vector<Type*>& args);

    string          GenericArguments() const;

    virtual string  ImportType() const;

    virtual void    WriteToParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, int flags);
    virtual void    CreateFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);
    virtual void    ReadFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);

private:
    string m_genericArguments;
    string m_importName;
    vector<Type*> m_args;
};


class GenericListType : public GenericType
{
public:
                    GenericListType(const string& package, const string& name,
                                 const vector<Type*>& args);

    virtual string  CreatorName() const;
    virtual string  InstantiableName() const;

    virtual void    WriteToParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, int flags);
    virtual void    CreateFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);
    virtual void    ReadFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable** cl);

private:
    string m_creator;
};

class ClassLoaderType : public Type
{
public:
                    ClassLoaderType();
};

class Namespace
{
public:
            Namespace();
            ~Namespace();
    void    Add(Type* type);

    // args is the number of template types (what is this called?)
    void    AddGenericType(const string& package, const string& name, int args);

    // lookup a specific class name
    Type*   Find(const string& name) const;
    Type*   Find(const char* package, const char* name) const;
    
    // try to search by either a full name or a partial name
    Type*   Search(const string& name);

    void    Dump() const;

private:
    struct Generic {
        string package;
        string name;
        string qualified;
        int args;
    };

    const Generic* search_generic(const string& name) const;

    vector<Type*> m_types;
    vector<Generic> m_generics;
};

extern Namespace NAMES;

extern Type* VOID_TYPE;
extern Type* BOOLEAN_TYPE;
extern Type* BYTE_TYPE;
extern Type* CHAR_TYPE;
extern Type* INT_TYPE;
extern Type* LONG_TYPE;
extern Type* FLOAT_TYPE;
extern Type* DOUBLE_TYPE;
extern Type* OBJECT_TYPE;
extern Type* STRING_TYPE;
extern Type* CHAR_SEQUENCE_TYPE;
extern Type* TEXT_UTILS_TYPE;
extern Type* REMOTE_EXCEPTION_TYPE;
extern Type* RUNTIME_EXCEPTION_TYPE;
extern Type* IBINDER_TYPE;
extern Type* IINTERFACE_TYPE;
extern Type* BINDER_NATIVE_TYPE;
extern Type* BINDER_PROXY_TYPE;
extern Type* PARCEL_TYPE;
extern Type* PARCELABLE_INTERFACE_TYPE;

extern Type* CONTEXT_TYPE;

extern Type* RPC_SERVICE_BASE_TYPE;
extern Type* RPC_DATA_TYPE;
extern Type* RPC_BROKER_TYPE;
extern Type* RPC_ENDPOINT_INFO_TYPE;
extern Type* RPC_RESULT_HANDLER_TYPE;
extern Type* RPC_ERROR_TYPE;
extern Type* RPC_ERROR_LISTENER_TYPE;

extern Expression* NULL_VALUE;
extern Expression* THIS_VALUE;
extern Expression* SUPER_VALUE;
extern Expression* TRUE_VALUE;
extern Expression* FALSE_VALUE;

void register_base_types();

#endif // AIDL_TYPE_H
