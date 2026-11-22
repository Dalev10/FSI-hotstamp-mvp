package com.simex.twin.adapter.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.simex.twin.application.engine.EngineRequest.Action;
import com.simex.twin.domain.control.command.CommandType;
import com.simex.twin.domain.control.thermal.ControlMode;
import com.simex.twin.domain.fault.FaultType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** Cuerpos de {@code openapi.yaml}. Los campos desconocidos se rechazan ({@code fail-on-unknown-properties}). */
public final class RestDtos {

    private static final String UUID = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    private RestDtos() {}

    // ---- peticiones (los @Nullable sin @NotNull son opcionales en el contrato) ----

    public record CommandRequest(
            @NotNull @Pattern(regexp = UUID) @Nullable String commandId,
            @NotNull @Nullable CommandType type,
            @Nullable JsonNode params,

            @NotNull @Pattern(regexp = "agent|dashboard") @Nullable
            String issuedBy,

            @Size(max = 500) @Nullable String reason) {}

    public record HeartbeatRequest(
            @NotNull @Size(min = 1, max = 100) @Nullable String agentId,
            @NotNull @PositiveOrZero @Nullable Long seq,
            @NotNull @Nullable Instant sentAt) {}

    public record StartRunRequest(
            @NotNull @Nullable String scenarioId,
            @Nullable Long seed,
            @NotNull @Min(1) @Nullable Integer speedFactor,
            @Nullable ControlMode controlMode) {}

    public record SimulationControlRequest(
            @NotNull @Nullable Action action,
            @Min(1) @Max(60) @Nullable Integer speedFactor) {}

    public record FaultRequest(
            @NotNull @Nullable FaultType type,
            @Nullable JsonNode params,
            @Positive @Nullable Double durationS) {}

    // ---- respuestas ----

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CommandResultResponse(
            String commandId,
            String status,
            @Nullable Long appliedAtSimMs,
            @Nullable String rule,
            @Nullable String detail) {}

    public record RunCreated(String runId) {}

    public record FaultCreated(String faultId) {}

    public record ScenarioSummary(String id, String description, double durationSimS) {}

    public record SimulationStatusResponse(
            @Nullable String runId, @Nullable String scenarioId, String status, int speedFactor, long simTimeMs) {}

    public record FaultCatalog(List<CatalogEntry> catalog, List<ActiveFault> active) {}

    public record CatalogEntry(String type, List<String> params, Map<String, Object> defaults) {}

    public record ActiveFault(
            String faultId,
            String type,
            Map<String, Object> params,
            long startAtSimMs,
            @Nullable Double durationS) {}

    public record Limits(
            Process process,
            double setpointMaxStepC,
            double pidMaxStepFraction,
            Range kp,
            Range ki,
            Range kd,
            BeltPwm beltPwm) {}

    public record Process(double windowMinC, double windowMaxC, double defaultSetpointC) {}

    public record Range(double min, double max) {}

    public record BeltPwm(double min, double max, double maxStep) {}
}
