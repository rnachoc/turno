// Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
// Messages are always written with textContent, so a reply can never inject markup into the page.
"use strict";

const startForm = document.getElementById("start");
const composer = document.getElementById("composer");
const log = document.getElementById("log");
const statusLine = document.getElementById("status");
const textInput = document.getElementById("text");
let conversationId = null;

function addMessage(text, author) {
  const bubble = document.createElement("div");
  bubble.className = `message ${author}`;
  bubble.textContent = text;
  log.append(bubble);
  log.scrollTop = log.scrollHeight;
}

async function post(path, body) {
  const response = await fetch(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(data.detail?.code ?? "VALIDATION_FAILED");
  }
  return data;
}

function explain(code) {
  if (code === "PATIENT_NOT_FOUND") {
    return "This phone number is new: please add a name to register.";
  }
  return `Something went wrong (${code}).`;
}

startForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  statusLine.textContent = "";
  const name = document.getElementById("name").value.trim();
  try {
    const started = await post("/api/conversations", {
      phone: document.getElementById("phone").value.trim(),
      language: document.getElementById("language").value,
      ...(name ? { full_name: name } : {}),
    });
    conversationId = started.conversation_id;
    startForm.hidden = true;
    log.hidden = false;
    composer.hidden = false;
    textInput.focus();
  } catch (error) {
    statusLine.textContent = explain(error.message);
  }
});

composer.addEventListener("submit", async (event) => {
  event.preventDefault();
  const text = textInput.value.trim();
  if (!text) {
    return;
  }
  const button = composer.querySelector("button");
  addMessage(text, "patient");
  textInput.value = "";
  button.disabled = true;
  statusLine.textContent = "…";
  try {
    const answer = await post(`/api/conversations/${conversationId}/messages`, { text });
    addMessage(answer.reply, "agent");
    statusLine.textContent = answer.state === "with_a_person" ? "Handed over to a person." : "";
  } catch (error) {
    statusLine.textContent = explain(error.message);
  } finally {
    button.disabled = false;
    textInput.focus();
  }
});
