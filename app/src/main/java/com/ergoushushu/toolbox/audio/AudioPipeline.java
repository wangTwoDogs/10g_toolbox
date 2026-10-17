package com.ergoushushu.toolbox.audio;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.SystemClock;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Package-private streaming pipeline; memory use does not grow with video duration. */
final class AudioPipeline {
    private static final int DEFAULT_SAMPLE_BUFFER = 256 * 1024;
    private static final int MAX_SAMPLE_BUFFER = 8 * 1024 * 1024;
    private static final long STALL_TIMEOUT_MS = 30_000;

    private AudioPipeline() { }

    static final class Stats {
        final long durationUs;
        final int sampleRate;
        final int channelCount;
        Stats(long durationUs, int sampleRate, int channelCount) {
            this.durationUs = durationUs;
            this.sampleRate = sampleRate;
            this.channelCount = channelCount;
        }
    }

    static Stats remux(MediaExtractor extractor, MediaFormat format, File output,
                       CancellationSignal cancellation, AudioExtractor.Progress progress)
            throws IOException {
        int sampleRate = requiredPositive(format, MediaFormat.KEY_SAMPLE_RATE);
        int channels = requiredPositive(format, MediaFormat.KEY_CHANNEL_COUNT);
        int requestedSize = AudioExtractor.getInt(format, MediaFormat.KEY_MAX_INPUT_SIZE, 0);
        int capacity = Math.max(DEFAULT_SAMPLE_BUFFER, requestedSize);
        if (capacity > MAX_SAMPLE_BUFFER) throw sampleTooLarge();
        ByteBuffer buffer = ByteBuffer.allocateDirect(capacity);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try (MuxerOutput muxer = new MuxerOutput(output)) {
            muxer.start(format);
            long originUs = -1;
            long lastUs = -1;
            long nominalFrameUs = Math.max(1, 1_024_000_000L / sampleRate);
            long lastStepUs = nominalFrameUs;
            long samples = 0;
            while (extractor.getSampleTrackIndex() >= 0) {
                AudioExtractor.checkCanceled(cancellation);
                int flags = extractor.getSampleFlags();
                requireClearSample(flags);
                if ((flags & MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                    throw new IOException("这个视频的 AAC 音轨使用了分片音频帧，暂时无法无损提取。" );
                }
                if (Build.VERSION.SDK_INT >= 28) {
                    long size = extractor.getSampleSize();
                    if (size > MAX_SAMPLE_BUFFER) throw sampleTooLarge();
                    if (size > buffer.capacity()) buffer = ByteBuffer.allocateDirect((int) size);
                }
                buffer.clear();
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) break;
                if (size > buffer.capacity()) throw sampleTooLarge();
                long rawTimeUs = extractor.getSampleTime();
                if (rawTimeUs < 0) throw invalidTimestamp();
                if (originUs < 0) originUs = rawTimeUs;
                long timeUs = rawTimeUs - originUs;
                if (timeUs < lastUs) throw invalidTimestamp();
                if (size > 0) {
                    if (lastUs >= 0 && timeUs > lastUs) {
                        long stepUs = timeUs - lastUs;
                        // A timestamp gap is silence between packets, not the final packet's length.
                        // Allow HE-AAC and small frame-duration variations, but do not duplicate gaps.
                        if (stepUs <= nominalFrameUs * 4) lastStepUs = stepUs;
                    }
                    info.set(0, size, timeUs, (flags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                            ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                    buffer.position(0);
                    buffer.limit(size);
                    muxer.write(buffer, info);
                    lastUs = timeUs;
                    samples++;
                    progress.audio(timeUs);
                }
                if (!extractor.advance()) break;
            }
            if (samples == 0) throw new IOException("这个文件的音轨没有可提取的音频数据。" );
            long durationUs = lastUs + Math.max(1, lastStepUs);
            muxer.finish(durationUs);
            return new Stats(durationUs, sampleRate, channels);
        }
    }

    static Stats transcode(MediaExtractor extractor, MediaFormat sourceFormat, File output,
                           CancellationSignal cancellation, AudioExtractor.Progress progress)
            throws IOException {
        return transcode(extractor, sourceFormat, output, cancellation, progress, false);
    }

    static Stats transcode(MediaExtractor extractor, MediaFormat sourceFormat, File output,
                           CancellationSignal cancellation, AudioExtractor.Progress progress,
                           boolean compress) throws IOException {
        String sourceMime = sourceFormat.getString(MediaFormat.KEY_MIME);
        if (MediaFormat.MIMETYPE_AUDIO_RAW.equals(sourceMime)) {
            // For raw tracks this key describes the input too; changing it would corrupt PCM.
            requirePcm16(sourceFormat);
            // WAV already contains PCM. A raw MediaCodec decoder adds another buffer queue
            // and vendor-dependent passthrough behavior without doing any decoding.
            return encodePcm(extractor, sourceFormat, output, cancellation, progress, compress);
        } else {
            sourceFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
        }
        // Avoid platform-selected floating-point PCM, which cannot be blindly sent to AAC.
        try (CodecHandle decoderHandle = new CodecHandle(MediaCodec.createDecoderByType(sourceMime));
             MuxerOutput muxer = new MuxerOutput(output)) {
            MediaCodec decoder = decoderHandle.codec;
            decoder.configure(sourceFormat, null, null, 0);
            decoderHandle.start();
            return pump(extractor, decoder, muxer, cancellation, progress, compress);
        }
    }

    private static Stats encodePcm(MediaExtractor extractor, MediaFormat source, File output,
                                   CancellationSignal cancellation, AudioExtractor.Progress progress,
                                   boolean compress) throws IOException {
        int sourceRate = requiredPositive(source, MediaFormat.KEY_SAMPLE_RATE);
        int sourceChannels = requiredPositive(source, MediaFormat.KEY_CHANNEL_COUNT);
        if (sourceChannels > 8) throw new IOException("暂不支持超过 8 个声道的录音。");
        int channels = compress ? 1 : sourceChannels;
        VoicePcm converter = compress ? new VoicePcm(sourceRate, sourceChannels) : null;
        int rate = converter == null ? sourceRate : converter.outputRate;
        int frameBytes = channels * 2;
        int capacity = Math.max(DEFAULT_SAMPLE_BUFFER,
                AudioExtractor.getInt(source, MediaFormat.KEY_MAX_INPUT_SIZE, 0));
        if (capacity > MAX_SAMPLE_BUFFER) throw sampleTooLarge();
        ByteBuffer raw = ByteBuffer.allocateDirect(capacity);
        ByteBuffer pending = null;
        long originUs = -1, pendingUs = 0, inputEndUs = 0, outputOriginUs = Long.MIN_VALUE;
        int consumed = 0;
        boolean sourceDone = false, inputDone = false, outputDone = false;
        long packets = 0, lastOutputUs = -1, lastActivity = SystemClock.elapsedRealtime();
        try (CodecHandle owner = createEncoder(rate, channels, compress);
             MuxerOutput muxer = new MuxerOutput(output)) {
            MediaCodec encoder = owner.codec;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (!outputDone) {
                AudioExtractor.checkCanceled(cancellation);
                boolean moved = false;
                // Drain/feed in batches, rather than sleeping once for every tiny PCM packet.
                for (int attempt = 0; attempt < 32; attempt++) {
                    int index = encoder.dequeueOutputBuffer(info, 0);
                    if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                    moved = true;
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxer.started) throw new IOException("编码过程中音频格式发生变化。");
                        muxer.start(encoder.getOutputFormat());
                    } else if (index >= 0) {
                        outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        try {
                            if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                if (!muxer.started) throw new IOException("AAC 编码器未提供音轨信息。");
                                ByteBuffer bytes = requireBuffer(encoder.getOutputBuffer(index));
                                validateRange(bytes, info.offset, info.size);
                                if (outputOriginUs == Long.MIN_VALUE) outputOriginUs = info.presentationTimeUs;
                                long timeUs = info.presentationTimeUs - outputOriginUs;
                                if (timeUs < 0 || timeUs < lastOutputUs) throw invalidTimestamp();
                                bytes.position(info.offset);
                                bytes.limit(info.offset + info.size);
                                info.set(info.offset, info.size, timeUs,
                                        info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME);
                                muxer.write(bytes, info);
                                lastOutputUs = timeUs;
                                packets++;
                                progress.audio(timeUs);
                            }
                        } finally { encoder.releaseOutputBuffer(index, false); }
                    }
                    if (outputDone) break;
                }
                if (outputDone) break;
                for (int attempt = 0; attempt < 16 && !inputDone; attempt++) {
                    AudioExtractor.checkCanceled(cancellation);
                    if (pending == null && !sourceDone) {
                        if (extractor.getSampleTrackIndex() < 0) sourceDone = true;
                        else {
                            requireClearSample(extractor.getSampleFlags());
                            if (Build.VERSION.SDK_INT >= 28) {
                                long size = extractor.getSampleSize();
                                if (size > MAX_SAMPLE_BUFFER) throw sampleTooLarge();
                                if (size > raw.capacity()) raw = ByteBuffer.allocateDirect((int) size);
                            }
                            raw.clear();
                            int size = extractor.readSampleData(raw, 0);
                            if (size < 0) sourceDone = true;
                            else {
                                if (size > raw.capacity()) throw sampleTooLarge();
                                if (size % (sourceChannels * 2) != 0) throw new IOException("录音 PCM 数据不完整。");
                                long time = extractor.getSampleTime();
                                if (time < 0) throw invalidTimestamp();
                                if (originUs < 0) originUs = time;
                                pendingUs = converter == null ? time - originUs
                                        : converter.framesWritten() * 1_000_000L / rate;
                                if (pendingUs < 0) throw invalidTimestamp();
                                consumed = 0;
                                raw.position(0);
                                raw.limit(size);
                                pending = converter == null ? raw.slice() : converter.convert(raw.slice());
                                extractor.advance();
                                if (!pending.hasRemaining()) pending = null;
                            }
                        }
                    }
                    if (pending == null && !sourceDone) continue;
                    int index = encoder.dequeueInputBuffer(moved ? 0 : 1_000);
                    if (index < 0) break;
                    if (pending == null) {
                        encoder.queueInputBuffer(index, 0, 0, inputEndUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        ByteBuffer input = requireBuffer(encoder.getInputBuffer(index));
                        input.clear();
                        int size = Math.min(input.remaining(), pending.remaining());
                        size -= size % frameBytes;
                        if (size <= 0) throw new IOException("手机 AAC 编码器的缓冲区太小。");
                        long timeUs = pendingUs + consumed / frameBytes * 1_000_000L / rate;
                        int limit = pending.limit();
                        pending.limit(pending.position() + size);
                        input.put(pending);
                        pending.limit(limit);
                        encoder.queueInputBuffer(index, 0, size, timeUs, 0);
                        consumed += size;
                        inputEndUs = pendingUs + consumed / frameBytes * 1_000_000L / rate;
                        if (!pending.hasRemaining()) pending = null;
                    }
                    moved = true;
                }
                if (moved) lastActivity = SystemClock.elapsedRealtime();
                else {
                    if (SystemClock.elapsedRealtime() - lastActivity > STALL_TIMEOUT_MS) {
                        throw new IOException("AAC 编码器没有响应（" + encoder.getName() + "）。请重试这份录音。");
                    }
                    Thread.yield();
                }
            }
            if (packets == 0) throw new IOException("录音没有可编码的音频数据。");
            long duration = Math.max(inputEndUs, lastOutputUs + 1_024_000_000L / rate);
            muxer.finish(duration);
            return new Stats(duration, rate, channels);
        }
    }

    private static Stats pump(MediaExtractor extractor, MediaCodec decoder, MuxerOutput muxer,
                              CancellationSignal cancellation, AudioExtractor.Progress progress,
                              boolean compress)
            throws IOException {
        MediaCodec encoder = null;
        MediaCodec.BufferInfo decodedInfo = new MediaCodec.BufferInfo();
        MediaCodec.BufferInfo encodedInfo = new MediaCodec.BufferInfo();
        int sampleRate = 0;
        int channels = 0;
        int decodedChannels = 0;
        int decodedRate = 0;
        VoicePcm converter = null;
        int frameBytes = 0;
        int pendingDecoderIndex = -1;
        ByteBuffer pendingPcm = null;
        long pendingTimeUs = 0;
        int pendingBytesConsumed = 0;
        boolean pendingEos = false;
        boolean decoderInputDone = false;
        boolean decoderDone = false;
        boolean encoderInputDone = false;
        boolean encoderDone = false;
        long sourceOriginUs = -1;
        long decodedOriginUs = Long.MIN_VALUE;
        long encodedOriginUs = Long.MIN_VALUE;
        long decoderLastInputUs = 0;
        long encoderInputEndUs = 0;
        long lastEncodedUs = -1;
        long packetCount = 0;
        long lastActivityMs = SystemClock.elapsedRealtime();

        try (DeferredCodec encoderOwner = new DeferredCodec()) {
            while (!encoderDone) {
                AudioExtractor.checkCanceled(cancellation);
                boolean moved = false;

                // Drain the encoder first so a full output queue cannot block PCM input.
                if (encoder != null) {
                    for (int attempt = 0; attempt < 16; attempt++) {
                        int index = encoder.dequeueOutputBuffer(encodedInfo, 0);
                        if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                        moved = true;
                        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            if (muxer.started) throw new IOException("编码过程中音频格式发生变化。" );
                            muxer.start(encoder.getOutputFormat());
                        } else if (index >= 0) {
                            boolean eos = (encodedInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                            try {
                                if (encodedInfo.size > 0
                                        && (encodedInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                    if (!muxer.started) throw new IOException("AAC 编码器未提供音轨信息。" );
                                    ByteBuffer encoded = requireBuffer(encoder.getOutputBuffer(index));
                                    validateRange(encoded, encodedInfo.offset, encodedInfo.size);
                                    if (encodedOriginUs == Long.MIN_VALUE) {
                                        encodedOriginUs = encodedInfo.presentationTimeUs;
                                    }
                                    long timeUs = encodedInfo.presentationTimeUs - encodedOriginUs;
                                    if (timeUs < lastEncodedUs || timeUs < 0) throw invalidTimestamp();
                                    encoded.position(encodedInfo.offset);
                                    encoded.limit(encodedInfo.offset + encodedInfo.size);
                                    // EOS is written separately after the last complete AAC packet.
                                    encodedInfo.set(encodedInfo.offset, encodedInfo.size, timeUs,
                                            encodedInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME);
                                    muxer.write(encoded, encodedInfo);
                                    lastEncodedUs = timeUs;
                                    packetCount++;
                                    progress.audio(timeUs);
                                }
                            } finally {
                                encoder.releaseOutputBuffer(index, false);
                            }
                            encoderDone = eos;
                        }
                        if (encoderDone) break;
                    }
                }
                if (encoderDone) break;

                if (pendingPcm != null && encoder != null) {
                    int inputIndex = encoder.dequeueInputBuffer(0);
                    if (inputIndex >= 0) {
                        ByteBuffer input = requireBuffer(encoder.getInputBuffer(inputIndex));
                        input.clear();
                        int bytes = Math.min(input.remaining(), pendingPcm.remaining());
                        bytes -= bytes % frameBytes;
                        if (bytes <= 0) throw new IOException("手机 AAC 编码器的缓冲区太小。" );
                        long chunkTimeUs = pendingTimeUs
                                + (pendingBytesConsumed / frameBytes) * 1_000_000L / sampleRate;
                        int previousLimit = pendingPcm.limit();
                        pendingPcm.limit(pendingPcm.position() + bytes);
                        input.put(pendingPcm);
                        pendingPcm.limit(previousLimit);
                        encoder.queueInputBuffer(inputIndex, 0, bytes, chunkTimeUs, 0);
                        pendingBytesConsumed += bytes;
                        encoderInputEndUs = chunkTimeUs + (bytes / frameBytes) * 1_000_000L / sampleRate;
                        moved = true;
                        if (!pendingPcm.hasRemaining()) {
                            decoder.releaseOutputBuffer(pendingDecoderIndex, false);
                            pendingDecoderIndex = -1;
                            pendingPcm = null;
                            decoderDone = pendingEos;
                        }
                    }
                }

                if (decoderDone && pendingPcm == null && !encoderInputDone) {
                    if (encoder == null) throw new IOException("这个文件的音轨没有可解码的音频数据。" );
                    int index = encoder.dequeueInputBuffer(0);
                    if (index >= 0) {
                        encoder.queueInputBuffer(index, 0, 0, encoderInputEndUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        encoderInputDone = true;
                        moved = true;
                    }
                }

                if (!decoderDone && pendingPcm == null) {
                    int index = decoder.dequeueOutputBuffer(decodedInfo, 0);
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat pcmFormat = decoder.getOutputFormat();
                        int actualRate = requiredPositive(pcmFormat, MediaFormat.KEY_SAMPLE_RATE);
                        int actualChannels = requiredPositive(pcmFormat, MediaFormat.KEY_CHANNEL_COUNT);
                        requirePcm16(pcmFormat);
                        if (encoder == null) {
                            decodedRate = actualRate;
                            decodedChannels = actualChannels;
                            if (decodedChannels > 8) throw new IOException("暂不支持超过 8 个声道的录音。");
                            channels = compress ? 1 : actualChannels;
                            converter = compress ? new VoicePcm(actualRate, actualChannels) : null;
                            sampleRate = converter == null ? actualRate : converter.outputRate;
                            frameBytes = channels * 2;
                            encoderOwner.handle = createEncoder(sampleRate, channels, compress);
                            encoder = encoderOwner.handle.codec;
                        } else if (actualRate != decodedRate || actualChannels != decodedChannels) {
                            throw new IOException("这个文件的音轨在中途改变了采样率或声道，暂不支持。" );
                        }
                        moved = true;
                    } else if (index >= 0) {
                        moved = true;
                        boolean eos = (decodedInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        boolean config = (decodedInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                        if (decodedInfo.size > 0 && !config) {
                            if (encoder == null) {
                                decoder.releaseOutputBuffer(index, false);
                                throw new IOException("音频解码器未提供 PCM 格式。" );
                            }
                            if (decodedInfo.size % (decodedChannels * 2) != 0) {
                                decoder.releaseOutputBuffer(index, false);
                                throw new IOException("解码后的音频数据不完整。" );
                            }
                            ByteBuffer pcm = requireBuffer(decoder.getOutputBuffer(index));
                            validateRange(pcm, decodedInfo.offset, decodedInfo.size);
                            pcm.position(decodedInfo.offset);
                            pcm.limit(decodedInfo.offset + decodedInfo.size);
                            pendingPcm = converter == null ? pcm.slice() : converter.convert(pcm.slice());
                            if (!pendingPcm.hasRemaining()) {
                                decoder.releaseOutputBuffer(index, false);
                                pendingPcm = null;
                                decoderDone = eos;
                                continue;
                            }
                            pendingDecoderIndex = index;
                            pendingBytesConsumed = 0;
                            pendingEos = eos;
                            if (decodedOriginUs == Long.MIN_VALUE) decodedOriginUs = decodedInfo.presentationTimeUs;
                            pendingTimeUs = decodedInfo.presentationTimeUs - decodedOriginUs;
                            if (pendingTimeUs < 0) throw invalidTimestamp();
                        } else {
                            decoder.releaseOutputBuffer(index, false);
                            decoderDone = eos;
                        }
                    }
                }

                if (!decoderInputDone) {
                    int index = decoder.dequeueInputBuffer(0);
                    if (index >= 0) {
                        ByteBuffer input = requireBuffer(decoder.getInputBuffer(index));
                        input.clear();
                        if (extractor.getSampleTrackIndex() < 0) {
                            decoder.queueInputBuffer(index, 0, 0, decoderLastInputUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            decoderInputDone = true;
                        } else {
                            int flags = extractor.getSampleFlags();
                            requireClearSample(flags);
                            if (Build.VERSION.SDK_INT >= 28 && extractor.getSampleSize() > input.capacity()) {
                                throw new IOException("这个音轨的单帧数据超过手机解码器容量。" );
                            }
                            int bytes = extractor.readSampleData(input, 0);
                            if (bytes < 0) {
                                decoder.queueInputBuffer(index, 0, 0, decoderLastInputUs,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                decoderInputDone = true;
                            } else {
                                if (bytes > input.capacity()) throw sampleTooLarge();
                                long rawTimeUs = extractor.getSampleTime();
                                if (rawTimeUs < 0) throw invalidTimestamp();
                                if (sourceOriginUs < 0) sourceOriginUs = rawTimeUs;
                                decoderLastInputUs = rawTimeUs - sourceOriginUs;
                                if (decoderLastInputUs < 0) throw invalidTimestamp();
                                decoder.queueInputBuffer(index, 0, bytes, decoderLastInputUs,
                                        (flags & MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0
                                                ? MediaCodec.BUFFER_FLAG_PARTIAL_FRAME : 0);
                                extractor.advance();
                            }
                        }
                        moved = true;
                    }
                }

                if (moved) {
                    lastActivityMs = SystemClock.elapsedRealtime();
                } else {
                    if (SystemClock.elapsedRealtime() - lastActivityMs > STALL_TIMEOUT_MS) {
                        throw new IOException("手机音频编解码器长时间没有响应，请换一个文件再试。" );
                    }
                    SystemClock.sleep(2);
                }
            }
            if (packetCount == 0) throw new IOException("这个文件的音轨没有可提取的音频数据。" );
            long durationUs = Math.max(encoderInputEndUs,
                    lastEncodedUs + 1_024_000_000L / sampleRate);
            muxer.finish(durationUs);
            return new Stats(durationUs, sampleRate, channels);
        }
    }

    private static CodecHandle createEncoder(int sampleRate, int channels, boolean compress) throws IOException {
        if (channels > 8) throw new IOException("这个音轨的声道数量暂不支持 AAC 转换。" );
        MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, compress ? 32_000
                : Math.min(320_000, Math.max(96_000, channels * 64_000)));
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024);
        format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
        String codecName = new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format);
        if (compress) {
            // Prefer the platform AAC software encoder for offline work: vendor encoders
            // may be designed for real-time capture and can stall with offline buffer queues.
            for (MediaCodecInfo candidate : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!candidate.isEncoder()) continue;
                String name = candidate.getName();
                boolean software = Build.VERSION.SDK_INT >= 29 ? candidate.isSoftwareOnly()
                        : name.startsWith("OMX.google.") || name.startsWith("c2.android.");
                if (!software) continue;
                for (String type : candidate.getSupportedTypes()) {
                    if (type.equalsIgnoreCase(MediaFormat.MIMETYPE_AUDIO_AAC)
                            && candidate.getCapabilitiesForType(type).isFormatSupported(format)) {
                        codecName = name;
                        break;
                    }
                }
                if (name.equals(codecName)) break;
            }
        }
        if (codecName == null) {
            throw new IOException("当前手机不支持把这个音轨的采样率和声道编码为 AAC。" );
        }
        CodecHandle handle = new CodecHandle(MediaCodec.createByCodecName(codecName));
        try {
            handle.codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            handle.start();
            requirePcm16(handle.codec.getInputFormat());
            return handle;
        } catch (IOException | RuntimeException error) {
            try { handle.close(); } catch (RuntimeException closeError) { error.addSuppressed(closeError); }
            throw error;
        }
    }

    private static void requirePcm16(MediaFormat format) throws IOException {
        int encoding = AudioExtractor.getInt(format, MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
        if (encoding != AudioFormat.ENCODING_PCM_16BIT) {
            throw new IOException("当前手机解码器输出的 PCM 格式暂不支持转换。" );
        }
    }

    private static void requireClearSample(int flags) throws IOException {
        if ((flags & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0) {
            throw new IOException("这个文件的音轨受 DRM 加密保护，无法提取。" );
        }
    }

    private static int requiredPositive(MediaFormat format, String key) throws IOException {
        int value = AudioExtractor.getInt(format, key, 0);
        if (value <= 0) throw new IOException("音轨缺少有效的采样率或声道信息。" );
        return value;
    }

    private static ByteBuffer requireBuffer(ByteBuffer buffer) throws IOException {
        if (buffer == null) throw new IOException("手机编解码器未提供音频缓冲区。" );
        return buffer;
    }

    private static void validateRange(ByteBuffer buffer, int offset, int size) throws IOException {
        if (offset < 0 || size < 0 || offset > buffer.capacity() - size) {
            throw new IOException("手机编解码器返回了无效的音频数据范围。" );
        }
    }

    private static IOException sampleTooLarge() {
        return new IOException("这个音轨的单帧数据过大，暂不支持提取。" );
    }

    private static IOException invalidTimestamp() {
        return new IOException("这个文件的音轨时间戳异常，无法完整提取。" );
    }

    private static final class CodecHandle implements AutoCloseable {
        final MediaCodec codec;
        boolean started;
        CodecHandle(MediaCodec codec) { this.codec = codec; }
        void start() { codec.start(); started = true; }
        @Override public void close() {
            try { if (started) codec.stop(); }
            finally { codec.release(); }
        }
    }

    private static final class DeferredCodec implements AutoCloseable {
        CodecHandle handle;
        @Override public void close() { if (handle != null) handle.close(); }
    }

    private static final class MuxerOutput implements AutoCloseable {
        final MediaMuxer muxer;
        boolean started;
        int track;
        MuxerOutput(File output) throws IOException {
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        }
        void start(MediaFormat format) {
            track = muxer.addTrack(format);
            muxer.start();
            started = true;
        }
        void write(ByteBuffer buffer, MediaCodec.BufferInfo info) { muxer.writeSampleData(track, buffer, info); }
        void finish(long endUs) {
            MediaCodec.BufferInfo end = new MediaCodec.BufferInfo();
            end.set(0, 0, endUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            muxer.writeSampleData(track, ByteBuffer.allocateDirect(1), end);
            muxer.stop();
            started = false;
        }
        @Override public void close() { muxer.release(); }
    }
}
