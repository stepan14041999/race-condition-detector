# Contributing to Race Condition Detector

Thank you for your interest in contributing! This document provides guidelines and information for contributors.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [Development Setup](#development-setup)
- [Project Structure](#project-structure)
- [Coding Guidelines](#coding-guidelines)
- [Testing](#testing)
- [Submitting Changes](#submitting-changes)

## Code of Conduct

Be respectful, constructive, and professional. We're here to build great software together.

## Getting Started

1. **Fork the repository** on GitHub
2. **Clone your fork** locally:
   ```bash
   git clone https://github.com/YOUR_USERNAME/thread-checker.git
   cd thread-checker
   ```
3. **Create a branch** for your changes:
   ```bash
   git checkout -b feature/your-feature-name
   ```

## Development Setup

### Requirements

- **JDK 17+** (OpenJDK or Oracle JDK)
- **Gradle 8.5+** (wrapper included)
- **IntelliJ IDEA 2024.1+** (Community or Ultimate)

### Building

```bash
./gradlew build
```

### Running in Development Mode

Launch a sandbox IntelliJ IDEA instance with the plugin installed:

```bash
./gradlew runIde
```

### Running Tests

```bash
# Run all tests
./gradlew test

# Run specific test
./gradlew test --tests "RaceConditionInspectionTest"

# Run with --no-build-cache if signatures changed
./gradlew test --no-build-cache
```

### Building Plugin Distribution

```bash
./gradlew buildPlugin
```

The `.zip` will be in `build/distributions/`.

## Project Structure

```
thread-checker/
├── src/
│   ├── main/
│   │   ├── kotlin/com/racedetector/
│   │   │   ├── analysis/        # Field Access Analyzer
│   │   │   ├── threading/       # Thread Context Resolver
│   │   │   ├── sync/            # Synchronization Checker
│   │   │   ├── folia/           # Folia Context Analyzer
│   │   │   ├── inspections/     # Inspection implementations
│   │   │   ├── quickfixes/      # Quick-fix implementations
│   │   │   └── settings/        # Plugin settings
│   │   └── resources/
│   │       ├── META-INF/plugin.xml
│   │       └── inspectionDescriptions/  # HTML docs
│   └── test/
│       └── kotlin/com/racedetector/
│           ├── inspections/     # Inspection tests
│           └── performance/     # Performance tests
├── docs/                        # Documentation
├── CLAUDE.md                    # Architecture & technical docs
├── README.md
└── build.gradle.kts
```

## Coding Guidelines

### Language & Style

- **Kotlin** for plugin code (not Java)
- Use **UAST** for code analysis (not raw PSI) — ensures Java + Kotlin support
- Follow **Kotlin coding conventions**: 4-space indent, no trailing whitespace
- Use meaningful variable names (avoid abbreviations except for common ones like `psi`, `uast`)

### Architecture

All inspections follow this pattern:

1. **Extend `AbstractBaseUastLocalInspectionTool`**
2. **Override `buildVisitor()`** to return a UAST visitor
3. **Use `UastHintedVisitorAdapter.create()`** for performance
4. **Call `holder.registerProblem()`** when issue detected
5. **Provide quick-fixes** as constructor parameters

### Example Inspection

```kotlin
class MyInspection : AbstractBaseUastLocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return UastHintedVisitorAdapter.create(
            holder.file.language,
            object : AbstractUastNonRecursiveVisitor() {
                override fun visitField(node: UField): Boolean {
                    // Analysis logic here
                    if (isProblematic(node)) {
                        holder.registerProblem(
                            node.sourcePsi!!,
                            "Problem description",
                            MyQuickFix()
                        )
                    }
                    return true
                }
            },
            arrayOf(UField::class.java),
            true
        )
    }
}
```

### Performance

- **Always include `ProgressManager.checkCanceled()`** in long loops
- Use **caching** via `CachedValuesManager` for expensive computations
- Prefer **non-recursive visitors** when possible
- **Don't traverse entire codebase** — limit to current file/class

### False Positives

- Check `FalsePositiveFilter.shouldSkipField()` for common patterns
- Use `FalsePositiveFilter.shouldReduceSeverity()` for uncertain cases
- Skip test classes: `FalsePositiveFilter.isInTestClass()`

### Localization

Currently, all strings are in English. Localization is planned for future releases.

## Testing

### Writing Tests

Tests use `LightJavaCodeInsightFixtureTestCase`:

```kotlin
class MyInspectionTest : LightJavaCodeInsightFixtureTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(MyInspection())
    }

    fun `test detects issue`() {
        myFixture.configureByText("Test.java", """
            public class Test {
                private int <warning>count</warning>;  // Expected warning
            }
        """.trimIndent())
        myFixture.checkHighlighting()
    }

    fun `test quick fix`() {
        myFixture.configureByText("Test.java", "...")
        val fix = myFixture.getAllQuickFixes().first()
        myFixture.launchAction(fix)
        myFixture.checkResult("...")  // Expected result after fix
    }
}
```

### Test Data

- **Inline test data** preferred (using `configureByText`)
- For complex tests, use `testData/` directory

### Coverage

- Add tests for **new inspections**
- Add tests for **quick-fixes**
- Add tests for **edge cases** (Kotlin, Java, nested classes, etc.)

## Submitting Changes

### Before Submitting

1. **Run tests**: `./gradlew test`
2. **Build plugin**: `./gradlew buildPlugin`
3. **Format code**: Ensure consistent style
4. **Update docs**: If adding new inspections, update:
   - `README.md`
   - `docs/INSPECTIONS.md`
   - HTML description in `inspectionDescriptions/`

### Pull Request Process

1. **Push to your fork**:
   ```bash
   git push origin feature/your-feature-name
   ```

2. **Create Pull Request** on GitHub with:
   - Clear title: "Add inspection for X" or "Fix Y issue"
   - Description of what changed and why
   - Link to related issue (if any)
   - Screenshots/examples (if applicable)

3. **CI checks** will run automatically — ensure they pass

4. **Code review** — address feedback from maintainers

5. **Squash commits** if requested before merge

### Commit Messages

Use clear, descriptive commit messages:

```
Add inspection for thread-unsafe lazy initialization

- Detects double-checked locking without volatile
- Provides quick-fix to add volatile modifier
- Includes tests for Java and Kotlin

Fixes #123
```

## Feature Requests & Bug Reports

- **Search existing issues** before creating new ones
- Use **issue templates** when available
- Provide **minimal reproducible examples** for bugs
- Include **IntelliJ version** and **plugin version**

## Documentation

When adding new inspections:

1. **HTML description** in `src/main/resources/inspectionDescriptions/<ShortName>.html`
2. **README.md** — add to feature list
3. **docs/INSPECTIONS.md** — add full reference entry
4. **plugin.xml** — register the inspection

## Questions?

- Check [CLAUDE.md](CLAUDE.md) for architecture details
- Check [docs/](docs/) for additional documentation
- Open a GitHub issue with the "question" label

---

Thank you for contributing! 🎉
