%{
#include "aidl_language.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int yyerror(char* errstr);
int yylex(void);
extern int yylineno;

static int count_brackets(const char*);

%}

%token IMPORT
%token PACKAGE
%token IDENTIFIER
%token GENERIC
%token ARRAY
%token PARCELABLE
%token INTERFACE
%token RPC
%token IN
%token OUT
%token INOUT
%token ONEWAY

%%
document:
        document_items                          { g_callbacks->document($1.document_item); }
    |   headers document_items                  { g_callbacks->document($2.document_item); }
    ;

headers:
        package                                 { }
    |   imports                                 { }
    |   package imports                         { }
    ;

package:
        PACKAGE                                 { }
    ;

imports:
        IMPORT                                  { g_callbacks->import(&($1.buffer)); }
    |   IMPORT imports                          { g_callbacks->import(&($1.buffer)); }
    ;

document_items:
                                                { $$.document_item = NULL; }
    |   document_items declaration              {
                                                    if ($2.document_item == NULL) {
                                                        // error cases only
                                                        $$ = $1;
                                                    } else {
                                                        document_item_type* p = $1.document_item;
                                                        while (p && p->next) {
                                                            p=p->next;
                                                        }
                                                        if (p) {
                                                            p->next = (document_item_type*)$2.document_item;
                                                            $$ = $1;
                                                        } else {
                                                            $$.document_item = (document_item_type*)$2.document_item;
                                                        }
                                                    }
                                                }
    | document_items error                      {
                                                    fprintf(stderr, "%s:%d: syntax error don't know what to do with \"%s\"\n", g_currentFilename,
                                                            $2.buffer.lineno, $2.buffer.data);
                                                    $$ = $1;
                                                }
    ;

declaration:
        parcelable_decl                            { $$.document_item = (document_item_type*)$1.parcelable; }
    |   interface_decl                             { $$.document_item = (document_item_type*)$1.interface_item; }
    ;

parcelable_decl:
        PARCELABLE IDENTIFIER ';'                  { 
                                                        parcelable_type* b = (parcelable_type*)malloc(sizeof(parcelable_type));
                                                        b->document_item.item_type = PARCELABLE_TYPE;
                                                        b->document_item.next = NULL;
                                                        b->parcelable_token = $1.buffer;
                                                        b->name = $2.buffer;
                                                        b->package = g_currentPackage ? strdup(g_currentPackage) : NULL;
                                                        b->semicolon_token = $3.buffer;
                                                        $$.parcelable = b;
                                                    }
    |   PARCELABLE ';'                              {
                                                        fprintf(stderr, "%s:%d syntax error in parcelable declaration. Expected type name.\n",
                                                                     g_currentFilename, $1.buffer.lineno);
                                                        $$.parcelable = NULL;
                                                    }
    |   PARCELABLE error ';'                        {
                                                        fprintf(stderr, "%s:%d syntax error in parcelable declaration. Expected type name, saw \"%s\".\n",
                                                                     g_currentFilename, $2.buffer.lineno, $2.buffer.data);
                                                        $$.parcelable = NULL;
                                                    }
    ;

interface_header:
        INTERFACE                                  {
                                                        interface_type* c = (interface_type*)malloc(sizeof(interface_type));
                                                        c->document_item.item_type = INTERFACE_TYPE_BINDER;
                                                        c->document_item.next = NULL;
                                                        c->interface_token = $1.buffer;
                                                        c->oneway = false;
                                                        memset(&c->oneway_token, 0, sizeof(buffer_type));
                                                        c->comments_token = &c->interface_token;
                                                        $$.interface_obj = c;
                                                   }
    |   ONEWAY INTERFACE                           {
                                                        interface_type* c = (interface_type*)malloc(sizeof(interface_type));
                                                        c->document_item.item_type = INTERFACE_TYPE_BINDER;
                                                        c->document_item.next = NULL;
                                                        c->interface_token = $2.buffer;
                                                        c->oneway = true;
                                                        c->oneway_token = $1.buffer;
                                                        c->comments_token = &c->oneway_token;
                                                        $$.interface_obj = c;
                                                   }
    |   RPC                                        {
                                                        interface_type* c = (interface_type*)malloc(sizeof(interface_type));
                                                        c->document_item.item_type = INTERFACE_TYPE_RPC;
                                                        c->document_item.next = NULL;
                                                        c->interface_token = $1.buffer;
                                                        c->oneway = false;
                                                        memset(&c->oneway_token, 0, sizeof(buffer_type));
                                                        c->comments_token = &c->interface_token;
                                                        $$.interface_obj = c;
                                                   }
    ;

interface_keywords:
        INTERFACE
    |   RPC
    ;

interface_decl:
        interface_header IDENTIFIER '{' interface_items '}' { 
                                                        interface_type* c = $1.interface_obj;
                                                        c->name = $2.buffer;
                                                        c->package = g_currentPackage ? strdup(g_currentPackage) : NULL;
                                                        c->open_brace_token = $3.buffer;
                                                        c->interface_items = $4.interface_item;
                                                        c->close_brace_token = $5.buffer;
                                                        $$.interface_obj = c;
                                                    }
    |   interface_keywords error '{' interface_items '}'     {
                                                        fprintf(stderr, "%s:%d: syntax error in interface declaration.  Expected type name, saw \"%s\"\n",
                                                                    g_currentFilename, $2.buffer.lineno, $2.buffer.data);
                                                        $$.document_item = NULL;
                                                    }
    |   interface_keywords error '}'                {
                                                        fprintf(stderr, "%s:%d: syntax error in interface declaration.  Expected type name, saw \"%s\"\n",
                                                                    g_currentFilename, $2.buffer.lineno, $2.buffer.data);
                                                        $$.document_item = NULL;
                                                    }

    ;

interface_items:
                                                    { $$.interface_item = NULL; }
    |   interface_items method_decl                 {
                                                        interface_item_type* p=$1.interface_item;
                                                        while (p && p->next) {
                                                            p=p->next;
                                                        }
                                                        if (p) {
                                                            p->next = (interface_item_type*)$2.method;
                                                            $$ = $1;
                                                        } else {
                                                            $$.interface_item = (interface_item_type*)$2.method;
                                                        }
                                                    }
    |   interface_items error ';'                   {
                                                        fprintf(stderr, "%s:%d: syntax error before ';' (expected method declaration)\n",
                                                                    g_currentFilename, $3.buffer.lineno);
                                                        $$ = $1;
                                                    }
    ;

method_decl:
        type IDENTIFIER '(' arg_list ')' ';'  {
                                                        method_type *method = (method_type*)malloc(sizeof(method_type));
                                                        method->interface_item.item_type = METHOD_TYPE;
                                                        method->interface_item.next = NULL;
                                                        method->type = $1.type;
                                                        method->oneway = false;
                                                        memset(&method->oneway_token, 0, sizeof(buffer_type));
                                                        method->name = $2.buffer;
                                                        method->open_paren_token = $3.buffer;
                                                        method->args = $4.arg;
                                                        method->close_paren_token = $5.buffer;
                                                        method->semicolon_token = $6.buffer;
                                                        method->comments_token = &method->type.type;
                                                        $$.method = method;
                                                    }
    |   ONEWAY type IDENTIFIER '(' arg_list ')' ';'  {
                                                        method_type *method = (method_type*)malloc(sizeof(method_type));
                                                        method->interface_item.item_type = METHOD_TYPE;
                                                        method->interface_item.next = NULL;
                                                        method->oneway = true;
                                                        method->oneway_token = $1.buffer;
                                                        method->type = $2.type;
                                                        method->name = $3.buffer;
                                                        method->open_paren_token = $4.buffer;
                                                        method->args = $5.arg;
                                                        method->close_paren_token = $6.buffer;
                                                        method->semicolon_token = $7.buffer;
                                                        method->comments_token = &method->oneway_token;
                                                        $$.method = method;
                                                    }
    ;

arg_list:
                                { $$.arg = NULL; }
    |   arg                     { $$ = $1; }
    |   arg_list ',' arg        {
                                    if ($$.arg != NULL) {
                                        // only NULL on error
                                        $$ = $1;
                                        arg_type *p = $1.arg;
                                        while (p && p->next) {
                                            p=p->next;
                                        }
                                        $3.arg->comma_token = $2.buffer;
                                        p->next = $3.arg;
                                    }
                                }
    |   error                   {
                                    fprintf(stderr, "%s:%d: syntax error in parameter list\n", g_currentFilename, $1.buffer.lineno);
                                    $$.arg = NULL;
                                }
    ;

arg:
        direction type IDENTIFIER     {
                                                arg_type* arg = (arg_type*)malloc(sizeof(arg_type));
                                                memset(&arg->comma_token, 0, sizeof(buffer_type));
                                                arg->direction = $1.buffer;
                                                arg->type = $2.type;
                                                arg->name = $3.buffer;
                                                arg->next = NULL;
                                                $$.arg = arg;
                                      }
    ;

type:
        IDENTIFIER              {
                                    $$.type.type = $1.buffer;
                                    init_buffer_type(&$$.type.array_token, yylineno);
                                    $$.type.dimension = 0;
                                }
    |   IDENTIFIER ARRAY        {
                                    $$.type.type = $1.buffer;
                                    $$.type.array_token = $2.buffer;
                                    $$.type.dimension = count_brackets($2.buffer.data);
                                }
    |   GENERIC                 {
                                    $$.type.type = $1.buffer;
                                    init_buffer_type(&$$.type.array_token, yylineno);
                                    $$.type.dimension = 0;
                                }
    ;

direction:
                    { init_buffer_type(&$$.buffer, yylineno); }
    |   IN          { $$.buffer = $1.buffer; }
    |   OUT         { $$.buffer = $1.buffer; }
    |   INOUT       { $$.buffer = $1.buffer; }
    ;

%%

#include <ctype.h>
#include <stdio.h>

int g_error = 0;

int yyerror(char* errstr)
{
    fprintf(stderr, "%s:%d: %s\n", g_currentFilename, yylineno, errstr);
    g_error = 1;
    return 1;
}

void init_buffer_type(buffer_type* buf, int lineno)
{
    buf->lineno = lineno;
    buf->token = 0;
    buf->data = NULL;
    buf->extra = NULL;
}

static int count_brackets(const char* s)
{
    int n=0;
    while (*s) {
        if (*s == '[') n++;
        s++;
    }
    return n;
}
