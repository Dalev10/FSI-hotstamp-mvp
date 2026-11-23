"""Registro de decisiones y resumen por corrida."""

import csv
import json
from pathlib import Path

COLUMNS = (
    "experiment", "arm", "runId", "scenario", "seed", "speed", "durationS", "status",
    "samples", "samplesMissing", "streamGaps", "operatingSamples", "stamped", "piecesPerHour",
    "inBandPct", "kwh", "waitMeanS", "metalEscaped", "lateErrorC", "lateDutySdPp",
    "decisionAtS", "appliedAtS",
    "detectionDelayS", "adjustments", "result",
)
LATE_WINDOW_S = 900  # Tramo común tras las ventanas de diagnóstico y ajuste de la demo.


class Journal:
    def __init__(self, directory, reference):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True)
        self.decisions = self.directory / "decisiones.jsonl"
        self.summary = self.directory / "resultados.csv"
        if not self.summary.exists():
            with self.summary.open("w", encoding="utf-8", newline="") as stream:
                csv.DictWriter(stream, COLUMNS).writeheader()
        self.rows = [dict(row, reference=True) for row in self.read_rows(reference)]
        archived = {row["runId"] for row in self.rows}
        self.rows.extend(row for row in self.read_rows(self.summary) if row["runId"] not in archived)

    @staticmethod
    def read_rows(path):
        if not path.exists():
            return []
        with path.open(encoding="utf-8", newline="") as stream:
            return list(csv.DictReader(stream))

    def log(self, decision):
        with self.decisions.open("a", encoding="utf-8") as stream:
            print(json.dumps(decision, ensure_ascii=False), file=stream)

    def save(self, row):
        with self.summary.open("a", encoding="utf-8", newline="") as stream:
            csv.DictWriter(stream, COLUMNS).writerow(row)
        self.rows.append(row)
