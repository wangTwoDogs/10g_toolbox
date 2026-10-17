package com.ergoushushu.toolbox;

import android.net.Uri;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

/** Process-wide task state: the service survives activity rotation and backgrounding. */
public final class ExtractionStore {
    public enum Phase { IDLE, WORKING, SUCCESS, FAILED, CANCELLED }

    public static final class State {
        public final Phase phase;
        public final int progress;
        public final String message;
        public final String fileName;
        public final Uri output;
        public final long durationUs;
        public final long outputBytes;
        public final boolean compressed;
        public final long inputBytes;

        State(Phase phase, int progress, String message, String fileName, Uri output,
                long durationUs, long outputBytes) {
            this(phase, progress, message, fileName, output, durationUs, outputBytes, false, -1);
        }

        State(Phase phase, int progress, String message, String fileName, Uri output,
                long durationUs, long outputBytes, boolean compressed, long inputBytes) {
            this.phase = phase;
            this.progress = progress;
            this.message = message;
            this.fileName = fileName;
            this.output = output;
            this.durationUs = durationUs;
            this.outputBytes = outputBytes;
            this.compressed = compressed;
            this.inputBytes = inputBytes;
        }
    }

    private static final MutableLiveData<State> STATE = new MutableLiveData<>(idle());

    private ExtractionStore() { }

    public static LiveData<State> observe() { return STATE; }
    public static State current() { return STATE.getValue(); }
    static void update(State state) { STATE.setValue(state); }
    static void reset() {
        if (current().phase != Phase.WORKING) STATE.setValue(idle());
    }

    private static State idle() {
        return new State(Phase.IDLE, 0, "", "", null, 0, 0);
    }
}
