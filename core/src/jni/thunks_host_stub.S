.text
.balign 4

// Host-only placeholder pool. The real Android ARM64 build uses thunks.S.
// Host-side JNI tests may store these addresses in a fake RegisterNatives
// backend, but must never call them.
.globl zb_native_thunk_base
.hidden zb_native_thunk_base
zb_native_thunk_base:
    .zero 131072

.globl zb_native_thunk_end
.hidden zb_native_thunk_end
zb_native_thunk_end:

.section .note.GNU-stack,"",%progbits
