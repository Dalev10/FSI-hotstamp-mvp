package com.simex.twin.adapter.in.rest;

import com.simex.twin.adapter.contract.ContractMapper;
import com.simex.twin.adapter.contract.ContractMessages.StateMessage;
import com.simex.twin.adapter.in.rest.dto.RestDtos;
import com.simex.twin.application.port.in.QueryStateUseCase;
import com.simex.twin.application.port.out.WallClock;
import com.simex.twin.domain.control.ControlParameters;
import com.simex.twin.domain.control.command.CommandLimits;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Lecturas sin bloquear: la última vista del motor y los límites de comandos. */
@RestController
@RequestMapping("/api/twin")
public class StateController {

    private final QueryStateUseCase query;
    private final WallClock clock;
    private final ContractMapper mapper = new ContractMapper();

    public StateController(QueryStateUseCase query, WallClock clock) {
        this.query = query;
        this.clock = clock;
    }

    @GetMapping("/state")
    public StateMessage state(@RequestParam(defaultValue = "false") boolean groundTruth) {
        return mapper.state(query.view(), groundTruth, clock.now());
    }

    @GetMapping("/limits")
    public RestDtos.Limits limits() {
        ControlParameters p = query.controlParameters();
        CommandLimits l = p.commandLimits();
        return new RestDtos.Limits(
                new RestDtos.Process(
                        p.process().windowMinC(),
                        p.process().windowMaxC(),
                        p.process().defaultSetpointC()),
                l.setpointMaxStepC(),
                l.pidMaxStepFraction(),
                new RestDtos.Range(l.kp().min(), l.kp().max()),
                new RestDtos.Range(l.ki().min(), l.ki().max()),
                new RestDtos.Range(l.kd().min(), l.kd().max()),
                new RestDtos.BeltPwm(l.beltPwm().min(), l.beltPwm().max(), l.beltPwmMaxStep()));
    }
}
