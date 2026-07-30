package com.smartlink.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Enforces the dependency rule from engineering-spec.md §3.3.
 *
 * <p>The rule matters for one concrete reason: the destination policy and short-code generation
 * carry the highest branch density in the system, and they are only cheap to test exhaustively
 * while they can run with no Spring context and no database. The moment a framework annotation
 * appears in {@code domain}, those tests need a container, they get slower, and they stop being run
 * on every save.
 *
 * <p>T4 is where that pressure actually arrives. These rules exist now, before there is any domain
 * code to violate them, so the first violation fails a build rather than passing a review.
 *
 * <p><strong>Known limitation, stated rather than hidden.</strong> Until T4 creates the first
 * {@code domain} class these rules match nothing and pass vacuously. ArchUnit rejects that by
 * default, and {@code allowEmptyShould(true)} is what suppresses the objection. That is the same
 * class of hole as the coverage gate skipping on an empty suite (risk R-5): a gate reporting green
 * without looking. It is tolerable only because there is no domain code to check yet. From T4
 * onward these rules are load-bearing, and if the domain package is ever emptied again the
 * suppression must be removed rather than left to hide the regression.
 */
class LayeringTest {

  private static final String BASE = "com.smartlink";

  private static final JavaClasses PRODUCTION_CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages(BASE);

  @Test
  @DisplayName("domain imports no framework: dependency inversion holds at the innermost layer")
  void domainIsFrameworkFree() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework..",
                "jakarta.persistence..",
                "jakarta.servlet..",
                "com.fasterxml.jackson..",
                "org.hibernate..",
                "org.flywaydb..")
            .because(
                "domain holds the rules worth testing exhaustively; it must stay runnable "
                    + "without a Spring context or a database (engineering-spec §3.3, §4)");

    rule.allowEmptyShould(true).check(PRODUCTION_CLASSES);
  }

  @Test
  @DisplayName("domain performs no I/O: DNS and persistence are reached through ports")
  void domainPerformsNoIo() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("java.sql..", "javax.sql..", "java.net.http..")
            .because(
                "the destination policy must be provable with a stubbed resolver and no "
                    + "network, so DNS is reached through a domain-owned port (T4, NFR-15)");

    rule.allowEmptyShould(true).check(PRODUCTION_CLASSES);
  }

  @Test
  @DisplayName("layer dependencies run inward only")
  void layerDependenciesRunInwardOnly() {
    ArchRule rule =
        layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("api")
            .definedBy("..api..")
            .layer("application")
            .definedBy("..application..")
            .layer("domain")
            .definedBy("..domain..")
            .layer("infrastructure")
            .definedBy("..infrastructure..")
            // api is the outermost layer, so nothing may depend on it.
            .whereLayer("api")
            .mayNotBeAccessedByAnyLayer()
            // infrastructure implements domain ports; nothing depends on it directly.
            .whereLayer("infrastructure")
            .mayNotBeAccessedByAnyLayer()
            .whereLayer("application")
            .mayOnlyBeAccessedByLayers("api")
            // Everything may depend on domain. That is the point of it.
            .allowEmptyShould(true)
            .because("engineering-spec §3.3: api → application → domain ← infrastructure");

    rule.check(PRODUCTION_CLASSES);
  }

  @Test
  @DisplayName("no node-local mutable state on the request path (NFR-06)")
  void noStaticMutableState() {
    // Written first against CLASS modifiers, which was simply wrong - it read as "no static
    // non-final classes" and duly flagged a nested sealed interface, since interfaces are
    // never final. The mistake was invisible while the domain package was empty and the rule
    // matched nothing, which is exactly the hazard recorded in the class Javadoc above: a
    // rule that checks nothing cannot be observed to be checking the wrong thing.
    ArchRule rule =
        noFields()
            .that()
            .areStatic()
            .and()
            .areNotFinal()
            .should()
            .beDeclaredInClassesThat()
            .resideInAPackage(BASE + "..")
            .because(
                "horizontal scaling under NFR-06 is only free while any instance can serve "
                    + "any request; node-local mutable state is what quietly removes that");

    rule.allowEmptyShould(true).check(PRODUCTION_CLASSES);
  }
}
