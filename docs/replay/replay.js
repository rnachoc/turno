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

const ROWS = [
  ["booking", "Booking"],
  ["cancellation", "Cancelling"],
  ["reschedule", "Moving"],
  ["urgency", "Emergencies and medical questions"],
  ["manipulation", "Attempts to manipulate it"],
  ["ambiguity", "Unclear requests"],
  ["information", "Questions about the clinic"],
  ["language", "Other languages"],
];

const data = window.TURNO_REPLAY;
const rows = document.getElementById("rows");
const detail = document.getElementById("detail");

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

function title(conversation) {
  const words = conversation.id.replaceAll("-", " ");
  return words.charAt(0).toUpperCase() + words.slice(1);
}

function outcomeTag(conversation) {
  return element("span", conversation.passed ? "tag ok" : "tag bad", conversation.passed ? "passed" : "failed");
}

function show(conversation) {
  detail.replaceChildren();
  const top = element("div", "detail-top");
  const heading = element("div");
  const name = element("h2", "", title(conversation));
  name.id = "detail-title";
  heading.append(name);
  const about = element("p", "note", `${conversation.category} · ${conversation.calls} calls to the model · ${conversation.seconds} s · `);
  about.append(outcomeTag(conversation));
  heading.append(about);
  top.append(heading);
  const close = element("button", "close", "✕");
  close.type = "button";
  close.setAttribute("aria-label", "Close");
  close.addEventListener("click", () => detail.close());
  top.append(close);
  detail.append(top);

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
  side.append(element("p", "ended", ended.length ? ended.join(", ") : "No appointments."));
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

function open(conversation) {
  show(conversation);
  if (!detail.open) {
    detail.showModal();
  }
  detail.scrollTop = 0;
  history.replaceState(null, "", `#${conversation.id}`);
}

function card(conversation) {
  const item = element("li");
  const button = element("button", conversation.passed ? "card" : "card failed");
  button.type = "button";
  button.append(element("strong", "", title(conversation)));
  const said = conversation.turns.find((turn) => turn.role === "patient");
  button.append(element("span", "quote", said ? said.text : ""));
  const meta = element("span", "meta", `${conversation.calls} calls · ${conversation.seconds} s`);
  meta.append(outcomeTag(conversation));
  button.append(meta);
  button.addEventListener("click", () => open(conversation));
  item.append(button);
  return item;
}

function row(name, conversations) {
  const section = element("section", "row");
  const head = element("div", "row-head");
  const heading = element("h2", "", name);
  heading.append(element("span", "", String(conversations.length)));
  head.append(heading);
  const cards = element("ul", "cards");
  const controls = element("div", "row-controls");
  for (const [label, symbol, direction] of [["Earlier", "←", -1], ["Later", "→", 1]]) {
    const move = element("button", "", symbol);
    move.type = "button";
    move.setAttribute("aria-label", `${label} in ${name}`);
    move.addEventListener("click", () => cards.scrollBy({ left: direction * cards.clientWidth * 0.8 }));
    controls.append(move);
  }
  // Fewer than this fit on a wide screen, with nothing to move to.
  if (conversations.length > 3) {
    head.append(controls);
  }
  section.append(head);
  for (const conversation of conversations) {
    cards.append(card(conversation));
  }
  section.append(cards);
  return section;
}

function stat(value, label) {
  const entry = element("div");
  entry.append(element("dt", "", label));
  entry.append(element("dd", "", value));
  return entry;
}

function start() {
  const all = data.conversations;
  const passed = all.filter((conversation) => conversation.passed).length;
  const calls = all.reduce((sum, conversation) => sum + conversation.calls, 0);
  const handedOver = all.filter((conversation) => conversation.handedOver).length;
  document.getElementById("stats").append(
    stat(`${passed} of ${all.length}`, "pass every check"),
    stat(String(handedOver), "passed to a person"),
    stat(String(calls), "calls to the model"),
  );
  document.getElementById("summary").textContent =
    `Out of ${data.total} in the bank. Each ran once, so this does not separate real failures from chance.`;

  const failures = all.filter((conversation) => !conversation.passed);
  if (failures.length) {
    rows.append(row("Failures stay in", failures));
  }
  const named = new Map(ROWS);
  const categories = [...named.keys(), ...new Set(all.map((conversation) => conversation.category))];
  for (const category of new Set(categories)) {
    const members = all.filter((conversation) => conversation.category === category);
    if (members.length) {
      rows.append(row(named.get(category) ?? category, members));
    }
  }

  detail.addEventListener("close", () => history.replaceState(null, "", location.pathname));
  // A click on the backdrop lands on the dialog itself, not on anything inside it.
  detail.addEventListener("click", (event) => {
    if (event.target === detail) {
      detail.close();
    }
  });
  const wanted = decodeURIComponent(location.hash.slice(1));
  const linked = all.find((conversation) => conversation.id === wanted);
  if (linked) {
    open(linked);
  }
}

start();
