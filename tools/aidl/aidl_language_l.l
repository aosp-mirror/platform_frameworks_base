%{
#include "aidl_language.h"
#include "aidl_language_y.h"
#include "search_path.h"
#include <string.h>
#include <stdlib.h>

extern YYSTYPE yylval;

// comment and whitespace handling
// these functions save a copy of the buffer
static void begin_extra_text(unsigned lineno, which_extra_text which);
static void append_extra_text(char* text);
static extra_text_type* get_extra_text(void);   // you now own the object
                                                // this returns
static void drop_extra_text(void);

// package handling
static void do_package_statement(const char* importText);

#define SET_BUFFER(t) \
    do { \
        yylval.buffer.lineno = yylineno; \
        yylval.buffer.token = (t); \
        yylval.buffer.data = strdup(yytext); \
        yylval.buffer.extra = get_extra_text(); \
    } while(0)

%}

%option yylineno
%option noyywrap

%x COPYING LONG_COMMENT

identifier  [_a-zA-Z][_a-zA-Z0-9\.]*
whitespace  ([ \t\n\r]+)
brackets    \[{whitespace}?\]

%%


\%\%\{              { begin_extra_text(yylineno, COPY_TEXT); BEGIN(COPYING); }
<COPYING>\}\%\%     { BEGIN(INITIAL); }
<COPYING>.*\n       { append_extra_text(yytext); }
<COPYING>.*         { append_extra_text(yytext); }
<COPYING>\n+        { append_extra_text(yytext); }


\/\*                            { begin_extra_text(yylineno, (which_extra_text)LONG_COMMENT);
                                  BEGIN(LONG_COMMENT); }
<LONG_COMMENT>[^*]*             { append_extra_text(yytext); }
<LONG_COMMENT>\*+[^/]           { append_extra_text(yytext); }
<LONG_COMMENT>\n                { append_extra_text(yytext); }
<LONG_COMMENT>\**\/             { BEGIN(INITIAL); }

^{whitespace}?import{whitespace}[^ \t\r\n]+{whitespace}?;  {
                                                SET_BUFFER(IMPORT);
                                                return IMPORT;
                                            }
^{whitespace}?package{whitespace}[^ \t\r\n]+{whitespace}?;  {
                                                do_package_statement(yytext);
                                                SET_BUFFER(PACKAGE);
                                                return PACKAGE;
                                            }
<<EOF>>             { yyterminate(); }

\/\/.*\n            { begin_extra_text(yylineno, SHORT_COMMENT);
                        append_extra_text(yytext); }

{whitespace}    { /* begin_extra_text(yylineno, WHITESPACE);
                    append_extra_text(yytext); */ }

;               { SET_BUFFER(';'); return ';'; }
\{              { SET_BUFFER('{'); return '{'; }
\}              { SET_BUFFER('}'); return '}'; }
\(              { SET_BUFFER('('); return '('; }
\)              { SET_BUFFER(')'); return ')'; }
,               { SET_BUFFER(','); return ','; }

    /* keywords */
parcelable      { SET_BUFFER(PARCELABLE); return PARCELABLE; }
interface       { SET_BUFFER(INTERFACE); return INTERFACE; }
rpc             { SET_BUFFER(INTERFACE); return RPC; }
in              { SET_BUFFER(IN); return IN; }
out             { SET_BUFFER(OUT); return OUT; }
inout           { SET_BUFFER(INOUT); return INOUT; }
oneway          { SET_BUFFER(ONEWAY); return ONEWAY; }

{brackets}+     { SET_BUFFER(ARRAY); return ARRAY; }

{identifier}                                        { SET_BUFFER(IDENTIFIER); return IDENTIFIER; }
{identifier}\<{whitespace}*{identifier}({whitespace}*,{whitespace}*{identifier})*{whitespace}*\>    {
                                                      SET_BUFFER(GENERIC); return GENERIC; }

    /* syntax error! */
.               { printf("UNKNOWN(%s)", yytext);
                  yylval.buffer.lineno = yylineno;
                  yylval.buffer.token = IDENTIFIER;
                  yylval.buffer.data = strdup(yytext);
                  return IDENTIFIER;
                }

%%

// comment and whitespace handling
// ================================================
extra_text_type* g_extraText = NULL;
extra_text_type* g_nextExtraText = NULL;

void begin_extra_text(unsigned lineno, which_extra_text which)
{
    extra_text_type* text = (extra_text_type*)malloc(sizeof(extra_text_type));
    text->lineno = lineno;
    text->which = which;
    text->data = NULL;
    text->len = 0;
    text->next = NULL;
    if (g_nextExtraText == NULL) {
        g_extraText = text;
    } else {
        g_nextExtraText->next = text;
    }
    g_nextExtraText = text;
}

void append_extra_text(char* text)
{
    if (g_nextExtraText->data == NULL) {
        g_nextExtraText->data = strdup(text);
        g_nextExtraText->len = strlen(text);
    } else {
        char* orig = g_nextExtraText->data;
        unsigned oldLen = g_nextExtraText->len;
        unsigned len = strlen(text);
        g_nextExtraText->len += len;
        g_nextExtraText->data = (char*)malloc(g_nextExtraText->len+1);
        memcpy(g_nextExtraText->data, orig, oldLen);
        memcpy(g_nextExtraText->data+oldLen, text, len);
        g_nextExtraText->data[g_nextExtraText->len] = '\0';
        free(orig);
    }
}

extra_text_type*
get_extra_text(void)
{
    extra_text_type* result = g_extraText;
    g_extraText = NULL;
    g_nextExtraText = NULL;
    return result;
}

void drop_extra_text(void)
{
    extra_text_type* p = g_extraText;
    while (p) {
        extra_text_type* next = p->next;
        free(p->data);
        free(p);
        free(next);
    }
    g_extraText = NULL;
    g_nextExtraText = NULL;
}


// package handling
// ================================================
void do_package_statement(const char* importText)
{
    if (g_currentPackage) free((void*)g_currentPackage);
    g_currentPackage = parse_import_statement(importText);
}


// main parse function
// ================================================
char const* g_currentFilename = NULL;
char const* g_currentPackage = NULL;

int yyparse(void);

int parse_aidl(char const *filename)
{
    yyin = fopen(filename, "r");
    if (yyin) {
        char const* oldFilename = g_currentFilename;
        char const* oldPackage = g_currentPackage;
        g_currentFilename = strdup(filename);

        g_error = 0;
        yylineno = 1;
        int rv = yyparse();
        if (g_error != 0) {
            rv = g_error;
        }

        free((void*)g_currentFilename);
        g_currentFilename = oldFilename;
        
        if (g_currentPackage) free((void*)g_currentPackage);
        g_currentPackage = oldPackage;

        return rv;
    } else {
        fprintf(stderr, "aidl: unable to open file for read: %s\n", filename);
        return 1;
    }
}

