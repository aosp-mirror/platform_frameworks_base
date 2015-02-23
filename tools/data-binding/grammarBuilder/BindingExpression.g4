/*
 * This grammar is a tweaked subset of that in Java.g4, which has a
 * BSD license and is redistributed along with this.
 */
grammar BindingExpression;

bindingSyntax
    :   expression defaults?
    ;

defaults
    :   ',' 'default' '=' constantValue
    ;
constantValue
    :   literal
    |   ResourceReference
    |   identifier
    ;

expression
    :   '(' expression ')'                              # Grouping
// this isn't allowed yet.
//    |   THIS                                            # Primary
    |   literal                                         # Primary
    |   identifier                                      # Primary
    |   classExtraction                                 # Primary
    |   resources                                       # Resource
//    |   typeArguments (explicitGenericInvocationSuffix | 'this' arguments) # GenericCall
    |   expression '.' Identifier                       # DotOp
//    |   expression '.' 'this'                           # ThisReference
//    |   expression '.' explicitGenericInvocation        # ExplicitGenericInvocationOp
    |   expression '[' expression ']'                   # BracketOp
    |   target=expression '.' methodName=Identifier '(' args=expressionList? ')' # MethodInvocation
    |   '(' type ')' expression                         # CastOp
    |   op=('+'|'-') expression                         # UnaryOp
    |   op=('~'|'!') expression                         # UnaryOp
    |   left=expression op=('*'|'/'|'%') right=expression             # MathOp
    |   left=expression op=('+'|'-') right=expression                 # MathOp
    |   left=expression op=('<<' | '>>>' | '>>') right=expression     # BitShiftOp
    |   left=expression op=('<=' | '>=' | '>' | '<') right=expression # ComparisonOp
    |   expression 'instanceof' type                    # InstanceOfOp
    |   left=expression op=('==' | '!=') right=expression             # ComparisonOp
    |   left=expression op='&' right=expression                       # BinaryOp
    |   left=expression op='^' right=expression                       # BinaryOp
    |   left=expression op='|' right=expression                       # BinaryOp
    |   left=expression op='&&' right=expression                      # AndOrOp
    |   left=expression op='||' right=expression                      # AndOrOp
    |   left=expression op='?' iftrue=expression ':' iffalse=expression        # TernaryOp
    |   left=expression op='??' right=expression                      # QuestionQuestionOp
    ;

THIS
    :   'this'
    ;

classExtraction
    :   type '.' 'class'
    |   'void' '.' 'class'
    ;

expressionList
    :   expression (',' expression)*
    ;

literal
    :   javaLiteral
    |   stringLiteral
    ;

identifier
    :   Identifier
    ;

javaLiteral
    :   IntegerLiteral
    |   FloatingPointLiteral
    |   BooleanLiteral
    |   NullLiteral
    |   CharacterLiteral
    ;

stringLiteral
    :   SingleQuoteString
    |   DoubleQuoteString
    ;

explicitGenericInvocation
    :   typeArguments explicitGenericInvocationSuffix
    ;

typeArguments
    :   '<' type (',' type)* '>'
    ;

type
    :   classOrInterfaceType ('[' ']')*
    |   primitiveType ('[' ']')*
    ;

explicitGenericInvocationSuffix
    :   Identifier arguments
    ;

arguments
    :   '(' expressionList? ')'
    ;

classOrInterfaceType
    :   identifier typeArguments? ('.' Identifier typeArguments? )*
    ;

primitiveType
    :   'boolean'
    |   'char'
    |   'byte'
    |   'short'
    |   'int'
    |   'long'
    |   'float'
    |   'double'
    ;

resources
    :   ResourceReference resourceParameters?
    ;

resourceParameters
    :   '(' expressionList ')'
    ;

// LEXER

// §3.10.1 Integer Literals

IntegerLiteral
    :   DecimalIntegerLiteral
    |   HexIntegerLiteral
    |   OctalIntegerLiteral
    |   BinaryIntegerLiteral
    ;

fragment
DecimalIntegerLiteral
    :   DecimalNumeral IntegerTypeSuffix?
    ;

fragment
HexIntegerLiteral
    :   HexNumeral IntegerTypeSuffix?
    ;

fragment
OctalIntegerLiteral
    :   OctalNumeral IntegerTypeSuffix?
    ;

fragment
BinaryIntegerLiteral
    :   BinaryNumeral IntegerTypeSuffix?
    ;

fragment
IntegerTypeSuffix
    :   [lL]
    ;

fragment
DecimalNumeral
    :   '0'
    |   NonZeroDigit (Digits? | Underscores Digits)
    ;

fragment
Digits
    :   Digit (DigitOrUnderscore* Digit)?
    ;

fragment
Digit
    :   '0'
    |   NonZeroDigit
    ;

fragment
NonZeroDigit
    :   [1-9]
    ;

fragment
DigitOrUnderscore
    :   Digit
    |   '_'
    ;

fragment
Underscores
    :   '_'+
    ;

fragment
HexNumeral
    :   '0' [xX] HexDigits
    ;

fragment
HexDigits
    :   HexDigit (HexDigitOrUnderscore* HexDigit)?
    ;

fragment
HexDigit
    :   [0-9a-fA-F]
    ;

fragment
HexDigitOrUnderscore
    :   HexDigit
    |   '_'
    ;

fragment
OctalNumeral
    :   '0' Underscores? OctalDigits
    ;

fragment
OctalDigits
    :   OctalDigit (OctalDigitOrUnderscore* OctalDigit)?
    ;

fragment
OctalDigit
    :   [0-7]
    ;

fragment
OctalDigitOrUnderscore
    :   OctalDigit
    |   '_'
    ;

fragment
BinaryNumeral
    :   '0' [bB] BinaryDigits
    ;

fragment
BinaryDigits
    :   BinaryDigit (BinaryDigitOrUnderscore* BinaryDigit)?
    ;

fragment
BinaryDigit
    :   [01]
    ;

fragment
BinaryDigitOrUnderscore
    :   BinaryDigit
    |   '_'
    ;

// §3.10.2 Floating-Point Literals

FloatingPointLiteral
    :   DecimalFloatingPointLiteral
    |   HexadecimalFloatingPointLiteral
    ;

fragment
DecimalFloatingPointLiteral
    :   Digits '.' Digits? ExponentPart? FloatTypeSuffix?
    |   '.' Digits ExponentPart? FloatTypeSuffix?
    |   Digits ExponentPart FloatTypeSuffix?
    |   Digits FloatTypeSuffix
    ;

fragment
ExponentPart
    :   ExponentIndicator SignedInteger
    ;

fragment
ExponentIndicator
    :   [eE]
    ;

fragment
SignedInteger
    :   Sign? Digits
    ;

fragment
Sign
    :   [+-]
    ;

fragment
FloatTypeSuffix
    :   [fFdD]
    ;

fragment
HexadecimalFloatingPointLiteral
    :   HexSignificand BinaryExponent FloatTypeSuffix?
    ;

fragment
HexSignificand
    :   HexNumeral '.'?
    |   '0' [xX] HexDigits? '.' HexDigits
    ;

fragment
BinaryExponent
    :   BinaryExponentIndicator SignedInteger
    ;

fragment
BinaryExponentIndicator
    :   [pP]
    ;

// §3.10.3 Boolean Literals

BooleanLiteral
    :   'true'
    |   'false'
    ;

// §3.10.4 Character Literals

CharacterLiteral
    :   '\'' SingleCharacter '\''
    |   '\'' EscapeSequence '\''
    ;

fragment
SingleCharacter
    :   ~['\\]
    ;
// §3.10.5 String Literals
SingleQuoteString
    :   '`' SingleQuoteStringCharacter* '`'
    ;

DoubleQuoteString
    :   '"' StringCharacters? '"'
    ;

fragment
StringCharacters
    :   StringCharacter+
    ;
fragment
StringCharacter
    :   ~["\\]
    |   EscapeSequence
    ;
fragment
SingleQuoteStringCharacter
    :   ~[`\\]
    |   EscapeSequence
    ;

// §3.10.6 Escape Sequences for Character and String Literals
fragment
EscapeSequence
    :   '\\' [btnfr"'`\\]
    |   OctalEscape
    |   UnicodeEscape
    ;

fragment
OctalEscape
    :   '\\' OctalDigit
    |   '\\' OctalDigit OctalDigit
    |   '\\' ZeroToThree OctalDigit OctalDigit
    ;

fragment
UnicodeEscape
    :   '\\' 'u' HexDigit HexDigit HexDigit HexDigit
    ;

fragment
ZeroToThree
    :   [0-3]
    ;

// §3.10.7 The Null Literal

NullLiteral
    :   'null'
    ;

// §3.8 Identifiers (must appear after all keywords in the grammar)

Identifier
    :   JavaLetter JavaLetterOrDigit*
    ;

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

//
// Whitespace and comments
//

WS  :  [ \t\r\n\u000C]+ -> skip
    ;

//
// Resource references
//

ResourceReference
    :   '@' (PackageName ':')? ResourceType '/' Identifier
    ;

PackageName
    :   'android'
    |   Identifier
    ;

ResourceType
    :   'anim'
    |   'animator'
    |   'bool'
    |   'color'
    |   'colorStateList'
    |   'dimen'
    |   'dimenOffset'
    |   'dimenSize'
    |   'drawable'
    |   'fraction'
    |   'id'
    |   'integer'
    |   'intArray'
    |   'interpolator'
    |   'layout'
    |   'plurals'
    |   'stateListAnimator'
    |   'string'
    |   'stringArray'
    |   'transition'
    |   'typedArray'
    ;
