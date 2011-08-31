#include "AST.h"
#include "Type.h"

void
WriteModifiers(FILE* to, int mod, int mask)
{
    int m = mod & mask;

    if (m & OVERRIDE) {
        fprintf(to, "@Override ");
    }

    if ((m & SCOPE_MASK) == PUBLIC) {
        fprintf(to, "public ");
    }
    else if ((m & SCOPE_MASK) == PRIVATE) {
        fprintf(to, "private ");
    }
    else if ((m & SCOPE_MASK) == PROTECTED) {
        fprintf(to, "protected ");
    }

    if (m & STATIC) {
        fprintf(to, "static ");
    }
    
    if (m & FINAL) {
        fprintf(to, "final ");
    }

    if (m & ABSTRACT) {
        fprintf(to, "abstract ");
    }
}

void
WriteArgumentList(FILE* to, const vector<Expression*>& arguments)
{
    size_t N = arguments.size();
    for (size_t i=0; i<N; i++) {
        arguments[i]->Write(to);
        if (i != N-1) {
            fprintf(to, ", ");
        }
    }
}

ClassElement::ClassElement()
{
}

ClassElement::~ClassElement()
{
}

Field::Field()
    :ClassElement(),
     modifiers(0),
     variable(NULL)
{
}

Field::Field(int m, Variable* v)
    :ClassElement(),
     modifiers(m),
     variable(v)
{
}

Field::~Field()
{
}

void
Field::GatherTypes(set<Type*>* types) const
{
    types->insert(this->variable->type);
}

void
Field::Write(FILE* to)
{
    if (this->comment.length() != 0) {
        fprintf(to, "%s\n", this->comment.c_str());
    }
    WriteModifiers(to, this->modifiers, SCOPE_MASK | STATIC | FINAL | OVERRIDE);
    fprintf(to, "%s %s", this->variable->type->QualifiedName().c_str(),
            this->variable->name.c_str());
    if (this->value.length() != 0) {
        fprintf(to, " = %s", this->value.c_str());
    }
    fprintf(to, ";\n");
}

Expression::~Expression()
{
}

LiteralExpression::LiteralExpression(const string& v)
    :value(v)
{
}

LiteralExpression::~LiteralExpression()
{
}

void
LiteralExpression::Write(FILE* to)
{
    fprintf(to, "%s", this->value.c_str());
}

StringLiteralExpression::StringLiteralExpression(const string& v)
    :value(v)
{
}

StringLiteralExpression::~StringLiteralExpression()
{
}

void
StringLiteralExpression::Write(FILE* to)
{
    fprintf(to, "\"%s\"", this->value.c_str());
}

Variable::Variable()
    :type(NULL),
     name(),
     dimension(0)
{
}

Variable::Variable(Type* t, const string& n)
    :type(t),
     name(n),
     dimension(0)
{
}

Variable::Variable(Type* t, const string& n, int d)
    :type(t),
     name(n),
     dimension(d)
{
}

Variable::~Variable()
{
}

void
Variable::GatherTypes(set<Type*>* types) const
{
    types->insert(this->type);
}

void
Variable::WriteDeclaration(FILE* to)
{
    string dim;
    for (int i=0; i<this->dimension; i++) {
        dim += "[]";
    }
    fprintf(to, "%s%s %s", this->type->QualifiedName().c_str(), dim.c_str(),
            this->name.c_str());
}

void
Variable::Write(FILE* to)
{
    fprintf(to, "%s", name.c_str());
}

FieldVariable::FieldVariable(Expression* o, const string& n)
    :object(o),
     clazz(NULL),
     name(n)
{
}

FieldVariable::FieldVariable(Type* c, const string& n)
    :object(NULL),
     clazz(c),
     name(n)
{
}

FieldVariable::~FieldVariable()
{
}

void
FieldVariable::Write(FILE* to)
{
    if (this->object != NULL) {
        this->object->Write(to);
    }
    else if (this->clazz != NULL) {
        fprintf(to, "%s", this->clazz->QualifiedName().c_str());
    }
    fprintf(to, ".%s", name.c_str());
}


Statement::~Statement()
{
}

StatementBlock::StatementBlock()
{
}

StatementBlock::~StatementBlock()
{
}

void
StatementBlock::Write(FILE* to)
{
    fprintf(to, "{\n");
    int N = this->statements.size();
    for (int i=0; i<N; i++) {
        this->statements[i]->Write(to);
    }
    fprintf(to, "}\n");
}

void
StatementBlock::Add(Statement* statement)
{
    this->statements.push_back(statement);
}

void
StatementBlock::Add(Expression* expression)
{
    this->statements.push_back(new ExpressionStatement(expression));
}

ExpressionStatement::ExpressionStatement(Expression* e)
    :expression(e)
{
}

ExpressionStatement::~ExpressionStatement()
{
}

void
ExpressionStatement::Write(FILE* to)
{
    this->expression->Write(to);
    fprintf(to, ";\n");
}

Assignment::Assignment(Variable* l, Expression* r)
    :lvalue(l),
     rvalue(r),
     cast(NULL)
{
}

Assignment::Assignment(Variable* l, Expression* r, Type* c)
    :lvalue(l),
     rvalue(r),
     cast(c)
{
}

Assignment::~Assignment()
{
}

void
Assignment::Write(FILE* to)
{
    this->lvalue->Write(to);
    fprintf(to, " = ");
    if (this->cast != NULL) {
        fprintf(to, "(%s)", this->cast->QualifiedName().c_str());
    }
    this->rvalue->Write(to);
}

MethodCall::MethodCall(const string& n)
    :obj(NULL),
     clazz(NULL),
     name(n)
{
}

MethodCall::MethodCall(const string& n, int argc = 0, ...)
    :obj(NULL),
     clazz(NULL),
     name(n)
{
  va_list args;
  va_start(args, argc);
  init(argc, args);
  va_end(args);
}

MethodCall::MethodCall(Expression* o, const string& n)
    :obj(o),
     clazz(NULL),
     name(n)
{
}

MethodCall::MethodCall(Type* t, const string& n)
    :obj(NULL),
     clazz(t),
     name(n)
{
}

MethodCall::MethodCall(Expression* o, const string& n, int argc = 0, ...)
    :obj(o),
     clazz(NULL),
     name(n)
{
  va_list args;
  va_start(args, argc);
  init(argc, args);
  va_end(args);
}

MethodCall::MethodCall(Type* t, const string& n, int argc = 0, ...)
    :obj(NULL),
     clazz(t),
     name(n)
{
  va_list args;
  va_start(args, argc);
  init(argc, args);
  va_end(args);
}

MethodCall::~MethodCall()
{
}

void
MethodCall::init(int n, va_list args)
{
    for (int i=0; i<n; i++) {
        Expression* expression = (Expression*)va_arg(args, void*);
        this->arguments.push_back(expression);
    }
}

void
MethodCall::Write(FILE* to)
{
    if (this->obj != NULL) {
        this->obj->Write(to);
        fprintf(to, ".");
    }
    else if (this->clazz != NULL) {
        fprintf(to, "%s.", this->clazz->QualifiedName().c_str());
    }
    fprintf(to, "%s(", this->name.c_str());
    WriteArgumentList(to, this->arguments);
    fprintf(to, ")");
}

Comparison::Comparison(Expression* l, const string& o, Expression* r)
    :lvalue(l),
     op(o),
     rvalue(r)
{
}

Comparison::~Comparison()
{
}

void
Comparison::Write(FILE* to)
{
    fprintf(to, "(");
    this->lvalue->Write(to);
    fprintf(to, "%s", this->op.c_str());
    this->rvalue->Write(to);
    fprintf(to, ")");
}

NewExpression::NewExpression(Type* t)
    :type(t)
{
}

NewExpression::NewExpression(Type* t, int argc = 0, ...)
    :type(t)
{
  va_list args;
  va_start(args, argc);
  init(argc, args);
  va_end(args);
}

NewExpression::~NewExpression()
{
}

void
NewExpression::init(int n, va_list args)
{
    for (int i=0; i<n; i++) {
        Expression* expression = (Expression*)va_arg(args, void*);
        this->arguments.push_back(expression);
    }
}

void
NewExpression::Write(FILE* to)
{
    fprintf(to, "new %s(", this->type->InstantiableName().c_str());
    WriteArgumentList(to, this->arguments);
    fprintf(to, ")");
}

NewArrayExpression::NewArrayExpression(Type* t, Expression* s)
    :type(t),
     size(s)
{
}

NewArrayExpression::~NewArrayExpression()
{
}

void
NewArrayExpression::Write(FILE* to)
{
    fprintf(to, "new %s[", this->type->QualifiedName().c_str());
    size->Write(to);
    fprintf(to, "]");
}

Ternary::Ternary()
    :condition(NULL),
     ifpart(NULL),
     elsepart(NULL)
{
}

Ternary::Ternary(Expression* a, Expression* b, Expression* c)
    :condition(a),
     ifpart(b),
     elsepart(c)
{
}

Ternary::~Ternary()
{
}

void
Ternary::Write(FILE* to)
{
    fprintf(to, "((");
    this->condition->Write(to);
    fprintf(to, ")?(");
    this->ifpart->Write(to);
    fprintf(to, "):(");
    this->elsepart->Write(to);
    fprintf(to, "))");
}

Cast::Cast()
    :type(NULL),
     expression(NULL)
{
}

Cast::Cast(Type* t, Expression* e)
    :type(t),
     expression(e)
{
}

Cast::~Cast()
{
}

void
Cast::Write(FILE* to)
{
    fprintf(to, "((%s)", this->type->QualifiedName().c_str());
    expression->Write(to);
    fprintf(to, ")");
}

VariableDeclaration::VariableDeclaration(Variable* l, Expression* r, Type* c)
    :lvalue(l),
     cast(c),
     rvalue(r)
{
}

VariableDeclaration::VariableDeclaration(Variable* l)
    :lvalue(l),
     cast(NULL),
     rvalue(NULL)
{
}

VariableDeclaration::~VariableDeclaration()
{
}

void
VariableDeclaration::Write(FILE* to)
{
    this->lvalue->WriteDeclaration(to);
    if (this->rvalue != NULL) {
        fprintf(to, " = ");
        if (this->cast != NULL) {
            fprintf(to, "(%s)", this->cast->QualifiedName().c_str());
        }
        this->rvalue->Write(to);
    }
    fprintf(to, ";\n");
}

IfStatement::IfStatement()
    :expression(NULL),
     statements(new StatementBlock),
     elseif(NULL)
{
}

IfStatement::~IfStatement()
{
}

void
IfStatement::Write(FILE* to)
{
    if (this->expression != NULL) {
        fprintf(to, "if (");
        this->expression->Write(to);
        fprintf(to, ") ");
    }
    this->statements->Write(to);
    if (this->elseif != NULL) {
        fprintf(to, "else ");
        this->elseif->Write(to);
    }
}

ReturnStatement::ReturnStatement(Expression* e)
    :expression(e)
{
}

ReturnStatement::~ReturnStatement()
{
}

void
ReturnStatement::Write(FILE* to)
{
    fprintf(to, "return ");
    this->expression->Write(to);
    fprintf(to, ";\n");
}

TryStatement::TryStatement()
    :statements(new StatementBlock)
{
}

TryStatement::~TryStatement()
{
}

void
TryStatement::Write(FILE* to)
{
    fprintf(to, "try ");
    this->statements->Write(to);
}

CatchStatement::CatchStatement(Variable* e)
    :statements(new StatementBlock),
     exception(e)
{
}

CatchStatement::~CatchStatement()
{
}

void
CatchStatement::Write(FILE* to)
{
    fprintf(to, "catch ");
    if (this->exception != NULL) {
        fprintf(to, "(");
        this->exception->WriteDeclaration(to);
        fprintf(to, ") ");
    }
    this->statements->Write(to);
}

FinallyStatement::FinallyStatement()
    :statements(new StatementBlock)
{
}

FinallyStatement::~FinallyStatement()
{
}

void
FinallyStatement::Write(FILE* to)
{
    fprintf(to, "finally ");
    this->statements->Write(to);
}

Case::Case()
    :statements(new StatementBlock)
{
}

Case::Case(const string& c)
    :statements(new StatementBlock)
{
    cases.push_back(c);
}

Case::~Case()
{
}

void
Case::Write(FILE* to)
{
    int N = this->cases.size();
    if (N > 0) {
        for (int i=0; i<N; i++) {
            string s = this->cases[i];
            if (s.length() != 0) {
                fprintf(to, "case %s:\n", s.c_str());
            } else {
                fprintf(to, "default:\n");
            }
        }
    } else {
        fprintf(to, "default:\n");
    }
    statements->Write(to);
}

SwitchStatement::SwitchStatement(Expression* e)
    :expression(e)
{
}

SwitchStatement::~SwitchStatement()
{
}

void
SwitchStatement::Write(FILE* to)
{
    fprintf(to, "switch (");
    this->expression->Write(to);
    fprintf(to, ")\n{\n");
    int N = this->cases.size();
    for (int i=0; i<N; i++) {
        this->cases[i]->Write(to);
    }
    fprintf(to, "}\n");
}

Method::Method()
    :ClassElement(),
     modifiers(0),
     returnType(NULL), // (NULL means constructor)
     returnTypeDimension(0),
     statements(NULL)
{
}

Method::~Method()
{
}

void
Method::GatherTypes(set<Type*>* types) const
{
    size_t N, i;

    if (this->returnType) {
        types->insert(this->returnType);
    }

    N = this->parameters.size();
    for (i=0; i<N; i++) {
        this->parameters[i]->GatherTypes(types);
    }

    N = this->exceptions.size();
    for (i=0; i<N; i++) {
        types->insert(this->exceptions[i]);
    }
}

void
Method::Write(FILE* to)
{
    size_t N, i;

    if (this->comment.length() != 0) {
        fprintf(to, "%s\n", this->comment.c_str());
    }

    WriteModifiers(to, this->modifiers, SCOPE_MASK | STATIC | ABSTRACT | FINAL | OVERRIDE);

    if (this->returnType != NULL) {
        string dim;
        for (i=0; i<this->returnTypeDimension; i++) {
            dim += "[]";
        }
        fprintf(to, "%s%s ", this->returnType->QualifiedName().c_str(),
                dim.c_str());
    }
   
    fprintf(to, "%s(", this->name.c_str());

    N = this->parameters.size();
    for (i=0; i<N; i++) {
        this->parameters[i]->WriteDeclaration(to);
        if (i != N-1) {
            fprintf(to, ", ");
        }
    }

    fprintf(to, ")");

    N = this->exceptions.size();
    for (i=0; i<N; i++) {
        if (i == 0) {
            fprintf(to, " throws ");
        } else {
            fprintf(to, ", ");
        }
        fprintf(to, "%s", this->exceptions[i]->QualifiedName().c_str());
    }

    if (this->statements == NULL) {
        fprintf(to, ";\n");
    } else {
        fprintf(to, "\n");
        this->statements->Write(to);
    }
}

Class::Class()
    :modifiers(0),
     what(CLASS),
     type(NULL),
     extends(NULL)
{
}

Class::~Class()
{
}

void
Class::GatherTypes(set<Type*>* types) const
{
    int N, i;

    types->insert(this->type);
    if (this->extends != NULL) {
        types->insert(this->extends);
    }

    N = this->interfaces.size();
    for (i=0; i<N; i++) {
        types->insert(this->interfaces[i]);
    }

    N = this->elements.size();
    for (i=0; i<N; i++) {
        this->elements[i]->GatherTypes(types);
    }
}

void
Class::Write(FILE* to)
{
    size_t N, i;

    if (this->comment.length() != 0) {
        fprintf(to, "%s\n", this->comment.c_str());
    }

    WriteModifiers(to, this->modifiers, ALL_MODIFIERS);

    if (this->what == Class::CLASS) {
        fprintf(to, "class ");
    } else {
        fprintf(to, "interface ");
    }

    string name = this->type->Name();
    size_t pos = name.rfind('.');
    if (pos != string::npos) {
        name = name.c_str() + pos + 1;
    }

    fprintf(to, "%s", name.c_str());

    if (this->extends != NULL) {
        fprintf(to, " extends %s", this->extends->QualifiedName().c_str());
    }

    N = this->interfaces.size();
    if (N != 0) {
        if (this->what == Class::CLASS) {
            fprintf(to, " implements");
        } else {
            fprintf(to, " extends");
        }
        for (i=0; i<N; i++) {
            fprintf(to, " %s", this->interfaces[i]->QualifiedName().c_str());
        }
    }

    fprintf(to, "\n");
    fprintf(to, "{\n");

    N = this->elements.size();
    for (i=0; i<N; i++) {
        this->elements[i]->Write(to);
    }

    fprintf(to, "}\n");

}

Document::Document()
{
}

Document::~Document()
{
}

static string
escape_backslashes(const string& str)
{
    string result;
    const size_t I=str.length();
    for (size_t i=0; i<I; i++) {
        char c = str[i];
        if (c == '\\') {
            result += "\\\\";
        } else {
            result += c;
        }
    }
    return result;
}

void
Document::Write(FILE* to)
{
    size_t N, i;

    if (this->comment.length() != 0) {
        fprintf(to, "%s\n", this->comment.c_str());
    }
    fprintf(to, "/*\n"
                " * This file is auto-generated.  DO NOT MODIFY.\n"
                " * Original file: %s\n"
                " */\n", escape_backslashes(this->originalSrc).c_str());
    if (this->package.length() != 0) {
        fprintf(to, "package %s;\n", this->package.c_str());
    }

    N = this->classes.size();
    for (i=0; i<N; i++) {
        Class* c = this->classes[i];
        c->Write(to);
    }
}

