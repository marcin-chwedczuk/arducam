package pl.marcinchwedczuk.template.domain.protocol;

import java.util.List;
import java.util.stream.Collectors;

/** Result of {@code MEMTEST} or {@code CAMTEST}. */
public record TestReport(String name, boolean passed, List<ResponseLine> tests) {
    public static TestReport from(String name, CommandResult result) {
        return new TestReport(name,
                "pass".equals(result.finalLine().param("result")),
                result.linesOfType(ResponseLine.Type.TEST));
    }

    /** Human readable summary, one sub-test per line. */
    public String summary() {
        String details = tests.stream()
                .map(t -> String.join(" ", t.tokens().subList(1, t.tokens().size())))
                .collect(Collectors.joining("\n"));
        return String.format("%s: %s%n%n%s", name, passed ? "PASS" : "FAIL", details);
    }
}
