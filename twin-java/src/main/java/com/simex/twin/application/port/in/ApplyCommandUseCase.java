package com.simex.twin.application.port.in;

import com.simex.twin.application.engine.EngineRequest.CommandOutcome;
import com.simex.twin.domain.control.command.IssuedCommand;
import java.util.concurrent.CompletableFuture;

/** Escritura: un comando para la máquina. Se completa cuando el motor lo aplicó (o lo rechazó). */
public interface ApplyCommandUseCase {

    CompletableFuture<CommandOutcome> apply(IssuedCommand command);
}
