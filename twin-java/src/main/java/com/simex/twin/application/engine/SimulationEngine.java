package com.simex.twin.application.engine;

import com.simex.twin.application.engine.EngineRequest.CommandOutcome;
import com.simex.twin.application.engine.Frames.TelemetryFrame;
import com.simex.twin.application.port.out.EngineMetrics;
import com.simex.twin.application.port.out.TelemetryPublisher;
import com.simex.twin.application.port.out.WallClock;
import com.simex.twin.application.service.TelemetryAssembler;
import com.simex.twin.domain.control.ControlParameters;
import com.simex.twin.domain.control.ControlStatus;
import com.simex.twin.domain.control.command.CommandResult;
import com.simex.twin.domain.control.command.Rule;
import com.simex.twin.domain.control.thermal.ControlMode;
import com.simex.twin.domain.event.DomainEvent;
import com.simex.twin.domain.machine.MachineSnapshot;
import com.simex.twin.domain.machine.ScanResult;
import com.simex.twin.domain.plant.PlantParameters;
import com.simex.twin.domain.scenario.Scenario;
import com.simex.twin.domain.scenario.ScenarioRunner;
import com.simex.twin.domain.shared.SimDuration;
import java.time.Instant;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Motor síncrono: no sabe de hilos. En cada ciclo vacía la bandeja (también en pausa), avanza {@code speedFactor}
 * ticks, publica telemetría en cada frontera de 1 s simulado y los eventos al momento, y deja la vista inmutable en un
 * {@link AtomicReference} para las lecturas concurrentes. Solo el hilo que llama a {@link #runCycle} lo muta.
 */
public final class SimulationEngine {

    /** Lo que se lee sin bloquear: el estado y la última telemetría (o la máquina en reposo si no hubo corridas). */
    /** @param runStartedAt inicio de pared de la corrida actual (para {@code simTs}); vacío sin corridas */
    public record EngineView(
            SimulationStatus status,
            Optional<TelemetryFrame> latest,
            MachineSnapshot snapshot,
            Optional<Instant> runStartedAt) {}

    /** Clave del {@code runId} en el MDC de los logs. */
    public static final String MDC_RUN_ID = "runId";

    // SLF4J y no System.Logger: JUL suelta sus handlers en su propio shutdown hook y se perdería el log del cierre.
    private static final Logger LOG = LoggerFactory.getLogger(SimulationEngine.class);
    private static final int MAX_MODE_BLOCK_TICKS = 1_000;
    private static final String NO_RUN = "No hay una corrida activa";

    private final PlantParameters plant;
    private final ControlParameters control;
    private final SimDuration tick;
    private final long telemetryPeriodMs;
    private final TelemetryPublisher publisher;
    private final EngineMetrics metrics;
    private final WallClock clock;
    private final Supplier<String> runIds;
    private final IdempotencyStore idempotency;
    private final TelemetryAssembler assembler = new TelemetryAssembler();
    private final Queue<EngineRequest> inbox = new ConcurrentLinkedQueue<>();
    private final AtomicReference<EngineView> view;

    private Optional<RunContext> run = Optional.empty();
    private boolean paused = true;
    private int speedFactor = 1;
    private Instant now;

    /**
     * @param idle escenario de la máquina en reposo que se muestra antes de la primera corrida
     */
    public SimulationEngine(
            PlantParameters plant,
            ControlParameters control,
            SimDuration tick,
            SimDuration telemetryPeriod,
            int idempotencyCapacity,
            TelemetryPublisher publisher,
            EngineMetrics metrics,
            WallClock clock,
            Supplier<String> runIds,
            Scenario idle) {
        this.plant = plant;
        this.control = control;
        this.tick = tick;
        this.telemetryPeriodMs = telemetryPeriod.ms();
        this.publisher = publisher;
        this.metrics = metrics;
        this.clock = clock;
        this.runIds = runIds;
        this.idempotency = new IdempotencyStore(idempotencyCapacity);
        this.now = clock.now();
        MachineSnapshot idleSnapshot = new ScenarioRunner(idle, idle.seed(), plant, control, tick)
                .machine()
                .snapshot();
        this.view = new AtomicReference<>(new EngineView(status(), Optional.empty(), idleSnapshot, Optional.empty()));
    }

    /** Lo llama cualquier hilo; se aplica en el próximo ciclo. */
    public void submit(EngineRequest request) {
        inbox.add(request);
    }

    public EngineView view() {
        return view.get();
    }

    /** Un ciclo de pared: bandeja y, si la corrida está en marcha, {@code speedFactor} ticks. */
    public void runCycle() {
        now = clock.now();
        drainInbox();
        if (isRunning()) {
            advance(speedFactor);
        }
        publishView();
    }

    /** Modo MAX (pruebas y uso interno): avanza sin dormir, en bloques, vaciando la bandeja entre bloques. */
    public void runMax(long ticks) {
        long remaining = ticks;
        while (remaining > 0) {
            now = clock.now();
            drainInbox();
            if (!isRunning()) {
                break;
            }
            int block = (int) Math.min(remaining, MAX_MODE_BLOCK_TICKS);
            advance(block);
            remaining -= block;
        }
        publishView();
    }

    private void drainInbox() {
        EngineRequest request;
        while ((request = inbox.poll()) != null) {
            handle(request);
        }
    }

    private void handle(EngineRequest request) {
        switch (request) {
            case EngineRequest.ApplyCommand r -> r.result().complete(applyCommand(r));
            case EngineRequest.Heartbeat r ->
                activeRun().ifPresent(c -> c.runner().machine().heartbeat(now.toEpochMilli()));
            case EngineRequest.StartRun r -> r.runId().complete(startRun(r));
            case EngineRequest.Control r -> {
                control(r);
                r.status().complete(status());
            }
            case EngineRequest.InjectFault r ->
                r.faultId()
                        .complete(activeRun()
                                .map(c -> c.runner()
                                        .machine()
                                        .injectFault(r.fault(), r.duration())
                                        .faultId()));
            case EngineRequest.RemoveFault r ->
                r.removed()
                        .complete(activeRun()
                                .map(c -> c.runner()
                                        .machine()
                                        .removeFault(r.faultId())
                                        .isPresent()));
        }
    }

    private CommandOutcome applyCommand(EngineRequest.ApplyCommand r) {
        String commandId = r.command().commandId();
        Optional<CommandResult> previous = idempotency.find(commandId);
        if (previous.isPresent()) {
            return new CommandOutcome(previous.get(), true);
        }
        CommandResult result = activeRun()
                .map(c -> c.runner().machine().apply(r.command()))
                .orElseGet(() -> new CommandResult.Rejected(commandId, Rule.STATE_NOT_ALLOWED, NO_RUN));
        idempotency.remember(result);
        return new CommandOutcome(result, false);
    }

    private String startRun(EngineRequest.StartRun r) {
        activeRun().ifPresent(c -> endRun(c, DomainEvent.RunEndReason.REPLACED));
        Scenario scenario =
                r.controlMode().map(mode -> withMode(r.scenario(), mode)).orElse(r.scenario());
        long seed = r.seed().orElse(scenario.seed());
        RunContext context =
                new RunContext(runIds.get(), seed, now, new ScenarioRunner(scenario, seed, plant, control, tick));
        run = Optional.of(context);
        MDC.put(MDC_RUN_ID, context.runId()); // los logs del hilo del motor llevan la corrida
        LOG.info("Corrida iniciada: escenario {}, semilla {}, x{}", scenario.id(), seed, r.speedFactor());
        // La telemetría de la corrida anterior ya no describe esta.
        view.set(new EngineView(
                status(), Optional.empty(), context.runner().machine().snapshot(), Optional.of(now)));
        speedFactor = r.speedFactor();
        paused = false;
        ControlStatus initial = context.runner().machine().snapshot().control();
        publishEvent(
                context,
                new DomainEvent.RunStarted(
                        scenario.id(),
                        seed,
                        initial.mode(),
                        speedFactor,
                        new DomainEvent.RunParameters(
                                initial.setpointC(), initial.kp(), initial.ki(), initial.kd(), initial.beltPwm())));
        return context.runId();
    }

    private void control(EngineRequest.Control r) {
        switch (r.action()) {
            case PAUSE -> paused = true;
            case RESUME -> paused = activeRun().isEmpty();
            case STOP -> activeRun().ifPresent(c -> endRun(c, DomainEvent.RunEndReason.STOPPED));
            case SET_SPEED -> speedFactor = r.speedFactor().orElse(speedFactor);
        }
    }

    private void advance(int ticks) {
        RunContext context = run.orElseThrow();
        for (int i = 0; i < ticks && !context.ended(); i++) {
            long started = System.nanoTime();
            ScanResult result = context.runner().step(now.toEpochMilli());
            metrics.tick(System.nanoTime() - started);
            result.events().forEach(event -> publishEvent(context, event));
            if (result.snapshot().simTime().ms() % telemetryPeriodMs == 0) {
                TelemetryFrame frame = assembler.telemetry(
                        context.runId(), context.startedAt(), context, speedFactor, result.snapshot(), now);
                publisher.publishTelemetry(frame);
                view.set(new EngineView(
                        status(), Optional.of(frame), result.snapshot(), Optional.of(context.startedAt())));
            }
            if (context.runner().finished()) {
                endRun(context, DomainEvent.RunEndReason.COMPLETED);
            }
        }
    }

    private void endRun(RunContext context, DomainEvent.RunEndReason reason) {
        publishEvent(context, new DomainEvent.RunEnded(reason));
        context.end();
        paused = true;
        LOG.info("Corrida terminada: {}", reason);
        MDC.remove(MDC_RUN_ID);
    }

    private void publishEvent(RunContext context, DomainEvent event) {
        long simMs = context.runner().now().ms();
        publisher.publishEvent(assembler.event(context.runId(), context.startedAt(), context, event, simMs, now));
    }

    private void publishView() {
        EngineView current = view.get();
        MachineSnapshot snapshot = run.map(c -> c.runner().machine().snapshot()).orElse(current.snapshot());
        view.set(new EngineView(status(), current.latest(), snapshot, run.map(RunContext::startedAt)));
    }

    private Optional<RunContext> activeRun() {
        return run.filter(c -> !c.ended());
    }

    private boolean isRunning() {
        return !paused && activeRun().isPresent();
    }

    private SimulationStatus status() {
        SimulationStatus.Status status = run.isEmpty()
                ? SimulationStatus.Status.IDLE
                : run.get().ended()
                        ? SimulationStatus.Status.ENDED
                        : paused ? SimulationStatus.Status.PAUSED : SimulationStatus.Status.RUNNING;
        return new SimulationStatus(
                run.map(RunContext::runId),
                run.map(c -> c.runner().scenario().id()),
                status,
                speedFactor,
                run.map(c -> c.runner().now().ms()).orElse(0L));
    }

    private static Scenario withMode(Scenario s, ControlMode mode) {
        return new Scenario(
                s.id(),
                s.description(),
                s.seed(),
                s.durationSimS(),
                mode,
                s.initialConditions(),
                s.arrivals(),
                s.ambientProfile(),
                s.actions(),
                s.faults());
    }
}
