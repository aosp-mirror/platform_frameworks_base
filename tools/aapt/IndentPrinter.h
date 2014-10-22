#ifndef __INDENT_PRINTER_H
#define __INDENT_PRINTER_H

class IndentPrinter {
public:
    IndentPrinter(FILE* stream, int indentSize=2)
        : mStream(stream)
        , mIndentSize(indentSize)
        , mIndent(0)
        , mNeedsIndent(true) {
    }

    void indent(int amount = 1) {
        mIndent += amount;
        if (mIndent < 0) {
            mIndent = 0;
        }
    }

    void print(const char* fmt, ...) {
        doIndent();
        va_list args;
        va_start(args, fmt);
        vfprintf(mStream, fmt, args);
        va_end(args);
    }

    void println(const char* fmt, ...) {
        doIndent();
        va_list args;
        va_start(args, fmt);
        vfprintf(mStream, fmt, args);
        va_end(args);
        fputs("\n", mStream);
        mNeedsIndent = true;
    }

    void println() {
        doIndent();
        fputs("\n", mStream);
        mNeedsIndent = true;
    }

private:
    void doIndent() {
        if (mNeedsIndent) {
            int numSpaces = mIndent * mIndentSize;
            while (numSpaces > 0) {
                fputs(" ", mStream);
                numSpaces--;
            }
            mNeedsIndent = false;
        }
    }

    FILE* mStream;
    const int mIndentSize;
    int mIndent;
    bool mNeedsIndent;
};

#endif // __INDENT_PRINTER_H

