/*
 * WebAssembly entry point.
 *
 * The exported functions themselves live in openh264_kmp.cpp. This
 * translation unit takes their addresses so the linker definitely pulls that
 * object out of the static archive; without a reference the archive member
 * would be dropped and the exports listed in CMakeLists.txt would be missing.
 */
#include "openh264_kmp.h"

typedef void (*ok_wasm_export)(void);

static const ok_wasm_export ok_wasm_exports[] = {
    (ok_wasm_export)ok_version_packed,
    (ok_wasm_export)ok_version_string,
    (ok_wasm_export)ok_api_build,
    (ok_wasm_export)ok_decoder_create,
    (ok_wasm_export)ok_decoder_destroy,
    (ok_wasm_export)ok_decoder_decode,
    (ok_wasm_export)ok_decoder_flush,
    (ok_wasm_export)ok_decoder_frame_ready,
    (ok_wasm_export)ok_decoder_frame_width,
    (ok_wasm_export)ok_decoder_frame_height,
    (ok_wasm_export)ok_decoder_frame_chroma_format,
    (ok_wasm_export)ok_decoder_frame_pts,
    (ok_wasm_export)ok_decoder_frame_plane,
    (ok_wasm_export)ok_decoder_frame_blob,
    (ok_wasm_export)ok_alloc,
    (ok_wasm_export)ok_free,
};

/* Keep the table observable so no optimiser removes it. */
const ok_wasm_export *ok_wasm_keep_exports(void);

const ok_wasm_export *ok_wasm_keep_exports(void) { return ok_wasm_exports; }
