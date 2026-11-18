// Grammar for POC (Proof of Concept), a tiny single-file language with two types: int and str.
// Named PocGrammar so the generated PocGrammarParser does not clash with poc.parser.PocParser.
grammar PocGrammar;

// MAIN placement (exactly one, last) is checked by the TypeChecker for friendlier messages.
program   : (funDecl | mainDecl)* EOF ;

funDecl   : 'fun' name=ID '(' (param (',' param)*)? ')' retType=type? block ;
param     : type ID ;
mainDecl  : 'MAIN' ID block ;

type      : 'int' | 'str' ;

block     : '{' stmt* '}' ;

stmt
    : type ID '=' expr ';'?                                         # varDeclStmt
    | ID '=' expr ';'?                                              # assignStmt
    | ID '[' index=expr ']' '=' value=expr ';'?                     # storeStmt
    | 'print' '(' expr ')' ';'?                                     # printStmt
    | call ';'?                                                     # callStmt
    | 'return' expr? ';'?                                           # returnStmt
    | 'if' cond block ('else' 'if' cond block)* ('else' block)?     # ifStmt
    ;

// Alternative order sets precedence: not > and > or.
cond
    : 'not' cond                                                    # notCond
    | cond 'and' cond                                               # andCond
    | cond 'or' cond                                                # orCond
    | '(' cond ')'                                                  # parenCond
    | expr op=('==' | '!=' | '<' | '<=' | '>' | '>=') expr          # cmpCond
    ;

// Alternative order sets precedence: unary minus > * / > + -.
expr
    : '-' expr                                                      # negExpr
    | expr op=('*' | '/') expr                                      # mulExpr
    | expr op=('+' | '-') expr                                      # addExpr
    | 'len' '(' expr ')'                                            # lenExpr
    | call                                                          # callExpr
    | ID '[' expr ']'                                               # indexExpr
    | ID                                                            # varExpr
    | INT                                                           # intExpr
    | STRING                                                        # strExpr
    | '(' expr ')'                                                  # parenExpr
    ;

call      : ID '(' (expr (',' expr)*)? ')' ;

ID            : [a-zA-Z_] [a-zA-Z_0-9]* ;
INT           : [0-9]+ ;
STRING        : '"' ( '\\' [nt"\\] | '\\x' HEX HEX | ~["\\\r\n] )* '"' ;
fragment HEX  : [0-9a-fA-F] ;

LINE_COMMENT  : '//' ~[\r\n]* -> skip ;
WS            : [ \t\r\n]+ -> skip ;

// Any other character becomes a token so the parser reports it with a position.
ERROR_CHAR    : . ;
