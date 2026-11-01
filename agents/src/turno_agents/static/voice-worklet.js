// Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
// Runs on the audio thread: turns the microphone into 16-bit samples, in pieces of a few milliseconds.
"use strict";

const SAMPLES_PER_MESSAGE = 1024;

class TurnoCapture extends AudioWorkletProcessor {
  constructor() {
    super();
    this.pending = new Int16Array(SAMPLES_PER_MESSAGE);
    this.filled = 0;
  }

  process(inputs) {
    const channel = inputs[0]?.[0];
    if (!channel) {
      return true;
    }
    for (const sample of channel) {
      this.pending[this.filled++] = Math.max(-1, Math.min(1, sample)) * 32767;
      if (this.filled === SAMPLES_PER_MESSAGE) {
        this.port.postMessage(this.pending.slice().buffer);
        this.filled = 0;
      }
    }
    return true;
  }
}

registerProcessor("turno-capture", TurnoCapture);
