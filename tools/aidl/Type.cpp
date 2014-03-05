#include "Type.h"

#include <sys/types.h>

Namespace NAMES;

Type* VOID_TYPE;
Type* BOOLEAN_TYPE;
Type* BYTE_TYPE;
Type* CHAR_TYPE;
Type* INT_TYPE;
Type* LONG_TYPE;
Type* FLOAT_TYPE;
Type* DOUBLE_TYPE;
Type* STRING_TYPE;
Type* OBJECT_TYPE;
Type* CHAR_SEQUENCE_TYPE;
Type* TEXT_UTILS_TYPE;
Type* REMOTE_EXCEPTION_TYPE;
Type* RUNTIME_EXCEPTION_TYPE;
Type* IBINDER_TYPE;
Type* IINTERFACE_TYPE;
Type* BINDER_NATIVE_TYPE;
Type* BINDER_PROXY_TYPE;
Type* PARCEL_TYPE;
Type* PARCELABLE_INTERFACE_TYPE;
Type* CONTEXT_TYPE;
Type* MAP_TYPE;
Type* LIST_TYPE;
Type* CLASSLOADER_TYPE;
Type* RPC_DATA_TYPE;
Type* RPC_ERROR_TYPE;
Type* EVENT_FAKE_TYPE;

Expression* NULL_VALUE;
Expression* THIS_VALUE;
Expression* SUPER_VALUE;
Expression* TRUE_VALUE;
Expression* FALSE_VALUE;

void
register_base_types()
{
    VOID_TYPE = new BasicType("void",
            "XXX", "XXX", "XXX", "XXX", "XXX",
            "XXX", "XXX", "XXX", "XXX", "XXX");
    NAMES.Add(VOID_TYPE);

    BOOLEAN_TYPE = new BooleanType();
    NAMES.Add(BOOLEAN_TYPE);

    BYTE_TYPE = new BasicType("byte",
            "writeByte", "readByte", "writeByteArray", "createByteArray", "readByteArray",
            "putByte", "getByte", "putByteArray", "createByteArray", "getByteArray");
    NAMES.Add(BYTE_TYPE);

    CHAR_TYPE = new CharType();
    NAMES.Add(CHAR_TYPE);

    INT_TYPE = new BasicType("int",
            "writeInt", "readInt", "writeIntArray", "createIntArray", "readIntArray",
            "putInteger", "getInteger", "putIntegerArray", "createIntegerArray", "getIntegerArray");
    NAMES.Add(INT_TYPE);

    LONG_TYPE = new BasicType("long",
            "writeLong", "readLong", "writeLongArray", "createLongArray", "readLongArray",
            "putLong", "getLong", "putLongArray", "createLongArray", "getLongArray");
    NAMES.Add(LONG_TYPE);

    FLOAT_TYPE = new BasicType("float",
            "writeFloat", "readFloat", "writeFloatArray", "createFloatArray", "readFloatArray",
            "putFloat", "getFloat", "putFloatArray", "createFloatArray", "getFloatArray");
    NAMES.Add(FLOAT_TYPE);

    DOUBLE_TYPE = new BasicType("double",
            "writeDouble", "readDouble", "writeDoubleArray", "createDoubleArray", "readDoubleArray",
            "putDouble", "getDouble", "putDoubleArray", "createDoubleArray", "getDoubleArray");
    NAMES.Add(DOUBLE_TYPE);

    STRING_TYPE = new StringType();
    NAMES.Add(STRING_TYPE);

    OBJECT_TYPE = new Type("java.lang", "Object", Type::BUILT_IN, false, false, false);
    NAMES.Add(OBJECT_TYPE);

    CHAR_SEQUENCE_TYPE = new CharSequenceType();
    NAMES.Add(CHAR_SEQUENCE_TYPE);

    MAP_TYPE = new MapType();
    NAMES.Add(MAP_TYPE);

    LIST_TYPE = new ListType();
    NAMES.Add(LIST_TYPE);

    TEXT_UTILS_TYPE = new Type("android.text", "TextUtils", Type::BUILT_IN, false, false, false);
    NAMES.Add(TEXT_UTILS_TYPE);

    REMOTE_EXCEPTION_TYPE = new RemoteExceptionType();
    NAMES.Add(REMOTE_EXCEPTION_TYPE);

    RUNTIME_EXCEPTION_TYPE = new RuntimeExceptionType();
    NAMES.Add(RUNTIME_EXCEPTION_TYPE);

    IBINDER_TYPE = new IBinderType();
    NAMES.Add(IBINDER_TYPE);

    IINTERFACE_TYPE = new IInterfaceType();
    NAMES.Add(IINTERFACE_TYPE);

    BINDER_NATIVE_TYPE = new BinderType();
    NAMES.Add(BINDER_NATIVE_TYPE);

    BINDER_PROXY_TYPE = new BinderProxyType();
    NAMES.Add(BINDER_PROXY_TYPE);

    PARCEL_TYPE = new ParcelType();
    NAMES.Add(PARCEL_TYPE);

    PARCELABLE_INTERFACE_TYPE = new ParcelableInterfaceType();
    NAMES.Add(PARCELABLE_INTERFACE_TYPE);

    CONTEXT_TYPE = new Type("android.content", "Context", Type::BUILT_IN, false, false, false);
    NAMES.Add(CONTEXT_TYPE);

    RPC_DATA_TYPE = new RpcDataType();
    NAMES.Add(RPC_DATA_TYPE);

    RPC_ERROR_TYPE = new UserDataType("android.support.place.rpc", "RpcError",
                                    true, __FILE__, __LINE__);
    NAMES.Add(RPC_ERROR_TYPE);

    EVENT_FAKE_TYPE = new Type("event", Type::BUILT_IN, false, false, false);
    NAMES.Add(EVENT_FAKE_TYPE);

    CLASSLOADER_TYPE = new ClassLoaderType();
    NAMES.Add(CLASSLOADER_TYPE);

    NULL_VALUE = new LiteralExpression("null");
    THIS_VALUE = new LiteralExpression("this");
    SUPER_VALUE = new LiteralExpression("super");
    TRUE_VALUE = new LiteralExpression("true");
    FALSE_VALUE = new LiteralExpression("false");

    NAMES.AddGenericType("java.util", "List", 1);
    NAMES.AddGenericType("java.util", "Map", 2);
}

static Type*
make_generic_type(const string& package, const string& name,
                    const vector<Type*>& args)
{
    if (package == "java.util" && name == "List") {
        return new GenericListType("java.util", "List", args);
    }
    return NULL;
    //return new GenericType(package, name, args);
}

// ================================================================

Type::Type(const string& name, int kind, bool canWriteToParcel, bool canWriteToRpcData,
        bool canBeOut)
    :m_package(),
     m_name(name),
     m_declFile(""),
     m_declLine(-1),
     m_kind(kind),
     m_canWriteToParcel(canWriteToParcel),
     m_canWriteToRpcData(canWriteToRpcData),
     m_canBeOut(canBeOut)
{
    m_qualifiedName = name;
}

Type::Type(const string& package, const string& name,
            int kind, bool canWriteToParcel, bool canWriteToRpcData,
            bool canBeOut, const string& declFile, int declLine)
    :m_package(package),
     m_name(name),
     m_declFile(declFile),
     m_declLine(declLine),
     m_kind(kind),
     m_canWriteToParcel(canWriteToParcel),
     m_canWriteToRpcData(canWriteToRpcData),
     m_canBeOut(canBeOut)
{
    if (package.length() > 0) {
        m_qualifiedName = package;
        m_qualifiedName += '.';
    }
    m_qualifiedName += name;
}

Type::~Type()
{
}

bool
Type::CanBeArray() const
{
    return false;
}

string
Type::ImportType() const
{
    return m_qualifiedName;
}

string
Type::CreatorName() const
{
    return "";
}

string
Type::RpcCreatorName() const
{
    return "";
}

string
Type::InstantiableName() const
{
    return QualifiedName();
}


void
Type::WriteToParcel(StatementBlock* addTo, Variable* v, Variable* parcel, int flags)
{
    fprintf(stderr, "aidl:internal error %s:%d qualifiedName=%sn",
            __FILE__, __LINE__, m_qualifiedName.c_str());
    addTo->Add(new LiteralExpression("/* WriteToParcel error "
                + m_qualifiedName + " */"));
}

void
Type::CreateFromParcel(StatementBlock* addTo, Variable* v, Variable* parcel, Variable**)
{
    fprintf(stderr, "aidl:internal error %s:%d qualifiedName=%s\n",
            __FILE__, __LINE__, m_qualifiedName.c_str());
    addTo->Add(new LiteralExpression("/* CreateFromParcel error "
                + m_qualifiedName + " */"));
}

void
Type::ReadFromParcel(StatementBlock* addTo, Variable* v, Variable* parcel, Variable**)
{
    fprintf(stderr, "aidl:internal error %s:%d qualifiedName=%s\n",
            __FILE__, __LINE__, m_qualifiedName.c_str());
    addTo->Add(new LiteralExpression("/* ReadFromParcel error "
                + m_qualifiedName + " */"));
}

void
Type::WriteArrayToParcel(StatementBlock* addTo, Variable* v, Variable* parcel, int flags)
{
    fprintf(stderr, "aidl:internal error %s:%d qualifiedName=%s\n",
            __FILE__, __LINE__, m_qualifiedName.c_str());
    addTo->Add(new LiteralExpression("/* WriteArrayToParcel error "
                + m_qualifiedName + " */"));
}

void
Type::CreateArrayFromParcel(StatementBlock* addTo, Variable* v,
                            Variable* parcel, Variable**)
{
    fprintf(stderr, "aidl:internal error %s:%d qualifiedName=%s\n",
            __FILE__, __LINE__, m_qualifiedName.c_str());
    addTo->Add(new LiteralExpression("/* CreateArrayFromParcel error "
                + m_qualifiedName + " */"));
}

void
Type::ReadArrayFromParcel(StatementBlock* addTo, Variable* v, Variable* parcel, Variable**)
{
    fprintf(stderr, "aidl:internal error %s:%d qualifiedName=%s\n",
            __FILE__, __LINE__, m_qualifiedName.c_str());
    addTo->Add(new LiteralExpression("/* ReadArrayFromParcel error "
                + m_qualifiedName + " */"));
}

void
Type::WriteToRpcData(StatementBlock* addTo, Expression* k, Variable* v,
        Variable* data, int flags)
{
    fprintf(stderr, "aidl:internal error %s:%d qualifiedName=%s\n",
            __FILE__, __LINE__, m_qualifiedName.c_str());
    addTo->Add(new LiteralExpression("/* WriteToRpcData error "
                + m_qualifiedName + " */"));
}

void
Type::CreateFromRpcData(StatementBlock* addTo, Expression* k, Variable* v, Variable* data,
        Variable** cl)
{
    fprintf(stderr, "aidl:internal error %s:%d qualifiedName=%s\n",
            __FILE__, __LINE__, m_qualifiedName.c_str());
    addTo->Add(new LiteralExpression("/* ReadFromRpcData error "
                + m_qualifiedName + " */"));
}

void
Type::SetQualifiedName(const string& qualified)
{
    m_qualifiedName = qualified;
}

Expression*
Type::BuildWriteToParcelFlags(int flags)
{
    if (flags == 0) {
        return new LiteralExpression("0");
    }
    if ((flags&PARCELABLE_WRITE_RETURN_VALUE) != 0) {
        return new FieldVariable(PARCELABLE_INTERFACE_TYPE,
                "PARCELABLE_WRITE_RETURN_VALUE");
    }
    return new LiteralExpression("0");
}

// ================================================================

BasicType::BasicType(const string& name, const string& marshallParcel,
          const string& unmarshallParcel, const string& writeArrayParcel,
          const string& createArrayParcel, const string& readArrayParcel,
          const string& marshallRpc, const string& unmarshallRpc,
          const string& writeArrayRpc, const string& createArrayRpc, const string& readArrayRpc)
    :Type(name, BUILT_IN, true, true, false),
     m_marshallParcel(marshallParcel),
     m_unmarshallParcel(unmarshallParcel),
     m_writeArrayParcel(writeArrayParcel),
     m_createArrayParcel(createArrayParcel),
     m_readArrayParcel(readArrayParcel),
     m_marshallRpc(marshallRpc),
     m_unmarshallRpc(unmarshallRpc),
     m_writeArrayRpc(writeArrayRpc),
     m_createArrayRpc(createArrayRpc),
     m_readArrayRpc(readArrayRpc)
{
}

void
BasicType::WriteToParcel(StatementBlock* addTo, Variable* v, Variable* parcel, int flags)
{
    addTo->Add(new MethodCall(parcel, m_marshallParcel, 1, v));
}

void
BasicType::CreateFromParcel(StatementBlock* addTo, Variable* v, Variable* parcel, Variable**)
{
    addTo->Add(new Assignment(v, new MethodCall(parcel, m_unmarshallParcel)));
}

bool
BasicType::CanBeArray() const
{
    return true;
}

void
BasicType::WriteArrayToParcel(StatementBlock* addTo, Variable* v, Variable* parcel, int flags)
{
    addTo->Add(new MethodCall(parcel, m_writeArrayParcel, 1, v));
}

void
BasicType::CreateArrayFromParcel(StatementBlock* addTo, Variable* v,
                            Variable* parcel, Variable**)
{
    addTo->Add(new Assignment(v, new MethodCall(parcel, m_createArrayParcel)));
}

void
BasicType::ReadArrayFromParcel(StatementBlock* addTo, Variable* v, Variable* parcel, Variable**)
{
    addTo->Add(new MethodCall(parcel, m_readArrayParcel, 1, v));
}

void
BasicType::WriteToRpcData(StatementBlock* addTo, Expression* k, Variable* v,
        Variable* data, int flags)
{
    addTo->Add(new MethodCall(data, m_marshallRpc, 2, k, v));
}

void
BasicType::CreateFromRpcData(StatementBlock* addTo, Expression* k, Variable* v, Variable* data,
        Variable** cl)
{
    addTo->Add(new Assignment(v, new MethodCall(data, m_unmarshallRpc, 1, k)));
}

// ================================================================

BooleanType::BooleanType()
    :Type("boolean", BUILT_IN, true, true, false)
{
}

void
BooleanType::WriteToParcel(StatementBlock* addTo, Variable* v, Variable* parcel, int flags)
{
    addTo->Add(new MethodCall(parcel, "writeInt", 1, 
                new Ternary(v, new LiteralExpression("1"),
                    new LiteralExpression("0"))));
}

void
BooleanType::CreateFromParcel(StatementBlock* addTo, Variable* v, Variable* parcel, Variable**)
{
    addTo->Add(new Assignment(v, new Comparison(new LiteralExpression("0"),
                    "!=", new MethodCall(parcel, "readInt"))));
}

bool
BooleanType::CanBeArray() const
{
    return true;
}

void
BooleanType::WriteArrayToParcel(StatementBlock* addTo, Variable* v, Variable* parcel, int flags)
{
    addTo->Add(new MethodCall(parcel, "writeBooleanArray", 1, v));
}

void
BooleanType::CreateArrayFromParcel(StatementBlock* addTo, Variable* v,
                            Variable* parcel, Variable**)
{
    addTo->Add(new Assignment(v, new MethodCall(parcel, "createBooleanArray")));
}

void
BooleanType::ReadArrayFromParcel(StatementBlock* addTo, Variable* v, Variable* parcel, Variable**)
{
    addTo->Add(new MethodCall(parcel, "readBooleanArray", 1, v));
}

void
BooleanType::WriteToRpcData(StatementBlock* addTo, Expression* k, Variable* v,
        Variable* data, int flags)
{
    addTo->Add(new MethodCall(data, "putBoolean", 2, k, v));
}

void
BooleanType::CreateFromRpcData(StatementBlock* addTo, Expression* k, Variable* v, Variable* data,
        Variable** cl)
{
    addTo->Add(new Assignment(v, new MethodCall(data, "getBoolean", 1, k)));
}

// ================================================================

CharType::CharType()
    :Type("char", BUILT_IN, true, true, false)
{
}

void
CharType::WriteToParcel(StatementBlock* addTo, Variable* v, Variable* parcel, int flags)
{
    addTo->Add(new MethodCall(parcel, "writeInt", 1, 
                    new Cast(INT_TYPE, v)));
}

void
CharType::CreateFromParcel(StatementBlock* addTo, Variable* v, Variable* parcel, Variable**)
{
    addTo->Add(new Assignment(v, new MethodCall(parcel, "readInt"), this));
}

bool
CharType::CanBeArray() const
{
    return true;
}

void
CharType::WriteArrayToParcel(StatementBlock* addTo, Variable* v, Variable* parcel, int flags)
{
    addTo->Add(new MethodCall(parcel, "writeCharArray", 1, v));
}

void
CharType::CreateArrayFromParcel(StatementBlock* addTo, Variable* v,
                            Variable* parcel, Variable**)
{
    addTo->Add(new Assignment(v, new MethodCall(parcel, "createCharArray")));
}

void
CharType::ReadArrayFromParcel(StatementBlock* addTo, Variable* v, Variable* parcel, Variable**)
{
    addTo->Add(new MethodCall(parcel, "readCharArray", 1, v));
}

void
CharType::WriteToRpcData(StatementBlock* addTo, Expression* k, Variable* v,
        Variable* data, int flags)
{
    addTo->Add(new MethodCall(data, "putChar", 2, k, v));
}

void
CharType::CreateFromRpcData(StatementBlock* addTo, Expression* k, Variable* v, Variable* data,
        Variable** cl)
{
    addTo->Add(new Assignment(v, new MethodCall(data, "getChar", 1, k)));
}

// ================================================================

StringType::StringType()
    :Type("java.lang", "String", BUILT_IN, true, true, false)
{
}

string
StringType::CreatorName() const
{
    return "android.os.Parcel.STRING_CREATOR";
}

void
StringType::WriteToParcel(StatementBlock* addTo, Variable* v, Variable* parcel, int flags)
{
    addTo->Add(new MethodCall(parcel, "writeString", 1, v));
}

void
StringType::CreateFromParcel(StatementBlock* addTo, Variable* v, Variable* parcel, Variable**)
{
    addTo->Add(new Assignment(v, new MethodCall(parcel, "readString")));
}

bool
StringType::CanBeArray() const
{
    return true;
}

void
StringType::WriteArrayToParcel(StatementBlock* addTo, Variable* v, Variable* parcel, int flags)
{
    addTo->Add(new MethodCall(parcel, "writeStringArray", 1, v));
}

void
StringType::CreateArrayFromParcel(StatementBlock* addTo, Variable* v,
                            Variable* parcel, Variable**)
{
    addTo->Add(new Assignment(v, new MethodCall(parcel, "createStringArray")));
}

void
StringType::ReadArrayFromParcel(StatementBlock* addTo, Variable* v, Variable* parcel, Variable**)
{
    addTo->Add(new MethodCall(parcel, "readStringArray", 1, v));
}

void
StringType::WriteToRpcData(StatementBlock* addTo, Expression* k, Variable* v,
        Variable* data, int flags)
{
    addTo->Add(new MethodCall(data, "putString", 2, k, v));
}

void
StringType::CreateFromRpcData(StatementBlock* addTo, Expression* k, Variable* v,
        Variable* data, Variable**)
{
    addTo->Add(new Assignment(v, new MethodCall(data, "getString", 1, k)));
}

// ================================================================

CharSequenceType::CharSequenceType()
    :Type("java.lang", "CharSequence", BUILT_IN, true, true, false)
{
}

string
CharSequenceType::CreatorName() const
{
    return "android.os.Parcel.STRING_CREATOR";
}

void
CharSequenceType::WriteToParcel(StatementBlock* addTo, Variable* v, Variable* parcel, int flags)
{
    // if (v != null) {
    //     parcel.writeInt(1);
    //     v.writeToParcel(parcel);
    // } else {
    //     parcel.writeInt(0);
    // }
    IfStatement* elsepart = new IfStatement();
    elsepart->statements->Add(new MethodCall(parcel, "writeInt", 1,
                                new LiteralExpression("0")));
    IfStatement* ifpart = new IfStatement;
    ifpart->expression = new Comparison(v, "!=", NULL_VALUE);
    ifpart->elseif = elsepart;
    ifpart->statements->Add(new MethodCall(parcel, "writeInt", 1,
                                new LiteralExpression("1")));
    ifpart->statements->Add(new MethodCall(TEXT_UTILS_TYPE, "writeToParcel",
                                3, v, parcel, BuildWriteToParcelFlags(flags)));

    addTo->Add(ifpart);
}

void
CharSequenceType::CreateFromParcel(StatementBlock* addTo, Variable* v,
                                Variable* parcel, Variable**)
{
    // if (0 != parcel.readInt()) {
    //     v = TextUtils.createFromParcel(parcel)
    // } else {
    //     v = null;
    // }
    IfStatement* elsepart = new IfStatement();
    elsepart->statements->Add(new Assignment(v, NULL_VALUE));

    IfStatement* ifpart = new IfStatement();
    ifpart->expression = new Comparison(new LiteralExpression("0"), "!=",
                new MethodCall(parcel, "readInt"));
    ifpart->elseif = elsepart;
    ifpart->statements->Add(new Assignment(v,
                new MethodCall(TEXT_UTILS_TYPE,
                                    "CHAR_SEQUENCE_CREATOR.createFromParcel", 1, parcel)));

    addTo->Add(ifpart);
}


// ================================================================

RemoteExceptionType::RemoteExceptionType()
    :Type("android.os", "RemoteException", BUILT_IN, false, false, false)
{
}

void
RemoteExceptionType::WriteToParcel(StatementBlock* addTo, Variable* v, Variable* parcel, int flags)
{
    fprintf(stderr, "aidl:internal error %s:%d\n", __FILE__, __LINE__);
}

void
RemoteExceptionType::CreateFromParcel(StatementBlock* addTo, Variable* v, Variable* parcel, Variable**)
{
    fprintf(stderr, "aidl:internal error %s:%d\n", __FILE__, __LINE__);
}

// ================================================================

RuntimeExceptionType::RuntimeExceptionType()
    :Type("java.lang", "RuntimeException", BUILT_IN, false, false, false)
{
}

void
RuntimeExceptionType::WriteToParcel(StatementBlock* addTo, Variable* v, Variable* parcel, int flags)
{
    fprintf(stderr, "aidl:internal error %s:%d\n", __FILE__, __LINE__);
}

void
RuntimeExceptionType::CreateFromParcel(StatementBlock* addTo, Variable* v, Variable* parcel, Variable**)
{
    fprintf(stderr, "aidl:internal error %s:%d\n", __FILE__, __LINE__);
}


// ================================================================

IBinderType::IBinderType()
    :Type("android.os", "IBinder", BUILT_IN, true, false, false)
{
}

void
IBinderType::WriteToParcel(StatementBlock* addTo, Variable* v, Variable* parcel, int flags)
{
    addTo->Add(new MethodCall(parcel, "writeStrongBinder", 1, v));
}

void
IBinderType::CreateFromParcel(StatementBlock* addTo, Variable* v, Variable* parcel, Variable**)
{
    addTo->Add(new Assignment(v, new MethodCall(parcel, "readStrongBinder")));
}

void
IBinderType::WriteArrayToParcel(StatementBlock* addTo, Variable* v, Variable* parcel, int flags)
{
    addTo->Add(new MethodCall(parcel, "writeBinderArray", 1, v));
}

void
IBinderType::CreateArrayFromParcel(StatementBlock* addTo, Variable* v,
                            Variable* parcel, Variable**)
{
    addTo->Add(new Assignment(v, new MethodCall(parcel, "createBinderArray")));
}

void
IBinderType::ReadArrayFromParcel(StatementBlock* addTo, Variable* v, Variable* parcel, Variable**)
{
    addTo->Add(new MethodCall(parcel, "readBinderArray", 1, v));
}


// ================================================================

IInterfaceType::IInterfaceType()
    :Type("android.os", "IInterface", BUILT_IN, false, false, false)
{
}

void
IInterfaceType::WriteToParcel(StatementBlock* addTo, Variable* v, Variable* parcel, int flags)
{
    fprintf(stderr, "aidl:internal error %s:%d\n", __FILE__, __LINE__);
}

void
IInterfaceType::CreateFromParcel(StatementBlock* addTo, Variable* v, Variable* parcel, Variable**)
{
    fprintf(stderr, "aidl:internal error %s:%d\n", __FILE__, __LINE__);
}


// ================================================================

BinderType::BinderType()
    :Type("android.os", "Binder", BUILT_IN, false, false, false)
{
}

void
BinderType::WriteToParcel(StatementBlock* addTo, Variable* v, Variable* parcel, int flags)
{
    fprintf(stderr, "aidl:internal error %s:%d\n", __FILE__, __LINE__);
}

void
BinderType::CreateFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable**)
{
    fprintf(stderr, "aidl:internal error %s:%d\n", __FILE__, __LINE__);
}


// ================================================================

BinderProxyType::BinderProxyType()
    :Type("android.os", "BinderProxy", BUILT_IN, false, false, false)
{
}

void
BinderProxyType::WriteToParcel(StatementBlock* addTo, Variable* v, Variable* parcel, int flags)
{
    fprintf(stderr, "aidl:internal error %s:%d\n", __FILE__, __LINE__);
}

void
BinderProxyType::CreateFromParcel(StatementBlock* addTo, Variable* v,
                                    Variable* parcel, Variable**)
{
    fprintf(stderr, "aidl:internal error %s:%d\n", __FILE__, __LINE__);
}


// ================================================================

ParcelType::ParcelType()
    :Type("android.os", "Parcel", BUILT_IN, false, false, false)
{
}

void
ParcelType::WriteToParcel(StatementBlock* addTo, Variable* v, Variable* parcel, int flags)
{
    fprintf(stderr, "aidl:internal error %s:%d\n", __FILE__, __LINE__);
}

void
ParcelType::CreateFromParcel(StatementBlock* addTo, Variable* v, Variable* parcel, Variable**)
{
    fprintf(stderr, "aidl:internal error %s:%d\n", __FILE__, __LINE__);
}

// ================================================================

ParcelableInterfaceType::ParcelableInterfaceType()
    :Type("android.os", "Parcelable", BUILT_IN, false, false, false)
{
}

void
ParcelableInterfaceType::WriteToParcel(StatementBlock* addTo, Variable* v, Variable* parcel, int flags)
{
    fprintf(stderr, "aidl:internal error %s:%d\n", __FILE__, __LINE__);
}

void
ParcelableInterfaceType::CreateFromParcel(StatementBlock* addTo, Variable* v, Variable* parcel, Variable**)
{
    fprintf(stderr, "aidl:internal error %s:%d\n", __FILE__, __LINE__);
}

// ================================================================

MapType::MapType()
    :Type("java.util", "Map", BUILT_IN, true, false, true)
{
}

void
MapType::WriteToParcel(StatementBlock* addTo, Variable* v, Variable* parcel, int flags)
{
    addTo->Add(new MethodCall(parcel, "writeMap", 1, v));
}

static void EnsureClassLoader(StatementBlock* addTo, Variable** cl)
{
    // We don't want to look up the class loader once for every
    // collection argument, so ensure we do it at most once per method.
    if (*cl == NULL) {
        *cl = new Variable(CLASSLOADER_TYPE, "cl");
        addTo->Add(new VariableDeclaration(*cl,
                new LiteralExpression("this.getClass().getClassLoader()"),
                CLASSLOADER_TYPE));
    }
}

void
MapType::CreateFromParcel(StatementBlock* addTo, Variable* v, Variable* parcel, Variable** cl)
{
    EnsureClassLoader(addTo, cl);
    addTo->Add(new Assignment(v, new MethodCall(parcel, "readHashMap", 1, *cl)));
}

void
MapType::ReadFromParcel(StatementBlock* addTo, Variable* v, Variable* parcel, Variable** cl)
{
    EnsureClassLoader(addTo, cl);
    addTo->Add(new MethodCall(parcel, "readMap", 2, v, *cl));
}


// ================================================================

ListType::ListType()
    :Type("java.util", "List", BUILT_IN, true, true, true)
{
}

string
ListType::InstantiableName() const
{
    return "java.util.ArrayList";
}

void
ListType::WriteToParcel(StatementBlock* addTo, Variable* v, Variable* parcel, int flags)
{
    addTo->Add(new MethodCall(parcel, "writeList", 1, v));
}

void
ListType::CreateFromParcel(StatementBlock* addTo, Variable* v, Variable* parcel, Variable** cl)
{
    EnsureClassLoader(addTo, cl);
    addTo->Add(new Assignment(v, new MethodCall(parcel, "readArrayList", 1, *cl)));
}

void
ListType::ReadFromParcel(StatementBlock* addTo, Variable* v,
                    Variable* parcel, Variable** cl)
{
    EnsureClassLoader(addTo, cl);
    addTo->Add(new MethodCall(parcel, "readList", 2, v, *cl));
}

void
ListType::WriteToRpcData(StatementBlock* addTo, Expression* k, Variable* v,
        Variable* data, int flags)
{
    addTo->Add(new MethodCall(data, "putList", 2, k, v));
}

void
ListType::CreateFromRpcData(StatementBlock* addTo, Expression* k, Variable* v, Variable* data,
        Variable** cl)
{
    addTo->Add(new Assignment(v, new MethodCall(data, "getList", 1, k)));
}

// ================================================================

UserDataType::UserDataType(const string& package, const string& name,
                        bool builtIn, bool canWriteToParcel, bool canWriteToRpcData,
                        const string& declFile, int declLine)
    :Type(package, name, builtIn ? BUILT_IN : USERDATA, canWriteToParcel, canWriteToRpcData,
            true, declFile, declLine)
{
}

string
UserDataType::CreatorName() const
{
    return QualifiedName() + ".CREATOR";
}

string
UserDataType::RpcCreatorName() const
{
    return QualifiedName() + ".RPC_CREATOR";
}

void
UserDataType::WriteToParcel(StatementBlock* addTo, Variable* v, Variable* parcel, int flags)
{
    // if (v != null) {
    //     parcel.writeInt(1);
    //     v.writeToParcel(parcel);
    // } else {
    //     parcel.writeInt(0);
    // }
    IfStatement* elsepart = new IfStatement();
    elsepart->statements->Add(new MethodCall(parcel, "writeInt", 1,
                                new LiteralExpression("0")));
    IfStatement* ifpart = new IfStatement;
    ifpart->expression = new Comparison(v, "!=", NULL_VALUE);
    ifpart->elseif = elsepart;
    ifpart->statements->Add(new MethodCall(parcel, "writeInt", 1,
                                new LiteralExpression("1")));
    ifpart->statements->Add(new MethodCall(v, "writeToParcel", 2,
                                parcel, BuildWriteToParcelFlags(flags)));

    addTo->Add(ifpart);
}

void
UserDataType::CreateFromParcel(StatementBlock* addTo, Variable* v, Variable* parcel, Variable**)
{
    // if (0 != parcel.readInt()) {
    //     v = CLASS.CREATOR.createFromParcel(parcel)
    // } else {
    //     v = null;
    // }
    IfStatement* elsepart = new IfStatement();
    elsepart->statements->Add(new Assignment(v, NULL_VALUE));

    IfStatement* ifpart = new IfStatement();
    ifpart->expression = new Comparison(new LiteralExpression("0"), "!=",
                new MethodCall(parcel, "readInt"));
    ifpart->elseif = elsepart;
    ifpart->statements->Add(new Assignment(v,
                new MethodCall(v->type, "CREATOR.createFromParcel", 1, parcel)));

    addTo->Add(ifpart);
}

void
UserDataType::ReadFromParcel(StatementBlock* addTo, Variable* v,
                    Variable* parcel, Variable**)
{
    // TODO: really, we don't need to have this extra check, but we
    // don't have two separate marshalling code paths
    // if (0 != parcel.readInt()) {
    //     v.readFromParcel(parcel)
    // }
    IfStatement* ifpart = new IfStatement();
    ifpart->expression = new Comparison(new LiteralExpression("0"), "!=",
                new MethodCall(parcel, "readInt"));
    ifpart->statements->Add(new MethodCall(v, "readFromParcel", 1, parcel));
    addTo->Add(ifpart);
}

bool
UserDataType::CanBeArray() const
{
    return true;
}

void
UserDataType::WriteArrayToParcel(StatementBlock* addTo, Variable* v, Variable* parcel, int flags)
{
    addTo->Add(new MethodCall(parcel, "writeTypedArray", 2, v,
                BuildWriteToParcelFlags(flags)));
}

void
UserDataType::CreateArrayFromParcel(StatementBlock* addTo, Variable* v,
                            Variable* parcel, Variable**)
{
    string creator = v->type->QualifiedName() + ".CREATOR";
    addTo->Add(new Assignment(v, new MethodCall(parcel,
                "createTypedArray", 1, new LiteralExpression(creator))));
}

void
UserDataType::ReadArrayFromParcel(StatementBlock* addTo, Variable* v, Variable* parcel, Variable**)
{
    string creator = v->type->QualifiedName() + ".CREATOR";
    addTo->Add(new MethodCall(parcel, "readTypedArray", 2,
                    v, new LiteralExpression(creator)));
}

void
UserDataType::WriteToRpcData(StatementBlock* addTo, Expression* k, Variable* v,
                                    Variable* data, int flags)
{
    // data.putFlattenable(k, v);
    addTo->Add(new MethodCall(data, "putFlattenable", 2, k, v));
}

void
UserDataType::CreateFromRpcData(StatementBlock* addTo, Expression* k, Variable* v,
                                    Variable* data, Variable** cl)
{
    // data.getFlattenable(k, CLASS.RPC_CREATOR);
    addTo->Add(new Assignment(v, new MethodCall(data, "getFlattenable", 2, k,
                new FieldVariable(v->type, "RPC_CREATOR"))));
}

// ================================================================

InterfaceType::InterfaceType(const string& package, const string& name,
                        bool builtIn, bool oneway,
                        const string& declFile, int declLine)
    :Type(package, name, builtIn ? BUILT_IN : INTERFACE, true, false, false,
                        declFile, declLine)
    ,m_oneway(oneway)
{
}

bool
InterfaceType::OneWay() const
{
    return m_oneway;
}

void
InterfaceType::WriteToParcel(StatementBlock* addTo, Variable* v, Variable* parcel, int flags)
{
    // parcel.writeStrongBinder(v != null ? v.asBinder() : null);
    addTo->Add(new MethodCall(parcel, "writeStrongBinder", 1, 
                new Ternary(
                    new Comparison(v, "!=", NULL_VALUE),
                    new MethodCall(v, "asBinder"),
                    NULL_VALUE)));
}

void
InterfaceType::CreateFromParcel(StatementBlock* addTo, Variable* v, Variable* parcel, Variable**)
{
    // v = Interface.asInterface(parcel.readStrongBinder());
    string type = v->type->QualifiedName();
    type += ".Stub";
    addTo->Add(new Assignment(v,
                new MethodCall( NAMES.Find(type), "asInterface", 1,
                    new MethodCall(parcel, "readStrongBinder"))));
}


// ================================================================

GenericType::GenericType(const string& package, const string& name,
                         const vector<Type*>& args)
    :Type(package, name, BUILT_IN, true, true, true)
{
    m_args = args;

    m_importName = package + '.' + name;

    string gen = "<";
    int N = args.size();
    for (int i=0; i<N; i++) {
        Type* t = args[i];
        gen += t->QualifiedName();
        if (i != N-1) {
            gen += ',';
        }
    }
    gen += '>';
    m_genericArguments = gen;
    SetQualifiedName(m_importName + gen);
}

const vector<Type*>&
GenericType::GenericArgumentTypes() const
{
    return m_args;
}

string
GenericType::GenericArguments() const
{
    return m_genericArguments;
}

string
GenericType::ImportType() const
{
    return m_importName;
}

void
GenericType::WriteToParcel(StatementBlock* addTo, Variable* v, Variable* parcel, int flags)
{
    fprintf(stderr, "implement GenericType::WriteToParcel\n");
}

void
GenericType::CreateFromParcel(StatementBlock* addTo, Variable* v, Variable* parcel, Variable**)
{
    fprintf(stderr, "implement GenericType::CreateFromParcel\n");
}

void
GenericType::ReadFromParcel(StatementBlock* addTo, Variable* v,
                            Variable* parcel, Variable**)
{
    fprintf(stderr, "implement GenericType::ReadFromParcel\n");
}


// ================================================================

GenericListType::GenericListType(const string& package, const string& name,
                         const vector<Type*>& args)
    :GenericType(package, name, args),
     m_creator(args[0]->CreatorName())
{
}

string
GenericListType::CreatorName() const
{
    return "android.os.Parcel.arrayListCreator";
}

string
GenericListType::InstantiableName() const
{
    return "java.util.ArrayList" + GenericArguments();
}

void
GenericListType::WriteToParcel(StatementBlock* addTo, Variable* v, Variable* parcel, int flags)
{
    if (m_creator == STRING_TYPE->CreatorName()) {
        addTo->Add(new MethodCall(parcel, "writeStringList", 1, v));
    } else if (m_creator == IBINDER_TYPE->CreatorName()) {
        addTo->Add(new MethodCall(parcel, "writeBinderList", 1, v));
    } else {
        // parcel.writeTypedListXX(arg);
        addTo->Add(new MethodCall(parcel, "writeTypedList", 1, v));
    }
}

void
GenericListType::CreateFromParcel(StatementBlock* addTo, Variable* v, Variable* parcel, Variable**)
{
    if (m_creator == STRING_TYPE->CreatorName()) {
        addTo->Add(new Assignment(v,
                   new MethodCall(parcel, "createStringArrayList", 0)));
    } else if (m_creator == IBINDER_TYPE->CreatorName()) {
        addTo->Add(new Assignment(v,
                   new MethodCall(parcel, "createBinderArrayList", 0)));
    } else {
        // v = _data.readTypedArrayList(XXX.creator);
        addTo->Add(new Assignment(v,
                   new MethodCall(parcel, "createTypedArrayList", 1,
                   new LiteralExpression(m_creator))));
    }
}

void
GenericListType::ReadFromParcel(StatementBlock* addTo, Variable* v,
                            Variable* parcel, Variable**)
{
    if (m_creator == STRING_TYPE->CreatorName()) {
        addTo->Add(new MethodCall(parcel, "readStringList", 1, v));
    } else if (m_creator == IBINDER_TYPE->CreatorName()) {
        addTo->Add(new MethodCall(parcel, "readBinderList", 1, v));
    } else {
        // v = _data.readTypedList(v, XXX.creator);
        addTo->Add(new MethodCall(parcel, "readTypedList", 2,
                       v,
                       new LiteralExpression(m_creator)));
    }
}

void
GenericListType::WriteToRpcData(StatementBlock* addTo, Expression* k, Variable* v,
        Variable* data, int flags)
{
    Type* generic = GenericArgumentTypes()[0];
    if (generic == RPC_DATA_TYPE) {
        addTo->Add(new MethodCall(data, "putRpcDataList", 2, k, v));
    } else if (generic->RpcCreatorName() != "") {
        addTo->Add(new MethodCall(data, "putFlattenableList", 2, k, v));
    } else {
        addTo->Add(new MethodCall(data, "putList", 2, k, v));
    }
}

void
GenericListType::CreateFromRpcData(StatementBlock* addTo, Expression* k, Variable* v,
        Variable* data, Variable** cl)
{
    Type* generic = GenericArgumentTypes()[0];
    if (generic == RPC_DATA_TYPE) {
        addTo->Add(new Assignment(v, new MethodCall(data, "getRpcDataList", 2, k)));
    } else if (generic->RpcCreatorName() != "") {
        addTo->Add(new Assignment(v, new MethodCall(data, "getFlattenableList", 2, k, 
                        new LiteralExpression(generic->RpcCreatorName()))));
    } else {
        string classArg = GenericArgumentTypes()[0]->QualifiedName();
        classArg += ".class";
        addTo->Add(new Assignment(v, new MethodCall(data, "getList", 2, k,
                        new LiteralExpression(classArg))));
    }
}


// ================================================================

RpcDataType::RpcDataType()
    :UserDataType("android.support.place.rpc", "RpcData", true, true, true)
{
}

void
RpcDataType::WriteToRpcData(StatementBlock* addTo, Expression* k, Variable* v,
        Variable* data, int flags)
{
    addTo->Add(new MethodCall(data, "putRpcData", 2, k, v));
}

void
RpcDataType::CreateFromRpcData(StatementBlock* addTo, Expression* k, Variable* v, Variable* data,
        Variable** cl)
{
    addTo->Add(new Assignment(v, new MethodCall(data, "getRpcData", 1, k)));
}


// ================================================================

ClassLoaderType::ClassLoaderType()
    :Type("java.lang", "ClassLoader", BUILT_IN, false, false, false)
{
}


// ================================================================

Namespace::Namespace()
{
}

Namespace::~Namespace()
{
    int N = m_types.size();
    for (int i=0; i<N; i++) {
        delete m_types[i];
    }
}

void
Namespace::Add(Type* type)
{
    Type* t = Find(type->QualifiedName());
    if (t == NULL) {
        m_types.push_back(type);
    }
}

void
Namespace::AddGenericType(const string& package, const string& name, int args)
{
    Generic g;
        g.package = package;
        g.name = name;
        g.qualified = package + '.' + name;
        g.args = args;
    m_generics.push_back(g);
}

Type*
Namespace::Find(const string& name) const
{
    int N = m_types.size();
    for (int i=0; i<N; i++) {
        if (m_types[i]->QualifiedName() == name) {
            return m_types[i];
        }
    }
    return NULL;
}

Type*
Namespace::Find(const char* package, const char* name) const
{
    string s;
    if (package != NULL) {
        s += package;
        s += '.';
    }
    s += name;
    return Find(s);
}

static string
normalize_generic(const string& s)
{
    string r;
    int N = s.size();
    for (int i=0; i<N; i++) {
        char c = s[i];
        if (!isspace(c)) {
            r += c;
        }
    }
    return r;
}

Type*
Namespace::Search(const string& name)
{
    // an exact match wins
    Type* result = Find(name);
    if (result != NULL) {
        return result;
    }

    // try the class names
    // our language doesn't allow you to not specify outer classes
    // when referencing an inner class.  that could be changed, and this
    // would be the place to do it, but I don't think the complexity in
    // scoping rules is worth it.
    int N = m_types.size();
    for (int i=0; i<N; i++) {
        if (m_types[i]->Name() == name) {
            return m_types[i];
        }
    }

    // we got to here and it's not a generic, give up
    if (name.find('<') == name.npos) {
        return NULL;
    }

    // remove any whitespace
    string normalized = normalize_generic(name);

    // find the part before the '<', find a generic for it
    ssize_t baseIndex = normalized.find('<');
    string base(normalized.c_str(), baseIndex);
    const Generic* g = search_generic(base);
    if (g == NULL) {
        return NULL;
    }

    // For each of the args, do a recursive search on it.  We don't allow
    // generics within generics like Java does, because we're really limiting
    // them to just built-in container classes, at least for now.  Our syntax
    // ensures this right now as well.
    vector<Type*> args;
    size_t start = baseIndex + 1;
    size_t end = start;
    while (normalized[start] != '\0') {
        end = normalized.find(',', start);
        if (end == normalized.npos) {
            end = normalized.find('>', start);
        }
        string s(normalized.c_str()+start, end-start);
        Type* t = this->Search(s);
        if (t == NULL) {
            // maybe we should print a warning here?
            return NULL;
        }
        args.push_back(t);
        start = end+1;
    }

    // construct a GenericType, add it to our name set so they always get
    // the same object, and return it.
    result = make_generic_type(g->package, g->name, args);
    if (result == NULL) {
        return NULL;
    }

    this->Add(result);
    return this->Find(result->QualifiedName());
}

const Namespace::Generic*
Namespace::search_generic(const string& name) const
{
    int N = m_generics.size();

    // first exact match
    for (int i=0; i<N; i++) {
        const Generic& g = m_generics[i];
        if (g.qualified == name) {
            return &g;
        }
    }

    // then name match
    for (int i=0; i<N; i++) {
        const Generic& g = m_generics[i];
        if (g.name == name) {
            return &g;
        }
    }

    return NULL;
}

void
Namespace::Dump() const
{
    int n = m_types.size();
    for (int i=0; i<n; i++) {
        Type* t = m_types[i];
        printf("type: package=%s name=%s qualifiedName=%s\n",
                t->Package().c_str(), t->Name().c_str(),
                t->QualifiedName().c_str());
    }
}
