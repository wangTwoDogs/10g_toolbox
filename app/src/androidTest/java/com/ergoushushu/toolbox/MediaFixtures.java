package com.ergoushushu.toolbox;

import static org.junit.Assert.assertTrue;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Small generated fixtures avoid storing copyrighted media or depending on network access. */
final class MediaFixtures {
    static final int SAMPLE_RATE = 44_100;
    private static final int AUDIO_FRAMES = 20;
    private static final int SAMPLES_PER_FRAME = 1024;

    private MediaFixtures() { }

    static void writeAac(File destination) throws IOException {
        MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 96_000);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, SAMPLES_PER_FRAME * 2);
        encodeMp4(destination, format, AUDIO_FRAMES, SAMPLES_PER_FRAME * 2,
                SAMPLES_PER_FRAME * 1_000_000L / SAMPLE_RATE,
                (buffer, frame) -> {
                    buffer.order(ByteOrder.LITTLE_ENDIAN);
                    for (int sample = 0; sample < SAMPLES_PER_FRAME; sample++) {
                        buffer.putShort(tone(frame * SAMPLES_PER_FRAME + sample));
                    }
                });
    }

    static void writeVideoWithoutAudio(File destination) throws IOException {
        int width = 320;
        int height = 240;
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 128_000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 10);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        int pixels = width * height;
        encodeMp4(destination, format, 4, pixels * 3 / 2, 100_000,
                (buffer, frame) -> {
                    for (int index = 0; index < pixels; index++) buffer.put((byte) (32 + frame * 16));
                    for (int index = 0; index < pixels / 2; index++) buffer.put((byte) 128);
                });
    }

    static void combineVideoAndAudio(File video, File audio, File destination) throws IOException {
        MediaExtractor videoExtractor = new MediaExtractor();
        MediaExtractor audioExtractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean started = false;
        try {
            videoExtractor.setDataSource(video.getAbsolutePath());
            audioExtractor.setDataSource(audio.getAbsolutePath());
            assertTrue(videoExtractor.getTrackCount() == 1);
            assertTrue(audioExtractor.getTrackCount() == 1);
            MediaFormat videoFormat = videoExtractor.getTrackFormat(0);
            MediaFormat audioFormat = audioExtractor.getTrackFormat(0);
            assertTrue(MediaFormat.MIMETYPE_VIDEO_AVC.equals(videoFormat.getString(MediaFormat.KEY_MIME)));
            assertTrue(MediaFormat.MIMETYPE_AUDIO_AAC.equals(audioFormat.getString(MediaFormat.KEY_MIME)));
            videoExtractor.selectTrack(0);
            audioExtractor.selectTrack(0);
            muxer = new MediaMuxer(destination.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int videoTrack = muxer.addTrack(videoFormat);
            int audioTrack = muxer.addTrack(audioFormat);
            muxer.start();
            started = true;
            copySelectedTrack(videoExtractor, muxer, videoTrack, Integer.MAX_VALUE, -1);
            copySelectedTrack(audioExtractor, muxer, audioTrack, Integer.MAX_VALUE, -1);
        } finally {
            try {
                closeMuxer(muxer, started);
            } finally {
                videoExtractor.release();
                audioExtractor.release();
            }
        }
    }

    static void writeAacWithTimestampGap(File audio, File destination) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean started = false;
        try {
            extractor.setDataSource(audio.getAbsolutePath());
            MediaFormat format = extractor.getTrackFormat(0);
            assertTrue(MediaFormat.MIMETYPE_AUDIO_AAC.equals(format.getString(MediaFormat.KEY_MIME)));
            extractor.selectTrack(0);
            muxer = new MediaMuxer(destination.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int track = muxer.addTrack(format);
            muxer.start();
            started = true;
            int copied = copySelectedTrack(extractor, muxer, track, 2, 10_000_000);
            assertTrue("Need two AAC packets for the gap fixture", copied == 2);
            MediaCodec.BufferInfo end = new MediaCodec.BufferInfo();
            end.set(0, 0, 10_000_000L + 1_024_000_000L / SAMPLE_RATE,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            muxer.writeSampleData(track, ByteBuffer.allocate(0), end);
        } finally {
            try {
                closeMuxer(muxer, started);
            } finally {
                extractor.release();
            }
        }
    }

    private static int copySelectedTrack(MediaExtractor source, MediaMuxer muxer, int destinationTrack,
                                         int maxSamples, long forcedStepUs) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long originUs = source.getSampleTime();
        int copied = 0;
        while (copied < maxSamples) {
            buffer.clear();
            int bytes = source.readSampleData(buffer, 0);
            if (bytes < 0) break;
            long timestampUs = forcedStepUs >= 0 ? copied * forcedStepUs
                    : source.getSampleTime() - originUs;
            int flags = (source.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
            info.set(0, bytes, timestampUs, flags);
            buffer.position(0);
            buffer.limit(bytes);
            muxer.writeSampleData(destinationTrack, buffer, info);
            copied++;
            if (!source.advance()) break;
        }
        return copied;
    }

    private static void closeMuxer(MediaMuxer muxer, boolean started) {
        if (muxer == null) return;
        try {
            if (started) muxer.stop();
        } finally {
            muxer.release();
        }
    }

    static void writePcmWav(File destination) throws IOException {
        int samples = AUDIO_FRAMES * SAMPLES_PER_FRAME;
        int dataBytes = samples * 2;
        ByteBuffer data = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        data.put(new byte[] { 'R', 'I', 'F', 'F' });
        data.putInt(36 + dataBytes);
        data.put(new byte[] { 'W', 'A', 'V', 'E', 'f', 'm', 't', ' ' });
        data.putInt(16);
        data.putShort((short) 1); // PCM
        data.putShort((short) 1); // mono
        data.putInt(SAMPLE_RATE);
        data.putInt(SAMPLE_RATE * 2);
        data.putShort((short) 2);
        data.putShort((short) 16);
        data.put(new byte[] { 'd', 'a', 't', 'a' });
        data.putInt(dataBytes);
        for (int sample = 0; sample < samples; sample++) data.putShort(tone(sample));
        try (FileOutputStream stream = new FileOutputStream(destination)) {
            stream.write(data.array());
        }
    }

    private static short tone(int sample) {
        return (short) (Math.sin(sample * 2 * Math.PI * 440 / SAMPLE_RATE) * 6000);
    }

    private static void encodeMp4(File destination, MediaFormat format, int frames,
                                  int frameBytes, long frameDurationUs, FrameWriter writer)
            throws IOException {
        String codecName = new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format);
        assertTrue("No encoder available for fixture: " + format, codecName != null);
        MediaCodec encoder = MediaCodec.createByCodecName(codecName);
        MediaMuxer muxer = null;
        boolean encoderStarted = false;
        boolean muxerStarted = false;
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            encoderStarted = true;
            muxer = new MediaMuxer(destination.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int submitted = 0;
            int outputTrack = -1;
            boolean inputEnded = false;
            boolean outputEnded = false;
            long deadline = SystemClock.elapsedRealtime() + 10_000;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (!outputEnded) {
                assertTrue("Fixture encoder did not finish", SystemClock.elapsedRealtime() < deadline);
                if (!inputEnded) {
                    int inputIndex = encoder.dequeueInputBuffer(10_000);
                    if (inputIndex >= 0) {
                        ByteBuffer input = encoder.getInputBuffer(inputIndex);
                        input.clear();
                        if (submitted == frames) {
                            encoder.queueInputBuffer(inputIndex, 0, 0, submitted * frameDurationUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEnded = true;
                        } else {
                            assertTrue("Fixture input buffer too small", input.remaining() >= frameBytes);
                            writer.write(input, submitted);
                            encoder.queueInputBuffer(inputIndex, 0, frameBytes, submitted * frameDurationUs, 0);
                            submitted++;
                        }
                    }
                }
                int outputIndex = encoder.dequeueOutputBuffer(info, 10_000);
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outputTrack = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                } else if (outputIndex >= 0) {
                    if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        assertTrue("Encoder emitted samples before output format", muxerStarted);
                        ByteBuffer output = encoder.getOutputBuffer(outputIndex);
                        output.position(info.offset);
                        output.limit(info.offset + info.size);
                        muxer.writeSampleData(outputTrack, output, info);
                    }
                    outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    encoder.releaseOutputBuffer(outputIndex, false);
                }
            }
        } finally {
            try {
                if (muxer != null) {
                    try {
                        if (muxerStarted) muxer.stop();
                    } finally {
                        muxer.release();
                    }
                }
            } finally {
                try {
                    if (encoderStarted) encoder.stop();
                } finally {
                    encoder.release();
                }
            }
        }
    }

    private interface FrameWriter {
        void write(ByteBuffer buffer, int frame);
    }
}
