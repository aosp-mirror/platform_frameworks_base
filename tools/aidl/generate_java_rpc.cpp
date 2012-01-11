#include "generate_java.h"
#include "Type.h"
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

Type* ANDROID_CONTEXT_TYPE = new Type("android.content",
        "Context", Type::BUILT_IN, false, false, false);
Type* PRESENTER_BASE_TYPE = new Type("android.support.place.connector",
        "EventListener", Type::BUILT_IN, false, false, false);
Type* PRESENTER_LISTENER_BASE_TYPE = new Type("android.support.place.connector",
        "EventListener.Listener", Type::BUILT_IN, false, false, false);
Type* RPC_BROKER_TYPE = new Type("android.support.place.connector", "Broker",
        Type::BUILT_IN, false, false, false);
Type* PLACE_INFO_TYPE = new Type("android.support.place.connector", "PlaceInfo",
        Type::BUILT_IN, false, false, false);
// TODO: Just use Endpoint, so this works for all endpoints.
Type* RPC_CONNECTOR_TYPE = new Type("android.support.place.connector", "Connector",
        Type::BUILT_IN, false, false, false);
Type* RPC_ENDPOINT_INFO_TYPE = new UserDataType("android.support.place.rpc",
        "EndpointInfo", true, __FILE__, __LINE__);
Type* RPC_RESULT_HANDLER_TYPE = new UserDataType("android.support.place.rpc", "RpcResultHandler",
        true, __FILE__, __LINE__);
Type* RPC_ERROR_LISTENER_TYPE = new Type("android.support.place.rpc", "RpcErrorHandler",
        Type::BUILT_IN, false, false, false);
Type* RPC_CONTEXT_TYPE = new UserDataType("android.support.place.rpc", "RpcContext", true,
        __FILE__, __LINE__);

static void generate_create_from_data(Type* t, StatementBlock* addTo, const string& key,
        Variable* v, Variable* data, Variable** cl);
static void generate_new_array(Type* t, StatementBlock* addTo, Variable* v, Variable* from);
static void generate_write_to_data(Type* t, StatementBlock* addTo, Expression* k, Variable* v,
        Variable* data);

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

static string
push_method_name(const string& n)
{
    string str = n;
    str[0] = toupper(str[0]);
    str.insert(0, "push");
    return str;
}

// =================================================
class DispatcherClass : public Class
{
public:
    DispatcherClass(const interface_type* iface, Expression* target);
    virtual ~DispatcherClass();

    void AddMethod(const method_type* method);
    void DoneWithMethods();

    Method* processMethod;
    Variable* actionParam;
    Variable* requestParam;
    Variable* rpcContextParam;
    Variable* errorParam;
    Variable* requestData;
    Variable* resultData;
    IfStatement* dispatchIfStatement;
    Expression* targetExpression;

private:
    void generate_process();
};

DispatcherClass::DispatcherClass(const interface_type* iface, Expression* target)
    :Class(),
     dispatchIfStatement(NULL),
     targetExpression(target)
{
    generate_process();
}

DispatcherClass::~DispatcherClass()
{
}

void
DispatcherClass::generate_process()
{
    // byte[] process(String action, byte[] params, RpcContext context, RpcError status)
    this->processMethod = new Method;
        this->processMethod->modifiers = PUBLIC;
        this->processMethod->returnType = BYTE_TYPE;
        this->processMethod->returnTypeDimension = 1;
        this->processMethod->name = "process";
        this->processMethod->statements = new StatementBlock;

    this->actionParam = new Variable(STRING_TYPE, "action");
    this->processMethod->parameters.push_back(this->actionParam);

    this->requestParam = new Variable(BYTE_TYPE, "requestParam", 1);
    this->processMethod->parameters.push_back(this->requestParam);

    this->rpcContextParam = new Variable(RPC_CONTEXT_TYPE, "context", 0);
    this->processMethod->parameters.push_back(this->rpcContextParam);    

    this->errorParam = new Variable(RPC_ERROR_TYPE, "errorParam", 0);
    this->processMethod->parameters.push_back(this->errorParam);

    this->requestData = new Variable(RPC_DATA_TYPE, "request");
    this->processMethod->statements->Add(new VariableDeclaration(requestData,
                new NewExpression(RPC_DATA_TYPE, 1, this->requestParam)));

    this->resultData = new Variable(RPC_DATA_TYPE, "resultData");
    this->processMethod->statements->Add(new VariableDeclaration(this->resultData,
                NULL_VALUE));
}

void
DispatcherClass::AddMethod(const method_type* method)
{
    arg_type* arg;

    // The if/switch statement
    IfStatement* ifs = new IfStatement();
        ifs->expression = new MethodCall(new StringLiteralExpression(method->name.data), "equals",
                1, this->actionParam);
    StatementBlock* block = ifs->statements = new StatementBlock;
    if (this->dispatchIfStatement == NULL) {
        this->dispatchIfStatement = ifs;
        this->processMethod->statements->Add(dispatchIfStatement);
    } else {
        this->dispatchIfStatement->elseif = ifs;
        this->dispatchIfStatement = ifs;
    }
    
    // The call to decl (from above)
    MethodCall* realCall = new MethodCall(this->targetExpression, method->name.data);

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
                    this->requestData, &classLoader);
        } else {
            if (arg->type.dimension == 0) {
                block->Add(new Assignment(v, new NewExpression(v->type)));
            }
            else if (arg->type.dimension == 1) {
                generate_new_array(v->type, block, v, this->requestData);
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

    // Add a final parameter: RpcContext. Contains data about
    // incoming request (e.g., certificate)
    realCall->arguments.push_back(new Variable(RPC_CONTEXT_TYPE, "context", 0));

    Type* returnType = NAMES.Search(method->type.type.data);
    if (returnType == EVENT_FAKE_TYPE) {
        returnType = VOID_TYPE;
    }
    
    // the real call
    bool first = true;
    Variable* _result = NULL;
    if (returnType == VOID_TYPE) {
        block->Add(realCall);
    } else {
        _result = new Variable(returnType, "_result",
                                method->type.dimension);
        block->Add(new VariableDeclaration(_result, realCall));

        // need the result RpcData
        if (first) {
            block->Add(new Assignment(this->resultData,
                        new NewExpression(RPC_DATA_TYPE)));
            first = false;
        }

        // marshall the return value
        generate_write_to_data(returnType, block,
                new StringLiteralExpression("_result"), _result, this->resultData);
    }

    // out parameters
    int i = 0;
    arg = method->args;
    while (arg != NULL) {
        Type* t = NAMES.Search(arg->type.type.data);
        Variable* v = stubArgs.Get(i++);

        if (convert_direction(arg->direction.data) & OUT_PARAMETER) {
            // need the result RpcData
            if (first) {
                block->Add(new Assignment(this->resultData, new NewExpression(RPC_DATA_TYPE)));
                first = false;
            }

            generate_write_to_data(t, block, new StringLiteralExpression(arg->name.data),
                    v, this->resultData);
        }

        arg = arg->next;
    }
}

void
DispatcherClass::DoneWithMethods()
{
    if (this->dispatchIfStatement == NULL) {
        return;
    }

    this->elements.push_back(this->processMethod);

    IfStatement* fallthrough = new IfStatement();
        fallthrough->statements = new StatementBlock;
        fallthrough->statements->Add(new ReturnStatement(
                    new MethodCall(SUPER_VALUE, "process", 4, 
                    this->actionParam, this->requestParam, 
                    this->rpcContextParam,
                    this->errorParam)));
    this->dispatchIfStatement->elseif = fallthrough;
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
class RpcProxyClass : public Class
{
public:
    RpcProxyClass(const interface_type* iface, InterfaceType* interfaceType);
    virtual ~RpcProxyClass();

    Variable* endpoint;
    Variable* broker;

private:
    void generate_ctor();
    void generate_get_endpoint_info();
};

RpcProxyClass::RpcProxyClass(const interface_type* iface, InterfaceType* interfaceType)
    :Class()
{
    this->comment = gather_comments(iface->comments_token->extra);
    this->modifiers = PUBLIC;
    this->what = Class::CLASS;
    this->type = interfaceType;

    // broker
    this->broker = new Variable(RPC_BROKER_TYPE, "_broker");
    this->elements.push_back(new Field(PRIVATE, this->broker));
    // endpoint
    this->endpoint = new Variable(RPC_ENDPOINT_INFO_TYPE, "_endpoint");
    this->elements.push_back(new Field(PRIVATE, this->endpoint));

    // methods
    generate_ctor();
    generate_get_endpoint_info();
}

RpcProxyClass::~RpcProxyClass()
{
}

void
RpcProxyClass::generate_ctor()
{
    Variable* broker = new Variable(RPC_BROKER_TYPE, "broker");
    Variable* endpoint = new Variable(RPC_ENDPOINT_INFO_TYPE, "endpoint");
    Method* ctor = new Method;
        ctor->modifiers = PUBLIC;
        ctor->name = class_name_leaf(this->type->Name());
        ctor->statements = new StatementBlock;
        ctor->parameters.push_back(broker);
        ctor->parameters.push_back(endpoint);
    this->elements.push_back(ctor);

    ctor->statements->Add(new Assignment(this->broker, broker));
    ctor->statements->Add(new Assignment(this->endpoint, endpoint));
}

void
RpcProxyClass::generate_get_endpoint_info()
{
    Method* get = new Method;
    get->modifiers = PUBLIC;
    get->returnType = RPC_ENDPOINT_INFO_TYPE;
    get->name = "getEndpointInfo";
    get->statements = new StatementBlock;
    this->elements.push_back(get);

    get->statements->Add(new ReturnStatement(this->endpoint));
}

// =================================================
class EventListenerClass : public DispatcherClass
{
public:
    EventListenerClass(const interface_type* iface, Type* listenerType);
    virtual ~EventListenerClass();

    Variable* _listener;

private:
    void generate_ctor();
};

Expression*
generate_get_listener_expression(Type* cast)
{
    return new Cast(cast, new MethodCall(THIS_VALUE, "getView"));
}

EventListenerClass::EventListenerClass(const interface_type* iface, Type* listenerType)
    :DispatcherClass(iface, new FieldVariable(THIS_VALUE, "_listener"))
{
    this->modifiers = PRIVATE;
    this->what = Class::CLASS;
    this->type = new Type(iface->package ? iface->package : "",
                        append(iface->name.data, ".Presenter"),
                        Type::GENERATED, false, false, false);
    this->extends = PRESENTER_BASE_TYPE;

    this->_listener = new Variable(listenerType, "_listener");
    this->elements.push_back(new Field(PRIVATE, this->_listener));

    // methods
    generate_ctor();
}

EventListenerClass::~EventListenerClass()
{
}

void
EventListenerClass::generate_ctor()
{
    Variable* broker = new Variable(RPC_BROKER_TYPE, "broker");
    Variable* listener = new Variable(this->_listener->type, "listener");
    Method* ctor = new Method;
        ctor->modifiers = PUBLIC;
        ctor->name = class_name_leaf(this->type->Name());
        ctor->statements = new StatementBlock;
        ctor->parameters.push_back(broker);
        ctor->parameters.push_back(listener);
    this->elements.push_back(ctor);

    ctor->statements->Add(new MethodCall("super", 2, broker, listener));
    ctor->statements->Add(new Assignment(this->_listener, listener));
}

// =================================================
class ListenerClass : public Class
{
public:
    ListenerClass(const interface_type* iface);
    virtual ~ListenerClass();

    bool needed;

private:
    void generate_ctor();
};

ListenerClass::ListenerClass(const interface_type* iface)
    :Class(),
     needed(false)
{
    this->comment = "/** Extend this to listen to the events from this class. */";
    this->modifiers = STATIC | PUBLIC ;
    this->what = Class::CLASS;
    this->type = new Type(iface->package ? iface->package : "",
                        append(iface->name.data, ".Listener"),
                        Type::GENERATED, false, false, false);
    this->extends = PRESENTER_LISTENER_BASE_TYPE;
}

ListenerClass::~ListenerClass()
{
}

// =================================================
class EndpointBaseClass : public DispatcherClass
{
public:
    EndpointBaseClass(const interface_type* iface);
    virtual ~EndpointBaseClass();

    bool needed;

private:
    void generate_ctor();
};

EndpointBaseClass::EndpointBaseClass(const interface_type* iface)
    :DispatcherClass(iface, THIS_VALUE),
     needed(false)
{
    this->comment = "/** Extend this to implement a link service. */";
    this->modifiers = STATIC | PUBLIC | ABSTRACT;
    this->what = Class::CLASS;
    this->type = new Type(iface->package ? iface->package : "",
                        append(iface->name.data, ".EndpointBase"),
                        Type::GENERATED, false, false, false);
    this->extends = RPC_CONNECTOR_TYPE;

    // methods
    generate_ctor();
}

EndpointBaseClass::~EndpointBaseClass()
{
}

void
EndpointBaseClass::generate_ctor()
{
    Variable* container = new Variable(ANDROID_CONTEXT_TYPE, "context");
    Variable* broker = new Variable(RPC_BROKER_TYPE, "broker");
	Variable* place = new Variable(PLACE_INFO_TYPE, "placeInfo");
    Method* ctor = new Method;
        ctor->modifiers = PUBLIC;
        ctor->name = class_name_leaf(this->type->Name());
        ctor->statements = new StatementBlock;
        ctor->parameters.push_back(container);
        ctor->parameters.push_back(broker);
        ctor->parameters.push_back(place);
    this->elements.push_back(ctor);

    ctor->statements->Add(new MethodCall("super", 3, container, broker, place));
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
    this->type = new Type("_ResultDispatcher", Type::GENERATED, false, false, false);
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
        ctor->name = class_name_leaf(this->type->Name());
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
    c->statements->Add(new Break());

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
static Type*
generate_results_method(const method_type* method, RpcProxyClass* proxyClass)
{
    arg_type* arg;

    string resultsMethodName = results_method_name(method->name.data);
    Type* resultsInterfaceType = new Type(results_class_name(method->name.data),
            Type::GENERATED, false, false, false);

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
    proxyMethod->statements->Add(new MethodCall(new FieldVariable(THIS_VALUE, "_broker"),
                "sendRpc", 5,
                proxyClass->endpoint,
                new StringLiteralExpression(method->name.data),
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
        if (t != VOID_TYPE) {
            Variable* rv = new Variable(t, "rv");
            dispatchMethod->statements->Add(new VariableDeclaration(rv));
            generate_create_from_data(t, dispatchMethod->statements, "_result", rv,
                    resultData, &classLoader);
            realCall->arguments.push_back(rv);
        }
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
generate_regular_method(const method_type* method, RpcProxyClass* proxyClass,
        EndpointBaseClass* serviceBaseClass, ResultDispatcherClass* resultsDispatcherClass,
        int index)
{
    arg_type* arg;

    // == the callback interface for results ================================
    // the service base class
    Type* resultsInterfaceType = generate_results_method(method, proxyClass);
    
    // == the method in the proxy class =====================================
    generate_proxy_method(method, proxyClass, resultsDispatcherClass, resultsInterfaceType, index);

    // == the method in the result dispatcher class =========================
    if (resultsInterfaceType != NULL) {
        generate_result_dispatcher_method(method, resultsDispatcherClass, resultsInterfaceType,
                index);
    }

    // == The abstract method that the service developers implement ==========
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

    // Add the default RpcContext param to all methods
    decl->parameters.push_back(new Variable(RPC_CONTEXT_TYPE, "context", 0));
	
    serviceBaseClass->elements.push_back(decl);
    

    // == the dispatch method in the service base class ======================
    serviceBaseClass->AddMethod(method);
}

static void
generate_event_method(const method_type* method, RpcProxyClass* proxyClass,
        EndpointBaseClass* serviceBaseClass, ListenerClass* listenerClass,
        EventListenerClass* presenterClass, int index)
{
    arg_type* arg;
    listenerClass->needed = true;

    // == the push method in the service base class =========================
    Method* push = new Method;
        push->modifiers = PUBLIC;
        push->name = push_method_name(method->name.data);
        push->statements = new StatementBlock;
        push->returnType = VOID_TYPE;
    serviceBaseClass->elements.push_back(push);

    // The local variables
    Variable* _data = new Variable(RPC_DATA_TYPE, "_data");
    push->statements->Add(new VariableDeclaration(_data, new NewExpression(RPC_DATA_TYPE)));

    // Add the arguments
    arg = method->args;
    while (arg != NULL) {
        // Function signature
        Type* t = NAMES.Search(arg->type.type.data);
        Variable* v = new Variable(t, arg->name.data, arg->type.dimension);
        push->parameters.push_back(v);

        // Input parameter marshalling
        generate_write_to_data(t, push->statements,
                new StringLiteralExpression(arg->name.data), v, _data);

        arg = arg->next;
    }

    // Send the notifications
    push->statements->Add(new MethodCall("pushEvent", 2,
                new StringLiteralExpression(method->name.data),
                new MethodCall(_data, "serialize")));

    // == the event callback dispatcher method  ====================================
    presenterClass->AddMethod(method);

    // == the event method in the listener base class =====================
    Method* event = new Method;
        event->modifiers = PUBLIC;
        event->name = method->name.data;
        event->statements = new StatementBlock;
        event->returnType = VOID_TYPE;
    listenerClass->elements.push_back(event);
    arg = method->args;
    while (arg != NULL) {
        event->parameters.push_back(new Variable(
                            NAMES.Search(arg->type.type.data), arg->name.data,
                            arg->type.dimension));
        arg = arg->next;
    }

    // Add a final parameter: RpcContext. Contains data about
    // incoming request (e.g., certificate)
    event->parameters.push_back(new Variable(RPC_CONTEXT_TYPE, "context", 0));
}

static void
generate_listener_methods(RpcProxyClass* proxyClass, Type* presenterType, Type* listenerType)
{
    // AndroidAtHomePresenter _presenter;
    // void startListening(Listener listener) {
    //     stopListening();
    //     _presenter = new Presenter(_broker, listener);
    //     _presenter.startListening(_endpoint);
    // }
    // void stopListening() {
    //     if (_presenter != null) {
    //         _presenter.stopListening();
    //     }
    // }

    Variable* _presenter = new Variable(presenterType, "_presenter");
    proxyClass->elements.push_back(new Field(PRIVATE, _presenter));

    Variable* listener = new Variable(listenerType, "listener");

    Method* startListeningMethod = new Method;
        startListeningMethod->modifiers = PUBLIC;
        startListeningMethod->returnType = VOID_TYPE;
        startListeningMethod->name = "startListening";
        startListeningMethod->statements = new StatementBlock;
        startListeningMethod->parameters.push_back(listener);
    proxyClass->elements.push_back(startListeningMethod);

    startListeningMethod->statements->Add(new MethodCall(THIS_VALUE, "stopListening"));
    startListeningMethod->statements->Add(new Assignment(_presenter,
                new NewExpression(presenterType, 2, proxyClass->broker, listener)));
    startListeningMethod->statements->Add(new MethodCall(_presenter,
                "startListening", 1, proxyClass->endpoint));

    Method* stopListeningMethod = new Method;
        stopListeningMethod->modifiers = PUBLIC;
        stopListeningMethod->returnType = VOID_TYPE;
        stopListeningMethod->name = "stopListening";
        stopListeningMethod->statements = new StatementBlock;
    proxyClass->elements.push_back(stopListeningMethod);

    IfStatement* ifst = new IfStatement;
        ifst->expression = new Comparison(_presenter, "!=", NULL_VALUE);
    stopListeningMethod->statements->Add(ifst);

    ifst->statements->Add(new MethodCall(_presenter, "stopListening"));
    ifst->statements->Add(new Assignment(_presenter, NULL_VALUE));
}

Class*
generate_rpc_interface_class(const interface_type* iface)
{
    // the proxy class
    InterfaceType* interfaceType = static_cast<InterfaceType*>(
        NAMES.Find(iface->package, iface->name.data));
    RpcProxyClass* proxy = new RpcProxyClass(iface, interfaceType);

    // the listener class
    ListenerClass* listener = new ListenerClass(iface);

    // the presenter class
    EventListenerClass* presenter = new EventListenerClass(iface, listener->type);

    // the service base class
    EndpointBaseClass* base = new EndpointBaseClass(iface);
    proxy->elements.push_back(base);

    // the result dispatcher
    ResultDispatcherClass* results = new ResultDispatcherClass();

    // all the declared methods of the proxy
    int index = 0;
    interface_item_type* item = iface->interface_items;
    while (item != NULL) {
        if (item->item_type == METHOD_TYPE) {
            if (NAMES.Search(((method_type*)item)->type.type.data) == EVENT_FAKE_TYPE) {
                generate_event_method((method_type*)item, proxy, base, listener, presenter, index);
            } else {
                generate_regular_method((method_type*)item, proxy, base, results, index);
            }
        }
        item = item->next;
        index++;
    }
    presenter->DoneWithMethods();
    base->DoneWithMethods();

    // only add this if there are methods with results / out parameters
    if (results->needed) {
        proxy->elements.push_back(results);
    }
    if (listener->needed) {
        proxy->elements.push_back(listener);
        proxy->elements.push_back(presenter);
        generate_listener_methods(proxy, presenter->type, listener->type);
    }

    return proxy;
}
