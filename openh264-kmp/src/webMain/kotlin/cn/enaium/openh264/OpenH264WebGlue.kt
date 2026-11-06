/*
 * Copyright (c) 2026 Enaium
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package cn.enaium.openh264

/**
 * JavaScript glue that owns the WebAssembly instance.
 *
 * The module is a standalone WASI reactor: it imports only a handful of WASI
 * calls (time and stdio, used by OpenH264's logger) plus the Emscripten
 * memory-growth notification. The glue provides those, exposes one function
 * per C entry point on `globalThis.__openh264Web`, and marshals strings
 * through the module's own `ok_alloc`.
 *
 * Bulk data (the access unit and the result blob) crosses the boundary as
 * base64 strings because Kotlin/JS and Kotlin/Wasm cannot share typed arrays
 * with a module compiled outside their own toolchain.
 */
internal const val OPENH264_WEB_INIT_JS: String = """
(base64) => {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  let wasmExports = null;
  const memory = () => new Uint8Array(wasmExports.memory.buffer);
  const dataView = () => new DataView(wasmExports.memory.buffer);
  const decoder = new TextDecoder();

  const encodeBase64 = (data) => {
    let text = '';
    const chunk = 0x8000;
    for (let i = 0; i < data.length; i += chunk) {
      text += String.fromCharCode.apply(null, data.subarray(i, i + chunk));
    }
    return btoa(text);
  };

  const decodeBase64 = (text) => {
    const raw = atob(text);
    const out = new Uint8Array(raw.length);
    for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
    return out;
  };

  const readString = (pointer) => {
    if (!pointer) return '';
    const data = memory();
    let end = pointer;
    while (data[end] !== 0) end++;
    return decoder.decode(data.subarray(pointer, end));
  };

  const writeBytes = (data) => {
    const pointer = wasmExports.ok_alloc(data.length);
    if (!pointer) return 0;
    memory().set(data, pointer);
    return pointer;
  };

  const blobOf = (decoder) => {
    const sizePointer = wasmExports.ok_alloc(4);
    if (!sizePointer) return '';
    const pointer = wasmExports.ok_decoder_frame_blob(decoder, sizePointer);
    if (!pointer) {
      wasmExports.ok_free(sizePointer);
      return '';
    }
    const size = dataView().getUint32(sizePointer, true);
    const data = memory().slice(pointer, pointer + size);
    wasmExports.ok_free(sizePointer);
    return encodeBase64(data);
  };

  const imports = {
    env: {
      emscripten_notify_memory_growth() {},
      __syscall_unlinkat() { return 0; },
      __syscall_renameat() { return 0; },
    },
    wasi_snapshot_preview1: {
      fd_write(fd, iovs, iovsLength, written) {
        let total = 0;
        for (let i = 0; i < iovsLength; i++) {
          const pointer = dataView().getUint32(iovs + i * 8, true);
          const length = dataView().getUint32(iovs + i * 8 + 4, true);
          const text = decoder.decode(memory().subarray(pointer, pointer + length));
          if (text.trim().length > 0) {
            if (fd === 2) console.error(text.trimEnd());
            else console.log(text.trimEnd());
          }
          total += length;
        }
        dataView().setUint32(written, total, true);
        return 0;
      },
      fd_read() { return 0; },
      fd_seek() { return 0; },
      fd_close() { return 0; },
      clock_time_get(clock, precision, out) {
        const now = typeof performance !== 'undefined' ? performance.now() : Date.now();
        dataView().setBigUint64(out, BigInt(Math.floor(now * 1e6)), true);
        return 0;
      },
      environ_sizes_get(count, size) {
        dataView().setUint32(count, 0, true);
        dataView().setUint32(size, 0, true);
        return 0;
      },
      environ_get() { return 0; },
    },
  };

  const instance = new WebAssembly.Instance(new WebAssembly.Module(bytes), imports);
  wasmExports = instance.exports;
  if (wasmExports._initialize) wasmExports._initialize();

  globalThis.__openh264Web = {
    versionPacked() {
      return wasmExports.ok_version_packed();
    },
    versionString() {
      return readString(wasmExports.ok_version_string());
    },
    apiBuild() {
      return wasmExports.ok_api_build();
    },
    decoderCreate() {
      return wasmExports.ok_decoder_create();
    },
    decoderDestroy(pointer) {
      wasmExports.ok_decoder_destroy(pointer);
    },
    decode(pointer, payloadBase64, pts) {
      const data = decodeBase64(payloadBase64);
      const buffer = writeBytes(data);
      if (!buffer) return -2;
      const code = wasmExports.ok_decoder_decode(pointer, buffer, data.length, BigInt(Math.round(pts)));
      wasmExports.ok_free(buffer);
      return code;
    },
    flush(pointer) {
      return wasmExports.ok_decoder_flush(pointer);
    },
    frameBlob(pointer) {
      return '1:' + blobOf(pointer);
    },
  };
}
"""
