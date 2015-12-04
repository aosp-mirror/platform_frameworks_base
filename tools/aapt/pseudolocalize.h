#ifndef HOST_PSEUDOLOCALIZE_H
#define HOST_PSEUDOLOCALIZE_H

#include <android-base/macros.h>
#include "StringPool.h"

class PseudoMethodImpl {
 public:
  virtual ~PseudoMethodImpl() {}
  virtual String16 start() { return String16(); }
  virtual String16 end() { return String16(); }
  virtual String16 text(const String16& text) = 0;
  virtual String16 placeholder(const String16& text) = 0;
};

class PseudoMethodNone : public PseudoMethodImpl {
 public:
  PseudoMethodNone() {}
  String16 text(const String16& text) { return text; }
  String16 placeholder(const String16& text) { return text; }
 private:
  DISALLOW_COPY_AND_ASSIGN(PseudoMethodNone);
};

class PseudoMethodBidi : public PseudoMethodImpl {
 public:
  String16 text(const String16& text);
  String16 placeholder(const String16& text);
};

class PseudoMethodAccent : public PseudoMethodImpl {
 public:
  PseudoMethodAccent() : mDepth(0), mWordCount(0), mLength(0) {}
  String16 start();
  String16 end();
  String16 text(const String16& text);
  String16 placeholder(const String16& text);
 private:
  size_t mDepth;
  size_t mWordCount;
  size_t mLength;
};

class Pseudolocalizer {
 public:
  Pseudolocalizer(PseudolocalizationMethod m);
  ~Pseudolocalizer() { if (mImpl) delete mImpl; }
  void setMethod(PseudolocalizationMethod m);
  String16 start() { return mImpl->start(); }
  String16 end() { return mImpl->end(); }
  String16 text(const String16& text);
 private:
  PseudoMethodImpl *mImpl;
  size_t mLastDepth;
};

#endif // HOST_PSEUDOLOCALIZE_H

