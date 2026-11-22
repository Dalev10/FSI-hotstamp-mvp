package com.simex.twin.domain.control.thermal;

import com.simex.twin.domain.shared.Percent;

/** Estado transferible entre controladores: la salida vigente, para cambiar de estrategia sin salto. */
public record ControllerState(Percent output) {}
