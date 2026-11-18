"use strict";
// POC replay viewer. Reads window.POC_REPLAY (written to replay-data.js by the tester) and replays each
// test instruction by instruction. See RunReplay in the Kotlin sources for the step delta format.
(function () {
  const $ = (id) => document.getElementById(id);
  const D = window.POC_REPLAY;
  if (!D || D.version !== 1) {
    $("missing").hidden = false;
    return;
  }

  const P = D.program;
  const fns = P.functions;
  const terms = D.terms;
  const runs = D.runs;
  const spans = P.spans.map(([l0, c0, l1, c1]) => ({ l0, c0, l1, c1 }));
  const CHECKPOINT_EVERY = 256;
  const JUMPS = /^(IntCmpJump|StrCmpJump)\(/;

  /** "parentRunIndex:constraintIndex" -> index of the run that flipping it produced. */
  const children = new Map();
  runs.forEach((r, i) => { if (r.from) children.set(`${r.from[0] - 1}:${r.from[1]}`, i); });

  // ---- per-viewer preferences (best effort: storage may be unavailable) ----
  const store = {
    get(key, fallback) {
      try {
        const v = localStorage.getItem("poc-replay:" + key);
        return v == null ? fallback : JSON.parse(v);
      } catch { return fallback; }
    },
    set(key, value) {
      try { localStorage.setItem("poc-replay:" + key, JSON.stringify(value)); } catch { /* ignore */ }
    },
  };

  // ---- text helpers ----
  const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
  const hex2 = (c) => c.toString(16).padStart(2, "0");

  /** A byte string as a POC literal, like the Kotlin quotePoc. */
  function quote(s) {
    let out = '"';
    for (const ch of s) {
      const c = ch.charCodeAt(0);
      if (ch === '"') out += '\\"';
      else if (ch === "\\") out += "\\\\";
      else if (ch === "\n") out += "\\n";
      else if (ch === "\t") out += "\\t";
      else if (c >= 0x20 && c <= 0x7e) out += ch;
      else out += "\\x" + hex2(c);
    }
    return out + '"';
  }

  const hexBytes = (s) => (s.length ? Array.from(s, (ch) => hex2(ch.charCodeAt(0))).join(" ") : "(empty)");

  /** Program output with control and non-ASCII bytes made visible. */
  function printable(s) {
    let out = "";
    for (const ch of s) {
      const c = ch.charCodeAt(0);
      if (ch === "\\") out += "\\\\";
      else if (c >= 0x20 && c <= 0x7e) out += ch;
      else if (ch === "\n") out += "\\n";
      else if (ch === "\t") out += "\\t";
      else out += "\\x" + hex2(c);
    }
    return out;
  }

  const plural = (n, word) => `${n} ${word}${n === 1 ? "" : "s"}`;
  const capitalise = (s) => s.charAt(0).toUpperCase() + s.slice(1);
  const term = (id) => terms[id];
  const siteSpan = (siteId) => spans[P.sites[siteId][1]];

  // ---- source pane ----
  const KEYWORDS = new Set(["fun", "MAIN", "if", "else", "return", "print", "len", "int", "str", "and", "or", "not"]);
  const lines = P.source.replace(/\r/g, "").split("\n");
  if (lines.length > 1 && lines[lines.length - 1] === "") lines.pop();

  function tokenize(line) {
    const toks = [];
    const re = /(\/\/.*$)|("(?:\\.|[^"\\])*"?)|(\d+)|([A-Za-z_]\w*)|(\s+)|(.)/g;
    let m;
    while ((m = re.exec(line)) && m[0].length) {
      const cls = m[1] ? "tok-com" : m[2] ? "tok-str" : m[3] ? "tok-num" : m[4] && KEYWORDS.has(m[0]) ? "tok-kw" : "";
      toks.push({ start: m.index, end: m.index + m[0].length, cls });
    }
    return toks;
  }
  const tokens = lines.map(tokenize);

  /** Renders line [i] (0-based); [range] = {from, to, cls} in 0-based columns (to exclusive). */
  function lineHtml(i, range, ghost) {
    let html = "";
    let open = false;
    for (const t of tokens[i]) {
      const cuts = [t.start, t.end];
      if (range) {
        if (range.from > t.start && range.from < t.end) cuts.push(range.from);
        if (range.to > t.start && range.to < t.end) cuts.push(range.to);
      }
      cuts.sort((a, b) => a - b);
      for (let k = 0; k + 1 < cuts.length; k++) {
        const a = cuts[k], b = cuts[k + 1];
        const inside = range && a >= range.from && b <= range.to;
        if (inside && !open) { html += `<mark class="${range.cls}">`; open = true; }
        if (!inside && open) { html += "</mark>"; open = false; }
        const text = esc(lines[i].slice(a, b));
        html += t.cls ? `<span class="${t.cls}">${text}</span>` : text;
      }
    }
    if (open) html += "</mark>";
    return html + (ghost || "");
  }

  const sourceEl = $("source");
  const lineEls = lines.map((_, i) => {
    const row = document.createElement("div");
    row.className = "line";
    row.innerHTML = `<span class="gutter">${i + 1}</span><span class="code"></span>`;
    row.lastChild.innerHTML = lineHtml(i, null, null);
    sourceEl.appendChild(row);
    return row;
  });
  let decorated = [];

  /**
   * Highlights [span] (1-based, end exclusive) with an optional ghost after its last line. [scroll] brings it
   * into view; hover previews pass false so the page never moves under the pointer.
   */
  function decorate(span, cls, ghost, scroll = true) {
    for (const i of decorated) {
      lineEls[i].classList.remove("current");
      lineEls[i].lastChild.innerHTML = lineHtml(i, null, null);
    }
    decorated = [];
    if (!span) return;
    for (let l = span.l0; l <= span.l1; l++) {
      const i = l - 1;
      if (!lineEls[i]) continue;
      const from = l === span.l0 ? span.c0 - 1 : 0;
      const to = l === span.l1 ? span.c1 - 1 : Infinity;
      lineEls[i].lastChild.innerHTML = lineHtml(i, { from, to, cls }, l === span.l1 ? ghost : null);
      lineEls[i].classList.add("current");
      decorated.push(i);
    }
    const first = lineEls[span.l0 - 1];
    if (first && scroll) scrollIntoViewIfNeeded(first);
  }

  function scrollIntoViewIfNeeded(el) {
    const box = el.getBoundingClientRect();
    const view = sourceEl.getBoundingClientRect();
    const transport = document.querySelector(".transport").getBoundingClientRect().top;
    if (box.top < Math.max(view.top, 0) || box.bottom > Math.min(view.bottom, transport)) {
      el.scrollIntoView({ block: "center", behavior: "auto" });
    }
  }

  // ---- run preparation: which function/pc each step ran in, and where things happen ----
  function prepare(run) {
    if (run._prep) return run._prep;
    const n = run.steps.length;
    const fnAt = new Int32Array(n);
    const pcAt = new Int32Array(n);
    const stack = [P.mainIndex];
    for (let i = 0; i < n; i++) {
      const s = run.steps[i];
      fnAt[i] = stack[stack.length - 1];
      pcAt[i] = typeof s === "number" ? s : s[0];
      if (typeof s !== "number") {
        for (let k = 1; k < s.length; k++) {
          if (s[k][0] === "c") stack.push(s[k][1]);
          else if (s[k][0] === "r") stack.pop();
        }
      }
    }
    const spanAt = (i) => spans[fns[fnAt[i]].pcSpan[pcAt[i]]];
    const lineFirst = new Map();
    for (let i = 0; i < n; i++) {
      const sp = spanAt(i);
      for (let l = sp.l0; l <= sp.l1; l++) if (!lineFirst.has(l)) lineFirst.set(l, i);
    }
    const constraintsAt = new Map();
    run.constraints.forEach((c, idx) => {
      if (!constraintsAt.has(c[0])) constraintsAt.set(c[0], []);
      constraintsAt.get(c[0]).push(idx);
    });
    run._prep = { n, fnAt, pcAt, spanAt, lineFirst, constraintsAt, checkpoints: [] };
    return run._prep;
  }

  // ---- machine state rebuilt from deltas ----
  function newFrame(fn, callPc) {
    const slots = fns[fn].slots;
    return {
      fn,
      callPc,
      pc: -1,
      values: slots.map((s) => (s[3] === "int" ? 0 : "")),
      syms: slots.map(() => null),
      written: new Set(),
      changed: new Set(),
    };
  }

  const cloneFrame = (f) => ({
    ...f,
    values: f.values.slice(),
    syms: f.syms.slice(),
    written: new Set(f.written),
    changed: new Set(f.changed),
  });
  const cloneState = (st) => ({ stack: st.stack.map(cloneFrame), last: st.last, finished: st.finished && cloneFrame(st.finished) });

  /** Applies effects; returns a summary of the step for the ghost (first write, call, return). */
  function applyEffects(st, effects, from) {
    const summary = { write: null, call: null, ret: false, retWrite: null };
    for (let k = from; k < effects.length; k++) {
      const e = effects[k];
      if (e[0] === "c") {
        const caller = st.stack[st.stack.length - 1];
        st.stack.push(newFrame(e[1], caller.pc));
        summary.call = e[1];
      } else if (e[0] === "r") {
        const popped = st.stack.pop();
        if (!st.stack.length) st.finished = popped; // keep MAIN's final variables on screen
        summary.ret = true;
      } else {
        const f = st.stack[st.stack.length - 1];
        f.values[e[0]] = e[1];
        f.syms[e[0]] = e.length > 2 ? e[2] : null;
        f.written.add(e[0]);
        f.changed.add(e[0]);
        const w = { fn: f.fn, slot: e[0], value: e[1], sym: e.length > 2 ? e[2] : null };
        if (summary.ret && !summary.retWrite) summary.retWrite = w;
        else if (!summary.write && !summary.ret && summary.call == null) summary.write = w;
      }
    }
    return summary;
  }

  function applyStep(st, s) {
    for (const f of st.stack) f.changed.clear();
    const top = st.stack[st.stack.length - 1];
    if (typeof s === "number") {
      top.pc = s;
      st.last = { write: null, call: null, ret: false, retWrite: null };
    } else {
      top.pc = s[0];
      st.last = applyEffects(st, s, 1);
    }
  }

  function initialState(run) {
    const st = { stack: [newFrame(P.mainIndex, -1)], last: null };
    applyEffects(st, run.init, 0);
    for (const f of st.stack) f.changed.clear();
    return st;
  }

  /** State after executing steps 0..c (c = -1: nothing executed yet). */
  function stateAt(run, c) {
    const prep = prepare(run);
    const cps = prep.checkpoints;
    if (!cps.length) cps.push(initialState(run));
    const k = Math.floor((c + 1) / CHECKPOINT_EVERY);
    while (cps.length <= k) {
      const st = cloneState(cps[cps.length - 1]);
      const from = (cps.length - 1) * CHECKPOINT_EVERY;
      for (let i = from; i < from + CHECKPOINT_EVERY; i++) applyStep(st, run.steps[i]);
      cps.push(st);
    }
    const st = cloneState(cps[k]);
    for (let i = k * CHECKPOINT_EVERY; i <= c; i++) applyStep(st, run.steps[i]);
    return st;
  }

  // ---- view state ----
  let runIdx = 0;
  let cursor = -1;
  let state = null;
  let viewFrame = null; // index into state.stack shown in Variables, null = top
  let playing = false;
  let raf = 0;
  let lastTick = 0;
  let carry = 0;
  let inputFormat = store.get("format", "text");
  let mode = store.get("mode", "instr");
  let showTemps = store.get("temps", false);
  let autoplay = store.get("autoplay", false);
  /** While autoplay holds on a finished test: the time to move to the next one. */
  let nextTestAt = null;
  const HOLD_MS = 1200;
  let siteFlash = null;

  const run = () => runs[runIdx];

  // ---- header and test bar ----
  function renderHeader() {
    $("program-name").textContent = `${P.name}.poc`;
    document.title = `${P.name}.poc replay`;
    const crashes = runs.filter((r) => r.outcome[0] === "error").length;
    const directions = new Set();
    for (const r of runs) for (const [site, taken] of r.covered) directions.add(`${site}:${taken}`);
    const stats = [
      [runs.length, runs.length === 1 ? "test" : "tests", ""],
      [crashes, crashes === 1 ? "crash" : "crashes", crashes ? "crash" : ""],
      [`${directions.size}/${P.sites.length * 2}`, "branch directions covered", ""],
      [D.stats.queries, "solver queries", ""],
    ];
    $("stats").innerHTML = stats
      .map(([v, label, cls]) => `<li class="${cls}"><strong>${v}</strong><span>${label}</span></li>`)
      .join("");

    $("tabs").innerHTML = runs
      .map((r, i) => {
        const cls = r.outcome[0] === "completed" ? "" : r.outcome[0];
        return `<button type="button" class="tab" role="tab" data-run="${i}" aria-selected="false" title="${esc(quote(r.input))}">` +
          `<span class="dot ${cls}"></span>Test ${i + 1}</button>`;
      })
      .join("");
  }

  function selectRun(i, keepCursor) {
    runIdx = Math.max(0, Math.min(runs.length - 1, i));
    for (const tab of $("tabs").children) {
      const sel = Number(tab.dataset.run) === runIdx;
      tab.setAttribute("aria-selected", String(sel));
      if (sel) tab.scrollIntoView({ block: "nearest", inline: "nearest" });
    }
    $("prev-test").disabled = runIdx === 0;
    $("next-test").disabled = runIdx === runs.length - 1;
    try { history.replaceState(null, "", `#test=${runIdx + 1}`); } catch { /* file:// may refuse */ }
    store.set("test", runIdx);
    const prep = prepare(run());
    const scrubber = $("scrubber");
    scrubber.max = String(prep.n - 1);
    renderTicks(prep);
    renderCard();
    seek(keepCursor ? Math.min(cursor, prep.n - 1) : -1);
  }

  function renderCard() {
    const r = run();
    $("input-value").textContent = inputFormat === "hex" ? hexBytes(r.input) : quote(r.input);
    $("fmt-text").setAttribute("aria-pressed", String(inputFormat === "text"));
    $("fmt-hex").setAttribute("aria-pressed", String(inputFormat === "hex"));

    const o = r.outcome;
    const outcome = $("outcome");
    outcome.className = "outcome " + o[0];
    if (o[0] === "completed") outcome.textContent = "Completed";
    else if (o[0] === "error") outcome.textContent = `${capitalise(o[2])} at line ${siteSpan(o[1]).l0}`;
    else outcome.textContent = `Stopped: ${o[1]}`;

    const lineage = $("lineage");
    if (!r.from) {
      lineage.textContent = "The initial seed: every other test is derived from it by flipping one branch.";
    } else {
      const parent = r.from[0] - 1;
      const c = runs[parent].constraints[r.from[1]];
      const site = P.sites[c[1]];
      lineage.innerHTML =
        `From <button type="button" class="link" data-goto="${parent}">Test ${parent + 1}</button> ` +
        `by flipping <code>${esc(site[3])}</code> from ${c[2]} to ${!c[2]}. ` +
        `The solver negated <span class="term" title="${esc(c[4])}">${esc(term(c[3]))}</span>.`;
    }
  }

  function renderTicks(prep) {
    const r = run();
    const n = prep.n;
    const pos = (step) => (n <= 1 ? 0 : (step / (n - 1)) * 100);
    let html = "";
    for (const c of r.constraints) html += `<span class="tick ${c[2]}" style="left:${pos(c[0])}%"></span>`;
    for (const o of r.output) html += `<span class="tick out" style="left:${pos(o[0])}%"></span>`;
    if (r.outcome[0] !== "completed") html += `<span class="tick crash" style="left:100%"></span>`;
    $("ticks").innerHTML = html;
  }

  // ---- per-step rendering ----
  function seek(c) {
    const r = run();
    const prep = prepare(r);
    c = Math.max(-1, Math.min(prep.n - 1, c));
    if (state && c === cursor + 1 && c >= 0) applyStep(state, r.steps[c]);
    else state = stateAt(r, c);
    cursor = c;
    viewFrame = null;
    render();
  }

  /** Source highlight and ghost only: hovering a constraint must not rebuild the list under the pointer. */
  function renderSource() {
    const r = run();
    const prep = prepare(r);
    const c = cursor;
    if (siteFlash != null) {
      decorate(siteSpan(siteFlash), "hl site", null, false);
    } else if (c >= 0) {
      decorate(prep.spanAt(c), "hl", ghostFor(r, prep, c));
    } else {
      decorate(null);
    }
  }

  function render() {
    const r = run();
    const prep = prepare(r);
    const c = cursor;

    renderSource();
    for (let i = 0; i < lineEls.length; i++) {
      const first = prep.lineFirst.get(i + 1);
      lineEls[i].classList.toggle("ran", first != null && first <= c);
    }

    // Status line
    const status = $("status-line");
    if (c < 0) {
      status.textContent = "Ready";
      status.title = "";
    } else {
      const fn = fns[prep.fnAt[c]];
      const text = fn.code[prep.pcAt[c]];
      status.textContent = `${fn.name} pc ${prep.pcAt[c]}  ${text}`;
      status.title = text;
    }

    renderVariables();
    renderConstraints(r, c);
    renderConsole(r, prep, c);

    $("scrubber").value = String(c);
    $("step-count").textContent = c < 0 ? `Ready, ${plural(prep.n, "step")}` : `Step ${c + 1} of ${prep.n}`;
    $("btn-start").disabled = $("btn-back").disabled = c < 0;
    $("btn-end").disabled = $("btn-fwd").disabled = c >= prep.n - 1;
  }

  function ghostFor(r, prep, c) {
    const isLast = c === prep.n - 1;
    if (isLast && r.outcome[0] === "error") {
      return `<span class="ghost crash">${esc(capitalise(r.outcome[2]))}</span>`;
    }
    if (isLast && r.outcome[0] === "limit") {
      return `<span class="ghost crash">${esc(capitalise(r.outcome[1]))}</span>`;
    }
    const recorded = prep.constraintsAt.get(c);
    if (recorded) {
      // A branch on the input: show the decision and its constraint, plus any value the step produced
      // (s[i] both checks bounds and loads the byte).
      const k = r.constraints[recorded[recorded.length - 1]];
      const write = state.last && state.last.write;
      return `<span class="ghost ${k[2]}">${k[2]} <span class="sym">${esc(term(k[3]))}</span></span>` +
        (write ? `<span class="ghost">= ${valueGhost(write)}</span>` : "");
    }
    const text = fns[prep.fnAt[c]].code[prep.pcAt[c]];
    if (JUMPS.test(text)) {
      const fellThrough = c + 1 < prep.n && prep.fnAt[c + 1] === prep.fnAt[c] && prep.pcAt[c + 1] === prep.pcAt[c] + 1;
      const taken = c + 1 < prep.n && !fellThrough;
      return `<span class="ghost ${taken}">${taken}</span>`;
    }
    const last = state.last;
    if (last && last.call != null) return `<span class="ghost">calls ${esc(fns[last.call].name)}</span>`;
    if (last && last.ret) {
      return last.retWrite ? `<span class="ghost">returns ${valueGhost(last.retWrite)}</span>` : `<span class="ghost">returns</span>`;
    }
    if (last && last.write) return `<span class="ghost">= ${valueGhost(last.write)}</span>`;
    if (/^Print/.test(text)) return `<span class="ghost">printed</span>`;
    return "";
  }

  function valueGhost(w) {
    const slot = fns[w.fn].slots[w.slot];
    const shown = slot[3] === "int" ? String(w.value) : quote(w.value);
    let sym = "";
    if (w.sym != null && slot[3] === "int") sym = ` <span class="sym">${esc(term(w.sym))}</span>`;
    else if (Array.isArray(w.sym) && w.sym.some((t) => t != null)) sym = ` <span class="sym">symbolic</span>`;
    return esc(shown) + sym;
  }

  function renderVariables() {
    const returned = !state.stack.length && state.finished;
    const stack = returned ? [state.finished] : state.stack;
    const shown = viewFrame == null ? stack.length - 1 : Math.min(viewFrame, stack.length - 1);
    const callstack = $("callstack");
    if (!stack.length) {
      callstack.innerHTML = "";
      $("vars").innerHTML = `<tr><td class="empty">The program has returned.</td></tr>`;
      return;
    }
    callstack.innerHTML = stack
      .map((f, i) => `${i ? '<span class="sep">›</span>' : ""}<button type="button" class="frame-btn" data-frame="${i}" aria-current="${i === shown}">${esc(fns[f.fn].name)}</button>`)
      .join("") + (returned ? `<span class="sep">returned</span>` : "");

    const f = stack[shown];
    const slots = fns[f.fn].slots;
    const rows = [];
    slots.forEach((slot, i) => {
      const [, , name, type, isTemp] = slot;
      if (!f.written.has(i) || (isTemp && !showTemps)) return;
      const value = type === "int" ? String(f.values[i]) : quote(f.values[i]);
      rows.push(
        `<tr class="${isTemp ? "temp " : ""}${f.changed.has(i) ? "changed" : ""}">` +
          `<td class="name">${esc(name)}</td><td class="type">${type}</td>` +
          `<td><span class="value">${esc(value)}</span>${symHtml(type, f.syms[i])}</td></tr>`,
      );
    });
    $("vars").innerHTML = rows.length ? rows.join("") : `<tr><td class="empty">Nothing assigned yet in ${esc(fns[f.fn].name)}.</td></tr>`;
  }

  function symHtml(type, sym) {
    if (sym == null) return "";
    if (type === "int") return `<br><span class="term">${esc(term(sym))}</span>`;
    const chips = [];
    if (sym[0] != null) chips.push(`<span class="term"><span class="idx">len</span>${esc(term(sym[0]))}</span>`);
    for (let k = 1; k < sym.length; k++) {
      if (sym[k] != null) chips.push(`<span class="term"><span class="idx">[${k - 1}]</span>${esc(term(sym[k]))}</span>`);
    }
    if (!chips.length) return "";
    if (chips.length <= 3) return "<br>" + chips.join("");
    return `<details><summary>${chips.length} symbolic parts</summary>${chips.join("")}</details>`;
  }

  function renderConstraints(r, c) {
    const visible = r.constraints.filter((k) => k[0] <= c);
    $("constraint-count").textContent = r.constraints.length ? `${visible.length} of ${r.constraints.length}` : "";
    if (!visible.length) {
      $("constraints").innerHTML = `<li class="empty">${r.constraints.length ? "None recorded yet." : "This test never branched on the input."}</li>`;
      return;
    }
    $("constraints").innerHTML = visible
      .map((k, idx) => {
        const child = children.get(`${runIdx}:${idx}`);
        const childBtn = child == null ? "" : `<button type="button" class="child" data-goto="${child}" title="Flipping this constraint produced Test ${child + 1}">Test ${child + 1}</button>`;
        return `<li class="${k[0] === c ? "latest" : ""}" data-step="${k[0]}" data-site="${k[1]}" title="${esc(k[4])}">` +
          `<span class="n">${idx + 1}</span><span class="pill ${k[2]}">${k[2] ? "T" : "F"}</span>` +
          `<span><span class="term">${esc(term(k[3]))}</span> <span class="where">line ${siteSpan(k[1]).l0}</span></span>${childBtn}</li>`;
      })
      .join("");
    const latest = $("constraints").querySelector(".latest");
    if (latest) latest.scrollIntoView({ block: "nearest" });
  }

  function renderConsole(r, prep, c) {
    const visible = r.output.filter((o) => o[0] <= c);
    let html = visible.map((o) => `<div class="out${o[0] === c ? " latest" : ""}">${esc(printable(o[1]))}</div>`).join("");
    if (!visible.length) html = `<div class="empty">${r.output.length ? "Nothing printed yet." : "This test prints nothing."}</div>`;
    if (c === prep.n - 1) {
      const o = r.outcome;
      const end = o[0] === "completed" ? "Finished normally." : o[0] === "error" ? `${capitalise(o[2])}.` : `Stopped: ${o[1]}.`;
      html += `<div class="end ${o[0]}">${esc(end)}</div>`;
    }
    $("console").innerHTML = html;
  }

  // ---- stepping and playback ----
  function lineKey(prep, i) {
    if (i < 0) return "start";
    return `${prep.fnAt[i]}:${prep.spanAt(i).l0}`;
  }

  /** The next cursor in [dir]; in line mode it lands on the last instruction of the next (or previous) line. */
  function nextCursor(dir) {
    const prep = prepare(run());
    if (mode === "instr") return cursor + dir;
    const here = lineKey(prep, cursor);
    let i = cursor + dir;
    while (i >= 0 && i < prep.n && lineKey(prep, i) === here) i += dir;
    if (dir > 0) while (i + 1 < prep.n && lineKey(prep, i + 1) === lineKey(prep, i)) i++;
    return i;
  }

  function step(dir) {
    siteFlash = null;
    const prep = prepare(run());
    const next = nextCursor(dir);
    if (next > prep.n - 1 || next < -1) return false;
    seek(next);
    return true;
  }

  const speed = () => Math.max(1, Math.round(Math.pow(60, Number($("speed").value) / 100)));

  function renderSpeed() {
    $("speed-value").textContent = `${speed()} ${mode === "line" ? "lines" : "steps"}/s`;
    $("mode-instr").setAttribute("aria-pressed", String(mode === "instr"));
    $("mode-line").setAttribute("aria-pressed", String(mode === "line"));
  }

  function setPlaying(on) {
    playing = on;
    nextTestAt = null;
    const btn = $("btn-play");
    btn.textContent = on ? "❚❚" : "▶︎";
    btn.setAttribute("aria-label", on ? "Pause" : "Play");
    cancelAnimationFrame(raf);
    if (on) {
      lastTick = performance.now();
      carry = 1; // move immediately on Play
      raf = requestAnimationFrame(tick);
    }
  }

  const hasNextTest = () => runIdx < runs.length - 1;

  function tick(now) {
    if (nextTestAt != null) {
      if (!autoplay) return setPlaying(false); // switched off during the hold
      if (now < nextTestAt) {
        raf = requestAnimationFrame(tick);
        return;
      }
      nextTestAt = null;
      selectRun(runIdx + 1);
      lastTick = now;
      carry = 1; // start the next test right away
    }
    carry += ((now - lastTick) / 1000) * speed();
    lastTick = now;
    while (carry >= 1) {
      carry -= 1;
      if (!step(1)) {
        if (autoplay && hasNextTest()) {
          // Hold on the finished test so its outcome can be read, then continue with the next one.
          nextTestAt = now + HOLD_MS;
          carry = 0;
          $("step-count").textContent = `Finished, Test ${runIdx + 2} next`;
          raf = requestAnimationFrame(tick);
          return;
        }
        setPlaying(false);
        return;
      }
    }
    raf = requestAnimationFrame(tick);
  }

  function togglePlay() {
    if (playing) return setPlaying(false);
    if (cursor >= prepare(run()).n - 1) {
      // At the end: with autoplay, carry on with the next test; otherwise replay this one.
      if (autoplay && hasNextTest()) selectRun(runIdx + 1);
      else seek(-1);
    }
    siteFlash = null;
    setPlaying(true);
  }

  function setAutoplay(on) {
    autoplay = on;
    $("autoplay").checked = on;
    store.set("autoplay", on);
  }

  // ---- events ----
  $("tabs").addEventListener("click", (e) => {
    const tab = e.target.closest(".tab");
    if (tab) { setPlaying(false); selectRun(Number(tab.dataset.run)); }
  });
  $("prev-test").addEventListener("click", () => { setPlaying(false); selectRun(runIdx - 1); });
  $("next-test").addEventListener("click", () => { setPlaying(false); selectRun(runIdx + 1); });

  document.addEventListener("click", (e) => {
    const go = e.target.closest("[data-goto]");
    if (go) {
      e.stopPropagation();
      setPlaying(false);
      selectRun(Number(go.dataset.goto));
      return;
    }
    const frame = e.target.closest("[data-frame]");
    if (frame) {
      viewFrame = Number(frame.dataset.frame);
      renderVariables();
      return;
    }
    const li = e.target.closest(".constraints li[data-step]");
    if (li) {
      setPlaying(false);
      siteFlash = null;
      seek(Number(li.dataset.step));
    }
  });

  $("constraints").addEventListener("mouseover", (e) => {
    const li = e.target.closest("li[data-site]");
    if (!li || playing) return;
    const site = Number(li.dataset.site);
    if (site === siteFlash) return;
    siteFlash = site;
    renderSource();
  });
  $("constraints").addEventListener("mouseleave", () => {
    if (siteFlash == null) return;
    siteFlash = null;
    renderSource();
  });

  $("fmt-text").addEventListener("click", () => { inputFormat = "text"; store.set("format", inputFormat); renderCard(); });
  $("fmt-hex").addEventListener("click", () => { inputFormat = "hex"; store.set("format", inputFormat); renderCard(); });
  $("mode-instr").addEventListener("click", () => { mode = "instr"; store.set("mode", mode); renderSpeed(); });
  $("mode-line").addEventListener("click", () => { mode = "line"; store.set("mode", mode); renderSpeed(); });
  $("show-temps").checked = showTemps;
  $("show-temps").addEventListener("change", (e) => { showTemps = e.target.checked; store.set("temps", showTemps); renderVariables(); });

  $("autoplay").checked = autoplay;
  $("autoplay").addEventListener("change", (e) => setAutoplay(e.target.checked));

  $("speed").value = String(store.get("speed", 45));
  $("speed").addEventListener("input", () => { store.set("speed", Number($("speed").value)); renderSpeed(); });

  $("scrubber").addEventListener("input", (e) => { setPlaying(false); siteFlash = null; seek(Number(e.target.value)); });
  $("btn-play").addEventListener("click", togglePlay);
  $("btn-fwd").addEventListener("click", () => { setPlaying(false); step(1); });
  $("btn-back").addEventListener("click", () => { setPlaying(false); step(-1); });
  $("btn-start").addEventListener("click", () => { setPlaying(false); siteFlash = null; seek(-1); });
  $("btn-end").addEventListener("click", () => { setPlaying(false); siteFlash = null; seek(prepare(run()).n - 1); });

  document.addEventListener("keydown", (e) => {
    const t = e.target;
    if (e.altKey || e.ctrlKey || e.metaKey) return;
    if (t.tagName === "INPUT" && t.type === "range" && !e.shiftKey && e.key.startsWith("Arrow")) return;
    if ((t.tagName === "BUTTON" || t.tagName === "INPUT") && (e.key === " " || e.key === "Enter")) return;
    switch (e.key) {
      case " ": togglePlay(); break;
      case "ArrowRight": setPlaying(false); if (e.shiftKey) selectRun(runIdx + 1); else step(1); break;
      case "ArrowLeft": setPlaying(false); if (e.shiftKey) selectRun(runIdx - 1); else step(-1); break;
      case "Home": setPlaying(false); seek(-1); break;
      case "End": setPlaying(false); seek(prepare(run()).n - 1); break;
      case "a": case "A": setAutoplay(!autoplay); break;
      default: return;
    }
    e.preventDefault();
  });

  // ---- start ----
  renderHeader();
  renderSpeed();
  const fromHash = /#test=(\d+)/.exec(location.hash);
  selectRun(fromHash ? Number(fromHash[1]) - 1 : store.get("test", 0));
})();
