package com.cloudradar.processor;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architectural constraints for the processor service.
 * Ref: https://github.com/ClementV78/CloudRadar/issues/557
 */
@AnalyzeClasses(packages = "com.cloudradar.processor", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    // RedisAggregateProcessor uses ConcurrentHashMap for metric counters.
    // Tracked: https://github.com/ClementV78/CloudRadar/issues/558
    // Will be fixed by ProcessorMetrics extraction (PR 3 of refactoring plan).
    @ArchTest
    static final ArchRule service_classes_should_not_use_concurrent_hash_map =
        noClasses()
            .that().resideInAPackage("..service..")
            .and().areAnnotatedWith(org.springframework.stereotype.Component.class)
            .and().doNotHaveSimpleName("RedisAggregateProcessor")
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
