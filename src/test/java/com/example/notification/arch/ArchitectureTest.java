package com.example.notification.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Executable architecture rules; see docs/adr/. */
@AnalyzeClasses(packages = "com.example.notification", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String ROOT = "com.example.notification";

    @ArchTest
    static final ArchRule modelDependsOnNothingElse = noClasses().that().resideInAPackage(ROOT + ".model..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    ROOT + ".api..", ROOT + ".service..", ROOT + ".messaging..", ROOT + ".sender..", ROOT + ".security..", ROOT + ".webhook..", ROOT + ".template..");

    @ArchTest
    static final ArchRule sendersAreProviderAdaptersOnly = noClasses().that().resideInAPackage(ROOT + ".sender..")
            .should().dependOnClassesThat().resideInAnyPackage(ROOT + ".api..", ROOT + ".messaging..", ROOT + ".service..", ROOT + ".webhook..", ROOT + ".security..");

    @ArchTest
    static final ArchRule nothingBelowTheApiLayerCallsControllers = noClasses()
            .that().resideInAnyPackage(ROOT + ".service..", ROOT + ".messaging..", ROOT + ".sender..", ROOT + ".webhook..", ROOT + ".template..", ROOT + ".model..")
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Controller");

    @ArchTest
    static final ArchRule controllersLiveInApiAndAreNamedAccordingly = classes()
            .that().areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
            .should().resideInAPackage(ROOT + ".api..").andShould().haveSimpleNameEndingWith("Controller");

    @ArchTest
    static final ArchRule controllersDoNotTouchRepositoriesDirectly = noClasses()
            .that().resideInAPackage(ROOT + ".api..")
            .should().dependOnClassesThat().areAssignableTo(org.springframework.data.repository.Repository.class);

    @ArchTest
    static final ArchRule noFieldInjection = fields()
            .should().notBeAnnotatedWith(org.springframework.beans.factory.annotation.Autowired.class)
            .because("use constructor injection");

    @ArchTest
    static final ArchRule noJavaUtilLogging = noClasses().that().resideInAPackage(ROOT + "..")
            .should().dependOnClassesThat().resideInAPackage("java.util.logging..")
            .because("the project logs through SLF4J");

    @ArchTest
    static final ArchRule noStandardStreams = com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
}
