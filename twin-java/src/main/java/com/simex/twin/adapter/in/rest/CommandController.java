package com.simex.twin.adapter.in.rest;

import com.simex.twin.adapter.contract.ContractJson;
import com.simex.twin.adapter.in.rest.dto.RestDtos.CommandRequest;
import com.simex.twin.adapter.in.rest.dto.RestDtos.CommandResultResponse;
import com.simex.twin.adapter.in.rest.dto.RestDtos.HeartbeatRequest;
import com.simex.twin.application.engine.EngineRequest.CommandOutcome;
import com.simex.twin.application.port.in.ApplyCommandUseCase;
import com.simex.twin.application.port.in.HeartbeatUseCase;
import com.simex.twin.domain.control.command.CommandResult;
import com.simex.twin.domain.control.command.IssuedCommand;
import com.simex.twin.domain.control.command.IssuedCommand.Issuer;
import jakarta.validation.Valid;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.MissingNode;

/** Comandos (202 aplicado, 409 rechazado, 503 sin respuesta del motor) y latidos del agente. */
@RestController
@RequestMapping("/api/twin/commands")
public class CommandController {

    static final String IDEMPOTENT_REPLAY = "Idempotent-Replay";

    private final ApplyCommandUseCase commands;
    private final HeartbeatUseCase heartbeats;
    private final long timeoutMs;

    public CommandController(
            ApplyCommandUseCase commands,
            HeartbeatUseCase heartbeats,
            @Value("${twin.commands.apply-timeout-ms}") long timeoutMs) {
        this.commands = commands;
        this.heartbeats = heartbeats;
        this.timeoutMs = timeoutMs;
    }

    @PostMapping
    public ResponseEntity<CommandResultResponse> apply(@Valid @RequestBody CommandRequest request) {
        IssuedCommand command = new IssuedCommand(
                Objects.requireNonNull(request.commandId()),
                Issuer.valueOf(Objects.requireNonNull(request.issuedBy()).toUpperCase(Locale.ROOT)),
                Optional.ofNullable(request.reason()),
                ContractJson.command(
                        Objects.requireNonNull(request.type()),
                        Objects.requireNonNullElse(request.params(), MissingNode.getInstance())));
        CommandOutcome outcome = EngineCalls.await(commands.apply(command), timeoutMs);
        ResponseEntity.BodyBuilder response = switch (outcome.result()) {
            case CommandResult.Applied a -> ResponseEntity.status(HttpStatus.ACCEPTED);
            case CommandResult.Rejected r -> ResponseEntity.status(HttpStatus.CONFLICT);
        };
        if (outcome.replay()) {
            response.header(IDEMPOTENT_REPLAY, "true");
        }
        return response.body(body(outcome.result()));
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<Void> heartbeat(@Valid @RequestBody HeartbeatRequest request) {
        heartbeats.heartbeat();
        return ResponseEntity.noContent().build();
    }

    private static CommandResultResponse body(CommandResult result) {
        return switch (result) {
            case CommandResult.Applied a ->
                new CommandResultResponse(
                        a.commandId(), "APPLIED", a.appliedAt().ms(), null, null);
            case CommandResult.Rejected r ->
                new CommandResultResponse(
                        r.commandId(), "REJECTED", null, r.rule().name(), r.detail());
        };
    }
}
