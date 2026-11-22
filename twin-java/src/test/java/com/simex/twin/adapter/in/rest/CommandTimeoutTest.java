package com.simex.twin.adapter.in.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simex.twin.application.port.in.ApplyCommandUseCase;
import com.simex.twin.application.port.in.HeartbeatUseCase;
import com.simex.twin.application.port.in.QueryStateUseCase;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Si el motor no responde dentro de {@code apply-timeout-ms}, el comando recibe 503. */
@WebMvcTest(controllers = CommandController.class, properties = "twin.commands.apply-timeout-ms=50")
class CommandTimeoutTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ApplyCommandUseCase commands;

    @MockitoBean
    HeartbeatUseCase heartbeats;

    // Lo usa RunIdMdcFilter, que el slice web también carga.
    @MockitoBean(answers = Answers.RETURNS_DEEP_STUBS)
    QueryStateUseCase query;

    @Test
    void anEngineThatDoesNotAnswerGives503() throws Exception {
        when(commands.apply(any())).thenReturn(new CompletableFuture<>());

        mvc.perform(post("/api/twin/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandId\":\"7f9c2b1e-4d3a-4b8c-9e2f-1a0b3c4d5e6f\","
                                + "\"type\":\"START\",\"issuedBy\":\"dashboard\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("El motor no respondió en 50 ms"));
    }
}
