package se.swedishpolls;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

class ArchitectureTest {
  private static final JavaClasses PRODUCTION =
      new ClassFileImporter().importPath("target/classes");

  @Test
  void packagesHaveNoCycles() {
    slices().matching("se.swedishpolls.(**)").should().beFreeOfCycles().check(PRODUCTION);
  }

  @Test
  void importContainsProductionOnly() {
    assertTrue(PRODUCTION.contain(Application.class));
    assertTrue(PRODUCTION.contain("se.swedishpolls.web.controller.ApiV1Controller"));
    assertTrue(PRODUCTION.contain("se.swedishpolls.estimation.DailyStateSpace"));
    assertFalse(PRODUCTION.contain(ArchitectureTest.class));
    assertFalse(PRODUCTION.contain("se.swedishpolls.testsupport.TestDatabase"));
  }

  @Test
  void rootContainsOnlyTheApplicationAndHasNoInboundDependencies() {
    classes()
        .that()
        .resideInAPackage("se.swedishpolls")
        .should()
        .haveFullyQualifiedName("se.swedishpolls.Application")
        .check(PRODUCTION);
    noClasses()
        .that()
        .resideOutsideOfPackage("se.swedishpolls")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("se.swedishpolls")
        .check(PRODUCTION);
  }

  @Test
  void responsibilitiesDependDownward() {
    noClasses()
        .that()
        .resideInAPackage("se.swedishpolls.source..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "se.swedishpolls.estimation..",
            "se.swedishpolls.publication..",
            "se.swedishpolls.web..")
        .check(PRODUCTION);
    noClasses()
        .that()
        .resideInAPackage("se.swedishpolls.estimation..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "se.swedishpolls.publication..", "se.swedishpolls.web..",
            "se.swedishpolls.source.service..", "se.swedishpolls.source.repository..")
        .check(PRODUCTION);
    noClasses()
        .that()
        .resideInAPackage("se.swedishpolls.publication..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("se.swedishpolls.web..")
        .check(PRODUCTION);
    classes()
        .that()
        .resideInAPackage("se.swedishpolls.model..")
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage("se.swedishpolls.model..", "java..", "org.ejml..")
        .check(PRODUCTION);
  }

  @Test
  void servicesAndRepositoriesDoNotDependOnTheirCallers() {
    noClasses()
        .that()
        .resideInAPackage("..service..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..controller..")
        .check(PRODUCTION);
    noClasses()
        .that()
        .resideInAPackage("..repository..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..controller..", "..service..")
        .check(PRODUCTION);
  }

  @Test
  void jdbcBelongsToRepositoriesOrConfiguration() {
    noClasses()
        .that()
        .resideOutsideOfPackage("..repository..")
        .and()
        .areNotAnnotatedWith(Configuration.class)
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("java.sql..", "javax.sql..", "org.springframework.jdbc..")
        .check(PRODUCTION);
  }

  @Test
  void calculationsAndValuesStayPlain() {
    noClasses()
        .that()
        .resideInAnyPackage(
            "se.swedishpolls.estimation..", "se.swedishpolls.model..",
            "se.swedishpolls.source", "se.swedishpolls.publication")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework..",
            "java.sql..",
            "javax.sql..",
            "java.net.http..",
            "org.apache.hc..",
            "okhttp3..",
            "se.swedishpolls.source.service..",
            "se.swedishpolls.source.repository..",
            "se.swedishpolls.publication.service..",
            "se.swedishpolls.publication.repository..")
        .check(PRODUCTION);
  }

  @Test
  void webUsesServicesInsteadOfStorageOrFitting() {
    noClasses()
        .that()
        .resideInAPackage("se.swedishpolls.web..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..repository..")
        .check(PRODUCTION);
    noClasses()
        .that()
        .resideInAPackage("se.swedishpolls.web.controller..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("se.swedishpolls.estimation..")
        .check(PRODUCTION);
    noClasses()
        .that()
        .resideInAPackage("se.swedishpolls.web.controller..")
        .should()
        .dependOnClassesThat()
        .haveNameMatching(
            "java\\.nio\\.file\\..*|java\\.io\\.(File.*|RandomAccessFile)"
                + "|java\\.nio\\.channels\\.(FileChannel|AsynchronousFileChannel)"
                + "|org\\.springframework\\.core\\.io\\.(FileSystemResource|PathResource)")
        .check(PRODUCTION);
  }

  @Test
  void scheduledEntryPointsCallServices() {
    methods()
        .that()
        .areAnnotatedWith(Scheduled.class)
        .should()
        .beDeclaredInClassesThat()
        .resideInAPackage("..service..")
        .andShould(
            new ArchCondition<JavaMethod>("call one application service, read outcomes and log") {
              @Override
              public void check(JavaMethod method, ConditionEvents events) {
                int serviceCalls = 0;
                for (JavaMethodCall call : method.getMethodCallsFromSelf()) {
                  final JavaClass target = call.getTargetOwner();
                  final boolean service =
                      target.getPackageName().endsWith(".service")
                          && (target.isAnnotatedWith(Component.class)
                              || target.isMetaAnnotatedWith(Component.class))
                          && !target.isAnnotatedWith(Configuration.class)
                          && target.getMethods().stream()
                              .noneMatch(candidate -> candidate.isAnnotatedWith(Scheduled.class));
                  if (service) {
                    serviceCalls++;
                  }
                  final boolean accessor =
                      call.getTarget().getRawParameterTypes().isEmpty()
                          && ((target.isRecord()
                                  && target.getFields().stream()
                                      .anyMatch(
                                          field ->
                                              field.getName().equals(call.getTarget().getName())))
                              || (target.isEnum()
                                  && (call.getName().equals("ordinal")
                                      || call.getName().equals("name"))));
                  final boolean allowed =
                      service
                          || accessor
                          || target.isEquivalentTo(org.slf4j.Logger.class)
                          || target.isEquivalentTo(org.slf4j.LoggerFactory.class);
                  events.add(new SimpleConditionEvent(call, allowed, call.getDescription()));
                }
                events.add(
                    new SimpleConditionEvent(
                        method,
                        serviceCalls == 1,
                        method.getFullName() + " must call exactly one application service"));
                events.add(
                    new SimpleConditionEvent(
                        method,
                        method.getConstructorCallsFromSelf().isEmpty(),
                        method.getFullName() + " must delegate construction to its service"));
              }
            })
        .check(PRODUCTION);
  }
}
