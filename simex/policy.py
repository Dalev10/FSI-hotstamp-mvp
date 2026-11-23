"""Reglas térmicas. Todas las ventanas usan segundos de simulación."""

from math import sqrt
from statistics import fmean, pstdev

OPERATING = {"TRANSPORT", "DISCARD", "THERMAL_WAIT", "STAMPING"}
GAINS = ("kp", "ki", "kd")
WINDOW_S = 60
PERSIST_WINDOWS = 2
SETTLE_S = 60
NOISE_FACTOR = 3
NOISE_FLOOR_C = 0.05  # Evita dividir por una referencia casi nula.
COLD_DROP_C = 3
MAX_COLD_ERROR_C = 2  # Error absoluto medio compatible con sostener la consigna.
MAX_COLD_DUTY = 0.90  # Deja margen antes de saturar la calefacción.
MIN_OPERATING_BAND = 0.95
MAX_DUTY_RATIO = 0.95  # Una reducción menor al 5 % se considera inconclusa.


def gains(snapshot):
    return {name: snapshot["control"][name] for name in GAINS}


def blocked(snapshot):
    safety = snapshot["safety"]
    return (
        snapshot["state"] in {"FAULT", "EMERGENCY_STOP"}
        or safety["estop"]
        or bool(safety["interlock"])
        or any(alarm["severity"] == "CRITICAL" for alarm in safety["activeAlarms"])
    )


def safe_outputs(snapshot):
    return (
        not snapshot["thermal"]["ssr"]
        and snapshot["thermal"]["heaterDuty"] == 0
        and not snapshot["conveyor"]["running"]
        and snapshot["conveyor"]["speedMps"] == 0
        and all(snapshot["valves"][name] == "RETRACTED" for name in ("discard", "stamp"))
    )


def metrics(samples, limits):
    temperatures = [sample["tempC"] for sample in samples]
    duty = [sample["heaterDuty"] for sample in samples]
    process = limits["process"]
    return {
        "tempC": fmean(temperatures),
        "setpointC": fmean(sample["setpointC"] for sample in samples),
        "ambientC": fmean(sample["ambientC"] for sample in samples),
        "noiseC": pstdev(b - a for a, b in zip(temperatures, temperatures[1:])) / sqrt(2),
        "heaterDuty": fmean(duty),
        "dutySd": pstdev(duty),
        "errorC": fmean(abs(s["tempC"] - s["setpointC"]) for s in samples),
        "inBand": fmean(process["windowMinC"] <= t <= process["windowMaxC"] for t in temperatures),
    }


def stable(samples, values):
    # Excluye el calentamiento y las ventanas que todavía tienen una deriva marcada.
    head = fmean(sample["tempC"] for sample in samples[:15])
    tail = fmean(sample["tempC"] for sample in samples[-15:])
    return abs(tail - head) <= 3 and values["errorC"] <= 4


def cold_response(values):
    return (
        values["errorC"] <= MAX_COLD_ERROR_C
        and values["heaterDuty"] < MAX_COLD_DUTY
        and values["inBand"] >= MIN_OPERATING_BAND
    )


def noise_adjustment(current, limits):
    # El paso publicado por /limits basta para ir y volver en un solo comando.
    fraction = limits["pidMaxStepFraction"]
    reduction = min(0.20, 0.99 * fraction / (1 + fraction))
    return {
        name: max(limits[name]["min"], current[name] * (1 - reduction))
        if name != "kd" else current[name]
        for name in GAINS
    }


def allowed_step(current, target, limits):
    return target != current and all(
        limits[name]["min"] <= target[name] <= limits[name]["max"]
        and abs(target[name] - current[name]) <= abs(current[name]) * limits["pidMaxStepFraction"]
        for name in GAINS
    )


def verify(before, after):
    # Una variación de la perturbación impide atribuir el resultado a las ganancias.
    if (
        abs(after["ambientC"] - before["ambientC"]) >= COLD_DROP_C
        or abs(after["setpointC"] - before["setpointC"]) > 0.01
        or not 0.67 * before["noiseC"] <= after["noiseC"] <= 1.5 * before["noiseC"]
    ):
        return "inconclusive", "El ambiente, la consigna o el ruido cambiaron entre ventanas."
    if (
        after["errorC"] > before["errorC"] + max(0.5, 0.25 * before["errorC"])
        or after["inBand"] < before["inBand"] - 0.05
    ):
        return "rollback", "Aumentó el error térmico o cayó la fracción de muestras en banda."
    if before["dutySd"] > 0 and after["dutySd"] <= MAX_DUTY_RATIO * before["dutySd"]:
        return "verified", "Disminuyó la variación del ciclo útil y se mantuvo la calidad térmica."
    return "inconclusive", "La variación del ciclo útil no bajó al menos un 5 %."
