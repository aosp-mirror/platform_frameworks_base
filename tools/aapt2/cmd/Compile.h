#ifndef AAPT2_COMPILE_H
#define AAPT2_COMPILE_H

#include "androidfw/StringPiece.h"

#include "Diagnostics.h"

namespace aapt {

  int Compile(const std::vector<android::StringPiece>& args, IDiagnostics* diagnostics);

}// namespace aapt

#endif //AAPT2_COMPILE_H
