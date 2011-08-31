#include "generate_java.h"
#include "Type.h"
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

Type* SERVICE_CONTAINER_TYPE = new Type("com.android.athome.service",
        "AndroidAtHomeServiceContainer", Type::BUILT_IN, false, false);

static string
format_int(int n)
{
    char str[20];
    sprintf(str, "%d", n);
    return string(str);
}

static string
class_name_leaf(const string& str)
{
    string::size_type pos = str.rfind('.');
    if (pos == string::npos) {
        return str;
    } else {
        return string(str, pos+1);
    }
}

// =================================================
class RpcProxyClass : public Class
{
public:
    RpcProxyClass(const interface_type* iface, InterfaceType* interfaceType);
    virtual ~RpcProxyClass();

    Variable* endpoint;
    Variable* context;

private:
    void generate_ctor();
};

RpcProxyClass::RpcProxyClass(const interface_type* iface, InterfaceType* interfaceType)
    :Class()
{
    this->comment = gather_comments(iface->comments_token->extra);
    this->modifiers = PUBLIC;
    this->what = Class::CLASS;
    this->type = interfaceType;

    // context
    this->context = new Variable(CONTEXT_TYPE, "_context");
    this->elements.push_back(new Field(PRIVATE, this->context));
    // endpoint
    this->endpoint = new Variable(RPC_ENDPOINT_INFO_TYPE, "_endpoint");
    this->elements.push_back(new Field(PRIVATE, this->endpoint));

    // methods
    generate_ctor();
}

RpcProxyClass::~RpcProxyClass()
{
}

void
RpcProxyClass::generate_ctor()
{
    Variable* context = new Variable(CONTEXT_TYPE, "context");
    Variable* endpoint = new Variable(RPC_ENDPOINT_INFO_TYPE, "endpoint");
    Method* ctor = new Method;
        ctor->modifiers = PUBLIC;
        ctor->name = this->type->Name();
        ctor->statements = new StatementBlock;
        ctor->parameters.push_back(context);
        ctor->parameters.push_back(endpoint);
    this->elements.push_back(ctor);

    ctor->statements->Add(new Assignment(this->context, context));
    ctor->statements->Add(new Assignment(this->endpoint, endpoint));
}

// =================================================
class ServiceBaseClass : public Class
{
public:
    ServiceBaseClass(const interface_type* iface);
    virtual ~ServiceBaseClass();

    void AddMethod(const string& methodName, StatementBlock** statements);
    void DoneWithMethods();

    bool needed;
    Method* processMethod;
    Variable* actionParam;
    Variable* errorParam;
    Variable* requestData;
    Variable* resultData;
    IfStatement* dispatchIfStatement;

private:
    void generate_ctor();
    void generate_process();
};

ServiceBaseClass::ServiceBaseClass(const interface_type* iface)
    :Class(),
     needed(false),
     dispatchIfStatement(NULL)
{
    this->comment = "/** Extend this to implement a link service. */";
    this->modifiers = STATIC | PUBLIC | ABSTRACT;
    this->what = Class::CLASS;
    this->type = NAMES.Find(iface->package, append(iface->name.data, ".ServiceBase").c_str());
    this->extends = RPC_SERVICE_BASE_TYPE;

    // methods
    generate_ctor();
    generate_process();
}

ServiceBaseClass::~ServiceBaseClass()
{
}

void
ServiceBaseClass::generate_ctor()
{
    Variable* container = new Variable(SERVICE_CONTAINER_TYPE, "container");
    Variable* name = new Variable(STRING_TYPE, "name");
    Variable* type = new Variable(STRING_TYPE, "type");
    Variable* version = new Variable(INT_TYPE, "version");
    Method* ctor = new Method;
        ctor->modifiers = PUBLIC;
        ctor->name = class_name_leaf(this->type->Name());
        ctor->statements = new StatementBlock;
        ctor->parameters.push_back(container);
        ctor->parameters.push_back(name);
        ctor->parameters.push_back(type);
        ctor->parameters.push_back(version);
    this->elements.push_back(ctor);

    ctor->statements->Add(new MethodCall("super", 4, container, name, type, version));
}

void
ServiceBaseClass::generate_process()
{
    // byte[] process(String action, byte[] params, RpcError status)
    this->processMethod = new Method;
        this->processMethod->modifiers = PUBLIC;
        this->processMethod->returnType = BYTE_TYPE;
        this->processMethod->returnTypeDimension = 1;
        this->processMethod->name = "process";
        this->processMethod->statements = new StatementBlock;
    this->elements.push_back(this->processMethod);

    this->actionParam = new Variable(STRING_TYPE, "action");
    this->processMethod->parameters.push_back(this->actionParam);

    Variable* requestParam = new Variable(BYTE_TYPE, "requestParam", 1);
    this->processMethod->parameters.push_back(requestParam);

    this->errorParam = new Variable(RPC_ERROR_TYPE, "errorParam", 0);
    this->processMethod->parameters.push_back(this->errorParam);

    this->requestData = new Variable(RPC_DATA_TYPE, "request");
    this->processMethod->statements->Add(new VariableDeclaration(requestData,
                new NewExpression(RPC_DATA_TYPE, 1, requestParam)));

    this->resultData = new Variable(RPC_DATA_TYPE, "response");
    this->processMethod->statements->Add(new VariableDeclaration(this->resultData,
                NULL_VALUE));
}

void
ServiceBaseClass::AddMethod(const string& methodName, StatementBlock** statements)
{
    IfStatement* ifs = new IfStatement();
        ifs->expression = new MethodCall(new StringLiteralExpression(methodName), "equals", 1,
                this->actionParam);
        ifs->statements = *statements = new StatementBlock;
    if (this->dispatchIfStatement == NULL) {
        this->dispatchIfStatement = ifs;
        this->processMethod->statements->Add(dispatchIfStatement);
    } else {
        this->dispatchIfStatement->elseif = ifs;
        this->dispatchIfStatement = ifs;
    }
}

void
ServiceBaseClass::DoneWithMethods()
{
    IfStatement* s = new IfStatement;
        s->statements = new StatementBlock;
    this->processMethod->statements->Add(s);
    s->expression = new Comparison(this->resultData, "!=", NULL_VALUE);
    s->statements->Add(new ReturnStatement(new MethodCall(this->resultData, "serialize")));
    s->elseif = new IfStatement;
    s = s->elseif;
    s->statements->Add(new ReturnStatement(NULL_VALUE));
}

// =================================================
class ResultDispatcherClass : public Class
{
public:
    ResultDispatcherClass();
    virtual ~ResultDispatcherClass();

    void AddMethod(int index, const string& name, Method** method, Variable** param);

    bool needed;
    Variable* methodId;
    Variable* callback;
    Method* onResultMethod;
    Variable* resultParam;
    SwitchStatement* methodSwitch;

private:
    void generate_ctor();
    void generate_onResult();
};

ResultDispatcherClass::ResultDispatcherClass()
    :Class(),
     needed(false)
{
    this->modifiers = PRIVATE | FINAL;
    this->what = Class::CLASS;
    this->type = new Type("_ResultDispatcher", Type::GENERATED, false, false);
    this->interfaces.push_back(RPC_RESULT_HANDLER_TYPE);

    // methodId
    this->methodId = new Variable(INT_TYPE, "methodId");
    this->elements.push_back(new Field(PRIVATE, this->methodId));
    this->callback = new Variable(OBJECT_TYPE, "callback");
    this->elements.push_back(new Field(PRIVATE, this->callback));

    // methods
    generate_ctor();
    generate_onResult();
}

ResultDispatcherClass::~ResultDispatcherClass()
{
}

void
ResultDispatcherClass::generate_ctor()
{
    Variable* methodIdParam = new Variable(INT_TYPE, "methId");
    Variable* callbackParam = new Variable(OBJECT_TYPE, "cbObj");
    Method* ctor = new Method;
        ctor->modifiers = PUBLIC;
        ctor->name = this->type->Name();
        ctor->statements = new StatementBlock;
        ctor->parameters.push_back(methodIdParam);
        ctor->parameters.push_back(callbackParam);
    this->elements.push_back(ctor);

    ctor->statements->Add(new Assignment(this->methodId, methodIdParam));
    ctor->statements->Add(new Assignment(this->callback, callbackParam));
}

void
ResultDispatcherClass::generate_onResult()
{
    this->onResultMethod = new Method;
        this->onResultMethod->modifiers = PUBLIC;
        this->onResultMethod->returnType = VOID_TYPE;
        this->onResultMethod->returnTypeDimension = 0;
        this->onResultMethod->name = "onResult";
        this->onResultMethod->statements = new StatementBlock;
    this->elements.push_back(this->onResultMethod);

    this->resultParam = new Variable(BYTE_TYPE, "result", 1);
    this->onResultMethod->parameters.push_back(this->resultParam);

    this->methodSwitch = new SwitchStatement(this->methodId);
    this->onResultMethod->statements->Add(this->methodSwitch);
}

void
ResultDispatcherClass::AddMethod(int index, const string& name, Method** method, Variable** param)
{
    Method* m = new Method;
        m->modifiers = PUBLIC;
        m->returnType = VOID_TYPE;
        m->returnTypeDimension = 0;
        m->name = name;
        m->statements = new StatementBlock;
    *param = new Variable(BYTE_TYPE, "result", 1);
    m->parameters.push_back(*param);
    this->elements.push_back(m);
    *method = m;

    Case* c = new Case(format_int(index));
    c->statements->Add(new MethodCall(new LiteralExpression("this"), name, 1, this->resultParam));

    this->methodSwitch->cases.push_back(c);
}

// =================================================
static void
generate_new_array(Type* t, StatementBlock* addTo, Variable* v, Variable* from)
{
    fprintf(stderr, "aidl: implement generate_new_array %s:%d\n", __FILE__, __LINE__);
    exit(1);
}

static void
generate_create_from_data(Type* t, StatementBlock* addTo, const string& key, Variable* v,
                            Variable* data, Variable** cl)
{
    Expression* k = new StringLiteralExpression(key);
    if (v->dimension == 0) {
        t->CreateFromRpcData(addTo, k, v, data, cl);
    }
    if (v->dimension == 1) {
        //t->ReadArrayFromRpcData(addTo, v, data, cl);
        fprintf(stderr, "aidl: implement generate_create_from_data for arrays%s:%d\n",
                __FILE__, __LINE__);
    }
}

static void
generate_write_to_data(Type* t, StatementBlock* addTo, Expression* k, Variable* v, Variable* data)
{
    if (v->dimension == 0) {
        t->WriteToRpcData(addTo, k, v, data, 0);
    }
    if (v->dimension == 1) {
        //t->WriteArrayToParcel(addTo, v, data);
        fprintf(stderr, "aidl: implement generate_write_to_data for arrays%s:%d\n",
                __FILE__, __LINE__);
    }
}

// =================================================
static string
results_class_name(const string& n)
{
    string str = n;
    str[0] = toupper(str[0]);
    str.insert(0, "On");
    return str;
}

static string
results_method_name(const string& n)
{
    string str = n;
    str[0] = toupper(str[0]);
    str.insert(0, "on");
    return str;
}

static Type*
generate_results_method(const method_type* method, RpcProxyClass* proxyClass)
{
    arg_type* arg;

    string resultsMethodName = results_method_name(method->name.data);
    Type* resultsInterfaceType = new Type(results_class_name(method->name.data),
            Type::GENERATED, false, false);

    if (!method->oneway) {
        Class* resultsClass = new Class;
            resultsClass->modifiers = STATIC | PUBLIC;
            resultsClass->what = Class::INTERFACE;
            resultsClass->type = resultsInterfaceType;

        Method* resultMethod = new Method;
            resultMethod->comment = gather_comments(method->comments_token->extra);
            resultMethod->modifiers = PUBLIC;
            resultMethod->returnType = VOID_TYPE;
            resultMethod->returnTypeDimension = 0;
            resultMethod->name = resultsMethodName;
        if (0 != strcmp("void", method->type.type.data)) {
            resultMethod->parameters.push_back(new Variable(NAMES.Search(method->type.type.data),
                        "_result", method->type.dimension));
        }
        arg = method->args;
        while (arg != NULL) {
            if (convert_direction(arg->direction.data) & OUT_PARAMETER) {
                resultMethod->parameters.push_back(new Variable(
                                    NAMES.Search(arg->type.type.data), arg->name.data,
                                    arg->type.dimension));
            }
            arg = arg->next;
        }
        resultsClass->elements.push_back(resultMethod);

        if (resultMethod->parameters.size() > 0) {
            proxyClass->elements.push_back(resultsClass);
            return resultsInterfaceType;
        } 
    }
    //delete resultsInterfaceType;
    return NULL;
}

static void
generate_proxy_method(const method_type* method, RpcProxyClass* proxyClass,
        ResultDispatcherClass* resultsDispatcherClass, Type* resultsInterfaceType, int index)
{
    arg_type* arg;
    Method* proxyMethod = new Method;
        proxyMethod->comment = gather_comments(method->comments_token->extra);
        proxyMethod->modifiers = PUBLIC;
        proxyMethod->returnType = VOID_TYPE;
        proxyMethod->returnTypeDimension = 0;
        proxyMethod->name = method->name.data;
        proxyMethod->statements = new StatementBlock;
    proxyClass->elements.push_back(proxyMethod);

    // The local variables
    Variable* _data = new Variable(RPC_DATA_TYPE, "_data");
    proxyMethod->statements->Add(new VariableDeclaration(_data, new NewExpression(RPC_DATA_TYPE)));

    // Add the arguments
    arg = method->args;
    while (arg != NULL) {
        if (convert_direction(arg->direction.data) & IN_PARAMETER) {
            // Function signature
            Type* t = NAMES.Search(arg->type.type.data);
            Variable* v = new Variable(t, arg->name.data, arg->type.dimension);
            proxyMethod->parameters.push_back(v);

            // Input parameter marshalling
            generate_write_to_data(t, proxyMethod->statements,
                    new StringLiteralExpression(arg->name.data), v, _data);
        }
        arg = arg->next;
    }

    // If there is a results interface for this class
    Expression* resultParameter;
    if (resultsInterfaceType != NULL) {
        // Result interface parameter
        Variable* resultListener = new Variable(resultsInterfaceType, "_result");
        proxyMethod->parameters.push_back(resultListener);

        // Add the results dispatcher callback
        resultsDispatcherClass->needed = true;
        resultParameter = new NewExpression(resultsDispatcherClass->type, 2,
                new LiteralExpression(format_int(index)), resultListener);
    } else {
        resultParameter = NULL_VALUE;
    }

    // All proxy methods take an error parameter
    Variable* errorListener = new Variable(RPC_ERROR_LISTENER_TYPE, "_errors");
    proxyMethod->parameters.push_back(errorListener);

    // Call the broker
    proxyMethod->statements->Add(new MethodCall(RPC_BROKER_TYPE, "sendRequest", 6,
                new FieldVariable(THIS_VALUE, "_context"),
                new StringLiteralExpression(method->name.data),
                proxyClass->endpoint,
                new MethodCall(_data, "serialize"),
                resultParameter,
                errorListener));
}

static void
generate_result_dispatcher_method(const method_type* method,
        ResultDispatcherClass* resultsDispatcherClass, Type* resultsInterfaceType, int index)
{
    arg_type* arg;
    Method* dispatchMethod;
    Variable* dispatchParam;
    resultsDispatcherClass->AddMethod(index, method->name.data, &dispatchMethod, &dispatchParam);

    Variable* classLoader = NULL;
    Variable* resultData = new Variable(RPC_DATA_TYPE, "resultData");
    dispatchMethod->statements->Add(new VariableDeclaration(resultData,
                new NewExpression(RPC_DATA_TYPE, 1, dispatchParam)));

    // The callback method itself
    MethodCall* realCall = new MethodCall(
            new Cast(resultsInterfaceType, new FieldVariable(THIS_VALUE, "callback")),
            results_method_name(method->name.data));

    // The return value
    {
        Type* t = NAMES.Search(method->type.type.data);
        Variable* rv = new Variable(t, "rv");
        dispatchMethod->statements->Add(new VariableDeclaration(rv));
        generate_create_from_data(t, dispatchMethod->statements, "_result", rv,
                resultData, &classLoader);
        realCall->arguments.push_back(rv);
    }

    VariableFactory stubArgs("arg");
    arg = method->args;
    while (arg != NULL) {
        if (convert_direction(arg->direction.data) & OUT_PARAMETER) {
            // Unmarshall the results
            Type* t = NAMES.Search(arg->type.type.data);
            Variable* v = stubArgs.Get(t);
            dispatchMethod->statements->Add(new VariableDeclaration(v));

            generate_create_from_data(t, dispatchMethod->statements, arg->name.data, v,
                    resultData, &classLoader);

            // Add the argument to the callback
            realCall->arguments.push_back(v);
        }
        arg = arg->next;
    }

    // Call the callback method
    dispatchMethod->statements->Add(realCall);
}

static void
generate_service_base_methods(const method_type* method, ServiceBaseClass* serviceBaseClass)
{
    arg_type* arg;
    StatementBlock* block;
    serviceBaseClass->AddMethod(method->name.data, &block);

    // The abstract method that the service developers implement
    Method* decl = new Method;
        decl->comment = gather_comments(method->comments_token->extra);
        decl->modifiers = PUBLIC | ABSTRACT;
        decl->returnType = NAMES.Search(method->type.type.data);
        decl->returnTypeDimension = method->type.dimension;
        decl->name = method->name.data;

    arg = method->args;
    while (arg != NULL) {
        decl->parameters.push_back(new Variable(
                            NAMES.Search(arg->type.type.data), arg->name.data,
                            arg->type.dimension));
        arg = arg->next;
    }

    serviceBaseClass->elements.push_back(decl);
    
    // The call to decl (from above)
    MethodCall* realCall = new MethodCall(THIS_VALUE, method->name.data);
    
    // args
    Variable* classLoader = NULL;
    VariableFactory stubArgs("_arg");
    arg = method->args;
    while (arg != NULL) {
        Type* t = NAMES.Search(arg->type.type.data);
        Variable* v = stubArgs.Get(t);
        v->dimension = arg->type.dimension;

        // Unmarshall the parameter
        block->Add(new VariableDeclaration(v));
        if (convert_direction(arg->direction.data) & IN_PARAMETER) {
            generate_create_from_data(t, block, arg->name.data, v,
                    serviceBaseClass->requestData, &classLoader);
        } else {
            if (arg->type.dimension == 0) {
                block->Add(new Assignment(v, new NewExpression(v->type)));
            }
            else if (arg->type.dimension == 1) {
                generate_new_array(v->type, block, v, serviceBaseClass->requestData);
            }
            else {
                fprintf(stderr, "aidl:internal error %s:%d\n", __FILE__,
                        __LINE__);
            }
        }

        // Add that parameter to the method call
        realCall->arguments.push_back(v);

        arg = arg->next;
    }

    // the real call
    Variable* _result = NULL;
    if (0 == strcmp(method->type.type.data, "void")) {
        block->Add(realCall);
    } else {
        _result = new Variable(decl->returnType, "_result",
                                decl->returnTypeDimension);
        block->Add(new VariableDeclaration(_result, realCall));

        // marshall the return value
        generate_write_to_data(decl->returnType, block,
                new StringLiteralExpression("_result"), _result, serviceBaseClass->resultData);
    }

    // out parameters
    int i = 0;
    arg = method->args;
    while (arg != NULL) {
        Type* t = NAMES.Search(arg->type.type.data);
        Variable* v = stubArgs.Get(i++);

        if (convert_direction(arg->direction.data) & OUT_PARAMETER) {
            generate_write_to_data(t, block, new StringLiteralExpression(arg->name.data),
                    v, serviceBaseClass->resultData);
        }

        arg = arg->next;
    }
}

static void
generate_method(const method_type* method, RpcProxyClass* proxyClass,
        ServiceBaseClass* serviceBaseClass, ResultDispatcherClass* resultsDispatcherClass,
        int index)
{
    // == the callback interface for results =================================
    // the service base class
    Type* resultsInterfaceType = generate_results_method(method, proxyClass);
    
    // == the method in the proxy class =====================================
    generate_proxy_method(method, proxyClass, resultsDispatcherClass, resultsInterfaceType, index);

    // == the method in the result dispatcher class =========================
    if (resultsInterfaceType != NULL) {
        generate_result_dispatcher_method(method, resultsDispatcherClass, resultsInterfaceType,
                index);
    }

    // == the dispatch method in the service base class ======================
    generate_service_base_methods(method, serviceBaseClass);
}

Class*
generate_rpc_interface_class(const interface_type* iface)
{
    // the proxy class
    InterfaceType* interfaceType = static_cast<InterfaceType*>(
        NAMES.Find(iface->package, iface->name.data));
    RpcProxyClass* proxy = new RpcProxyClass(iface, interfaceType);

    // the service base class
    ServiceBaseClass* base = new ServiceBaseClass(iface);
    proxy->elements.push_back(base);

    // the result dispatcher
    ResultDispatcherClass* results = new ResultDispatcherClass();

    // all the declared methods of the proxy
    int index = 0;
    interface_item_type* item = iface->interface_items;
    while (item != NULL) {
        if (item->item_type == METHOD_TYPE) {
            generate_method((method_type*)item, proxy, base, results, index);
        }
        item = item->next;
        index++;
    }
    base->DoneWithMethods();

    // only add this if there are methods with results / out parameters
    if (results->needed) {
        proxy->elements.push_back(results);
    }

    return proxy;
}

