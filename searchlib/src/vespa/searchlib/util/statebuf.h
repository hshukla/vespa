// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include <string>

namespace search
{

/**
 * Class used to serialize application state in a mostly safe manner.
 *
 * Only async signal safe methods can be called, except for unit test
 * helper methods (str).
 *
 */
class StateBuf
{
    char *_start;
    char *_cur;
    char *_end;

    void
    overflow() noexcept __attribute__((__noinline__, __noreturn__));

public:
    StateBuf(void *buf, size_t bufLen) noexcept;

    inline StateBuf &
    operator<<(char c)  noexcept __attribute__((__always_inline__))
    {
        if (__builtin_expect(_cur != _end, true)) {
            *_cur++ = c;
            return *this;
        }
        overflow();
    }


    StateBuf &
    operator<<(const char *s) noexcept;

    StateBuf &
    appendQuoted(const char *s) noexcept;

    StateBuf &
    appendKey(const char *s) noexcept;

    StateBuf &
    operator<<(const struct timespec &ts) noexcept;

    StateBuf &
    appendTimestamp(const struct timespec &ts) noexcept;

    StateBuf &
    appendTimestamp() noexcept;

    StateBuf &
    appendAddr(void *addr) noexcept;

    StateBuf &
    operator<<(unsigned long long val) noexcept;

    StateBuf &
    operator<<(long long val) noexcept;

    StateBuf &
    operator<<(unsigned long val) noexcept;

    StateBuf &
    operator<<(long val) noexcept;

    StateBuf &
    operator<<(unsigned int val) noexcept;

    StateBuf &
    operator<<(int val) noexcept;

    StateBuf &
    appendDecFraction(unsigned long val, unsigned int width) noexcept;

    StateBuf &
    appendHex(unsigned long val) noexcept;

    size_t
    size() const noexcept;

    const char *
    base() const noexcept;

    /*
     * Unit test helper methods.
     */
    std::string
    str() const;
};

}
