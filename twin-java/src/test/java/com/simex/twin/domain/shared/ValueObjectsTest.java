package com.simex.twin.domain.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ValueObjectsTest {

    @ParameterizedTest
    @ValueSource(doubles = {-0.001, 100.001, Double.NaN, Double.POSITIVE_INFINITY})
    void percentRejectsValuesOutsideZeroToHundred(double value) {
        assertThatThrownBy(() -> new Percent(value)).isInstanceOf(DomainException.class);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.001, 1.001, Double.NaN, Double.NEGATIVE_INFINITY})
    void dutyCycleRejectsValuesOutsideZeroToOne(double value) {
        assertThatThrownBy(() -> new DutyCycle(value)).isInstanceOf(DomainException.class);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-273.16, Double.NaN, Double.POSITIVE_INFINITY})
    void celsiusRejectsNonPhysicalTemperatures(double value) {
        assertThatThrownBy(() -> new Celsius(value)).isInstanceOf(DomainException.class);
    }

    @Test
    void boundariesAreAccepted() {
        assertThatCode(() -> {
                    new Percent(0);
                    new Percent(100);
                    new DutyCycle(0);
                    new DutyCycle(1);
                    new Celsius(Celsius.ABSOLUTE_ZERO_C);
                })
                .doesNotThrowAnyException();
    }

    @Test
    void negativeZeroEqualsZero() {
        assertThat(new DutyCycle(-0.0)).isEqualTo(DutyCycle.ZERO);
        assertThat(new Percent(-0.0)).isEqualTo(new Percent(0));
        assertThat(new Celsius(-0.0)).isEqualTo(new Celsius(0));
    }

    @Test
    void percentAndDutyCycleConvertBothWays() {
        assertThat(new Percent(23).toDutyCycle()).isEqualTo(new DutyCycle(0.23));
        assertThat(new Percent(100).toDutyCycle()).isEqualTo(new DutyCycle(1));
        assertThat(new DutyCycle(0.5).toPercent()).isEqualTo(new Percent(50));
    }
}
