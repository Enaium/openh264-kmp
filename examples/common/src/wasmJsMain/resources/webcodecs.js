/*
 * WebCodecs bridge for the browser example.
 *
 * The Kotlin/Wasm side only passes base64 payloads across: I420 pictures in,
 * Annex-B access units out (and the reverse for decoding). VideoEncoder and
 * VideoDecoder are asynchronous, so their callbacks append to per-instance
 * queues and the Kotlin side polls them once per frame.
 */
(function () {
    let nextHandle = 1;
    const decoders = new Map();
    const encoders = new Map();

    const toBase64 = (bytes) => {
        let text = '';
        const chunk = 0x8000;
        for (let i = 0; i < bytes.length; i += chunk) {
            text += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
        }
        return btoa(text);
    };

    const fromBase64 = (text) => {
        const raw = atob(text);
        const out = new Uint8Array(raw.length);
        for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
        return out;
    };

    const rgbaToI420 = (rgba, width, height) => {
        const cw = (width + 1) >> 1;
        const ch = (height + 1) >> 1;
        const yPlane = new Uint8Array(width * height);
        const uPlane = new Uint8Array(cw * ch);
        const vPlane = new Uint8Array(cw * ch);
        let uv = 0;
        for (let row = 0; row < height; row += 2) {
            for (let col = 0; col < width; col += 2) {
                const base = (row * width + col) * 4;
                const right = base + 4;
                const bottom = base + width * 4;
                const bottomRight = bottom + 4;
                const r = (rgba[base] + rgba[right] + rgba[bottom] + rgba[bottomRight]) / 4;
                const g = (rgba[base + 1] + rgba[right + 1] + rgba[bottom + 1] + rgba[bottomRight + 1]) / 4;
                const b = (rgba[base + 2] + rgba[right + 2] + rgba[bottom + 2] + rgba[bottomRight + 2]) / 4;
                uPlane[uv] = Math.max(0, Math.min(255, ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128));
                vPlane[uv] = Math.max(0, Math.min(255, ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128));
                uv++;
            }
        }
        for (let row = 0; row < height; row++) {
            for (let col = 0; col < width; col++) {
                const o = (row * width + col) * 4;
                const r = rgba[o];
                const g = rgba[o + 1];
                const b = rgba[o + 2];
                yPlane[row * width + col] = Math.max(0, Math.min(255, ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16));
            }
        }
        const out = new Uint8Array(width * height + cw * ch * 2);
        out.set(yPlane, 0);
        out.set(uPlane, width * height);
        out.set(vPlane, width * height + cw * ch);
        return { width, height, data: out };
    };

    const frameToI420 = async (frame) => {
        const width = frame.displayWidth;
        const height = frame.displayHeight;
        const cw = (width + 1) >> 1;
        const ch = (height + 1) >> 1;
        const buffer = new Uint8Array(width * height + cw * ch * 2);
        try {
            await frame.copyTo(buffer, {
                format: 'I420',
                layout: [
                    { offset: 0, stride: width },
                    { offset: width * height, stride: cw },
                    { offset: width * height + cw * ch, stride: cw },
                ],
            });
            return { width, height, data: buffer };
        } catch (ignored) {
            const canvas = typeof OffscreenCanvas !== 'undefined'
                ? new OffscreenCanvas(width, height)
                : Object.assign(document.createElement('canvas'), { width, height });
            const ctx = canvas.getContext('2d');
            ctx.drawImage(frame, 0, 0, width, height);
            const rgba = ctx.getImageData(0, 0, width, height).data;
            return rgbaToI420(rgba, width, height);
        }
    };

    const codecConfig = (kind, width, height) => {
        if (kind === 'HEVC') {
            return {
                codec: 'hev1.1.6.L93.B0',
                codedWidth: width,
                codedHeight: height,
                hevc: { format: 'annexb' },
                optimizeForLatency: true,
            };
        }
        return {
            codec: 'avc1.42001E',
            codedWidth: width,
            codedHeight: height,
            avc: { format: 'annexb' },
            optimizeForLatency: true,
        };
    };

    globalThis.webCodecsCreateDecoder = (kind, width, height) => {
        try {
            const state = { frames: [], error: null };
            const decoder = new VideoDecoder({
                output: (frame) => {
                    frameToI420(frame)
                        .then((picture) => {
                            state.frames.push(
                                picture.width + ',' + picture.height + ',' + toBase64(picture.data),
                            );
                        })
                        .catch((error) => { state.error = String(error); })
                        .finally(() => frame.close());
                },
                error: (error) => { state.error = String(error); },
            });
            const config = codecConfig(kind, width, height);
            delete config.optimizeForLatency;
            config.optimizeForLatency = true;
            decoder.configure(config);
            state.decoder = decoder;
            const handle = nextHandle++;
            decoders.set(handle, state);
            return handle;
        } catch (error) {
            return 0;
        }
    };

    globalThis.webCodecsDecode = (handle, base64, pts, key) => {
        const state = decoders.get(handle);
        if (!state || !state.decoder) return;
        try {
            state.decoder.decode(new EncodedVideoChunk({
                type: key === 1 ? 'key' : 'delta',
                timestamp: Number(pts),
                data: fromBase64(base64),
            }));
        } catch (error) {
            state.error = String(error);
        }
    };

    globalThis.webCodecsPollDecode = (handle) => {
        const state = decoders.get(handle);
        if (!state || state.frames.length === 0) return '';
        const out = state.frames.join('\n');
        state.frames.length = 0;
        return out;
    };

    globalThis.webCodecsCloseDecoder = (handle) => {
        const state = decoders.get(handle);
        if (!state) return;
        try { if (state.decoder) state.decoder.close(); } catch (ignored) { }
        decoders.delete(handle);
    };

    const encoderConfig = (kind, width, height) => {
        const config = {
            codec: kind === 'HEVC' ? 'hev1.1.6.L93.B0' : 'avc1.42001E',
            width,
            height,
            bitrate: 4000000,
            framerate: 30,
        };
        if (kind === 'HEVC') config.hevc = { format: 'annexb' };
        else config.avc = { format: 'annexb' };
        return config;
    };

    globalThis.webCodecsCreateEncoder = (kind, width, height) => {
        try {
            const state = { chunks: [], status: 0, error: null };
            const encoder = new VideoEncoder({
                output: (chunk) => {
                    const data = new Uint8Array(chunk.byteLength);
                    chunk.copyTo(data);
                    state.chunks.push(
                        (chunk.type === 'key' ? '1' : '0') + ',' + Number(chunk.timestamp) +
                        ',' + toBase64(data),
                    );
                },
                error: (error) => {
                    state.error = String(error);
                    state.status = -1;
                },
            });
            const config = encoderConfig(kind, width, height);
            VideoEncoder.isConfigSupported(config).then((support) => {
                if (!support.supported) {
                    state.status = -1;
                    return;
                }
                try {
                    encoder.configure(support.config || config);
                    state.status = 1;
                } catch (error) {
                    state.error = String(error);
                    state.status = -1;
                }
            }).catch((error) => {
                state.error = String(error);
                state.status = -1;
            });
            state.encoder = encoder;
            const handle = nextHandle++;
            encoders.set(handle, state);
            return handle;
        } catch (error) {
            return 0;
        }
    };

    globalThis.webCodecsEncoderStatus = (handle) => {
        const state = encoders.get(handle);
        return state ? state.status : -1;
    };

    globalThis.webCodecsEncode = (handle, base64, width, height, pts) => {
        const state = encoders.get(handle);
        if (!state || state.status !== 1) return;
        try {
            const frame = new VideoFrame(fromBase64(base64), {
                format: 'I420',
                codedWidth: width,
                codedHeight: height,
                timestamp: Number(pts),
            });
            state.encoder.encode(frame, { keyFrame: false });
            frame.close();
        } catch (error) {
            state.error = String(error);
            state.status = -1;
        }
    };

    globalThis.webCodecsPollEncode = (handle) => {
        const state = encoders.get(handle);
        if (!state || state.chunks.length === 0) return '';
        const out = state.chunks.join('\n');
        state.chunks.length = 0;
        return out;
    };

    globalThis.webCodecsCloseEncoder = (handle) => {
        const state = encoders.get(handle);
        if (!state) return;
        try { if (state.encoder) state.encoder.close(); } catch (ignored) { }
        encoders.delete(handle);
    };
})();
