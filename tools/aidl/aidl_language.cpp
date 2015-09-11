#include "aidl_language.h"
#include "aidl_language_y.hpp"
#include <stdio.h>
#include <stdlib.h>
#include <string>
#include <iostream>

#ifdef _WIN32
int isatty(int  fd)
{
    return (fd == 0);
}
#endif

using std::string;
using std::cerr;
using std::endl;

ParserCallbacks* g_callbacks = NULL; // &k_parserCallbacks;

void yylex_init(void **);
void yylex_destroy(void *);
void yyset_in(FILE *f, void *);
int yyparse(ParseState*);

ParseState::ParseState() : ParseState("") {}

ParseState::ParseState(const string& filename)
    : filename_(filename) {
  yylex_init(&scanner_);
}

ParseState::~ParseState() {
  yylex_destroy(scanner_);
}

string ParseState::FileName() {
  return filename_;
}

string ParseState::Package() {
  return g_currentPackage;
}

void ParseState::ProcessDocument(const document_item_type& items) {
  /* The cast is not my fault. I didn't write the code on the other side. */
  /* TODO(sadmac): b/23977313 */
  g_callbacks->document((document_item_type *)&items);
}

void ParseState::ProcessImport(const buffer_type& statement) {
  /* The cast is not my fault. I didn't write the code on the other side. */
  /* TODO(sadmac): b/23977313 */
  g_callbacks->import((buffer_type *)&statement);
}

void ParseState::ReportError(const string& err) {
  /* FIXME: We're printing out the line number as -1. We used to use yylineno
   * (which was NEVER correct even before reentrant parsing). Now we'll need
   * another way.
   */
  cerr << filename_ << ":" << -1 << ": " << err << endl;
  error_ = 1;
}

bool ParseState::FoundNoErrors() {
  return error_ == 0;
}

void *ParseState::Scanner() {
  return scanner_;
}

bool ParseState::OpenFileFromDisk() {
  FILE *in = fopen(FileName().c_str(), "r");

  if (! in)
    return false;

  yyset_in(in, Scanner());
  return true;
}

int ParseState::RunParser() {
  int ret = yy::parser(this).parse();

  free((void *)g_currentPackage);
  g_currentPackage = NULL;

  if (error_)
    return 1;

  return ret;
}
