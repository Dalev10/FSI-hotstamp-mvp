package com.simex.twin.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.simex.twin.domain.control.thermal.ControlMode;
import com.simex.twin.domain.plant.sensing.ColdJunctionMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;

class TwinPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesConfig.class);

    @Test
    void bindsDefaultsFromApplicationYml() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            TwinProperties p = ctx.getBean(TwinProperties.class);
            assertThat(p.engine().tickMs()).isEqualTo(100);
            assertThat(p.telemetry().allowedOrigins()).containsExactly("*");
            assertThat(p.autostart().scenario()).isEqualTo("nominal");
            assertThat(p.sensing().coldJunction()).isEqualTo(ColdJunctionMode.AMBIENT_SENSOR);
            assertThat(p.control().defaultMode()).isEqualTo(ControlMode.PID);
            assertThat(p.conveyor().discardFaceFromM()).isEqualTo(-0.01);
            assertThat(p.commands().kp().max()).isEqualTo(60.0);
        });
    }

    @Test
    void emptyAutostartScenarioIsAllowed() {
        runner.withPropertyValues("twin.autostart.scenario=")
                .run(ctx -> assertThat(
                                ctx.getBean(TwinProperties.class).autostart().scenario())
                        .isEmpty());
    }

    @Test
    void environmentVariablesOverrideYml() {
        runner.withInitializer(ctx -> TestPropertyValues.of("TWIN_AUTOSTART_SPEEDFACTOR=5")
                        .applyTo(ctx.getEnvironment(), TestPropertyValues.Type.SYSTEM_ENVIRONMENT))
                .run(ctx -> assertThat(
                                ctx.getBean(TwinProperties.class).autostart().speedFactor())
                        .isEqualTo(5));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "twin.engine.tick-ms=0",
                "twin.engine.speed-factor=61",
                "twin.autostart.speed-factor=61",
                "twin.process.window-min-c=80",
                "twin.process.default-setpoint-c=80",
                "twin.thermal.heater-efficiency=1.5",
                "twin.sensing.open-tc-counts-threshold=5000",
                "twin.conveyor.station-at-m=0.5",
                "twin.conveyor.discard-face-to-m=-0.02",
                "twin.pneumatics.stroke-ms=1600",
                "twin.safety.over-temp-trip-c=70",
                "twin.commands.kp.min=70",
                "twin.commands.belt-pwm.max=0.3"
            })
    void rejectsInvalidConfiguration(String property) {
        runner.withPropertyValues(property)
                .run(ctx -> assertThat(ctx).getFailure().hasRootCauseInstanceOf(BindValidationException.class));
    }

    @EnableConfigurationProperties(TwinProperties.class)
    static class PropertiesConfig {}
}
