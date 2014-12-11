grammar DataBinder;

start : expr ;

expr
    : nil #nilExpr
	| '(' inner=expr ')' #innerExpr
	| ownerObj=expr '.' methodName=Identifier '(' arguments=exprList? ')' #methodCallExpr
	| methodName=Identifier '(' arguments=exprList? ')' #globalMethodCallExpr
	| atomString=hackyStringSymbol#hackyStringExpr
	| atom=symbol#atomExpr
	| field #idExpr
    | left=expr op=('*'|'/') right=expr #opExpr
    | left=expr op=('+'|'-') right=expr #opExpr
    | left=expr '==' right=expr #eqExpr
    | pred=expr '?' t=expr ':' f=expr #ternaryExpr
     ;

exprList
    :   expr (',' expr)*
    ;

//methodCall
	//: ownerObj=field '.' methodName=Identifier '(' arguments=exprList? ')' #methodCallOnFieldExpr
	//| ownerMethod=methodCall '.' methodName=Identifier '(' arguments=exprList? ')' #methodCallOnMethodExpr
	//| methodName=Identifier '(' arguments=exprList? ')' #globalMethodCallExpr
	//: ownerObj=expr '.' methodName=Identifier '(' arguments=exprList? ')' #methodCallOnFieldExpr
	//;

field
	: name=Identifier ('.' Identifier)*
	;

Identifier
    :   JavaLetter JavaLetterOrDigit*
    ;

hackyStringSymbol
    : HackyStringLiteral
    ;

symbol
	: INT
	| BOOLEAN
	| StringLiteral
	| CharacterLiteral
	;

INT : ('0'..'9')+ ;

BOOLEAN : ('true'|'false');

nil: 'null';

fragment
JavaLetter
    :   [a-zA-Z$_] // these are the "java letters" below 0xFF
    |   // covers all characters above 0xFF which are not a surrogate
        ~[\u0000-\u00FF\uD800-\uDBFF]
        {Character.isJavaIdentifierStart(_input.LA(-1))}?
    |   // covers UTF-16 surrogate pairs encodings for U+10000 to U+10FFFF
        [\uD800-\uDBFF] [\uDC00-\uDFFF]
        {Character.isJavaIdentifierStart(Character.toCodePoint((char)_input.LA(-2), (char)_input.LA(-1)))}?
    ;

fragment
JavaLetterOrDigit
    :   [a-zA-Z0-9$_] // these are the "java letters or digits" below 0xFF
    |   // covers all characters above 0xFF which are not a surrogate
        ~[\u0000-\u00FF\uD800-\uDBFF]
        {Character.isJavaIdentifierPart(_input.LA(-1))}?
    |   // covers UTF-16 surrogate pairs encodings for U+10000 to U+10FFFF
        [\uD800-\uDBFF] [\uDC00-\uDFFF]
        {Character.isJavaIdentifierPart(Character.toCodePoint((char)_input.LA(-2), (char)_input.LA(-1)))}?
    ;

HackyStringLiteral
    :   '`' StringCharacters? '`'
    ;
StringLiteral
    :   '"' StringCharacters? '"'
    ;

CharacterLiteral
    :   '\'' SingleCharacter '\''
    ;

fragment
SingleCharacter
    :   ~['\\]
    ;

fragment
StringCharacters
    :   StringCharacter+
    ;

fragment
StringCharacter
    :   ~["\\]
    ;


WS : [ \t\r\n]+ -> skip ;
