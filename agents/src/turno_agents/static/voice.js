// Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
// Sends the microphone to the service and plays what it answers. Text is only ever written as text.
"use strict";

const INPUT_RATE = 16000;
const form = document.getElementById("call");
const startButton = document.getElementById("start");
const hangupButton = document.getElementById("hangup");
const statusLine = document.getElementById("status");
const log = document.getElementById("log");

let socket = null;
let microphone = null;
let captureContext = null;
let playback = null;
let playing = [];
let nextStart = 0;

function say(who, text) {
  const line = document.createElement("p");
  line.className = who;
  line.textContent = text;
  log.append(line);
  line.scrollIntoView({ block: "nearest" });
}

function stopPlaying() {
  for (const source of playing) {
    source.stop();
  }
  playing = [];
  nextStart = 0;
}

function play(buffer) {
  const samples = new Int16Array(buffer);
  const audio = playback.createBuffer(1, samples.length, playback.sampleRate);
  const channel = audio.getChannelData(0);
  for (let i = 0; i < samples.length; i++) {
    channel[i] = samples[i] / 32768;
  }
  const source = playback.createBufferSource();
  source.buffer = audio;
  source.connect(playback.destination);
  const at = Math.max(playback.currentTime, nextStart);
  source.start(at);
  nextStart = at + audio.duration;
  playing.push(source);
  source.addEventListener("ended", () => {
    playing = playing.filter((other) => other !== source);
  });
}

function hangUp(message) {
  if (microphone) {
    for (const track of microphone.getTracks()) {
      track.stop();
    }
  }
  captureContext?.close();
  playback?.close();
  if (socket && socket.readyState <= WebSocket.OPEN) {
    socket.close();
  }
  socket = microphone = captureContext = playback = null;
  playing = [];
  nextStart = 0;
  startButton.hidden = false;
  hangupButton.hidden = true;
  statusLine.textContent = message;
}

async function capture() {
  captureContext = new AudioContext({ sampleRate: INPUT_RATE });
  await captureContext.audioWorklet.addModule("/static/voice-worklet.js");
  const source = captureContext.createMediaStreamSource(microphone);
  const worklet = new AudioWorkletNode(captureContext, "turno-capture");
  worklet.port.addEventListener("message", (event) => {
    if (socket?.readyState === WebSocket.OPEN) {
      socket.send(event.data);
    }
  });
  worklet.port.start();
  source.connect(worklet);
}

function explainClose(code) {
  const reasons = {
    4403: "This page is not allowed to open a call.",
    4408: "The call reached its time limit.",
    4429: "Too many calls at the moment. Please wait a minute.",
    4400: "The call could not start. Check the phone and the name.",
  };
  return reasons[code] ?? "The call has ended.";
}

function handle(message) {
  if (message.type === "ready") {
    playback = new AudioContext({ sampleRate: message.output_sample_rate });
    statusLine.textContent = "Speak when you like.";
  } else if (message.type === "heard") {
    say("patient", message.text);
  } else if (message.type === "reply") {
    say("agent", message.text);
  } else if (message.type === "stop") {
    stopPlaying();
  } else if (message.type === "timing" && message.reply_seconds !== null) {
    statusLine.textContent = `Answered in ${message.reply_seconds} s.`;
  }
}

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  statusLine.textContent = "Starting…";
  try {
    microphone = await navigator.mediaDevices.getUserMedia({
      audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true },
    });
  } catch {
    statusLine.textContent = "The microphone is not available.";
    return;
  }
  const scheme = location.protocol === "https:" ? "wss" : "ws";
  socket = new WebSocket(`${scheme}://${location.host}/ws/voice`);
  socket.binaryType = "arraybuffer";
  socket.addEventListener("open", async () => {
    const name = document.getElementById("name").value.trim();
    socket.send(
      JSON.stringify({
        phone: document.getElementById("phone").value.trim(),
        full_name: name || null,
        language: document.getElementById("language").value,
      }),
    );
    await capture();
    startButton.hidden = true;
    hangupButton.hidden = false;
  });
  socket.addEventListener("message", (message) => {
    if (typeof message.data === "string") {
      handle(JSON.parse(message.data));
    } else if (playback) {
      play(message.data);
    }
  });
  socket.addEventListener("close", (closed) => hangUp(explainClose(closed.code)));
});

hangupButton.addEventListener("click", () => hangUp("The call has ended."));
