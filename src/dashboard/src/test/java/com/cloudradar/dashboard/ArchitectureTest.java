package com.cloudradar.dashboard;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architectural constraints for the dashboard service.
 * Ref: https://github.com/ClementV78/CloudRadar/issues/557
 */
@AnalyzeClasses(packages = "com.cloudradar.dashboard", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    // FlightUpdateStreamService uses ConcurrentHashMap for SSE emitters (legitimate).
    // Exclude it from this rule.
    @ArchTest
    static final ArchRule service_classes_should_not_use_concurrent_hash_map =
        noClasses()
            .that().resideInAPackage("..service..")
            .and().areAnnotatedWith(org.springframework.stereotype.Component.class)
            .or().areAnnotatedWith(org.springframework.stereotype.Service.class)
            .and().doNotHaveSimpleName("FlightUpdateStreamService")
            .should().accessClassesThat().haveFullyQualifiedName("java.util.concurrent.ConcurrentHashMap")
            .allowEmptyShould(true)
            .because("@Component classes should delegate metric tracking to a dedicated collaborator (AGENTS.md §9)");

    @ArchTest
    static final ArchRule config_classes_should_not_depend_on_services =
        noClasses()
            .that().resideInAPackage("..config..")
            .should().dependOnClassesThat().resideInAPackage("..service..")
            .because("Configuration classes must not create circular dependencies with service classes");
}
