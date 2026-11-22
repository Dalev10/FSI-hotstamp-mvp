package com.simex.twin.domain.machine;

import com.simex.twin.domain.event.DomainEvent;
import java.util.List;

/** Resultado de un scan: la foto y los eventos del tick, en orden. */
public record ScanResult(MachineSnapshot snapshot, List<DomainEvent> events) {}
