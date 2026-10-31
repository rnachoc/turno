// Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
// Everything from a recording is written as text, never as markup: a simulated patient tries attacks.
"use strict";

const STEP_LABELS = {
  model: "Asked the model",
  consent: "Consent",
  action: "The core did it",
  refused: "The core refused",
  blocked: "A guard stopped the reply",
  rejected: "Answer in the wrong format",
  handover: "Passed to a person",
};

const data = window.TURNO_REPLAY;
const list = document.getElementById("conversations");
const detail = document.getElementById("detail");
const filter = document.getElementById("filter");

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) {
    node.className = className;
  }
  if (text !== undefined) {
    node.textContent = text;
  }
  return node;
}

function show(conversation) {
  detail.replaceChildren();
  const head = element("div", "detail-head");
  head.append(element("h2", "", conversation.id));
  head.append(element("span", conversation.passed ? "tag ok" : "tag bad", conversation.passed ? "passed" : "failed"));
  detail.append(head);
  detail.append(
    element("p", "note", `${conversation.category} · ${conversation.calls} calls to the model · ${conversation.seconds} s`),
  );

  const columns = element("div", "columns");
  const talk = element("section", "talk");
  talk.append(element("h3", "", "The conversation"));
  for (const turn of conversation.turns) {
    const bubble = element("article", `bubble ${turn.role}`);
    bubble.append(element("span", "who", turn.role === "patient" ? "Simulated patient" : "Agent"));
    bubble.append(element("p", "", turn.text));
    talk.append(bubble);
  }
  columns.append(talk);

  const side = element("aside", "side");
  side.append(element("h3", "", "What the code did"));
  const steps = element("ol", "steps");
  for (const step of conversation.steps) {
    const item = element("li", `step kind-${step.kind}`);
    item.append(element("strong", "", STEP_LABELS[step.kind] ?? step.kind));
    item.append(element("code", "", step.detail));
    steps.append(item);
  }
  if (conversation.steps.length === 0) {
    steps.append(element("li", "note", "No steps."));
  }
  side.append(steps);

  side.append(element("h3", "", "How it ended"));
  const ended = conversation.appointments.map((a) => `${a.treatment}: ${a.status}`);
  side.append(element("p", "", ended.length ? ended.join(", ") : "No appointments."));
  if (conversation.handedOver) {
    side.append(element("p", "note", "Handed over to a person."));
  }

  side.append(element("h3", "", "Checks on the facts"));
  const checks = element("ul", "checks");
  for (const check of conversation.checks) {
    const item = element("li", check.passed ? "check ok" : "check bad");
    item.append(element("span", "", `${check.passed ? "✓" : "✗"} ${check.name}`));
    if (!check.passed && check.detail) {
      item.append(element("code", "", check.detail));
    }
    checks.append(item);
  }
  side.append(checks);
  columns.append(side);
  detail.append(columns);
}

function select(conversation, button) {
  for (const other of list.querySelectorAll("button")) {
    other.removeAttribute("aria-current");
  }
  button.setAttribute("aria-current", "true");
  show(conversation);
  history.replaceState(null, "", `#${conversation.id}`);
}

function render() {
  const chosen = filter.value;
  list.replaceChildren();
  const visible = data.conversations.filter((conversation) => {
    if (chosen === "all") {
      return true;
    }
    return chosen === "failed" ? !conversation.passed : conversation.category === chosen;
  });
  const wanted = decodeURIComponent(location.hash.slice(1));
  let first = null;
  let match = null;
  for (const conversation of visible) {
    const item = element("li");
    const button = element("button", conversation.passed ? "" : "failed", conversation.id);
    button.type = "button";
    button.addEventListener("click", () => select(conversation, button));
    item.append(button);
    list.append(item);
    first ??= [conversation, button];
    if (conversation.id === wanted) {
      match = [conversation, button];
    }
  }
  const opening = match ?? first;
  if (opening) {
    select(...opening);
  } else {
    detail.replaceChildren(element("p", "note", "Nothing to show."));
  }
}

function start() {
  const passed = data.conversations.filter((conversation) => conversation.passed).length;
  document.getElementById("summary").textContent =
    `${passed} of ${data.conversations.length} recorded conversations pass every check, out of ${data.total} in the bank. Each ran once, so this does not separate real failures from chance.`;
  const options = [
    ["all", "All conversations"],
    ["failed", "Failures"],
    ...[...new Set(data.conversations.map((conversation) => conversation.category))].sort().map((name) => [name, name]),
  ];
  for (const [value, label] of options) {
    const option = element("option", "", label);
    option.value = value;
    filter.append(option);
  }
  filter.addEventListener("change", render);
  render();
}

start();
