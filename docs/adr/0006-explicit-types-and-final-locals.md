# Java uses explicit types and final local variables

Java source uses explicit local variable types. A local variable that is not reassigned is
declared `final`. The rule applies equally to main and test sources.

The `final` rule covers ordinary local declarations, including try-with-resources variables. It
does not cover method or constructor parameters, catch parameters, lambda parameters, or
enhanced-`for` variables. Those values already receive a fresh binding for each call, catch, or
iteration, and marking them does not protect a local that persists through the surrounding
method. Traditional `for` initializers remain ordinary locals; a loop counter is non-final
because the loop reassigns it.

PMD 7.17.0 enforces the decision during Maven `verify` through `UseExplicitTypes`,
`LocalVariableCouldBeFinal`, and the project XPath rule `FinalTryResource`. The stock final-local
rule reports every ordinary local that can be `final`, so a surviving non-final local must be
reassigned. Java treats resource variables as implicitly final, which prevents that stock rule
from requiring the keyword; the XPath rule checks their explicit modifiers. All findings fail
the build. PMD scans main and test sources.

An explicit type may be impossible to write when `var` captures an anonymous class or another
non-denotable inferred type. Such a declaration may retain `var` only with a narrow
`@SuppressWarnings("PMD.UseExplicitTypes")` and a comment naming the non-denotable type. There
are no escapes in the tree when this decision is recorded.

## Considered options

- Error Prone 2.50.0's `Var` check: rejected. It asks for `@Var` on reassigned variables and
  discourages the `final` keyword. It also does not prohibit Java local-variable type inference.
- Checkstyle: rejected. `FinalLocalVariable` covers the immutability half, but PMD supplies both
  required rules in one tool.
- A project-specific compiler or text check for the whole rule: rejected. Correctly
  distinguishing local variables, fields, parameters, inferred lambda parameters, comments, and
  non-denotable types requires a Java parser that the project would then own. The retained XPath
  only closes PMD's try-resource gap and uses PMD's existing Java syntax tree.
- Agent instructions without a build gate: rejected. The existing split style shows that an
  unenforced rule drifts.
- Spotless and SpotBugs: rejected as enforcement mechanisms. Spotless formats syntax without
  changing these declarations, while SpotBugs analyzes bytecode after the source-level choice is
  gone.

ADR 0005 rejected PMD when its general source rules only overlapped Error Prone. These
source-style rules do not exist in the current Error Prone, Spotless, or SpotBugs gates, so the
new evidence changes that decision narrowly. No unrelated PMD rules are enabled.

## Consequences

`./mvnw verify` now fails when `var` remains or when a local can be declared `final`. PMD has no
safe automatic fix for inferred types, so violations are fixed in source and checked with
`./mvnw pmd:check`. Reassigned locals remain mutable without annotations or suppression. A future
decision to cover parameters or enhanced-`for` variables can change the PMD rule properties
without replacing the gate.
