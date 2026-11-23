"use strict";

const byId = (id) => document.getElementById(id);
const labels = {
  nominal: "Operación normal", "ruido-sensor": "Ruido de sensor",
  "frente-frio": "Frente frío", "termocupla-abierta": "Termocupla abierta",
  shadow: "Sombra", auto: "Automático", off: "Sin agente",
  LEGACY: "Control heredado", PID: "PID sin agente", AGENT: "PID con agente",
  OFF: "Apagada", HEATING: "Calentando", TRANSPORT: "Transportando",
  DISCARD: "Descartando", THERMAL_WAIT: "En espera térmica", STAMPING: "Estampando",
  FAULT: "En falla", EMERGENCY_STOP: "En paro de emergencia",
  RETRACTED: "Retraído", EXTENDED: "Extendido", EXTENDING: "Extendiéndose", RETRACTING: "Retrayéndose",
  THERMOCOUPLE_OPEN: "Termocupla abierta", PNEUMATIC_TIMEOUT: "Tiempo de pistón excedido",
  OVER_TEMPERATURE: "Sobretemperatura", WATCHDOG_TIMEOUT: "Latido de supervisión vencido",
  HEATUP_TIMEOUT: "Tiempo de calentamiento excedido", THERMAL_WAIT_LONG: "Espera térmica prolongada",
  TEMP_LOW: "Temperatura fuera de ventana por debajo", TEMP_HIGH: "Temperatura fuera de ventana por encima",
  CRITICAL: "Crítica", WARNING: "Advertencia",
  observed: "Observado", shadow_result: "Propuesta en sombra", kept: "Ajuste conservado",
  monitoring: "En observación", sending: "Enviando ajuste", confirming: "Confirmando ajuste",
  applied: "Aplicado · en verificación", verified: "Verificado", rejected: "Rechazado",
  rolled_back: "Revertido", inconclusive: "No concluyente", safe_stop: "Paro confirmado",
  no_adjustment: "Sin ajustes",
};
const label = (key) => labels[key] || key || "—";
const resultLabel = (key) => label(key === "shadow" ? "shadow_result" : key);
const format = (value, digits = 1, suffix = "") => {
  if (value === null || value === undefined || value === "") return "—";
  return new Intl.NumberFormat("es-CO", { maximumFractionDigits: digits }).format(Number(value)) + suffix;
};
const celsius = (value) => format(value, 2, " °C");
const percent = (value) => value == null ? "—" : format(100 * value, 1, " %");
function clock(ms) {
  if (ms == null) return "—";
  const seconds = Math.floor(ms / 1000);
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  return [h, m, s].map((value) => String(value).padStart(2, "0")).join(":");
}

function element(tag, value, className) {
  const node = document.createElement(tag);
  if (value !== undefined) node.textContent = value;
  if (className) node.className = className;
  return node;
}

function text(id, value) {
  const node = byId(id);
  if (node.textContent !== value) node.textContent = value;
}

function setNotice(id, message) {
  byId(id).hidden = !message;
  if (message) text(id, message);
}

const form = byId("run-form");
let live;
let requesting = false;
let decisionKey;
let alarmsKey;
let tableKey;

function render(state) {
  const first = !live;
  live = state;
  const { snapshot, run } = state;
  const thermal = snapshot.thermal || {};
  const currentMode = run && !state.ended ? run.mode : state.mode;
  if (first) byId("mode").value = state.mode;
  const connected = state.connected ? (state.fresh ? "live" : "stale") : "offline";
  byId("connection").dataset.state = connected;
  text("connection", { live: "Telemetría en vivo", stale: "Sin telemetría reciente", offline: "Gemelo desconectado" }[connected]);
  const details = snapshot.control?.mode === "PID" ? "Regulador térmico" : "Control heredado";
  const description = run
    ? `${label(run.scenario)} · Semilla ${run.seed} · ×${snapshot.speedFactor ?? run.speed} · ${details}`
    : state.runId ? "Escenario desconocido · Corrida iniciada fuera del tablero" : "Selecciona un escenario e inicia una corrida.";
  text("run-description", description);
  text("machine", label(snapshot.state));
  byId("machine").dataset.fault = Boolean(snapshot.safety?.interlock);
  text("clock", clock(state.simTimeMs));
  text("active-mode", label(currentMode));
  setNotice("notice", state.error || (state.ended
    ? (state.ended === "COMPLETED" ? "La corrida terminó. Se muestran sus últimas mediciones." : "La corrida se interrumpió.")
    : state.gaps
      ? "Se detectó una discontinuidad. Esta corrida sigue en observación y los ajustes están suspendidos."
      : state.watchdogTripped
        ? "El vigilante del gemelo restauró los valores seguros. Inicia otra corrida para aplicar ajustes."
        : state.connected && !state.fresh
          ? "Sin telemetría reciente. Los ajustes del agente están suspendidos." : ""));
  text("temp", celsius(thermal.tempC));
  text("setpoint", celsius(thermal.setpointC));
  text("ambient", celsius(thermal.ambientC));
  text("duty", percent(thermal.heaterDuty));
  text("heater", thermal.ssr == null ? "—" : thermal.ssr ? "Encendida" : "Apagada");
  text("belt", snapshot.conveyor ? (snapshot.conveyor.running ? "En marcha" : "Detenida") : "—");
  text("discard", label(snapshot.valves?.discard));
  text("stamp", label(snapshot.valves?.stamp));
  const process = state.process;
  if (process) text("band-label", `Ventana de estampado ${process.windowMinC}–${process.windowMaxC} °C`);
  drawChart();
  renderAlarms(snapshot.safety?.activeAlarms);
  renderDecision(state.decisions);
  renderComparison(state);
  updateControls();
}

function renderAlarms(alarms) {
  const key = JSON.stringify(alarms);
  if (key === alarmsKey) return;
  alarmsKey = key;
  text("alarm-count", String(alarms?.length ?? 0));
  const items = (alarms || []).map((alarm) => {
    const item = element("div", undefined, "alarm-item");
    const time = clock(alarm.raisedAtSimMs);
    item.append(element("strong", label(alarm.code)), element("small", `${label(alarm.severity)} · ${time}`));
    return item;
  });
  if (!items.length) items.push(element("p", alarms ? "Sin alarmas activas." : "Esperando datos de seguridad.", "muted"));
  byId("alarms").replaceChildren(...items);
}

function drawChart() {
  const canvas = byId("chart");
  const { width, height } = canvas.getBoundingClientRect();
  if (!width || !height) return;
  const ratio = window.devicePixelRatio || 1;
  canvas.width = Math.round(width * ratio);
  canvas.height = Math.round(height * ratio);
  const ctx = canvas.getContext("2d");
  ctx.scale(ratio, ratio);
  ctx.font = "12px system-ui";
  ctx.fillStyle = "#4d665a";
  const points = live?.graph ?? [];
  if (!points.length) {
    ctx.fillText("El historial aparece al llegar las mediciones.", 10, 30);
    return;
  }
  const left = 48, right = width - 12, top = 16, bottom = height - 29;
  const latest = points[points.length - 1][0];
  const from = points[0][0];
  const to = Math.max(from + 60_000, latest);
  const limit = live.process;
  const values = points.flatMap((point) => point.slice(1));
  if (limit) values.push(limit.windowMinC, limit.windowMaxC);
  const min = Math.floor((Math.min(...values) - 3) / 5) * 5;
  const max = Math.ceil((Math.max(...values) + 3) / 5) * 5;
  const x = (t) => left + ((t - from) / (to - from)) * (right - left);
  const y = (v) => bottom - ((v - min) / (max - min)) * (bottom - top);
  if (limit) {
    ctx.fillStyle = "#e6f2e8";
    ctx.fillRect(left, y(limit.windowMaxC), right - left, y(limit.windowMinC) - y(limit.windowMaxC));
  }
  const yStep = Math.max(5, Math.ceil((max - min) / 25) * 5);
  for (let value = min; value <= max; value += yStep) {
    ctx.fillStyle = "#4d665a";
    ctx.textAlign = "right";
    ctx.fillText(`${value}°`, left - 8, y(value) + 4);
    ctx.strokeStyle = "#dbe7df";
    ctx.beginPath();
    ctx.moveTo(left, y(value));
    ctx.lineTo(right, y(value));
    ctx.stroke();
  }
  const xIntervals = width >= 520 ? 4 : 2;
  for (let index = 0; index <= xIntervals; index++) {
    const timestamp = from + index * (to - from) / xIntervals;
    ctx.textAlign = index === xIntervals ? "right" : index === 0 ? "left" : "center";
    ctx.fillStyle = "#4d665a";
    ctx.fillText(clock(timestamp), x(timestamp), bottom + 20);
  }
  for (const [time, kind] of live.marks) {
    if (time < from || time > to) continue;
    ctx.strokeStyle = kind === "alarm" ? "#a33232" : "#925711";
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.moveTo(x(time), top);
    ctx.lineTo(x(time), bottom);
    ctx.stroke();
  }
  for (const [index, color] of [[2, "#52668e"], [1, "#14684d"]]) {
    ctx.lineWidth = 2;
    ctx.strokeStyle = color;
    ctx.setLineDash(index === 2 ? [6, 4] : []);
    ctx.beginPath();
    let previous = -Infinity;
    for (const point of points) {
      const plot = point[0] - previous > 1000 ? ctx.moveTo : ctx.lineTo;
      plot.call(ctx, x(point[0]), y(point[index]));
      previous = point[0];
    }
    ctx.stroke();
  }
}

const evidenceFields = [
  ["Temperatura medida", "tempC", celsius], ["Objetivo", "setpointC", celsius],
  ["Ambiente", "ambientC", celsius], ["Ruido de medición", "noiseC", celsius],
  ["Demanda media de calefacción", "heaterDuty", percent],
  ["Variación del ciclo útil", "dutySd", (value) => format(100 * value, 2, " pp")],
  ["Error térmico medio", "errorC", celsius], ["Muestras en ventana", "inBand", percent],
];

function renderDecision(history) {
  const key = JSON.stringify([live.runId, live.run?.mode, history]);
  if (key === decisionKey) return;
  decisionKey = key;
  const current = history[0];
  const status = byId("decision-status");
  status.hidden = !current;
  byId("history").hidden = history.length <= 1;
  if (!current) {
    const message = live.run?.mode === "off"
      ? "Este brazo de la comparación corre sin supervisión."
      : live.run
        ? "El agente está reuniendo mediciones para formar una referencia."
        : "Inicia un escenario desde el tablero para supervisar la corrida.";
    byId("decision").replaceChildren(element("p", message, "muted"));
    return;
  }
  text("decision-status", resultLabel(current.result));
  status.dataset.result = current.result;
  const evidence = element("dl", undefined, "evidence");
  const add = (title, value) => {
    const item = element("div");
    item.append(element("dt", title), element("dd", value));
    evidence.append(item);
  };
  const facts = current.evidence || {};
  if (facts.observed) {
    const keys = current.diagnosis === "cold"
      ? ["ambientC", "tempC", "setpointC", "heaterDuty", "errorC", "inBand"]
      : ["noiseC", "dutySd", "errorC", "inBand"];
    for (const [title, field, unit] of evidenceFields) {
      if (!keys.includes(field)) continue;
      const observed = unit(facts.observed[field]);
      if (current.after && ["dutySd", "errorC", "inBand"].includes(field)) {
        add(title + " · antes → después", observed + " → " + unit(current.after[field]));
      } else if (facts.reference && (field === "noiseC" || field === "ambientC" || field === "heaterDuty")) {
        add(title + " · referencia → lectura", unit(facts.reference[field]) + " → " + observed);
      } else {
        add(title, observed);
      }
    }
  }
  if (current.before && current.proposed) {
    const heading = current.confirmedGains ? " · anterior → aplicado" : " · anterior → propuesto";
    for (const [gain, name] of [["kp", "Proporcional"], ["ki", "Integral"], ["kd", "Derivativa"]]) {
      add(name + heading, [current.before[gain], current.proposed[gain]].map((value) => format(value, 4)).join(" → "));
    }
  }
  if (facts.cause) {
    add("Protección activada", label(facts.cause));
    add("Salidas seguras", facts.safeOutputs ? "Confirmadas" : "Por confirmar");
    add("Ajustes del agente en la corrida", String(facts.adjustments));
  }
  if (facts.restored && facts.before) {
    for (const [gain, name] of [["kp", "Proporcional"], ["ki", "Integral"]]) {
      add(name + " · anterior → restaurado",
        [facts.before[gain], facts.restored[gain]].map((value) => format(value, 4)).join(" → "));
    }
  }
  const metadata = `${clock(current.simTimeMs)} simulados · Modo ${label(current.mode).toLowerCase()} · Explicación por plantilla`;
  const parts = [element("h3", current.title), element("p", current.explanation, "decision-copy")];
  if (evidence.childElementCount) parts.push(evidence);
  if (current.note) parts.push(element("p", current.note, "decision-copy"));
  if (facts.observed && current.diagnosis !== "cold") {
    parts.push(element("p", "pp significa puntos porcentuales.", "muted decision-meta"));
  }
  parts.push(element("p", metadata, "muted decision-meta"));
  byId("decision").replaceChildren(...parts);
  byId("timeline").replaceChildren(...history.slice(1).map((item) =>
    element("li", `${clock(item.simTimeMs)} · ${item.title} · ${resultLabel(item.result)}`)));
}

const expectations = ["LEGACY", "PID", "AGENT"];
const comparisonMetrics = [
  ["piecesPerHour", "piezas por hora", (v) => format(v, 2)],
  ["inBandPct", "muestras en ventana", (v) => format(v, 3, " %")],
  ["kwh", "energía", (v) => format(v, 6)],
  ["waitMeanS", "espera media", (v) => format(v, 3, " s")],
  ["metalEscaped", "metales escapados", (v) => format(v, 0)],
];

function renderComparison(state) {
  const experiment = state.experiment;
  const running = experiment?.status === "running";
  const armTime = state.run?.experiment === experiment?.id && state.run?.arm === experiment?.arm
    ? clock(state.simTimeMs) + " simulados" : "Preparando corrida";
  setNotice("comparison-progress", running
    ? `En curso · ${label(experiment.arm)} · ${armTime}`
    : experiment?.note);
  const selector = byId("experiments");
  const previous = selector.value;
  const key = JSON.stringify([state.comparison, experiment, previous]);
  byId("decisions-file").hidden = !state.hasDecisions;
  byId("reference-file").hidden = !state.comparison.some((row) => row.reference);
  if (key === tableKey) return;
  const groups = new Map();
  for (const row of state.comparison) {
    if (!groups.has(row.experiment)) groups.set(row.experiment, []);
    groups.get(row.experiment).push(row);
  }
  if (experiment && !groups.has(experiment.id)) groups.set(experiment.id, []);
  const selected = groups.has(previous) ? previous : [...groups.keys()].at(-1);
  selector.replaceChildren(...[...groups].reverse().map(([id, rows]) => {
    const example = rows[0];
    const name = example
      ? `${label(example.scenario)} · Semilla ${example.seed} · ×${example.speed} · ${example.reference ? "Referencia" : id.slice(0, 8)}`
      : `${label(experiment.scenario)} · ${id.slice(0, 8)}`;
    const option = element("option", name);
    option.value = id;
    return option;
  }));
  if (selected) selector.value = selected;
  tableKey = JSON.stringify([state.comparison, experiment, selector.value]);
  byId("experiment-label").hidden = !selected;
  const rows = groups.get(selected) || [];
  if (!selected) return;
  const example = rows[0];
  const complete = rows.length === 3 && rows.every((row) => row.status === "complete");
  const metadata = example
    ? `${label(example.scenario)} · Semilla ${example.seed} · ×${example.speed} · ${clock(Number(example.durationS) * 1000)} simulados por brazo`
    : `${label(experiment.scenario)} · Esperando el primer brazo`;
  text("comparison-meta", metadata + (complete ? "" : " · Comparación sin completar"));
  byId("results").replaceChildren(...expectations.map((arm) => {
    const row = rows.find((item) => item.arm === arm);
    const tr = element("tr");
    const title = element("th", label(arm));
    title.scope = "row";
    tr.append(title);
    if (!row) {
      const td = element("td", running && experiment.id === selected && experiment.arm === arm ? "En curso" : "Pendiente");
      td.colSpan = 9;
      tr.append(td);
      return tr;
    }
    for (const [field, , unit] of comparisonMetrics) tr.append(element("td", unit(row[field])));
    tr.append(element("td", `${row.samplesMissing}${Number(row.streamGaps) ? " · flujo discontinuo" : ""}`));
    tr.append(element("td", `${celsius(row.lateErrorC)} / ${format(row.lateDutySdPp, 2, " pp")}`));
    const at = (value) => value === "" || value == null ? "—" : clock(Number(value) * 1000);
    tr.append(element("td", `${at(row.decisionAtS)} / ${at(row.appliedAtS)}`));
    const outcome = resultLabel(row.result);
    tr.append(element("td", row.status === "complete" ? outcome : `No concluyente · ${outcome}`));
    return tr;
  }));
  let note = "";
  if (complete) {
    const pid = rows.find((row) => row.arm === "PID");
    const supervised = rows.find((row) => row.arm === "AGENT");
    const ties = comparisonMetrics.filter(([field]) => pid[field] !== "" && supervised[field] !== ""
      && pid[field] != null && supervised[field] != null && Number(pid[field]) === Number(supervised[field]))
      .map(([, name]) => name);
    if (ties.length) note = `Empate entre los dos brazos PID en ${ties.join(", ")}.`;
    const near = comparisonMetrics.filter(([field]) => {
      if (pid[field] === "" || supervised[field] === "" || pid[field] == null || supervised[field] == null) return false;
      const base = Number(pid[field]);
      const difference = Math.abs(Number(supervised[field]) - base);
      return difference > 0 && difference < 0.02 * Math.abs(base);
    }).map(([, name]) => name);
    if (near.length) note += ` Diferencia menor al 2 % en ${near.join(", ")}.`;
  }
  setNotice("ties", note);
}

function updateControls() {
  const busy = requesting || live?.starting || live?.experiment?.status === "running";
  const unavailable = busy || !live?.connected || live.commandPending;
  const waiting = ["sending", "confirming", "applied"].includes(live?.decisions[0]?.result);
  byId("launch").disabled = unavailable;
  byId("compare").disabled = unavailable || waiting;
  byId("change-mode").disabled = !live?.run || !!live.ended || busy
    || live.run.mode === byId("mode").value;
}

async function request(path, body, message) {
  requesting = true;
  updateControls();
  setNotice("feedback", "Enviando solicitud al programa local…");
  byId("feedback").dataset.error = false;
  try {
    const response = await fetch(path, {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
    });
    const payload = await response.json();
    if (!response.ok) throw new Error(typeof payload.detail === "string" ? payload.detail : "Revisa el escenario, la semilla y la velocidad.");
    if (path === "/api/comparison") byId("experiments").value = "";
    setNotice("feedback", message);
    await poll();
  } catch (error) {
    setNotice("feedback", error.message);
    byId("feedback").dataset.error = true;
  } finally {
    requesting = false;
    updateControls();
  }
}

function runOptions() {
  return { scenario: byId("scenario").value, seed: byId("seed").valueAsNumber, speed: Number(byId("speed").value) };
}

form.addEventListener("submit", (event) => {
  event.preventDefault();
  request("/api/run", { ...runOptions(), mode: byId("mode").value }, "El gemelo inició el escenario.");
});
byId("mode").addEventListener("change", updateControls);
byId("change-mode").addEventListener("click", () =>
  request("/api/mode", { mode: byId("mode").value }, "Modo de supervisión actualizado."));
byId("compare").addEventListener("click", () => {
  if (form.reportValidity()) request("/api/comparison", runOptions(), "Comparación iniciada. Los tres brazos se ejecutan en orden.");
});
byId("experiments").addEventListener("change", () => renderComparison(live));
window.addEventListener("resize", drawChart);

async function poll() {
  try {
    const response = await fetch("/api/status", { cache: "no-store" });
    if (!response.ok) throw new Error("Respuesta inválida");
    render(await response.json());
  } catch {
    setNotice("notice", "Se perdió la conexión con la aplicación local.");
    text("connection", "Tablero desconectado");
    for (const id of ["launch", "compare", "change-mode"]) byId(id).disabled = true;
  }
}

async function refresh() {
  await poll();
  setTimeout(refresh, 1000);
}

refresh();
