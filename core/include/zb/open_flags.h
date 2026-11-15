#pragma once

#include <cstdint>
#include <fcntl.h>

namespace zb {

// Linux AArch32 open(2) flags from the Android NDK's arm-linux-androideabi
// asm/fcntl.h. The guest and host can have different values for the four
// architecture-specific flags, so never pass the guest bitfield straight to
// the host kernel.
constexpr std::uint32_t kArmOpenDirectory = 040000;
constexpr std::uint32_t kArmOpenNoFollow = 0100000;
constexpr std::uint32_t kArmOpenDirect = 0200000;
constexpr std::uint32_t kArmOpenLargeFile = 0400000;

// asm-generic flags shared by AArch32 and the 64-bit host.
constexpr std::uint32_t kArmOpenCreate = 00000100;
constexpr std::uint32_t kArmOpenExclusive = 00000200;
constexpr std::uint32_t kArmOpenNoCtty = 00000400;
constexpr std::uint32_t kArmOpenTruncate = 00001000;
constexpr std::uint32_t kArmOpenAppend = 00002000;
constexpr std::uint32_t kArmOpenNonblock = 00004000;
constexpr std::uint32_t kArmOpenDsync = 00010000;
constexpr std::uint32_t kArmOpenAsync = 00020000;
constexpr std::uint32_t kArmOpenNoAtime = 01000000;
constexpr std::uint32_t kArmOpenCloseOnExec = 02000000;
constexpr std::uint32_t kArmOpenSyncExtra = 04000000;
constexpr std::uint32_t kArmOpenPath = 010000000;
constexpr std::uint32_t kArmOpenTmpfileExtra = 020000000;

constexpr std::uint32_t kArmOpenAccessMode = 00000003;
constexpr std::uint32_t kArmOpenTmpfileMask = kArmOpenTmpfileExtra | kArmOpenDirectory;

inline bool translate_arm_open_flags(std::uint32_t guest_flags, int& host_flags) noexcept {
    constexpr std::uint32_t known =
            kArmOpenAccessMode | kArmOpenCreate | kArmOpenExclusive | kArmOpenNoCtty |
            kArmOpenTruncate | kArmOpenAppend | kArmOpenNonblock | kArmOpenDsync |
            kArmOpenAsync | kArmOpenDirectory | kArmOpenNoFollow | kArmOpenDirect |
            kArmOpenLargeFile | kArmOpenNoAtime | kArmOpenCloseOnExec |
            kArmOpenSyncExtra | kArmOpenPath | kArmOpenTmpfileExtra;
    if ((guest_flags & ~known) != 0) return false;

    host_flags = static_cast<int>(guest_flags & kArmOpenAccessMode);
    if (guest_flags & kArmOpenCreate) host_flags |= O_CREAT;
    if (guest_flags & kArmOpenExclusive) host_flags |= O_EXCL;
    if (guest_flags & kArmOpenNoCtty) host_flags |= O_NOCTTY;
    if (guest_flags & kArmOpenTruncate) host_flags |= O_TRUNC;
    if (guest_flags & kArmOpenAppend) host_flags |= O_APPEND;
    if (guest_flags & kArmOpenNonblock) host_flags |= O_NONBLOCK;
    if (guest_flags & kArmOpenDsync) host_flags |= O_DSYNC;
    if (guest_flags & kArmOpenAsync) host_flags |= O_ASYNC;
    if (guest_flags & kArmOpenDirectory) host_flags |= O_DIRECTORY;
    if (guest_flags & kArmOpenNoFollow) host_flags |= O_NOFOLLOW;
    if (guest_flags & kArmOpenDirect) host_flags |= O_DIRECT;
    if (guest_flags & kArmOpenNoAtime) host_flags |= O_NOATIME;
    if (guest_flags & kArmOpenCloseOnExec) host_flags |= O_CLOEXEC;
    if (guest_flags & kArmOpenSyncExtra) host_flags |= O_SYNC;

    // O_LARGEFILE is implied by 64-bit hosts; retain it only on hosts that
    // define a nonzero value. It must not accidentally become O_NOFOLLOW.
#if defined(O_LARGEFILE) && O_LARGEFILE != 0
    if (guest_flags & kArmOpenLargeFile) host_flags |= O_LARGEFILE;
#endif

#ifdef O_PATH
    if (guest_flags & kArmOpenPath) host_flags |= O_PATH;
#else
    if (guest_flags & kArmOpenPath) return false;
#endif

#ifdef O_TMPFILE
    if (guest_flags & kArmOpenTmpfileExtra) {
        if ((guest_flags & kArmOpenTmpfileMask) != kArmOpenTmpfileMask) return false;
        host_flags |= O_TMPFILE;
    }
#else
    if (guest_flags & kArmOpenTmpfileExtra) return false;
#endif

    return true;
}

}  // namespace zb
