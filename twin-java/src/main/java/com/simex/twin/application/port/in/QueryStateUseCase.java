package com.simex.twin.application.port.in;

import com.simex.twin.application.engine.SimulationEngine.EngineView;
import com.simex.twin.domain.control.ControlParameters;

/** Lectura sin bloquear (CQS): la última vista publicada por el motor. */
public interface QueryStateUseCase {

    EngineView view();

    /** Límites de comandos y ventana de proceso, para que el agente se limite solo. */
    ControlParameters controlParameters();
}
