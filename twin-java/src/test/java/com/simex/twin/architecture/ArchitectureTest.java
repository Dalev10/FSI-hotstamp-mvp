package com.simex.twin.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.alwaysTrue;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Random;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;

/** Reglas de la sección 9.3 del plan. Solo analiza código de producción. */
@AnalyzeClasses(packages = "com.simex.twin", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String DOMAIN = "com.simex.twin.domain..";
    private static final String APPLICATION = "com.simex.twin.application..";
    private static final String ADAPTER = "com.simex.twin.adapter..";
    private static final String CONTRACT = "com.simex.twin.adapter.contract..";
    private static final String CONFIG = "com.simex.twin.config..";

    private static final DescribedPredicate<JavaAccess<?>> NON_DETERMINISTIC_ACCESS = access(
                    System.class, "currentTimeMillis")
            .or(access(System.class, "nanoTime"))
            .or(access(Instant.class, "now"))
            .or(access(LocalDateTime.class, "now"))
            .or(access(Math.class, "random"))
            .or(access(Thread.class, "sleep"));

    @ArchTest
    static final ArchRule r1_domain_is_pure_java = noClasses()
            .that()
            .resideInAPackage(DOMAIN)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework..",
                    "tools.jackson..",
                    "com.fasterxml..",
                    "jakarta..",
                    APPLICATION,
                    ADAPTER,
                    CONFIG)
            .allowEmptyShould(true);

    // Extendida a Jackson y Jakarta según las secciones 8.D y 13.1 del plan.
    @ArchTest
    static final ArchRule r2_application_is_framework_free = noClasses()
            .that()
            .resideInAPackage(APPLICATION)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework..", "tools.jackson..", "com.fasterxml..", "jakarta..", ADAPTER, CONFIG)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule r3_inbound_and_outbound_adapters_are_independent = slices().matching(
                    "com.simex.twin.adapter.(*)..")
            .should()
            .notDependOnEachOther()
            // adapter.contract es el formato JSON que comparten REST, WebSocket y los escenarios.
            .ignoreDependency(alwaysTrue(), resideInAPackage(CONTRACT))
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule r4a_control_does_not_know_plant = noClasses()
            .that()
            .resideInAPackage("com.simex.twin.domain.control..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.simex.twin.domain.plant..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule r4b_plant_does_not_know_control = noClasses()
            .that()
            .resideInAPackage("com.simex.twin.domain.plant..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.simex.twin.domain.control..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule r5_domain_slices_are_free_of_cycles = slices().matching("com.simex.twin.domain.(*)..")
            .should()
            .beFreeOfCycles()
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule r6_domain_has_no_wall_clock_or_unseeded_randomness = noClasses()
            .that()
            .resideInAPackage(DOMAIN)
            .should()
            .accessTargetWhere(NON_DETERMINISTIC_ACCESS)
            .orShould()
            .dependOnClassesThat()
            .areAssignableTo(Random.class)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule r7a_only_rest_adapter_has_rest_controllers = noClasses()
            .that()
            .resideOutsideOfPackage("com.simex.twin.adapter.in.rest..")
            .should()
            .beAnnotatedWith(RestController.class)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule r7b_only_config_has_configuration_classes = noClasses()
            .that()
            .resideOutsideOfPackage(CONFIG)
            .should()
            .beAnnotatedWith(Configuration.class)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule r7c_only_config_declares_beans = noMethods()
            .that()
            .areDeclaredInClassesThat()
            .resideOutsideOfPackage(CONFIG)
            .should()
            .beAnnotatedWith(Bean.class)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule r8_contract_records_stay_in_adapters = noClasses()
            .that()
            .resideOutsideOfPackage(ADAPTER)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("com.simex.twin.adapter..dto..", "com.simex.twin.adapter..message..", CONTRACT)
            .allowEmptyShould(true);

    private static DescribedPredicate<JavaAccess<?>> access(Class<?> owner, String member) {
        return DescribedPredicate.describe(
                owner.getSimpleName() + "." + member,
                a -> a.getTargetOwner().isEquivalentTo(owner) && a.getName().equals(member));
    }
}
