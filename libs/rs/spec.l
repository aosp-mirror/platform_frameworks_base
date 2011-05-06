%option stack

%x comment
%x api_entry
%x api_entry2
%x api_entry_param
%x var_type

DIGIT    [0-9]
ID       [a-zA-Z_][a-zA-Z0-9_]*

    #include "spec.h"

   int num_lines = 0;

   VarType *currType = 0;

   ApiEntry apis[128];
   int apiCount = 0;

   int typeNextState;

   void checkPointerType() {
       VarType *baseType = currType;
       int curPtrLevel = 0;
       while (curPtrLevel < baseType->ptrLevel) {
           currType = &apis[apiCount].params[apis[apiCount].paramCount];
           currType->type = 4;
           currType->ptrLevel = curPtrLevel;
           if (currType->ptrLevel > 0) {
              currType->isConst = 1;
           }
           sprintf(currType->typeName, "%s", "size_t");
           switch(baseType->ptrLevel - curPtrLevel) {
           case 1:
              sprintf(currType->name, "%s_length", baseType->name);
              break;
           case 2:
              sprintf(currType->name, "%s_length_length", baseType->name);
              break;
           }
           apis[apiCount].paramCount++;
           curPtrLevel ++;
       }
   }

   extern "C" int yylex();

%%

"/*"         BEGIN(comment);
<comment>[^*\n]*        /* eat anything that's not a '*' */
<comment>"*"+[^*/\n]*   /* eat up '*'s not followed by '/'s */
<comment>\n             ++num_lines;
<comment>"*"+"/"        BEGIN(INITIAL);

<*>" "   //printf("found ' '\n");
<*>"\t"   //printf("found ' '\n");
<*>"\n"  ++num_lines; //printf("found lf \n");

{ID} {
    memset(&apis[apiCount], 0, sizeof(ApiEntry));
    memcpy(apis[apiCount].name, yytext, yyleng);
    BEGIN(api_entry);
    }

<api_entry>"{" {
    BEGIN(api_entry2);
    }

<api_entry2>"sync" {
    apis[apiCount].sync = 1;
    }

<api_entry2>"handcodeApi" {
    apis[apiCount].handcodeApi = 1;
    }

<api_entry2>"direct" {
    apis[apiCount].direct = 1;
    }

<api_entry2>"nocontext" {
    apis[apiCount].nocontext = 1;
    }

<api_entry2>"ret" {
    currType = &apis[apiCount].ret;
    typeNextState = api_entry2;
    BEGIN(var_type);
    }

<api_entry2>"param" {
    currType = &apis[apiCount].params[apis[apiCount].paramCount];
    apis[apiCount].paramCount++;
    typeNextState = api_entry_param;
    BEGIN(var_type);
    }

<var_type>"const" {
    currType->isConst = 1;
    }

<var_type>"i8" {
    currType->type = 1;
    currType->bits = 8;
    BEGIN(typeNextState);
    }

<var_type>"i16" {
    currType->type = 1;
    currType->bits = 16;
    BEGIN(typeNextState);
    }

<var_type>"i32" {
    currType->type = 1;
    currType->bits = 32;
    BEGIN(typeNextState);
    }

<var_type>"i64" {
    currType->type = 1;
    currType->bits = 64;
    BEGIN(typeNextState);
    }

<var_type>"u8" {
    currType->type = 2;
    currType->bits = 8;
    BEGIN(typeNextState);
    }

<var_type>"u16" {
    currType->type = 2;
    currType->bits = 16;
    BEGIN(typeNextState);
    }

<var_type>"u32" {
    currType->type = 2;
    currType->bits = 32;
    BEGIN(typeNextState);
    }

<var_type>"u64" {
    currType->type = 2;
    currType->bits = 64;
    BEGIN(typeNextState);
    }

<var_type>"f" {
    currType->type = 3;
    currType->bits = 32;
    BEGIN(typeNextState);
    }

<var_type>"d" {
    currType->type = 3;
    currType->bits = 64;
    BEGIN(typeNextState);
    }

<var_type>{ID} {
    currType->type = 4;
    currType->bits = 32;
    memcpy(currType->typeName, yytext, yyleng);
    BEGIN(typeNextState);
    }

<api_entry_param>"*" {
    currType->ptrLevel ++;
    }

<api_entry_param>{ID} {
    memcpy(currType->name, yytext, yyleng);
    checkPointerType();
    BEGIN(api_entry2);
    }

<api_entry2>"*" {
    currType->ptrLevel ++;
    }

<api_entry2>"}" {
    apiCount++;
    BEGIN(INITIAL);
    }


%%


int yywrap()
{
    return 1;
}

